package com.techaus.afamfresh.models

import com.google.gson.annotations.SerializedName

/**
 * ✅ VERIFIED against `api/orders.php` (actions list / detail / create).
 *
 * The endpoint normalises the legacy `orders` columns before encoding, so the
 * wire names below are the mapped ones, not the raw table names:
 *
 *   orderid      -> id
 *   total_amount -> total
 *   ordertime    -> created_at
 *
 * Dropped from the previous model because no such column exists and the server
 * cannot send them:
 *
 *   payment_method  — orders has `payment_status` only
 *   delivery_notes  — never existed on `orders`
 */
data class Order(
    @SerializedName("id") val id: String = "",
    @SerializedName("status") val status: String = "",

    /** Coarser machine-readable state: pending | ready | on_way | delivered. */
    @SerializedName("current_status") val currentStatus: String? = null,

    /** pending | authorization_pending | (Pesapal states) */
    @SerializedName("payment_status") val paymentStatus: String? = null,

    @SerializedName("items") val items: List<OrderItem> = emptyList(),

    /** `total_amount` — the full charge including all fees. */
    @SerializedName("total") val total: Double = 0.0,

    @SerializedName("fname") val fname: String? = null,
    @SerializedName("lname") val lname: String? = null,
    @SerializedName("mobile") val mobile: String? = null,
    @SerializedName("area") val area: String? = null,
    @SerializedName("address") val address: String? = null,
    @SerializedName("landmark_notes") val landmarkNotes: String? = null,

    /** The geocoded destination; `address` is what the customer typed. */
    @SerializedName("delivery_address") val deliveryAddress: String? = null,

    // ----- fee breakdown, as stored on the order -----
    @SerializedName("delivery_fee") val deliveryFee: Double = 0.0,
    @SerializedName("service_fee") val serviceFee: Double = 0.0,
    @SerializedName("insurance_fee") val insuranceFee: Double = 0.0,
    @SerializedName("processing_fee") val processingFee: Double = 0.0,
    @SerializedName("small_order_surcharge") val smallOrderSurcharge: Double = 0.0,

    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("scheduled_delivery_date") val scheduledDeliveryDate: String? = null,
    @SerializedName("scheduled_delivery_slot") val scheduledDeliverySlot: String? = null,
    @SerializedName("cancelled_at") val cancelledAt: String? = null,
    @SerializedName("delivered_at") val deliveredAt: String? = null,

    // ----- detail only -----
    @SerializedName("delivery_person") val deliveryPerson: String? = null,
    @SerializedName("estimated_delivery") val estimatedDelivery: String? = null,
    @SerializedName("dest_lat") val destLat: Double? = null,
    @SerializedName("dest_lng") val destLng: Double? = null,

    // ----- customer confirm-and-rate -----
    // `deliveryConfirmed` is the RIDER's proof-of-delivery attestation, not
    // the customer's — see includes/order_feedback.php on the server. Once
    // true, and `completedAt` is still null, the app can offer "Confirm & Rate".
    @SerializedName("delivery_confirmed") val deliveryConfirmed: Boolean = false,
    @SerializedName("completed_at") val completedAt: String? = null,
    @SerializedName("customer_rating") val customerRating: Int? = null
) {
    /** Everything charged on top of the goods. */
    val totalFees: Double
        get() = deliveryFee + serviceFee + insuranceFee + processingFee + smallOrderSurcharge

    /** Goods value, derived — the server does not send a subtotal. */
    val itemsSubtotal: Double
        get() = items.sumOf { it.lineTotal }

    val isCancelled: Boolean get() = status.equals("Cancelled", ignoreCase = true)

    /**
     * Mirrors `isOrderEditable()` in orders.php. Kept in sync deliberately so
     * the UI can hide Edit/Cancel instead of offering them and then showing the
     * server's refusal.
     */
    val isEditable: Boolean
        get() = status.trim().lowercase() in setOf(
            "received", "pending", "awaiting payment", "awaiting confirmation", "preparing"
        )

    val customerName: String
        get() = listOfNotNull(fname, lname).joinToString(" ").trim()

    /** Delivered by the rider, not yet confirmed by the customer. */
    val needsReceiptConfirmation: Boolean
        get() = deliveryConfirmed && completedAt == null
}

/**
 * ✅ VERIFIED. `order_items` columns as returned by orders.php.
 *
 * `name` was wrong — the column is `product_name`. `product_id` is int(11), not
 * a Double.
 */
data class OrderItem(
    @SerializedName("product_id") val productId: Int = 0,
    @SerializedName("product_name") val productName: String = "",
    @SerializedName("price") val price: Double = 0.0,
    @SerializedName("quantity") val quantity: Int = 1
) {
    val lineTotal: Double get() = price * quantity

    /**
     * Orders placed before the `product_id` fix in orders.php stored 0, because
     * the endpoint read `id` while the app sent `product_id`. Those rows cannot
     * be linked back to the catalogue, so "buy again" must be hidden for them.
     */
    val isLinkedToCatalogue: Boolean get() = productId > 0
}

data class OrdersResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("orders") val orders: List<Order>? = null,
    @SerializedName("error") val error: String? = null
)

/** `action=detail` wraps the order: {"success":true,"order":{...}}. */
data class OrderDetailResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("order") val order: Order? = null,
    @SerializedName("error") val error: String? = null
)

/**
 * Body for `loyalty-quote.php` — shared by shop and Bulk checkout, since
 * the calculation is identical once you have a goods value: how many of the
 * REQUESTED points can actually be redeemed, and the resulting discount.
 * Read-only; writes nothing.
 */
data class LoyaltyQuoteRequest(
    @SerializedName("points") val points: Int,
    @SerializedName("goods_value") val goodsValue: Double
)

data class LoyaltyQuoteResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("points_applied") val pointsApplied: Int = 0,
    @SerializedName("discount") val discount: Double = 0.0,
    /** True when the requested amount got capped by max_redeem_percent. */
    @SerializedName("capped") val capped: Boolean = false,
    @SerializedName("balance") val balance: Int = 0,
    @SerializedName("error") val error: String? = null
)

data class OrderCreateResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("order_id") val orderId: String? = null,
    @SerializedName("message") val message: String? = null,
    @SerializedName("error") val error: String? = null,
    // Both null on any app build that never sends points_redeem — additive,
    // backward compatible.
    @SerializedName("points_applied") val pointsApplied: Int? = null,
    @SerializedName("loyalty_discount") val loyaltyDiscount: Double? = null
)

// Confirmed field names — this exact shape is constructed in MainScreen.kt's
// DeliveryMapScreen -> onLocationSelected callback.
data class DeliveryResult(
    val pickupAddress: String,
    val dropoffAddress: String,
    val pickupLat: Double,
    val pickupLng: Double,
    val dropoffLat: Double,
    val dropoffLng: Double,
    val distanceKm: Double,
    val cost: Double
)

// NOTE: DeliveryAreasResponse and DeliveryLocationResponse were removed along
// with the `delivery.php` endpoints they belonged to — that file does not exist
// in api/, and nothing referenced these types.
