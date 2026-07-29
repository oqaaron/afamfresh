package com.techaus.afamfresh.ui.screens.vendor

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techaus.afamfresh.models.Order
import com.techaus.afamfresh.ui.components.EmptyState
import com.techaus.afamfresh.ui.components.ErrorState
import com.techaus.afamfresh.ui.components.ListSkeleton
import com.techaus.afamfresh.ui.theme.*
import com.techaus.afamfresh.utils.formatUgx
import com.techaus.afamfresh.viewmodel.VendorViewModel

// ⚠️ INFERRED screen. Signature matches MainScreen.kt's composable("vendor_orders")
// call: VendorOrdersScreen(vendorViewModel, onBack).
@Composable
fun VendorOrdersScreen(
    vendorViewModel: VendorViewModel,
    onBack: () -> Unit
) {
    val orders by vendorViewModel.vendorOrders.collectAsState()
    val isLoading by vendorViewModel.isLoading.collectAsState()
    val error by vendorViewModel.error.collectAsState()
    val canRetry by vendorViewModel.canRetry.collectAsState()

    LaunchedEffect(Unit) { vendorViewModel.loadVendorOrders() }

    Scaffold(
        containerColor = Cream,
        topBar = {
            Row(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Ink)
                }
                Text("Orders", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                isLoading && orders.isEmpty() -> ListSkeleton(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                error != null && orders.isEmpty() -> ErrorState(
                    message = error ?: "",
                    onRetry = if (canRetry) ({ vendorViewModel.loadVendorOrders() }) else null
                )
                orders.isEmpty() -> EmptyState(
                    icon = Icons.Default.ReceiptLong,
                    title = "No orders yet",
                    detail = "Orders customers place for your products will appear here."
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(orders, key = { it.id }) { order -> VendorOrderRow(order) }
                }
            }
        }
    }
}

@Composable
private fun VendorOrderRow(order: Order) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CardWhite).padding(14.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text("Order #${order.id}", fontWeight = FontWeight.SemiBold, color = Ink)
            Text(order.status.replaceFirstChar { it.uppercase() }, fontSize = 12.sp, color = InkMuted)
        }
        Text(formatUgx(order.total), fontWeight = FontWeight.Bold, color = Forest)
    }
}
