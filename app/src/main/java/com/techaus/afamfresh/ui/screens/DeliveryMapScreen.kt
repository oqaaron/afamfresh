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
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import com.techaus.afamfresh.api.ApiClient
import com.techaus.afamfresh.config.DeliveryConfig
import com.techaus.afamfresh.config.isWithinRange
import com.techaus.afamfresh.config.quoteFor
import com.techaus.afamfresh.ui.theme.*
import com.techaus.afamfresh.utils.formatUgx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.*

// Pricing is no longer defined here. It lives in config/DeliveryConfig.kt and
// is supplied by config.php when the backend provides it, so rates can change
// without an app release. See that file for the fallback values.

private const val TAG = "DeliveryMap"

// Signature confirmed exactly from MainScreen.kt's composable("delivery_map"):
//   onLocationSelected(pickupAddress, dropoffAddress, pickupLat, pickupLng,
//                      dropoffLat, dropoffLng, distanceKm, totalCost)
@Composable
fun DeliveryMapScreen(
    onBack: () -> Unit,
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

    // Snapshotted once per composition of this screen so a config refresh
    // mid-selection cannot change the price under the customer's feet between
    // the quote they see and the quote that gets submitted.
    val pricing = remember { DeliveryConfig.current }
    val pickupLatLng = remember(pricing) { LatLng(pricing.pickupLat, pricing.pickupLng) }

    var dropoff by remember { mutableStateOf<LatLng?>(null) }
    var dropoffAddress by remember { mutableStateOf("") }
    var distanceKm by remember { mutableStateOf(0.0) }
    var totalCost by remember { mutableStateOf(0.0) }
    var isCalculating by remember { mutableStateOf(false) }
    var usedFallbackDistance by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(pickupLatLng, 12f)
    }

    fun selectPoint(point: LatLng) {
        dropoff = point
        isCalculating = true
        errorMessage = null
        usedFallbackDistance = false

        scope.launch {
            // --- distance: OSRM road distance, straight-line fallback ---
            val osrmKm = fetchOsrmDistanceKm(pickupLatLng, point)
            val km = if (osrmKm != null) {
                osrmKm
            } else {
                usedFallbackDistance = true
                haversineKm(pickupLatLng, point)
            }

            // --- address: Nominatim, then Google, then raw coordinates ---
            val address = reverseGeocode(point)

            distanceKm = km
            dropoffAddress = address
            totalCost = pricing.quoteFor(km)
            isCalculating = false

            if (!pricing.isWithinRange(km)) {
                errorMessage = "That location is ${"%.1f".format(km)} km away — " +
                    "outside our ${pricing.maxDeliveryKm.toInt()} km delivery range."
            }
        }
    }

    val outOfRange = !pricing.isWithinRange(distanceKm)
    val canConfirm = dropoff != null && !isCalculating && !outOfRange

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
                    properties = MapProperties(isMyLocationEnabled = false),
                    onMapClick = { latLng -> selectPoint(latLng) }
                ) {
                    Marker(
                        state = MarkerState(position = pickupLatLng),
                        title = "Pickup",
                        snippet = pricing.pickupLabel
                    )
                    dropoff?.let {
                        Marker(state = MarkerState(position = it), title = "Delivery here")
                    }
                }

                if (dropoff == null) {
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
                        Text(pricing.pickupLabel, fontWeight = FontWeight.Medium, color = Ink, fontSize = 14.sp)
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
                            Text(formatUgx(totalCost), fontWeight = FontWeight.Bold, color = Forest)
                        }

                        if (usedFallbackDistance) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Estimated in a straight line — routing service unavailable, " +
                                    "so the real driving distance may be longer.",
                                fontSize = 11.sp,
                                color = InkMuted
                            )
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
                                pricing.pickupLabel,
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

/** OSRM road distance in km, or null if the service can't be reached. */
private suspend fun fetchOsrmDistanceKm(from: LatLng, to: LatLng): Double? =
    withContext(Dispatchers.IO) {
        try {
            // OSRM expects lon,lat order — reversed from the usual lat,lng.
            val coords = "${from.longitude},${from.latitude};${to.longitude},${to.latitude}"
            val response = ApiClient.osrmApiService.getRoute(coords).execute()
            val meters = response.body()?.routes?.firstOrNull()?.distance
            if (response.isSuccessful && meters != null) meters / 1000.0 else null
        } catch (e: Exception) {
            Log.w(TAG, "OSRM unavailable, will fall back to straight-line: ${e.message}")
            null
        }
    }

/** Great-circle distance — a floor, not a real driving distance. */
private fun haversineKm(a: LatLng, b: LatLng): Double {
    val earthRadiusKm = 6371.0
    val dLat = Math.toRadians(b.latitude - a.latitude)
    val dLon = Math.toRadians(b.longitude - a.longitude)
    val lat1 = Math.toRadians(a.latitude)
    val lat2 = Math.toRadians(b.latitude)
    val h = sin(dLat / 2).pow(2) + sin(dLon / 2).pow(2) * cos(lat1) * cos(lat2)
    return 2 * earthRadiusKm * asin(sqrt(h))
}

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
