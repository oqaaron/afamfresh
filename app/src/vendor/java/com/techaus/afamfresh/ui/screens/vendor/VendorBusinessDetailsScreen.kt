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
import com.techaus.afamfresh.ui.theme.*
import com.techaus.afamfresh.viewmodel.VendorDetailsSaveState
import com.techaus.afamfresh.viewmodel.VendorViewModel

/**
 * The middle stage of vendor onboarding.
 *
 * Vendor onboarding is three steps, and this is the one that had no screen:
 *
 *   1. an admin approves the role request, which creates the `vendors` row
 *   2. the vendor fills in their real business details  <- here
 *   3. an admin verifies the record, and only then does
 *      api/surplus-listings.php accept a listing
 *
 * Step 1 can only infer what a user account holds, so it writes the person's
 * own name as the business name and leaves the phone blank. Until this form is
 * submitted, that placeholder is what customers would see.
 *
 * Deliberately does NOT claim the vendor is live on save. Filling this in does
 * not grant permission to sell — an admin verifying does — and a form that
 * implies otherwise produces a vendor who thinks they are trading and is not.
 */
@Composable
fun VendorBusinessDetailsScreen(
    vendorViewModel: VendorViewModel,
    onDone: () -> Unit,
    onBack: () -> Unit
) {
    val profile by vendorViewModel.profile.collectAsState()
    val saveState by vendorViewModel.detailsSaveState.collectAsState()

    // Seeded from the existing record so a vendor editing later sees what is
    // already stored rather than empty boxes. `key(profile?.id)` so the fields
    // populate once the profile arrives, not just on first composition.
    var businessName by remember(profile?.id) { mutableStateOf(profile?.businessName ?: "") }
    var phone by remember(profile?.id) { mutableStateOf(profile?.phone ?: "") }
    var location by remember(profile?.id) { mutableStateOf(profile?.location ?: "") }
    var marketStall by remember(profile?.id) { mutableStateOf(profile?.marketStall ?: "") }
    var businessType by remember(profile?.id) {
        mutableStateOf(profile?.businessType ?: "market_vendor")
    }
    var typeMenuOpen by remember { mutableStateOf(false) }

    // Mirrors the ENUM in `vendors.business_type`. The server whitelists these
    // too — an unknown value would otherwise be stored by MySQL as an empty
    // string with only a warning.
    val businessTypes = listOf(
        "market_vendor" to "Market vendor",
        "farmer" to "Farmer",
        "wholesaler" to "Wholesaler",
        "store" to "Store",
        "Fish Products" to "Fish products"
    )

    val saving = saveState is VendorDetailsSaveState.Saving
    // The two the server requires, and the two an admin needs to verify anyone.
    val canSubmit = businessName.isNotBlank() && phone.isNotBlank() && !saving

    LaunchedEffect(saveState) {
        if (saveState is VendorDetailsSaveState.Saved) {
            onDone()
            vendorViewModel.clearDetailsSaveState()
        }
    }

    Scaffold(
        containerColor = Cream,
        topBar = {
            TopAppBar(
                title = { Text("Business details", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Forest,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Says plainly what happens next. Without it, a vendor submits the
            // form and is left guessing why they still cannot list anything.
            Card(
                colors = CardDefaults.cardColors(containerColor = ForestSurface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Tell us about your business",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = Ink
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "An administrator checks these details before you can list " +
                            "products. This is what customers will see.",
                        fontSize = 13.sp,
                        color = InkMuted
                    )
                }
            }

            OutlinedTextField(
                value = businessName,
                onValueChange = { businessName = it },
                label = { Text("Business name *") },
                singleLine = true,
                enabled = !saving,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it },
                label = { Text("Phone number *") },
                singleLine = true,
                enabled = !saving,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Phone
                ),
                modifier = Modifier.fillMaxWidth()
            )

            ExposedDropdownMenuBox(
                expanded = typeMenuOpen,
                onExpandedChange = { if (!saving) typeMenuOpen = !typeMenuOpen }
            ) {
                OutlinedTextField(
                    value = businessTypes.firstOrNull { it.first == businessType }?.second
                        ?: "Market vendor",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Business type") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeMenuOpen) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = typeMenuOpen,
                    onDismissRequest = { typeMenuOpen = false }
                ) {
                    businessTypes.forEach { (value, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                businessType = value
                                typeMenuOpen = false
                            }
                        )
                    }
                }
            }

            OutlinedTextField(
                value = location,
                onValueChange = { location = it },
                label = { Text("Location") },
                placeholder = { Text("e.g. Kampala, Nakawa") },
                singleLine = true,
                enabled = !saving,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = marketStall,
                onValueChange = { marketStall = it },
                label = { Text("Market stall") },
                placeholder = { Text("e.g. Owino Market Stall 45") },
                singleLine = true,
                enabled = !saving,
                modifier = Modifier.fillMaxWidth()
            )

            (saveState as? VendorDetailsSaveState.Error)?.let { state ->
                Text(state.message, color = Tomato, fontSize = 13.sp)
            }

            Button(
                onClick = {
                    vendorViewModel.saveBusinessDetails(
                        businessName = businessName,
                        phone = phone,
                        businessType = businessType,
                        location = location,
                        marketStall = marketStall
                    )
                },
                enabled = canSubmit,
                colors = ButtonDefaults.buttonColors(containerColor = Forest),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                if (saving) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    Text("Save details", fontWeight = FontWeight.Bold)
                }
            }

            if (!canSubmit && !saving) {
                Text(
                    "Business name and phone number are both required.",
                    color = InkMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}
