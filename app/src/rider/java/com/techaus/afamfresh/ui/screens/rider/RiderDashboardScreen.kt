package com.techaus.afamfresh.ui.screens.rider

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techaus.afamfresh.models.Delivery
import com.techaus.afamfresh.ui.components.EmptyState
import com.techaus.afamfresh.ui.components.ErrorState
import com.techaus.afamfresh.ui.components.ListSkeleton
import com.techaus.afamfresh.ui.theme.*
import com.techaus.afamfresh.utils.formatUgx
import com.techaus.afamfresh.viewmodel.RiderViewModel

/**
 * The rider's home: duty toggle, today's numbers, and the deliveries to do.
 *
 * Refreshes on open rather than from the ViewModel's init block — most accounts
 * are not riders and the endpoint refuses them, so loading eagerly would fire a
 * guaranteed error for every customer on every launch.
 */
@Composable
fun RiderDashboardScreen(
    riderViewModel: RiderViewModel,
    onDeliveryClick: (Int) -> Unit,
    onViewAll: () -> Unit,
    onBack: () -> Unit
) {
    val profile by riderViewModel.profile.collectAsState()
    val active by riderViewModel.active.collectAsState()
    val isLoading by riderViewModel.isLoading.collectAsState()
    val error by riderViewModel.error.collectAsState()

    LaunchedEffect(Unit) { riderViewModel.refresh() }

    // Position streaming is tied to the duty switch, so going off duty stops it.
    RiderLocationUpdates(enabled = profile?.online == true, riderViewModel = riderViewModel)

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
                Text("Rider", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)
        ) {
            // An account with the Rider role but no linked rider record is a
            // real state the admin can leave things in, so it gets an
            // explanation rather than an empty list.
            if (error != null && profile == null) {
                ErrorState(message = error ?: "", onRetry = { riderViewModel.refresh() })
                return@Column
            }

            DutyCard(
                name = profile?.displayName ?: "Rider",
                vehicle = profile?.vehicleOrDash ?: "—",
                online = profile?.online == true,
                onToggle = { riderViewModel.setOnline(it) }
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(
                    label = "To deliver",
                    value = "${profile?.activeCount ?: active.size}",
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    label = "Delivered today",
                    value = "${profile?.deliveredToday ?: 0}",
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Your deliveries", fontWeight = FontWeight.Bold, color = Ink, fontSize = 16.sp)
                Text(
                    "See all",
                    color = Forest,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { onViewAll() }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            when {
                isLoading && active.isEmpty() -> ListSkeleton(rows = 3)
                active.isEmpty() -> EmptyState(
                    icon = Icons.Default.LocalShipping,
                    title = "Nothing to deliver",
                    detail = "New deliveries appear here once an admin assigns them to you."
                )
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(active, key = { it.assignmentId ?: it.orderId ?: 0 }) { d ->
                        DeliveryRow(delivery = d, onClick = { d.orderId?.let(onDeliveryClick) })
                    }
                    item { Spacer(modifier = Modifier.height(40.dp)) }
                }
            }
        }
    }
}

@Composable
private fun DutyCard(
    name: String,
    vehicle: String,
    online: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardWhite)
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(name, fontWeight = FontWeight.SemiBold, color = Ink, fontSize = 16.sp)
            Text(
                if (online) "On duty  •  $vehicle" else "Off duty  •  $vehicle",
                fontSize = 12.sp,
                color = if (online) Forest else InkMuted
            )
        }
        Switch(
            checked = online,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Forest
            )
        )
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

/** Shared by the dashboard and the full deliveries list. */
@Composable
fun DeliveryRow(delivery: Delivery, onClick: () -> Unit) {
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
            Text(delivery.customerOrDash, fontWeight = FontWeight.SemiBold, color = Ink)
            Text(
                delivery.addressOrDash,
                fontSize = 12.sp,
                color = InkMuted,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StageChip(delivery)
                Spacer(modifier = Modifier.width(8.dp))
                Text(formatUgx(delivery.totalAmount ?: 0.0), fontSize = 12.sp, color = InkMuted)
            }
        }
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = InkMuted,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
fun StageChip(delivery: Delivery) {
    val color = when (delivery.status) {
        "delivered" -> Forest
        "on_way", "picked_up" -> Color(0xFFFFA000)
        else -> InkMuted
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(delivery.stageLabel, fontSize = 11.sp, color = color, fontWeight = FontWeight.Medium)
    }
}
