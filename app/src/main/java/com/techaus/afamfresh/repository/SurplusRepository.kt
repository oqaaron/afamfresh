package com.techaus.afamfresh.repository

import com.techaus.afamfresh.api.ApiService
import com.techaus.afamfresh.models.BaseResponse
import com.techaus.afamfresh.models.CreateSurplusOrderRequest
import com.techaus.afamfresh.models.CreateSurplusOrderResponse
import com.techaus.afamfresh.models.SurplusListing
import com.techaus.afamfresh.models.SurplusListingsResponse
import com.techaus.afamfresh.models.SurplusOrder
import com.techaus.afamfresh.models.SurplusOrdersResponse
import com.techaus.afamfresh.models.SurplusQuoteRequest
import com.techaus.afamfresh.models.SurplusQuoteResponse
import com.techaus.afamfresh.utils.ApiError
import com.techaus.afamfresh.utils.enqueueApi
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

// Constructor confirmed by MainActivity.kt: SurplusRepository(ApiClient.apiService)
class SurplusRepository(
    private val apiService: ApiService
) {
    fun getPublicListings(
        status: String = "approved",
        callback: (List<SurplusListing>?, ApiError?) -> Unit
    ) {
        apiService.getSurplusListings(status = status)
            .enqueueApi<SurplusListingsResponse>("SurplusRepo", "getPublicListings") { body, error ->
                when {
                    error != null -> callback(null, error)
                    body?.success == true -> callback(body.listings ?: emptyList(), null)
                    else -> callback(null, ApiError.reported(body?.error))
                }
            }
    }

    /**
     * Places a surplus order.
     *
     * The whole response is handed back on success, not just the order: the
     * delivery fee and grand total are computed server-side from the order's
     * weight and are not derivable from the listing alone.
     *
     * Failures here are usually the server's own limits — minimum order value,
     * minimum weight — which arrive as a sentence written for the customer. They
     * are passed through as [ApiError.reported] so the screen shows the actual
     * reason rather than a generic "could not place order".
     */
    fun createOrder(
        request: CreateSurplusOrderRequest,
        callback: (CreateSurplusOrderResponse?, ApiError?) -> Unit
    ) {
        apiService.createSurplusOrder(request)
            .enqueueApi<CreateSurplusOrderResponse>("SurplusRepo", "createOrder") { body, error ->
                when {
                    error != null -> callback(null, error)
                    body?.success == true && body.order != null -> callback(body, null)
                    else -> callback(null, ApiError.reported(body?.error))
                }
            }
    }

    /**
     * Prices an order without placing it.
     *
     * The whole response is returned, not just the total: the screen shows the
     * itemised breakdown, and the server's `reason` explains a fee better than
     * anything this layer could reconstruct from the numbers.
     */
    fun getQuote(
        request: SurplusQuoteRequest,
        callback: (SurplusQuoteResponse?, ApiError?) -> Unit
    ) {
        apiService.getSurplusQuote(request)
            .enqueueApi<SurplusQuoteResponse>("SurplusRepo", "getQuote") { body, error ->
                when {
                    error != null -> callback(null, error)
                    body?.success == true -> callback(body, null)
                    else -> callback(null, ApiError.reported(body?.error))
                }
            }
    }

    /** The signed-in customer's own surplus orders. */
    fun getMyOrders(
        userId: Int,
        callback: (List<SurplusOrder>?, ApiError?) -> Unit
    ) {
        apiService.getMySurplusOrders(userId = userId)
            .enqueueApi<SurplusOrdersResponse>("SurplusRepo", "getMyOrders") { body, error ->
                when {
                    error != null -> callback(null, error)
                    body?.success == true -> callback(body.orders ?: emptyList(), null)
                    else -> callback(null, ApiError.reported(body?.error))
                }
            }
    }

    private fun text(value: String) = value.toRequestBody("text/plain".toMediaTypeOrNull())

    /**
     * The customer's confirm-and-rate step for a surplus order. Mirrors
     * OrderRepository.confirmReceipt — see there for why [photoBytes] is
     * prepared by the caller rather than here.
     */
    fun confirmReceipt(
        orderId: Int,
        userId: Int,
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
        apiService.confirmSurplusReceipt(
            orderId = text(orderId.toString()),
            userId = text(userId.toString()),
            rating = rating?.let { text(it.toString()) },
            ratingSpeed = ratingSpeed?.let { text(it.toString()) },
            ratingProfessionalism = ratingProfessionalism?.let { text(it.toString()) },
            ratingPackaging = ratingPackaging?.let { text(it.toString()) },
            feedback = feedback?.takeIf { it.isNotBlank() }?.let { text(it) },
            emojiReaction = emojiReaction?.let { text(it) },
            photo = photoPart
        ).enqueueApi<BaseResponse>("SurplusRepo", "confirmReceipt") { body, error ->
            when {
                error != null -> callback(false, error)
                body?.success == true -> callback(true, null)
                else -> callback(false, ApiError.reported(body?.error))
            }
        }
    }
}
