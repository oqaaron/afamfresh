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
import androidx.compose.material.icons.filled.Place
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
import com.techaus.afamfresh.models.CreateBulkOrderRequest
import com.techaus.afamfresh.models.PaymentRequest
import com.techaus.afamfresh.models.BulkListing
import com.techaus.afamfresh.models.BulkQuoteResponse
import com.techaus.afamfresh.api.ApiService
import com.techaus.afamfresh.ui.components.NetworkImage
import com.techaus.afamfresh.ui.theme.*
import com.techaus.afamfresh.utils.formatUgx
import com.techaus.afamfresh.viewmodel.PaymentViewModel
import com.techaus.afamfresh.viewmodel.BulkViewModel
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Buying a Bulk listing.
 *
 * WHY THIS IS NOT THE ORDINARY CHECKOUT
 *
 * Bulk is a bulk channel with rules the shop does not have, all enforced by
 * api/Bulk-orders.php: a minimum order value of UGX 250,000, a minimum of
 * 20 kg on weight-based listings, and a 1000 kg ceiling. It also has no cart —
 * one listing from one vendor is one order, because the goods are perishable
 * Bulk held by that vendor and cannot be pooled.
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
 * See releaseStaleBulkReservations() in includes/Bulk_payment.php.
 *
 * THE TOTAL IS THE SERVER'S, NOT THE APP'S
 *
 * The delivery fee mixes a base charge, a per-kg rate, a per-km rate, a service
 * fee and percentages of the goods value -- all from admin-editable tables. The
 * app cannot compute any of that, so it asks: api/Bulk-quote.php runs the
 * identical function order creation will, and the figure shown here is the
 * figure charged.
 *
 * Distance needs a pinned location. Without one the fee is real but incomplete,
 * and the screen says so rather than implying the total is final.
 */
