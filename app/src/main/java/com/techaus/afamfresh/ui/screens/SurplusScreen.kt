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
import com.techaus.afamfresh.models.SurplusListing
import com.techaus.afamfresh.ui.theme.*
import com.techaus.afamfresh.utils.formatUgx
import com.techaus.afamfresh.viewmodel.SurplusViewModel

// ⚠️ INFERRED screen. Signature matches MainScreen.kt's composable("surplus")
// call exactly. This is the public "rescue surplus produce at a discount"
// marketplace — separate from the vendor-facing screens in ui/screens/vendor/.
@Composable
fun SurplusScreen(
    surplusViewModel: SurplusViewModel,
    onBack: () -> Unit,
    onListingClick: (SurplusListing) -> Unit
) {
    val listings by surplusViewModel.listings.collectAsState()
    val isLoading by surplusViewModel.isLoading.collectAsState()
    val error by surplusViewModel.error.collectAsState()

    Scaffold(
        containerColor = Cream,
        topBar = {
            Row(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Ink)
                }
                Column {
                    Text("Surplus Deals", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Text("Rescue fresh produce at a discount", fontSize = 12.sp, color = InkMuted)
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                isLoading && listings.isEmpty() -> {
                    CircularProgressIndicator(color = Forest, modifier = Modifier.align(Alignment.Center))
                }
                error != null && listings.isEmpty() -> {
                    Text(error ?: "", color = Tomato, modifier = Modifier.align(Alignment.Center).padding(24.dp))
                }
                listings.isEmpty() -> {
                    Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.LocalFlorist, contentDescription = null, tint = InkMuted, modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("No surplus deals right now", color = InkMuted)
                    }
                }
                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(listings, key = { it.id }) { listing ->
                            SurplusCard(listing = listing, onClick = { onListingClick(listing) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SurplusCard(listing: SurplusListing, onClick: () -> Unit) {
    val discountPercent = if (listing.originalPrice > 0)
        (((listing.originalPrice - listing.price) / listing.originalPrice) * 100).toInt()
    else 0

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
            contentDescription = listing.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(72.dp).clip(RoundedCornerShape(14.dp)).background(ForestSurface)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(listing.title, fontWeight = FontWeight.SemiBold, color = Ink)
            listing.vendorName?.let { Text(it, fontSize = 12.sp, color = InkMuted) }
            Text("${listing.quantity} ${listing.unit} left  •  Expires ${listing.expiresAt}", fontSize = 11.sp, color = InkMuted)
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatUgx(listing.originalPrice),
                    fontSize = 12.sp,
                    color = InkMuted,
                    textDecoration = TextDecoration.LineThrough
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(formatUgx(listing.price), fontWeight = FontWeight.Bold, color = Forest)
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
