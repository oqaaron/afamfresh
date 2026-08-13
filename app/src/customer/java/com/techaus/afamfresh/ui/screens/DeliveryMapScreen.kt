package com.techaus.afamfresh.ui.screens

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapsComposeExperimentalApi
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import com.techaus.afamfresh.ui.map.DropoffMarker
import com.techaus.afamfresh.ui.map.PickupMarker
import com.techaus.afamfresh.ui.map.rememberAfamFreshMapProperties
import com.techaus.afamfresh.ui.map.rememberAfamFreshMapUiSettings
import com.techaus.afamfresh.utils.GkmaBounds
import com.techaus.afamfresh.api.ApiClient
import com.techaus.afamfresh.config.DeliveryConfig
import com.techaus.afamfresh.repository.DeliveryRepository
import com.techaus.afamfresh.ui.theme.*
import com.techaus.afamfresh.utils.formatUgx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.*

// This screen does NOT price deliveries. The fee is tiered by order value and
// configured server-side, so it is obtained from api/calculate-delivery-fee.php
// via DeliveryRepository. config/DeliveryConfig.kt holds only the pickup
// coordinates used to draw the origin marker.

private const val TAG = "DeliveryMap"

// Signature confirmed exactly from MainScreen.kt's composable("delivery_map"):
//   onLocationSelected(pickupAddress, dropoffAddress, pickupLat, pickupLng,
//                      dropoffLat, dropoffLng, distanceKm, totalCost)
@OptIn(MapsComposeExperimentalApi::class)
@Composable
fun DeliveryMapScreen(
    onBack: () -> Unit,
    /**
     * Cart subtotal in UGX. Required, because the delivery fee is tiered by
     * order value — the same drop-off point costs a different amount depending
     * on how much is being bought, and can be free entirely.
     */
    cartSubtotal: Double,
    deliveryRepository: DeliveryRepository,
    onLocationSelected: (
        pickupAddress: String,
        dropoffAddress: String,
        pickupLat: Double,
        pickupLng: Double,
        dropoffLat: Double,
        dropoffLng: Double,
        distanceKm: Double,
        totalCost: Double
    ) -> Unit
) {
    val scope = rememberCoroutineScope()

    // Pickup is only used to draw the origin marker and centre the map. The
    // server measures distance from its own configured warehouse coordinates,
    // so this cannot influence a price.
    val pickupLatLng = remember { LatLng(DeliveryConfig.PICKUP_LAT, DeliveryConfig.PICKUP_LNG) }

    var dropoff by remember { mutableStateOf<LatLng?>(null) }
    var dropoffAddress by remember { mutableStateOf("") }
    var distanceKm by remember { mutableStateOf(0.0) }
    var totalCost by remember { mutableStateOf(0.0) }
    var isCalculating by remember { mutableStateOf(false) }
    var quoteReason by remember { mutableStateOf<String?>(null) }
    var isFreeDelivery by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    // Null until a successful quote. Confirm stays disabled while it is null,
    // so a failed quote can never be submitted as a zero fee.
    var quotedFee by remember { mutableStateOf<Double?>(null) }

    // Set when a tap lands outside the service area, so the refusal is
    // explained on the map rather than silently ignored.
    var outOfArea by remember { mutableStateOf(false) }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(pickupLatLng, 12f)
    }

    fun selectPoint(point: LatLng) {
        // Reject anything that cannot be a Ugandan address before spending a
        // geocode and a quote on it. Without this an order was once placed to
        // 51.7, -88.9 (Ontario, Canada): with no Maps API key the map never
        // authenticates, so it renders at world scale and a stray tap lands
        // on another continent. Nothing downstream questioned it.
        if (!DeliveryConfig.isWithinServiceArea(point.latitude, point.longitude)) {
            dropoff = null
            dropoffAddress = ""
            quotedFee = null
            quoteReason = null
            isCalculating = false
            errorMessage = "That location is outside Uganda. Pick a delivery point on the map."
            return
        }

        dropoff = point
        isCalculating = true
        errorMessage = null
        quotedFee = null
        quoteReason = null

        scope.launch {
            // Reverse-geocode for display only; the fee comes from the server.
            val address = reverseGeocode(point)
            dropoffAddress = address

            deliveryRepository.quote(
                orderValue = cartSubtotal,
                lat = point.latitude,
                lng = point.longitude,
                address = address
            ) { quote, error ->
                isCalculating = false

                if (quote?.fee == null) {
                    // No local fallback on purpose: showing an invented figure
                    // that differs from what the backend records is worse than
                    // telling the customer we could not price it.
                    errorMessage = error?.userMessage
                        ?: "Couldn't calculate the delivery fee for that spot. Try again, or pick a nearby point."
                    return@quote
                }

                distanceKm = quote.distance ?: 0.0
                totalCost = quote.fee
                quotedFee = quote.fee
                isFreeDelivery = quote.isFree || quote.fee == 0.0
                quoteReason = quote.reason
            }
        }
    }

    val canConfirm = dropoff != null && !isCalculating && quotedFee != null

    Scaffold(
        containerColor = Cream,
        topBar = {
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Ink)
                }
                Text("Delivery Location", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink)
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                GoogleMap(
                    modifier = Modifier.fillMaxSize(),
                    cameraPositionState = cameraPositionState,
                    // Panned and pinned only inside Greater Kampala. The same
                    // box is enforced server-side in includes/service_area.php,
                    // because coordinates travel in a request body and this
                    // restriction is a convenience rather than a control.
                    properties = rememberAfamFreshMapProperties(),
                    uiSettings = rememberAfamFreshMapUiSettings(),
                    onMapClick = { latLng ->
                        if (GkmaBounds.contains(latLng.latitude, latLng.longitude)) {
                            outOfArea = false
                            selectPoint(latLng)
                        } else {
                            // The camera bounds keep the map over the area, but
                            // a tap near the edge can still land outside it.
                            outOfArea = true
                        }
                    }
                ) {
                    PickupMarker(
                        state = MarkerState(position = pickupLatLng),
                        label = "Pickup",
                        sub = DeliveryConfig.PICKUP_LABEL
                    )
                    dropoff?.let {
                        DropoffMarker(state = MarkerState(position = it), label = "Delivery here")
                    }
                }

                if (outOfArea) {
                    Surface(
                        modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
                        shape = RoundedCornerShape(20.dp),
                        color = Color(0xFFB3261E).copy(alpha = 0.92f)
                    ) {
                        Text(
                            GkmaBounds.OUT_OF_AREA_MESSAGE,
                            color = Color.White,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                } else if (dropoff == null) {
                    Surface(
                        modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
                        shape = RoundedCornerShape(20.dp),
                        color = Ink.copy(alpha = 0.82f)
                    ) {
                        Text(
                            "Tap the map to set your delivery point",
                            color = Color.White,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                }
            }

            // ===== Summary panel =====
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                    .background(CardWhite)
                    .padding(20.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LocationOn, contentDescription = null, tint = Forest)
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text("From", fontSize = 11.sp, color = InkMuted)
                        Text(DeliveryConfig.PICKUP_LABEL, fontWeight = FontWeight.Medium, color = Ink, fontSize = 14.sp)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                when {
                    isCalculating -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Forest, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Calculating route…", color = InkMuted, fontSize = 13.sp)
                    }

                    dropoff != null -> {
                        Text("To", fontSize = 11.sp, color = InkMuted)
                        Text(dropoffAddress, fontWeight = FontWeight.Medium, color = Ink, fontSize = 14.sp)

                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(color = DividerGray)
                        Spacer(modifier = Modifier.height(12.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Distance", color = InkMuted, fontSize = 14.sp)
                            Text("${"%.1f".format(distanceKm)} km", color = Ink, fontSize = 14.sp)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Delivery fee", fontWeight = FontWeight.Bold, color = Ink)
                            Text(
                                if (isFreeDelivery) "FREE" else formatUgx(totalCost),
                                fontWeight = FontWeight.Bold,
                                color = Forest
                            )
                        }

                        // The server explains its own decision, e.g. "Free
                        // delivery for orders above 100,000 UGX". Showing it
                        // tells the customer why, and nudges toward the
                        // threshold when they are close.
                        quoteReason?.let { reason ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(reason, fontSize = 11.sp, color = InkMuted)
                        }
                    }

                    else -> Text("No delivery point selected yet", color = InkMuted, fontSize = 13.sp)
                }

                errorMessage?.let {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(it, color = Tomato, fontSize = 13.sp)
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        dropoff?.let { d ->
                            onLocationSelected(
                                DeliveryConfig.PICKUP_LABEL,
                                dropoffAddress,
                                pickupLatLng.latitude,
                                pickupLatLng.longitude,
                                d.latitude,
                                d.longitude,
                                distanceKm,
                                totalCost
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Forest),
                    enabled = canConfirm
                ) {
                    Text("Confirm Location", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ===== helpers =====

/** Nominatim first, then Google, then raw coordinates. */
private suspend fun reverseGeocode(point: LatLng): String = withContext(Dispatchers.IO) {
    try {
        val r = ApiClient.nominatimApiService.reverseGeocode(point.latitude, point.longitude).execute()
        r.body()?.display_name?.let { return@withContext it }
    } catch (e: Exception) {
        Log.w(TAG, "Nominatim reverse geocode failed: ${e.message}")
    }

    try {
        val r = ApiClient.googleGeocodingApi.reverseGeocode(
            latLng = "${point.latitude},${point.longitude}",
            apiKey = ApiClient.GOOGLE_MAPS_API_KEY
        ).execute()
        r.body()?.results?.firstOrNull()?.formatted_address?.let { return@withContext it }
    } catch (e: Exception) {
        Log.w(TAG, "Google reverse geocode failed: ${e.message}")
    }

    "%.5f, %.5f".format(point.latitude, point.longitude)
}
