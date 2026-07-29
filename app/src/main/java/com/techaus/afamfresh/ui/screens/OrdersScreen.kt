package com.techaus.afamfresh.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techaus.afamfresh.models.Order
import com.techaus.afamfresh.ui.components.EmptyState
import com.techaus.afamfresh.ui.components.ErrorState
import com.techaus.afamfresh.ui.components.ListSkeleton
import com.techaus.afamfresh.ui.theme.*
import com.techaus.afamfresh.utils.formatUgx
import com.techaus.afamfresh.viewmodel.OrderViewModel

// ⚠️ INFERRED screen. Signature matches MainScreen.kt's composable("orders")
// call exactly: OrdersScreen(orderViewModel, onBack, onEditOrder).
@Composable
fun OrdersScreen(
    orderViewModel: OrderViewModel,
    onBack: () -> Unit,
    onEditOrder: (String) -> Unit
) {
    val orders by orderViewModel.orders.collectAsState()
    val isLoading by orderViewModel.isLoading.collectAsState()
    val error by orderViewModel.error.collectAsState()
    val canRetry by orderViewModel.canRetry.collectAsState()

    LaunchedEffect(Unit) {
        orderViewModel.loadOrders()
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
                Text("My Orders", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                isLoading && orders.isEmpty() -> {
                    ListSkeleton(modifier = Modifier.padding(horizontal = 20.dp))
                }
                error != null && orders.isEmpty() -> {
                    ErrorState(
                        message = error ?: "",
                        onRetry = if (canRetry) ({ orderViewModel.loadOrders() }) else null
                    )
                }
                orders.isEmpty() -> {
                    EmptyState(
                        icon = Icons.Default.ReceiptLong,
                        title = "No orders yet",
                        detail = "Once you place an order it'll show up here so you can track it."
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(orders, key = { it.id }) { order ->
                            OrderRow(order = order, onClick = { onEditOrder(order.id) })
                        }
                        item { Spacer(modifier = Modifier.height(20.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun OrderRow(order: Order, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardWhite)
            .clickable { onClick() }
            .padding(16.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Order #${order.id}", fontWeight = FontWeight.Bold, color = Ink)
            StatusPill(status = order.status)
        }
        Spacer(modifier = Modifier.height(6.dp))
        order.createdAt?.let {
            Text(it, fontSize = 12.sp, color = InkMuted)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${order.items.size} item(s)", fontSize = 13.sp, color = InkMuted)
            Text(formatUgx(order.total), fontWeight = FontWeight.Bold, color = Ink)
        }
    }
}

@Composable
private fun StatusPill(status: String) {
    val (bg, fg) = when (status.lowercase()) {
        "delivered", "completed" -> ForestSurface to Forest
        "cancelled", "failed" -> Color(0xFFFFEBEE) to Tomato
        else -> PillGray to InkMuted
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(status.replaceFirstChar { it.uppercase() }, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}
