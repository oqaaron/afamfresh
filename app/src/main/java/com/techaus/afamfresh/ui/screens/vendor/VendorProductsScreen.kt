package com.techaus.afamfresh.ui.screens.vendor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techaus.afamfresh.ui.components.NetworkImage
import com.techaus.afamfresh.models.Product
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
    onBack: () -> Unit
) {
    val products by vendorViewModel.vendorProducts.collectAsState()
    val isLoading by vendorViewModel.isLoading.collectAsState()

    LaunchedEffect(Unit) { vendorViewModel.loadVendorProducts() }

    Scaffold(
        containerColor = Cream,
        topBar = {
            Row(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Ink)
                }
                Text("My Products", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                isLoading && products.isEmpty() -> CircularProgressIndicator(color = Forest, modifier = Modifier.align(Alignment.Center))
                products.isEmpty() -> Text("No products listed yet", color = InkMuted, modifier = Modifier.align(Alignment.Center))
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

@Composable
private fun VendorProductRow(product: Product) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CardWhite).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NetworkImage(
            model = product.image,
            contentDescription = product.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(ForestSurface)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(product.name, fontWeight = FontWeight.SemiBold, color = Ink)
            product.category?.let { Text(it, fontSize = 12.sp, color = InkMuted) }
        }
        Text(formatUgx(product.price), fontWeight = FontWeight.Bold, color = Forest)
    }
}
