package com.techaus.afamfresh.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techaus.afamfresh.ui.theme.Cream
import com.techaus.afamfresh.ui.theme.Forest
import com.techaus.afamfresh.ui.theme.ForestSurface
import com.techaus.afamfresh.ui.theme.Ink
import com.techaus.afamfresh.ui.theme.InkMuted

/**
 * Shown when the backend explicitly blocks access — either `maintenance_mode`
 * is on, or the config demands a newer app version.
 *
 * This exists because the maintenance message coming back from config.php was
 * previously accepted by MainActivity and then thrown away, so a user hitting
 * maintenance mode saw nothing explaining why the app would not open.
 *
 * Retry re-runs the config check rather than closing the app, so the user can
 * get back in as soon as maintenance ends without force-quitting.
 */
@Composable
fun MaintenanceScreen(
    message: String?,
    isForceUpdate: Boolean,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Cream)
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(ForestSurface),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isForceUpdate) Icons.Default.SystemUpdate else Icons.Default.Build,
                    contentDescription = null,
                    tint = Forest,
                    modifier = Modifier.size(40.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = if (isForceUpdate) "Update required" else "We'll be right back",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Ink,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))

            // The server's own wording wins when it sends one, because it can
            // say something specific ("back at 6pm"). The fallbacks only cover
            // the case where the server blocked access without explaining why.
            Text(
                text = message?.takeIf { it.isNotBlank() }
                    ?: if (isForceUpdate) {
                        "A newer version of AfamFresh is needed to continue. " +
                            "Please update the app from the Play Store."
                    } else {
                        "AfamFresh is briefly down for maintenance. " +
                            "Please try again in a few minutes."
                    },
                fontSize = 15.sp,
                color = InkMuted,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(28.dp))

            Button(
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Forest)
            ) {
                Text("TRY AGAIN", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}
