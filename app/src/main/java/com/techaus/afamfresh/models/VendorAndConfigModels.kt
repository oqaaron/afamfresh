package com.techaus.afamfresh.models

import com.google.gson.annotations.SerializedName

// ⚠️ INFERRED — none of the underlying PHP endpoints were shared with me.

data class VendorOrdersResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("orders") val orders: List<Order>? = null,
    @SerializedName("error") val error: String? = null
)

data class VendorProductsResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("products") val products: List<Product>? = null,
    @SerializedName("error") val error: String? = null
)

data class AppConfigResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("maintenance_mode") val maintenanceMode: Boolean = false,
    @SerializedName("maintenance_message") val maintenanceMessage: String? = null,
    @SerializedName("force_update") val forceUpdate: Boolean = false,
    @SerializedName("min_supported_version") val minSupportedVersion: Int? = null,
    /**
     * Optional. When present, this drives delivery pricing instead of the
     * app's built-in fallback, so rates can be changed without an app release.
     * Absent = the app keeps using DeliveryConfig.FALLBACK.
     */
    @SerializedName("delivery_pricing") val deliveryPricing: DeliveryPricingConfig? = null,
    @SerializedName("error") val error: String? = null
)

/**
 * Server-supplied delivery pricing. Every field is required — a partial config
 * is rejected rather than merged, because quoting from half a config would
 * charge customers a number nobody chose.
 */
data class DeliveryPricingConfig(
    @SerializedName("pickup_lat") val pickupLat: Double? = null,
    @SerializedName("pickup_lng") val pickupLng: Double? = null,
    @SerializedName("pickup_label") val pickupLabel: String? = null,
    @SerializedName("base_fare") val baseFare: Double? = null,
    @SerializedName("cost_per_km") val costPerKm: Double? = null,
    @SerializedName("max_delivery_km") val maxDeliveryKm: Double? = null
)

data class AppNotification(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String,
    @SerializedName("body") val body: String,
    @SerializedName("read") val read: Boolean = false,
    @SerializedName("created_at") val createdAt: String? = null,
    /**
     * Set when the notification concerns a specific order, so tapping it can
     * open that order rather than dumping the user on a generic list.
     * Null for anything not order-related (surplus deals, announcements).
     */
    @SerializedName("order_id") val orderId: String? = null,
    /** Free-form category, e.g. "order_status", "surplus", "promo". */
    @SerializedName("type") val type: String? = null
)

data class NotificationsResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("notifications") val notifications: List<AppNotification>? = null,
    @SerializedName("error") val error: String? = null
)
