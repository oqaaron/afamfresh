package com.techaus.afamfresh.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
//
// RESCHEDULE SECTION: OrderViewModel.updateOrder() already accepts
// scheduledDeliveryDate/scheduledDeliverySlot — this screen previously never
// sent them, so they were dead parameters. Two known limitations, both from
// not having seen com.techaus.afamfresh.models.Order or OrderRepository.kt
// yet:
//   1. NOT PREFILLED. If this order already has a schedule, the toggle below
//      starts off regardless. Once Order exposes scheduledDeliveryDate /
//      scheduledDeliverySlot fields, initialise wantsSchedule/scheduledDate/
//      scheduledSlot from them the same way address/area/mobile already are.
//   2. SLOT LABELS ARE HARDCODED to the three currently seeded in
//      delivery_slots ("Morning (8AM-12PM)" etc — see api/schema.sql).
//      admin/configuration.php can add more slots; this list will silently
//      go stale if it does. Replace with a live call to get-slots.php once
//      OrderRepository exposes one, so this always reflects real
//      availability instead of a fixed list baked into the app.
@OptIn(ExperimentalMaterial3Api::class)
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

    // Blank/off means "leave the existing schedule untouched" — matches
    // OrderViewModel.updateOrder()'s params defaulting to null when omitted,
    // and orders.php's update action treating a null/absent pair as "don't
    // touch" rather than "clear". See limitation #1 above: this cannot yet
    // show what the order's schedule already is, only set a new one.
    var wantsSchedule by remember { mutableStateOf(false) }
    var scheduledDate by remember { mutableStateOf<String?>(null) }
    var scheduledSlot by remember { mutableStateOf<String?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }

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

            if (editable) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                ) {
                    Switch(
                        checked = wantsSchedule,
                        onCheckedChange = { checked ->
                            wantsSchedule = checked
                            if (!checked) {
                                scheduledDate = null
                                scheduledSlot = null
                            }
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = Forest)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Schedule this delivery", color = Ink, fontWeight = FontWeight.Medium)
                }

                if (wantsSchedule) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(ForestSurface)
                            .clickable { showDatePicker = true }
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            scheduledDate ?: "Choose a delivery date",
                            color = if (scheduledDate != null) Ink else InkMuted,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    DELIVERY_SLOT_LABELS.forEach { label ->
                        SlotOptionRow(
                            label = label,
                            selected = scheduledSlot == label,
                            onSelect = { scheduledSlot = label }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }

            saveError?.let {
                Text(it, color = Tomato, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
            }

            Spacer(modifier = Modifier.weight(1f))

            if (editable) {
                Button(
                    onClick = {
                        // The toggle being on with only a date or only a slot
                        // picked is treated as incomplete rather than silently
                        // dropping the half that was picked — orders.php's
                        // update action refuses a lone date or slot anyway
                        // (they must be provided together), so failing here is
                        // the same rule, just surfaced before the network call.
                        if (wantsSchedule && (scheduledDate == null || scheduledSlot == null)) {
                            saveError = "Pick both a date and a time slot, or turn scheduling off."
                            return@Button
                        }
                        orderViewModel.updateOrder(
                            orderId = order.id,
                            address = address,
                            area = area,
                            mobile = mobile,
                            scheduledDeliveryDate = if (wantsSchedule) scheduledDate else null,
                            scheduledDeliverySlot = if (wantsSchedule) scheduledSlot else null
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

    if (showDatePicker) {
        // Same today..+7-day window orders.php's update action and
        // get-slots.php both enforce server-side — picking a date outside
        // it here would just fail on save, so it is disabled instead.
        val today = remember { System.currentTimeMillis() }
        val maxDate = remember { today + 7L * 24 * 60 * 60 * 1000 }
        val state = rememberDatePickerState(
            initialSelectedDateMillis = today,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) =
                    utcTimeMillis in today..maxDate
            }
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { scheduledDate = isoDateFor(it) }
                    showDatePicker = false
                }) { Text("Choose") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = state)
        }
    }
}

/**
 * Hardcoded to delivery_slots' current seed data — see limitation #2 in the
 * file comment above.
 */
private val DELIVERY_SLOT_LABELS = listOf(
    "Morning (8AM-12PM)",
    "Afternoon (12PM-4PM)",
    "Evening (4PM-8PM)"
)

/** Same row shape as CheckoutScreen.kt's PaymentOptionRow — a private copy
 *  since that one is private to its own file. */
@Composable
private fun SlotOptionRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) ForestSurface else PillGray)
            .clickable { onSelect() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect, colors = RadioButtonDefaults.colors(selectedColor = Forest))
        Spacer(modifier = Modifier.width(6.dp))
        Text(label, color = Ink)
    }
}

/**
 * "YYYY-MM-DD" — the format orders.php's update action expects verbatim.
 * The picker reports UTC midnight for the chosen day, so the date has to be
 * read in UTC as well — formatting it locally lands on the wrong day
 * depending on time of day, same reasoning as AddSurplusScreen.kt's
 * endOfDayFor() for the identical picker.
 */
private fun isoDateFor(millis: Long): String {
    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
    fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
    return fmt.format(java.util.Date(millis))
}
