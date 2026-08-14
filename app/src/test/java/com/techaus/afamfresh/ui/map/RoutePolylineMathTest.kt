package com.techaus.afamfresh.ui.map

import com.google.android.gms.maps.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure geometry behind the customer tracking screen's rider marker:
 * walking a decoded route by distance rather than lerping straight-line
 * between two GPS fixes. See OrderTrackingScreen.kt for where this is used.
 */
class RoutePolylineMathTest {

    // Roughly Kampala-scale coordinates; exact values don't matter, only
    // that they are far enough apart to give segments a real length.
    private val straight = listOf(
        LatLng(0.3476, 32.5825),
        LatLng(0.3486, 32.5825), // ~1.11km due north
        LatLng(0.3496, 32.5825), // another ~1.11km due north
    )

    // A sharp bend at the middle vertex — the case the arc-length fix exists
    // for: a straight lerp between the endpoints would cut across the bend.
    private val bend = listOf(
        LatLng(0.3476, 32.5825),
        LatLng(0.3486, 32.5825), // north
        LatLng(0.3486, 32.5925), // then east
    )

    @Test
    fun `cumulativeDistances starts at zero`() {
        val d = cumulativeDistances(straight)
        assertEquals(0.0, d[0], 0.001)
    }

    @Test
    fun `cumulativeDistances is monotonically non-decreasing`() {
        val d = cumulativeDistances(bend)
        for (i in 1 until d.size) {
            assertTrue("distances must not decrease", d[i] >= d[i - 1])
        }
    }

    @Test
    fun `positionAtDistance at zero returns the first point`() {
        val d = cumulativeDistances(straight)
        val p = positionAtDistance(straight, d, 0.0)
        assertEquals(straight[0].latitude, p.latitude, 0.0001)
        assertEquals(straight[0].longitude, p.longitude, 0.0001)
    }

    @Test
    fun `positionAtDistance beyond the route length clamps to the last point`() {
        val d = cumulativeDistances(straight)
        val p = positionAtDistance(straight, d, d.last() + 10_000.0)
        assertEquals(straight.last().latitude, p.latitude, 0.0001)
        assertEquals(straight.last().longitude, p.longitude, 0.0001)
    }

    @Test
    fun `positionAtDistance at the midpoint of a two-point route is between them`() {
        val two = listOf(straight[0], straight[1])
        val d = cumulativeDistances(two)
        val p = positionAtDistance(two, d, d.last() / 2.0)

        assertTrue(p.latitude > two[0].latitude && p.latitude < two[1].latitude)
    }

    @Test
    fun `positionAtDistance on a bend follows the bend, not a straight cut`() {
        val d = cumulativeDistances(bend)

        // Just past the first segment's length: should be at (or essentially
        // at) the bend's middle vertex, not partway along the straight line
        // from the first point directly to the last.
        val p = positionAtDistance(bend, d, d[1])

        assertEquals(bend[1].latitude, p.latitude, 0.0001)
        assertEquals(bend[1].longitude, p.longitude, 0.0001)

        // A straight lerp from bend[0] to bend[2] at the halfway point in
        // distance would land at a longitude roughly halfway between the two
        // endpoints' longitudes despite bend[0] and bend[1] sharing a
        // longitude — confirm the walked position does NOT do that.
        val straightCutLng = (bend[0].longitude + bend[2].longitude) / 2.0
        assertTrue(kotlin.math.abs(p.longitude - straightCutLng) > 0.01)
    }

    @Test
    fun `positionAtDistance on an empty route does not throw`() {
        positionAtDistance(emptyList(), DoubleArray(0), 0.0)
    }

    @Test
    fun `positionAtDistance on a single-point route returns that point`() {
        val single = listOf(straight[0])
        val d = cumulativeDistances(single)
        val p = positionAtDistance(single, d, 500.0)
        assertEquals(single[0].latitude, p.latitude, 0.0001)
        assertEquals(single[0].longitude, p.longitude, 0.0001)
    }
}
