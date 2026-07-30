package com.techaus.afamfresh.models

import com.techaus.afamfresh.api.afamFreshGson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the endpoints whose real shape differed from what the app
 * assumed. Every JSON string below is the shape the live PHP produces, taken
 * from the source and the database â€” not invented.
 *
 * These exist because the mismatches they cover were all silent: Gson fills a
 * missing field with null or a default rather than throwing, so a wrong
 * @SerializedName shows up as a blank screen or a disabled feature, never as a
 * crash.
 */
class BackendContractTest {

    // The production Gson, so these tests exercise the real tinyint handling.
    private val gson = afamFreshGson()

    // ==================================================================
    // config.php â€” nested key/value strings, not flat booleans
    // ==================================================================

    private val realConfigJson = """
        {
          "success": true,
          "config": {
            "current_version": "1.0",
            "is_maintenance_mode": "0",
            "maintenance_message": "We are updating our stock. Back soon!",
            "min_version_required": "1.0",
            "surplus_approval_required": "1"
          }
        }
    """.trimIndent()

    @Test
    fun `config parses the nested key value map`() {
        val config = gson.fromJson(realConfigJson, AppConfigResponse::class.java)

        assertTrue(config.success)
        assertFalse("is_maintenance_mode is \"0\"", config.maintenanceMode)
        assertEquals("We are updating our stock. Back soon!", config.maintenanceMessage)
        assertEquals("1.0", config.minVersionRequired)
        assertTrue(config.surplusApprovalRequired)
    }

    @Test
    fun `maintenance mode reads the string flag`() {
        val on = gson.fromJson(
            """{"success":true,"config":{"is_maintenance_mode":"1"}}""",
            AppConfigResponse::class.java
        )
        assertTrue(on.maintenanceMode)
    }

    /**
     * The old model looked for a top-level `maintenance_mode` boolean. The
     * server never sends one, so this asserts the app no longer depends on it.
     */
    @Test
    fun `absent config does not enable maintenance mode`() {
        val empty = gson.fromJson("""{"success":true}""", AppConfigResponse::class.java)

        assertFalse(empty.maintenanceMode)
        assertNull(empty.maintenanceMessage)
        assertFalse("a missing config must not lock users out", empty.requiresUpdate("1.0"))
    }

    @Test
    fun `version comparison is numeric per component, not lexicographic`() {
        val config = gson.fromJson(
            """{"success":true,"config":{"min_version_required":"1.10"}}""",
            AppConfigResponse::class.java
        )

        // "1.9" < "1.10" numerically, even though it sorts after as a string.
        assertTrue(config.requiresUpdate("1.9"))
        assertFalse(config.requiresUpdate("1.10"))
        assertFalse(config.requiresUpdate("2.0"))
    }

    @Test
    fun `current version is not treated as out of date`() {
        val config = gson.fromJson(realConfigJson, AppConfigResponse::class.java)
        assertFalse(config.requiresUpdate("1.0"))
    }

    @Test
    fun `unparseable version does not force an update`() {
        val config = gson.fromJson(
            """{"success":true,"config":{"min_version_required":"beta-2"}}""",
            AppConfigResponse::class.java
        )
        assertFalse(config.requiresUpdate("1.0"))
    }

    // ==================================================================
    // notifications.php â€” user_notifications columns
    // ==================================================================

    @Test
    fun `notification parses message is_read and link`() {
        // Row 192 as it exists in the database.
        val json = """
            {
              "success": true,
              "notifications": [
                {
                  "id": 192,
                  "user_id": 100,
                  "title": "New Order Received",
                  "message": "Order #500191 has been placed.",
                  "type": "order",
                  "is_read": 0,
                  "link": "vendor-orders.php?view=order&id=500191",
                  "created_at": "2026-07-21 09:52:44"
                }
              ]
            }
        """.trimIndent()

        val response = gson.fromJson(json, NotificationsResponse::class.java)
        val item = response.notifications!!.single()

        assertEquals(192, item.id)
        assertEquals("Order #500191 has been placed.", item.message)
        // tinyint(1) 0/1 must map onto Boolean.
        assertFalse(item.isRead)
        assertEquals("order", item.type)
    }

    @Test
    fun `is_read 1 parses as read`() {
        val item = gson.fromJson(
            """{"id":1,"title":"t","message":"m","is_read":1}""",
            AppNotification::class.java
        )
        assertTrue(item.isRead)
    }

