package com.techaus.afamfresh.ui.screens.vendor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import com.techaus.afamfresh.models.BulkListing
import com.techaus.afamfresh.ui.theme.*
import com.techaus.afamfresh.utils.decimalInput
import com.techaus.afamfresh.utils.formatQuantity
import com.techaus.afamfresh.utils.formatUgx
import com.techaus.afamfresh.viewmodel.VendorViewModel

/**
 * Create or adjust a wholesale listing.
 *
 * The wholesale counterpart to [AddBulkScreen]. Where that form asks for a
 * retail price, a discount off it, and a date the produce expires, this one
 * asks for the price outright and the minimum a buyer must take. Written
 * against the wholesaler branch of api/api/Bulk-listings.php, which requires:
 *
 *   - product_id           the listing decorates one of the seller's approved
 *                          products, exactly as surplus does
 *   - wholesale_price      stored in `discounted_price`; > 0
 *   - min_order_quantity   > 0, and not greater than Bulk_quantity
 *   - Bulk_quantity        decimal, since stock is sold by weight
 *
 * and treats `original_price` as an OPTIONAL retail reference. If given it
 * must exceed the wholesale price, because the server renders it as a saving.
 * `expiry_date` is not sent at all — wholesale stock is not near its end of
 * life, and the column is nullable as of the wholesale migration.
 *
 * `listing_type` is not this form's to choose. The server forces 'wholesale'
 * from the seller's `business_type` and rejects the value from anyone else, so
 * a wholesaler cannot file stock under a surplus heading or the reverse.
 *
 * Edit mode is reduced to the remaining quantity for the same reason as the
 * surplus form: PUT on that endpoint only changes status, remaining_quantity
 * and admin_notes, so offering a price field would silently discard it.
 */
