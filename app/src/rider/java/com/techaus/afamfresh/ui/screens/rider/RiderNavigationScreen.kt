package com.techaus.afamfresh.ui.screens.rider

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.rememberCameraPositionState
import com.techaus.afamfresh.models.RiderActionState
import com.techaus.afamfresh.ui.map.*
import com.techaus.afamfresh.ui.theme.*
import com.techaus.afamfresh.utils.PolylineDecoder
import com.techaus.afamfresh.utils.formatUgx
import com.techaus.afamfresh.viewmodel.RiderViewModel
import com.techaus.afamfresh.viewmodel.TrackingViewModel

/**
 * Turn-by-turn-shaped, without turn-by-turn — the real Navigation SDK needs a
 * Maps Platform agreement Google has not approved yet. Until then this is the
 * follow-camera map and route: the rider's own [MyLocation] dot, tilted and
 * bearing-locked, drawn over the same route geometry the customer's tracking
 * screen shows.
 *
 * Position comes from THIS DEVICE's [com.google.android.gms.location.FusedLocationProviderClient],
 * not a round trip to the server: the rider is the one person who already
 * knows exactly where they are, and a screen meant to be glanced at while
 * riding cannot afford a network hop for that. [RiderTrackingService] keeps
 * posting to the server independently, for the customer's benefit.
 *
 * "Open in Google Maps" stays as a fallback button, not the default action —
 * the whole point of this screen is that a rider is no longer forced out of
 * the app to navigate, but nothing here stops them choosing to leave.
 */
