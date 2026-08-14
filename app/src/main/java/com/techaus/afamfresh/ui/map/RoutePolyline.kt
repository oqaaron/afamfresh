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
import kotlin.math.cos
import kotlin.math.sqrt

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

/** Metres per degree of latitude. Good enough at this scale; not worth a full geodesic. */
private const val METERS_PER_DEGREE_LAT = 111_320.0

/**
 * Equirectangular approximation of the distance between two points, in
 * metres. Not a real distance — a real distance is not needed. What matters
 * is that segment lengths are proportional to real road distance, so a glide
 * along several short segments takes the same time as one long one; the
 * squared-degree trick [nearestPointIndex] uses does not have that property
 * once latitude changes, and this animation's timing depends on it.
 */
private fun approxMetersBetween(a: LatLng, b: LatLng): Double {
    val latMid = Math.toRadians((a.latitude + b.latitude) / 2.0)
    val dLat = (b.latitude - a.latitude) * METERS_PER_DEGREE_LAT
    val dLng = (b.longitude - a.longitude) * METERS_PER_DEGREE_LAT * cos(latMid)
    return sqrt(dLat * dLat + dLng * dLng)
}

/**
 * Prefix sum of [points]' segment lengths, in metres — `distances[i]` is how
 * far along the route vertex `i` is. `distances.size == points.size`,
 * `distances[0] == 0.0`.
 */
fun cumulativeDistances(points: List<LatLng>): DoubleArray {
    val out = DoubleArray(points.size)
    for (i in 1 until points.size) {
        out[i] = out[i - 1] + approxMetersBetween(points[i - 1], points[i])
    }
    return out
}

/**
 * Where the rider marker should sit, [targetMeters] along the route.
 *
 * The reason this exists rather than lerping straight from one poll's snapped
 * vertex to the next: that lerp cuts across whatever the road does between
 * the two vertices. This walks the polyline instead, so the straight-line
 * interpolation that inevitably still happens is confined to one short
 * segment rather than spanning however much of the route the rider covered
 * between polls.
 *
 * [points] and [distances] must correspond ([distances] from
 * [cumulativeDistances] of the same [points]).
 */
fun positionAtDistance(points: List<LatLng>, distances: DoubleArray, targetMeters: Double): LatLng {
    if (points.isEmpty()) return LatLng(0.0, 0.0)
    if (points.size == 1 || distances.isEmpty()) return points[0]

    val target = targetMeters.coerceIn(0.0, distances.last())

    // Linear scan: route point counts are small (a single delivery's worth of
    // decoded polyline), and this runs once per animation frame, not once per
    // poll — a binary search would not be measurably faster here.
    var segment = 0
    while (segment < distances.lastIndex - 1 && distances[segment + 1] < target) {
        segment++
    }

    val segStart = distances[segment]
    val segEnd = distances[segment + 1]
    val segLen = segEnd - segStart
    val t = if (segLen > 0.0) ((target - segStart) / segLen).coerceIn(0.0, 1.0) else 0.0

    val a = points[segment]
    val b = points[segment + 1]
    return LatLng(
        a.latitude + (b.latitude - a.latitude) * t,
        a.longitude + (b.longitude - a.longitude) * t
    )
}