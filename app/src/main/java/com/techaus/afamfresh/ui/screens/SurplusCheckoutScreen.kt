package com.techaus.afamfresh.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techaus.afamfresh.models.Address
import com.techaus.afamfresh.models.CreateSurplusOrderRequest
import com.techaus.afamfresh.models.PaymentRequest
import com.techaus.afamfresh.models.SurplusListing
import com.techaus.afamfresh.api.ApiService
import com.techaus.afamfresh.ui.components.NetworkImage
import com.techaus.afamfresh.ui.theme.*
import com.techaus.afamfresh.utils.formatUgx
import com.techaus.afamfresh.viewmodel.PaymentViewModel
import com.techaus.afamfresh.viewmodel.SurplusViewModel
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Buying a surplus listing.
 *
 * WHY THIS IS NOT THE ORDINARY CHECKOUT
 *
 * Surplus is a bulk channel with rules the shop does not have, all enforced by
 * api/surplus-orders.php: a minimum order value of UGX 250,000, a minimum of
 * 20 kg on weight-based listings, and a 1000 kg ceiling. It also has no cart —
 * one listing from one vendor is one order, because the goods are perishable
 * surplus held by that vendor and cannot be pooled.
 *
 * THE SHAPE OF THE FLOW
 *
 * Placing the order and paying for it are two separate steps, in that order.
 * The order has to exist before Pesapal can be given an amount to collect, and
 * the amount has to come from the server — the delivery fee is computed from
 * the order's weight and waived above a threshold, so the app genuinely does
 * not know the total until the order comes back.
 *
 * That means a customer can end up with an order they never paid for. This is
 * handled rather than prevented: an unpaid order is a reservation, and one left
 * for 30 minutes is cancelled server-side and its stock returned to the listing.
 * See releaseStaleSurplusReservations() in includes/surplus_payment.php.
 *
 * THE TOTALS SHOWN HERE ARE AN ESTIMATE, AND SAY SO
 *
 * Everything before the order is placed is computed client-side from the
 * listing's price. Delivery is not included because it cannot be known yet.
 * The screen never presents an estimate as the amount to be charged; the real
 * figure arrives with the order and is what goes to Pesapal.
 */
