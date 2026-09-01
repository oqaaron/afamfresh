package com.techaus.afamfresh.ui.screens.rider

import com.techaus.afamfresh.utils.computeDistanceMeters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryHandshakeTest {
    @Test
    fun `distance check returns near-zero for the same point`() {
        val distance = computeDistanceMeters(0.0, 0.0, 0.0, 0.0)
        assertEquals(0.0, distance, 0.001)
    }

    @Test
    fun `distance check stays under the 150m geofence threshold`() {
        val distance = computeDistanceMeters(0.347600, 32.582500, 0.347500, 32.582500)
        assertTrue(distance < 150.0)
    }
}
