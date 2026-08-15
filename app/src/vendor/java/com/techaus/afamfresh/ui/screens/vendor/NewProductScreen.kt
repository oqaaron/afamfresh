package com.techaus.afamfresh.ui.screens.vendor

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.techaus.afamfresh.ui.theme.*
import com.techaus.afamfresh.viewmodel.VendorViewModel
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Create a product of the vendor's own.
 *
 * The vendor half of vendor-owned products. Before this, `items` was the admin
 * catalogue alone and Bulk_listings.product_id has a hard foreign key to it,
 * so a vendor could only list Bulk of something an admin had already
 * entered.
 *
 * What is created here is NOT on sale. It goes in as `pending`, an admin
 * approves it, and only then can the vendor attach a Bulk listing to it. It
 * never appears in the main shop either way — api/products.php filters
 * vendor-owned rows out, because a vendor product has no stock or fulfilment
 * path there.
 *
 * The screen says all of that, because a vendor who thinks they have just put
 * something on sale will wait for orders that cannot come.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewProductScreen(
    vendorViewModel: VendorViewModel,
    onDone: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val isLoading by vendorViewModel.isLoading.collectAsState()

    var name by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var quantityType by remember { mutableStateOf("kg") }
    var imageUri by remember { mutableStateOf<Uri?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    val pickImage = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri -> imageUri = uri }

    // Matches the categories already in `items`, so a vendor product files
    // alongside the catalogue rather than inventing a category of one.
    val categories = listOf(
        "Vegetables", "Fruits", "Grains", "Tubers", "Legumes",
        "Herbs", "Dairy", "Meat", "Fish Products", "Others"
    )
    val units = listOf("kg", "piece", "bunch", "tray", "basket", "crate", "sack")

    val canSubmit = name.isNotBlank() && category.isNotBlank() &&
        price.isNotBlank() && !isLoading

    Scaffold(
        containerColor = Cream,
        topBar = {
            TopAppBar(
                title = { Text("New product", fontWeight = FontWeight.Bold) },
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
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = ForestSurface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "An administrator reviews this first",
                        fontWeight = FontWeight.Bold,
                        color = Ink,
                        fontSize = 15.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Once approved you can list Bulk of it for customers to buy. " +
                            "Adding it here does not put it on sale by itself.",
                        color = InkMuted,
                        fontSize = 13.sp
                    )
                }
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Product name *") },
                placeholder = { Text("e.g. Nakati bundle") },
                singleLine = true,
                enabled = !isLoading,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Text("Category *", fontSize = 13.sp, color = InkMuted)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                categories.forEach { c ->
                    val selected = category == c
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (selected) Forest else PillGray)
                            .clickable { category = c }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            c,
                            color = if (selected) Color.White else Ink,
                            fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = price,
                    onValueChange = { price = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Normal price *") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Number
                    ),
                    singleLine = true,
                    enabled = !isLoading,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = quantityType,
                    onValueChange = { },
                    readOnly = true,
                    label = { Text("Sold by") },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                units.forEach { u ->
                    val selected = quantityType == u
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (selected) Forest else PillGray)
                            .clickable { quantityType = u }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            u,
                            color = if (selected) Color.White else Ink,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Description (optional)") },
                minLines = 2,
                enabled = !isLoading,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )

            // Optional on purpose: the server accepts the fields without one,
            // and a vendor standing in a market with no photo to hand should
            // still be able to submit.
            Text("Photo (optional)", fontSize = 13.sp, color = InkMuted)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(PillGray)
                    .clickable(enabled = !isLoading) { pickImage.launch("image/*") },
                contentAlignment = Alignment.Center
            ) {
                if (imageUri != null) {
                    AsyncImage(
                        model = imageUri,
                        contentDescription = "Selected photo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text("Tap to choose a photo", color = InkMuted, fontSize = 13.sp)
                }
            }

            message?.let {
                Text(it, color = if (isError) Tomato else Forest, fontSize = 13.sp)
            }

            Button(
                onClick = {
                    message = null
                    // Read the picked image here rather than holding bytes in
                    // state: the user may never choose one, and a large bitmap
                    // parked in a recomposing screen is wasted memory.
                    val part = imageUri?.let { uri ->
                        runCatching {
                            val bytes = context.contentResolver.openInputStream(uri)
                                ?.use { s -> s.readBytes() } ?: return@runCatching null
                            MultipartBody.Part.createFormData(
                                "image",
                                "product.jpg",
                                bytes.toRequestBody("image/*".toMediaTypeOrNull())
                            )
                        }.getOrNull()
                    }

                    vendorViewModel.createProduct(
                        name = name.trim(),
                        category = category,
                        price = price.trim(),
                        description = description.trim(),
                        quantityType = quantityType,
                        image = part
                    ) { ok, reason ->
                        isError = !ok
                        message = reason
                        if (ok) onDone()
                    }
                },
                enabled = canSubmit,
                colors = ButtonDefaults.buttonColors(containerColor = Forest),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    Text("Send for approval", fontWeight = FontWeight.Bold)
                }
            }

            if (!canSubmit && !isLoading) {
                Text(
                    "Name, category and price are required.",
                    color = InkMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}
