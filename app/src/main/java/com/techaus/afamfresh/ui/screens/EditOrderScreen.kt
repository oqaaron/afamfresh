package com.techaus.afamfresh.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techaus.afamfresh.ui.theme.*
import com.techaus.afamfresh.viewmodel.OrderViewModel

// ⚠️ INFERRED screen. Signature matches MainScreen.kt's
// composable("edit_order/{orderId}") call: EditOrderScreen(orderId, orderViewModel, onBack).
//
// Finds the order from OrderViewModel's already-loaded `orders` list (loaded by
// OrdersScreen just before navigating here) rather than re-fetching — if that
// list can be empty on deep-link/process-restart, you'll want OrderViewModel to
// expose a single getOrder(id) call instead; OrderRepository.getOrder already
// supports it, just not wired into a StateFlow here.
@Composable
fun EditOrderScreen(
    orderId: String,
    orderViewModel: OrderViewModel,
    onBack: () -> Unit
) {
    val orders by orderViewModel.orders.collectAsState()
    val isLoading by orderViewModel.isLoading.collectAsState()
    val order = remember(orders, orderId) { orders.find { it.id == orderId } }

    var address by remember(order) { mutableStateOf(order?.address ?: "") }
    var area by remember(order) { mutableStateOf(order?.area ?: "") }
    var mobile by remember(order) { mutableStateOf(order?.mobile ?: "") }
    var saveError by remember { mutableStateOf<String?>(null) }
    // There is no "delivery notes" field here any more: `orders` has no such
    // column and orders.php does not accept one, so anything typed into it was
    // discarded on save.

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
                Text("Edit Order", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
            }
        }
    ) { padding ->
        if (order == null) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Order not found — go back and try again", color = InkMuted)
            }
            return@Scaffold
        }

        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            // "pending"/"processing" are not statuses this backend uses — the
            // real vocabulary is "Received", "Awaiting Payment", "Preparing",
            // "Out for Delivery", ... so that check disabled editing on every
            // real order. Order.isEditable mirrors orders.php's own rule.
            val editable = order.isEditable

            if (!editable) {
                Text(
                    "This order is ${order.status} and can no longer be edited.",
                    color = Tomato,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }

            OutlinedTextField(
                value = mobile, onValueChange = { mobile = it }, label = { Text("Mobile") },
                enabled = editable, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                shape = RoundedCornerShape(12.dp)
            )
            OutlinedTextField(
                value = area, onValueChange = { area = it }, label = { Text("Area") },
                enabled = editable, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                shape = RoundedCornerShape(12.dp)
            )
            OutlinedTextField(
                value = address, onValueChange = { address = it }, label = { Text("Address") },
                enabled = editable, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                shape = RoundedCornerShape(12.dp)
            )
            saveError?.let {
                Text(it, color = Tomato, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
            }

            Spacer(modifier = Modifier.weight(1f))

            if (editable) {
                Button(
                    onClick = {
                        orderViewModel.updateOrder(
                            orderId = order.id,
                            address = address,
                            area = area,
                            mobile = mobile
                        ) { success, reason ->
                            if (success) onBack() else saveError = reason ?: "Unable to save changes"
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Forest),
                    enabled = !isLoading
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White)
                    } else {
                        Text("Save Changes", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedButton(
                    onClick = {
                        orderViewModel.cancelOrder(order.id) { success, reason ->
                            if (success) onBack() else saveError = reason ?: "Unable to cancel order"
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Tomato),
                    enabled = !isLoading
                ) {
                    Text("Cancel Order", fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}
