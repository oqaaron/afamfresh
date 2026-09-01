package com.techaus.afamfresh.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techaus.afamfresh.ui.components.NumericKeypad
import com.techaus.afamfresh.ui.theme.Forest
import com.techaus.afamfresh.ui.theme.Tomato
import kotlinx.coroutines.delay

private const val CODE_LENGTH = 6
private const val RESEND_COOLDOWN_SECONDS = 60 // matches send_phone_otp's per-mobile rate limit window loosely — not exact, just keeps the button disabled long enough that a tap during the real cooldown is rare rather than guaranteed

/**
 * Second screen of the phone sign-in/signup flow — enter the code just
 * texted to the number from PhoneEntryScreen. No mockup existed for this
 * one; styled to match PhoneEntryScreen's brand mark / headline / keypad
 * layout rather than inventing an unrelated look.
 */
@Composable
fun OtpEntryScreen(
    mobileDisplay: String, // e.g. "+256 712 345 678", for the "sent to" line
    onBack: () -> Unit,
    onVerify: (code: String) -> Unit,
    onResend: () -> Unit,
    isLoading: Boolean,
    errorMessage: String?
) {
    var code by remember { mutableStateOf("") }
    var secondsRemaining by remember { mutableStateOf(RESEND_COOLDOWN_SECONDS) }

    LaunchedEffect(Unit) {
        while (secondsRemaining > 0) {
            delay(1000)
            secondsRemaining -= 1
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 24.dp)
            ) {
                Spacer(modifier = Modifier.height(8.dp))

                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.height(28.dp))

                Text(
                    text = "Enter verification code",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "We sent a code to $mobileDisplay",
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(36.dp))

                // CODE_LENGTH boxes, one per digit -- filled boxes read the
                // entered digit back clearly at a glance, more legible for a
                // 6-digit code than a single running text field would be.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    for (i in 0 until CODE_LENGTH) {
                        val filled = i < code.length
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(
                                    if (filled) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surface
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (filled) code[i].toString() else "",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = Forest
                            )
                        }
                    }
                }

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(text = errorMessage, color = Tomato, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }

                Spacer(modifier = Modifier.height(28.dp))

                Button(
                    onClick = { onVerify(code) },
                    enabled = code.length == CODE_LENGTH && !isLoading,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Forest, disabledContainerColor = Forest.copy(alpha = 0.4f))
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Verify", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (secondsRemaining > 0) {
                    Text(
                        text = "Resend code in ${secondsRemaining}s",
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Text(
                        text = "Resend code",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        color = Forest,
                        modifier = Modifier.fillMaxWidth().clickable {
                            onResend()
                            secondsRemaining = RESEND_COOLDOWN_SECONDS
                            code = ""
                        }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            NumericKeypad(
                onDigit = { d -> if (code.length < CODE_LENGTH) code += d },
                onBackspace = { if (code.isNotEmpty()) code = code.dropLast(1) }
            )
        }
    }
}
