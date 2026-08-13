package com.techaus.afamfresh.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.JointType
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.RoundCap
import com.google.maps.android.compose.Polyline
import com.techaus.afamfresh.ui.theme.Forest
import com.techaus.afamfresh.ui.theme.InkMuted

/**
 * The route, drawn the way a navigation app draws one.
 *
 * A single-width line reads as a scribble on a map. Three stacked layers read as
 * a road: a dark casing underneath so the line separates from pale streets, the
 * brand colour on top of it, and the ground already covered greyed out behind
 * the rider so progress is visible at a glance rather than inferred from a
 * marker's position.
 */
@Composable
fun RoutePolyline(
    points: List<LatLng>,
    /** How far along the rider is. Everything before this is drawn as travelled. */
    progressIndex: Int = 0
) {
    if (points.size < 2) return

    val density = LocalDensity.current
    val casingWidth = with(density) { 14.dp.toPx() }
    val bodyWidth = with(density) { 9.dp.toPx() }

    // Casing first — Polyline z-order follows declaration order.
    Polyline(
        points = points,
        color = Color(0xFF0E4A25),   // Forest, darkened
        width = casingWidth,
        jointType = JointType.ROUND,
        startCap = RoundCap(),
        endCap = RoundCap()
    )

    Polyline(
        points = points,
        color = Forest,
        width = bodyWidth,
        jointType = JointType.ROUND,
        startCap = RoundCap(),
        endCap = RoundCap()
    )

    // The part already driven, greyed back. Drawn last so it sits over the body.
    val travelled = progressIndex.coerceIn(0, points.lastIndex)
    if (travelled >= 1) {
        Polyline(
            points = points.subList(0, travelled + 1),
            color = InkMuted.copy(alpha = 0.6f),
            width = bodyWidth,
            jointType = JointType.ROUND,
            startCap = RoundCap(),
            endCap = RoundCap()
        )
    }
}

/**
 * The fallback when there is no route geometry.
 *
 * Google being unreachable, or a rider who has not collected yet, means no
 * polyline. A dashed straight line is honest about that — it is visibly not a
 * road, so nobody reads it as the way the delivery will travel. Drawing an OSRM
 * route here instead, or a solid line, would present a guess as a fact.
 */
@Composable
fun ApproximateRouteLine(from: LatLng, to: LatLng) {
    val density = LocalDensity.current
    Polyline(
        points = listOf(from, to),
        color = InkMuted.copy(alpha = 0.7f),
        width = with(density) { 4.dp.toPx() },
        pattern = listOf(Dash(with(density) { 12.dp.toPx() }), Gap(with(density) { 8.dp.toPx() }))
    )
}

/**
 * How far along [points] the rider is, as an index.
 *
 * Nearest vertex by straight-line distance. Good enough: vertices on a decoded
 * Google route are dense, and the only consumer is the greying-out above, where
 * being one vertex out is invisible.
 */
fun nearestPointIndex(points: List<LatLng>, lat: Double, lng: Double): Int {
    if (points.isEmpty()) return 0
    var best = 0
    var bestDist = Double.MAX_VALUE
    points.forEachIndexed { i, p ->
        // Squared degrees. No need for a real distance — only the ordering
        // matters, and a square root per vertex on every poll is waste.
        val d = (p.latitude - lat).let { it * it } + (p.longitude - lng).let { it * it }
        if (d < bestDist) {
            bestDist = d
            best = i
        }
    }
    return best
}