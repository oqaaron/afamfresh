package com.techaus.afamfresh.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Google publishes a reference vector for the encoded-polyline format. If this
 * passes, the decoder agrees with the thing producing the strings.
 */
class PolylineDecoderTest {

    @Test
    fun `decodes Google's published reference vector`() {
        // From Google's encoded polyline documentation. Decodes to
        // (38.5, -120.2), (40.7, -120.95), (43.252, -126.453).
        val points = PolylineDecoder.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")

        assertEquals(3, points.size)
        assertEquals(38.5, points[0].first, 1e-5)
        assertEquals(-120.2, points[0].second, 1e-5)
        assertEquals(40.7, points[1].first, 1e-5)
        assertEquals(-120.95, points[1].second, 1e-5)
        assertEquals(43.252, points[2].first, 1e-5)
        assertEquals(-126.453, points[2].second, 1e-5)
    }

    @Test
    fun `empty and null decode to nothing rather than throwing`() {
        // A delivery with no cached route yet is normal, not exceptional — the
        // tracking screen asks before the rider has collected.
        assertTrue(PolylineDecoder.decode(null).isEmpty())
        assertTrue(PolylineDecoder.decode("").isEmpty())
    }

    @Test
    fun `a truncated string yields the points it did contain`() {
        val full = PolylineDecoder.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        val cut = PolylineDecoder.decode("_p~iF~ps|U_ulLnnqC_mqNvxq")

        // Most of the route beats none of it: a partial line still shows the
        // customer roughly where their delivery is going.
        assertTrue(cut.size < full.size)
        assertEquals(full[0], cut[0])
    }

    @Test
    fun `handles a single point`() {
        val points = PolylineDecoder.decode("_p~iF~ps|U")
        assertEquals(1, points.size)
        assertEquals(38.5, points[0].first, 1e-5)
    }
}