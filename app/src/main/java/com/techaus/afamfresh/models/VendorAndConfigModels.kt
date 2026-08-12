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

/**
 * A product the vendor created themselves, from api/vendor-catalogue.php.
 *
 * These live in `items` alongside the admin catalogue, distinguished by
 * `vendor_id`, and carry a status an admin controls. They are not sold in the
 * main shop — a vendor product has no stock or fulfilment path there. It exists
 * so the vendor can list surplus of it, which is where customers meet it.
 */
data class VendorCatalogueProduct(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("name") val name: String = "",
    @SerializedName("category") val category: String? = null,
    @SerializedName("description") val description: String? = null,
    /** `items.price` is a varchar column, so this arrives as a string. */
    @SerializedName("price") val price: String? = null,
    @SerializedName("image_url") val imageUrl: String? = null,
    /** pending | approved | rejected */
    @SerializedName("status") val status: String = "pending",
    /** Set only on rejection, and the reason the vendor is shown. */
    @SerializedName("rejection_reason") val rejectionReason: String? = null
) {
    val isApproved: Boolean get() = status.equals("approved", ignoreCase = true)
    val isRejected: Boolean get() = status.equals("rejected", ignoreCase = true)
}

data class VendorCatalogueResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("products") val products: List<VendorCatalogueProduct>? = null,
    @SerializedName("error") val error: String? = null
)

data class CreateVendorProductResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("product_id") val productId: Int? = null,
    @SerializedName("message") val message: String? = null,
    @SerializedName("error") val error: String? = null
)

/**
 * Body for POST `vendor-products.php` — "I stock this catalogue item".
 *
 * A vendor cannot invent a product: `product_id` must already exist in `items`,
 * the shared catalogue an admin owns. What the vendor sets is their own price
 * and stock for it. The endpoint upserts, so re-sending an existing product_id
 * updates the price and quantity rather than failing.
 *
 * No user_id — the server takes the vendor from the session.
 */