@Composable
fun BulkCheckoutScreen(
    listing: BulkListing?,
    userId: Int?,
    userEmail: String?,
    userPhone: String?,
    defaultAddress: Address?,
    // Set once the customer pins a delivery point on the map. Without it the
    // server cannot measure a distance, and that component is simply absent
    // from the fee rather than guessed.
    pinnedLat: Double?,
    pinnedLng: Double?,
    pinnedAddress: String?,
    BulkViewModel: BulkViewModel,
    paymentViewModel: PaymentViewModel,
    onBack: () -> Unit,
    onPickLocation: () -> Unit,
    onPaymentRedirect: (paymentUrl: String, transactionId: String) -> Unit,
    onOrderPlacedUnpaid: () -> Unit,
    availableLoyaltyPoints: Int = 0
) {
    if (listing == null) {
        // Reached by id from a list that has since been reloaded, or a deep
        // link. Nothing to buy, so say so rather than showing an empty form.
        MissingListing(onBack)
        return
    }

    val quote by BulkViewModel.quote.collectAsState()
    val quoteLoading by BulkViewModel.quoteLoading.collectAsState()
    val quoteError by BulkViewModel.quoteError.collectAsState()
    val isPlacing by BulkViewModel.isPlacingOrder.collectAsState()
    val orderError by BulkViewModel.orderError.collectAsState()
    val paymentError by paymentViewModel.error.collectAsState()
    val isStartingPayment by paymentViewModel.isLoading.collectAsState()

    // Start at whatever the server will accept, so the customer is not greeted
    // by an error on a quantity the screen itself chose.
    val minQuantity = remember(listing.id) {
        val byWeight = if (listing.isWeightBased) {
            CreateBulkOrderRequest.MIN_WEIGHT_BASED_QUANTITY
        } else 1.0
        val byValue = if (listing.discountedPrice > 0) {
            kotlin.math.ceil(CreateBulkOrderRequest.MIN_ORDER_VALUE / listing.discountedPrice)
        } else 1.0
        max(byWeight, byValue)
    }

    var quantity by remember(listing.id) {
        mutableStateOf(minQuantity.coerceAtMost(listing.remainingQuantity.toDouble()))
    }
    var address by remember(pinnedAddress) {
        mutableStateOf(pinnedAddress ?: defaultAddress?.addressLine.orEmpty())
    }
    var area by remember { mutableStateOf(defaultAddress?.area.orEmpty()) }
    var notes by remember { mutableStateOf("") }
    var payWithCash by remember { mutableStateOf(false) }
    var pointsToRedeem by remember { mutableStateOf(0) }

    val unit = listing.unit?.takeIf { it.isNotBlank() } ?: if (listing.isWeightBased) "kg" else "units"
    val goodsTotal = quantity * listing.discountedPrice
    val totalWeight = quantity * listing.weightPerUnitKg

    val loyaltyPreview by BulkViewModel.loyaltyPreview.collectAsState()
    val loyaltyDiscount = loyaltyPreview?.discount ?: 0.0

    LaunchedEffect(pointsToRedeem, goodsTotal) {
        BulkViewModel.quoteLoyaltyPoints(pointsToRedeem, goodsTotal)
    }

    // The same three limits the server enforces, checked here so the customer
    // learns before filling in an address rather than after submitting.
    val blockingReason: String? = when {
        listing.isSoldOut -> "This listing is sold out."
        quantity > listing.remainingQuantity ->
            "Only ${listing.remainingQuantity} $unit left."
        listing.isWeightBased && quantity < CreateBulkOrderRequest.MIN_WEIGHT_BASED_QUANTITY ->
            "Bulk listings start at ${CreateBulkOrderRequest.MIN_WEIGHT_BASED_QUANTITY.roundToInt()} kg."
        goodsTotal < CreateBulkOrderRequest.MIN_ORDER_VALUE ->
            "Bulk orders start at ${formatUgx(CreateBulkOrderRequest.MIN_ORDER_VALUE)}. " +
                "Add more to reach it."
        totalWeight > CreateBulkOrderRequest.MAX_WEIGHT_KG ->
            "That is ${totalWeight.roundToInt()} kg. The most we can move in one order is " +
                "${CreateBulkOrderRequest.MAX_WEIGHT_KG.roundToInt()} kg."
        !listing.pickupOnly && address.isBlank() ->
            "Add a delivery address, or nobody knows where this is going."
        userId == null -> "Sign in to place this order."
        else -> null
    }

    // Re-priced whenever anything that feeds the fee changes. Debounced, because
    // the quantity field fires on every keystroke and each one would otherwise
    // be a round trip.
    LaunchedEffect(listing.id, quantity, pinnedLat, pinnedLng) {
        if (blockingReason == null || blockingReason.startsWith("Add a delivery")) {
            kotlinx.coroutines.delay(400)
            BulkViewModel.requestQuote(listing.id, quantity, pinnedLat, pinnedLng)
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
                        CreateBulkOrderRequest.MIN_WEIGHT_BASED_QUANTITY
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
                    // Pinning is what makes the distance leg of the fee
                    // computable. Typed text cannot be measured from, so
                    // without a pin the quote is missing that component and
                    // says so rather than quietly under-charging.
                    OutlinedButton(
                        onClick = onPickLocation,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Place, contentDescription = null, tint = Forest)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (pinnedLat != null) "Change location on map" else "Pin the delivery point",
                            color = Forest
                        )
                    }
                    if (pinnedLat == null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Delivery is charged partly by distance. Pin the point and we can " +
                                "show you the full price before you order.",
                            fontSize = 11.sp,
                            color = InkMuted
                        )
                    }
                    Spacer(Modifier.height(12.dp))
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

            if (availableLoyaltyPoints > 0) {
                SectionCard(title = "Loyalty points") {
                    Text(
                        "You have $availableLoyaltyPoints points available.",
                        fontSize = 13.sp,
                        color = InkMuted
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { pointsToRedeem = (pointsToRedeem - 50).coerceAtLeast(0) },
                            enabled = pointsToRedeem > 0
                        ) { Text("−", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Forest) }

                        Text(
                            "$pointsToRedeem points",
                            modifier = Modifier.weight(1f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            fontWeight = FontWeight.Bold,
                            color = Ink
                        )

                        IconButton(
                            onClick = {
                                pointsToRedeem = (pointsToRedeem + 50).coerceAtMost(availableLoyaltyPoints)
                            },
                            enabled = pointsToRedeem < availableLoyaltyPoints
                        ) { Text("+", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Forest) }
                    }
                    if (loyaltyPreview?.capped == true) {
                        Spacer(Modifier.height(6.dp))
                        Text("Capped to what this order can apply.", fontSize = 11.sp, color = InkMuted)
                    }
                }
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
                quote = quote,
                loading = quoteLoading,
                pickupOnly = listing.pickupOnly,
                hasPin = pinnedLat != null,
                loyaltyPointsApplied = loyaltyPreview?.pointsApplied ?: 0,
                loyaltyDiscount = loyaltyDiscount
            )

            quoteError?.let { e ->
                Text(e, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }

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
                    BulkViewModel.clearOrderError()
                    paymentViewModel.clearError()

                    val request = CreateBulkOrderRequest(
                        listingId = listing.id,
                        userId = userId ?: return@Button,
                        quantity = quantity,
                        deliveryAddress = address.takeIf { it.isNotBlank() },
                        deliveryArea = area.takeIf { it.isNotBlank() },
                        // The pin the customer just dropped, falling back to a
                        // saved address that was itself pinned. A typed address
                        // has no coordinates, and inventing them would produce a
                        // charge for a journey that was never measured.
                        deliveryLat = pinnedLat ?: defaultAddress?.lat,
                        deliveryLng = pinnedLng ?: defaultAddress?.lng,
                        orderNotes = notes.takeIf { it.isNotBlank() },
                        pointsRedeem = loyaltyPreview?.pointsApplied?.takeIf { it > 0 }
                    )

                    BulkViewModel.placeOrder(request) { orderId, _ ->
                        // Paid for as a Bulk order, not a shop order: the two
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
                            orderType = ApiService.ORDER_TYPE_Bulk,
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
                "We only deliver within Greater Kampala — Kampala, Wakiso, Mpigi and Mukono.",
                fontSize = 11.sp,
                color = InkMuted
            )
        }
    }
}

