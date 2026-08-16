package com.techaus.afamfresh.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MyLocation
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
import com.techaus.afamfresh.ui.map.GKMA_CENTER
import com.techaus.afamfresh.ui.map.rememberAfamFreshMapProperties
import com.techaus.afamfresh.ui.map.rememberAfamFreshMapUiSettings
import com.techaus.afamfresh.ui.theme.*
import com.techaus.afamfresh.utils.GkmaBounds

/**
 * Map-based delivery location picker for customers.
 *
 * Allows users to:
 * - Tap the map to select a delivery location
 * - Auto-detect current GPS location (optional)
 * - Confirm the selection to get coordinates for reverse geocoding
 * - Visual feedback when selection is outside service area
 */
@Composable
fun LocationPickerScreen(
    initialLat: Double?,
    initialLng: Double?,
    currentGpsLat: Double?,
    currentGpsLng: Double?,
    onBack: () -> Unit,
    onLocationPicked: (lat: Double, lng: Double) -> Unit
) {
    val start = remember {
        when {
            GkmaBounds.contains(currentGpsLat, currentGpsLng) -> LatLng(currentGpsLat!!, currentGpsLng!!)
            GkmaBounds.contains(initialLat, initialLng) -> LatLng(initialLat!!, initialLng!!)
            else -> GKMA_CENTER
        }
    }

    var picked by remember { mutableStateOf<LatLng?>(if (GkmaBounds.contains(initialLat, initialLng)) LatLng(initialLat!!, initialLng!!) else null) }
    var outOfArea by remember { mutableStateOf(false) }
    var showGpsInfo by remember { mutableStateOf(currentGpsLat != null && currentGpsLng != null) }

    val camera = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(start, if (picked != null) 16f else 12f)
    }

    Scaffold(
        containerColor = Cream,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Forest)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
                Column {
                    Text(
                        "Select Delivery Location",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        "Tap map to pin your delivery point",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
                Spacer(modifier = Modifier.width(48.dp))
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                GoogleMap(
                    modifier = Modifier.fillMaxSize(),
                    cameraPositionState = camera,
                    properties = rememberAfamFreshMapProperties(
                        showMyLocation = true,
                        clampToServiceArea = true
                    ),
                    uiSettings = rememberAfamFreshMapUiSettings(
                        showMyLocationButton = true,
                        allowScroll = true
                    ),
                    onMapClick = { latLng ->
                        if (GkmaBounds.contains(latLng.latitude, latLng.longitude)) {
                            outOfArea = false
                            picked = latLng
                            showGpsInfo = false
                        } else {
                            outOfArea = true
                            picked = null
                        }
                    }
                ) {
                    picked?.let {
                        Marker(
                            state = MarkerState(position = it),
                            title = "Delivery Point"
                        )
                    }
                    if (showGpsInfo && currentGpsLat != null && currentGpsLng != null) {
                        val gpsLocation = LatLng(currentGpsLat, currentGpsLng)
                        Marker(
                            state = MarkerState(position = gpsLocation),
                            title = "Your GPS Location"
                        )
                    }
                }

                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(12.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = when {
                        outOfArea -> Color(0xFFB3261E).copy(alpha = 0.92f)
                        picked == null -> Ink.copy(alpha = 0.82f)
                        else -> Forest.copy(alpha = 0.82f)
                    }
                ) {
                    Text(
                        when {
                            outOfArea -> GkmaBounds.OUT_OF_AREA_MESSAGE
                            picked == null -> "Tap the map to pin your delivery location"
                            else -> "Tap again to move the pin"
                        },
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }

                if (showGpsInfo && currentGpsLat != null && currentGpsLng != null) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(12.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = Forest,
                        shadowElevation = 4.dp
                    ) {
                        IconButton(onClick = {
                            picked = LatLng(currentGpsLat, currentGpsLng)
                            outOfArea = !GkmaBounds.contains(currentGpsLat, currentGpsLng)
                        }) {
                            Icon(
                                Icons.Default.MyLocation,
                                contentDescription = "Use GPS Location",
                                tint = Color.White
                            )
                        }
                    }
                }
            }

            Column(Modifier.padding(20.dp)) {
                Text(
                    "Tap the map to select where your order should be delivered. Make sure to pin the exact location where the rider can find you.",
                    fontSize = 13.sp,
                    color = InkMuted,
                    lineHeight = 18.sp
                )

                if (picked != null && !outOfArea) {
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color(0xFFF1F8E9),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                "Coordinates:",
                                fontSize = 11.sp,
                                color = Ink,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "Lat: ${String.format("%.4f", picked!!.latitude)}, Lng: ${String.format("%.4f", picked!!.longitude)}",
                                fontSize = 10.sp,
                                color = InkMuted
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { picked?.let { onLocationPicked(it.latitude, it.longitude) } },
                    enabled = picked != null && !outOfArea,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Forest)
                ) {
                    Text("Confirm Location", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }
    }
}
