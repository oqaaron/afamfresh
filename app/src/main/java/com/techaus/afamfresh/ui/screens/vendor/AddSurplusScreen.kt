package com.techaus.afamfresh.ui.screens.vendor

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techaus.afamfresh.models.SurplusListing
import com.techaus.afamfresh.ui.theme.*
import com.techaus.afamfresh.viewmodel.VendorViewModel

// ⚠️ INFERRED screen. Signature matches MainScreen.kt's usage in both
// composable("add_surplus") (no existingListing passed -> defaults null,
// create mode) and composable("edit_surplus/{listingId}") (existingListing
// passed -> edit mode). expiresAt is a free-text field here rather than a
// date picker — swap in a real DatePicker once you confirm the expected
// date format your surplus-listings.php expects.
@Composable
fun AddSurplusScreen(
    vendorViewModel: VendorViewModel,
    existingListing: SurplusListing? = null,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    val isEditing = existingListing != null
    val isLoading by vendorViewModel.isLoading.collectAsState()

    var title by remember(existingListing) { mutableStateOf(existingListing?.title ?: "") }
    var description by remember(existingListing) { mutableStateOf(existingListing?.description ?: "") }
    var originalPrice by remember(existingListing) { mutableStateOf(existingListing?.originalPrice?.toString() ?: "") }
    var price by remember(existingListing) { mutableStateOf(existingListing?.price?.toString() ?: "") }
    var quantity by remember(existingListing) { mutableStateOf(existingListing?.quantity?.toString() ?: "") }
    var unit by remember(existingListing) { mutableStateOf(existingListing?.unit ?: "kg") }
    var expiresAt by remember(existingListing) { mutableStateOf(existingListing?.expiresAt ?: "") }
    var formError by remember { mutableStateOf<String?>(null) }

    fun submit() {
        formError = null
        val originalPriceVal = originalPrice.toDoubleOrNull()
        val priceVal = price.toDoubleOrNull()
        val quantityVal = quantity.toDoubleOrNull()

        if (title.isBlank() || originalPriceVal == null || priceVal == null || quantityVal == null || expiresAt.isBlank()) {
            formError = "Please fill in all required fields with valid numbers"
            return
        }

        if (isEditing) {
            vendorViewModel.updateListing(
                id = existingListing!!.id,
                title = title,
                description = description.ifBlank { null },
                originalPrice = originalPriceVal,
                price = priceVal,
                quantity = quantityVal,
                unit = unit,
                expiresAt = expiresAt
            ) { success -> if (success) onSave() else formError = "Unable to save changes" }
        } else {
            vendorViewModel.createListing(
                SurplusListing(
                    title = title,
                    description = description.ifBlank { null },
                    originalPrice = originalPriceVal,
                    price = priceVal,
                    quantity = quantityVal,
                    unit = unit,
                    expiresAt = expiresAt
                )
            ) { success -> if (success) onSave() else formError = "Unable to create listing" }
        }
    }

    Scaffold(
        containerColor = Cream,
        topBar = {
            Row(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Ink)
                }
                Text(if (isEditing) "Edit Listing" else "New Surplus Listing", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
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
            OutlinedTextField(
                value = title, onValueChange = { title = it }, label = { Text("Title") },
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), shape = RoundedCornerShape(12.dp), singleLine = true
            )
            OutlinedTextField(
                value = description, onValueChange = { description = it }, label = { Text("Description (optional)") },
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), shape = RoundedCornerShape(12.dp), minLines = 2
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = originalPrice, onValueChange = { originalPrice = it }, label = { Text("Original price") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp), singleLine = true
                )
                OutlinedTextField(
                    value = price, onValueChange = { price = it }, label = { Text("Discounted price") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp), singleLine = true
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = quantity, onValueChange = { quantity = it }, label = { Text("Quantity") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp), singleLine = true
                )
                OutlinedTextField(
                    value = unit, onValueChange = { unit = it }, label = { Text("Unit (kg, pcs...)") },
                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp), singleLine = true
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = expiresAt, onValueChange = { expiresAt = it }, label = { Text("Expires at (YYYY-MM-DD)") },
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), shape = RoundedCornerShape(12.dp), singleLine = true
            )

            formError?.let {
                Text(it, color = Tomato, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
            }

            Button(
                onClick = { submit() },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Forest),
                enabled = !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White)
                } else {
                    Text(if (isEditing) "Save Changes" else "Create Listing", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }

            if (isEditing) {
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        vendorViewModel.deleteListing(existingListing!!.id) { success -> if (success) onSave() }
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Tomato),
                    enabled = !isLoading
                ) {
                    Text("Delete Listing", fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
