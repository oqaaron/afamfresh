package com.techaus.afamfresh.models

import com.google.gson.annotations.SerializedName

/**
 * Verified against api/vendor-profile.php and the `vendors` table.
 */
data class VendorProfile(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("user_id") val userId: Int = 0,
    @SerializedName("business_name") val businessName: String = "",

    /** farmer | market_vendor | wholesaler */
    @SerializedName("business_type") val businessType: String? = null,

    @SerializedName("phone") val phone: String? = null,
    @SerializedName("email") val email: String? = null,
    @SerializedName("location") val location: String? = null,

    @SerializedName("lat") val lat: Double? = null,
    @SerializedName("lng") val lng: Double? = null,

    @SerializedName("market_stall") val marketStall: String? = null,
    @SerializedName("logo") val logo: String? = null,
    @SerializedName("is_verified") val isVerified: Boolean = false,
    @SerializedName("rating") val rating: Double = 0.0,
    @SerializedName("total_reviews") val totalReviews: Int = 0,
    @SerializedName("total_sales") val totalSales: Double = 0.0,
    @SerializedName("commission_rate") val commissionRate: Double = 10.0,
    @SerializedName("low_stock_threshold") val lowStockThreshold: Int = 5,

    @SerializedName("user_email") val userEmail: String? = null,
    @SerializedName("fname") val firstName: String? = null,
    @SerializedName("lname") val lastName: String? = null
) {
    val isWholesaler: Boolean get() = businessType == "wholesaler"
}

data class VendorProfileResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("vendor") val vendor: VendorProfile? = null,
    @SerializedName("products") val products: List<VendorProduct>? = null,
    @SerializedName("error") val error: String? = null
)

data class UpdateVendorProfileRequest(
    @SerializedName("business_name") val businessName: String,
    @SerializedName("phone") val phone: String,
    @SerializedName("location") val location: String? = null,
    @SerializedName("market_stall") val marketStall: String? = null,
    @SerializedName("lat") val lat: Double? = null,
    @SerializedName("lng") val lng: Double? = null
)

data class VendorCatalogueProduct(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("name") val name: String = "",
    @SerializedName("category") val category: String? = null,
    @SerializedName("description") val description: String? = null,
    @SerializedName("price") val price: String? = null,
    @SerializedName("image_url") val imageUrl: String? = null,
    @SerializedName("status") val status: String = "pending",
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

data class AddVendorProductRequest(
    @SerializedName("product_id") val productId: Int,
    @SerializedName("stock_quantity") val stockQuantity: Int,
    @SerializedName("price") val price: Double? = null
)

data class UpdateVendorProfileResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("is_verified") val isVerified: Boolean = false,
    @SerializedName("message") val message: String? = null,
    @SerializedName("error") val error: String? = null
)

data class BulkOrder(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("listing_id") val listingId: Int = 0,
    @SerializedName("user_id") val userId: Int = 0,
    @SerializedName("quantity") val quantity: Double = 0.0,
    @SerializedName("total_price") val totalPrice: Double = 0.0,
    @SerializedName("status") val status: String = "pending",
    @SerializedName("payment_status") val paymentStatus: String = "pending",
    @SerializedName("rider_assigned_at") val riderAssignedAt: String? = null,
    @SerializedName("pickup_only") val pickupOnly: Boolean = false,
    @SerializedName("delivery_address") val deliveryAddress: String? = null,
    @SerializedName("delivery_area") val deliveryArea: String? = null,
    @SerializedName("recipient_name") val recipientName: String? = null,
    @SerializedName("recipient_phone") val recipientPhone: String? = null,
    @SerializedName("delivery_fee") val deliveryFee: Double = 0.0,
    @SerializedName("pickup_code") val pickupCode: String? = null,
    @SerializedName("scheduled_delivery_date") val scheduledDeliveryDate: String? = null,
    @SerializedName("scheduled_delivery_slot") val scheduledDeliverySlot: String? = null,
    @SerializedName("order_notes") val orderNotes: String? = null,
    @SerializedName("total_weight_kg") val totalWeightKg: Double = 0.0,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("delivered_at") val deliveredAt: String? = null,
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
    @SerializedName("customer_lname") val customerLastName: String? = null,
    @SerializedName("delivery_confirmed") val deliveryConfirmed: Boolean = false,
    @SerializedName("completed_at") val completedAt: String? = null,
    @SerializedName("customer_rating") val customerRating: Int? = null
) {
    val customerName: String
        get() = listOfNotNull(customerFirstName, customerLastName).joinToString(" ").trim()

    val displayTitle: String
        get() = productName?.takeIf { it.isNotBlank() } ?: "Bulk order"

    val isTerminal: Boolean
        get() = status in setOf("delivered", "cancelled", "refunded")

    val grandTotal: Double get() = totalPrice + deliveryFee

    val isPaid: Boolean get() = paymentStatus == "paid"

    val isAwaitingPayment: Boolean
        get() = paymentStatus == "pending" || paymentStatus == "authorization_pending"

    val hasRider: Boolean get() = !riderAssignedAt.isNullOrBlank()

    val sellerCompletesDelivery: Boolean get() = !hasRider && pickupOnly

    val awaitingDispatch: Boolean get() = !hasRider && !pickupOnly

    val quantityLabel: String
        get() = if (quantity % 1.0 == 0.0) "${quantity.toLong()}" else String.format("%.1f", quantity)

    val needsReceiptConfirmation: Boolean
        get() = deliveryConfirmed && completedAt == null
}

