package com.techaus.afamfresh.models

import com.google.gson.annotations.SerializedName

/**
 * ✅ VERIFIED against api/vendor-profile.php and the `vendors` table.
 *
 * This exists because the vendor endpoints are split between two different
 * identifiers: `vendor-products.php` and the surplus create call take the USER
 * id, while `surplus-listings.php` and `surplus-orders.php` filter on the VENDOR
 * id. Nothing in the auth response carries the vendor id, so it has to be
 * looked up once after sign-in.
 */
data class VendorProfile(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("user_id") val userId: Int = 0,
    @SerializedName("business_name") val businessName: String = "",

    /** farmer | market_vendor | wholesaler | store | Fish Products */
    @SerializedName("business_type") val businessType: String? = null,

    @SerializedName("phone") val phone: String? = null,
    @SerializedName("email") val email: String? = null,
    @SerializedName("location") val location: String? = null,
    @SerializedName("market_stall") val marketStall: String? = null,
    @SerializedName("logo") val logo: String? = null,
    @SerializedName("is_verified") val isVerified: Boolean = false,
    @SerializedName("rating") val rating: Double = 0.0,
    @SerializedName("total_reviews") val totalReviews: Int = 0,
    @SerializedName("total_sales") val totalSales: Double = 0.0,
    @SerializedName("commission_rate") val commissionRate: Double = 10.0,
    @SerializedName("low_stock_threshold") val lowStockThreshold: Int = 5,

    // joined by the endpoint
    @SerializedName("user_email") val userEmail: String? = null,
    @SerializedName("fname") val firstName: String? = null,
    @SerializedName("lname") val lastName: String? = null
)

/**
 * vendor-profile.php returns the vendor alongside their products, earnings and
 * recent reviews. Only the vendor and products are modelled here — the rest is
 * ignored until a screen needs it.
 */
data class VendorProfileResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("vendor") val vendor: VendorProfile? = null,
    @SerializedName("products") val products: List<VendorProduct>? = null,
    @SerializedName("error") val error: String? = null
)

/**
 * The business details a vendor fills in after their role request is approved.
 *
 * Approval creates the `vendors` row from what a user account can supply — the
 * person's own name as the business name, and a blank phone — so this is the
 * step that turns it into a real business record. An admin verifies it
 * afterwards, and api/surplus-listings.php refuses to create a listing until
 * they have.
 *
 * No user_id: api/vendor-profile.php?action=update takes the vendor from the
 * session, so the account being edited cannot be chosen by the caller.
 */
data class UpdateVendorProfileRequest(
    @SerializedName("business_name") val businessName: String,
    @SerializedName("phone") val phone: String,
    /** One of the `vendors.business_type` ENUM values; the server whitelists it. */
    @SerializedName("business_type") val businessType: String,
    @SerializedName("location") val location: String? = null,
    @SerializedName("market_stall") val marketStall: String? = null
)

data class UpdateVendorProfileResponse(
    @SerializedName("success") val success: Boolean = false,
    /** False until an admin verifies, which is what gates listing products. */
    @SerializedName("is_verified") val isVerified: Boolean = false,
    @SerializedName("message") val message: String? = null,
    @SerializedName("error") val error: String? = null
)

/**
 * ✅ VERIFIED against api/surplus-orders.php.
 *
 * `getVendorOrders` used to be typed as List<Order>, but the only per-vendor
 * order endpoint is surplus-orders.php, whose rows come from `surplus_orders` —
 * a different table with a different shape from `orders`. Catalogue orders are
 * not exposed per-vendor at all.
 */
