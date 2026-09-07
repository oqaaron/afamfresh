package com.techaus.afamfresh.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Payment
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techaus.afamfresh.models.BulkOrder
import com.techaus.afamfresh.ui.components.EmptyState
import com.techaus.afamfresh.ui.components.ErrorState
import com.techaus.afamfresh.ui.components.ListSkeleton
import com.techaus.afamfresh.ui.theme.*
import com.techaus.afamfresh.utils.formatUgx
import com.techaus.afamfresh.viewmodel.BulkViewModel

@Composable
fun BulkOrdersScreen(
    BulkViewModel: BulkViewModel,
    userId: Int?,
    onBack: () -> Unit,
    onTrackOrder: (Int) -> Unit = {},
    onConfirmReceipt: (Int) -> Unit = {},
    onPayNow: (BulkOrder) -> Unit = {}
) {
    val orders by BulkViewModel.myOrders.collectAsState()
    val isLoading by BulkViewModel.ordersLoading.collectAsState()
    val error by BulkViewModel.ordersError.collectAsState()

    LaunchedEffect(userId) {
        userId?.let { BulkViewModel.loadMyOrders(it) }
    }

    Scaffold(
        containerColor = Cream,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Ink)
                }
                Column {
                    Text("Merchant orders", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Text("Merchant purchases you have made", fontSize = 12.sp, color = InkMuted)
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                isLoading && orders.isEmpty() ->
                    ListSkeleton(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))

                error != null && orders.isEmpty() ->
                    ErrorState(
                        message = error ?: "",
                        onRetry = userId?.let { id -> { BulkViewModel.loadMyOrders(id) } }
                    )

                orders.isEmpty() ->
                    EmptyState(
                        icon = Icons.Default.Inventory2,
                        title = "No merchant orders yet",
                        detail = "Merchant purchases you make from the marketplace will show up here.",
                        actionLabel = "BROWSE MERCHANTS",
                        onAction = onBack
                    )

                else ->
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(orders, key = { it.id }) { order ->
                            OrderCard(
                                order = order,
                                onTrack = { onTrackOrder(order.id) },
                                onConfirmReceipt = { onConfirmReceipt(order.id) },
                                onPayNow = { onPayNow(order) }
                            )
                        }
                    }
            }
        }
    }
}

@Composable
private fun OrderCard(
    order: BulkOrder,
    onTrack: () -> Unit = {},
    onConfirmReceipt: () -> Unit = {},
    onPayNow: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardWhite)
            .padding(16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = order.productName ?: "Bulk order",
                    fontWeight = FontWeight.SemiBold,
                    color = Ink
                )
                order.businessName?.let { Text(it, fontSize = 12.sp, color = InkMuted) }
            }
            Text("#${order.id}", fontSize = 12.sp, color = InkMuted)
        }

        Spacer(Modifier.height(10.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = "${order.quantityLabel}  •  ${order.totalWeightKg.toInt()} kg",
                fontSize = 13.sp,
                color = InkMuted
            )
            Text(formatUgx(order.grandTotal), fontWeight = FontWeight.Bold, color = Forest)
        }

        if (order.deliveryFee > 0) {
            Text(
                text = "includes ${formatUgx(order.deliveryFee)} delivery",
                fontSize = 11.sp,
                color = InkMuted
            )
        }

        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusChip(label = orderStatusLabel(order.status), tone = Forest)
            Spacer(Modifier.width(8.dp))
            val (paymentLabel, paymentTone) = paymentChip(order.paymentStatus)
            StatusChip(label = paymentLabel, tone = paymentTone)
        }

        order.pickupCode?.takeIf { it.isNotBlank() }?.let { code ->
            Spacer(Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(ForestSurface)
                    .padding(12.dp)
            ) {
                Text("Collection code", fontSize = 11.sp, color = InkMuted)
                Text(code, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Ink)
            }
        }

        // Active button for orders awaiting payment or with failed transactions
        val canPay = (order.isAwaitingPayment || order.paymentStatus.equals("failed", ignoreCase = true)) &&
            order.status !in setOf("cancelled", "refunded")

        if (canPay) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = if (order.paymentStatus.equals("failed", ignoreCase = true)) {
                    "Payment failed. You can retry paying with mobile money or card."
                } else {
                    "Not paid yet. Unpaid reservations are released after 30 minutes."
                },
                fontSize = 11.sp,
                color = Tomato
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onPayNow,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Tomato)
            ) {
                Icon(
                    imageVector = Icons.Default.Payment,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Pay Now • ${formatUgx(order.grandTotal)}",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
        }

        if (order.hasRider && order.status !in setOf("delivered", "cancelled", "refunded")) {
            Spacer(Modifier.height(10.dp))
            FilledTonalButton(onClick = onTrack, modifier = Modifier.fillMaxWidth()) {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("Track this delivery")
            }
        }

        if (order.needsReceiptConfirmation) {
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onConfirmReceipt,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Forest)
            ) {
                Text("Confirm & Rate", color = Color.White)
            }
        }
    }
}

@Composable
private fun StatusChip(label: String, tone: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(tone.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = tone)
    }
}

private fun orderStatusLabel(status: String): String = when (status) {
    "pending" -> "Placed"
    "confirmed" -> "Confirmed"
    "processing" -> "Being prepared"
    "ready" -> "Ready"
    "delivered" -> "Delivered"
    "cancelled" -> "Cancelled"
    "refunded" -> "Refunded"
    "cancellation_requested" -> "Cancellation requested"
    else -> status.replaceFirstChar { it.uppercase() }
}

private fun paymentChip(paymentStatus: String): Pair<String, Color> = when (paymentStatus) {
    "paid" -> "Paid" to Forest
    "pending_cash" -> "Cash on delivery" to Forest
    "authorization_pending" -> "Payment in progress" to Ink
    "failed" -> "Payment failed" to Color(0xFFB3261E)
    "cancelled" -> "Cancelled" to Color(0xFFB3261E)
    else -> "Awaiting payment" to Tomato
}