data class AddVendorProductRequest(
    @SerializedName("product_id") val productId: Int,
    @SerializedName("stock_quantity") val stockQuantity: Int,
    /** Null means "use the catalogue price". */
    @SerializedName("price") val price: Double? = null
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
 *
 * Used by BOTH sides. The vendor reads their incoming orders and the customer
 * reads their own from the same endpoint and the same rows; the only difference
 * is which query parameter scopes them. Splitting this into a customer model and
 * a vendor model would give two views of one table that quietly drift apart.
 */
data class SurplusOrder(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("listing_id") val listingId: Int = 0,
    @SerializedName("user_id") val userId: Int = 0,

    /**
     * Decimal, not whole units. Weight-based listings are ordered in kilograms
     * and the column is a DECIMAL, so an order of 20.5 kg arrives as 20.5. This
     * was typed Int, which Gson truncates — the vendor was shown a quantity
     * that did not match what the customer bought or what they were charged for.
     */
    @SerializedName("quantity") val quantity: Double = 0.0,
    @SerializedName("total_price") val totalPrice: Double = 0.0,

    /** pending | confirmed | processing | ready | delivered | cancelled | refunded */
    @SerializedName("status") val status: String = "pending",

    /**
     * pending | authorization_pending | paid | failed | pending_cash | cancelled
     *
     * Separate from [status] on purpose: a placed-but-unpaid order is only a
     * reservation, and a vendor picking against one is doing unpaid work. The
     * server releases it after 30 minutes.
     */
    @SerializedName("payment_status") val paymentStatus: String = "pending",

    /**
     * Set when an admin dispatches the order to a rider.
     *
     * Once it is set, the delivery is the rider's to complete — they mark it
     * delivered, which credits both of them in one transaction. A vendor
     * marking it delivered themselves at that point would credit the vendor
     * while the rider is still carrying the load, and that credit cannot be
     * taken back.
     */
    @SerializedName("rider_assigned_at") val riderAssignedAt: String? = null,

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

    /**
     * Goods plus delivery — what is actually charged.
     *
     * `orders` folds delivery into one total_amount; surplus_orders keeps them
     * apart, so total_price alone is the goods only. Mirrors
     * surplusPayableTotal() in includes/surplus_payment.php, which is the figure
     * sent to Pesapal.
     */
    val grandTotal: Double get() = totalPrice + deliveryFee

    val isPaid: Boolean get() = paymentStatus == "paid"

    /** Placed but not yet paid for, and therefore still releasable. */
    val isAwaitingPayment: Boolean
        get() = paymentStatus == "pending" || paymentStatus == "authorization_pending"

    /** A rider is carrying this. Completing it is theirs, not the vendor's. */
    val hasRider: Boolean get() = !riderAssignedAt.isNullOrBlank()

    /** Whole numbers without a trailing ".0"; decimals to one place. */
    val quantityLabel: String
        get() = if (quantity % 1.0 == 0.0) "${quantity.toLong()}" else String.format("%.1f", quantity)
}

/**
 * The response from surplus-orders.php GET.
 *
 * Was `VendorOrdersResponse`, which named the caller rather than the content —
 * the same endpoint and the same rows serve the customer's own order list.
 */
data class SurplusOrdersResponse(
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

// ===========================================================================
// VENDOR EARNINGS & WITHDRAWALS
//
// ✅ VERIFIED against api/vendor-earnings.php.
//
// A vendor is credited when a surplus order is DELIVERED, not when it is paid
// for — see creditVendorEarnings() in includes/vendor_earnings.php. The credit
// is the goods value less the vendor's own commission_rate; the delivery fee is
// never part of it, because that money pays the rider who carried the load.
//
// Nothing here computes money. Every figure is read from the ledger, which the
// server writes once, inside the same transaction as the delivery itself.
// ===========================================================================

/** One credit in the ledger: what a single delivered order earned. */
data class VendorEarning(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("order_id") val orderId: Int = 0,

    /** "surplus" in practice — the shop does not credit vendors at all. */
    @SerializedName("source") val source: String = "order",

    /** The goods value this was calculated from, before commission. */
    @SerializedName("order_amount") val orderAmount: Double = 0.0,
    @SerializedName("commission_amount") val commissionAmount: Double = 0.0,

    /** What the vendor actually earns: order_amount − commission_amount. */
    @SerializedName("net_earnings") val netEarnings: Double = 0.0,

    @SerializedName("is_paid") val isPaid: Boolean = false,
    @SerializedName("paid_at") val paidAt: String? = null,
    @SerializedName("created_at") val createdAt: String? = null,

    /** Filled in server-side for surplus rows so the row says what it was for. */
    @SerializedName("product_name") val productName: String? = null
) {
    val displayTitle: String
        get() = productName?.takeIf { it.isNotBlank() } ?: "Order #$orderId"
}

/**
 * Totals across the whole ledger.
 *
 * Every field is nullable because the endpoint's summary is a set of SQL
 * aggregates, and SUM() over no rows is NULL, not 0 — a brand new vendor gets
 * nulls throughout rather than zeros.
 */
data class VendorEarningsSummary(
    @SerializedName("total_orders") val totalOrders: Int? = null,
    @SerializedName("total_revenue") val totalRevenue: Double? = null,
    @SerializedName("total_commission") val totalCommission: Double? = null,
    @SerializedName("total_net_earnings") val totalNetEarnings: Double? = null,
    @SerializedName("paid_earnings") val paidEarnings: Double? = null,

    /** Unpaid, and therefore withdrawable. The figure the vendor cares about. */
    @SerializedName("pending_earnings") val pendingEarnings: Double? = null
)

/** One withdrawal request and where it has got to. */
data class VendorPayout(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("amount") val amount: Double = 0.0,

    /** pending | approved | paid | rejected */
    @SerializedName("status") val status: String = "pending",

    @SerializedName("requested_at") val requestedAt: String? = null,
    @SerializedName("processed_at") val processedAt: String? = null,

    /** The admin's note. Carries the reason on a rejection, so it is shown. */
    @SerializedName("notes") val notes: String? = null
) {
    val statusLabel: String
        get() = when (status) {
            "pending" -> "Waiting for approval"
            "approved" -> "Approved — being sent"
            "paid" -> "Sent"
            "rejected" -> "Not approved"
            else -> status.replaceFirstChar { it.uppercase() }
        }

    val isOpen: Boolean get() = status == "pending" || status == "approved"
}

data class VendorEarningsResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("earnings") val earnings: List<VendorEarning>? = null,
    @SerializedName("summary") val summary: VendorEarningsSummary? = null,
    @SerializedName("payouts") val payouts: List<VendorPayout>? = null,

    /**
     * True when a request is already with an admin. Decided server-side, beside
     * the check that enforces it, so the app is never offering a button the
     * endpoint would refuse.
     */
    @SerializedName("has_open_request") val hasOpenRequest: Boolean = false,
    @SerializedName("error") val error: String? = null
)

/** Reply to `?action=request_payout`. The amount is the server's figure. */
data class RequestVendorPayoutResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("amount") val amount: Double? = null,
    @SerializedName("message") val message: String? = null,
    @SerializedName("error") val error: String? = null
)
