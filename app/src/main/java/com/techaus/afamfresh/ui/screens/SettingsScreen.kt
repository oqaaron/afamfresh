package com.techaus.afamfresh.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.imageLoader
import com.techaus.afamfresh.BuildConfig
import com.techaus.afamfresh.ui.theme.*
import com.techaus.afamfresh.utils.FirebaseTokenManager
import com.techaus.afamfresh.viewmodel.AuthViewModel

/**
 * Settings. Everything here does something real — nothing is a stub.
 *
 * The delete-account option that used to live at the bottom has been removed
 * rather than left in place: there is no delete-account endpoint, and a
 * destructive-looking button that silently does nothing is worse than no
 * button. Add it back when `auth.php?action=delete_account` exists.
 */
@Composable
fun SettingsScreen(
    authViewModel: AuthViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val user by authViewModel.user.collectAsState()

    // Seed from the server's stored preference where we have it, falling back
    // to the device-local FCM flag. The two are different things: the local
    // flag controls whether this install registers a token at all, while the
    // server preference is what NotificationManager consults before sending.
    // Both have to agree or a user can switch push "off" and still receive it.
    var notificationsEnabled by remember(user?.notificationPreferences?.push) {
        mutableStateOf(user?.notificationPreferences?.push ?: FirebaseTokenManager.areNotificationsEnabled())
    }
    var emailNotificationsEnabled by remember(user?.notificationPreferences?.email) {
        mutableStateOf(user?.notificationPreferences?.email ?: true)
    }
    var cacheCleared by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Cream,
        topBar = {
            Row(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Ink)
                }
                Text("Settings", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {

            SettingsToggleRow(
                title = "Push notifications",
                subtitle = "Order updates and surplus deals",
                checked = notificationsEnabled,
                onCheckedChange = { enabled ->
                    notificationsEnabled = enabled
                    // Two writes, deliberately. The local one persists the
                    // preference and registers/stops registering this device's
                    // FCM token; the server one is what actually stops messages
                    // being sent. Doing only the first left the server happily
                    // dispatching to a user who had opted out.
                    FirebaseTokenManager.setNotificationsEnabled(enabled)
                    authViewModel.setNotificationPrefs(
                        email = emailNotificationsEnabled,
                        push = enabled
                    )
                }
            )

            SettingsToggleRow(
                title = "Email notifications",
                subtitle = "Order receipts and account updates",
                checked = emailNotificationsEnabled,
                onCheckedChange = { enabled ->
                    emailNotificationsEnabled = enabled
                    authViewModel.setNotificationPrefs(
                        email = enabled,
                        push = notificationsEnabled
                    )
                }
            )

            Spacer(modifier = Modifier.height(24.dp))
            Text("Account", fontWeight = FontWeight.SemiBold, color = Ink, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(8.dp))
            SettingsInfoRow(label = "Signed in as", value = user?.email ?: "Not signed in")
            user?.currentRole?.let {
                SettingsInfoRow(label = "Role", value = it.replaceFirstChar { c -> c.uppercase() })
            }
            user?.isGoogleAccount?.let {
                SettingsInfoRow(label = "Signed in with", value = if (it) "Google" else "Email and password")
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text("Storage", fontWeight = FontWeight.SemiBold, color = Ink, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Clear image cache", color = Ink, fontWeight = FontWeight.Medium)
                    Text(
                        if (cacheCleared) "Cleared" else "Frees space used by product photos",
                        color = if (cacheCleared) Forest else InkMuted,
                        fontSize = 12.sp
                    )
                }
                TextButton(
                    enabled = !cacheCleared,
                    onClick = {
                        // Coil owns every remote image in the app, so clearing
                        // its caches is the whole job. No endpoint needed.
                        context.imageLoader.memoryCache?.clear()
                        context.imageLoader.diskCache?.clear()
                        cacheCleared = true
                    }
                ) {
                    Text(if (cacheCleared) "Done" else "Clear", color = if (cacheCleared) InkMuted else Forest)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text("About", fontWeight = FontWeight.SemiBold, color = Ink, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(8.dp))
            // Read from the build rather than hardcoded, so it cannot drift out
            // of step with what was actually shipped.
            SettingsInfoRow(label = "App version", value = BuildConfig.VERSION_NAME)
            SettingsInfoRow(label = "Build", value = BuildConfig.VERSION_CODE.toString())
        }
    }
}

@Composable
private fun SettingsToggleRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Ink, fontWeight = FontWeight.Medium)
            Text(subtitle, color = InkMuted, fontSize = 12.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Forest)
        )
    }
}

@Composable
private fun SettingsInfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = InkMuted, fontSize = 14.sp)
        Text(value, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}
