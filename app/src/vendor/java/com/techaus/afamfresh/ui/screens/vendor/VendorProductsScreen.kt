package com.techaus.afamfresh.ui.screens.vendor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techaus.afamfresh.ui.components.NetworkImage
import com.techaus.afamfresh.models.VendorProduct
import com.techaus.afamfresh.ui.components.EmptyState
import com.techaus.afamfresh.ui.components.ErrorState
import com.techaus.afamfresh.ui.components.ListSkeleton
import com.techaus.afamfresh.ui.theme.*
import com.techaus.afamfresh.utils.formatUgx
import com.techaus.afamfresh.viewmodel.VendorViewModel

// ⚠️ INFERRED screen. Signature matches MainScreen.kt's composable("vendor_products")
// call: VendorProductsScreen(vendorViewModel, onBack).
//
// Read-only by design: ApiService.kt only has a GET for vendor/products.php —
// no create/update/delete methods exist for a vendor's regular product catalog
// (unlike surplus listings, which have full CRUD). Add editing once those
// endpoints exist.
@Composable
fun VendorProductsScreen(
    vendorViewModel: VendorViewModel,
    onAddProduct: () -> Unit,
    onBack: () -> Unit
) {
    val products by vendorViewModel.vendorProducts.collectAsState()
    val isLoading by vendorViewModel.isLoading.collectAsState()
    val error by vendorViewModel.error.collectAsState()
    val canRetry by vendorViewModel.canRetry.collectAsState()

    val profile by vendorViewModel.profile.collectAsState()

    // Keyed on the vendor profile so this waits for start() to identify the
    // vendor — vendor-products.php takes the user id as a query parameter and
    // does not read the session.
    LaunchedEffect(profile?.id) {
        if (profile != null) vendorViewModel.loadVendorProducts()
    }

    Scaffold(
        containerColor = Cream,
        topBar = {
            Row(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Ink)
                }
                Text("My Products", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
            }
        },
        floatingActionButton = {
            // The only way to fill an inventory from the app. Without it a
            // vendor's product list could only be populated by an admin, and a
            // surplus listing has to point at a product already stocked.
            FloatingActionButton(onClick = onAddProduct, containerColor = Forest) {
                Icon(Icons.Default.Add, contentDescription = "Add product", tint = Color.White)
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                isLoading && products.isEmpty() -> ListSkeleton(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                error != null && products.isEmpty() -> ErrorState(
                    message = error ?: "",
                    onRetry = if (canRetry) ({ vendorViewModel.loadVendorProducts() }) else null
                )
                products.isEmpty() -> EmptyState(
                    icon = Icons.Default.Inventory2,
                    title = "No products listed yet",
                    detail = "Products you sell through AfamFresh will appear here."
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(products, key = { it.id }) { product -> VendorProductRow(product) }
                }
            }
        }
    }
}

/**
 * Renders a [VendorProduct] — the vendor's own price and stock for a catalogue
 * item — rather than a catalogue Product. vendor-products.php returns
 * `vendor_products` rows joined onto `items`, so `price` here is the vendor's
 * override and may be null.
 */
@Composable
private fun VendorProductRow(product: VendorProduct) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CardWhite).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NetworkImage(
            model = product.image,
            contentDescription = product.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(ForestSurface)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(product.displayName, fontWeight = FontWeight.SemiBold, color = Ink)
            Text(
                buildString {
                    product.category?.takeIf { it.isNotBlank() }?.let { append(it) }
                    if (isNotEmpty()) append("  •  ")
                    append(
                        if (product.inStock) "${product.stockQuantity} in stock"
                        else "Out of stock"
                    )
                },
                fontSize = 12.sp,
                color = InkMuted
            )
        }
        // Null means "use the catalogue price", so say so rather than showing 0.
        Text(
            product.price?.let { formatUgx(it) } ?: "Catalogue price",
            fontWeight = FontWeight.Bold,
            color = Forest
        )
    }
}
