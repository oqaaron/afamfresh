package com.techaus.afamfresh.repository

import android.util.Log
import com.techaus.afamfresh.api.ApiService
import com.techaus.afamfresh.models.BaseResponse
import com.techaus.afamfresh.models.Order
import com.techaus.afamfresh.models.OrderCreateResponse
import com.techaus.afamfresh.models.OrdersResponse
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

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
        callback: (OrderCreateResponse?) -> Unit
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
            ).enqueue(object : Callback<OrderCreateResponse> {
                override fun onResponse(call: Call<OrderCreateResponse>, response: Response<OrderCreateResponse>) {
                    if (response.isSuccessful) {
                        callback(response.body())
                    } else {
                        Log.e("OrderRepo", "createOrder HTTP error: ${response.code()}")
                        callback(null)
                    }
                }

                override fun onFailure(call: Call<OrderCreateResponse>, t: Throwable) {
                    Log.e("OrderRepo", "createOrder network failure: ${t.message}", t)
                    callback(null)
                }
            })
        } catch (e: Exception) {
            Log.e("OrderRepo", "createOrder exception: ${e.message}", e)
            callback(null)
        }
    }

    fun getOrders(callback: (List<Order>?) -> Unit) {
        apiService.getOrders().enqueue(object : Callback<OrdersResponse> {
            override fun onResponse(call: Call<OrdersResponse>, response: Response<OrdersResponse>) {
                callback(if (response.isSuccessful) response.body()?.orders else null)
            }

            override fun onFailure(call: Call<OrdersResponse>, t: Throwable) {
                Log.e("OrderRepo", "getOrders network failure: ${t.message}", t)
                callback(null)
            }
        })
    }

    fun getOrder(id: String, callback: (Order?) -> Unit) {
        apiService.getOrder(id = id).enqueue(object : Callback<Order> {
            override fun onResponse(call: Call<Order>, response: Response<Order>) {
                callback(if (response.isSuccessful) response.body() else null)
            }

            override fun onFailure(call: Call<Order>, t: Throwable) {
                Log.e("OrderRepo", "getOrder network failure: ${t.message}", t)
                callback(null)
            }
        })
    }

    fun updateOrder(
        orderId: String,
        address: String,
        area: String,
        mobile: String,
        scheduledDeliveryDate: String? = null,
        scheduledDeliverySlot: String? = null,
        deliveryNotes: String? = null,
        callback: (Boolean) -> Unit
    ) {
        apiService.updateOrder(
            orderId = orderId,
            address = address,
            area = area,
            mobile = mobile,
            scheduledDeliveryDate = scheduledDeliveryDate,
            scheduledDeliverySlot = scheduledDeliverySlot,
            deliveryNotes = deliveryNotes
        ).enqueue(object : Callback<BaseResponse> {
            override fun onResponse(call: Call<BaseResponse>, response: Response<BaseResponse>) {
                callback(response.isSuccessful && response.body()?.success == true)
            }

            override fun onFailure(call: Call<BaseResponse>, t: Throwable) {
                Log.e("OrderRepo", "updateOrder network failure: ${t.message}", t)
                callback(false)
            }
        })
    }

    fun cancelOrder(orderId: String, callback: (Boolean) -> Unit) {
        apiService.cancelOrder(orderId = orderId).enqueue(object : Callback<BaseResponse> {
            override fun onResponse(call: Call<BaseResponse>, response: Response<BaseResponse>) {
                callback(response.isSuccessful && response.body()?.success == true)
            }

            override fun onFailure(call: Call<BaseResponse>, t: Throwable) {
                Log.e("OrderRepo", "cancelOrder network failure: ${t.message}", t)
                callback(false)
            }
        })
    }
}
