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
    @SerializedName("error") val error: String? = null
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
