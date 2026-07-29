package com.techaus.afamfresh.repository

import android.util.Log
import com.techaus.afamfresh.api.ApiService
import com.techaus.afamfresh.models.PaymentRequest
import com.techaus.afamfresh.models.PaymentResponse
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

// Constructor shape confirmed by MainActivity.kt:
//   val paymentRepository = PaymentRepository(ApiClient.apiService)
// Method bodies follow the same callback pattern as AuthRepository.login().
class PaymentRepository(
    private val apiService: ApiService
) {
    fun initiatePayment(request: PaymentRequest, callback: (PaymentResponse?) -> Unit) {
        try {
            apiService.initiatePayment(request).enqueue(object : Callback<PaymentResponse> {
                override fun onResponse(call: Call<PaymentResponse>, response: Response<PaymentResponse>) {
                    if (response.isSuccessful) {
                        callback(response.body())
                    } else {
                        Log.e("PaymentRepo", "initiatePayment HTTP error: ${response.code()}")
                        callback(null)
                    }
                }

                override fun onFailure(call: Call<PaymentResponse>, t: Throwable) {
                    Log.e("PaymentRepo", "initiatePayment network failure: ${t.message}", t)
                    callback(null)
                }
            })
        } catch (e: Exception) {
            Log.e("PaymentRepo", "initiatePayment exception: ${e.message}", e)
            callback(null)
        }
    }

    fun verifyPayment(transactionId: String, reference: String? = null, callback: (PaymentResponse?) -> Unit) {
        apiService.verifyPayment(transactionId = transactionId, reference = reference)
            .enqueue(object : Callback<PaymentResponse> {
                override fun onResponse(call: Call<PaymentResponse>, response: Response<PaymentResponse>) {
                    callback(if (response.isSuccessful) response.body() else null)
                }

                override fun onFailure(call: Call<PaymentResponse>, t: Throwable) {
                    Log.e("PaymentRepo", "verifyPayment network failure: ${t.message}", t)
                    callback(null)
                }
            })
    }
}
