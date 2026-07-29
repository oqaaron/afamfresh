package com.techaus.afamfresh.repository

import android.util.Log
import com.techaus.afamfresh.api.ApiService
import com.techaus.afamfresh.models.BaseResponse
import com.techaus.afamfresh.models.Order
import com.techaus.afamfresh.models.OrderCreateResponse
import com.techaus.afamfresh.models.OrdersResponse
import com.techaus.afamfresh.utils.ApiError
import com.techaus.afamfresh.utils.enqueueApi

// Constructor confirmed by MainActivity.kt: OrderRepository(ApiClient.apiService)
// Method params mirror ApiService.kt's createOrder/updateOrder/cancelOrder exactly.
class OrderRepository(
    private val apiService: ApiService
) {
    fun createOrder(
        fname: String,
        lname: String,
        mobile: String,
        area: String,
        address: String,
        itemsJson: String,
        total: Double,
        paymentMethod: String = "mobile_money",
        email: String = "",
        pickupAddress: String = "",
        dropoffAddress: String = "",
        pickupLat: Double = 0.0,
        pickupLng: Double = 0.0,
        dropoffLat: Double = 0.0,
        dropoffLng: Double = 0.0,
        distanceKm: Double = 0.0,
        deliveryCost: Double = 0.0,
        callback: (OrderCreateResponse?, ApiError?) -> Unit
    ) {
        try {
            apiService.createOrder(
                fname = fname,
                lname = lname,
                mobile = mobile,
                area = area,
                address = address,
                items = itemsJson,
                total = total,
                paymentMethod = paymentMethod,
                email = email,
                pickupAddress = pickupAddress,
                dropoffAddress = dropoffAddress,
                pickupLat = pickupLat,
                pickupLng = pickupLng,
                dropoffLat = dropoffLat,
                dropoffLng = dropoffLng,
                distanceKm = distanceKm,
                deliveryCost = deliveryCost
            ).enqueueApi<OrderCreateResponse>("OrderRepo", "createOrder") { body, error ->
                when {
                    error != null -> callback(null, error)
                    // Previously the body was passed through even when the
                    // server said success=false, so a rejected order looked
                    // like a placed one.
                    body?.success == true -> callback(body, null)
                    else -> callback(null, ApiError.reported(body?.error))
                }
            }
        } catch (e: Exception) {
            Log.e("OrderRepo", "createOrder exception: ${e.message}", e)
            callback(null, ApiError.Unexpected(e.message))
        }
    }

    fun getOrders(callback: (List<Order>?, ApiError?) -> Unit) {
        apiService.getOrders().enqueueApi<OrdersResponse>("OrderRepo", "getOrders") { body, error ->
            when {
                error != null -> callback(null, error)
                body?.success == true -> callback(body.orders ?: emptyList(), null)
                else -> callback(null, ApiError.reported(body?.error))
            }
        }
    }

    fun getOrder(id: String, callback: (Order?, ApiError?) -> Unit) {
        apiService.getOrder(id = id).enqueueApi<Order>("OrderRepo", "getOrder") { body, error ->
            callback(body, error)
        }
    }

    fun updateOrder(
        orderId: String,
        address: String,
        area: String,
        mobile: String,
        scheduledDeliveryDate: String? = null,
        scheduledDeliverySlot: String? = null,
        deliveryNotes: String? = null,
        callback: (Boolean, ApiError?) -> Unit
    ) {
        apiService.updateOrder(
            orderId = orderId,
            address = address,
            area = area,
            mobile = mobile,
            scheduledDeliveryDate = scheduledDeliveryDate,
            scheduledDeliverySlot = scheduledDeliverySlot,
            deliveryNotes = deliveryNotes
        ).enqueueApi<BaseResponse>("OrderRepo", "updateOrder") { body, error ->
            when {
                error != null -> callback(false, error)
                body?.success == true -> callback(true, null)
                else -> callback(false, ApiError.reported(body?.error))
            }
        }
    }

    fun cancelOrder(orderId: String, callback: (Boolean, ApiError?) -> Unit) {
        apiService.cancelOrder(orderId = orderId)
            .enqueueApi<BaseResponse>("OrderRepo", "cancelOrder") { body, error ->
                when {
                    error != null -> callback(false, error)
                    body?.success == true -> callback(true, null)
                    else -> callback(false, ApiError.reported(body?.error))
                }
            }
    }
}
