package com.techaus.afamfresh.ui.screens.vendor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.techaus.afamfresh.models.VendorEarning
import com.techaus.afamfresh.models.VendorPayout
import com.techaus.afamfresh.ui.components.EmptyState
import com.techaus.afamfresh.ui.components.ErrorState
import com.techaus.afamfresh.ui.components.ListSkeleton
import com.techaus.afamfresh.ui.theme.*
import com.techaus.afamfresh.utils.formatUgx
import com.techaus.afamfresh.viewmodel.VendorViewModel

/**
 * What the vendor has earned, and how to get it.
 *
 * WHY THIS SCREEN MATTERS MORE THAN IT LOOKS
 *
 * The earnings backend, the commission split and the whole payout approval
 * chain were built and live long before this screen existed. Vendors were
 * being credited for delivered orders with no way to see the money or ask for
 * it — the ledger filled up and nobody could reach it.
 *
 * WHAT THE NUMBERS MEAN
 *
 * A credit lands when a surplus order is DELIVERED, not when it is paid for,
 * and it is the goods value less the vendor's commission rate. The delivery fee
 * is never part of it: that money pays the rider who carried the load.
 *
 * Nothing here calculates anything. Every figure is read from the ledger the
 * server writes once, inside the same transaction as the delivery itself.
 */
