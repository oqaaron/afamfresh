package com.techaus.afamfresh.ui.screens.vendor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
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
import com.techaus.afamfresh.models.VendorCatalogueProduct
import com.techaus.afamfresh.ui.components.EmptyState
import com.techaus.afamfresh.ui.components.ErrorState
import com.techaus.afamfresh.ui.components.ListSkeleton
import coil.compose.AsyncImage
import com.techaus.afamfresh.ui.theme.*
import com.techaus.afamfresh.utils.formatUgx
import com.techaus.afamfresh.viewmodel.VendorViewModel

// ⚠️ INFERRED screen. Signature matches MainScreen.kt's composable("vendor_products")
// call: VendorProductsScreen(vendorViewModel, onBack).
//
// Read-only by design: ApiService.kt only has a GET for vendor/products.php —
// no create/update/delete methods exist for a vendor's regular product catalog
// (unlike Bulk listings, which have full CRUD). Add editing once those
// endpoints exist.
@Composable
fun VendorProductsScreen(
    vendorViewModel: VendorViewModel,
    onAddProduct: () -> Unit,
    onBack: () -> Unit
) {
    // The vendor's OWN products now, not catalogue items they stock. Only the
    // approved ones can carry a Bulk listing, and pending/rejected must stay
    // visible or a rejection is invisible and simply gets resubmitted.
    val products by vendorViewModel.myProducts.collectAsState()
    val isLoading by vendorViewModel.isLoading.collectAsState()
    val error by vendorViewModel.error.collectAsState()
    val canRetry by vendorViewModel.canRetry.collectAsState()

    val profile by vendorViewModel.profile.collectAsState()

    // Refetches on every entry to the screen, so returning here after an admin
    // approves picks the change up. It does NOT refresh while the screen is
    // already open -- approval happens elsewhere and nothing pushes it here --
    // which is what the refresh action in the top bar is for.
    LaunchedEffect(profile?.id) {
        if (profile != null) vendorViewModel.loadMyProducts()
    }

    Scaffold(
        containerColor = Cream,
        topBar = {
            Row(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Ink)
                }
                Text("My Products", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = { vendorViewModel.loadMyProducts() },
                    enabled = !isLoading
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Ink)
                }
            }
        },
        floatingActionButton = {
            // The only way to fill an inventory from the app. Without it a
            // vendor's product list could only be populated by an admin, and a
            // Bulk listing has to point at a product already stocked.
            FloatingActionButton(onClick = onAddProduct, containerColor = Forest) {
                Icon(Icons.Default.Add, contentDescription = "Add product", tint = Color.White)
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

            // A failed refresh used to be invisible whenever a stale list was
            // already on screen -- the error branch below only fires when the
            // list is empty. So a session that had expired looked exactly like
            // "the admin has not approved it yet".
            if (error != null && products.isNotEmpty()) {
                Text(
                    error ?: "",
                    color = Tomato,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp)
                )
            }

            when {
                isLoading && products.isEmpty() -> ListSkeleton(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                error != null && products.isEmpty() -> ErrorState(
                    message = error ?: "",
                    onRetry = if (canRetry) ({ vendorViewModel.loadMyProducts() }) else null
                )
                products.isEmpty() -> EmptyState(
                    icon = Icons.Default.Inventory2,
                    title = "No products yet",
                    detail = "Add what you sell. An administrator approves each one " +
                        "before you can list Bulk of it."
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(products, key = { it.id }) { product -> MyProductRow(product) }
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

/**
 * One of the vendor's own products, with the status an admin controls.
 *
 * The status is the point of this row. A vendor needs to see what is waiting,
 * and above all why something was rejected — a rejection with no visible reason
 * just gets resubmitted unchanged.
 */
@Composable
private fun MyProductRow(product: VendorCatalogueProduct) {
    val (badgeText, badgeBg, badgeFg) = when {
        product.isApproved -> Triple("Approved", ForestSurface, Forest)
        product.isRejected -> Triple("Rejected", Color(0xFFFFEBEE), Tomato)
        else -> Triple("Awaiting approval", Color(0xFFFFF4E5), Color(0xFF8A6100))
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!product.imageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = product.imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(product.name, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(
                    buildString {
                        product.price?.let { append("UGX ").append(it) }
                        product.category?.let { append("  •  ").append(it) }
                    },
                    color = InkMuted,
                    fontSize = 12.sp
                )
                if (product.isRejected && !product.rejectionReason.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(product.rejectionReason, color = Tomato, fontSize = 12.sp)
                }
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(badgeBg)
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(badgeText, color = badgeFg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
