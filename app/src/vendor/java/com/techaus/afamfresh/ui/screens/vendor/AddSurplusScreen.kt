package com.techaus.afamfresh.ui.screens.vendor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techaus.afamfresh.models.CreateBulkListingRequest
import com.techaus.afamfresh.models.BulkListing
import com.techaus.afamfresh.ui.theme.*
import com.techaus.afamfresh.utils.decimalInput
import com.techaus.afamfresh.utils.formatQuantity
import com.techaus.afamfresh.utils.formatUgx
import com.techaus.afamfresh.viewmodel.VendorViewModel

/**
 * Create or adjust a Bulk listing.
 *
 * ✅ Rewritten against api/Bulk-listings.php. The previous version collected
 * a free-text title, a discounted price, a decimal quantity and a "unit" — none
 * of which the endpoint accepts. What it actually requires is:
 *
 *   - product_id        a listing decorates an EXISTING catalogue item, so the
 *                       vendor picks one of their products rather than typing a
 *                       name; the server joins on `items` to render the listing
 *   - discount_percent  within CreateBulkListingRequest.DISCOUNT_RANGE
 *                       inclusive, rejected outside it. The server computes
 *                       discounted_price itself
 *   - Bulk_quantity  decimal, since produce is sold by weight
 *   - expiry_date       "YYYY-MM-DD HH:MM:SS"
 *
 * Edit mode is deliberately reduced to the remaining quantity: PUT on that
 * endpoint can only change status, remaining_quantity and admin_notes, so
 * offering price or expiry fields here would silently discard them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddBulkScreen(
    vendorViewModel: VendorViewModel,
    existingListing: BulkListing? = null,
    onSave: () -> Unit,
    /** Route to the inventory screen, offered when there is nothing to list. */
    onAddInventory: () -> Unit,
    onCancel: () -> Unit
) {
    val isEditing = existingListing != null
    val isLoading by vendorViewModel.isLoading.collectAsState()
    // Only the vendor's OWN approved products can carry a listing: the server
    // checks ownership and approval, so offering anything else here would just
    // produce a rejected request.
    val myProducts by vendorViewModel.myProducts.collectAsState()
    val vendorProducts = remember(myProducts) { myProducts.filter { it.isApproved } }

    // ----- create-mode form state -----
    var selectedProductId by remember(existingListing) {
        mutableStateOf(existingListing?.productId?.takeIf { it > 0 })
    }
    var description by remember(existingListing) {
        mutableStateOf(existingListing?.description ?: "")
    }
    var originalPrice by remember(existingListing) {
        mutableStateOf(existingListing?.originalPrice?.takeIf { it > 0 }?.toInt()?.toString() ?: "")
    }
    var discountPercent by remember(existingListing) {
        mutableStateOf(existingListing?.discountPercent?.takeIf { it > 0 }?.toInt()?.toString() ?: "")
    }
    var BulkQuantity by remember(existingListing) {
        mutableStateOf(existingListing?.BulkQuantity?.takeIf { it > 0 }?.let { formatQuantity(it) } ?: "")
    }
    var expiryDate by remember(existingListing) {
        mutableStateOf(existingListing?.expiryDate ?: "")
    }
    var listingType by remember(existingListing) {
        mutableStateOf(existingListing?.listingType ?: "goodie_bag")
    }
    var conditionRating by remember(existingListing) {
        mutableStateOf(existingListing?.conditionRating ?: "good")
    }
    var pickupOnly by remember(existingListing) {
        mutableStateOf(existingListing?.pickupOnly ?: false)
    }

    // How a unit is measured. The endpoint has always accepted
    // weight_per_unit_kg and is_weight_based -- the table, the API and the
    // request model all carry them -- but the form never asked, so every
    // listing went out as 1 kg per unit. Delivery is priced by weight, so a
    // sack of potatoes was quoted to the customer as if it weighed a kilo.
    var unitIndex by remember(existingListing) { mutableStateOf(0) }
    val unit = Bulk_UNITS[unitIndex]
    var weightPerUnit by remember(existingListing) { mutableStateOf("1") }

    var showDatePicker by remember { mutableStateOf(false) }

    // ----- edit-mode state -----
    var remainingQuantity by remember(existingListing) {
        mutableStateOf(existingListing?.remainingQuantity?.let { formatQuantity(it) } ?: "")
    }

    var formError by remember { mutableStateOf<String?>(null) }

    // The picker lists the vendor's own products; make sure they are loaded.
    // Keyed on the profile because the request needs the resolved vendor
    // identity, which start() supplies asynchronously.
    val profile by vendorViewModel.profile.collectAsState()
    LaunchedEffect(profile?.id) {
        if (profile != null && myProducts.isEmpty()) vendorViewModel.loadMyProducts()
    }

    fun submitCreate() {
        formError = null

        val productId = selectedProductId
        val originalPriceVal = originalPrice.toDoubleOrNull()
        val discountVal = discountPercent.toDoubleOrNull()
        val quantityVal = BulkQuantity.toDoubleOrNull()

        if (productId == null) {
            // Told apart deliberately. "Choose which product" is useless advice
            // when the picker above is empty -- it reads as a broken form
            // rather than a missing prerequisite.
            formError = if (vendorProducts.isEmpty()) {
                "Add a product and get it approved first — there is nothing to list yet."
            } else {
                "Choose which product this Bulk is for"
            }
            return
        }
        if (originalPriceVal == null || originalPriceVal <= 0) {
            formError = "Enter the normal selling price"
            return
        }
        if (discountVal == null) {
            formError = "Enter a discount percentage"
            return
        }
        // Checked here as well as server-side so the vendor is told immediately
        // rather than after a round trip.
        if (discountVal !in CreateBulkListingRequest.DISCOUNT_RANGE) {
            formError = "Discount must be between ${CreateBulkListingRequest.discountRangeLabel()}"
            return
        }
        if (quantityVal == null || quantityVal <= 0) {
            formError = "Enter how many units are available"
            return
        }
        if (expiryDate.isBlank()) {
            formError = "Enter when this Bulk expires"
            return
        }

        vendorViewModel.createListing(
            productId = productId,
            originalPrice = originalPriceVal,
            discountPercent = discountVal,
            BulkQuantity = quantityVal,
            expiryDate = expiryDate.trim(),
            listingType = listingType,
            description = description.trim(),
            conditionRating = conditionRating,
            pickupOnly = pickupOnly,
            // Kilograms are their own weight; anything else needs the vendor to
            // say what one of them weighs, or delivery is priced for 1 kg.
            weightPerUnitKg = if (unit.isWeight) 1.0
                              else (weightPerUnit.toDoubleOrNull() ?: unit.defaultKg),
            isWeightBased = unit.isWeight
        ) { success, reason ->
            if (success) onSave() else formError = reason ?: "Unable to create listing"
        }
    }

    fun submitEdit() {
        formError = null
        val remaining = remainingQuantity.toDoubleOrNull()
        if (remaining == null || remaining < 0) {
            formError = "Enter a valid remaining quantity"
            return
        }
        vendorViewModel.updateListingQuantity(existingListing!!.id, remaining) { success, reason ->
            if (success) onSave() else formError = reason ?: "Unable to save changes"
        }
    }

    Scaffold(
        containerColor = Cream,
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Ink)
                }
                Text(
                    if (isEditing) "Update Listing" else "New Bulk Listing",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ink
                )
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
            if (isEditing) {
                Text(
                    existingListing!!.displayTitle,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = Ink
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Status: ${existingListing.status}",
                    fontSize = 13.sp,
                    color = InkMuted
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "Only the remaining quantity can be changed after a listing is " +
                        "submitted. To change the price or expiry, cancel this listing " +
                        "and create a new one.",
                    fontSize = 12.sp,
                    color = InkMuted
                )
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = remainingQuantity,
                    // Digits AND a single decimal point: these are kilograms,
                    // and a digit-only filter made 20.5 impossible to type.
                    onValueChange = { input -> remainingQuantity = decimalInput(input) },
                    label = { Text("Remaining quantity") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Number
                    ),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )
            } else {
                StepHeader(1, "Which product?")

                if (vendorProducts.isEmpty()) {
                    // This is the state that reads as "the option is missing".
                    // A listing must reference a product already in the
                    // vendor's inventory -- the server joins product_id onto
                    // `items` -- and vendors cannot add inventory themselves,
                    // so the message has to say who can.
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF4E5)),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(
                                "No approved products yet",
                                fontWeight = FontWeight.SemiBold,
                                color = Ink,
                                fontSize = 14.sp
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "A Bulk listing has to point at one of your approved " +
                                    "products. Add what you sell and an administrator will " +
                                    "approve it, then you can list Bulk of it here.",
                                fontSize = 13.sp,
                                color = InkMuted
                            )
                            Spacer(Modifier.height(12.dp))
                            // Was a dead end: the card explained the problem and
                            // left the vendor to find the fix on another screen,
                            // so tapping Submit produced "choose which product"
                            // with nothing on the page to choose.
                            Button(
                                onClick = onAddInventory,
                                colors = ButtonDefaults.buttonColors(containerColor = Forest),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Add a product", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        vendorProducts.forEach { vp ->
                            val selected = selectedProductId == vp.id
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (selected) ForestSurface else CardWhite)
                                    .clickable {
                                        selectedProductId = vp.id
                                        // Seeded from the product's own price so
                                        // it rarely needs typing. `items.price`
                                        // is a varchar column, hence the parse.
                                        vp.price?.toDoubleOrNull()?.let { p ->
                                            originalPrice = p.toInt().toString()
                                        }
                                    }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = selected, onClick = {
                                    selectedProductId = vp.id
                                    vp.price?.toDoubleOrNull()?.let { p ->
                                        originalPrice = p.toInt().toString()
                                    }
                                })
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(vp.name, color = Ink, fontSize = 14.sp)
                                    Text(
                                        buildString {
                                            vp.price?.toDoubleOrNull()?.let { append(formatUgx(it)) }
                                            vp.category?.let { append("  •  ").append(it) }
                                        },
                                        color = InkMuted,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }

                        // Was only reachable when vendorProducts was empty,
                        // so it vanished the moment a vendor had one approved
                        // product -- there was then no way back to
                        // NewProductScreen from this screen at all.
                        TextButton(onClick = onAddInventory) {
                            Text("+ Add a product", fontWeight = FontWeight.SemiBold, color = Forest)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
                StepHeader(2, "How is it sold?")

                // Chips rather than a dropdown: six options is few enough to
                // show at once, and the choice changes the field below it.
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Bulk_UNITS.forEachIndexed { i, u ->
                        val selected = i == unitIndex
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(if (selected) Forest else PillGray)
                                .clickable {
                                    unitIndex = i
                                    weightPerUnit = u.defaultKg.toString().removeSuffix(".0")
                                }
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text(
                                u.label,
                                color = if (selected) Color.White else Ink,
                                fontSize = 13.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }

                if (!unit.isWeight) {
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = weightPerUnit,
                        onValueChange = { weightPerUnit = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Approx. weight of one ${unit.label.lowercase()} (kg)") },
                        // Delivery is charged by weight. Without this a sack is
                        // quoted to the customer as if it weighed one kilo.
                        supportingText = {
                            Text("Used to work out the delivery fee", fontSize = 11.sp)
                        },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = KeyboardType.Decimal
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))
                StepHeader(3, "Price and quantity")

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = originalPrice,
                        onValueChange = { originalPrice = it.filter { c -> c.isDigit() } },
                        label = { Text("Normal price") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = KeyboardType.Number
                        ),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = discountPercent,
                        onValueChange = { discountPercent = it.filter { c -> c.isDigit() } },
                        label = { Text("Discount %") },
                        // Derived, not typed: this hint is what the vendor
                        // actually reads, so a stale literal here would advertise
                        // a floor the app no longer enforces.
                        supportingText = {
                            Text(
                                "${CreateBulkListingRequest.DISCOUNT_RANGE.start.toInt()}-" +
                                    "${CreateBulkListingRequest.DISCOUNT_RANGE.endInclusive.toInt()}",
                                fontSize = 11.sp
                            )
                        },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = KeyboardType.Number
                        ),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true
                    )
                }

                // Show the resulting price, since the server derives it and the
                // vendor never types it directly.
                val previewPrice = remember(originalPrice, discountPercent) {
                    val base = originalPrice.toDoubleOrNull()
                    val pct = discountPercent.toDoubleOrNull()
                    if (base != null && pct != null && pct in 0.0..100.0) {
                        base * (1.0 - pct / 100.0)
                    } else null
                }
                previewPrice?.let {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "Customers pay ${formatUgx(it)}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Forest
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = BulkQuantity,
                    onValueChange = { input -> BulkQuantity = decimalInput(input) },
                    // Names the unit chosen above, so "12" is unambiguous.
                    label = { Text("How many ${unit.plural} available") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Number
                    ),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(8.dp))
                StepHeader(4, "When does it expire?")

                // Was a free-text box wanting "YYYY-MM-DD HH:MM:SS" typed by
                // hand. The column is DATETIME and the server inserts the
                // string verbatim, so one wrong character was a failed listing
                // — and nobody types a timestamp format correctly on a phone.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 10.dp)
                ) {
                    QuickDateChip("Today", 0) { expiryDate = endOfDayAfter(it) }
                    QuickDateChip("Tomorrow", 1) { expiryDate = endOfDayAfter(it) }
                    QuickDateChip("In 3 days", 3) { expiryDate = endOfDayAfter(it) }
                }

                OutlinedTextField(
                    value = if (expiryDate.isBlank()) "" else prettyExpiry(expiryDate),
                    onValueChange = { },
                    readOnly = true,
                    label = { Text("Expires") },
                    placeholder = { Text("Pick a date") },
                    trailingIcon = {
                        IconButton(onClick = { showDatePicker = true }) {
                            Icon(Icons.Default.DateRange, contentDescription = "Pick a date")
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .clickable { showDatePicker = true },
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(8.dp))
                StepHeader(5, "Details")

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description (optional)") },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    shape = RoundedCornerShape(12.dp),
                    minLines = 2
                )

                ChoiceRow(
                    label = "Listing type",
                    options = listOf("goodie_bag", "final_days", "bulk"),
                    selected = listingType,
                    onSelect = { listingType = it }
                )
                Spacer(modifier = Modifier.height(8.dp))
                ChoiceRow(
                    label = "Condition",
                    options = listOf("excellent", "good", "fair"),
                    selected = conditionRating,
                    onSelect = { conditionRating = it }
                )

                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = pickupOnly, onCheckedChange = { pickupOnly = it })
                    Text("Pickup only (no delivery)", fontSize = 14.sp, color = Ink)
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            formError?.let {
                Text(it, color = Tomato, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
            }

            Button(
                onClick = { if (isEditing) submitEdit() else submitCreate() },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Forest),
                enabled = !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White)
                } else {
                    Text(
                        if (isEditing) "Save Quantity" else "Submit for Approval",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (isEditing) {
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        vendorViewModel.deleteListing(existingListing!!.id) { success, reason ->
                            if (success) onSave() else formError = reason ?: "Unable to cancel listing"
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Tomato),
                    enabled = !isLoading
                ) {
                    Text("Cancel Listing", fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showDatePicker) {
        // Bounded to today onwards: Bulk that expired yesterday is not a
        // listing, and the server does no such check.
        val today = remember { System.currentTimeMillis() }
        val state = rememberDatePickerState(
            initialSelectedDateMillis = today,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) =
                    utcTimeMillis >= today - ONE_DAY_MS
            }
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { expiryDate = endOfDayFor(it) }
                    showDatePicker = false
                }) { Text("Set date") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = state)
        }
    }
}