@Composable
private fun ListingSummary(listing: BulkListing, unit: String) {
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

/**
 * The price, itemised.
 *
 * Every line is the server's figure. Showing the components rather than one
 * delivery number is the point: a customer told "delivery: 47,000" on a bag of
 * matooke assumes they are being fleeced, whereas the same figure split into
 * distance, weight, service and insurance is a bill they can check.
 */
@Composable
private fun TotalsCard(
    goodsTotal: Double,
    quote: BulkQuoteResponse?,
    loading: Boolean,
    pickupOnly: Boolean,
    hasPin: Boolean,
    loyaltyPointsApplied: Int = 0,
    loyaltyDiscount: Double = 0.0
) {
    val b = quote?.breakdown

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardWhite)
            .padding(16.dp)
    ) {
        FeeLine("Goods", formatUgx(quote?.goodsTotal ?: goodsTotal), bold = false)

        if (pickupOnly) {
            Spacer(Modifier.height(6.dp))
            FeeLine("Delivery", "Collection", bold = false)
        } else if (b != null) {
            // Zero lines are omitted rather than shown as "UGX 0". A waived
            // carriage charge is explained by `reason` below; a list of zeroes
            // just makes the bill harder to read.
            if (b.baseFee > 0) { Spacer(Modifier.height(6.dp)); FeeLine("Base delivery", formatUgx(b.baseFee)) }
            if (b.weightFee > 0) {
                Spacer(Modifier.height(6.dp))
                FeeLine("Weight (${b.weightKg.roundToInt()} kg)", formatUgx(b.weightFee))
            }
            if (b.distanceFee > 0) {
                Spacer(Modifier.height(6.dp))
                // "by road" because it is: the server routes the journey rather
                // than measuring the straight line, and the difference on a
                // Kampala trip is 20-40% of the charge.
                val km = b.distanceKm?.let { " (${String.format("%.1f", it)} km by road)" } ?: ""
                FeeLine("Distance$km", formatUgx(b.distanceFee))
            }
            if (b.serviceFee > 0) { Spacer(Modifier.height(6.dp)); FeeLine("Service fee", formatUgx(b.serviceFee)) }
            if (b.insuranceFee > 0) {
                Spacer(Modifier.height(6.dp))
                FeeLine("Insurance (${trimPercent(b.insurancePercent)}%)", formatUgx(b.insuranceFee))
            }
            if (b.processingFee > 0) {
                Spacer(Modifier.height(6.dp))
                FeeLine("Processing (${trimPercent(b.processingPercent)}%)", formatUgx(b.processingFee))
            }
        } else {
            Spacer(Modifier.height(6.dp))
            FeeLine("Delivery", if (loading) "Working it out…" else "—", bold = false)
        }

        if (loyaltyDiscount > 0) {
            Spacer(Modifier.height(6.dp))
            FeeLine("Loyalty points ($loyaltyPointsApplied)", "-${formatUgx(loyaltyDiscount)}", bold = false)
        }

        Spacer(Modifier.height(10.dp))
        HorizontalDivider()
        Spacer(Modifier.height(10.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                if (quote != null) "Total to pay" else "Goods only",
                fontWeight = FontWeight.Bold,
                color = Ink
            )
            Text(
                formatUgx((quote?.grandTotal ?: goodsTotal) - loyaltyDiscount),
                fontWeight = FontWeight.Bold,
                color = Forest
            )
        }

        // The server's own explanation. It names the per-km and per-kg rates,
        // says when carriage was waived, and admits when the distance was
        // measured from the depot because the vendor has not pinned their
        // premises — which is a real charge computed from an approximation and
        // should never be presented as exact.
        // Rough driving time, when the router supplied one. Framed as "about"
        // and never as a promise — it is traffic-unaware by design, so that the
        // same order quoted twice cannot produce two different prices.
        b?.durationMinutes?.takeIf { it > 0 }?.let { mins ->
            Spacer(Modifier.height(6.dp))
            Text("About $mins min drive from the vendor", fontSize = 11.sp, color = InkMuted)
        }

        b?.reason?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, fontSize = 11.sp, color = InkMuted)
        }

        if (!pickupOnly && !hasPin) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Pin the delivery point to include distance — the total will go up.",
                fontSize = 11.sp,
                color = InkMuted
            )
        }
    }
}

@Composable
private fun FeeLine(label: String, value: String, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = InkMuted, fontSize = 13.sp)
        Text(
            value,
            color = Ink,
            fontSize = 13.sp,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

/** "0.9%" not "0.90%", and "1.8%" not "1.80%". */
private fun trimPercent(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else String.format("%.1f", value)

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
                "Bulk sells fast and listings expire. Have another look at what is up now.",
                color = InkMuted,
                fontSize = 14.sp
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onBack,
                colors = ButtonDefaults.buttonColors(containerColor = Forest)
            ) {
                Text("Back to Bulk")
            }
        }
    }
}

/** Whole numbers without a trailing ".0", decimals kept to one place. */
private fun formatQuantity(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else String.format("%.1f", value)