    /**
     * There is no order_id column. The order has to be recovered from `link`,
     * which appears in three different shapes across the real data.
     */
    @Test
    fun `order id is extracted from the link query parameter`() {
        val item = AppNotification(link = "vendor-orders.php?view=order&id=500191")
        assertEquals("500191", item.orderId)
    }

    @Test
    fun `a bare numeric link is the order id`() {
        assertEquals("500149", AppNotification(link = "500149").orderId)
    }

    @Test
    fun `a link naming no order yields null`() {
        assertNull(AppNotification(link = "user-dashboard.php").orderId)
        assertNull(AppNotification(link = null).orderId)
        assertNull(AppNotification(link = "  ").orderId)
    }

    @Test
    fun `unread count parses`() {
        val response = gson.fromJson(
            """{"success":true,"count":7}""",
            UnreadCountResponse::class.java
        )
        assertEquals(7, response.count)
    }

    @Test
    fun `not logged in reply parses without throwing`() {
        val response = gson.fromJson(
            """{"success":false,"error":"Not logged in"}""",
            NotificationsResponse::class.java
        )

        assertFalse(response.success)
        assertNull(response.notifications)
        assertEquals("Not logged in", response.error)
    }

    // ==================================================================
    // products.php â€” raw `items` columns
    // ==================================================================

    @Test
    fun `product parses the real items columns`() {
        // Row 30 as it exists in the database. Note price is a STRING: the
        // column is VARCHAR(50).
        val json = """
            {
              "id": 30,
              "name": "Apples Red 500g",
              "category": "Fruits",
              "description": "Imported red apples",
              "price": "9500",
              "quantity": "500",
              "quantitytype": "Grams",
              "image": "apples.jpg",
              "discount": "0.00",
              "homepage": "NO",
              "offer": "NO",
              "freshness_date": null,
              "stock_qty": 999,
              "weekly_deal": "NO"
            }
        """.trimIndent()

        val product = gson.fromJson(json, Product::class.java)

        assertEquals(30, product.id)
        assertEquals("Apples Red 500g", product.name)
        // Gson coerces the VARCHAR price to Double.
        assertEquals(9500.0, product.price, 0.001)
        assertEquals("500 Grams", product.packLabel)
        assertTrue(product.inStock)
        assertFalse(product.hasDiscount)
        assertFalse(product.isWeeklyDeal)
        assertEquals(9500.0, product.effectivePrice, 0.001)
    }

    @Test
    fun `discount reduces the effective price`() {
        val product = gson.fromJson(
            """{"id":1,"name":"x","price":"10000","discount":"25.00","stock_qty":5}""",
            Product::class.java
        )

        assertTrue(product.hasDiscount)
        assertEquals(7500.0, product.effectivePrice, 0.001)
    }

    @Test
    fun `zero stock reads as out of stock`() {
        val product = gson.fromJson(
            """{"id":1,"name":"x","price":"100","stock_qty":0}""",
            Product::class.java
        )
        assertFalse(product.inStock)
    }

    /**
     * Stock is not tracked yet â€” every row sits at the column default of 999 â€”
     * so an absent field must not read as a stockout and block the sale.
     */
    @Test
    fun `absent stock field does not block the sale`() {
        val product = gson.fromJson("""{"id":1,"name":"x","price":"100"}""", Product::class.java)
        assertTrue(product.inStock)
    }

    @Test
    fun `product detail is wrapped in an envelope`() {
        val response = gson.fromJson(
            """{"success":true,"product":{"id":4,"name":"Apple","price":"1300"}}""",
            ProductDetailResponse::class.java
        )

        assertTrue(response.success)
        assertEquals(4, response.product!!.id)
    }

    @Test
    fun `product not found parses as a failure, not an empty product`() {
        val response = gson.fromJson(
            """{"success":false,"error":"Product not found"}""",
            ProductDetailResponse::class.java
        )

        assertFalse(response.success)
        assertNull(response.product)
    }

    // ==================================================================
    // orders.php â€” normalised order shape
    // ==================================================================

