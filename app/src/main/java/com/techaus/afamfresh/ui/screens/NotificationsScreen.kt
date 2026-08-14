package com.techaus.afamfresh.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techaus.afamfresh.models.AppNotification
import com.techaus.afamfresh.ui.theme.*
import com.techaus.afamfresh.viewmodel.NotificationViewModel

/**
 * In-app notification list with read/unread state.
 *
 * Tapping an order-related notification marks it read and opens that order.
 */
@Composable
fun NotificationsScreen(
    notificationViewModel: NotificationViewModel,
    onBack: () -> Unit,
    onOpenOrder: (String) -> Unit
) {
    val notifications by notificationViewModel.notifications.collectAsState()
    val isLoading by notificationViewModel.isLoading.collectAsState()
    val error by notificationViewModel.error.collectAsState()
    val unread by notificationViewModel.unreadCount.collectAsState()

    LaunchedEffect(Unit) { notificationViewModel.refresh() }

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
                    "Notifications",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                    modifier = Modifier.weight(1f)
                )
                if (unread > 0) {
                    TextButton(onClick = { notificationViewModel.markAllRead() }) {
                        Text("Mark all read", color = Forest, fontSize = 13.sp)
                    }
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                isLoading && notifications.isEmpty() -> {
                    CircularProgressIndicator(
                        color = Forest,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                error != null && notifications.isEmpty() -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(error!!, color = InkMuted, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { notificationViewModel.refresh() },
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Forest)
                        ) {
                            Text("RETRY", color = androidx.compose.ui.graphics.Color.White)
                        }
                    }
                }

                notifications.isEmpty() -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.NotificationsNone,
                            contentDescription = null,
                            tint = InkMuted,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Nothing here yet", fontWeight = FontWeight.SemiBold, color = Ink)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "Order updates and Bulk deals will show up here.",
                            fontSize = 13.sp,
                            color = InkMuted
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(notifications, key = { it.id }) { item ->
                            NotificationRow(
                                notification = item,
                                onClick = {
                                    notificationViewModel.markRead(item.id)
                                    item.orderId?.let(onOpenOrder)
                                }
                            )
                        }
                        item { Spacer(modifier = Modifier.height(24.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationRow(notification: AppNotification, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            // Unread items get the tinted background so they are scannable at
            // a glance rather than relying on the dot alone.
            .background(if (notification.isRead) CardWhite else ForestSurface)
            .clickable { onClick() }
            .padding(14.dp)
    ) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(if (notification.isRead) DividerGray else Forest)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                notification.title,
                fontWeight = if (notification.isRead) FontWeight.Medium else FontWeight.Bold,
                color = Ink,
                fontSize = 15.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(notification.message, color = InkMuted, fontSize = 13.sp)
            notification.createdAt?.let {
                Spacer(modifier = Modifier.height(4.dp))
                Text(it, color = InkMuted, fontSize = 11.sp)
            }
        }
    }
}