data class SurplusOrder(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("listing_id") val listingId: Int = 0,
    @SerializedName("user_id") val userId: Int = 0,
    @SerializedName("quantity") val quantity: Int = 0,
    @SerializedName("total_price") val totalPrice: Double = 0.0,

    /** pending | confirmed | processing | ready | delivered | cancelled | refunded */
    @SerializedName("status") val status: String = "pending",

    @SerializedName("delivery_address") val deliveryAddress: String? = null,
    @SerializedName("delivery_area") val deliveryArea: String? = null,
    @SerializedName("delivery_fee") val deliveryFee: Double = 0.0,
    @SerializedName("pickup_code") val pickupCode: String? = null,
    @SerializedName("scheduled_delivery_date") val scheduledDeliveryDate: String? = null,
    @SerializedName("scheduled_delivery_slot") val scheduledDeliverySlot: String? = null,
    @SerializedName("order_notes") val orderNotes: String? = null,
    @SerializedName("total_weight_kg") val totalWeightKg: Double = 0.0,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("delivered_at") val deliveredAt: String? = null,

    // ----- joined columns -----
    @SerializedName("product_name") val productName: String? = null,
    @SerializedName("image") val image: String? = null,
    @SerializedName("original_price") val originalPrice: Double = 0.0,
    @SerializedName("discount_percent") val discountPercent: Double = 0.0,
    @SerializedName("discounted_price") val discountedPrice: Double = 0.0,
    @SerializedName("listing_type") val listingType: String? = null,
    @SerializedName("condition_rating") val conditionRating: String? = null,
    @SerializedName("business_name") val businessName: String? = null,
    @SerializedName("vendor_location") val vendorLocation: String? = null,
    @SerializedName("customer_fname") val customerFirstName: String? = null,
    @SerializedName("customer_lname") val customerLastName: String? = null
) {
    val customerName: String
        get() = listOfNotNull(customerFirstName, customerLastName).joinToString(" ").trim()

    val displayTitle: String
        get() = productName?.takeIf { it.isNotBlank() } ?: "Surplus order"

    /** True once the order has reached a state the vendor can no longer change. */
    val isTerminal: Boolean
        get() = status in setOf("delivered", "cancelled", "refunded")
}

data class VendorOrdersResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("orders") val orders: List<SurplusOrder>? = null,
    @SerializedName("error") val error: String? = null
)

/**
 * ✅ VERIFIED against api/vendor-products.php.
 *
 * Rows are `vendor_products.*` joined onto `items`, so a vendor product is not
 * a catalogue [Product] — it is the vendor's own price/stock for one catalogue
 * item, carrying the item's descriptive columns alongside.
 */
data class VendorProduct(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("vendor_id") val vendorId: Int = 0,
    @SerializedName("product_id") val productId: Int = 0,

    /** The vendor's own price; null means "use the catalogue price". */
    @SerializedName("price") val price: Double? = null,
    @SerializedName("stock_quantity") val stockQuantity: Int = 0,

    /**
     * Nullable so an absent field is not read as "inactive" — Gson skips Kotlin
     * defaults for missing fields (it allocates with Unsafe), so a declared
     * `= true` would still arrive as false and hide the product. See the same
     * note on Product.stockQty.
     */
    @SerializedName("is_active") val isActive: Boolean? = null,
    @SerializedName("created_at") val createdAt: String? = null,

    // ----- joined from items -----
    @SerializedName("product_name") val productName: String? = null,
    @SerializedName("category") val category: String? = null,
    @SerializedName("description") val description: String? = null,
    @SerializedName("image") val image: String? = null,
    @SerializedName("quantitytype") val unit: String? = null
) {
    val displayName: String
        get() = productName?.takeIf { it.isNotBlank() } ?: "Item #$productId"

    val inStock: Boolean get() = stockQuantity > 0

    /** Absent means active — see the note on [isActive]. */
    val isListed: Boolean get() = isActive ?: true
}

data class VendorProductsResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("products") val products: List<VendorProduct>? = null,
    @SerializedName("error") val error: String? = null
)

/**
 * ✅ VERIFIED against api/config.php.
 *
 * The endpoint returns every `app_config` row as a nested key/value object of
 * STRINGS:
 *
 *   {"success":true,"config":{"is_maintenance_mode":"0","current_version":"1.0", ...}}
 *
 * The previous model expected flat top-level booleans (`maintenance_mode`,
 * `force_update`, `min_supported_version`), none of which the server sends — so
 * maintenanceMode was permanently false and the maintenance gate could never
 * fire, whatever the database said.
 */