data class BulkOrdersResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("orders") val orders: List<BulkOrder>? = null,
    @SerializedName("error") val error: String? = null
)

data class VendorProduct(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("vendor_id") val vendorId: Int = 0,
    @SerializedName("product_id") val productId: Int = 0,
    @SerializedName("price") val price: Double? = null,
    @SerializedName("stock_quantity") val stockQuantity: Int = 0,
    @SerializedName("is_active") val isActive: Boolean? = null,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("product_name") val productName: String? = null,
    @SerializedName("category") val category: String? = null,
    @SerializedName("description") val description: String? = null,
    @SerializedName("image") val image: String? = null,
    @SerializedName("quantitytype") val unit: String? = null
) {
    val displayName: String
        get() = productName?.takeIf { it.isNotBlank() } ?: "Item #$productId"

    val inStock: Boolean get() = stockQuantity > 0

    val isListed: Boolean get() = isActive ?: true
}

data class VendorProductsResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("products") val products: List<VendorProduct>? = null,
    @SerializedName("error") val error: String? = null
)

/**
 * Verified against api/config.php.
 */
data class AppConfigResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("config") val config: Map<String, String>? = null,
    @SerializedName("error") val error: String? = null
) {
    private fun value(key: String): String? = config?.get(key)?.trim()?.takeIf { it.isNotEmpty() }

    val maintenanceMode: Boolean
        get() = value("is_maintenance_mode")?.lowercase() in setOf("1", "true", "yes")

    val maintenanceMessage: String? get() = value("maintenance_message")

    val BulkApprovalRequired: Boolean
        get() = value("Bulk_approval_required")?.lowercase() in setOf("1", "true", "yes")

    val minVersionRequired: String? get() = value("min_version_required")
    val currentVersion: String? get() = value("current_version")

    // Dynamic Promo Banner Configuration
    val promoBannerActive: Boolean
        get() = value("promo_banner_active")?.lowercase() in setOf("1", "true", "yes")

    val promoBannerTitle: String?
        get() = value("promo_banner_title")

    val promoBannerButtonText: String?
        get() = value("promo_banner_button_text")

    val promoBannerImageUrl: String?
        get() = value("promo_banner_image_url")

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

data class AppNotification(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("user_id") val userId: Int = 0,
    @SerializedName("title") val title: String = "",
    @SerializedName("message") val message: String = "",
    @SerializedName("type") val type: String? = null,
    @SerializedName("is_read") val isRead: Boolean = false,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("link") val link: String? = null
) {
    val orderId: String?
        get() {
            val raw = link?.trim().orEmpty()
            if (raw.isEmpty()) return null

            Regex("""[?&]id=(\d+)""").find(raw)?.let { return it.groupValues[1] }

            if (raw.all { it.isDigit() }) return raw

            return null
        }
}

data class NotificationsResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("notifications") val notifications: List<AppNotification>? = null,
    @SerializedName("error") val error: String? = null
)

data class UnreadCountResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("count") val count: Int = 0,
    @SerializedName("error") val error: String? = null
)

data class VendorEarning(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("order_id") val orderId: Int = 0,
    @SerializedName("source") val source: String = "order",
    @SerializedName("order_amount") val orderAmount: Double = 0.0,
    @SerializedName("commission_amount") val commissionAmount: Double = 0.0,
    @SerializedName("net_earnings") val netEarnings: Double = 0.0,
    @SerializedName("is_paid") val isPaid: Boolean = false,
    @SerializedName("paid_at") val paidAt: String? = null,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("product_name") val productName: String? = null
) {
    val displayTitle: String
        get() = productName?.takeIf { it.isNotBlank() } ?: "Order #$orderId"
}

data class VendorEarningsSummary(
    @SerializedName("total_orders") val totalOrders: Int? = null,
    @SerializedName("total_revenue") val totalRevenue: Double? = null,
    @SerializedName("total_commission") val totalCommission: Double? = null,
    @SerializedName("total_net_earnings") val totalNetEarnings: Double? = null,
    @SerializedName("paid_earnings") val paidEarnings: Double? = null,
    @SerializedName("pending_earnings") val pendingEarnings: Double? = null
)

data class VendorPayout(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("amount") val amount: Double = 0.0,
    @SerializedName("status") val status: String = "pending",
    @SerializedName("requested_at") val requestedAt: String? = null,
    @SerializedName("processed_at") val processedAt: String? = null,
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
    @SerializedName("has_open_request") val hasOpenRequest: Boolean = false,
    @SerializedName("error") val error: String? = null
)

data class RequestVendorPayoutResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("amount") val amount: Double? = null,
    @SerializedName("message") val message: String? = null,
    @SerializedName("error") val error: String? = null
)