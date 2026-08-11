package com.techaus.afamfresh.ui.screens.vendor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
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
import com.techaus.afamfresh.models.Product
import com.techaus.afamfresh.ui.theme.*
import com.techaus.afamfresh.utils.formatUgx
import com.techaus.afamfresh.viewmodel.ProductViewModel
import com.techaus.afamfresh.viewmodel.VendorViewModel

/**
 * Add a catalogue product to this vendor's inventory.
 *
 * The missing half of the vendor flow. `POST vendor-products.php` has always
 * supported this and the app never called it, so a vendor's inventory could
 * only be filled by an admin — and since a surplus listing must point at a
 * product the vendor already stocks, an empty inventory made the whole surplus
 * feature unreachable.
 *
 * A vendor stocks catalogue items rather than inventing them: `items` is shared
 * and admin-owned, which is what stops five vendors creating five spellings of
 * the same tomato. What the vendor sets here is their own price and stock.
 *
 * The endpoint upserts, so choosing a product already in the inventory edits it
 * rather than failing — which is also how the price of an existing line gets
 * changed, there being no separate edit screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddInventoryScreen(
    vendorViewModel: VendorViewModel,
    productViewModel: ProductViewModel,
    onDone: () -> Unit,
    onBack: () -> Unit
) {
    val catalogue by productViewModel.products.collectAsState()
    val myProducts by vendorViewModel.vendorProducts.collectAsState()
    val isLoading by vendorViewModel.isLoading.collectAsState()

    var query by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf<Product?>(null) }
    var price by remember { mutableStateOf("") }
    var stock by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (catalogue.isEmpty()) productViewModel.loadProducts()
    }

    val alreadyStocked = remember(myProducts) { myProducts.map { it.productId }.toSet() }
    val filtered = remember(catalogue, query) {
        if (query.isBlank()) catalogue
        else catalogue.filter { it.name.contains(query, ignoreCase = true) }
    }

    Scaffold(
        containerColor = Cream,
        topBar = {
            TopAppBar(
                title = { Text("Add to my inventory", fontWeight = FontWeight.Bold) },
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
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search the catalogue") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))

            message?.let {
                Text(
                    it,
                    color = if (isError) Tomato else Forest,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            if (catalogue.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Forest)
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(filtered, key = { it.id }) { product ->
                        val stocked = product.id in alreadyStocked
                        val selected = chosen?.id == product.id

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (selected) ForestSurface else CardWhite)
                                .clickable {
                                    chosen = if (selected) null else product
                                    // Seeded from the catalogue so the common
                                    // case is one tap and a quantity.
                                    price = product.price.toInt().toString()
                                    stock = ""
                                    message = null
                                }
                                .padding(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(product.name, color = Ink, fontSize = 15.sp)
                                    Text(
                                        buildString {
                                            append(formatUgx(product.price))
                                            product.category?.let { append("  •  $it") }
                                        },
                                        color = InkMuted,
                                        fontSize = 12.sp
                                    )
                                }
                                if (stocked) {
                                    // Says so rather than hiding it: re-picking
                                    // an existing product is how its price and
                                    // stock get changed.
                                    Text(
                                        "In inventory",
                                        color = Forest,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }

                            if (selected) {
                                Spacer(Modifier.height(10.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedTextField(
                                        value = price,
                                        onValueChange = { price = it.filter { c -> c.isDigit() } },
                                        label = { Text("Your price") },
                                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                            keyboardType = KeyboardType.Number
                                        ),
                                        singleLine = true,
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.weight(1f)
                                    )
                                    OutlinedTextField(
                                        value = stock,
                                        onValueChange = { stock = it.filter { c -> c.isDigit() } },
                                        label = { Text("Stock") },
                                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                            keyboardType = KeyboardType.Number
                                        ),
                                        singleLine = true,
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.weight(1f)
                                    )
                                }

                                Spacer(Modifier.height(10.dp))
                                Button(
                                    onClick = {
                                        val qty = stock.toIntOrNull()
                                        if (qty == null || qty <= 0) {
                                            isError = true
                                            message = "Enter how much stock you have"
                                            return@Button
                                        }
                                        vendorViewModel.addProductToInventory(
                                            productId = product.id,
                                            stockQuantity = qty,
                                            price = price.toDoubleOrNull()
                                        ) { ok, reason ->
                                            isError = !ok
                                            message = if (ok) {
                                                chosen = null
                                                "${product.name} is now in your inventory"
                                            } else {
                                                reason
                                            }
                                        }
                                    },
                                    enabled = !isLoading,
                                    colors = ButtonDefaults.buttonColors(containerColor = Forest),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth().height(46.dp)
                                ) {
                                    if (isLoading) {
                                        CircularProgressIndicator(
                                            color = Color.White,
                                            strokeWidth = 2.dp,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    } else {
                                        Text(
                                            if (stocked) "Update" else "Add to inventory",
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = onDone,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Done", color = Forest, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(20.dp))
                    }
                }
            }
        }
    }
}
