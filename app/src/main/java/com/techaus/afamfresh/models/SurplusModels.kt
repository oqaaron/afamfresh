package com.techaus.afamfresh.models

import com.google.gson.annotations.SerializedName

/**
 * ✅ VERIFIED against `api/Bulk-listings.php` and the `Bulk_listings`
 * table. The previous version of this file was inferred from the app's own
 * (wrong) ApiService field list, and not one of its names existed on the wire:
 *
 *   title        -> product_name      (joined from items.name)
 *   price        -> discounted_price
 *   quantity     -> Bulk_quantity / remaining_quantity
 *   unit         -> quantitytype      (joined from items)
 *   expires_at   -> expiry_date
 *   vendor_name  -> business_name     (joined from vendors)
 *
 * The endpoint does `SELECT sl.*` plus a join, so these are raw column names.
 */
data class BulkListing(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("vendor_id") val vendorId: Int = 0,
    @SerializedName("product_id") val productId: Int = 0,

    @SerializedName("original_price") val originalPrice: Double = 0.0,
    @SerializedName("discount_percent") val discountPercent: Double = 0.0,
    @SerializedName("discounted_price") val discountedPrice: Double = 0.0,

    // Double, not Int. Bulk produce is sold in decimal kilograms, and the
    // columns are DECIMAL(12,3) as of the decimal-quantities migration. Gson
    // coerced 20.500 into an Int silently, so the app displayed a different
    // quantity from the one the server held.
    @SerializedName("Bulk_quantity") val BulkQuantity: Double = 0.0,
    @SerializedName("remaining_quantity") val remainingQuantity: Double = 0.0,

    /**
     * Wholesale minimum order, in the listing's unit. DECIMAL(12,3) server-side
     * and 0 on every surplus listing, which is why it defaults to 0 rather than
     * 1 — a surplus deal has no minimum and must not display one.
     */
    @SerializedName("min_order_quantity") val minOrderQuantity: Double = 0.0,

    /**
     * datetime, e.g. "2026-08-04 18:00:00" — NOT an ISO-8601 instant.
     *
     * NULL for wholesale listings: that stock is not near its end of life, and
     * the column was made nullable by the wholesale migration.
     */
    @SerializedName("expiry_date") val expiryDate: String? = null,

    /** goodie_bag | final_days | bulk | wholesale */
    @SerializedName("listing_type") val listingType: String? = null,

    /** pending | approved | rejected | cancelled */
    @SerializedName("status") val status: String = "pending",

    @SerializedName("description") val description: String? = null,

    /** excellent | good | fair */
    @SerializedName("condition_rating") val conditionRating: String? = null,

    @SerializedName("pickup_only") val pickupOnly: Boolean = false,
    @SerializedName("weight_per_unit_kg") val weightPerUnitKg: Double = 1.0,
    @SerializedName("is_weight_based") val isWeightBased: Boolean = true,
    @SerializedName("admin_notes") val adminNotes: String? = null,
    @SerializedName("created_at") val createdAt: String? = null,

    // ----- joined columns, present on GET only -----
    @SerializedName("product_name") val productName: String? = null,
    @SerializedName("category") val category: String? = null,
    @SerializedName("image") val image: String? = null,
    @SerializedName("quantitytype") val unit: String? = null,
    @SerializedName("business_name") val businessName: String? = null,
    @SerializedName("vendor_location") val vendorLocation: String? = null,
    @SerializedName("vendor_fname") val vendorFirstName: String? = null,
    @SerializedName("vendor_lname") val vendorLastName: String? = null
) {
    /** What the UI should show as the listing's heading. */
    val displayTitle: String
        get() = productName?.takeIf { it.isNotBlank() } ?: "Bulk item"

    /** Prefer the business name; fall back to the vendor's personal name. */
    val vendorDisplayName: String?
        get() = businessName?.takeIf { it.isNotBlank() }
            ?: listOfNotNull(vendorFirstName, vendorLastName)
                .joinToString(" ").trim().takeIf { it.isNotBlank() }

    val isSoldOut: Boolean get() = remainingQuantity <= 0.0
    val isApproved: Boolean get() = status == "approved"

    /**
     * A wholesale offer rather than a surplus markdown. The server forces
     * `listing_type` from the seller's `business_type` on create, so this is
     * authoritative and not a client-side guess.
     */
    val isWholesale: Boolean get() = listingType == "wholesale"

    /**
     * Only meaningful when the wholesaler supplied an optional retail reference
     * price. Surplus listings always carry both, so this is true for them too;
     * callers that care about the wholesale case should check [isWholesale].
     */
    val hasRetailReference: Boolean get() = originalPrice > 0.0 && originalPrice > discountedPrice
}

