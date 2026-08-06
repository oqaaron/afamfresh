package com.techaus.afamfresh.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techaus.afamfresh.models.PaymentRequest
import com.techaus.afamfresh.models.PaymentResponse
import com.techaus.afamfresh.repository.PaymentRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// Constructor confirmed by MainActivity.kt:
//   paymentViewModel = PaymentViewModel(paymentRepository)
class PaymentViewModel(
    private val paymentRepository: PaymentRepository
) : ViewModel() {

    /** The outcome of a settled payment attempt. */
    enum class Outcome {
        /** Money confirmed received by Pesapal. */
        PAID,

        /** Cash on delivery — nothing to collect online. */
        CASH_ON_DELIVERY,

        /** Definitively failed, reversed or invalid. The customer was not charged. */
        FAILED,

        /**
         * Still in flight after we stopped polling, or we could not reach the
         * server to ask. The outcome is UNKNOWN — never present this as failure,
         * because saying "payment failed" to someone who has paid invites a
         * second payment.
         */
        UNCONFIRMED
    }

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _paymentResult = MutableStateFlow<PaymentResponse?>(null)
    val paymentResult: StateFlow<PaymentResponse?> = _paymentResult.asStateFlow()

    /** True while [awaitPaymentOutcome] is polling, so the UI can say "confirming…". */
    private val _isConfirming = MutableStateFlow(false)
    val isConfirming: StateFlow<Boolean> = _isConfirming.asStateFlow()

    private var pollJob: Job? = null

    /**
     * Starts a payment for an already-created order.
     *
     * No amount is passed: the server reads the payable total from the order and
     * ignores anything the client sends, so accepting one here would only invite
     * the caller to believe it mattered.
     *
     * [onRedirect] fires for online payments and carries the Pesapal checkout URL.
     * [onCashAccepted] fires instead when the method is cash, where there is no
     * page to open.
     */
    fun initiatePayment(
        orderId: String,
        paymentMethod: String = PaymentRequest.METHOD_MOBILE_MONEY,
        email: String? = null,
        phone: String? = null,
        onCashAccepted: () -> Unit = {},
        onRedirect: (paymentUrl: String, transactionId: String) -> Unit
    ) {
        _isLoading.value = true
        _error.value = null

        val request = PaymentRequest(
            orderId = orderId,
            paymentMethod = paymentMethod,
            email = email,
            phone = phone
        )

        paymentRepository.initiatePayment(request) { response, error ->
            _isLoading.value = false
            _paymentResult.value = response

            when {
                error != null -> _error.value = error.userMessage

                response == null -> _error.value = "Unable to start payment"

                // The order was already settled before we got here.
                response.paid -> onCashAccepted()

                response.isCashOnDelivery -> onCashAccepted()

                // checkoutUrl reads redirect_url OR payment_url — the endpoint
                // sends both, and the old model only looked at the first.
                response.checkoutUrl != null && response.transactionId != null ->
                    onRedirect(response.checkoutUrl!!, response.transactionId!!)

                else -> _error.value = response.error ?: "Unable to start payment"
            }
        }
    }

    /**
     * Confirms a payment once, reporting what the server said.
     *
     * The status to look for is "paid". This previously compared against
     * "completed", a value the endpoint never returns, so a successful payment
     * could never be recognised.
     */
    fun verifyPayment(
        transactionId: String? = null,
        orderId: String? = null,
        onResult: (Outcome) -> Unit
    ) {
        _isLoading.value = true
        _error.value = null

        paymentRepository.verifyPayment(transactionId = transactionId, orderId = orderId) { response, error ->
            _isLoading.value = false
            _paymentResult.value = response
            onResult(classify(response, error != null))
        }
    }

    /**
     * Polls until the payment settles, or gives up and reports [Outcome.UNCONFIRMED].
     *
     * Needed because mobile-money payments are not instant: the customer approves
     * a prompt on their handset seconds after the WebView closes. A single check
     * at that moment almost always reads `pending`.
     *
     * Also the only way an order gets reconciled at all right now — the configured
     * IPN host has no DNS record, so Pesapal's server-to-server notification
     * cannot be delivered.
     */
    fun awaitPaymentOutcome(
        transactionId: String? = null,
        orderId: String? = null,
        attempts: Int = 10,
        intervalMs: Long = 3000,
        onOutcome: (Outcome) -> Unit
    ) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            _isConfirming.value = true
            _error.value = null

            var last: Outcome = Outcome.UNCONFIRMED

            repeat(attempts) { attempt ->
                // Wait before the FIRST check too: the payment cannot possibly
                // have settled in the instant the WebView closed.
                delay(intervalMs)

                val outcome = verifyOnce(transactionId, orderId)
                last = outcome

                // Settled either way — stop polling.
                if (outcome == Outcome.PAID ||
                    outcome == Outcome.FAILED ||
                    outcome == Outcome.CASH_ON_DELIVERY
                ) {
                    _isConfirming.value = false
                    onOutcome(outcome)
                    return@launch
                }
            }

            _isConfirming.value = false
            // Ran out of attempts while still pending.
            onOutcome(last)
        }
    }

    /** Cancels any in-flight polling, e.g. when the user navigates away. */
    fun stopConfirming() {
        pollJob?.cancel()
        pollJob = null
        _isConfirming.value = false
    }

    override fun onCleared() {
        super.onCleared()
        pollJob?.cancel()
    }

    /** One verify call, bridged from the callback API into the coroutine. */
    private suspend fun verifyOnce(transactionId: String?, orderId: String?): Outcome =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            paymentRepository.verifyPayment(
                transactionId = transactionId,
                orderId = orderId
            ) { response, error ->
                _paymentResult.value = response
                if (cont.isActive) cont.resume(classify(response, error != null)) {}
            }
        }

    /**
     * Maps a response onto an [Outcome].
     *
     * Anything not explicitly settled becomes UNCONFIRMED rather than FAILED.
     * Only the server's own terminal statuses produce FAILED.
     */
    private fun classify(response: PaymentResponse?, hadTransportError: Boolean): Outcome = when {
        hadTransportError || response == null -> Outcome.UNCONFIRMED
        response.paid -> Outcome.PAID
        response.isCashOnDelivery -> Outcome.CASH_ON_DELIVERY
        response.isFailed -> Outcome.FAILED
        else -> Outcome.UNCONFIRMED
    }

    fun clearError() {
        _error.value = null
    }
}
