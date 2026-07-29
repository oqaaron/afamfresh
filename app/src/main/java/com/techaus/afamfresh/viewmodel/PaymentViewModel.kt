package com.techaus.afamfresh.viewmodel

import androidx.lifecycle.ViewModel
import com.techaus.afamfresh.models.PaymentRequest
import com.techaus.afamfresh.models.PaymentResponse
import com.techaus.afamfresh.repository.PaymentRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// Constructor confirmed by MainActivity.kt:
//   paymentViewModel = PaymentViewModel(paymentRepository)
class PaymentViewModel(
    private val paymentRepository: PaymentRepository
) : ViewModel() {

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _paymentResult = MutableStateFlow<PaymentResponse?>(null)
    val paymentResult: StateFlow<PaymentResponse?> = _paymentResult.asStateFlow()

    fun initiatePayment(
        orderId: String,
        amount: Double,
        email: String? = null,
        phone: String? = null,
        onRedirect: (paymentUrl: String, transactionId: String) -> Unit
    ) {
        _isLoading.value = true
        _error.value = null

        val request = PaymentRequest(orderId = orderId, amount = amount, email = email, phone = phone)
        paymentRepository.initiatePayment(request) { response, error ->
            _isLoading.value = false
            _paymentResult.value = response
            if (response?.success == true && response.redirectUrl != null && response.transactionId != null) {
                onRedirect(response.redirectUrl, response.transactionId)
            } else {
                _error.value = error?.userMessage
                    ?: response?.error
                    ?: "Unable to start payment"
            }
        }
    }

    /**
     * Reports both whether the payment completed and, separately, whether we
     * could not tell. A verification that fails to reach the server is NOT the
     * same as a payment that failed — telling a customer their payment failed
     * when it may have succeeded is the worst outcome here.
     */
    fun verifyPayment(transactionId: String, onResult: (completed: Boolean, undetermined: Boolean) -> Unit) {
        _isLoading.value = true
        _error.value = null
        paymentRepository.verifyPayment(transactionId) { response, error ->
            _isLoading.value = false
            _paymentResult.value = response

            if (error != null) {
                _error.value = error.userMessage
                onResult(false, true)
                return@verifyPayment
            }

            val completed = response?.success == true && response.status == "completed"
            onResult(completed, false)
        }
    }

    fun clearError() {
        _error.value = null
    }
}