data class BulkListingsResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("listings") val listings: List<BulkListing>? = null,
    // The endpoint reports failures as {"error": "..."} with no success flag,
    // so `success` must default to false rather than being non-null-required.
    @SerializedName("error") val error: String? = null
)

data class BulkListingResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("listing") val listing: BulkListing? = null,
    @SerializedName("message") val message: String? = null,
    @SerializedName("error") val error: String? = null
)

/**
 * Body for POST `Bulk-listings.php`. Read field-for-field from the PHP.
 *
 * `user_id` — not vendor_id: the endpoint looks the vendor up from the user and
 * requires `is_verified = TRUE`.
 *
 * The server rejects `discount_percent` outside [DISCOUNT_RANGE] inclusive, and
 * computes `discounted_price` itself, so the app must not send a price.
 */
data class CreateBulkListingRequest(
    @SerializedName("user_id") val userId: Int,
    @SerializedName("product_id") val productId: Int,
    @SerializedName("original_price") val originalPrice: Double,
    @SerializedName("discount_percent") val discountPercent: Double,
    @SerializedName("Bulk_quantity") val BulkQuantity: Double,

    /**
     * "YYYY-MM-DD HH:MM:SS", or null for a wholesale listing.
     *
     * Nullable because wholesale stock does not expire. Gson omits null
     * fields, and the endpoint reads it as `$input['expiry_date'] ?? ''`, so
     * omitting it is the same as sending blank — which is exactly what the
     * wholesale branch expects.
     */
    @SerializedName("expiry_date") val expiryDate: String? = null,

    /**
     * The flat price a wholesaler charges, which the server stores in
     * `discounted_price`. Ignored on the surplus path, where the server
     * derives that price from original_price and discount_percent instead.
     */
    @SerializedName("wholesale_price") val wholesalePrice: Double? = null,

    /**
     * Wholesale minimum order, in the listing's unit. Required by the server
     * for a wholesaler and rejected if it exceeds Bulk_quantity.
     */
    @SerializedName("min_order_quantity") val minOrderQuantity: Double? = null,

    @SerializedName("listing_type") val listingType: String = "goodie_bag",
    @SerializedName("description") val description: String = "",
    @SerializedName("condition_rating") val conditionRating: String = "good",
    @SerializedName("pickup_only") val pickupOnly: Boolean = false,
    @SerializedName("weight_per_unit_kg") val weightPerUnitKg: Double = 1.0,
    @SerializedName("is_weight_based") val isWeightBased: Boolean = true
) {
    /**
     * A wholesale offer rather than a surplus markdown.
     *
     * Set only by VendorViewModel.createWholesaleListing. The server does not
     * trust it — it forces listing_type from the seller's business_type — but
     * the CLIENT needs it, to know which of its own pre-flight checks apply.
     */
    val isWholesale: Boolean get() = listingType == "wholesale"

    /**
     * The local discount check, or null if there is nothing to complain about.
     *
     * Wholesale listings are exempt: they carry discount 0 by definition,
     * because there is no retail price they are a discount FROM. The check
     * used to be an unconditional `discountPercent !in DISCOUNT_RANGE` inside
     * VendorRepository.createListing, which every wholesale submission failed
     * — locally, before any request was sent, with a message about a discount
     * the form had never asked for. The rule belongs to the surplus path only,
     * exactly as the server's own fork has it.
     *
     * Pure and on the request itself so it can be tested without an
     * ApiService, and so the two callers cannot drift.
     */
    fun localDiscountRejection(): String? = when {
        isWholesale -> null
        discountPercent !in DISCOUNT_RANGE ->
            "Discount must be between ${discountRangeLabel()}."
        else -> null
    }

    companion object {
        /**
         * Must stay in step with SURPLUS_DISCOUNT_MIN/MAX in
         * api/includes/bulk_listing_rules.php — the server is the authority and
         * this copy exists only to fail fast without a round trip. The floor
         * moved from 30 to 10 so vendors can post modest markdowns rather than
         * being forced into a distress discount to list at all.
         *
         * Applies to SURPLUS listings only. See [localDiscountRejection].
         */
        val DISCOUNT_RANGE = 10.0..70.0

        /** Renders the bounds without trailing zeros, for user-facing messages. */
        fun discountRangeLabel(): String {
            fun trim(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()
            return "${trim(DISCOUNT_RANGE.start)}% and ${trim(DISCOUNT_RANGE.endInclusive)}%"
        }
    }
}

/**
 * Body for PUT `Bulk-listings.php`.
 *
 * ⚠️ The endpoint only updates status, remaining_quantity and admin_notes — it
 * cannot edit price, quantity or expiry, which is what the app's old
 * `updateVendorBulkListing` tried to send.
 *
 * The ownership hole this used to warn about is closed: the endpoint now
 * resolves the listing's owner and refuses a caller who is neither that vendor
 * nor an admin, and status/admin_notes are admin-only. The app still sends
 * only `remaining_quantity`.
 */
data class UpdateBulkListingRequest(
    @SerializedName("listing_id") val listingId: Int,
    @SerializedName("remaining_quantity") val remainingQuantity: Double? = null,
    @SerializedName("status") val status: String? = null,
    @SerializedName("admin_notes") val adminNotes: String? = null
)

// ===========================================================================
// Bulk ORDERS — the customer side
// ===========================================================================

/**
 * Body for POST `Bulk-orders.php`. Read field-for-field from the PHP.
 *
 * `user_id` is sent but NOT trusted: the endpoint runs it through
 * requireOwnUserId(), so a mismatched id is rejected rather than obeyed. It
 * stays in the payload because the endpoint still reads it for admin callers.
 *
 * No price of any kind is sent. The server multiplies the listing's stored
 * discounted_price by the quantity and computes delivery from weight, so an
 * amount here would be ignored — see api/Bulk-orders.php.
 *
 * The server enforces three limits the UI should check first, so the customer
 * finds out before they fill in an address:
 *
 *   - minimum order value UGX 250,000
 *   - minimum 20 kg for weight-based listings
 *   - maximum 1000 kg total weight
 */
data class CreateBulkOrderRequest(
    @SerializedName("listing_id") val listingId: Int,
    @SerializedName("user_id") val userId: Int,
    /** Decimal: weight-based listings are ordered in kg, not whole units. */
    @SerializedName("quantity") val quantity: Double,
    @SerializedName("delivery_address") val deliveryAddress: String? = null,
    @SerializedName("delivery_area") val deliveryArea: String? = null,
    @SerializedName("delivery_lat") val deliveryLat: Double? = null,
    @SerializedName("delivery_lng") val deliveryLng: Double? = null,
    @SerializedName("order_notes") val orderNotes: String? = null,
    @SerializedName("points_redeem") val pointsRedeem: Int? = null
) {
    companion object {
        const val MIN_ORDER_VALUE = 250_000.0
        const val MIN_WEIGHT_BASED_QUANTITY = 20.0
        const val MAX_WEIGHT_KG = 1000.0
    }
}

// The order row itself is `BulkOrder` in VendorAndConfigModels.kt. It is
// deliberately not redeclared here: customer and vendor read the same rows from
// the same endpoint, and a second model would drift from the first.

/**
 * Response to POST `Bulk-orders.php`.
 *
 * [grandTotal] is the figure to show and the figure that will be charged; it is
 * computed server-side and is not necessarily quantity × price, because the
 * delivery fee is weight-based and waived above a threshold.
 */
data class CreateBulkOrderResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("message") val message: String? = null,
    @SerializedName("order") val order: BulkOrder? = null,
    @SerializedName("delivery_fee") val deliveryFee: Double = 0.0,
    @SerializedName("total_weight_kg") val totalWeightKg: Double = 0.0,
    @SerializedName("grand_total") val grandTotal: Double = 0.0,
    // Both null on any app build that never sends points_redeem — additive,
    // backward compatible.
    @SerializedName("points_applied") val pointsApplied: Int? = null,
    @SerializedName("loyalty_discount") val loyaltyDiscount: Double? = null,
    /** What's actually charged — grandTotal minus loyaltyDiscount. */
    @SerializedName("payable_total") val payableTotal: Double? = null,
    // Failures come back as {"error": "..."} with no success flag.
    @SerializedName("error") val error: String? = null
)

