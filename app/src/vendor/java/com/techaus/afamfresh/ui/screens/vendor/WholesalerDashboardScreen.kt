package com.techaus.afamfresh.ui.screens.vendor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Inventory
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techaus.afamfresh.models.BulkListing
import com.techaus.afamfresh.ui.components.EmptyState
import com.techaus.afamfresh.ui.components.ErrorState
import com.techaus.afamfresh.ui.components.ListSkeleton
import com.techaus.afamfresh.ui.theme.*
import com.techaus.afamfresh.utils.formatQuantity
import com.techaus.afamfresh.utils.formatUgx
import com.techaus.afamfresh.viewmodel.VendorViewModel

/**
 * The wholesaler's home screen.
 *
 * A wholesaler is a `vendors` row with `business_type = 'wholesaler'`, so
 * everything underneath — orders, earnings, products, the four-gate approval
 * chain, commission and payouts — is the vendor machinery unchanged. Only the
 * listing shape differs, and that is what this screen and
 * [AddWholesaleListingScreen] exist for.
 *
 * Deliberately a separate screen from [VendorDashboardScreen] rather than a
 * flag inside it: the two differ in what a row means (a flat price and a
 * minimum order, versus a markdown and an expiry date), and threading that
 * through one screen would put a branch on nearly every line while leaving the
 * surplus path exposed to any mistake made in the wholesale one.
 */
@Composable
fun WholesalerDashboardScreen(
    vendorViewModel: VendorViewModel,
    onAddListing: () -> Unit,
    onEditListing: (BulkListing) -> Unit,
    onViewOrders: () -> Unit,
    onViewProducts: () -> Unit,
    onEditBusinessDetails: () -> Unit,
    onNotificationsClick: () -> Unit,
    unreadNotifications: Int,
    onBack: () -> Unit
) {
    val listings by vendorViewModel.listings.collectAsState()
    val isLoading by vendorViewModel.isLoading.collectAsState()
    val error by vendorViewModel.error.collectAsState()
    val canRetry by vendorViewModel.canRetry.collectAsState()
    val profile by vendorViewModel.profile.collectAsState()

    // Same gate as the vendor dashboard: api/api/Bulk-listings.php refuses to
    // create a listing for an unverified seller, so offering the FAB would only
    // produce a rejected request with nothing on screen explaining why.
    val isVerified = profile?.isVerified == true

    val activeCount = listings.count { it.status.equals("approved", ignoreCase = true) }
    val pendingCount = listings.count { it.status.equals("pending", ignoreCase = true) }

    Scaffold(
        containerColor = Cream,
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Ink)
                }
                Text(
                    "Wholesale Dashboard",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ink
                )
                Spacer(Modifier.weight(1f))
                BadgedBox(
                    badge = {
                        if (unreadNotifications > 0) {
                            Badge { Text("$unreadNotifications") }
                        }
                    }
                ) {
                    IconButton(onClick = onNotificationsClick) {
                        Icon(
                            Icons.Default.Notifications,
                            contentDescription = "Notifications",
                            tint = Ink
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (isVerified) {
                FloatingActionButton(onClick = onAddListing, containerColor = Forest) {
                    Icon(Icons.Default.Add, contentDescription = "Add listing", tint = Color.White)
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
        ) {

            // ===== Verification gate =====
            if (profile != null && !isVerified) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = ForestSurface),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "Your account is not verified yet",
                            fontWeight = FontWeight.Bold,
                            color = Ink,
                            fontSize = 15.sp
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Add your business details so an administrator can review " +
                                "them. You can start listing wholesale stock once they do.",
                            color = InkMuted,
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = onEditBusinessDetails,
                            colors = ButtonDefaults.buttonColors(containerColor = Forest),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Business details", fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // ===== Quick stats =====
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(label = "Active", value = "$activeCount", modifier = Modifier.weight(1f))
                StatCard(
                    label = "Pending review",
                    value = "$pendingCount",
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ===== Quick actions =====
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionButton(
                    icon = Icons.Default.ListAlt,
                    label = "Orders",
                    onClick = onViewOrders,
                    modifier = Modifier.weight(1f)
                )
                ActionButton(
                    icon = Icons.Default.Inventory,
                    label = "Products",
                    onClick = onViewProducts,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
            Text(
                "Your wholesale listings",
                fontWeight = FontWeight.Bold,
                color = Ink,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.height(10.dp))

            if (isLoading && listings.isEmpty()) {
                ListSkeleton(rows = 3)
            } else if (error != null && listings.isEmpty()) {
                ErrorState(
                    message = error ?: "",
                    onRetry = if (canRetry) ({ vendorViewModel.loadListings() }) else null
                )
            } else if (listings.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.Inventory,
                    title = "No listings yet",
                    detail = "Tap the + button to offer stock at a wholesale price."
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(listings, key = { it.id }) { listing ->
                        WholesaleListingRow(
                            listing = listing,
                            onClick = { onEditListing(listing) }
                        )
                    }
                    item { Spacer(modifier = Modifier.height(80.dp)) }
                }
            }
        }
    }
}

/**
 * A wholesale listing as its seller reads it: what one unit costs, how much is
 * left, and the minimum a buyer has to take.
 *
 * The minimum is the number a wholesaler is asked about most, and it has no
 * equivalent on a surplus row — which is why this is not [VendorDashboardScreen]'s
 * row with a different label.
 */
@Composable
private fun WholesaleListingRow(listing: BulkListing, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CardWhite)
            .clickable { onClick() }
            .padding(14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(listing.displayTitle, fontWeight = FontWeight.SemiBold, color = Ink)
            Text(
                formatQuantity(listing.remainingQuantity, listing.unit) +
                    " left  •  ${formatUgx(listing.discountedPrice)}",
                fontSize = 12.sp,
                color = InkMuted
            )
            // Only when there is one. A listing created before the wholesale
            // migration, or one an admin corrected, can carry 0 — and "min 0"
            // reads as a bug rather than as "no minimum".
            if (listing.minOrderQuantity > 0.0) {
                Text(
                    "Minimum order ${formatQuantity(listing.minOrderQuantity, listing.unit)}",
                    fontSize = 12.sp,
                    color = Forest,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            WholesaleStatusDot(status = listing.status)
            Spacer(modifier = Modifier.width(10.dp))
            Icon(
                Icons.Default.Edit,
                contentDescription = "Edit",
                tint = InkMuted,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun WholesaleStatusDot(status: String) {
    val color = when (status.lowercase()) {
        "approved" -> Forest
        "pending" -> Color(0xFFFFA000)
        "rejected", "cancelled" -> Tomato
        else -> InkMuted
    }
    Box(modifier = Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.clip(RoundedCornerShape(16.dp)).background(CardWhite).padding(16.dp)
    ) {
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Forest)
        Text(label, fontSize = 12.sp, color = InkMuted)
    }
}

@Composable
private fun ActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(ForestSurface)
            .clickable { onClick() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = Forest, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(label, color = Forest, fontWeight = FontWeight.Medium, fontSize = 13.sp)
    }
}