@Composable
fun VendorEarningsScreen(
    vendorViewModel: VendorViewModel,
    onBack: () -> Unit
) {
    val summary by vendorViewModel.earningsSummary.collectAsState()
    val earnings by vendorViewModel.earnings.collectAsState()
    val payouts by vendorViewModel.payouts.collectAsState()
    val hasOpenRequest by vendorViewModel.hasOpenPayoutRequest.collectAsState()
    val isLoading by vendorViewModel.earningsLoading.collectAsState()
    val error by vendorViewModel.earningsError.collectAsState()
    val message by vendorViewModel.payoutMessage.collectAsState()
    val requesting by vendorViewModel.requestingPayout.collectAsState()
    val profile by vendorViewModel.profile.collectAsState()

    // Keyed on the profile, not Unit: the vendor id is resolved after sign-in by
    // MainScreen's start(), and firing on Unit could run first and load nothing.
    LaunchedEffect(profile?.id) {
        if (profile != null) vendorViewModel.loadEarnings()
    }

    val available = summary?.pendingEarnings ?: 0.0

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
                Column {
                    Text("Earnings", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Text("From delivered surplus orders", fontSize = 12.sp, color = InkMuted)
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                isLoading && summary == null ->
                    ListSkeleton(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))

                error != null && summary == null ->
                    ErrorState(
                        message = error ?: "",
                        onRetry = { vendorViewModel.loadEarnings() }
                    )

                else -> LazyColumn(
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        BalanceCard(
                            available = available,
                            allTime = summary?.totalNetEarnings ?: 0.0,
                            paid = summary?.paidEarnings ?: 0.0,
                            deliveries = summary?.totalOrders ?: 0
                        )
                    }

                    item {
                        WithdrawSection(
                            available = available,
                            hasOpenRequest = hasOpenRequest,
                            requesting = requesting,
                            onRequest = { vendorViewModel.requestPayout() }
                        )
                    }

                    // An error while the screen already has content has nowhere
                    // else to go — ErrorState only renders on an empty screen.
                    error?.let { e ->
                        item {
                            Text(e, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                        }
                    }

                    message?.let { m ->
                        item {
                            Text(m, color = Forest, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    }

                    if (payouts.isNotEmpty()) {
                        item { SectionHeading("Withdrawals") }
                        items(payouts, key = { "payout-${it.id}" }) { PayoutRow(it) }
                    }

                    item { SectionHeading("Every credit") }

                    if (earnings.isEmpty()) {
                        item {
                            EmptyState(
                                icon = Icons.Default.Payments,
                                title = "Nothing earned yet",
                                detail = "You are credited when a surplus order is delivered, " +
                                    "not when it is paid for. Once one arrives, it shows up here.",
                                modifier = Modifier.height(260.dp)
                            )
                        }
                    } else {
                        items(earnings, key = { "earning-${it.id}" }) { EarningRow(it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun BalanceCard(available: Double, allTime: Double, paid: Double, deliveries: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Forest)
            .padding(20.dp)
    ) {
        Text("Available to withdraw", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            formatUgx(available),
            color = Color.White,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat("Earned all time", formatUgx(allTime))
            Stat("Already paid", formatUgx(paid))
            Stat("Deliveries", deliveries.toString())
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp)
        Text(value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun WithdrawSection(
    available: Double,
    hasOpenRequest: Boolean,
    requesting: Boolean,
    onRequest: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardWhite)
            .padding(16.dp)
    ) {
        // The three states are distinguished deliberately. "Nothing available"
        // and "a request is already in" are completely different situations,
        // and one disabled button with no explanation would leave a vendor
        // guessing which they are in — or requesting again.
        when {
            hasOpenRequest -> {
                Text(
                    "A withdrawal is already being processed",
                    fontWeight = FontWeight.SemiBold,
                    color = Ink,
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "You can request the next one once this has been sent. " +
                        "Earnings from new deliveries keep adding up in the meantime.",
                    fontSize = 12.sp,
                    color = InkMuted
                )
            }

            available <= 0 -> {
                Text(
                    "Nothing to withdraw yet",
                    fontWeight = FontWeight.SemiBold,
                    color = Ink,
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Money becomes available once an order is delivered.",
                    fontSize = 12.sp,
                    color = InkMuted
                )
            }

            else -> {
                Text(
                    "Withdraw ${formatUgx(available)}",
                    fontWeight = FontWeight.SemiBold,
                    color = Ink,
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "The whole available balance is requested at once. An administrator " +
                        "approves it before the money is sent.",
                    fontSize = 12.sp,
                    color = InkMuted
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onRequest,
                    enabled = !requesting,
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Forest)
                ) {
                    if (requesting) {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp)
                        )
                    } else {
                        Text("Request withdrawal", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text,
        fontWeight = FontWeight.SemiBold,
        color = Ink,
        fontSize = 15.sp,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun PayoutRow(payout: VendorPayout) {
    val tone = when (payout.status) {
        "paid" -> Forest
        "rejected" -> Color(0xFFB3261E)
        else -> Ink
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CardWhite)
            .padding(14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatUgx(payout.amount), fontWeight = FontWeight.SemiBold, color = Ink)
            Text(payout.statusLabel, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = tone)
        }
        payout.requestedAt?.let {
            Text("Requested $it", fontSize = 11.sp, color = InkMuted)
        }
        // Carries the admin's reason on a rejection, which is the whole point of
        // requiring one.
        payout.notes?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, fontSize = 12.sp, color = InkMuted)
        }
    }
}

@Composable
private fun EarningRow(earning: VendorEarning) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CardWhite)
            .padding(14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(earning.displayTitle, fontWeight = FontWeight.SemiBold, color = Ink, fontSize = 14.sp)
                Text("Order #${earning.orderId}", fontSize = 11.sp, color = InkMuted)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(formatUgx(earning.netEarnings), fontWeight = FontWeight.Bold, color = Forest)
                Text(
                    if (earning.isPaid) "Paid" else "Available",
                    fontSize = 11.sp,
                    color = if (earning.isPaid) InkMuted else Forest
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        // The split is shown rather than just the net figure. A vendor who can
        // see goods minus commission can check the number against their own
        // records; one shown only the net has to take it on trust.
        Text(
            "${formatUgx(earning.orderAmount)} goods − ${formatUgx(earning.commissionAmount)} commission",
            fontSize = 11.sp,
            color = InkMuted
        )
        earning.createdAt?.let {
            Text(it, fontSize = 10.sp, color = InkMuted)
        }
    }
}