// The list response is `BulkOrdersResponse` in VendorAndConfigModels.kt,
// alongside the row model. Same endpoint, same rows, one type.

/**
 * Body for PUT `Bulk-orders.php` — the vendor moving their own order along.
 *
 * The server decides who may send this: the vendor who owns the listing, or an
 * admin. It also refuses to advance an order that has not been paid for, since
 * reaching 'delivered' credits the vendor's earnings ledger and that credit
 * cannot be undone.
 */
data class UpdateBulkOrderStatusRequest(
    @SerializedName("order_id") val orderId: Int,
    @SerializedName("status") val status: String
) {
    companion object {
        /**
         * The states a vendor moves through, in order.
         *
         * `pending` is absent on purpose — it is where an order starts, and
         * `confirmed` is set automatically when payment lands, not by hand.
         * `refunded` is absent too: money has to move before a row may claim it
         * did, and nothing in the app moves money back.
         */
        val VENDOR_FLOW = listOf("processing", "ready", "delivered")

        const val CANCELLED = "cancelled"

        /** The next step for an order in [current], or null if there isn't one. */
        fun nextAfter(current: String): String? = when (current) {
            "confirmed" -> "processing"
            "processing" -> "ready"
            "ready" -> "delivered"
            else -> null
        }

        /** What the button for [status] should say. */
        fun actionLabel(status: String): String = when (status) {
            "processing" -> "Start preparing"
            "ready" -> "Mark ready"
            "delivered" -> "Mark delivered"
            else -> status.replaceFirstChar { it.uppercase() }
        }
    }
}

