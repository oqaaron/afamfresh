package com.techaus.afamfresh.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapsComposeExperimentalApi
import com.google.maps.android.compose.MarkerComposable
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import com.techaus.afamfresh.ui.map.*
import com.techaus.afamfresh.ui.theme.*
import com.techaus.afamfresh.utils.DeliveryPushBus
import com.techaus.afamfresh.utils.PolylineDecoder
import com.techaus.afamfresh.viewmodel.TrackingViewModel

/**
 * Watching a delivery move.
 *
 * Before this, a customer's only signal was a status word on a list that did
 * not refresh, plus whatever notifications arrived. `order_tracking_logs` has
 * been collecting the rider's position since the dispatch work and nothing had
 * ever read it.
 *
 * THE ONE DETAIL THAT MAKES IT FEEL LIVE
 *
 * The rider marker's position is ANIMATED between polls rather than assigned.
 * A marker that jumps every ten seconds reads as a stuttering diagram; the same
 * data, interpolated, reads as a vehicle moving. It is the cheapest and most
 * valuable thing on this screen.
 *
 * The marker's CONTENT stays fixed while it does — MarkerComposable at
 * maps-compose 4.3.0 rasterises once and does not reliably re-render on content
 * change, so anything that must update has to update through MarkerState.
 */
@OptIn(MapsComposeExperimentalApi::class)
@Composable
fun OrderTrackingScreen(
    orderId: Int,
    source: String,
    trackingViewModel: TrackingViewModel,
    onBack: () -> Unit
) {
    val tracking by trackingViewModel.tracking.collectAsState()
    val stale by trackingViewModel.stale.collectAsState()
    val error by trackingViewModel.error.collectAsState()
    val context = LocalContext.current

    // Polling starts with the screen and stops with it, so a backgrounded app
    // is not waking the radio every ten seconds for a map nobody is looking at.
    DisposableEffect(orderId, source) {
        trackingViewModel.start(orderId, source)
        onDispose { trackingViewModel.stop() }
    }

    // A transition push means the state changed a moment ago. Waiting out the
    // rest of the interval would show information the app already knows is old.
    LaunchedEffect(orderId, source) {
        DeliveryPushBus.transitions.collect { (pushedId, pushedSource) ->
            if (pushedId == orderId && pushedSource == source) {
                trackingViewModel.refreshNow(orderId, source)
            }
        }
    }

    val route = remember(tracking?.routePolyline) {
        PolylineDecoder.decode(tracking?.routePolyline).map { LatLng(it.first, it.second) }
    }

    val riderPos = tracking?.position?.let { p ->
        if (p.lat != null && p.lng != null) LatLng(p.lat, p.lng) else null
    }

    // The raw GPS fix is rarely exactly on the decoded route polyline — GPS
    // noise, road width, the fix arriving mid-turn — so drawing the marker at
    // riderPos directly puts the bike visibly off the road. Snapped onto the
    // nearest point on the route instead; falls back to the raw fix when
    // there is no route yet (rider not collected, or Google unreachable).
    val riderRouteIndex = if (route.size >= 2 && riderPos != null) {
        nearestPointIndex(route, riderPos.latitude, riderPos.longitude)
    } else null
    val snappedRiderPos = riderRouteIndex?.let { route[it] } ?: riderPos

    // Distance walked along the route, in metres, rather than a lat/lng
    // target — see positionAtDistance's doc for why: animating two floats
    // straight toward a new snapped vertex lerps across whatever the road
    // does between here and there, cutting corners on a bend. Walking the
    // polyline instead confines that same straight-line lerp to one short
    // segment.
    val distances = remember(route) { cumulativeDistances(route) }
    val targetDistanceM = riderRouteIndex?.let { distances.getOrNull(it) }

    // Monotonic ratchet: the nearest-vertex index can regress slightly poll
    // to poll from GPS noise even while the rider moves forward, which would
    // make the marker visibly twitch backward. Small regressions are
    // absorbed; a regression bigger than the same 100m accuracy-usability
    // margin api/rider.php's `location` action already applies server-side
    // (with headroom) is treated as a genuine backtrack — a wrong turn, or
    // the rider actually reversing — and let through rather than stuck.
    // Reset when the route itself changes: a reassignment mid-delivery means
    // the old distances no longer mean anything.
    var displayDistanceM by remember(route) { mutableStateOf(0.0) }
    targetDistanceM?.let { target ->
        if (target >= displayDistanceM || displayDistanceM - target > 150.0) {
            displayDistanceM = target
        }
    }

    val pickup = tracking?.pickup?.let { p ->
        if (p.lat != null && p.lng != null) LatLng(p.lat, p.lng) else null
    }
    val dropoff = tracking?.dropoff?.let { p ->
        if (p.lat != null && p.lng != null) LatLng(p.lat, p.lng) else null
    }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(GKMA_CENTER, 12f)
    }

    // Frame everything once, when the pieces first arrive. Not on every poll:
    // re-framing continuously would fight the customer every time they pinched
    // in to look at a street.
    var framed by remember { mutableStateOf(false) }
    LaunchedEffect(riderPos, pickup, dropoff) {
        if (framed) return@LaunchedEffect
        val points = listOfNotNull(riderPos, pickup, dropoff)
        if (points.size >= 2) {
            val b = LatLngBounds.builder().apply { points.forEach { include(it) } }.build()
            runCatching { cameraPositionState.animate(CameraUpdateFactory.newLatLngBounds(b, 140)) }
            framed = true
        } else if (points.size == 1) {
            runCatching {
                cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(points.first(), 15f))
            }
            framed = true
        }
    }

    // Interpolated so the bike glides. Keyed on the polling interval so the
    // animation lasts exactly as long as the gap between updates — finishing
    // just as the next position arrives.
    val pollMs = ((tracking?.pollAfterSeconds ?: 10).coerceIn(3, 30)) * 1000
    val animDistance by animateFloatAsState(
        targetValue = displayDistanceM.toFloat(),
        animationSpec = tween(pollMs), label = "riderDistance"
    )

    // Walks the polyline for a route; falls back to the raw/snapped fix when
    // there is no route yet (route.size < 2 — rider not collected, or Google
    // unreachable), since there is nothing to walk.
    val animatedRiderPos = if (route.size >= 2) {
        positionAtDistance(route, distances, animDistance.toDouble())
    } else {
        snappedRiderPos
    }

    Scaffold(containerColor = Cream) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {

            Box(Modifier.weight(1f).fillMaxWidth()) {
                GoogleMap(
                    modifier = Modifier.fillMaxSize(),
                    cameraPositionState = cameraPositionState,
                    // NOT clamped to the service area: a rider who strays outside
                    // it is a real situation, and a camera that refused to follow
                    // would look broken at the worst possible moment.
                    properties = rememberAfamFreshMapProperties(clampToServiceArea = false),
                    uiSettings = rememberAfamFreshMapUiSettings()
                ) {
                    when {
                        route.size >= 2 -> RoutePolyline(
                            points = route,
                            progressIndex = riderRouteIndex ?: 0
                        )
                        // No geometry yet — the rider has not collected, or
                        // Google was unreachable. A dashed line is visibly not a
                        // road, so nobody reads it as the actual path.
                        pickup != null && dropoff != null -> ApproximateRouteLine(pickup, dropoff)
                    }

                    pickup?.let {
                        PickupMarker(
                            state = MarkerState(position = it),
                            label = tracking?.pickup?.label ?: "Pickup"
                        )
                    }
                    dropoff?.let {
                        DropoffMarker(
                            state = MarkerState(position = it),
                            label = tracking?.dropoff?.label ?: "Your address",
                            sub = tracking?.dropoff?.area
                        )
                    }

                    if (riderPos != null && animatedRiderPos != null) {
                        MarkerComposable(
                            // Content is constant; only the state moves. See the
                            // header for why that is not merely a preference.
                            keys = arrayOf("rider"),
                            state = MarkerState(position = animatedRiderPos),
                            rotation = (tracking?.position?.headingDeg ?: 0).toFloat(),
                            flat = true,
                            zIndex = 3f
                        ) {
                            Box(
                                modifier = Modifier.size(36.dp).clip(CircleShape).background(Forest),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.TwoWheeler,
                                    contentDescription = "Rider",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }

                IconButton(
                    onClick = onBack,
                    modifier = Modifier.padding(12.dp)
                        .size(40.dp).clip(CircleShape).background(CardWhite)
                ) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Ink)
                }

                if (stale) {
                    // A strip, not a dialog. A momentary signal drop is not
                    // worth interrupting someone watching their delivery.
                    Surface(
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 14.dp),
                        shape = RoundedCornerShape(20.dp),
                        color = Color(0xFFF9A825)
                    ) {
                        Text(
                            "Reconnecting…",
                            color = Color.White, fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            TrackingSheet(
                headline = tracking?.headline ?: "Finding your delivery…",
                stage = tracking?.stageIndex ?: 0,
                eta = tracking?.etaMinutes,
                riderName = tracking?.rider?.name,
                riderRating = tracking?.rider?.avgRating,
                riderPhone = tracking?.rider?.phone,
                error = if (tracking == null) error else null,
                onCall = { phone ->
                    context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
                }
            )
        }
    }
}

@Composable
private fun TrackingSheet(
    headline: String,
    stage: Int,
    eta: Int?,
    riderName: String?,
    riderRating: Double?,
    riderPhone: String?,
    error: String?,
    onCall: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
            .background(CardWhite)
            .padding(20.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(headline, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink)
                if (eta != null) {
                    Text("About $eta min away", fontSize = 13.sp, color = InkMuted)
                }
            }
            if (eta != null) {
                Column(horizontalAlignment = Alignment.End) {
                    Text("$eta", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Forest)
                    Text("min", fontSize = 11.sp, color = InkMuted)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Four dots rather than a percentage: the stages are discrete and a
        // customer wants to know which one they are in, not a made-up fraction.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(4) { i ->
                Box(
                    modifier = Modifier
                        .weight(1f).height(5.dp).clip(RoundedCornerShape(3.dp))
                        .background(if (i <= stage) Forest else ForestSurface)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("Assigned", "Collected", "On the way", "Delivered").forEachIndexed { i, s ->
                Text(s, fontSize = 9.sp, color = if (i <= stage) Forest else InkMuted)
            }
        }

        if (riderName != null) {
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(40.dp).clip(CircleShape).background(ForestSurface),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.TwoWheeler, contentDescription = null, tint = Forest,
                         modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(riderName, fontWeight = FontWeight.SemiBold, color = Ink, fontSize = 14.sp)
                    if (riderRating != null && riderRating > 0) {
                        Text("★ ${String.format("%.1f", riderRating)}", fontSize = 12.sp, color = InkMuted)
                    }
                }
                // Only while the delivery is live — the server withholds the
                // number once it is over, so this simply disappears.
                if (!riderPhone.isNullOrBlank()) {
                    FilledTonalButton(onClick = { onCall(riderPhone) }) {
                        Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Call")
                    }
                }
            }
        }

        if (error != null) {
            Spacer(Modifier.height(12.dp))
            Text(error, color = Tomato, fontSize = 13.sp)
        }
    }
}