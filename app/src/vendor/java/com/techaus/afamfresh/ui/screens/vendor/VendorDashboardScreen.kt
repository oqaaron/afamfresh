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
import com.techaus.afamfresh.utils.formatUgx
import com.techaus.afamfresh.viewmodel.VendorViewModel
import com.techaus.afamfresh.utils.formatQuantity

// ⚠️ INFERRED screen. Signature matches MainScreen.kt's composable("vendor_dashboard")
// call exactly: VendorDashboardScreen(vendorViewModel, onAddListing, onEditListing,
// onViewOrders, onViewProducts, onBack).
@Composable
fun VendorDashboardScreen(
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

    // api/Bulk-listings.php refuses to create a listing unless the vendor is
    // verified. Without this the FAB was offered anyway and the request came
    // back rejected with nothing on screen explaining why.
    val isVerified = profile?.isVerified == true

    val activeCount = listings.count { it.status.equals("approved", ignoreCase = true) }
    val pendingCount = listings.count { it.status.equals("pending", ignoreCase = true) }

    Scaffold(
        containerColor = Cream,
        topBar = {
            Row(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Ink)
                }
                Text("Vendor Dashboard", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
                Spacer(Modifier.weight(1f))
                // The only way into the notifications screen from this app. The
                // route is registered for every flavor, but the bell that
                // reached it lived on the customer catalogue, which a vendor
                // never opens — so vendor notifications were written and never
                // seen.
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
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {

            // ===== Verification gate =====
            // Shown until an admin verifies. It is the only route to the
            // business-details form, and the only place the vendor is told why
            // they cannot list anything yet.
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
                                "them. You can start listing products once they do.",
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
                StatCard(label = "Pending review", value = "$pendingCount", modifier = Modifier.weight(1f))
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ===== Quick actions =====
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionButton(icon = Icons.Default.ListAlt, label = "Orders", onClick = onViewOrders, modifier = Modifier.weight(1f))
                ActionButton(icon = Icons.Default.Inventory, label = "Products", onClick = onViewProducts, modifier = Modifier.weight(1f))
            }

            Spacer(modifier = Modifier.height(20.dp))
            Text("Your Bulk listings", fontWeight = FontWeight.Bold, color = Ink, fontSize = 16.sp)
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
                    detail = "Tap the + button to post Bulk produce at a discount."
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(listings, key = { it.id }) { listing ->
                        VendorListingRow(listing = listing, onClick = { onEditListing(listing) })
                    }
                    item { Spacer(modifier = Modifier.height(80.dp)) }
                }
            }
        }
    }
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
private fun ActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
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

@Composable
private fun VendorListingRow(listing: BulkListing, onClick: () -> Unit) {
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
        Column {
            Text(listing.displayTitle, fontWeight = FontWeight.SemiBold, color = Ink)
            Text(
                formatQuantity(listing.remainingQuantity, listing.unit) +
                    "  •  ${formatUgx(listing.discountedPrice)}",
                fontSize = 12.sp,
                color = InkMuted
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(status = listing.status)
            Spacer(modifier = Modifier.width(10.dp))
            Icon(Icons.Default.Edit, contentDescription = "Edit", tint = InkMuted, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun StatusDot(status: String) {
    val color = when (status.lowercase()) {
        "approved" -> Forest
        "pending" -> Color(0xFFFFA000)
        // The enum is pending|approved|rejected|cancelled — there is no
        // "sold_out" status; a sold-out listing has remaining_quantity = 0.
        "rejected", "cancelled" -> Tomato
        else -> InkMuted
    }
    Box(modifier = Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
}