// BulkUnit, Bulk_UNITS and StepHeader moved to ListingFormCommon.kt — the
// wholesale form needs the same units, and two copies of weight_per_unit_kg
// defaults would be a delivery-pricing bug waiting to happen.

private const val ONE_DAY_MS = 24L * 60 * 60 * 1000

/** "YYYY-MM-DD 23:59:59" — the format the DATETIME column is given verbatim. */
private fun endOfDayFor(millis: Long): String {
    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
    // The picker reports UTC midnight for the chosen day, so the date has to be
    // read in UTC as well — formatting it locally lands on the previous day for
    // anywhere behind UTC, and Kampala is ahead, which hides the bug here.
    fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
    return fmt.format(java.util.Date(millis)) + " 23:59:59"
}

private fun endOfDayAfter(daysFromNow: Int): String =
    endOfDayFor(System.currentTimeMillis() + daysFromNow * ONE_DAY_MS)

/** Shows the stored timestamp as something a person would read. */
private fun prettyExpiry(stored: String): String = stored.substringBefore(' ').ifBlank { stored }

@Composable
private fun QuickDateChip(label: String, days: Int, onPick: (Int) -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(ForestSurface)
            .clickable { onPick(days) }
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(label, color = Forest, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

/** Small inline segmented picker for the endpoint's enum columns. */
@Composable
private fun ChoiceRow(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit
) {
    Column {
        Text(label, fontSize = 13.sp, color = InkMuted)
        Spacer(modifier = Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                val isSelected = option == selected
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (isSelected) Forest else PillGray)
                        .clickable { onSelect(option) }
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                ) {
                    Text(
                        option.replace('_', ' '),
                        color = if (isSelected) Color.White else Ink,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}
