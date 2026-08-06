package com.techaus.afamfresh.models

import com.techaus.afamfresh.api.afamFreshGson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for `api/payment.php`.
 *
 * Every JSON body below is a real response shape from that endpoint, captured
 * while probing it against the live database.
 *
 * These matter more than the other contract tests: a misread payment status
 * either tells a customer who paid that their payment failed, or marks an unpaid
 * order as settled. The old code managed both — it compared status against
 * "completed", a value the endpoint never returns, and read `redirect_url` when
 * the server sent `payment_url`.
 */
class PaymentContractTest {

    private val gson = afamFreshGson()

    // ======================================================================
    // initiate
    // ======================================================================

    @Test
    fun `initiate returns a checkout url under either key`() {
        val json = """
            {
              "success": true,
              "status": "pending",
              "paid": false,
              "order_id": "500930",
              "amount": 28570.51,
              "currency": "UGX",
              "redirect_url": "https://pay.pesapal.com/iframe/PesapalIframe3/Index?OrderTrackingId=abc-123",
              "payment_url": "https://pay.pesapal.com/iframe/PesapalIframe3/Index?OrderTrackingId=abc-123",
              "transaction_id": "abc-123",
              "environment": "sandbox"
            }
        """.trimIndent()

        val response = gson.fromJson(json, PaymentResponse::class.java)

        assertTrue(response.success)
        assertFalse("a freshly started payment is not paid", response.paid)
        assertTrue(response.isPending)
        assertEquals("abc-123", response.transactionId)
        assertEquals(28570.51, response.amount!!, 0.001)
        assertNotNull(response.checkoutUrl)
        assertTrue(response.checkoutUrl!!.startsWith("https://pay.pesapal.com/"))
    }

    /**
     * The endpoint emits both keys, but only one is guaranteed by any given
     * caller. checkoutUrl must resolve either — the old model read redirect_url
     * only, and the stub server sent payment_url only, so nothing ever opened.
     */
    @Test
    fun `checkout url falls back to payment_url`() {
        val response = gson.fromJson(
            """{"success":true,"payment_url":"https://pay.pesapal.com/x","transaction_id":"t1"}""",
            PaymentResponse::class.java
        )
        assertEquals("https://pay.pesapal.com/x", response.checkoutUrl)
    }

    @Test
    fun `blank urls do not count as a checkout url`() {
        val response = gson.fromJson(
            """{"success":true,"redirect_url":"","payment_url":"  "}""",
            PaymentResponse::class.java
        )
        assertNull(response.checkoutUrl)
    }

    /** Real captured response from the cash branch. */
    @Test
    fun `cash on delivery is recognised and is not a failure`() {
        val json = """
            {
              "success": true,
              "status": "pending_cash",
              "paid": false,
              "order_id": "500930",
              "amount": 28570.51,
              "currency": "UGX",
              "message": "Pay with cash when your order arrives."
            }
        """.trimIndent()

        val response = gson.fromJson(json, PaymentResponse::class.java)

        assertTrue(response.isCashOnDelivery)
        assertFalse("cash is not a failed payment", response.isFailed)
        assertFalse(response.paid)
        assertEquals("Pay with cash when your order arrives.", response.userMessage)
    }

    /**
     * The client's amount is ignored server-side. Verified against the live
     * endpoint: sending amount=1 for order 500930 returned 28570.51.
     */
    @Test
    fun `payment request carries no amount field`() {
        val json = gson.toJson(
            PaymentRequest(orderId = "500930", phone = "0785463210", email = "a@b.com")
        )

        assertTrue("expected order_id in $json", json.contains("\"order_id\""))
        assertTrue(json.contains("\"payment_method\""))
        assertFalse(
            "the wire format must not imply the client can set the amount: $json",
            json.contains("\"amount\"")
        )
    }

    @Test
    fun `payment request defaults to mobile money`() {
        assertEquals(
            PaymentRequest.METHOD_MOBILE_MONEY,
            PaymentRequest(orderId = "1").paymentMethod
        )
    }

    // ======================================================================
    // verify
    // ======================================================================

    @Test
    fun `paid is read from the paid flag, not from a status string`() {
        val json = """
            {
              "success": true,
              "status": "paid",
              "paid": true,
              "order_id": "500930",
              "transaction_id": "abc-123",
              "amount": 28570.51,
              "currency": "UGX",
              "method": "MpesaKE",
              "description": "Completed"
            }
        """.trimIndent()

        val response = gson.fromJson(json, PaymentResponse::class.java)

        assertTrue(response.paid)
        assertFalse(response.isPending)
        assertFalse(response.isFailed)
        assertEquals("MpesaKE", response.method)
    }

    /**
     * Regression guard for the exact bug that made every real payment look
     * unfinished: the app compared `status == "completed"`. The server says
     * "paid".
     */
    @Test
    fun `the server never says completed`() {
        val response = gson.fromJson(
            """{"success":true,"status":"paid","paid":true}""",
            PaymentResponse::class.java
        )

        assertEquals("paid", response.status)
        assertTrue(
            "code comparing against \"completed\" would miss this",
            response.status != "completed" && response.paid
        )
    }

