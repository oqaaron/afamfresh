package com.techaus.afamfresh.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techaus.afamfresh.models.SurplusOrder
import com.techaus.afamfresh.ui.components.EmptyState
import com.techaus.afamfresh.ui.components.ErrorState
import com.techaus.afamfresh.ui.components.ListSkeleton
import com.techaus.afamfresh.ui.theme.*
import com.techaus.afamfresh.utils.formatUgx
import com.techaus.afamfresh.viewmodel.SurplusViewModel

/**
 * A customer's surplus orders.
 *
 * Kept separate from the ordinary order list because surplus orders live in a
 * different table with a different lifecycle: they carry a payment status that
 * can sit at "awaiting payment" for half an hour before being cancelled, and a
 * pickup code the customer has to be able to find again.
 *
 * This screen is also where every uncertain payment outcome lands. Someone
 * arriving here may have just paid, may have abandoned a payment, or may not
 * know which. So each card states the payment position plainly rather than
 * folding it into a single "status" word.
 */
@Composable
fun SurplusOrdersScreen(
    surplusViewModel: SurplusViewModel,
    userId: Int?,
    onBack: () -> Unit
) {
    val orders by surplusViewModel.myOrders.collectAsState()
    val isLoading by surplusViewModel.ordersLoading.collectAsState()
    val error by surplusViewModel.ordersError.collectAsState()

    // Reloaded on every visit, not cached: the most common way to arrive here is
    // straight from a payment, and a stale list would show the order the app saw
    // before the money was confirmed.
    LaunchedEffect(userId) {
        userId?.let { surplusViewModel.loadMyOrders(it) }
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
                Column {
                    Text("Surplus orders", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Text("Bulk deals you have bought", fontSize = 12.sp, color = InkMuted)
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                isLoading && orders.isEmpty() ->
                    ListSkeleton(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))

                error != null && orders.isEmpty() ->
                    ErrorState(
                        message = error ?: "",
                        onRetry = userId?.let { id -> { surplusViewModel.loadMyOrders(id) } }
                    )

                orders.isEmpty() ->
                    EmptyState(
                        icon = Icons.Default.Inventory2,
                        title = "No surplus orders yet",
                        detail = "Bulk deals you buy from the surplus marketplace show up here.",
                        actionLabel = "BROWSE SURPLUS",
                        onAction = onBack
                    )

                else ->
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(orders, key = { it.id }) { order -> OrderCard(order) }
                    }
            }
        }
    }
}

@Composable
private fun OrderCard(order: SurplusOrder) {
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
                    order.productName ?: "Surplus order",
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
                "${order.quantityLabel}  •  ${order.totalWeightKg.toInt()} kg",
                fontSize = 13.sp,
                color = InkMuted
            )
            Text(formatUgx(order.grandTotal), fontWeight = FontWeight.Bold, color = Forest)
        }

        if (order.deliveryFee > 0) {
            Text(
                "includes ${formatUgx(order.deliveryFee)} delivery",
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

        // The whole point of a pickup code is being able to find it later.
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

        if (order.isAwaitingPayment) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Not paid yet. Unpaid orders are released after 30 minutes and the stock " +
                    "goes back on sale.",
                fontSize = 11.sp,
                color = InkMuted
            )
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
    else -> status.replaceFirstChar { it.uppercase() }
}

/**
 * The payment position, in words a customer can act on.
 *
 * `authorization_pending` is deliberately not shown as "pending": it means a
 * payment is genuinely in flight, and someone reading "pending" is far more
 * likely to try paying again.
 */
private fun paymentChip(paymentStatus: String): Pair<String, Color> = when (paymentStatus) {
    "paid" -> "Paid" to Forest
    "pending_cash" -> "Cash on delivery" to Forest
    "authorization_pending" -> "Payment in progress" to Ink
    "failed" -> "Payment failed" to Color(0xFFB3261E)
    "cancelled" -> "Cancelled" to Color(0xFFB3261E)
    else -> "Awaiting payment" to Ink
}