package com.techaus.afamfresh.ui.screens.vendor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
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
import com.techaus.afamfresh.models.CreateSurplusListingRequest
import com.techaus.afamfresh.models.SurplusListing
import com.techaus.afamfresh.ui.theme.*
import com.techaus.afamfresh.utils.formatUgx
import com.techaus.afamfresh.viewmodel.VendorViewModel

/**
 * Create or adjust a surplus listing.
 *
 * ✅ Rewritten against api/surplus-listings.php. The previous version collected
 * a free-text title, a discounted price, a decimal quantity and a "unit" — none
 * of which the endpoint accepts. What it actually requires is:
 *
 *   - product_id        a listing decorates an EXISTING catalogue item, so the
 *                       vendor picks one of their products rather than typing a
 *                       name; the server joins on `items` to render the listing
 *   - discount_percent  30-70 inclusive, rejected outside that range. The
 *                       server computes discounted_price itself
 *   - surplus_quantity  an integer
 *   - expiry_date       "YYYY-MM-DD HH:MM:SS"
 *
 * Edit mode is deliberately reduced to the remaining quantity: PUT on that
 * endpoint can only change status, remaining_quantity and admin_notes, so
 * offering price or expiry fields here would silently discard them.
 */
@Composable
fun AddSurplusScreen(
    vendorViewModel: VendorViewModel,
    existingListing: SurplusListing? = null,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    val isEditing = existingListing != null
    val isLoading by vendorViewModel.isLoading.collectAsState()
    val vendorProducts by vendorViewModel.vendorProducts.collectAsState()

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
    var surplusQuantity by remember(existingListing) {
        mutableStateOf(existingListing?.surplusQuantity?.takeIf { it > 0 }?.toString() ?: "")
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

    // ----- edit-mode state -----
    var remainingQuantity by remember(existingListing) {
        mutableStateOf(existingListing?.remainingQuantity?.toString() ?: "")
    }

    var formError by remember { mutableStateOf<String?>(null) }

    // The picker lists the vendor's own products; make sure they are loaded.
    // Keyed on the profile because the request needs the resolved vendor
    // identity, which start() supplies asynchronously.
    val profile by vendorViewModel.profile.collectAsState()
    LaunchedEffect(profile?.id) {
        if (profile != null && vendorProducts.isEmpty()) vendorViewModel.loadVendorProducts()
    }

    fun submitCreate() {
        formError = null

        val productId = selectedProductId
        val originalPriceVal = originalPrice.toDoubleOrNull()
        val discountVal = discountPercent.toDoubleOrNull()
        val quantityVal = surplusQuantity.toIntOrNull()

        if (productId == null) {
            formError = "Choose which product this surplus is for"
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
        if (discountVal !in CreateSurplusListingRequest.DISCOUNT_RANGE) {
            formError = "Discount must be between 30% and 70%"
            return
        }
        if (quantityVal == null || quantityVal <= 0) {
            formError = "Enter how many units are available"
            return
        }
        if (expiryDate.isBlank()) {
            formError = "Enter when this surplus expires"
            return
        }

        vendorViewModel.createListing(
            productId = productId,
            originalPrice = originalPriceVal,
            discountPercent = discountVal,
            surplusQuantity = quantityVal,
            expiryDate = expiryDate.trim(),
            listingType = listingType,
            description = description.trim(),
            conditionRating = conditionRating,
            pickupOnly = pickupOnly
        ) { success, reason ->
            if (success) onSave() else formError = reason ?: "Unable to create listing"
        }
    }

    fun submitEdit() {
        formError = null
        val remaining = remainingQuantity.toIntOrNull()
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
                    if (isEditing) "Update Listing" else "New Surplus Listing",
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
                    onValueChange = { remainingQuantity = it.filter { c -> c.isDigit() } },
                    label = { Text("Remaining quantity") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Number
                    ),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )
            } else {
                Text("Which product?", fontWeight = FontWeight.SemiBold, color = Ink)
                Spacer(modifier = Modifier.height(8.dp))

                if (vendorProducts.isEmpty()) {
                    Text(
                        "You have no products yet. Add products to your inventory " +
                            "before listing surplus.",
                        fontSize = 13.sp,
                        color = InkMuted,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        vendorProducts.forEach { vp ->
                            val selected = selectedProductId == vp.productId
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (selected) ForestSurface else CardWhite)
                                    .clickable {
                                        selectedProductId = vp.productId
                                        // Seed the normal price from the vendor's
                                        // own price so it rarely needs typing.
                                        vp.price?.let { p -> originalPrice = p.toInt().toString() }
                                    }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = selected, onClick = {
                                    selectedProductId = vp.productId
                                    vp.price?.let { p -> originalPrice = p.toInt().toString() }
                                })
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(vp.displayName, color = Ink, fontSize = 14.sp)
                                    Text(
                                        buildString {
                                            vp.price?.let { append(formatUgx(it)) }
                                            append("  •  ${vp.stockQuantity} in stock")
                                        },
                                        color = InkMuted,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

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
                        supportingText = { Text("30-70", fontSize = 11.sp) },
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
                    value = surplusQuantity,
                    onValueChange = { surplusQuantity = it.filter { c -> c.isDigit() } },
                    label = { Text("Quantity available") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Number
                    ),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )

                OutlinedTextField(
                    value = expiryDate,
                    onValueChange = { expiryDate = it },
                    label = { Text("Expires") },
                    placeholder = { Text("2026-08-04 18:00:00") },
                    // The column is DATETIME and the server inserts the string
                    // verbatim, so the time part matters.
                    supportingText = { Text("YYYY-MM-DD HH:MM:SS", fontSize = 11.sp) },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )

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