    @Test
    fun `failed reversed and invalid are terminal failures`() {
        for (status in listOf("failed", "reversed", "invalid")) {
            val response = gson.fromJson(
                """{"success":true,"status":"$status","paid":false}""",
                PaymentResponse::class.java
            )
            assertTrue("$status should be terminal", response.isFailed)
            assertFalse(response.paid)
            assertFalse(response.isPending)
        }
    }

    /** Pending must never be treated as failure — that is what causes double payment. */
    @Test
    fun `pending is neither paid nor failed`() {
        val response = gson.fromJson(
            """{"success":true,"status":"pending","paid":false,"order_id":"500930"}""",
            PaymentResponse::class.java
        )

        assertTrue(response.isPending)
        assertFalse(response.isFailed)
        assertFalse(response.paid)
    }

    /** Captured response for an order with no payment attempt yet. */
    @Test
    fun `an order with no payment started reads as pending`() {
        val json = """
            {
              "success": true,
              "status": "pending",
              "paid": false,
              "order_id": "500930",
              "message": "No payment has been started for this order yet."
            }
        """.trimIndent()

        val response = gson.fromJson(json, PaymentResponse::class.java)

        assertTrue(response.isPending)
        assertFalse(response.isFailed)
    }

    // ======================================================================
    // Errors
    // ======================================================================

    /**
     * A verify that could not reach Pesapal is UNKNOWN, not failed. Showing
     * "payment failed" here is the single most damaging thing the app can do.
     */
    @Test
    fun `verify unavailable is unconfirmed, never failed`() {
        val json = """
            {
              "success": false,
              "error": "We could not confirm the payment yet. Please try again shortly.",
              "error_code": "VERIFY_UNAVAILABLE"
            }
        """.trimIndent()

        val response = gson.fromJson(json, PaymentResponse::class.java)

        assertTrue(response.isUnconfirmed)
        assertFalse("must NOT read as failed", response.isFailed)
        assertFalse(response.paid)
        assertTrue(
            "the message must discourage paying again, got: ${response.userMessage}",
            response.userMessage.contains("again", ignoreCase = true)
        )
    }

    /** Captured: probing initiate with no session. */
    @Test
    fun `unauthenticated response parses`() {
        val response = gson.fromJson(
            """{"success":false,"error":"Not logged in","error_code":"UNAUTHENTICATED"}""",
            PaymentResponse::class.java
        )

        assertFalse(response.success)
        assertFalse(response.paid)
        assertEquals(PaymentResponse.ERROR_UNAUTHENTICATED, response.errorCode)
    }

    /** Captured: user 100 targeting order 500058, which belongs to someone else. */
    @Test
    fun `another users order is not found`() {
        val response = gson.fromJson(
            """{"success":false,"error":"Order not found","error_code":"ORDER_NOT_FOUND"}""",
            PaymentResponse::class.java
        )

        assertEquals(PaymentResponse.ERROR_ORDER_NOT_FOUND, response.errorCode)
        assertFalse(response.paid)
        assertNull(response.checkoutUrl)
    }

    @Test
    fun `pesapal unavailable is not a paid state`() {
        val response = gson.fromJson(
            """{"success":false,"error":"We could not start the payment. Please try again in a moment.","error_code":"PESAPAL_UNAVAILABLE"}""",
            PaymentResponse::class.java
        )

        assertFalse(response.paid)
        assertNull(response.checkoutUrl)
        assertEquals(PaymentResponse.ERROR_PESAPAL_UNAVAILABLE, response.errorCode)
    }

    /**
     * An empty or unparseable body must default to "not paid". Gson leaves
     * missing booleans at their default, and for a payment flag that default has
     * to be false.
     */
    @Test
    fun `an empty body is not paid`() {
        val response = gson.fromJson("{}", PaymentResponse::class.java)

        assertFalse(response.success)
        assertFalse(response.paid)
        assertFalse(response.isFailed)
        assertTrue(response.isPending)
        assertNull(response.checkoutUrl)
    }

    /**
     * The environment is echoed so a build pointed at sandbox cannot be mistaken
     * for one taking real money.
     */
    @Test
    fun `environment is surfaced`() {
        val sandbox = gson.fromJson(
            """{"success":true,"environment":"sandbox"}""", PaymentResponse::class.java
        )
        val live = gson.fromJson(
            """{"success":true,"environment":"live"}""", PaymentResponse::class.java
        )

        assertEquals("sandbox", sandbox.environment)
        assertEquals("live", live.environment)
    }

    @Test
    fun `user messages never blame the customer for a pending payment`() {
        val pending = gson.fromJson(
            """{"success":true,"status":"pending","paid":false}""", PaymentResponse::class.java
        )
        assertFalse(
            "a pending message must not claim failure: ${pending.userMessage}",
            pending.userMessage.contains("failed", ignoreCase = true)
        )
    }

    @Test
    fun `a failed payment tells the customer they were not charged`() {
        val failed = gson.fromJson(
            """{"success":true,"status":"failed","paid":false}""", PaymentResponse::class.java
        )
        assertTrue(
            "expected reassurance about charging, got: ${failed.userMessage}",
            failed.userMessage.contains("not been charged", ignoreCase = true)
        )
    }
}