    @Test
    fun `order parses the normalised names and nested items`() {
        // Exactly what the restored `action=list` branch returns for order
        // 500930, captured from a real run against the database.
        val json = """
            {
              "success": true,
              "orders": [
                {
                  "id": "500930",
                  "status": "Received",
                  "current_status": "pending",
                  "payment_status": "pending",
                  "total": 28570.51,
                  "created_at": "2026-07-21 09:52:44",
                  "fname": "24",
                  "lname": "HOURS",
                  "mobile": "0785463210",
                  "area": "Kampala",
                  "address": "Ssonde Rd, Kampala, Uganda",
                  "delivery_address": "Ssonde Rd, Kampala, Uganda",
                  "delivery_fee": 6770.51,
                  "service_fee": 0,
                  "insurance_fee": 0,
                  "processing_fee": 0,
                  "small_order_surcharge": 0,
                  "scheduled_delivery_date": null,
                  "scheduled_delivery_slot": null,
                  "cancelled_at": null,
                  "delivered_at": null,
                  "items": [
                    {"product_id": 0, "product_name": "Eggs -Tray", "quantity": 1, "price": 13500},
                    {"product_id": 0, "product_name": "RWENZORI TEA BAGS", "quantity": 1, "price": 5800}
                  ]
                }
              ]
            }
        """.trimIndent()

        val response = gson.fromJson(json, OrdersResponse::class.java)
        val order = response.orders!!.single()

        assertEquals("500930", order.id)
        assertEquals(28570.51, order.total, 0.001)
        assertEquals(6770.51, order.deliveryFee, 0.001)
        assertEquals(6770.51, order.totalFees, 0.001)
        assertEquals(2, order.items.size)
        assertEquals("Eggs -Tray", order.items[0].productName)
        assertEquals(19300.0, order.itemsSubtotal, 0.001)
    }

    /**
     * Orders placed before the orders.php fix stored product_id = 0. Those lines
     * cannot be linked back to the catalogue, so the UI must be able to tell.
     */
    @Test
    fun `legacy zero product id is flagged as unlinked`() {
        val legacy = OrderItem(productId = 0, productName = "Eggs -Tray")
        val current = OrderItem(productId = 42, productName = "Eggs -Tray")

        assertFalse(legacy.isLinkedToCatalogue)
        assertTrue(current.isLinkedToCatalogue)
    }

    @Test
    fun `order editability matches the server's rule`() {
        // Real values from the status column.
        assertTrue(Order(status = "Received").isEditable)
        assertTrue(Order(status = "Awaiting Payment").isEditable)
        assertTrue(Order(status = "Preparing").isEditable)

        assertFalse(Order(status = "Out for Delivery").isEditable)
        assertFalse(Order(status = "Delivered").isEditable)
        assertFalse(Order(status = "Completed").isEditable)
        assertFalse(Order(status = "Cancelled").isEditable)

        assertTrue(Order(status = "Cancelled").isCancelled)
    }

    @Test
    fun `order detail is wrapped in an envelope`() {
        val response = gson.fromJson(
            """{"success":true,"order":{"id":"500930","status":"Received","total":100}}""",
            OrderDetailResponse::class.java
        )
        assertEquals("500930", response.order!!.id)
    }

    // ==================================================================
    // surplus-listings.php â€” joined surplus_listings row
    // ==================================================================

    @Test
    fun `surplus listing parses the joined shape`() {
        val json = """
            {
              "success": true,
              "listings": [
                {
                  "id": 12,
                  "vendor_id": 6,
                  "product_id": 30,
                  "original_price": "9500.00",
                  "discount_percent": "40.00",
                  "discounted_price": "5700.00",
                  "surplus_quantity": 10,
                  "remaining_quantity": 4,
                  "expiry_date": "2026-08-04 18:00:00",
                  "listing_type": "goodie_bag",
                  "status": "approved",
                  "condition_rating": "good",
                  "pickup_only": 0,
                  "business_name": "Afam Farm",
                  "product_name": "Apples Red 500g",
                  "quantitytype": "Grams",
                  "image": "apples.jpg"
                }
              ]
            }
        """.trimIndent()

        val listing = gson.fromJson(json, SurplusListingsResponse::class.java).listings!!.single()

        assertEquals("Apples Red 500g", listing.displayTitle)
        assertEquals("Afam Farm", listing.vendorDisplayName)
        assertEquals(5700.0, listing.discountedPrice, 0.001)
        assertEquals(40.0, listing.discountPercent, 0.001)
        assertEquals(4, listing.remainingQuantity)
        assertFalse(listing.pickupOnly)
        assertFalse(listing.isSoldOut)
        assertTrue(listing.isApproved)
    }

