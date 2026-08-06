package com.techaus.afamfresh.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Verified
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
import com.techaus.afamfresh.models.RoleGateState
import com.techaus.afamfresh.ui.theme.*
import com.techaus.afamfresh.viewmodel.RoleGateViewModel

/**
 * Shown by the Rider and Vendor apps when the signed-in account does not
 * (yet) hold this app's role.
 *
 * Installing the Rider app grants nothing: the account registers here, an
 * admin approves it in admin/role-requests.php, and only then does the
 * workspace open. Without this screen an unapproved rider would land on an
 * empty delivery list with no explanation of why.
 *
 * The customer app never shows this — 'user' is every account's baseline.
 */
@Composable
fun RoleGateScreen(
    roleGateViewModel: RoleGateViewModel,
    roleLabel: String,
    onLogout: () -> Unit
) {
    val state by roleGateViewModel.state.collectAsState()
    val reason by roleGateViewModel.reason.collectAsState()
    val canRequest by roleGateViewModel.canRequest.collectAsState()
    val isWorking by roleGateViewModel.isWorking.collectAsState()

    // Re-checked on every entry, so an approval granted while the app was
    // closed is picked up on next launch without needing a push.
    LaunchedEffect(Unit) { roleGateViewModel.refresh() }

    Scaffold(containerColor = Cream) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            val (icon, tint, title, detail) = when (state) {
                RoleGateState.Pending -> Quad(
                    Icons.Default.HourglassEmpty, Color(0xFFFFA000),
                    "Waiting for approval",
                    "Your request to join as a $roleLabel has been sent. " +
                        "An administrator will review it shortly — check back soon."
                )
                RoleGateState.Rejected -> Quad(
                    Icons.Default.Cancel, Tomato,
                    "Request not approved",
                    reason ?: "Your request to join as a $roleLabel was not approved. " +
                        "Contact AfamFresh if you think this is a mistake."
                )
                RoleGateState.Approved -> Quad(
                    Icons.Default.Verified, Forest,
                    "You're approved",
                    "Your $roleLabel account is ready."
                )
                else -> Quad(
                    Icons.Default.HourglassEmpty, InkMuted,
                    "Join as a $roleLabel",
                    "This app is for AfamFresh ${roleLabel}s. Send a request and an " +
                        "administrator will review your account."
                )
            }

            Box(
                modifier = Modifier.size(84.dp).clip(CircleShape).background(tint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(40.dp))
            }

            Spacer(modifier = Modifier.height(20.dp))
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
            Spacer(modifier = Modifier.height(10.dp))
            Text(detail, fontSize = 14.sp, color = InkMuted, textAlign = TextAlign.Center)

            // Surfaced verbatim from canRequestRole() — most usefully
            // "you can only have one additional role", which is a rule the
            // person needs to understand, not a generic failure.
            if (!canRequest && state == RoleGateState.None && reason != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(reason ?: "", fontSize = 13.sp, color = Tomato, textAlign = TextAlign.Center)
            }

            Spacer(modifier = Modifier.height(28.dp))

            if (state == RoleGateState.None && canRequest) {
                Button(
                    onClick = { roleGateViewModel.request() },
                    enabled = !isWorking,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Forest)
                ) {
                    if (isWorking) {
                        CircularProgressIndicator(
                            color = Color.White, strokeWidth = 2.dp,
                            modifier = Modifier.size(20.dp)
                        )
                    } else {
                        Text("Request $roleLabel access", fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            if (state == RoleGateState.Pending) {
                OutlinedButton(
                    onClick = { roleGateViewModel.refresh() },
                    enabled = !isWorking,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Check again", color = Forest, fontWeight = FontWeight.SemiBold)
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            TextButton(onClick = onLogout) {
                Text("Sign out", color = InkMuted, fontSize = 14.sp)
            }
        }
    }
}

/** Local 4-tuple; Kotlin's stdlib stops at Triple. */
private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

private operator fun <A, B, C, D> Quad<A, B, C, D>.component1() = a
private operator fun <A, B, C, D> Quad<A, B, C, D>.component2() = b
private operator fun <A, B, C, D> Quad<A, B, C, D>.component3() = c
private operator fun <A, B, C, D> Quad<A, B, C, D>.component4() = d
