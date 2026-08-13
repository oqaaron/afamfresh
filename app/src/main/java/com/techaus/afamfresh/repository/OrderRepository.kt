package com.techaus.afamfresh.repository

import android.util.Log
import com.techaus.afamfresh.api.ApiService
import com.techaus.afamfresh.models.BaseResponse
import com.techaus.afamfresh.models.Order
import com.techaus.afamfresh.models.OrderCreateResponse
import com.techaus.afamfresh.models.OrderDetailResponse
import com.techaus.afamfresh.models.OrdersResponse
import com.techaus.afamfresh.utils.ApiError
import com.techaus.afamfresh.utils.enqueueApi
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

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
        // The response is an envelope, and a failure carries success=false with
        // an error string. Previously this parsed the envelope AS an Order and
        // handed back an all-default object, so a missing order looked like a
        // real one with a blank id.
        apiService.getOrder(id = id)
            .enqueueApi<OrderDetailResponse>("OrderRepo", "getOrder") { body, error ->
                when {
                    error != null -> callback(null, error)
                    body?.success == true && body.order != null -> callback(body.order, null)
                    else -> callback(null, ApiError.reported(body?.error))
                }
            }
    }

    fun updateOrder(
        orderId: String,
        address: String,
        area: String,
        mobile: String,
        scheduledDeliveryDate: String? = null,
        scheduledDeliverySlot: String? = null,
        callback: (Boolean, ApiError?) -> Unit
    ) {
        apiService.updateOrder(
            orderId = orderId,
            address = address,
            area = area,
            mobile = mobile,
            scheduledDeliveryDate = scheduledDeliveryDate,
            scheduledDeliverySlot = scheduledDeliverySlot
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

    private fun text(value: String) = value.toRequestBody("text/plain".toMediaTypeOrNull())

    /**
     * The customer's confirm-and-rate step. Every field but [orderId] is
     * optional — a bare confirmation with no rating at all is a valid call.
     * [photoBytes] comes from [com.techaus.afamfresh.utils.prepareJpegBytes],
     * called by the screen before this, since that needs a Context this
     * repository does not hold.
     */
    fun confirmReceipt(
        orderId: String,
        rating: Int?,
        ratingSpeed: Int?,
        ratingProfessionalism: Int?,
        ratingPackaging: Int?,
        feedback: String?,
        emojiReaction: String?,
        photoBytes: ByteArray?,
        callback: (Boolean, ApiError?) -> Unit
    ) {
        val photoPart = photoBytes?.let {
            MultipartBody.Part.createFormData(
                "photo", "confirm.jpg", it.toRequestBody("image/jpeg".toMediaTypeOrNull())
            )
        }
        apiService.confirmOrderReceipt(
            orderId = text(orderId),
            rating = rating?.let { text(it.toString()) },
            ratingSpeed = ratingSpeed?.let { text(it.toString()) },
            ratingProfessionalism = ratingProfessionalism?.let { text(it.toString()) },
            ratingPackaging = ratingPackaging?.let { text(it.toString()) },
            feedback = feedback?.takeIf { it.isNotBlank() }?.let { text(it) },
            emojiReaction = emojiReaction?.let { text(it) },
            photo = photoPart
        ).enqueueApi<BaseResponse>("OrderRepo", "confirmReceipt") { body, error ->
            when {
                error != null -> callback(false, error)
                body?.success == true -> callback(true, null)
                else -> callback(false, ApiError.reported(body?.error))
            }
        }
    }
}
