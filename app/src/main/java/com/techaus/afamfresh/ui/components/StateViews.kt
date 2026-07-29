package com.techaus.afamfresh.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techaus.afamfresh.ui.theme.Forest
import com.techaus.afamfresh.ui.theme.Ink
import com.techaus.afamfresh.ui.theme.InkMuted

/**
 * The two non-happy states every list screen needs, in one place so they look
 * and behave the same everywhere.
 *
 * Several screens previously showed a bare spinner, an unexplained blank area,
 * or nothing at all — which left "loading", "empty" and "broken" visually
 * identical.
 */

/**
 * Shown when a load failed.
 *
 * [onRetry] is omitted for failures where retrying cannot help (a 403, say),
 * so the user is not invited to fail repeatedly — this is what the ViewModels'
 * `canRetry` flag drives.
 */
@Composable
fun ErrorState(
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Default.CloudOff,
    onRetry: (() -> Unit)? = null
) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = null, tint = InkMuted, modifier = Modifier.size(48.dp))
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            message,
            color = InkMuted,
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )
        if (onRetry != null) {
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = onRetry,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Forest)
            ) {
                Text("TRY AGAIN", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * Shown when a load succeeded but returned nothing.
 *
 * [actionLabel]/[onAction] give the user somewhere to go — an empty cart that
 * links to browsing is more useful than one that just says "empty".
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = null, tint = InkMuted, modifier = Modifier.size(48.dp))
        Spacer(modifier = Modifier.height(14.dp))
        Text(title, fontWeight = FontWeight.SemiBold, color = Ink, fontSize = 16.sp)
        Spacer(modifier = Modifier.height(6.dp))
        Text(detail, color = InkMuted, fontSize = 13.sp, textAlign = TextAlign.Center)

        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = onAction,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Forest)
            ) {
                Text(actionLabel, color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}
