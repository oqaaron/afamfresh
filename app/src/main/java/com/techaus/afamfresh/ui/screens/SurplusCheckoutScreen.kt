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
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.techaus.afamfresh.utils.PaymentPolicy
import com.techaus.afamfresh.utils.formatQuantity
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
 * api/api/Bulk-orders.php: a minimum order value, a minimum weight on
 * weight-based listings, and a ceiling on what one order can weigh. Those are
 * admin-editable settings rather than fixed numbers, and a WHOLESALE listing
 * is governed instead by the minimum its seller set — so this screen does not
 * state any of them itself. It asks api/api/Bulk-quote.php, which answers with
 * the same function the order endpoint refuses with.
 *
 * It also has no cart — one listing from one vendor is one order, because the
 * goods are held by that vendor and cannot be pooled.
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

    // A STARTING quantity, not a rule.
    //
    // The real limits live in Bulk_delivery_settings and differ per listing
    // kind, so only the server knows them — see BulkQuoteResponse.limits, which
    // takes over as soon as the first quote lands. This just picks somewhere
    // sensible to begin so the customer is not greeted by an error on a
    // quantity the screen itself chose.
    //
    // A wholesale listing carries its own minimum, which IS known up front.
    val seedQuantity = remember(listing.id) {
        when {
            listing.minOrderQuantity > 0.0 -> listing.minOrderQuantity
            listing.isWeightBased -> CreateBulkOrderRequest.SEED_MIN_WEIGHT_BASED_QUANTITY
            else -> 1.0
        }
    }

    // rememberSaveable: pinning a delivery point navigates to
    // Bulk_delivery_map, taking this screen out of composition. Plain remember
    // threw away the quantity, area and notes the customer had already entered.
    // The existing keys are kept — a new listing or a new pin SHOULD reset the
    // field they feed.
    var quantity by rememberSaveable(listing.id) {
        mutableStateOf(seedQuantity.coerceAtMost(listing.remainingQuantity))
    }
    var address by rememberSaveable(pinnedAddress) {
        mutableStateOf(pinnedAddress ?: defaultAddress?.addressLine.orEmpty())
    }

    // Keyed on the pin, exactly like `address` above.
    //
    // Without the key this was set once from the customer's OWN default address
    // and never moved again, so pinning a location filled in the address line
    // and silently left the area saying wherever the buyer usually shops. For
    // a diaspora customer sending to a relative that is not a cosmetic
    // mismatch: delivery_area is what the rider is shown and what the order is
    // filed under.
    //
    // Seeded from the pinned address's leading component — reverse geocoding
    // returns "Bukoto, Kampala, Uganda" and the first part is the area far more
    // often than not. It stays editable, and a wrong guess the customer can see
    // and correct beats a stale value they cannot.
    var area by rememberSaveable(pinnedAddress) {
        mutableStateOf(
            if (pinnedAddress != null) {
                pinnedAddress.substringBefore(',').trim()
            } else {
                defaultAddress?.area.orEmpty()
            }
        )
    }

    // Who receives it. Defaults to the buyer, because that is the common case,
    // but both fields are editable — see the "Who is receiving this" card.
    var recipientName by rememberSaveable {
        mutableStateOf(defaultAddress?.recipientName.orEmpty())
    }
    var recipientPhone by rememberSaveable {
        mutableStateOf(defaultAddress?.phone ?: userPhone.orEmpty())
    }
    var notes by rememberSaveable { mutableStateOf("") }
    var payWithCash by rememberSaveable { mutableStateOf(false) }
    var pointsToRedeem by rememberSaveable { mutableStateOf(0) }

    val unit = listing.unit?.takeIf { it.isNotBlank() } ?: if (listing.isWeightBased) "kg" else "units"
    val goodsTotal = quantity * listing.discountedPrice
    val totalWeight = quantity * listing.weightPerUnitKg

    val loyaltyPreview by BulkViewModel.loyaltyPreview.collectAsState()
    val loyaltyDiscount = loyaltyPreview?.discount ?: 0.0
    val totalAfterDiscount = (quote?.grandTotal ?: (goodsTotal + (quote?.deliveryFee ?: 0.0))) - loyaltyDiscount

    LaunchedEffect(pointsToRedeem, goodsTotal) {
        BulkViewModel.quoteLoyaltyPoints(pointsToRedeem, goodsTotal)
    }

    // The order limits are the SERVER's to state.
    //
    // This screen used to re-implement three of them from built-in constants:
    // a 250,000 minimum value, 20 kg on weight-based listings and a 1000 kg
    // ceiling. All three are now rows in Bulk_delivery_settings that an admin
    // edits, and none of them applies to a wholesale listing, where the
    // seller's own minimum governs instead. A local copy could only ever state
    // a rule that was true when the APK was built.
    //
    // api/api/Bulk-quote.php now answers this with the SAME function
    // api/api/Bulk-orders.php refuses with, so what the screen says and what
    // the order does cannot disagree. Only the two checks that need no round
    // trip are still made here.
    val blockingReason: String? = when {
        listing.isSoldOut -> "This listing is sold out."
        quantity > listing.remainingQuantity ->
            "Only ${formatQuantity(listing.remainingQuantity)} $unit left."
        // Blocks only when the server has actually said no. A quote that has
        // not arrived yet leaves this open rather than guessing: the order
        // endpoint is still the authority and refuses with the same wording.
        quote?.canOrder == false ->
            quote?.blockedReason ?: "That quantity cannot be ordered."
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
                    // stepping to a minimum one kilogram at a time is not a
                    // control, it is a punishment.
                    step = if (listing.isWeightBased) 5.0 else 1.0,
                    // From the server once it has told us, falling back to the
                    // seed until then. Never a built-in rule.
                    min = quote?.limits?.smallestQuantity ?: seedQuantity,
                    max = listing.remainingQuantity,
                    onChange = { quantity = it }
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "${formatQuantity(listing.remainingQuantity)} $unit available  •  about ${totalWeight.roundToInt()} kg",
                    fontSize = 12.sp,
                    color = InkMuted
                )

                // State the rules up front rather than only on refusal, and
                // state the ones that actually apply to THIS listing — the
                // server sends 0 for anything irrelevant, so nothing here
                // shows a wholesale buyer a surplus floor or the reverse.
                quote?.limits?.let { lim ->
                    val rules = buildList {
                        if (lim.minQuantity > 0.0) {
                            add("This seller's minimum order is ${formatQuantity(lim.minQuantity)} $unit.")
                        }
                        if (lim.minWeightKg > 0.0) {
                            add("Orders start at ${formatQuantity(lim.minWeightKg)} kg.")
                        }
                        if (lim.minOrderValue > 0.0) {
                            add("Orders start at ${formatUgx(lim.minOrderValue)}.")
                        }
                    }
                    if (rules.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            rules.joinToString(" "),
                            fontSize = 12.sp,
                            color = InkMuted
                        )
                    }
                }
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

            // Who is at the other end.
            //
            // Not an edge case for this product: AfamFresh sells to the
            // diaspora ordering for family in Uganda, so the person receiving
            // the goods is routinely not the person paying. Bulk_orders stored
            // nothing about them, and the rider was handed the buyer's own
            // name and phone — a foreign number they cannot call on arrival.
            if (!listing.pickupOnly) {
                SectionCard(title = "Who is receiving this") {
                    Text(
                        "The rider calls this number on arrival. Change it if the " +
                            "order is going to someone else.",
                        fontSize = 12.sp,
                        color = InkMuted
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = recipientName,
                        onValueChange = { recipientName = it },
                        label = { Text("Recipient's name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = recipientPhone,
                        onValueChange = { recipientPhone = it },
                        label = { Text("Recipient's phone") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = KeyboardType.Phone
                        ),
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

            val cashAllowed = PaymentPolicy.cashAllowedFor(totalAfterDiscount)

            SectionCard(title = "How you'll pay") {
                PaymentChoice(
                    label = "Mobile money or card",
                    detail = "Pay now through Pesapal.",
                    selected = !payWithCash,
                    onSelect = { payWithCash = false }
                )
                Spacer(Modifier.height(8.dp))
                PaymentChoice(
                    label = PaymentPolicy.cashOptionLabel(totalAfterDiscount),
                    detail = "Pay the full amount when the order arrives.",
                    selected = payWithCash,
                    enabled = cashAllowed,
                    onSelect = { if (cashAllowed) payWithCash = true }
                )
                if (!cashAllowed) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Cash payments are limited to UGX 50,000. Please select mobile money or card for this order.",
                        fontSize = 12.sp,
                        color = InkMuted
                    )
                }
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
                        // Blank means "the buyer" — the server falls back to
                        // the account holder rather than storing an empty
                        // string the rider would see instead of a number.
                        recipientName = recipientName.trim().takeIf { it.isNotBlank() },
                        recipientPhone = recipientPhone.trim().takeIf { it.isNotBlank() },
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
    enabled: Boolean = true,
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
        RadioButton(
            selected = selected,
            onClick = if (enabled) onSelect else null,
            enabled = enabled
        )
        Spacer(Modifier.width(6.dp))
        Column {
            Text(
                label,
                fontWeight = FontWeight.Medium,
                color = if (enabled) Ink else InkMuted,
                fontSize = 14.sp
            )
            Text(detail, fontSize = 12.sp, color = if (enabled) InkMuted else InkMuted.copy(alpha = 0.7f))
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