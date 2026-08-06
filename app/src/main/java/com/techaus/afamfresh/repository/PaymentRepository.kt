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
     * Starts a payment.
     *
     * Payment is the flow where a vague error is most damaging — the customer
     * cannot tell whether money left their account. Every failure here carries a
     * specific reason.
     */
    fun initiatePayment(request: PaymentRequest, callback: (PaymentResponse?, ApiError?) -> Unit) {
        try {
            apiService.initiatePayment(request = request)
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
     * Asks the server to re-check the payment against Pesapal.
     *
     * The body is handed back even when `success` is false, because the caller
     * must be able to tell three different situations apart:
     *
     *   paid          money confirmed received
     *   pending       still in flight — NOT an error
     *   unconfirmed   the server could not reach Pesapal, outcome UNKNOWN
     *
     * Collapsing the last two into "failed" is what makes customers pay twice, so
     * only genuine transport problems produce an [ApiError] here.
     */
    fun verifyPayment(
        transactionId: String? = null,
        orderId: String? = null,
        callback: (PaymentResponse?, ApiError?) -> Unit
    ) {
        require(!transactionId.isNullOrBlank() || !orderId.isNullOrBlank()) {
            "verifyPayment needs either a transactionId or an orderId"
        }

        apiService.verifyPayment(transactionId = transactionId, orderId = orderId)
            .enqueueApi<PaymentResponse>("PaymentRepo", "verifyPayment") { body, error ->
                callback(body, error)
            }
    }
}
