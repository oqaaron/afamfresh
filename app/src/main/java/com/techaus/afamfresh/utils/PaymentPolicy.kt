package com.techaus.afamfresh.utils

object PaymentPolicy {
    const val MAX_CASH_PAYMENT_UGX = 50_000.0

    fun cashAllowedFor(amountUgx: Double): Boolean = amountUgx <= MAX_CASH_PAYMENT_UGX

    fun cashOptionLabel(amountUgx: Double): String =
        if (cashAllowedFor(amountUgx)) {
            "Cash on delivery"
        } else {
            "Cash unavailable above UGX 50,000"
        }
}
