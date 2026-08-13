package com.techaus.afamfresh.ui.screens.vendor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import com.techaus.afamfresh.ui.map.rememberAfamFreshMapProperties
import com.techaus.afamfresh.ui.map.rememberAfamFreshMapUiSettings
import com.techaus.afamfresh.ui.theme.*
import com.techaus.afamfresh.utils.GkmaBounds

/**
 * Where a rider collects from.
 *
 * WHY A VENDOR PINS ANYTHING
 *
 * A surplus load is picked up at the vendor's premises and driven to the
 * customer, and the delivery fee charges for that journey. Vendors carry only a
 * free-text `location` — "Nakawa market", "near the stage" — which nothing can
 * measure a distance from. Until a vendor pins, their listings are priced from
 * the depot instead, and every quote on them is flagged as estimated.
 *
 * So this screen exists to convert one vague sentence into two numbers that a
 * fee can honestly be built on.
 *
 * DELIBERATELY MINIMAL
 *
 * No route drawing, no fee preview, no geocoding — the customer-facing
 * DeliveryMapScreen does all of that and belongs to the customer flavor. This
 * is a pin and a confirm, which is why it is worth a second small screen rather
 * than sharing a 300-line one across two flavors.
 */
@Composable
fun VendorLocationPickerScreen(
    initialLat: Double?,
    initialLng: Double?,
    onBack: () -> Unit,
    onPicked: (lat: Double, lng: Double) -> Unit
) {
    val start = remember {
        if (GkmaBounds.contains(initialLat, initialLng)) {
            LatLng(initialLat!!, initialLng!!)
        } else {
            LatLng(GkmaBounds.CENTER_LAT, GkmaBounds.CENTER_LNG)
        }
    }

    var picked by remember { mutableStateOf(if (GkmaBounds.contains(initialLat, initialLng)) start else null) }
    var outOfArea by remember { mutableStateOf(false) }

    val camera = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(start, if (picked != null) 16f else 12f)
    }

    Scaffold(
        containerColor = Cream,
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Ink)
                }
                Column {
                    Text("Pickup point", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Text("Where riders collect your surplus", fontSize = 12.sp, color = InkMuted)
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                GoogleMap(
                    modifier = Modifier.fillMaxSize(),
                    cameraPositionState = camera,
                    // Same box the server enforces in includes/service_area.php.
                    // A vendor outside Greater Kampala would price every one of
                    // their listings off a journey no rider makes.
                    properties = rememberAfamFreshMapProperties(),
                    uiSettings = rememberAfamFreshMapUiSettings(),
                    onMapClick = { latLng ->
                        if (GkmaBounds.contains(latLng.latitude, latLng.longitude)) {
                            outOfArea = false
                            picked = latLng
                        } else {
                            // The camera bounds hold the map over the area, but
                            // a tap near the edge can still land outside it.
                            outOfArea = true
                        }
                    }
                ) {
                    picked?.let {
                        Marker(state = MarkerState(position = it), title = "Your premises")
                    }
                }

                Surface(
                    modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = if (outOfArea) Color(0xFFB3261E).copy(alpha = 0.92f)
                            else Ink.copy(alpha = 0.82f)
                ) {
                    Text(
                        when {
                            outOfArea -> GkmaBounds.OUT_OF_AREA_MESSAGE
                            picked == null -> "Tap the map on your premises"
                            else -> "Tap again to move the pin"
                        },
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }

            Column(Modifier.padding(20.dp)) {
                Text(
                    "Riders are sent here to collect. Put the pin where a loaded " +
                        "vehicle can actually stop, not just on the building.",
                    fontSize = 12.sp,
                    color = InkMuted
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { picked?.let { onPicked(it.latitude, it.longitude) } },
                    enabled = picked != null,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Forest)
                ) {
                    Text("Use this point", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}