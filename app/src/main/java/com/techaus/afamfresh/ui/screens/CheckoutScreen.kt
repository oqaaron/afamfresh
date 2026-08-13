package com.techaus.afamfresh.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techaus.afamfresh.models.CartItem
import com.techaus.afamfresh.models.PaymentRequest
import com.techaus.afamfresh.ui.theme.*
import com.techaus.afamfresh.utils.formatUgx
import com.techaus.afamfresh.viewmodel.AddressViewModel
import com.techaus.afamfresh.viewmodel.CheckoutViewModel
import com.techaus.afamfresh.viewmodel.DeliveryResultViewModel
import com.techaus.afamfresh.viewmodel.PaymentViewModel

// ⚠️ INFERRED screen. Signature matches MainScreen.kt's composable("checkout") call
// exactly (param names/order/types). Payment method selector added since
// ApiService.createOrder already has a paymentMethod field defaulting to
// "mobile_money" — cash-on-delivery skips the Pesapal redirect and completes
// the order immediately via onOrderComplete.
@Composable
fun CheckoutScreen(
    cartItems: List<CartItem>,
    onBack: () -> Unit,
    checkoutViewModel: CheckoutViewModel,
    paymentViewModel: PaymentViewModel,
    onPaymentRedirect: (String, String) -> Unit,
    onOrderComplete: () -> Unit,
    onSelectLocation: () -> Unit,
    deliveryResultViewModel: DeliveryResultViewModel,
    addressViewModel: AddressViewModel,
    userEmail: String?,
    userPhone: String?
) {
    val deliveryResult by deliveryResultViewModel.deliveryResult.collectAsState()
    val savedAddresses by addressViewModel.addresses.collectAsState()
    val isPlacingOrder by checkoutViewModel.isLoading.collectAsState()
    val isPaying by paymentViewModel.isLoading.collectAsState()
    val checkoutError by checkoutViewModel.error.collectAsState()
    val paymentError by paymentViewModel.error.collectAsState()

    var fname by remember { mutableStateOf("") }
    var lname by remember { mutableStateOf("") }
    var mobile by remember(userPhone) { mutableStateOf(userPhone ?: "") }
    var email by remember(userEmail) { mutableStateOf(userEmail ?: "") }
    var area by remember { mutableStateOf("") }
    var address by remember(deliveryResult) { mutableStateOf(deliveryResult?.dropoffAddress ?: "") }
    var paymentMethod by remember { mutableStateOf("mobile_money") }

    /** Fills the form from a saved address so nothing has to be retyped. */
    fun applyAddress(saved: com.techaus.afamfresh.models.Address) {
        // The form asks for first/last separately but an address stores one
        // recipient name. Split on the first space and keep the remainder as
        // the surname, so "Mary Jane Okello" does not lose "Jane".
        val parts = saved.recipientName.trim().split(" ", limit = 2)
        fname = parts.getOrElse(0) { "" }
        lname = parts.getOrElse(1) { "" }
        if (saved.phone.isNotBlank()) mobile = saved.phone
        area = saved.area
        address = saved.addressLine
    }

    // Prefill from the default address the first time the screen opens. Guarded
    // so it never overwrites something the customer has already typed, and only
    // runs once the addresses have actually loaded.
    var hasPrefilled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(savedAddresses) {
        if (!hasPrefilled && savedAddresses.isNotEmpty() && address.isBlank()) {
            addressViewModel.defaultAddress()?.let {
                applyAddress(it)
                hasPrefilled = true
            }
        }
    }

    val subtotal = cartItems.sumOf { it.lineTotal }
    val deliveryCost = deliveryResult?.cost ?: 0.0
    val total = subtotal + deliveryCost
    val isBusy = isPlacingOrder || isPaying

    fun submitOrder() {
        checkoutViewModel.placeOrder(
            cartItems = cartItems,
            fname = fname,
            lname = lname,
            mobile = mobile,
            area = area,
            address = address,
            email = email,
            deliveryResult = deliveryResult
        ) { placed ->
            if (placed == null) return@placeOrder

            // Both branches go through initiatePayment now. Cash used to skip the
            // server entirely, which left payment_status at its default instead of
            // 'pending_cash' — so a cash order was indistinguishable from an
            // unpaid card order in the admin views.
            //
            // No amount is passed: the server reads the payable total from the
            // order row and ignores anything the client sends.
            paymentViewModel.initiatePayment(
                orderId = placed.orderId,
                paymentMethod = if (paymentMethod == "cash") {
                    PaymentRequest.METHOD_CASH
                } else {
                    PaymentRequest.METHOD_MOBILE_MONEY
                },
                email = email,
                phone = mobile,
                onCashAccepted = { onOrderComplete() },
                onRedirect = { paymentUrl, transactionId ->
                    onPaymentRedirect(paymentUrl, transactionId)
                }
            )
        }
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
                Text("Checkout", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
            }
        },
        bottomBar = {
            Surface(color = Cream, shadowElevation = 8.dp) {
                Column(modifier = Modifier.padding(20.dp)) {
                    (checkoutError ?: paymentError)?.let {
                        Text(it, color = Tomato, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
                    }
                    Button(
                        onClick = { submitOrder() },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Forest),
                        enabled = !isBusy && cartItems.isNotEmpty()
                    ) {
                        if (isBusy) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), color = Color.White)
                        } else {
                            Text(
                                if (paymentMethod == "cash") "PLACE ORDER" else "PROCEED TO PAY  •  ${formatUgx(total)}",
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            if (savedAddresses.isNotEmpty()) {
                SectionCard(title = "Saved addresses") {
                    Text(
                        "Tap one to fill in the form below.",
                        fontSize = 12.sp,
                        color = InkMuted,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    savedAddresses.forEach { saved ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(ForestSurface)
                                .clickable { applyAddress(saved) }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.LocationOn, contentDescription = null, tint = Forest)
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(saved.label, fontWeight = FontWeight.Bold, color = Ink, fontSize = 14.sp)
                                    if (saved.isDefault) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("• Default", color = Forest, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                Text(saved.summary, fontSize = 12.sp, color = InkMuted)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            SectionCard(title = "Delivery details") {
                CheckoutField(value = fname, onChange = { fname = it }, label = "First name")
                CheckoutField(value = lname, onChange = { lname = it }, label = "Last name")
                CheckoutField(value = mobile, onChange = { mobile = it }, label = "Mobile number")
                CheckoutField(value = email, onChange = { email = it }, label = "Email (optional)")
                CheckoutField(value = area, onChange = { area = it }, label = "Area / neighborhood")
                CheckoutField(value = address, onChange = { address = it }, label = "Delivery address")
            }

            Spacer(modifier = Modifier.height(16.dp))

            SectionCard(title = "Delivery location") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(ForestSurface)
                        .clickable { onSelectLocation() }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.LocationOn, contentDescription = null, tint = Forest)
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            deliveryResult?.dropoffAddress ?: "Select delivery location on map",
                            fontWeight = FontWeight.Medium,
                            color = Ink
                        )
                        deliveryResult?.let {
                            Text(
                                "${"%.1f".format(it.distanceKm)} km  •  ${formatUgx(it.cost)} delivery fee",
                                fontSize = 12.sp,
                                color = InkMuted
                            )
                        }
                    }
                    Text("Change", color = Forest, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            SectionCard(title = "Payment method") {
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

            Spacer(modifier = Modifier.height(16.dp))

            SectionCard(title = "Order summary") {
                cartItems.forEach { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("${item.product.name}  x${item.quantity}", fontSize = 14.sp, color = Ink)
                        Text(formatUgx(item.lineTotal), fontSize = 14.sp, color = Ink)
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = DividerGray)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Subtotal", color = InkMuted)
                    Text(formatUgx(subtotal), color = InkMuted)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Delivery", color = InkMuted)
                    // Showing "UGX 0" before a location is chosen would state a fee
                    // of zero, which is not what the customer will be charged.
                    Text(
                        deliveryResult?.let { formatUgx(it.cost) } ?: "Select location above",
                        color = InkMuted
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        if (deliveryResult == null) "Subtotal so far" else "Total",
                        fontWeight = FontWeight.Bold,
                        color = Ink
                    )
                    Text(formatUgx(total), fontWeight = FontWeight.Bold, color = Ink)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(CardWhite)
            .padding(16.dp)
    ) {
        Text(title, fontWeight = FontWeight.Bold, color = Ink, fontSize = 16.sp)
        Spacer(modifier = Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun CheckoutField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        shape = RoundedCornerShape(12.dp),
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = Cream,
            focusedContainerColor = Cream
        )
    )
}

/** Shared with PaymentRetryScreen, which offers the same choice on an existing order. */
@Composable
fun PaymentOptionRow(label: String, selected: Boolean, onSelect: () -> Unit) {
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
