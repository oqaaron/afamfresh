package com.techaus.afamfresh.models

import com.google.gson.JsonParser
import com.techaus.afamfresh.api.afamFreshGson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wholesale listing contract, both directions.
 *
 * These matter because the two listing kinds share one endpoint, one table and
 * one request model, and the server picks its rules from the SELLER's
 * business_type rather than from anything in the body. So a body that is wrong
 * for the branch it lands in is not rejected as malformed — it is rejected for
 * a reason that reads as a validation failure ("A wholesale price is
 * required"), or worse, accepted with a field silently ignored.
 *
 * Field names below are read from the wholesaler branch of
 * api/api/Bulk-listings.php, not invented.
 */
class WholesaleListingContractTest {

    private val gson = afamFreshGson()

    private fun json(o: Any) = JsonParser.parseString(gson.toJson(o)).asJsonObject

    // ==================================================================
    // Outbound — what the wholesale form sends
    // ==================================================================

    private fun wholesaleRequest(
        wholesalePrice: Double = 4500.0,
        minOrder: Double = 10.0,
        quantity: Double = 250.0,
        retail: Double? = null
    ) = CreateBulkListingRequest(
        userId = 7,
        productId = 42,
        originalPrice = retail ?: 0.0,
        discountPercent = 0.0,
        BulkQuantity = quantity,
        expiryDate = null,
        wholesalePrice = wholesalePrice,
        minOrderQuantity = minOrder,
        listingType = "wholesale",
        description = "Grade A matooke",
        pickupOnly = false,
        weightPerUnitKg = 1.0,
        isWeightBased = true
    )

    @Test
    fun `wholesale request carries the two fields the server requires`() {
        val body = json(wholesaleRequest())

        // The exact keys the PHP reads:
        //   $wholesale_price    = floatval($input['wholesale_price'] ?? 0);
        //   $min_order_quantity = floatval($input['min_order_quantity'] ?? 0);
        assertEquals(4500.0, body["wholesale_price"].asDouble, 0.001)
        assertEquals(10.0, body["min_order_quantity"].asDouble, 0.001)
        assertEquals("wholesale", body["listing_type"].asString)
    }

    @Test
    fun `wholesale request omits expiry_date entirely`() {
        val body = json(wholesaleRequest())

        // Gson drops nulls, and the server reads `$input['expiry_date'] ?? ''`
        // — which yields '' for an absent key. Sending an explicit JSON null
        // would also satisfy ??, but relying on that is needless: omitting the
        // key is unambiguous on both sides.
        assertFalse(
            "expiry_date must not be serialised for wholesale",
            body.has("expiry_date")
        )
    }

    @Test
    fun `wholesale request sends zero discount, not a computed one`() {
        // discount_percent is DECIMAL(5,2) NOT NULL DEFAULT 0.00 as of the
        // wholesale migration. The server computes a DISPLAY percentage from
        // the RRP itself; a client-computed value here would be overwritten at
        // best and disagree with the stored price at worst.
        val body = json(wholesaleRequest(retail = 6000.0))
        assertEquals(0.0, body["discount_percent"].asDouble, 0.001)
    }

    @Test
    fun `retail reference goes in original_price, and is zero when omitted`() {
        // The server's test is `$original_price > 0`, so "no RRP" has to be 0
        // rather than null — a null would become 0.0 via floatval anyway, but
        // the distinction is worth pinning because the UI treats blank as
        // meaningful.
        assertEquals(0.0, json(wholesaleRequest(retail = null))["original_price"].asDouble, 0.001)
        assertEquals(
            6000.0,
            json(wholesaleRequest(retail = 6000.0))["original_price"].asDouble,
            0.001
        )
    }

    @Test
    fun `decimal quantities survive serialisation`() {
        // The columns are DECIMAL(12,3). This is the bug class that truncated
        // 20.5 kg to 21 before the decimal migration, so it is worth holding.
        val body = json(wholesaleRequest(minOrder = 2.5, quantity = 20.5))
        assertEquals(2.5, body["min_order_quantity"].asDouble, 0.0001)
        assertEquals(20.5, body["Bulk_quantity"].asDouble, 0.0001)
    }

    // ==================================================================
    // The surplus path must be untouched by all of the above
    // ==================================================================

    @Test
    fun `surplus request is unchanged - no wholesale keys, expiry present`() {
        val surplus = CreateBulkListingRequest(
            userId = 7,
            productId = 42,
            originalPrice = 10000.0,
            discountPercent = 40.0,
            BulkQuantity = 12.0,
            expiryDate = "2026-08-20 23:59:59",
            listingType = "goodie_bag",
            description = "",
            conditionRating = "good",
            pickupOnly = false,
            weightPerUnitKg = 1.0,
            isWeightBased = true
        )
        val body = json(surplus)

        assertEquals("2026-08-20 23:59:59", body["expiry_date"].asString)
        assertEquals(40.0, body["discount_percent"].asDouble, 0.001)
        assertEquals("goodie_bag", body["listing_type"].asString)

        // The new fields default to null and Gson omits them, so a surplus
        // body is byte-for-byte what it was before wholesale existed. If these
        // ever appear, the server's surplus branch would read a wholesale
        // price it has no business having.
        assertFalse(body.has("wholesale_price"))
        assertFalse(body.has("min_order_quantity"))
    }

    // ==================================================================
    // The local pre-flight check — surplus only
    // ==================================================================

    @Test
    fun `a wholesale request is exempt from the discount range`() {
        // The regression this pins: VendorRepository.createListing rejected
        // ANY request outside DISCOUNT_RANGE, so every wholesale submission
        // failed on the device before a request was ever sent, with a message
        // about a discount the wholesale form does not ask for.
        assertNull(wholesaleRequest().localDiscountRejection())
        assertNull(wholesaleRequest(retail = 6000.0).localDiscountRejection())
    }

    @Test
    fun `a surplus request is still held to the discount range`() {
        fun surplus(pct: Double) = CreateBulkListingRequest(
            userId = 7,
            productId = 42,
            originalPrice = 10000.0,
            discountPercent = pct,
            BulkQuantity = 12.0,
            expiryDate = "2026-08-20 23:59:59",
            listingType = "goodie_bag"
        )

        // Both ends of the range are accepted...
        assertNull(surplus(10.0).localDiscountRejection())
        assertNull(surplus(70.0).localDiscountRejection())
        assertNull(surplus(40.0).localDiscountRejection())

        // ...and outside it, still refused, with the bounds named.
        val tooLow = surplus(9.0).localDiscountRejection()
        assertTrue("below the floor must be refused", tooLow != null)
        assertTrue("message should name the range", tooLow!!.contains("10%"))
        assertTrue(surplus(71.0).localDiscountRejection() != null)

        // 0 is what a wholesale listing carries; on the surplus path it is
        // still a missing discount and must not slip through.
        assertTrue(surplus(0.0).localDiscountRejection() != null)
    }

    // ==================================================================
    // Inbound — what GET returns
    // ==================================================================

    @Test
    fun `wholesale listing parses, with null expiry and a decimal minimum`() {
        // Shaped like `SELECT sl.*` plus the joins, with the wholesale
        // migration's nullable columns actually null.
        val listing = gson.fromJson(
            """
            {
              "id": 31, "vendor_id": 4, "product_id": 42,
              "original_price": null,
              "discount_percent": "0.00",
              "discounted_price": "4500.00",
              "Bulk_quantity": "250.000",
              "remaining_quantity": "247.500",
              "min_order_quantity": "10.500",
              "expiry_date": null,
              "listing_type": "wholesale",
              "status": "approved",
              "product_name": "Matooke",
              "business_name": "Kalerwe Wholesalers",
              "quantitytype": "kg"
            }
            """.trimIndent(),
            BulkListing::class.java
        )

        assertTrue(listing.isWholesale)
        assertNull("wholesale stock does not expire", listing.expiryDate)
        assertEquals(10.5, listing.minOrderQuantity, 0.0001)
        assertEquals(247.5, listing.remainingQuantity, 0.0001)
        assertEquals(4500.0, listing.discountedPrice, 0.001)

        // No RRP was given, so there is no saving to advertise.
        assertEquals(0.0, listing.originalPrice, 0.001)
        assertFalse(listing.hasRetailReference)
    }

    @Test
    fun `wholesale listing with an RRP reports a retail reference`() {
        val listing = gson.fromJson(
            """
            {
              "id": 32, "listing_type": "wholesale",
              "original_price": "6000.00", "discounted_price": "4500.00",
              "min_order_quantity": "10.000", "expiry_date": null
            }
            """.trimIndent(),
            BulkListing::class.java
        )
        assertTrue(listing.isWholesale)
        assertTrue(listing.hasRetailReference)
    }

    @Test
    fun `a surplus listing is not wholesale and has no minimum`() {
        val listing = gson.fromJson(
            """
            {
              "id": 12, "listing_type": "final_days",
              "original_price": "10000.00", "discount_percent": "40.00",
              "discounted_price": "6000.00",
              "expiry_date": "2026-08-20 23:59:59"
            }
            """.trimIndent(),
            BulkListing::class.java
        )
        assertFalse(listing.isWholesale)
        // Defaults to 0, NOT 1 — the dashboard row hides the minimum when it
        // is zero, and a default of 1 would print "Minimum order 1" on every
        // surplus deal ever posted.
        assertEquals(0.0, listing.minOrderQuantity, 0.0001)
    }

    // ==================================================================
    // The quote's verdict — what stops the app hardcoding the limits
    // ==================================================================

    @Test
    fun `a blocked quote carries the reason and the applicable limits`() {
        val q = gson.fromJson(
            """
            {
              "success": true, "goods_total": 4500, "delivery_fee": 8000,
              "grand_total": 12500, "total_weight_kg": 1.0,
              "exceeds_stock": false,
              "can_order": false,
              "blocked_reason": "This seller's minimum order is 10. You asked for 1.",
              "blocked_code": "BELOW_MIN_QUANTITY",
              "limits": {
                "is_wholesale": true, "min_quantity": 10.0,
                "min_order_value": 0.0, "min_weight_kg": 0.0, "max_weight_kg": 1000.0
              }
            }
            """.trimIndent(),
            BulkQuoteResponse::class.java
        )

        assertFalse(q.canOrder)
        assertEquals("BELOW_MIN_QUANTITY", q.blockedCode)
        assertTrue(q.blockedReason!!.contains("minimum order is 10"))

        // Only the wholesale rule is populated; the surplus floors arrive as 0
        // so the screen renders nothing for them.
        assertEquals(10.0, q.limits!!.minQuantity, 0.0001)
        assertEquals(0.0, q.limits!!.minOrderValue, 0.0001)
        assertEquals(0.0, q.limits!!.minWeightKg, 0.0001)
        assertEquals(10.0, q.limits!!.smallestQuantity!!, 0.0001)
    }

    @Test
    fun `a surplus quote surfaces the platform floors instead`() {
        val q = gson.fromJson(
            """
            {
              "success": true, "can_order": true,
              "limits": {
                "is_wholesale": false, "min_quantity": 0.0,
                "min_order_value": 250000.0, "min_weight_kg": 20.0, "max_weight_kg": 1000.0
              }
            }
            """.trimIndent(),
            BulkQuoteResponse::class.java
        )
        assertTrue(q.canOrder)
        assertEquals(250000.0, q.limits!!.minOrderValue, 0.001)
        // The stepper floor comes from the weight minimum here, not a constant.
        assertEquals(20.0, q.limits!!.smallestQuantity!!, 0.0001)
    }

    @Test
    fun `an admin-changed floor flows through without an app release`() {
        // The point of moving these server-side: the app must show whatever
        // the setting currently is, not what it was when the APK was built.
        val q = gson.fromJson(
            """
            {"success": true, "can_order": true,
             "limits": {"is_wholesale": false, "min_order_value": 99000.0,
                        "min_weight_kg": 5.0, "max_weight_kg": 500.0}}
            """.trimIndent(),
            BulkQuoteResponse::class.java
        )
        assertEquals(99000.0, q.limits!!.minOrderValue, 0.001)
        assertEquals(5.0, q.limits!!.smallestQuantity!!, 0.0001)
        assertEquals(500.0, q.limits!!.maxWeightKg, 0.001)
    }

    @Test
    fun `a quote from an older server does not block ordering`() {
        // can_order defaults to true and limits to null, so a server that has
        // not shipped these fields behaves exactly as before rather than
        // locking the customer out of checkout.
        val q = gson.fromJson(
            """{"success": true, "goods_total": 300000, "grand_total": 308000}""",
            BulkQuoteResponse::class.java
        )
        assertTrue(q.canOrder)
        assertNull(q.limits)
        assertNull(q.blockedReason)
    }

    @Test
    fun `no minimum to show leaves the stepper floor to the caller`() {
        val q = gson.fromJson(
            """{"success": true, "can_order": true,
                "limits": {"is_wholesale": false, "min_quantity": 0.0,
                           "min_order_value": 250000.0, "min_weight_kg": 0.0,
                           "max_weight_kg": 1000.0}}""",
            BulkQuoteResponse::class.java
        )
        // A counted surplus listing has only a VALUE floor, which depends on
        // price — there is no quantity the stepper can be clamped to.
        assertNull(q.limits!!.smallestQuantity)
    }

    // ==================================================================
    // Who completes a delivery
    // ==================================================================

    private fun order(pickupOnly: Boolean, riderAt: String?) = gson.fromJson(
        """
        {
          "id": 5, "status": "ready", "payment_status": "paid",
          "pickup_only": ${if (pickupOnly) 1 else 0},
          "rider_assigned_at": ${riderAt?.let { "\"$it\"" } ?: "null"}
        }
        """.trimIndent(),
        BulkOrder::class.java
    )

    @Test
    fun `only a collection order is the seller's to mark delivered`() {
        // The one case where there is no rider in the flow at all.
        assertTrue(order(pickupOnly = true, riderAt = null).sellerCompletesDelivery)

        // A delivery order with nobody dispatched: nothing has been delivered
        // to anyone, and marking it would credit the seller for produce still
        // in their own store.
        val undispatched = order(pickupOnly = false, riderAt = null)
        assertFalse(undispatched.sellerCompletesDelivery)
        assertTrue(undispatched.awaitingDispatch)

        // A rider holds it — theirs to complete, and the credit rides along.
        val withRider = order(pickupOnly = false, riderAt = "2026-08-17 10:00:00")
        assertFalse(withRider.sellerCompletesDelivery)
        assertFalse(withRider.awaitingDispatch)
        assertTrue(withRider.hasRider)
    }

    @Test
    fun `a dispatched collection order still belongs to the rider`() {
        // Shouldn't happen — a pickup listing should never be dispatched — but
        // if an admin does it by mistake, the person physically holding the
        // goods wins over the flag on the listing.
        val odd = order(pickupOnly = true, riderAt = "2026-08-17 10:00:00")
        assertFalse(odd.sellerCompletesDelivery)
        assertFalse(odd.awaitingDispatch)
    }

    @Test
    fun `pickup_only survives the tinyint the server actually sends`() {
        // PDO returns tinyint columns as the strings "0"/"1", which is the
        // whole reason afamFreshGson() exists. A pickup order misread as a
        // delivery would hide the seller's only way to complete it.
        assertTrue(order(pickupOnly = true, riderAt = null).pickupOnly)
        assertFalse(order(pickupOnly = false, riderAt = null).pickupOnly)
    }

    // ==================================================================
    // The seller-type branch the UI keys off
    // ==================================================================

    @Test
    fun `isWholesaler is true only for the wholesaler business type`() {
        fun profile(type: String?) = VendorProfile(id = 1, businessType = type)

        assertTrue(profile("wholesaler").isWholesaler)
        assertFalse(profile("market_vendor").isWholesaler)
        assertFalse(profile("farmer").isWholesaler)
        // Null while VendorViewModel.start() is still resolving the record.
        // False is the safe answer: it shows the existing surplus screens.
        assertFalse(profile(null).isWholesaler)
    }

    @Test
    fun `business_type is never sent on a profile update`() {
        // The escalation this closes: business_type decides which listing
        // rules apply, so a vendor who could write it from their own profile
        // form could unlock flat wholesale pricing for themselves.
        val body = json(
            UpdateVendorProfileRequest(
                businessName = "Kalerwe Wholesalers",
                phone = "0700000000",
                location = "Kampala"
            )
        )
        assertFalse(
            "vendor-profile.php no longer accepts business_type",
            body.has("business_type")
        )
        assertEquals("Kalerwe Wholesalers", body["business_name"].asString)
    }
}
