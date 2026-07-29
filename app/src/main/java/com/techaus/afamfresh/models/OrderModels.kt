package com.techaus.afamfresh.models

import com.google.gson.annotations.SerializedName

// ⚠️ INFERRED from ApiService.kt's createOrder/updateOrder/cancelOrder fields
// and from DeliveryResult's exact constructor call in MainScreen.kt (that one's
// field names are confirmed, not guessed — copied directly from usage).

data class Order(
    @SerializedName("id") val id: String,
    @SerializedName("status") val status: String,
    @SerializedName("items") val items: List<OrderItem> = emptyList(),
    @SerializedName("total") val total: Double,
    @SerializedName("fname") val fname: String? = null,
    @SerializedName("lname") val lname: String? = null,
    @SerializedName("mobile") val mobile: String? = null,
    @SerializedName("area") val area: String? = null,
    @SerializedName("address") val address: String? = null,
    @SerializedName("payment_method") val paymentMethod: String? = null,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("scheduled_delivery_date") val scheduledDeliveryDate: String? = null,
    @SerializedName("scheduled_delivery_slot") val scheduledDeliverySlot: String? = null,
    @SerializedName("delivery_notes") val deliveryNotes: String? = null
)

data class OrderItem(
    @SerializedName("product_id") val productId: Double,
    @SerializedName("name") val name: String,
    @SerializedName("price") val price: Double,
    @SerializedName("quantity") val quantity: Int
)

data class OrdersResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("orders") val orders: List<Order>? = null,
    @SerializedName("error") val error: String? = null
)

data class OrderCreateResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("order_id") val orderId: String? = null,
    @SerializedName("error") val error: String? = null
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

data class DeliveryAreasResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("areas") val areas: List<String>? = null,
    @SerializedName("error") val error: String? = null
)

data class DeliveryLocationResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("location") val location: DeliveryResult? = null,
    @SerializedName("error") val error: String? = null
)
