package com.techaus.afamfresh.models

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract test for `api/calculate-delivery-fee.php`.
 *
 * The JSON below is the shape the real endpoint returns — taken from the PHP,
 * not invented. This replaces the previous DeliveryPricingTest, which tested a
 * local `baseFare + distance * costPerKm` formula that does not exist in the
 * business: the real fee is tiered by order value and computed server-side.
 *
 * What is worth testing now is that the app parses the server's answer
 * correctly, because a silent parse failure here means a wrong delivery charge.
 */
class DeliveryQuoteTest {

    private val gson = Gson()

    @Test
    fun `parses a standard priced quote`() {
        val json = """
            {
              "success": true,
              "distance": 4.2,
              "fee": 6770,
              "is_free": false,
              "reason": "Standard rate: 700 UGX/km, plus 1,800 UGX service fee, plus 0.9% insurance",
              "dest_lat": 0.3476,
              "dest_lng": 32.5825,
              "breakdown": {
                "distance_fee": 2940,
                "service_fee": 1800,
                "insurance_fee": 677,
                "processing_fee": 1353
              }
            }
        """.trimIndent()

        val quote = gson.fromJson(json, DeliveryQuoteResponse::class.java)

        assertTrue(quote.success)
        assertEquals(4.2, quote.distance!!, 0.001)
        assertEquals(6770.0, quote.fee!!, 0.001)
        assertFalse(quote.isFree)
        assertNotNull(quote.reason)

        // snake_case wire names must map onto the Kotlin properties
        val breakdown = quote.breakdown!!
        assertEquals(2940.0, breakdown.distanceFee, 0.001)
        assertEquals(1800.0, breakdown.serviceFee, 0.001)
        assertEquals(677.0, breakdown.insuranceFee, 0.001)
        assertEquals(1353.0, breakdown.processingFee, 0.001)
    }

    /**
     * Above the free-delivery threshold the server zeroes the distance fee but
     * still charges service, insurance and processing. "Free" therefore does
     * NOT mean a zero total, and the UI must not assume it does.
     */
    @Test
    fun `free delivery still carries the non-distance fees`() {
        val json = """
            {
              "success": true,
              "distance": 3.1,
              "fee": 4277,
              "is_free": false,
              "reason": "Free delivery for orders above 100,000 UGX",
              "breakdown": {
                "distance_fee": 0,
                "service_fee": 1800,
                "insurance_fee": 990,
                "processing_fee": 1980
              }
            }
        """.trimIndent()

        val quote = gson.fromJson(json, DeliveryQuoteResponse::class.java)

        assertEquals(0.0, quote.breakdown!!.distanceFee, 0.001)
        assertEquals(4277.0, quote.fee!!, 0.001)
        assertTrue("total fee should exceed zero even when distance is free", quote.fee!! > 0)
    }

    @Test
    fun `breakdown total agrees with the quoted fee`() {
        val json = """
            {
              "success": true, "distance": 4.2, "fee": 6770, "is_free": false,
              "breakdown": {
                "distance_fee": 2940, "service_fee": 1800,
                "insurance_fee": 677, "processing_fee": 1353
              }
            }
        """.trimIndent()

        val quote = gson.fromJson(json, DeliveryQuoteResponse::class.java)

        // 2940 + 1800 + 677 + 1353 = 6770
        assertEquals(quote.fee!!, quote.breakdown!!.total, 0.001)
    }

    @Test
    fun `parses a geocoding failure without throwing`() {
        val json = """
            {
              "success": false,
              "error": "Unable to determine delivery location. Please provide a more specific address or enable location services.",
              "error_code": "GEOCODE_FAILED"
            }
        """.trimIndent()

        val quote = gson.fromJson(json, DeliveryQuoteResponse::class.java)

        assertFalse(quote.success)
        assertNull("fee must be null so the UI cannot treat a failure as free", quote.fee)
        assertEquals("GEOCODE_FAILED", quote.errorCode)
        assertNotNull(quote.error)
    }

    @Test
    fun `missing breakdown does not crash parsing`() {
        val quote = gson.fromJson(
            """{"success": true, "distance": 2.0, "fee": 3000, "is_free": false}""",
            DeliveryQuoteResponse::class.java
        )

        assertEquals(3000.0, quote.fee!!, 0.001)
        assertNull(quote.breakdown)
    }

    @Test
    fun `request serialises with the field names the endpoint reads`() {
        val json = gson.toJson(
            DeliveryQuoteRequest(
                address = "Ntinda",
                area = "Kampala",
                orderValue = 45000.0,
                lat = 0.35,
                lng = 32.6
            )
        )

        // order_value, not orderValue — the PHP reads $input['order_value'].
        assertTrue("expected order_value in $json", json.contains("\"order_value\""))
        assertTrue(json.contains("\"address\""))
        assertTrue(json.contains("\"area\""))
        assertTrue(json.contains("\"lat\""))
        assertTrue(json.contains("\"lng\""))
    }
}
