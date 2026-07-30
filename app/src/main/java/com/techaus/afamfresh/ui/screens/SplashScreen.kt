package com.techaus.afamfresh.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techaus.afamfresh.R
import com.techaus.afamfresh.repository.AppRepository
import com.techaus.afamfresh.repository.AuthRepository
import com.techaus.afamfresh.ui.theme.Forest
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

// Signature confirmed by MainActivity.kt:
//   SplashScreen(authRepository, appRepository, onConfigChecked = { proceed, maintenanceMessage, forceUpdate -> ... })
//
// ===== About the `shouldProceed` override in MainActivity =====
// MainActivity currently has a comment saying the splash's proceed result is
// "overridden to true" so login isn't blocked. That workaround exists because
// a config check that fails closed will lock every user out whenever the
// backend is unreachable or config.php doesn't exist yet.
//
// This implementation fails OPEN instead:
//   - config request errors / times out  -> proceed = true
//   - config.php missing entirely        -> proceed = true
//   - maintenance_mode explicitly true   -> proceed = false (the only block)
//
// With that behaviour, the override in MainActivity is no longer needed — you
// can delete `shouldProceed = true` and honour the real `proceed` value. I've
// NOT changed MainActivity myself, since that's a live behaviour change to your
// app's gating logic and should be your call.
@Composable
fun SplashScreen(
    authRepository: AuthRepository,
    appRepository: AppRepository,
    onConfigChecked: (proceed: Boolean, maintenanceMessage: String?, forceUpdate: Boolean) -> Unit
) {
    LaunchedEffect(Unit) {
        // Minimum visible duration so the splash doesn't flash-and-vanish.
        val minimumDisplay = async { delay(1200) }

        // Hard ceiling on the network call — a hanging request must never
        // hold the user on the splash screen indefinitely.
        val config = withTimeoutOrNull(5000L) {
            suspendCancellableCoroutine { cont ->
                appRepository.getAppConfig { result -> cont.resume(result) }
            }
        }

        minimumDisplay.await()

        if (config == null) {
            // No config, unreachable backend, or timeout -> let the user in.
            onConfigChecked(true, null, false)
        } else {
            val proceed = !config.maintenanceMode
            onConfigChecked(proceed, config.maintenanceMessage, config.forceUpdate)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Forest),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(id = R.drawable.logo),
                contentDescription = "AfamFresh",
                modifier = Modifier
                    .size(120.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .padding(16.dp)
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "AfamFresh",
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Fresh produce delivered",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 13.sp
            )
            Spacer(modifier = Modifier.height(28.dp))
            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(28.dp))
        }
    }
}
