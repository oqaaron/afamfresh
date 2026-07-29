package com.techaus.afamfresh.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techaus.afamfresh.ui.theme.*
import com.techaus.afamfresh.viewmodel.AuthViewModel

// ⚠️ INFERRED screen. Signature matches MainScreen.kt's composable("profile")
// call exactly: ProfileScreen(authViewModel, onLogout, onBack, onOrdersClick,
// onAddressesClick, onSettingsClick).
//
// Role-switch row only shows if the user has more than one role — this uses
// AuthViewModel.switchRole(), which already exists and is fully wired to
// AuthRepository/SharedPreferences.
@Composable
fun ProfileScreen(
    authViewModel: AuthViewModel,
    onLogout: () -> Unit,
    onBack: () -> Unit,
    onOrdersClick: () -> Unit,
    onAddressesClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    val user by authViewModel.user.collectAsState()
    var showLogoutConfirm by remember { mutableStateOf(false) }

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
                Text("Profile", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {

            // ===== Avatar + name/email =====
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(ForestSurface),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Person, contentDescription = null, tint = Forest, modifier = Modifier.size(32.dp))
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Text(user?.name ?: "Guest", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Ink)
                    Text(user?.email ?: "", fontSize = 13.sp, color = InkMuted)
                    user?.mobile?.let { Text(it, fontSize = 13.sp, color = InkMuted) }
                }
            }

            // ===== Role switcher (only if more than one role available) =====
            val roles = user?.roles ?: emptyList()
            if (roles.size > 1) {
                Spacer(modifier = Modifier.height(20.dp))
                Text("Switch role", fontWeight = FontWeight.SemiBold, color = Ink, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    roles.forEach { role ->
                        val selected = role == user?.currentRole
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(if (selected) Forest else PillGray)
                                .clickable { authViewModel.switchRole(role) {} }
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text(
                                role.replaceFirstChar { it.uppercase() },
                                color = if (selected) Color.White else Ink,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            ProfileMenuItem(icon = Icons.Default.Receipt, label = "My Orders", onClick = onOrdersClick)
            ProfileMenuItem(icon = Icons.Default.LocationOn, label = "Addresses", onClick = onAddressesClick)
            ProfileMenuItem(icon = Icons.Default.Settings, label = "Settings", onClick = onSettingsClick)

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = DividerGray)
            Spacer(modifier = Modifier.height(16.dp))

            ProfileMenuItem(
                icon = Icons.Default.Logout,
                label = "Log out",
                tint = Tomato,
                onClick = { showLogoutConfirm = true }
            )
        }
    }

    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text("Log out?") },
            text = { Text("You'll need to sign in again to place new orders.") },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutConfirm = false
                    onLogout()
                }) {
                    Text("Log out", color = Tomato, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun ProfileMenuItem(
    icon: ImageVector,
    label: String,
    tint: Color = Ink,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = tint)
            Spacer(modifier = Modifier.width(14.dp))
            Text(label, color = tint, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
        if (tint == Ink) {
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = InkMuted)
        }
    }
}