@Composable
fun AddWholesaleListingScreen(
    vendorViewModel: VendorViewModel,
    existingListing: BulkListing? = null,
    onSave: () -> Unit,
    /** Route to the product screen, offered when there is nothing to list. */
    onAddInventory: () -> Unit,
    onCancel: () -> Unit
) {
    val isEditing = existingListing != null
    val isLoading by vendorViewModel.isLoading.collectAsState()
    val myProducts by vendorViewModel.myProducts.collectAsState()
    val vendorProducts = remember(myProducts) { myProducts.filter { it.isApproved } }

    // ----- create-mode form state -----
    var selectedProductId by remember(existingListing) {
        mutableStateOf(existingListing?.productId?.takeIf { it > 0 })
    }
    var description by remember(existingListing) {
        mutableStateOf(existingListing?.description ?: "")
    }
    var wholesalePrice by remember(existingListing) {
        mutableStateOf(
            existingListing?.discountedPrice?.takeIf { it > 0 }?.toInt()?.toString() ?: ""
        )
    }
    var retailReference by remember(existingListing) {
        mutableStateOf(existingListing?.originalPrice?.takeIf { it > 0 }?.toInt()?.toString() ?: "")
    }
    var availableQuantity by remember(existingListing) {
        mutableStateOf(
            existingListing?.BulkQuantity?.takeIf { it > 0 }?.let { formatQuantity(it) } ?: ""
        )
    }
    var minOrderQuantity by remember(existingListing) {
        mutableStateOf(
            existingListing?.minOrderQuantity?.takeIf { it > 0 }?.let { formatQuantity(it) } ?: ""
        )
    }
    var pickupOnly by remember(existingListing) {
        mutableStateOf(existingListing?.pickupOnly ?: false)
    }

    // Delivery is priced by weight, so a unit that is not a kilogram has to say
    // what one of them weighs. Same list the surplus form uses — see
    // ListingFormCommon.kt for why there is only one.
    var unitIndex by remember(existingListing) { mutableStateOf(0) }
    val unit = Bulk_UNITS[unitIndex]
    var weightPerUnit by remember(existingListing) { mutableStateOf("1") }

    // ----- edit-mode state -----
    var remainingQuantity by remember(existingListing) {
        mutableStateOf(existingListing?.remainingQuantity?.let { formatQuantity(it) } ?: "")
    }

    var formError by remember { mutableStateOf<String?>(null) }

    val profile by vendorViewModel.profile.collectAsState()
    LaunchedEffect(profile?.id) {
        if (profile != null && myProducts.isEmpty()) vendorViewModel.loadMyProducts()
    }

    fun submitCreate() {
        formError = null

        val productId = selectedProductId
        val priceVal = wholesalePrice.toDoubleOrNull()
        val quantityVal = availableQuantity.toDoubleOrNull()
        val minOrderVal = minOrderQuantity.toDoubleOrNull()
        // Blank is legitimate: the retail reference is optional. Only a
        // non-blank value that fails to parse is an error.
        val retailVal = retailReference.takeIf { it.isNotBlank() }?.toDoubleOrNull()

        if (productId == null) {
            formError = if (vendorProducts.isEmpty()) {
                "Add a product and get it approved first — there is nothing to list yet."
            } else {
                "Choose which product this listing is for"
            }
            return
        }
        if (priceVal == null || priceVal <= 0) {
            formError = "Enter your wholesale price"
            return
        }
        if (quantityVal == null || quantityVal <= 0) {
            formError = "Enter how many ${unit.plural} you have available"
            return
        }
        if (minOrderVal == null || minOrderVal <= 0) {
            formError = "Enter the minimum order"
            return
        }
        // Mirrors the server check, so the seller is told immediately rather
        // than after a round trip.
        if (minOrderVal > quantityVal) {
            formError = "The minimum order cannot be larger than the quantity available"
            return
        }
        if (retailReference.isNotBlank() && retailVal == null) {
            formError = "The retail price must be a number, or leave it blank"
            return
        }
        if (retailVal != null && retailVal <= priceVal) {
            formError = "The retail price must be higher than your wholesale price"
            return
        }

        vendorViewModel.createWholesaleListing(
            productId = productId,
            wholesalePrice = priceVal,
            minOrderQuantity = minOrderVal,
            BulkQuantity = quantityVal,
            retailReferencePrice = retailVal,
            description = description.trim(),
            pickupOnly = pickupOnly,
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
                    if (isEditing) "Update Listing" else "New Wholesale Listing",
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
                Text("Status: ${existingListing.status}", fontSize = 13.sp, color = InkMuted)
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "Only the remaining quantity can be changed after a listing is " +
                        "submitted. To change the price or the minimum order, cancel " +
                        "this listing and create a new one.",
                    fontSize = 12.sp,
                    color = InkMuted
                )
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = remainingQuantity,
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
                                "A wholesale listing has to point at one of your approved " +
                                    "products. Add what you sell and an administrator will " +
                                    "approve it, then you can list it here.",
                                fontSize = 13.sp,
                                color = InkMuted
                            )
                            Spacer(Modifier.height(12.dp))
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
                                        // Seeds the RETAIL reference, not the
                                        // wholesale price. The product's own
                                        // price is what it sells for normally,
                                        // which is the reference a wholesale
                                        // price sits below — seeding the
                                        // wholesale field with it would invite
                                        // the seller to list at retail.
                                        vp.price?.toDoubleOrNull()?.let { p ->
                                            retailReference = p.toInt().toString()
                                        }
                                    }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selected,
                                    onClick = {
                                        selectedProductId = vp.id
                                        vp.price?.toDoubleOrNull()?.let { p ->
                                            retailReference = p.toInt().toString()
                                        }
                                    }
                                )
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

                        TextButton(onClick = onAddInventory) {
                            Text(
                                "+ Add a product",
                                fontWeight = FontWeight.SemiBold,
                                color = Forest
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
                StepHeader(2, "How is it sold?")

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
                        onValueChange = {
                            weightPerUnit = it.filter { c -> c.isDigit() || c == '.' }
                        },
                        label = { Text("Approx. weight of one ${unit.label.lowercase()} (kg)") },
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
                StepHeader(3, "Your wholesale price")

                OutlinedTextField(
                    value = wholesalePrice,
                    onValueChange = { wholesalePrice = it.filter { c -> c.isDigit() } },
                    label = { Text("Price per ${unit.label.lowercase()}") },
                    supportingText = {
                        Text("What a buyer pays for one ${unit.label.lowercase()}", fontSize = 11.sp)
                    },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Number
                    ),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )

                OutlinedTextField(
                    value = retailReference,
                    onValueChange = { retailReference = it.filter { c -> c.isDigit() } },
                    label = { Text("Usual retail price (optional)") },
                    supportingText = {
                        Text(
                            "Shown to buyers as how much they save. Leave blank to " +
                                "quote your price only.",
                            fontSize = 11.sp
                        )
                    },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Number
                    ),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )

                // The saving the buyer will see, computed the same way the
                // server does. Shown only when it is genuinely a saving, which
                // is also the condition the server enforces.
                val savingPercent = remember(wholesalePrice, retailReference) {
                    val price = wholesalePrice.toDoubleOrNull()
                    val retail = retailReference.toDoubleOrNull()
                    if (price != null && retail != null && price > 0 && retail > price) {
                        ((retail - price) / retail * 100.0).toInt()
                    } else null
                }
                savingPercent?.let {
                    Text(
                        "Buyers see “save $it% vs retail”",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Forest,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                StepHeader(4, "Quantity and minimum order")

                OutlinedTextField(
                    value = availableQuantity,
                    onValueChange = { input -> availableQuantity = decimalInput(input) },
                    label = { Text("How many ${unit.plural} available") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Number
                    ),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )

                OutlinedTextField(
                    value = minOrderQuantity,
                    onValueChange = { input -> minOrderQuantity = decimalInput(input) },
                    label = { Text("Minimum order (${unit.plural})") },
                    supportingText = {
                        Text(
                            "The smallest quantity you will sell in one order",
                            fontSize = 11.sp
                        )
                    },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Number
                    ),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )

                // What one minimum order is worth, which is the number a
                // wholesaler is actually deciding about.
                val minOrderValue = remember(wholesalePrice, minOrderQuantity) {
                    val price = wholesalePrice.toDoubleOrNull()
                    val min = minOrderQuantity.toDoubleOrNull()
                    if (price != null && min != null && price > 0 && min > 0) price * min else null
                }
                minOrderValue?.let {
                    Text(
                        "A minimum order comes to ${formatUgx(it)}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Forest,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                StepHeader(5, "Details")

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description (optional)") },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    shape = RoundedCornerShape(12.dp),
                    minLines = 2
                )

                // No listing-type or condition picker. listing_type is forced
                // to 'wholesale' by the server, and a condition rating
                // describes how close surplus produce is to spoiling — neither
                // is the wholesaler's to set.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = pickupOnly, onCheckedChange = { pickupOnly = it })
                    Text("Pickup only (no delivery)", fontSize = 14.sp, color = Ink)
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            formError?.let {
                Text(
                    it,
                    color = Tomato,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
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
                            if (success) onSave()
                            else formError = reason ?: "Unable to cancel listing"
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
}
