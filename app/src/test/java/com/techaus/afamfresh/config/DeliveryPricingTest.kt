package com.techaus.afamfresh.config

import com.techaus.afamfresh.models.DeliveryPricingConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The delivery quote decides what customers are charged, so it is tested at
 * the boundaries rather than only in the middle.
 */
class DeliveryPricingTest {

    private val pricing = DeliveryPricing(
        pickupLat = 0.3476,
        pickupLng = 32.5825,
        pickupLabel = "Test Shop",
        baseFareUgx = 2000.0,
        costPerKmUgx = 1000.0,
        maxDeliveryKm = 25.0
    )

    // ===== quoteFor =====

    @Test
    fun `zero distance still charges the base fare`() {
        assertEquals(2000.0, pricing.quoteFor(0.0), 0.001)
    }

    @Test
    fun `cost is base fare plus distance times rate`() {
        assertEquals(7000.0, pricing.quoteFor(5.0), 0.001)
        assertEquals(12500.0, pricing.quoteFor(10.5), 0.001)
    }

    /**
     * A negative distance must never discount the fare. It should not happen,
     * but a bad OSRM response or a sign error upstream should cost the
     * customer nothing rather than paying them.
     */
    @Test
    fun `negative distance is treated as zero, never as a discount`() {
        assertEquals(2000.0, pricing.quoteFor(-5.0), 0.001)
    }

    @Test
    fun `zero rates produce a free delivery rather than an error`() {
        val free = pricing.copy(baseFareUgx = 0.0, costPerKmUgx = 0.0)
        assertEquals(0.0, free.quoteFor(10.0), 0.001)
    }

    // ===== isWithinRange =====

    @Test
    fun `distance exactly at the limit is in range`() {
        assertTrue(pricing.isWithinRange(25.0))
    }

    @Test
    fun `distance beyond the limit is out of range`() {
        assertFalse(pricing.isWithinRange(25.01))
        assertFalse(pricing.isWithinRange(100.0))
    }

    @Test
    fun `short distances are in range`() {
        assertTrue(pricing.isWithinRange(0.0))
        assertTrue(pricing.isWithinRange(1.5))
    }

    // ===== server config parsing =====

    @Test
    fun `complete server config is accepted`() {
        val parsed = DeliveryPricingConfig(
            pickupLat = 1.0,
            pickupLng = 2.0,
            pickupLabel = "Shop",
            baseFare = 3000.0,
            costPerKm = 1500.0,
            maxDeliveryKm = 30.0
        ).toPricingOrNull()

        assertNotNull(parsed)
        assertEquals(3000.0, parsed!!.baseFareUgx, 0.001)
        assertEquals(1500.0, parsed.costPerKmUgx, 0.001)
        assertEquals(30.0, parsed.maxDeliveryKm, 0.001)
    }

    /**
     * All-or-nothing. If cost_per_km were treated as 0 when missing, every
     * delivery would quote at the flat base fare — a silent, systematic
     * undercharge.
     */
    @Test
    fun `partial server config is rejected rather than partially applied`() {
        val missingRate = DeliveryPricingConfig(
            pickupLat = 1.0,
            pickupLng = 2.0,
            pickupLabel = "Shop",
            baseFare = 3000.0,
            costPerKm = null,
            maxDeliveryKm = 30.0
        )
        assertNull(missingRate.toPricingOrNull())

        val missingCoords = DeliveryPricingConfig(
            pickupLat = null,
            pickupLng = 2.0,
            pickupLabel = "Shop",
            baseFare = 3000.0,
            costPerKm = 1500.0,
            maxDeliveryKm = 30.0
        )
        assertNull(missingCoords.toPricingOrNull())
    }

    @Test
    fun `negative or zero values are rejected`() {
        val negativeFare = DeliveryPricingConfig(
            pickupLat = 1.0, pickupLng = 2.0, pickupLabel = "Shop",
            baseFare = -100.0, costPerKm = 1500.0, maxDeliveryKm = 30.0
        )
        assertNull(negativeFare.toPricingOrNull())

        val zeroRadius = DeliveryPricingConfig(
            pickupLat = 1.0, pickupLng = 2.0, pickupLabel = "Shop",
            baseFare = 3000.0, costPerKm = 1500.0, maxDeliveryKm = 0.0
        )
        assertNull(zeroRadius.toPricingOrNull())
    }

    @Test
    fun `blank pickup label falls back rather than showing an empty address`() {
        val parsed = DeliveryPricingConfig(
            pickupLat = 1.0, pickupLng = 2.0, pickupLabel = "  ",
            baseFare = 3000.0, costPerKm = 1500.0, maxDeliveryKm = 30.0
        ).toPricingOrNull()

        assertNotNull(parsed)
        assertEquals("Pickup point", parsed!!.pickupLabel)
    }
}
