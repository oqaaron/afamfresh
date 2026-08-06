package com.techaus.afamfresh.models

import com.google.gson.annotations.SerializedName

/**
 * ✅ VERIFIED against the rewritten `api/payment.php`.
 *
 * The previous version was inferred and wrong in ways that made payment
 * impossible: it expected only `redirect_url` while the endpoint returned
 * `payment_url`, and it sent an `amount` the server now deliberately ignores.
 */

/**
 * Body for `payment.php?action=initiate`.
 *
 * NOTE THE ABSENCE OF `amount`.
 *
 * The server reads the payable total from the `orders` row and ignores any
 * amount in the request — otherwise a customer could pay 1 UGX for a 100,000
 * UGX order by editing one field. Sending it would be harmless but misleading,
 * so the field is gone: the wire format should not imply the client has a say.
 *
 * `email` and `phone` are optional overrides. When omitted the server falls back
 * to the account email and the order's mobile number; Pesapal requires at least
 * one of the two.
 */
data class PaymentRequest(
    @SerializedName("order_id") val orderId: String,
    @SerializedName("payment_method") val paymentMethod: String = METHOD_MOBILE_MONEY,
    @SerializedName("email") val email: String? = null,
    @SerializedName("phone") val phone: String? = null
) {
    companion object {
        const val METHOD_MOBILE_MONEY = "mobile_money"

        /** Cash on delivery — the server skips Pesapal entirely for this. */
        const val METHOD_CASH = "cash"
    }
}

/**
 * Response shared by `initiate`, `verify` and `status`.
 *
 * [paid] is the field to branch on. It is computed server-side from Pesapal's
 * GetTransactionStatus and is the only trustworthy signal — the app must never
 * infer success from a redirect URL's query parameters, which is what
 * PaymentWebViewScreen used to do.
 */
data class PaymentResponse(
    @SerializedName("success") val success: Boolean = false,

    /** pending | paid | failed | reversed | invalid | pending_cash */
    @SerializedName("status") val status: String? = null,

    /** Authoritative. Absent on error responses, hence the default. */
    @SerializedName("paid") val paid: Boolean = false,

    @SerializedName("order_id") val orderId: String? = null,

    /** Pesapal's order_tracking_id. */
    @SerializedName("transaction_id") val transactionId: String? = null,

    /** Where to send the customer to pay. Only present on a successful initiate. */
    @SerializedName("redirect_url") val redirectUrl: String? = null,

    /** Same value as [redirectUrl]; the endpoint emits both for the web client. */
    @SerializedName("payment_url") val paymentUrl: String? = null,

    /** Always the server's figure, never what the app asked for. */
    @SerializedName("amount") val amount: Double? = null,
    @SerializedName("currency") val currency: String? = null,

    /** Channel Pesapal reported, e.g. "MpesaKE", "Visa". Verify only. */
    @SerializedName("method") val method: String? = null,
    @SerializedName("description") val description: String? = null,

    /** "sandbox" or "live" — surfaced so a test build cannot be mistaken for real. */
    @SerializedName("environment") val environment: String? = null,

    @SerializedName("message") val message: String? = null,
    @SerializedName("error") val error: String? = null,

    /**
     * UNAUTHENTICATED | ORDER_NOT_FOUND | BAD_REQUEST | INVALID_AMOUNT |
     * PESAPAL_UNAVAILABLE | VERIFY_UNAVAILABLE
     */
    @SerializedName("error_code") val errorCode: String? = null
) {
    /** Whichever redirect key the server populated. */
    val checkoutUrl: String?
        get() = redirectUrl?.takeIf { it.isNotBlank() } ?: paymentUrl?.takeIf { it.isNotBlank() }

    val isCashOnDelivery: Boolean get() = status == STATUS_PENDING_CASH

    /**
     * Still in flight. Distinct from failure: a pending payment must not be
     * presented as an error, or the customer may pay twice.
     */
    val isPending: Boolean get() = status == STATUS_PENDING || status == null

    /**
     * Definitively not going to complete. `pending` and `pending_cash` are
     * excluded on purpose — only these three are terminal failures.
     */
    val isFailed: Boolean
        get() = status == STATUS_FAILED || status == STATUS_REVERSED || status == STATUS_INVALID

    /**
     * True when the outcome is genuinely unknown, i.e. the server could not
     * reach Pesapal. The right response is to retry, never to report failure.
     */
    val isUnconfirmed: Boolean
        get() = errorCode == ERROR_VERIFY_UNAVAILABLE

    /** A message that is safe to show a customer, whatever happened. */
    val userMessage: String
        get() = when {
            paid -> "Payment received. Your order is confirmed."
            isCashOnDelivery -> message ?: "Pay with cash when your order arrives."
            isFailed -> when (status) {
                STATUS_REVERSED ->
                    "This payment was reversed. Your provider will return any amount taken."
                STATUS_INVALID ->
                    "That transaction could not be processed. You have not been charged."
                else ->
                    "The payment did not go through. You have not been charged."
            }
            isUnconfirmed ->
                "We couldn't confirm the payment yet. Don't pay again — we'll keep checking."
            error != null -> error
            else -> "Your payment is still being processed."
        }

    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_PAID = "paid"
        const val STATUS_FAILED = "failed"
        const val STATUS_REVERSED = "reversed"
        const val STATUS_INVALID = "invalid"
        const val STATUS_PENDING_CASH = "pending_cash"

        const val ERROR_UNAUTHENTICATED = "UNAUTHENTICATED"
        const val ERROR_ORDER_NOT_FOUND = "ORDER_NOT_FOUND"
        const val ERROR_PESAPAL_UNAVAILABLE = "PESAPAL_UNAVAILABLE"
        const val ERROR_VERIFY_UNAVAILABLE = "VERIFY_UNAVAILABLE"
    }
}
