package com.techaus.afamfresh.ui.screens.rider

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techaus.afamfresh.ui.components.EmptyState
import com.techaus.afamfresh.ui.components.ListSkeleton
import com.techaus.afamfresh.ui.theme.*
import com.techaus.afamfresh.viewmodel.RiderViewModel

/**
 * Every delivery assigned to this rider, active first then completed.
 *
 * The split comes from the server rather than being derived here, so the two
 * sections cannot disagree with the status the server considers current.
 */
@Composable
fun RiderDeliveriesScreen(
    riderViewModel: RiderViewModel,
    // (orderId, source) — the source has to travel with the id, since the
    // shop and Bulk id spaces overlap.
    onDeliveryClick: (Int, String) -> Unit,
    onBack: () -> Unit
) {
    val active by riderViewModel.active.collectAsState()
    val history by riderViewModel.history.collectAsState()
    val isLoading by riderViewModel.isLoading.collectAsState()

    LaunchedEffect(Unit) { riderViewModel.refresh() }

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
                Text("My deliveries", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            if (isLoading && active.isEmpty() && history.isEmpty()) {
                ListSkeleton(rows = 4)
                return@Column
            }

            if (active.isEmpty() && history.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.LocalShipping,
                    title = "No deliveries yet",
                    detail = "Deliveries assigned to you by an admin will show up here."
                )
                return@Column
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (active.isNotEmpty()) {
                    item { SectionHeader("Active (${active.size})") }
                    items(active, key = { "a-${it.assignmentId ?: it.orderId}" }) { d ->
                        DeliveryRow(delivery = d, onClick = { d.orderId?.let { id -> onDeliveryClick(id, d.source) } })
                    }
                }
                if (history.isNotEmpty()) {
                    item { SectionHeader("Completed (${history.size})") }
                    items(history, key = { "h-${it.assignmentId ?: it.orderId}" }) { d ->
                        DeliveryRow(delivery = d, onClick = { d.orderId?.let { id -> onDeliveryClick(id, d.source) } })
                    }
                }
                item { Spacer(modifier = Modifier.height(40.dp)) }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Column {
        Spacer(modifier = Modifier.height(6.dp))
        Text(text, fontWeight = FontWeight.Bold, color = Ink, fontSize = 15.sp)
        Spacer(modifier = Modifier.height(4.dp))
    }
}