@Composable
fun SurplusCheckoutScreen(
    listing: SurplusListing?,
    userId: Int?,
    userEmail: String?,
    userPhone: String?,
    defaultAddress: Address?,
    surplusViewModel: SurplusViewModel,
    paymentViewModel: PaymentViewModel,
    onBack: () -> Unit,
    onPaymentRedirect: (paymentUrl: String, transactionId: String) -> Unit,
    onOrderPlacedUnpaid: () -> Unit
) {
    if (listing == null) {
        // Reached by id from a list that has since been reloaded, or a deep
        // link. Nothing to buy, so say so rather than showing an empty form.
        MissingListing(onBack)
        return
    }

    val isPlacing by surplusViewModel.isPlacingOrder.collectAsState()
    val orderError by surplusViewModel.orderError.collectAsState()
    val paymentError by paymentViewModel.error.collectAsState()
    val isStartingPayment by paymentViewModel.isLoading.collectAsState()

    // Start at whatever the server will accept, so the customer is not greeted
    // by an error on a quantity the screen itself chose.
    val minQuantity = remember(listing.id) {
        val byWeight = if (listing.isWeightBased) {
            CreateSurplusOrderRequest.MIN_WEIGHT_BASED_QUANTITY
        } else 1.0
        val byValue = if (listing.discountedPrice > 0) {
            kotlin.math.ceil(CreateSurplusOrderRequest.MIN_ORDER_VALUE / listing.discountedPrice)
        } else 1.0
        max(byWeight, byValue)
    }

    var quantity by remember(listing.id) {
        mutableStateOf(minQuantity.coerceAtMost(listing.remainingQuantity.toDouble()))
    }
    var address by remember { mutableStateOf(defaultAddress?.addressLine.orEmpty()) }
    var area by remember { mutableStateOf(defaultAddress?.area.orEmpty()) }
    var notes by remember { mutableStateOf("") }
    var payWithCash by remember { mutableStateOf(false) }

    val unit = listing.unit?.takeIf { it.isNotBlank() } ?: if (listing.isWeightBased) "kg" else "units"
    val goodsTotal = quantity * listing.discountedPrice
    val totalWeight = quantity * listing.weightPerUnitKg

    // The same three limits the server enforces, checked here so the customer
    // learns before filling in an address rather than after submitting.
    val blockingReason: String? = when {
        listing.isSoldOut -> "This listing is sold out."
        quantity > listing.remainingQuantity ->
            "Only ${listing.remainingQuantity} $unit left."
        listing.isWeightBased && quantity < CreateSurplusOrderRequest.MIN_WEIGHT_BASED_QUANTITY ->
            "Bulk listings start at ${CreateSurplusOrderRequest.MIN_WEIGHT_BASED_QUANTITY.roundToInt()} kg."
        goodsTotal < CreateSurplusOrderRequest.MIN_ORDER_VALUE ->
            "Surplus orders start at ${formatUgx(CreateSurplusOrderRequest.MIN_ORDER_VALUE)}. " +
                "Add more to reach it."
        totalWeight > CreateSurplusOrderRequest.MAX_WEIGHT_KG ->
            "That is ${totalWeight.roundToInt()} kg. The most we can move in one order is " +
                "${CreateSurplusOrderRequest.MAX_WEIGHT_KG.roundToInt()} kg."
        !listing.pickupOnly && address.isBlank() ->
            "Add a delivery address, or nobody knows where this is going."
        userId == null -> "Sign in to place this order."
        else -> null
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
                    Text("Checkout", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Text(listing.displayTitle, fontSize = 12.sp, color = InkMuted)
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
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ListingSummary(listing, unit)

            SectionCard(title = "How much") {
                QuantityStepper(
                    quantity = quantity,
                    unit = unit,
                    // One step is one unit for counted goods, but 5 kg for bulk:
                    // stepping from 20 kg to a 250,000-shilling minimum one
                    // kilogram at a time is not a control, it is a punishment.
                    step = if (listing.isWeightBased) 5.0 else 1.0,
                    min = if (listing.isWeightBased) {
                        CreateSurplusOrderRequest.MIN_WEIGHT_BASED_QUANTITY
                    } else 1.0,
                    max = listing.remainingQuantity.toDouble(),
                    onChange = { quantity = it }
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "${listing.remainingQuantity} $unit available  •  about ${totalWeight.roundToInt()} kg",
                    fontSize = 12.sp,
                    color = InkMuted
                )
            }

            if (listing.pickupOnly) {
                SectionCard(title = "Collection") {
                    Text(
                        "This vendor does not deliver. You will get a collection code once " +
                            "the order is paid for, and pick it up from " +
                            (listing.vendorLocation?.takeIf { it.isNotBlank() } ?: "the vendor") + ".",
                        fontSize = 13.sp,
                        color = InkMuted
                    )
                }
            } else {
                SectionCard(title = "Where to deliver") {
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text("Address or directions") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        minLines = 2
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = area,
                        onValueChange = { area = it },
                        label = { Text("Area") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }

            SectionCard(title = "Anything else") {
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Note for the vendor (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
            }

            SectionCard(title = "How you'll pay") {
                PaymentChoice(
                    label = "Mobile money or card",
                    detail = "Pay now through Pesapal.",
                    selected = !payWithCash,
                    onSelect = { payWithCash = false }
                )
                Spacer(Modifier.height(8.dp))
                PaymentChoice(
                    label = "Cash on delivery",
                    detail = "Pay the full amount when the order arrives.",
                    selected = payWithCash,
                    onSelect = { payWithCash = true }
                )
            }

            TotalsCard(
                goodsTotal = goodsTotal,
                pickupOnly = listing.pickupOnly
            )

            val message = orderError ?: paymentError
            if (message != null) {
                Text(
                    message,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (blockingReason != null) {
                Text(blockingReason, color = InkMuted, fontSize = 13.sp)
            }

            val busy = isPlacing || isStartingPayment
            Button(
                onClick = {
                    surplusViewModel.clearOrderError()
                    paymentViewModel.clearError()

                    val request = CreateSurplusOrderRequest(
                        listingId = listing.id,
                        userId = userId ?: return@Button,
                        quantity = quantity,
                        deliveryAddress = address.takeIf { it.isNotBlank() },
                        deliveryArea = area.takeIf { it.isNotBlank() },
                        // Present only if the address was pinned on the map. A
                        // typed address has none, and inventing coordinates
                        // would produce a wrong delivery quote.
                        deliveryLat = defaultAddress?.lat,
                        deliveryLng = defaultAddress?.lng,
                        orderNotes = notes.takeIf { it.isNotBlank() }
                    )

                    surplusViewModel.placeOrder(request) { orderId, _ ->
                        // Paid for as a surplus order, not a shop order: the two
                        // id spaces overlap, and without order_type the server
                        // would look up an unrelated row in `orders`.
                        paymentViewModel.initiatePayment(
                            orderId = orderId.toString(),
                            paymentMethod = if (payWithCash) {
                                PaymentRequest.METHOD_CASH
                            } else {
                                PaymentRequest.METHOD_MOBILE_MONEY
                            },
                            email = userEmail,
                            phone = userPhone,
                            orderType = ApiService.ORDER_TYPE_SURPLUS,
                            onCashAccepted = onOrderPlacedUnpaid,
                            onRedirect = onPaymentRedirect
                        )
                    }
                },
                enabled = blockingReason == null && !busy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Forest)
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    Text(
                        if (payWithCash) "Place order" else "Place order and pay",
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Text(
                "Delivery is added by the vendor based on weight and distance, so the " +
                    "amount you pay may be a little higher than the estimate above.",
                fontSize = 11.sp,
                color = InkMuted
            )
        }
    }
}

@Composable
private fun ListingSummary(listing: SurplusListing, unit: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardWhite)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NetworkImage(
            model = listing.image,
            contentDescription = listing.displayTitle,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(ForestSurface)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(listing.displayTitle, fontWeight = FontWeight.SemiBold, color = Ink)
            listing.vendorDisplayName?.let {
                Text(it, fontSize = 12.sp, color = InkMuted)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatUgx(listing.originalPrice),
                    fontSize = 12.sp,
                    color = InkMuted,
                    textDecoration = TextDecoration.LineThrough
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "${formatUgx(listing.discountedPrice)} / $unit",
                    fontWeight = FontWeight.Bold,
                    color = Forest
                )
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardWhite)
            .padding(16.dp)
    ) {
        Text(title, fontWeight = FontWeight.SemiBold, color = Ink, fontSize = 15.sp)
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun QuantityStepper(
    quantity: Double,
    unit: String,
    step: Double,
    min: Double,
    max: Double,
    onChange: (Double) -> Unit
) {
    var typed by remember(quantity) { mutableStateOf(formatQuantity(quantity)) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(
            onClick = { onChange((quantity - step).coerceAtLeast(min)) },
            enabled = quantity - step >= min
        ) {
            Icon(Icons.Default.Remove, contentDescription = "Less")
        }

        OutlinedTextField(
            value = typed,
            onValueChange = { raw ->
                typed = raw
                // Only commit a value that parses and is in range. Typing "2"
                // on the way to "200" must not be rejected mid-keystroke, so an
                // unparseable field simply leaves the committed quantity alone.
                raw.toDoubleOrNull()?.let { parsed ->
                    if (parsed in min..max) onChange(parsed)
                }
            },
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = KeyboardType.Decimal
            ),
            suffix = { Text(unit, fontSize = 12.sp, color = InkMuted) }
        )

        FilledTonalIconButton(
            onClick = { onChange((quantity + step).coerceAtMost(max)) },
            enabled = quantity + step <= max
        ) {
            Icon(Icons.Default.Add, contentDescription = "More")
        }
    }
}

@Composable
private fun PaymentChoice(
    label: String,
    detail: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) ForestSurface else Color.Transparent)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(6.dp))
        Column {
            Text(label, fontWeight = FontWeight.Medium, color = Ink, fontSize = 14.sp)
            Text(detail, fontSize = 12.sp, color = InkMuted)
        }
    }
}

@Composable
private fun TotalsCard(goodsTotal: Double, pickupOnly: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardWhite)
            .padding(16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Goods", color = InkMuted, fontSize = 14.sp)
            Text(formatUgx(goodsTotal), color = Ink, fontSize = 14.sp)
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Delivery", color = InkMuted, fontSize = 14.sp)
            Text(
                if (pickupOnly) "Collection" else "Added at checkout",
                color = InkMuted,
                fontSize = 14.sp
            )
        }
        Spacer(Modifier.height(10.dp))
        HorizontalDivider()
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Estimated total", fontWeight = FontWeight.Bold, color = Ink)
            Text(formatUgx(goodsTotal), fontWeight = FontWeight.Bold, color = Forest)
        }
    }
}

@Composable
private fun MissingListing(onBack: () -> Unit) {
    Scaffold(containerColor = Cream) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                "This deal is no longer available",
                fontWeight = FontWeight.Bold,
                color = Ink,
                fontSize = 18.sp
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Surplus sells fast and listings expire. Have another look at what is up now.",
                color = InkMuted,
                fontSize = 14.sp
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onBack,
                colors = ButtonDefaults.buttonColors(containerColor = Forest)
            ) {
                Text("Back to surplus")
            }
        }
    }
}

/** Whole numbers without a trailing ".0", decimals kept to one place. */
private fun formatQuantity(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else String.format("%.1f", value)