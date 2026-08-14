package com.techaus.afamfresh.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techaus.afamfresh.api.ApiService
import com.techaus.afamfresh.models.PaymentRequest
import com.techaus.afamfresh.ui.theme.*
import com.techaus.afamfresh.utils.formatUgx
import com.techaus.afamfresh.viewmodel.PaymentViewModel

/**
 * Retries payment on an order that already exists, rather than placing a new
 * one.
 *
 * WHY THIS SCREEN EXISTS
 *
 * A failed Pesapal attempt used to send the customer all the way back to
 * CheckoutScreen with the cart still in hand. Tapping "Place order" again ran
 * `api/orders.php?action=create` a second time — a brand-new order id, while
 * the first stayed behind forever at `payment_status = 'failed'` (shop orders
 * have no cleanup job; surplus ones self-release stock after 30 minutes, but
 * still leave a dead order row). A customer whose mobile money bounced and who
 * then chose cash ended up with two orders: one dead, one real.
 *
 * This screen instead calls `api/payment.php?action=initiate` again on the
 * SAME order id with whichever method the customer picks now. The server
 * already supports this — there is no guard against retrying a 'failed'
 * order — the gap was purely that nothing in the app routed here.
 */
@Composable
fun PaymentRetryScreen(
    orderId: String,
    orderType: String,
    amount: Double?,
    paymentViewModel: PaymentViewModel,
    onBack: () -> Unit,
    onCashAccepted: () -> Unit,
    onRedirect: (paymentUrl: String, transactionId: String) -> Unit
) {
    var paymentMethod by remember { mutableStateOf("mobile_money") }
    val isPaying by paymentViewModel.isLoading.collectAsState()
    val error by paymentViewModel.error.collectAsState()

    fun retry() {
        paymentViewModel.clearError()
        paymentViewModel.initiatePayment(
            orderId = orderId,
            paymentMethod = if (paymentMethod == "cash") {
                PaymentRequest.METHOD_CASH
            } else {
                PaymentRequest.METHOD_MOBILE_MONEY
            },
            orderType = orderType,
            onCashAccepted = onCashAccepted,
            onRedirect = onRedirect
        )
    }

    Scaffold(
        containerColor = Cream,
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Ink)
                }
                Text("Retry payment", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            ) {
                Text(
                    "Order #$orderId is still waiting to be paid",
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                    fontSize = 16.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Your last attempt didn't go through — nothing was charged. " +
                        "Pick a payment method to try again.",
                    fontSize = 13.sp,
                    color = InkMuted,
                    textAlign = TextAlign.Start
                )
                amount?.let {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        "Amount due: ${formatUgx(it)}",
                        fontWeight = FontWeight.Bold,
                        color = Forest,
                        fontSize = 15.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(CardWhite)
                    .padding(16.dp)
            ) {
                Text("Payment method", fontWeight = FontWeight.Bold, color = Ink, fontSize = 16.sp)
                Spacer(modifier = Modifier.height(10.dp))
                PaymentOptionRow(
                    label = "Mobile Money / Card (Pesapal)",
                    selected = paymentMethod == "mobile_money",
                    onSelect = { paymentMethod = "mobile_money" }
                )
                Spacer(modifier = Modifier.height(8.dp))
                PaymentOptionRow(
                    label = "Cash on Delivery",
                    selected = paymentMethod == "cash",
                    onSelect = { paymentMethod = "cash" }
                )
            }

            error?.let {
                Spacer(modifier = Modifier.height(12.dp))
                Text(it, color = Tomato, fontSize = 13.sp)
            }

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = { retry() },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Forest),
                enabled = !isPaying
            ) {
                if (isPaying) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), color = Color.White)
                } else {
                    Text(
                        if (paymentMethod == "cash") "PLACE AS CASH ON DELIVERY" else "RETRY PAYMENT",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
