package com.techaus.afamfresh.ui.screens

import androidx.compose.foundation.Image
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techaus.afamfresh.R
import com.techaus.afamfresh.ui.components.NumericKeypad
import com.techaus.afamfresh.ui.theme.Forest
import com.techaus.afamfresh.ui.theme.Tomato

// Uganda, not the +44 shown in the mockup — that was placeholder/template
// content, not this app's actual locale. Matches normaliseUgandanMsisdn()
// on the backend, which is what actually validates this number server-side.
private const val COUNTRY_CODE = "+256"
private const val MAX_DIGITS = 9 // e.g. 7XX XXX XXX, after the country code

/**
 * First screen of the phone sign-in/signup flow. Purely presentational —
 * owns only the digits being typed; onContinue hands the assembled number
 * up to whatever's driving the actual send_phone_otp call.
 */
@Composable
fun PhoneEntryScreen(
    onBack: () -> Unit,
    onContinue: (fullNumber: String) -> Unit,
    onTermsClick: () -> Unit,
    isLoading: Boolean,
    errorMessage: String?
) {
    var digits by remember { mutableStateOf("") }

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

                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    // Real brand_mark asset, not the Icons.Default.Eco
                    // placeholder this screen was built with originally —
                    // that stood in for an asset I didn't know existed yet.
                    // Same circular treatment as SplashScreen/OnboardingScreen
                    // (and closer to the original mockup's own circular leaf
                    // icon than LoginScreen's rounded-square logo would be).
                    // Background stays colorScheme-driven rather than
                    // switching to Onboarding's hardcoded ForestSurface —
                    // that hardcoding predates the dark-mode migration this
                    // screen was already built with.
                    Image(
                        painter = painterResource(id = R.drawable.brand_mark),
                        contentDescription = "AfamFresh",
                        modifier = Modifier.size(42.dp)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "Enter your mobile number",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "We will send you a verification code",
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(36.dp))

                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = COUNTRY_CODE,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = formatDisplay(digits),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (digits.isEmpty())
                            MaterialTheme.colorScheme.onSurfaceVariant
                        else
                            MaterialTheme.colorScheme.onBackground
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = errorMessage,
                        color = Tomato,
                        fontSize = 13.sp
                    )
                }

                Spacer(modifier = Modifier.height(28.dp))

                Button(
                    onClick = { onContinue(COUNTRY_CODE + digits) },
                    enabled = digits.length == MAX_DIGITS && !isLoading,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Forest, disabledContainerColor = Forest.copy(alpha = 0.4f))
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Continue", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // AnnotatedString would let "terms of use" alone be tappable,
                // but that's a bigger change to test carefully against
                // exactly how this app already renders links elsewhere (the
                // Create Account screen's "By continuing..." text, not shown
                // to me in full). Keeping the whole line tappable is a safe,
                // if slightly less precise, default until that's confirmed.
                Text(
                    text = "By clicking on \"Continue\" you are agreeing to our terms of use",
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().clickable { onTermsClick() }
                )

                Spacer(modifier = Modifier.height(16.dp))
            }

            NumericKeypad(
                onDigit = { d -> if (digits.length < MAX_DIGITS) digits += d },
                onBackspace = { if (digits.isNotEmpty()) digits = digits.dropLast(1) }
            )
        }
    }
}

/** "712" -> "(712", "712345" -> "(712) 345", "712345678" -> "(712) 345-678"
 *  — grows the grouping as digits are typed, matching the mockup's format
 *  without padding with fake zeros for digits not yet entered. */
private fun formatDisplay(digits: String): String {
    if (digits.isEmpty()) return "(000) 000-000"
    val sb = StringBuilder("(")
    for (i in digits.indices) {
        if (i == 3) sb.append(") ")
        if (i == 6) sb.append("-")
        sb.append(digits[i])
    }
    return sb.toString()
}