    @Test
    fun `surplus create request uses the field names the endpoint reads`() {
        val json = gson.toJson(
            CreateSurplusListingRequest(
                userId = 100,
                productId = 30,
                originalPrice = 9500.0,
                discountPercent = 40.0,
                surplusQuantity = 10,
                expiryDate = "2026-08-04 18:00:00"
            )
        )

        // user_id, NOT vendor_id â€” the endpoint resolves the vendor itself.
        assertTrue("expected user_id in $json", json.contains("\"user_id\""))
        assertTrue(json.contains("\"product_id\""))
        assertTrue(json.contains("\"discount_percent\""))
        assertTrue(json.contains("\"surplus_quantity\""))
        assertTrue(json.contains("\"expiry_date\""))
        // The server derives the discounted price; sending one would be ignored.
        assertFalse("must not send a price: $json", json.contains("\"discounted_price\""))
    }

    @Test
    fun `the discount range matches the server's validation`() {
        val range = CreateSurplusListingRequest.DISCOUNT_RANGE

        assertTrue(30.0 in range)
        assertTrue(70.0 in range)
        assertFalse(29.9 in range)
        assertFalse(70.1 in range)
    }

    // ==================================================================
    // vendor-products.php / vendor-profile.php
    // ==================================================================

    @Test
    fun `vendor product parses and null price means catalogue price`() {
        val json = """
            {
              "success": true,
              "products": [
                {
                  "id": 3, "vendor_id": 6, "product_id": 30,
                  "stock_quantity": 12, "price": null, "is_active": 1,
                  "product_name": "Apples Red 500g", "category": "Fruits",
                  "quantitytype": "Grams"
                }
              ]
            }
        """.trimIndent()

        val product = gson.fromJson(json, VendorProductsResponse::class.java).products!!.single()

        assertEquals("Apples Red 500g", product.displayName)
        assertNull(product.price)
        assertTrue(product.inStock)
        // isActive is nullable so absence is distinguishable from false; isListed
        // is the resolved answer.
        assertTrue(product.isListed)
    }

    /** An absent is_active must not hide the product. */
    @Test
    fun `vendor product with no is_active field stays listed`() {
        val product = gson.fromJson(
            """{"id":1,"vendor_id":6,"product_id":30,"stock_quantity":3}""",
            VendorProduct::class.java
        )

        assertNull(product.isActive)
        assertTrue(product.isListed)
    }

    @Test
    fun `vendor product is_active zero unlists it`() {
        val product = gson.fromJson(
            """{"id":1,"vendor_id":6,"product_id":30,"is_active":0}""",
            VendorProduct::class.java
        )
        assertFalse(product.isListed)
    }

    @Test
    fun `vendor profile carries the vendor id the other endpoints need`() {
        val json = """
            {
              "success": true,
              "vendor": {
                "id": 6, "user_id": 100, "business_name": "Afam Farm",
                "business_type": "farmer", "phone": "0785463210",
                "is_verified": 1, "rating": "4.50", "commission_rate": "10.00"
              }
            }
        """.trimIndent()

        val vendor = gson.fromJson(json, VendorProfileResponse::class.java).vendor!!

        assertEquals(6, vendor.id)
        assertEquals(100, vendor.userId)
        assertTrue(vendor.isVerified)
        assertEquals(4.5, vendor.rating, 0.001)
    }

    @Test
    fun `vendor not found parses as a failure`() {
        val response = gson.fromJson(
            """{"error":"Vendor not found"}""",
            VendorProfileResponse::class.java
        )

        assertFalse(response.success)
        assertNull(response.vendor)
        assertEquals("Vendor not found", response.error)
    }

    // ==================================================================
    // surplus-orders.php
    // ==================================================================

    @Test
    fun `surplus order parses the joined vendor view`() {
        val json = """
            {
              "success": true,
              "orders": [
                {
                  "id": 5, "listing_id": 12, "user_id": 100,
                  "quantity": 2, "total_price": "11400.00",
                  "status": "pending", "delivery_fee": "3000.00",
                  "total_weight_kg": "1.00",
                  "product_name": "Apples Red 500g",
                  "business_name": "Afam Farm",
                  "customer_fname": "Aaron", "customer_lname": "Okwi"
                }
              ]
            }
        """.trimIndent()

        val order = gson.fromJson(json, VendorOrdersResponse::class.java).orders!!.single()

        assertEquals(5, order.id)
        assertEquals(11400.0, order.totalPrice, 0.001)
        assertEquals("Apples Red 500g", order.displayTitle)
        assertEquals("Aaron Okwi", order.customerName)
        assertFalse(order.isTerminal)
    }

    @Test
    fun `delivered surplus order is terminal`() {
        assertTrue(SurplusOrder(status = "delivered").isTerminal)
        assertTrue(SurplusOrder(status = "cancelled").isTerminal)
        assertFalse(SurplusOrder(status = "processing").isTerminal)
    }
}

