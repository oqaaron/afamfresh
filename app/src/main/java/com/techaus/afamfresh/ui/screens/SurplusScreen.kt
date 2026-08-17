package com.techaus.afamfresh.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.LocalFlorist
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techaus.afamfresh.ui.components.NetworkImage
import com.techaus.afamfresh.models.BulkListing
import com.techaus.afamfresh.ui.components.EmptyState
import com.techaus.afamfresh.ui.components.ErrorState
import com.techaus.afamfresh.ui.components.ListSkeleton
import com.techaus.afamfresh.ui.theme.*
import com.techaus.afamfresh.utils.formatUgx
import com.techaus.afamfresh.viewmodel.BulkViewModel

// ⚠️ INFERRED screen. Signature matches MainScreen.kt's composable("Bulk")
// call exactly. This is the public "rescue Bulk produce at a discount"
// marketplace — separate from the vendor-facing screens in ui/screens/vendor/.
@Composable
fun BulkScreen(
    BulkViewModel: BulkViewModel,
    onBack: () -> Unit,
    onListingClick: (BulkListing) -> Unit,
    // Bulk orders are not in the ordinary order list — different table,
    // different lifecycle — so without this the only route to them is having
    // just paid for one.
    onMyOrdersClick: () -> Unit = {}
) {
    val listings by BulkViewModel.listings.collectAsState()
    val isLoading by BulkViewModel.isLoading.collectAsState()
    val error by BulkViewModel.error.collectAsState()
    val canRetry by BulkViewModel.canRetry.collectAsState()

    Scaffold(
        containerColor = Cream,
        topBar = {
            Row(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Ink)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Bulk Deals", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Text("Rescue fresh produce at a discount", fontSize = 12.sp, color = InkMuted)
                }
                TextButton(onClick = onMyOrdersClick) {
                    Text("My orders", color = Forest, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                isLoading && listings.isEmpty() -> {
                    ListSkeleton(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                }
                error != null && listings.isEmpty() -> {
                    ErrorState(
                        message = error ?: "",
                        onRetry = if (canRetry) ({ BulkViewModel.loadListings() }) else null
                    )
                }
                listings.isEmpty() -> {
                    EmptyState(
                        icon = Icons.Default.LocalFlorist,
                        title = "No Bulk deals right now",
                        detail = "Vendors post discounted produce here when they have extra. Check back soon.",
                        actionLabel = "REFRESH",
                        onAction = { BulkViewModel.loadListings() }
                    )
                }
                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(listings, key = { it.id }) { listing ->
                            BulkCard(listing = listing, onClick = { onListingClick(listing) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BulkCard(listing: BulkListing, onClick: () -> Unit) {
    // The server stores and validates discount_percent itself, so use it rather
    // than recomputing it from the two prices.
    val discountPercent = listing.discountPercent.toInt()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardWhite)
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NetworkImage(
            model = listing.image,
            contentDescription = listing.displayTitle,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(72.dp).clip(RoundedCornerShape(14.dp)).background(ForestSurface)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(listing.displayTitle, fontWeight = FontWeight.SemiBold, color = Ink)
            listing.vendorDisplayName?.let { Text(it, fontSize = 12.sp, color = InkMuted) }
            Text(
                buildString {
                    append("${listing.remainingQuantity} ${listing.unit.orEmpty()} left".trim())
                    listing.expiryDate?.let { append("  •  Expires $it") }
                },
                fontSize = 11.sp,
                color = InkMuted
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatUgx(listing.originalPrice),
                    fontSize = 12.sp,
                    color = InkMuted,
                    textDecoration = TextDecoration.LineThrough
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(formatUgx(listing.discountedPrice), fontWeight = FontWeight.Bold, color = Forest)
            }
        }
        if (discountPercent > 0) {
            Box(
                modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Forest).padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text("-$discountPercent%", color = androidx.compose.ui.graphics.Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