@Composable
fun RiderNavigationScreen(
    orderId: Int,
    source: String,
    riderViewModel: RiderViewModel,
    trackingViewModel: TrackingViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val delivery by riderViewModel.selected.collectAsState()
    val tracking by trackingViewModel.tracking.collectAsState()
    val actionState by riderViewModel.actionState.collectAsState()

    LaunchedEffect(orderId, source) { riderViewModel.loadDelivery(orderId, source) }
    DisposableEffect(orderId, source) {
        trackingViewModel.start(orderId, source)
        onDispose { trackingViewModel.stop() }
    }

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }
    LaunchedEffect(Unit) {
        if (!granted) permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    // The rider's own position, read locally at navigation-grade cadence —
    // tighter than RiderTrackingService's, because this drives a camera a
    // person is looking at, not just a customer's slowly-updating map.
    var myLat by remember { mutableStateOf<Double?>(null) }
    var myLng by remember { mutableStateOf<Double?>(null) }
    var myBearing by remember { mutableStateOf(0f) }

    DisposableEffect(granted) {
        if (!granted) return@DisposableEffect onDispose { }
        val client = LocationServices.getFusedLocationProviderClient(context)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3_000L)
            .setMinUpdateDistanceMeters(5f)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let {
                    myLat = it.latitude
                    myLng = it.longitude
                    if (it.hasBearing()) myBearing = it.bearing
                }
            }
        }
        try {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (e: SecurityException) { /* revoked between check and call */ }
        onDispose { client.removeLocationUpdates(callback) }
    }

    val route = remember(tracking?.routePolyline) {
        PolylineDecoder.decode(tracking?.routePolyline).map { LatLng(it.first, it.second) }
    }
    val dropoff = delivery?.destLat?.let { lat ->
        delivery?.destLng?.let { lng -> LatLng(lat, lng) }
    }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(GKMA_CENTER, 15f)
    }

    // Follows the rider once a fix exists: tilted and bearing-locked so the
    // road ahead fills the screen, the way a turn-by-turn view reads even
    // though nothing here is actually routing.
    var following by remember { mutableStateOf(true) }
    LaunchedEffect(myLat, myLng, myBearing, following) {
        val lat = myLat; val lng = myLng
        if (lat != null && lng != null && following) {
            cameraPositionState.animate(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(LatLng(lat, lng))
                        .zoom(17f)
                        .tilt(45f)
                        .bearing(myBearing)
                        .build()
                ),
                600
            )
        }
    }

    Scaffold(containerColor = Cream) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {

            // ===== Header: distance, ETA, who and where =====
            Column(
                modifier = Modifier.fillMaxWidth().background(CardWhite).padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Ink)
                    }
                    Spacer(Modifier.width(4.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            delivery?.customerOrDash ?: "Delivery #$orderId",
                            fontWeight = FontWeight.Bold, color = Ink, fontSize = 15.sp
                        )
                        Text(
                            delivery?.addressOrDash ?: "Loading…",
                            fontSize = 12.sp, color = InkMuted, maxLines = 1
                        )
                    }
                    if (tracking?.etaMinutes != null) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text("${tracking?.etaMinutes}", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Forest)
                            Text("min", fontSize = 10.sp, color = InkMuted)
                        }
                    }
                }
                tracking?.distanceRemainingKm?.let {
                    Spacer(Modifier.height(4.dp))
                    Text("${"%.1f".format(it)} km remaining", fontSize = 12.sp, color = InkMuted)
                }
            }

            // ===== Map =====
            Box(Modifier.weight(1f).fillMaxWidth()) {
                GoogleMap(
                    modifier = Modifier.fillMaxSize(),
                    cameraPositionState = cameraPositionState,
                    properties = rememberAfamFreshMapProperties(showMyLocation = granted, clampToServiceArea = false),
                    uiSettings = rememberAfamFreshMapUiSettings(allowScroll = true),
                    onMapClick = { following = false }
                ) {
                    if (route.size >= 2) {
                        val progressIndex = myLat?.let { lat ->
                            myLng?.let { lng -> nearestPointIndex(route, lat, lng) }
                        } ?: 0
                        RoutePolyline(points = route, progressIndex = progressIndex)
                    }
                    dropoff?.let {
                        DropoffMarker(
                            state = com.google.maps.android.compose.MarkerState(position = it),
                            label = delivery?.addressOrDash ?: "Drop-off",
                            sub = delivery?.area
                        )
                    }
                }

                if (!following) {
                    IconButton(
                        onClick = { following = true },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp)
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(CardWhite)
                    ) {
                        Icon(Icons.Default.MyLocation, contentDescription = "Recentre", tint = Forest)
                    }
                }
            }

            // ===== Bottom controls =====
            Column(modifier = Modifier.fillMaxWidth().background(CardWhite).padding(16.dp)) {
                if (actionState is RiderActionState.Error) {
                    Text(
                        (actionState as RiderActionState.Error).message,
                        color = Tomato, fontSize = 12.sp
                    )
                    Spacer(Modifier.height(8.dp))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!delivery?.customerPhone.isNullOrBlank()) {
                        OutlinedButton(
                            onClick = {
                                context.startActivity(
                                    Intent(Intent.ACTION_DIAL, Uri.parse("tel:${delivery?.customerPhone}"))
                                )
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Call")
                        }
                    }
                    // Kept deliberately: nothing here prevents a rider choosing
                    // to leave for Google Maps, only stops the app forcing it.
                    if (dropoff != null) {
                        OutlinedButton(
                            onClick = {
                                val label = Uri.encode(delivery?.addressOrDash ?: "Delivery")
                                context.startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("geo:${dropoff.latitude},${dropoff.longitude}?q=${dropoff.latitude},${dropoff.longitude}($label)")
                                    )
                                )
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Google Maps")
                        }
                    }
                }

                val cashConfirm = actionState as? RiderActionState.NeedsCashConfirm
                if (cashConfirm != null) {
                    AlertDialog(
                        onDismissRequest = { riderViewModel.clearActionState() },
                        title = { Text("Confirm cash collected") },
                        text = {
                            Text(
                                "Collect ${formatUgx(cashConfirm.amount)} in cash from the " +
                                    "customer. Confirm you have received it in full before " +
                                    "marking this delivered."
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                riderViewModel.advance(
                                    cashConfirm.orderId, "delivered", cashConfirm.source,
                                    cashCollected = true
                                )
                            }) { Text("I've collected the cash") }
                        },
                        dismissButton = {
                            TextButton(onClick = { riderViewModel.clearActionState() }) { Text("Not yet") }
                        }
                    )
                }

                val next = delivery?.nextStage
                if (next != null) {
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            riderViewModel.clearActionState()
                            riderViewModel.advance(orderId, next, source)
                        },
                        enabled = actionState !is RiderActionState.Working,
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Forest)
                    ) {
                        if (actionState is RiderActionState.Working) {
                            CircularProgressIndicator(
                                color = androidx.compose.ui.graphics.Color.White,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp)
                            )
                        } else {
                            Text(delivery?.nextStageLabel ?: "Continue", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}