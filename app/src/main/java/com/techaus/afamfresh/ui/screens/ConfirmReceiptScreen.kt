package com.techaus.afamfresh.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techaus.afamfresh.ui.components.StarRating
import com.techaus.afamfresh.ui.theme.*
import com.techaus.afamfresh.utils.prepareJpegBytes
import com.techaus.afamfresh.viewmodel.OrderViewModel
import com.techaus.afamfresh.viewmodel.SurplusViewModel
import kotlinx.coroutines.launch

private data class EmojiOption(val key: String, val glyph: String, val label: String)

private val emojiOptions = listOf(
    EmojiOption("thumbs_up", "👍", "Good"),
    EmojiOption("heart", "❤️", "Loved it"),
    EmojiOption("smile", "😊", "Happy"),
    EmojiOption("fire", "🔥", "Great"),
    EmojiOption("rocket", "🚀", "Fast"),
)

/**
 * The customer's confirm-and-rate step.
 *
 * Reached only once the rider has uploaded proof of delivery (order
 * .deliveryConfirmed / SurplusOrder.deliveryConfirmed) — the server refuses
 * the confirmation otherwise. Everything but tapping "Confirm" is optional:
 * a customer who just wants to close out the order without rating anything
 * can do that.
 *
 * `orderType` is "order" or "surplus" — the same vocabulary tracking.php and
 * the payment_retry route already use, so this fits the existing pattern
 * instead of inventing another word for the same distinction.
 */
@Composable
fun ConfirmReceiptScreen(
    orderId: String,
    orderType: String,
    userId: Int?,
    orderViewModel: OrderViewModel,
    surplusViewModel: SurplusViewModel,
    onBack: () -> Unit,
    onDone: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isSurplus = orderType == "surplus"

    val isLoading by if (isSurplus) {
        surplusViewModel.ordersLoading.collectAsState()
    } else {
        orderViewModel.isLoading.collectAsState()
    }

    var overallRating by remember { mutableStateOf(0) }
    var speedRating by remember { mutableStateOf(0) }
    var professionalismRating by remember { mutableStateOf(0) }
    var packagingRating by remember { mutableStateOf(0) }
    var selectedEmoji by remember { mutableStateOf<String?>(null) }
    var feedback by remember { mutableStateOf("") }
    var photoBytes by remember { mutableStateOf<ByteArray?>(null) }
    var isPreparingPhoto by remember { mutableStateOf(false) }
    var submitError by remember { mutableStateOf<String?>(null) }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            isPreparingPhoto = true
            scope.launch {
                photoBytes = prepareJpegBytes(context, uri)
                isPreparingPhoto = false
            }
        }
    }

    fun submit() {
        submitError = null
        val onResult: (Boolean, String?) -> Unit = { success, error ->
            if (success) onDone() else submitError = error ?: "Could not confirm receipt."
        }
        if (isSurplus) {
            val uid = userId
            if (uid == null) {
                submitError = "Please sign in again."
                return
            }
            surplusViewModel.confirmReceipt(
                orderId = orderId.toIntOrNull() ?: 0,
                userId = uid,
                rating = overallRating.takeIf { it > 0 },
                ratingSpeed = speedRating.takeIf { it > 0 },
                ratingProfessionalism = professionalismRating.takeIf { it > 0 },
                ratingPackaging = packagingRating.takeIf { it > 0 },
                feedback = feedback,
                emojiReaction = selectedEmoji,
                photoBytes = photoBytes,
                onResult = onResult
            )
        } else {
            orderViewModel.confirmReceipt(
                orderId = orderId,
                rating = overallRating.takeIf { it > 0 },
                ratingSpeed = speedRating.takeIf { it > 0 },
                ratingProfessionalism = professionalismRating.takeIf { it > 0 },
                ratingPackaging = packagingRating.takeIf { it > 0 },
                feedback = feedback,
                emojiReaction = selectedEmoji,
                photoBytes = photoBytes,
                onResult = onResult
            )
        }
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
                Text("Confirm receipt", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                "Order #$orderId was delivered",
                fontWeight = FontWeight.Bold,
                color = Ink,
                fontSize = 16.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Let us know how it went. Everything below is optional.",
                fontSize = 13.sp,
                color = InkMuted
            )

            Spacer(Modifier.height(20.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(CardWhite)
                    .padding(16.dp)
            ) {
                Text("Overall rating", fontWeight = FontWeight.Bold, color = Ink)
                Spacer(Modifier.height(8.dp))
                StarRating(value = overallRating, onValueChange = { overallRating = it })

                Spacer(Modifier.height(16.dp))
                Text("How was it?", fontWeight = FontWeight.Bold, color = Ink, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    emojiOptions.forEach { option ->
                        val selected = selectedEmoji == option.key
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (selected) ForestSurface else Color.Transparent)
                                .clickable {
                                    selectedEmoji = if (selected) null else option.key
                                }
                                .padding(8.dp)
                        ) {
                            Text(option.glyph, fontSize = 22.sp)
                            Text(option.label, fontSize = 10.sp, color = InkMuted)
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(CardWhite)
                    .padding(16.dp)
            ) {
                Text("Rate the delivery", fontWeight = FontWeight.Bold, color = Ink)
                Spacer(Modifier.height(12.dp))

                RatingLine("Speed", speedRating) { speedRating = it }
                Spacer(Modifier.height(10.dp))
                RatingLine("Professionalism", professionalismRating) { professionalismRating = it }
                Spacer(Modifier.height(10.dp))
                RatingLine("Packaging", packagingRating) { packagingRating = it }
            }

            Spacer(Modifier.height(16.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(CardWhite)
                    .padding(16.dp)
            ) {
                Text("Comments", fontWeight = FontWeight.Bold, color = Ink)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = feedback,
                    onValueChange = { feedback = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Anything you'd like us to know?") },
                    minLines = 3
                )

                Spacer(Modifier.height(16.dp))
                Text("Photo (optional)", fontWeight = FontWeight.Bold, color = Ink, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))

                val bytes = photoBytes
                Box(
                    modifier = Modifier
                        .size(90.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(ForestSurface)
                        .clickable {
                            photoPicker.launch(
                                androidx.activity.result.PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        isPreparingPhoto -> CircularProgressIndicator(
                            modifier = Modifier.size(24.dp), color = Forest
                        )
                        bytes != null -> {
                            val bitmap = remember(bytes) {
                                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            }
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = "Selected photo",
                                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp))
                                )
                            }
                        }
                        else -> Icon(Icons.Default.AddAPhoto, contentDescription = "Add a photo", tint = Forest)
                    }
                }
            }

            submitError?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = Tomato, fontSize = 13.sp)
            }

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = { submit() },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Forest),
                enabled = !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), color = Color.White)
                } else {
                    Text("CONFIRM RECEIPT", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun RatingLine(label: String, value: Int, onValueChange: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 13.sp, color = InkMuted)
        StarRating(value = value, onValueChange = onValueChange, starSize = 20.dp)
    }
}
