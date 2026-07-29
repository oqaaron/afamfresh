package com.techaus.afamfresh.repository

import android.util.Log
import com.techaus.afamfresh.api.ApiService
import com.techaus.afamfresh.models.PaymentRequest
import com.techaus.afamfresh.models.PaymentResponse
import com.techaus.afamfresh.utils.ApiError
import com.techaus.afamfresh.utils.enqueueApi

// Constructor shape confirmed by MainActivity.kt:
//   val paymentRepository = PaymentRepository(ApiClient.apiService)
class PaymentRepository(
    private val apiService: ApiService
) {
    /**
     * Payment is the flow where a vague error is most damaging — the customer
     * cannot tell whether money left their account. Every failure here carries
     * a specific reason.
     */
    fun initiatePayment(request: PaymentRequest, callback: (PaymentResponse?, ApiError?) -> Unit) {
        try {
            apiService.initiatePayment(request)
                .enqueueApi<PaymentResponse>("PaymentRepo", "initiatePayment") { body, error ->
                    when {
                        error != null -> callback(null, error)
                        body?.success == true -> callback(body, null)
                        else -> callback(null, ApiError.reported(body?.error))
                    }
                }
        } catch (e: Exception) {
            Log.e("PaymentRepo", "initiatePayment exception: ${e.message}", e)
            callback(null, ApiError.Unexpected(e.message))
        }
    }

    /**
     * Note that a "not successful yet" verification is NOT an error — a pending
     * payment is a legitimate state. The body is returned so the caller can
     * distinguish pending from failed, and only genuine transport or HTTP
     * problems produce an [ApiError].
     */
    fun verifyPayment(
        transactionId: String,
        reference: String? = null,
        callback: (PaymentResponse?, ApiError?) -> Unit
    ) {
        apiService.verifyPayment(transactionId = transactionId, reference = reference)
            .enqueueApi<PaymentResponse>("PaymentRepo", "verifyPayment") { body, error ->
                callback(body, error)
            }
    }
}
