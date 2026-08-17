package com.techaus.afamfresh.ui.screens.vendor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.techaus.afamfresh.models.BulkOrder
import com.techaus.afamfresh.models.UpdateBulkOrderStatusRequest
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
    val profile by vendorViewModel.profile.collectAsState()
    val updating by vendorViewModel.updatingOrders.collectAsState()

    // Keyed on the vendor profile, not Unit: Bulk-orders.php needs a
    // vendor_id, and that is only known once MainScreen's start() has resolved
    // the vendor record. Firing on Unit could run first and load nothing.
    LaunchedEffect(profile?.id) {
        if (profile != null) vendorViewModel.loadVendorOrders()
    }

    // Reload whenever the screen comes back to the foreground.
    //
    // An order's status is moved by OTHER people — a rider marking it
    // delivered, an admin dispatching or cancelling it — and this list used to
    // load exactly once, on first composition. A seller who left it open (or
    // switched away and back) kept looking at the state as it was when they
    // opened it, which reads as the rider's delivery never having registered.
    // Nothing here polls; this only costs a request when the seller is
    // actually looking.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, profile?.id) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && profile != null) {
                vendorViewModel.loadVendorOrders()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        containerColor = Cream,
        topBar = {
            Row(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Ink)
                }
                Text("Orders", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
                Spacer(Modifier.weight(1f))
                // Explicit refresh as well as the resume hook above: a seller
                // waiting on a rider who is minutes away should not have to
                // leave the screen and come back to find out.
                IconButton(
                    onClick = { if (profile != null) vendorViewModel.loadVendorOrders() },
                    enabled = !isLoading
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh orders", tint = Ink)
                }
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
                    // An update that fails while orders are on screen has
                    // nowhere else to be seen: ErrorState only renders when the
                    // list is empty, so a refusal like "this order has not been
                    // paid for" would vanish silently.
                    error?.let { message ->
                        item {
                            Text(
                                message,
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                    }
                    items(orders, key = { it.id }) { order ->
                        VendorOrderRow(
                            order = order,
                            isUpdating = order.id in updating,
                            onAdvance = { next -> vendorViewModel.updateOrderStatus(order.id, next) },
                            onCancel = {
                                vendorViewModel.updateOrderStatus(
                                    order.id,
                                    UpdateBulkOrderStatusRequest.CANCELLED
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Only special-cases the one status a vendor wouldn't recognise as raw enum
 * text — everything else already reads fine capitalised (Pending, Confirmed,
 * Ready...).
 */
private fun vendorOrderStatusLabel(status: String): String = when (status) {
    "cancellation_requested" -> "Cancellation requested — awaiting admin"
    else -> status.replaceFirstChar { it.uppercase() }
}

/**
 * Renders a [BulkOrder], not an Order — Bulk-orders.php is the only
 * per-vendor order endpoint this backend exposes.
 */
@Composable
private fun VendorOrderRow(
    order: BulkOrder,
    isUpdating: Boolean,
    onAdvance: (String) -> Unit,
    onCancel: () -> Unit
) {
    val next = UpdateBulkOrderStatusRequest.nextAfter(order.status)

    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CardWhite).padding(14.dp)
    ) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Order #${order.id}", fontWeight = FontWeight.SemiBold, color = Ink)
                Text(order.displayTitle, fontSize = 13.sp, color = Ink)
                Text(
                    buildString {
                        append(vendorOrderStatusLabel(order.status))
                        // quantityLabel, not quantity: bulk orders are decimal
                        // kilograms, so the raw value renders as "20.0 unit(s)".
                        append("  •  ${order.quantityLabel} unit(s)")
                        order.customerName.takeIf { it.isNotBlank() }?.let { append("  •  $it") }
                    },
                    fontSize = 12.sp,
                    color = InkMuted
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                // grandTotal, not totalPrice: the customer paid for delivery too,
                // and showing only the goods makes every order look short.
                Text(formatUgx(order.grandTotal), fontWeight = FontWeight.Bold, color = Forest)
                if (order.deliveryFee > 0) {
                    Text(
                        "incl. ${formatUgx(order.deliveryFee)} delivery",
                        fontSize = 10.sp,
                        color = InkMuted
                    )
                }
            }
        }

        // An unpaid order is a reservation, not work. The server refuses to
        // advance it, so saying so here saves the vendor a pointless tap — and
        // saves them from picking and packing an order that may be cancelled
        // out from under them when the reservation is released.
        if (order.isAwaitingPayment) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Not paid for yet. Nothing to do until it is — unpaid orders are " +
                    "released automatically after 30 minutes.",
                fontSize = 11.sp,
                color = InkMuted
            )
            return@Column
        }

        if (order.isTerminal) return@Column

        // Once a rider has been dispatched, completing the delivery is theirs.
        // They mark it delivered, and that one transaction credits the rider and
        // the vendor together. A vendor marking it delivered from here would
        // credit themselves while the rider is still carrying the load — and
        // the credit is idempotent, so it cannot be taken back.
        if (order.hasRider) {
            Spacer(Modifier.height(10.dp))
            Text(
                "A rider is delivering this. They will mark it delivered on arrival, " +
                    "and your earnings are credited then.",
                fontSize = 11.sp,
                color = InkMuted
            )
            return@Column
        }

        // A delivery order with nobody dispatched yet. The seller can still
        // move it through confirmed -> processing -> ready, but "delivered" is
        // not theirs to claim: no rider has taken the goods, so nothing has
        // been delivered to anybody. Marking it here would credit the seller
        // for an order still sitting in their store — and the credit is
        // idempotent, so it could not be taken back.
        //
        // Only a collection order (pickup_only) is ever completed by the
        // seller, because there is no rider in that flow at all.
        if (next == "delivered" && order.awaitingDispatch) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Ready for collection by a rider. An administrator assigns one, and " +
                    "the rider marks it delivered on arrival — your earnings are " +
                    "credited then.",
                fontSize = 11.sp,
                color = InkMuted
            )
            return@Column
        }

        if (order.sellerCompletesDelivery && next == "delivered") {
            Spacer(Modifier.height(10.dp))
            Text(
                "The customer collects this one themselves. Mark it delivered when " +
                    "you hand it over — that is what credits your earnings.",
                fontSize = 11.sp,
                color = InkMuted
            )
        }

        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isUpdating) {
                CircularProgressIndicator(
                    color = Forest,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(20.dp)
                )
            } else {
                if (next != null) {
                    // "Mark delivered" is the one that pays the vendor and
                    // cannot be undone, so it asks first. The earlier steps are
                    // reversible in practice and a confirm on each would just
                    // train the vendor to dismiss them.
                    var confirming by remember(order.id, order.status) { mutableStateOf(false) }

                    if (confirming) {
                        Text("Delivered?", fontSize = 12.sp, color = Ink)
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { confirming = false }) {
                            Text("No", fontSize = 12.sp, color = InkMuted)
                        }
                        Button(
                            onClick = { confirming = false; onAdvance(next) },
                            colors = ButtonDefaults.buttonColors(containerColor = Forest),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text("Yes", fontSize = 12.sp)
                        }
                    } else {
                        Button(
                            onClick = {
                                if (next == "delivered") confirming = true else onAdvance(next)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Forest),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text(
                                UpdateBulkOrderStatusRequest.actionLabel(next),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = onCancel) {
                            Text("Cancel order", fontSize = 12.sp, color = InkMuted)
                        }
                    }
                }
            }
        }

        if (order.pickupCode?.isNotBlank() == true && order.status == "ready") {
            Spacer(Modifier.height(8.dp))
            Text(
                "Collection code: ${order.pickupCode}",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Ink
            )
        }
    }
}
