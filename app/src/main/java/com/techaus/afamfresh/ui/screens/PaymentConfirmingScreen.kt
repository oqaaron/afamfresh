package com.techaus.afamfresh.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techaus.afamfresh.api.ApiService
import com.techaus.afamfresh.ui.theme.Cream
import com.techaus.afamfresh.ui.theme.Forest
import com.techaus.afamfresh.ui.theme.Ink
import com.techaus.afamfresh.ui.theme.InkMuted
import com.techaus.afamfresh.viewmodel.PaymentViewModel

/**
 * Shown after the Pesapal page closes, while the server confirms the payment
 * with GetTransactionStatus.
 *
 * WHY A WHOLE SCREEN FOR THIS
 *
 * Mobile-money payments do not settle when the WebView closes — the customer
 * approves a prompt on their handset a few seconds later. Checking once at that
 * moment nearly always reads `pending`, which is why the app previously treated
 * every real payment as a failure.
 *
 * It is also currently the ONLY way an order gets reconciled: the configured IPN
 * host has no DNS record, so Pesapal cannot deliver its server-to-server
 * notification. Until that is fixed, this polling is the payment confirmation.
 *
 * The customer must not be able to leave this screen into a state that invites a
 * second payment, so an unknown outcome routes to the order list, never back to
 * checkout.
 */
@Composable
fun PaymentConfirmingScreen(
    trackingId: String,
    paymentViewModel: PaymentViewModel,
    // Which table the tracking id belongs to. Surplus orders are a separate
    // table; verifying one as a shop order finds nothing and the customer is
    // told their payment could not be confirmed when it went through fine.
    orderType: String = ApiService.ORDER_TYPE_SHOP,
    onPaid: () -> Unit,
    onFailed: () -> Unit,
    onUnconfirmed: () -> Unit
) {
    val result by paymentViewModel.paymentResult.collectAsState()

    // Keyed on the tracking id so a recomposition does not restart polling, and a
    // genuinely new payment does.
    LaunchedEffect(trackingId, orderType) {
        paymentViewModel.awaitPaymentOutcome(
            transactionId = trackingId,
            orderType = orderType
        ) { outcome ->
            when (outcome) {
                PaymentViewModel.Outcome.PAID -> onPaid()
                PaymentViewModel.Outcome.CASH_ON_DELIVERY -> onPaid()
                PaymentViewModel.Outcome.FAILED -> onFailed()
                PaymentViewModel.Outcome.UNCONFIRMED -> onUnconfirmed()
            }
        }
    }

    Scaffold(containerColor = Cream) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator(color = Forest)

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                "Confirming your payment",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Ink,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                // The single most important instruction on this screen.
                "Please wait — do not pay again. If you approved a prompt on your " +
                    "phone, it can take a few moments to come through.",
                fontSize = 13.sp,
                color = InkMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            // Surface whatever the server last reported, so a long wait does not
            // look like the app has hung.
            result?.description?.takeIf { it.isNotBlank() }?.let { description ->
                Spacer(modifier = Modifier.height(16.dp))
                Text(description, fontSize = 12.sp, color = InkMuted, textAlign = TextAlign.Center)
            }
        }
    }
}
