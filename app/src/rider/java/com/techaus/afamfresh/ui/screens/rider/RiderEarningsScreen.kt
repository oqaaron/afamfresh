package com.techaus.afamfresh.ui.screens.rider

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techaus.afamfresh.models.EarningsEntry
import com.techaus.afamfresh.models.RiderActionState
import com.techaus.afamfresh.ui.components.EmptyState
import com.techaus.afamfresh.ui.components.ListSkeleton
import com.techaus.afamfresh.ui.theme.*
import com.techaus.afamfresh.utils.formatUgx
import com.techaus.afamfresh.viewmodel.RiderViewModel

/**
 * What a rider has earned and what they can withdraw.
 *
 * Earnings are computed and credited server-side the moment a delivery is
 * marked delivered (api/rider.php) — this screen only displays the ledger,
 * it never computes money itself. [EarningsEntry.isEstimated] is shown
 * plainly rather than hidden: it means the mileage fee for that one delivery
 * was recomputed from stored coordinates because it predates the column that
 * now persists the real quoted figure.
 */
@Composable
fun RiderEarningsScreen(
    riderViewModel: RiderViewModel,
    onBack: () -> Unit
) {
    val summary by riderViewModel.earningsSummary.collectAsState()
    val history by riderViewModel.earningsHistory.collectAsState()
    val payouts by riderViewModel.payouts.collectAsState()
    val isLoading by riderViewModel.earningsLoading.collectAsState()
    val actionState by riderViewModel.actionState.collectAsState()

    LaunchedEffect(Unit) { riderViewModel.loadEarnings() }

    val hasPendingPayout = payouts.any { it.isPending }

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
                Text("Earnings", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
            }
        }
    ) { padding ->
        if (isLoading && summary == null) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Forest)
            }
            return@Scaffold
        }

        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            // ===== Available to withdraw =====
            ElevatedCard(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = Forest),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                    Text("Available to withdraw", fontSize = 13.sp, color = Color.White.copy(alpha = 0.85f))
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        formatUgx(summary?.available ?: 0.0),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    summary?.commissionRate?.let {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "After AfamFresh's ${it.toInt()}% commission",
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.75f)
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    if (hasPendingPayout) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White.copy(alpha = 0.15f))
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "Payout request pending review",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    } else {
                        Button(
                            onClick = {
                                riderViewModel.clearActionState()
                                riderViewModel.requestPayout()
                            },
                            enabled = (summary?.available ?: 0.0) > 0 && actionState !is RiderActionState.Working,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White)
                        ) {
                            if (actionState is RiderActionState.Working) {
                                CircularProgressIndicator(
                                    color = Forest, strokeWidth = 2.dp, modifier = Modifier.size(18.dp)
                                )
                            } else {
                                Icon(Icons.Default.Payments, contentDescription = null, tint = Forest, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Request payout", color = Forest, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            if (actionState is RiderActionState.Error) {
                Spacer(modifier = Modifier.height(8.dp))
                Text((actionState as RiderActionState.Error).message, color = Tomato, fontSize = 13.sp)
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ===== Today / This week / All time =====
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Today", summary?.today ?: 0.0, Modifier.weight(1f))
                StatTile("This week", summary?.thisWeek ?: 0.0, Modifier.weight(1f))
                StatTile("All time", summary?.allTime ?: 0.0, Modifier.weight(1f))
            }

            Spacer(modifier = Modifier.height(20.dp))
            Text("Delivery history", fontWeight = FontWeight.Bold, color = Ink, fontSize = 15.sp)
            Spacer(modifier = Modifier.height(10.dp))

            when {
                isLoading && history.isEmpty() -> ListSkeleton(rows = 3)
                history.isEmpty() -> EmptyState(
                    icon = Icons.Default.Payments,
                    title = "No earnings yet",
                    detail = "Completed deliveries appear here with what each one paid."
                )
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(history, key = { it.id ?: it.orderId ?: 0 }) { entry ->
                        EarningsRow(entry)
                    }
                    item { Spacer(modifier = Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun StatTile(label: String, amount: Double, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.clip(RoundedCornerShape(14.dp)).background(CardWhite).padding(14.dp)
    ) {
        Text(label, fontSize = 11.sp, color = InkMuted)
        Spacer(modifier = Modifier.height(2.dp))
        Text(formatUgx(amount), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Forest)
    }
}

@Composable
private fun EarningsRow(entry: EarningsEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CardWhite)
            .padding(14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.customerOrDash, fontWeight = FontWeight.SemiBold, color = Ink, fontSize = 14.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Order #${entry.orderId ?: 0}", fontSize = 12.sp, color = InkMuted)
                if (entry.isEstimated == true) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(InkMuted.copy(alpha = 0.12f))
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    ) {
                        Text("Estimated", fontSize = 10.sp, color = InkMuted)
                    }
                }
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(formatUgx(entry.netEarnings ?: 0.0), fontWeight = FontWeight.Bold, color = Forest, fontSize = 14.sp)
            Text(
                if (entry.isPaid == true) "Paid" else "Unpaid",
                fontSize = 11.sp,
                color = if (entry.isPaid == true) Forest else InkMuted
            )
        }
    }
}