/**
 * Body for POST `Bulk-quote.php` — what will this cost, before ordering?
 *
 * Coordinates are optional. Without them the server cannot compute a distance
 * and that component is simply absent from the fee, rather than guessed.
 */
data class BulkQuoteRequest(
    @SerializedName("listing_id") val listingId: Int,
    @SerializedName("quantity") val quantity: Double,
    @SerializedName("delivery_lat") val deliveryLat: Double? = null,
    @SerializedName("delivery_lng") val deliveryLng: Double? = null
)

/**
 * The itemised delivery fee.
 *
 * Mirrors the shape `calculateDeliveryFee()` returns for shop orders, so both
 * channels can be rendered by the same code. Every field is the server's
 * figure; nothing here is computed in the app.
 */
data class BulkFeeBreakdown(
    @SerializedName("base_fee") val baseFee: Double = 0.0,
    @SerializedName("weight_fee") val weightFee: Double = 0.0,
    @SerializedName("distance_fee") val distanceFee: Double = 0.0,
    @SerializedName("service_fee") val serviceFee: Double = 0.0,
    @SerializedName("insurance_fee") val insuranceFee: Double = 0.0,
    @SerializedName("processing_fee") val processingFee: Double = 0.0,
    @SerializedName("total_fee") val totalFee: Double = 0.0,

    /** Carriage waived above the free threshold; the flat fees still apply. */
    @SerializedName("is_free") val isFree: Boolean = false,
    @SerializedName("is_capped") val isCapped: Boolean = false,

    @SerializedName("weight_kg") val weightKg: Double = 0.0,

    /** Null when no location was pinned, so no distance could be measured. */
    @SerializedName("distance") val distanceKm: Double? = null,

    /**
     * True when the vendor has not pinned their premises and the distance was
     * measured from the depot instead. Shown, never hidden — it is a real
     * charge computed from an approximation.
     */
    @SerializedName("distance_estimated") val distanceEstimated: Boolean = false,

    /**
     * "google" | "osrm" | "haversine" — how the distance was measured.
     *
     * Anything but "google" means a fallback answered, and haversine in
     * particular is a straight line that under-states a real journey. Shown
     * rather than hidden: the customer is being charged per kilometre.
     */
    @SerializedName("distance_source") val distanceSource: String? = null,

    /** Driving time from the router. Null or 0 means it did not supply one. */
    @SerializedName("duration_minutes") val durationMinutes: Int? = null,

    @SerializedName("insurance_percent") val insurancePercent: Double = 0.0,
    @SerializedName("processing_percent") val processingPercent: Double = 0.0,

    /** Plain-language explanation of how this fee was arrived at. */
    @SerializedName("reason") val reason: String? = null
)

data class BulkQuoteResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("goods_total") val goodsTotal: Double = 0.0,
    @SerializedName("delivery_fee") val deliveryFee: Double = 0.0,

    /** What will actually be charged. Goods plus delivery, server-computed. */
    @SerializedName("grand_total") val grandTotal: Double = 0.0,

    @SerializedName("total_weight_kg") val totalWeightKg: Double = 0.0,
    @SerializedName("breakdown") val breakdown: BulkFeeBreakdown? = null,

    /** Warns before committing, rather than letting order creation refuse it. */
    @SerializedName("exceeds_stock") val exceedsStock: Boolean = false,

    @SerializedName("error") val error: String? = null,
    /** OUT_OF_SERVICE_AREA when the pin falls outside Greater Kampala. */
    @SerializedName("error_code") val errorCode: String? = null
)