data class AppConfigResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("config") val config: Map<String, String>? = null,
    @SerializedName("error") val error: String? = null
) {
    private fun value(key: String): String? = config?.get(key)?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Column holds "0"/"1". Also accepts true/yes so a later change of
     * representation doesn't silently disable maintenance mode.
     */
    val maintenanceMode: Boolean
        get() = value("is_maintenance_mode")?.lowercase() in setOf("1", "true", "yes")

    val maintenanceMessage: String? get() = value("maintenance_message")

    val surplusApprovalRequired: Boolean
        get() = value("surplus_approval_required")?.lowercase() in setOf("1", "true", "yes")

    /** Dotted version strings, e.g. "1.0" — NOT integers. */
    val minVersionRequired: String? get() = value("min_version_required")
    val currentVersion: String? get() = value("current_version")

    /**
     * True when [installedVersion] is older than `min_version_required`.
     *
     * Compares dot-separated numeric parts, so "1.10" is correctly newer than
     * "1.9". Unparseable or absent values return false: a malformed config row
     * must not lock every user out of the app.
     */
    fun requiresUpdate(installedVersion: String): Boolean {
        val required = minVersionRequired ?: return false
        return compareVersions(installedVersion, required) < 0
    }

    private fun compareVersions(a: String, b: String): Int {
        val left = a.split('.').map { it.trim().toIntOrNull() ?: return 0 }
        val right = b.split('.').map { it.trim().toIntOrNull() ?: return 0 }
        for (i in 0 until maxOf(left.size, right.size)) {
            val cmp = (left.getOrNull(i) ?: 0).compareTo(right.getOrNull(i) ?: 0)
            if (cmp != 0) return cmp
        }
        return 0
    }
}

/**
 * ✅ VERIFIED against `user_notifications`.
 *
 * api/notifications.php does `SELECT *` with no normalisation, so these are raw
 * column names. The previous model was wrong on three of its five fields:
 *
 *   body     -> message
 *   read     -> is_read
 *   order_id -> link      (no order_id column exists)
 *
 * Note this is the CUSTOMER table. `notification` (singular) is the admin one —
 * its rows have a NULL user_id and link into admin pages.
 */
data class AppNotification(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("user_id") val userId: Int = 0,
    @SerializedName("title") val title: String = "",
    @SerializedName("message") val message: String = "",

    /** Free-form, defaults to "system" upstream. Every current row is "order". */
    @SerializedName("type") val type: String? = null,

    @SerializedName("is_read") val isRead: Boolean = false,
    @SerializedName("created_at") val createdAt: String? = null,

    /**
     * A web URL fragment, not an id. Observed shapes:
     *   "user-dashboard.php"
     *   "vendor-orders.php?view=order&id=500191"
     *   "500149"                                  (a bare order id)
     */
    @SerializedName("link") val link: String? = null
) {
    /**
     * The order this notification concerns, if any.
     *
     * Parses [link] rather than trusting a dedicated column, because there
     * isn't one. Returns null for links that name no specific order (e.g. plain
     * "user-dashboard.php"), so the UI opens the order list instead.
     */
    val orderId: String?
        get() {
            val raw = link?.trim().orEmpty()
            if (raw.isEmpty()) return null

            // "…?view=order&id=500191" or any "id=<digits>" query parameter.
            Regex("""[?&]id=(\d+)""").find(raw)?.let { return it.groupValues[1] }

            // A bare numeric link is the order id itself.
            if (raw.all { it.isDigit() }) return raw

            return null
        }
}

data class NotificationsResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("notifications") val notifications: List<AppNotification>? = null,
    @SerializedName("error") val error: String? = null
)

/** `action=unread-count` replies {"success":true,"count":N}. */
data class UnreadCountResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("count") val count: Int = 0,
    @SerializedName("error") val error: String? = null
)
