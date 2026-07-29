package com.techaus.afamfresh.models

import com.google.gson.annotations.SerializedName

// ⚠️ INFERRED. MainScreen.kt's CheckoutScreen callback is
// `onPaymentRedirect = { paymentUrl, transactionId -> ... }`, which confirms
// PaymentResponse must carry both a redirect URL and a transaction id — the
// exact JSON key names are a guess pending your real payment.php / Pesapal
// integration code.

data class PaymentRequest(
    @SerializedName("order_id") val orderId: String,
    @SerializedName("amount") val amount: Double,
    @SerializedName("email") val email: String? = null,
    @SerializedName("phone") val phone: String? = null,
    @SerializedName("description") val description: String = "AfamFresh order payment"
)

data class PaymentResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("redirect_url") val redirectUrl: String? = null,
    @SerializedName("transaction_id") val transactionId: String? = null,
    @SerializedName("status") val status: String? = null, // pending | completed | failed
    @SerializedName("error") val error: String? = null
)
