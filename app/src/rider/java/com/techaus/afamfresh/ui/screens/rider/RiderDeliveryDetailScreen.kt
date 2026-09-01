package com.techaus.afamfresh.ui.screens.rider

import android.content.Intent
import android.location.Location
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techaus.afamfresh.models.RiderActionState
import com.techaus.afamfresh.services.RiderTrackingService
import com.techaus.afamfresh.ui.components.NetworkImage
import com.techaus.afamfresh.ui.theme.*
import com.techaus.afamfresh.utils.computeDistanceMeters
import com.techaus.afamfresh.utils.formatUgx
import com.techaus.afamfresh.viewmodel.RiderViewModel

@Composable
fun CompleteDeliveryDialog(
    orderId: Int,
    targetLat: Double,
    targetLng: Double,
    currentLocation: Location?,
    onDismiss: () -> Unit,
    onConfirm: (otp: String, lat: Double, lng: Double) -> Unit
) {
    var otpInput by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    val currentLat = currentLocation?.latitude ?: 0.0
    val currentLng = currentLocation?.longitude ?: 0.0
    val distance = remember(currentLocation) {
        computeDistanceMeters(currentLat, currentLng, targetLat, targetLng)
    }
    val isWithinGeofence = distance <= 150.0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Complete Handover #$orderId") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!isWithinGeofence) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Too far: you are ${Math.round(distance).toInt()}m away. Must be within 150m.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }

                Text("Enter the customer's 4-digit handover PIN:")
                OutlinedTextField(
                    value = otpInput,
                    onValueChange = { if (it.length <= 4) otpInput = it },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                localError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (otpInput.length != 4) {
                        localError = "Please enter 4 digits"
                    } else {
                        onConfirm(otpInput, currentLat, currentLng)
                    }
                },
                enabled = isWithinGeofence && otpInput.length == 4
            ) {
                Text("Confirm Delivery")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/**
 * One delivery: who it is for, where, what is in it, and the single button
 * that advances it.
 *
 * The button offers only the next stage. The server enforces the same
 * forward-only order and refuses anything else, so this is a convenience, not
 * the rule itself.
 */
@Composable
fun RiderDeliveryDetailScreen(
    orderId: Int,
    // "order" or "Bulk" — which table orderId belongs to. Passed in from the
    // route rather than assumed: the two id spaces overlap, so the wrong value
    // loads a different customer's delivery.
    source: String = "order",
    riderViewModel: RiderViewModel,
    onBack: () -> Unit,
    /** Opens the in-app follow-camera map, replacing the old geo: hand-off to Google Maps. */
    onNavigate: (Int, String) -> Unit = { _, _ -> }
) {
    val delivery by riderViewModel.selected.collectAsState()
    val actionState by riderViewModel.actionState.collectAsState()
    val context = LocalContext.current
    var showCompleteDialog by remember { mutableStateOf(false) }
    val currentLocation = remember { mutableStateOf<Location?>(null) }

    LaunchedEffect(orderId, source) { riderViewModel.loadDelivery(orderId, source) }

    // Keeps position posts going even after this screen is left, by handing
    // off to a real Service rather than a Compose effect. "delivered" (or any
    // other terminal status) stops it; picked_up/on_way (re)start it — see
    // RiderTrackingService for why "on_way" alone gets the tighter cadence.
    LaunchedEffect(delivery?.status) {
        when (delivery?.status) {
            "picked_up", "on_way" -> RiderTrackingService.start(context, delivery!!.status!!)
            null -> Unit
            else -> RiderTrackingService.stop(context)
        }
    }

    // Photo picker needs no runtime permission and falls back automatically
    // below API 30, so it works at this app's minSdk of 24 — the same choice
    // the avatar flow makes.
    val pickPhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) riderViewModel.uploadProof(orderId, uri, source)
    }

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
                Text("Order #$orderId", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
            }
        }
    ) { padding ->
        val d = delivery
        if (d == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator(color = Forest) }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StageChip(d)
                Spacer(modifier = Modifier.width(8.dp))
                Text(d.orderStatus.orEmpty(), fontSize = 12.sp, color = InkMuted)
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Where to COLLECT. Only Bulk jobs have one: a shop order leaves
            // the warehouse, which every rider already knows, but a Bulk load
            // sits at the vendor's premises and a rider who is not told that
            // drives to the wrong place.
            if (!d.pickupAddress.isNullOrBlank()) {
                Card {
                    Text("Collect from", fontSize = 11.sp, color = InkMuted)
                    Text(d.pickupAddress, fontWeight = FontWeight.SemiBold, color = Ink, fontSize = 15.sp)
                    if (d.isBulk) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Bulk Bulk order — check the weight before you set off.",
                            fontSize = 11.sp,
                            color = InkMuted
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            Card {
                Text(d.customerOrDash, fontWeight = FontWeight.SemiBold, color = Ink, fontSize = 16.sp)
                Spacer(modifier = Modifier.height(2.dp))
                Text(d.addressOrDash, fontSize = 13.sp, color = InkMuted)
                if (!d.area.isNullOrBlank()) {
                    Text(d.area, fontSize = 12.sp, color = InkMuted)
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!d.customerPhone.isNullOrBlank()) {
                        SmallAction(Icons.Default.Call, "Call") {
                            // ACTION_DIAL, not ACTION_CALL: it opens the dialler
                            // with the number filled in and needs no permission.
                            context.startActivity(
                                Intent(Intent.ACTION_DIAL, Uri.parse("tel:${d.customerPhone}"))
                            )
                        }
                    }
                    if (d.destLat != null && d.destLng != null) {
                        SmallAction(Icons.Default.Map, "Navigate") {
                            onNavigate(orderId, source)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Card {
                Text("Items", fontWeight = FontWeight.SemiBold, color = Ink)
                Spacer(modifier = Modifier.height(8.dp))
                val items = d.items.orEmpty()
                if (items.isEmpty()) {
                    Text("No items recorded for this order.", fontSize = 12.sp, color = InkMuted)
                } else {
                    items.forEach { item ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                // quantityLabel, not the raw value: a Bulk
                                // line is decimal kilograms and would otherwise
                                // render as "20.0 ×".
                                "${item.quantityLabel} × ${item.name.orEmpty()}",
                                fontSize = 13.sp,
                                color = Ink,
                                modifier = Modifier.weight(1f)
                            )
                            Text(formatUgx(item.lineTotal), fontSize = 13.sp, color = InkMuted)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Divider(color = PillGray)
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Total to collect", fontWeight = FontWeight.SemiBold, color = Ink)
                    Text(
                        formatUgx(d.totalAmount ?: 0.0),
                        fontWeight = FontWeight.Bold,
                        color = Forest
                    )
                }
                if (!d.paymentStatus.isNullOrBlank()) {
                    Text(
                        "Payment: ${d.paymentStatus}",
                        fontSize = 12.sp,
                        color = if (d.paymentStatus.equals("paid", true)) Forest else Tomato
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Proof of delivery. Offered from the point the rider is actually
            // at the door, not before.
            if (d.status == "on_way" || d.isDelivered) {
                Card {
                    Text("Proof of delivery", fontWeight = FontWeight.SemiBold, color = Ink)
                    Spacer(modifier = Modifier.height(8.dp))
                    if (d.proofPhotoUrl != null) {
                        NetworkImage(
                            model = d.proofPhotoUrl,
                            contentDescription = "Proof of delivery",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp)
                                .clip(RoundedCornerShape(12.dp))
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    SmallAction(
                        Icons.Default.CameraAlt,
                        if (d.proofPhotoUrl != null) "Replace photo" else "Add photo"
                    ) {
                        pickPhoto.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            if (actionState is RiderActionState.Error) {
                Text(
                    (actionState as RiderActionState.Error).message,
                    color = Tomato,
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            val cashConfirm = actionState as? RiderActionState.NeedsCashConfirm
            if (cashConfirm != null) {
                AlertDialog(
                    onDismissRequest = { riderViewModel.clearActionState() },
                    title = { Text("Confirm cash collected") },
                    text = {
                        Text(
                            "Collect ${formatUgx(cashConfirm.amount)} in cash from the " +
                                "customer. Confirm you have received it in full before " +
                                "marking this delivered."
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            riderViewModel.advance(
                                cashConfirm.orderId, "delivered", cashConfirm.source,
                                cashCollected = true
                            )
                        }) { Text("I've collected the cash") }
                    },
                    dismissButton = {
                        TextButton(onClick = { riderViewModel.clearActionState() }) { Text("Not yet") }
                    }
                )
            }

            val next = d.nextStage
            if (next != null) {
                Button(
                    onClick = {
                        riderViewModel.clearActionState()
                        if (next == "delivered") {
                            showCompleteDialog = true
                        } else {
                            riderViewModel.advance(orderId, next, source)
                        }
                    },
                    enabled = actionState !is RiderActionState.Working,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Forest)
                ) {
                    if (actionState is RiderActionState.Working) {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(20.dp)
                        )
                    } else {
                        Text(d.nextStageLabel ?: "Continue", fontWeight = FontWeight.SemiBold)
                    }
                }
            } else {
                Text(
                    "This delivery is complete.",
                    color = Forest,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp
                )
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }

    if (showCompleteDialog && delivery != null) {
        CompleteDeliveryDialog(
            orderId = orderId,
            targetLat = delivery!!.destLat ?: 0.0,
            targetLng = delivery!!.destLng ?: 0.0,
            currentLocation = currentLocation.value,
            onDismiss = { showCompleteDialog = false },
            onConfirm = { otp, lat, lng ->
                showCompleteDialog = false
                riderViewModel.advance(
                    orderId = orderId,
                    status = "delivered",
                    source = source,
                    deliveryOtp = otp,
                    lat = lat,
                    lng = lng
                )
            }
        )
    }
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardWhite)
            .padding(16.dp),
        content = content
    )
}

@Composable
private fun SmallAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(ForestSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = Forest, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(label, color = Forest, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}
