package com.techaus.afamfresh.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentPolicyTest {

    @Test
    fun `cash is allowed up to the configured cap`() {
        assertTrue(PaymentPolicy.cashAllowedFor(50000.0))
    }

    @Test
    fun `cash is blocked above the configured cap`() {
        assertFalse(PaymentPolicy.cashAllowedFor(50000.01))
    }
}
