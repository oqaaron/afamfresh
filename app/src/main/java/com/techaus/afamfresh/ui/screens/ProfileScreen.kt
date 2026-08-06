package com.techaus.afamfresh.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techaus.afamfresh.models.addressOrEmpty
import com.techaus.afamfresh.models.areaOrEmpty
import com.techaus.afamfresh.models.firstNameOrDerived
import com.techaus.afamfresh.models.lastNameOrDerived
import com.techaus.afamfresh.ui.components.NetworkImage
import com.techaus.afamfresh.ui.theme.*
import com.techaus.afamfresh.viewmodel.AuthViewModel

/**
 * The account overview: who you are, and the way in to changing it.
 *
 * The detail rows read from the server rather than only from the cache — see
 * the refresh below. Editing itself lives on EditProfileScreen, so this screen
 * stays a summary and does not turn into a form.
 */
@Composable
fun ProfileScreen(
    authViewModel: AuthViewModel,
    onLogout: () -> Unit,
    onBack: () -> Unit,
    onOrdersClick: () -> Unit,
    onAddressesClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onEditProfileClick: () -> Unit,
    onChangePasswordClick: () -> Unit,
    /**
     * Whether to show the shopping entries (My Orders, Addresses).
     *
     * False in the Rider and Vendor apps, where those routes are not even
     * registered — a rider gets their profile and nothing that leads back into
     * the shop.
     */
    showCustomerActions: Boolean = true
) {
    val user by authViewModel.user.collectAsState()
    val avatarUploading by authViewModel.avatarUploading.collectAsState()
    val avatarError by authViewModel.avatarError.collectAsState()
    var showLogoutConfirm by remember { mutableStateOf(false) }
    var showRemovePhoto by remember { mutableStateOf(false) }

    // Pull the profile from the server on entry. Without this the screen shows
    // whatever the cache held at sign-in, so an edit made on another device —
    // or the loyalty points, which only the server knows — never appeared.
    LaunchedEffect(Unit) { authViewModel.refreshUser() }

    val photoPicker = rememberLauncherForActivityResult(
        // PickVisualMedia needs no runtime permission and falls back to
        // OPEN_DOCUMENT below API 30, so this is safe at minSdk 24.
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { authViewModel.uploadAvatar(it) } }

    Scaffold(
        containerColor = Cream,
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Ink)
                }
                Text("Profile", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            // ===== Identity card: avatar, name, contact, role badge =====
            ElevatedCard(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = CardWhite),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box {
                        Box(
                            modifier = Modifier
                                .size(88.dp)
                                .clip(CircleShape)
                                .background(ForestSurface)
                                .clickable(enabled = !avatarUploading) {
                                    photoPicker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            val avatar = user?.avatarUrl
                            when {
                                avatarUploading -> CircularProgressIndicator(
                                    modifier = Modifier.size(28.dp), color = Forest, strokeWidth = 2.dp
                                )
                                !avatar.isNullOrBlank() -> NetworkImage(
                                    model = avatar,
                                    contentDescription = "Profile picture",
                                    modifier = Modifier.fillMaxSize().clip(CircleShape)
                                )
                                else -> Icon(
                                    Icons.Default.Person, contentDescription = null,
                                    tint = Forest, modifier = Modifier.size(40.dp)
                                )
                            }
                        }
                        // Camera badge, so the avatar is visibly tappable rather
                        // than a hidden affordance. 32dp keeps it a real touch
                        // target rather than a decorative dot.
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .align(Alignment.BottomEnd)
                                .clip(CircleShape)
                                .background(Forest)
                                .clickable(enabled = !avatarUploading) {
                                    photoPicker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.CameraAlt, contentDescription = "Change photo",
                                tint = Color.White, modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        user?.name?.takeIf { it.isNotBlank() } ?: "Guest",
                        fontWeight = FontWeight.Bold, fontSize = 19.sp, color = Ink
                    )

                    // account_type is fixed per install (Customer/Rider/Vendor
                    // apps are separate flavors) — surfacing it here is new,
                    // and confirms at a glance which account you're signed into.
                    user?.accountType?.let { type ->
                        Spacer(modifier = Modifier.height(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(ForestSurface)
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text(
                                type.replaceFirstChar { it.uppercase() },
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Forest
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(user?.email ?: "", fontSize = 13.sp, color = InkMuted)
                    user?.mobile?.let { Text(it, fontSize = 13.sp, color = InkMuted) }

                    if (!user?.avatarUrl.isNullOrBlank()) {
                        TextButton(
                            onClick = { showRemovePhoto = true },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text("Remove photo", fontSize = 12.sp, color = Tomato)
                        }
                    }

                    avatarError?.let {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(it, color = Tomato, fontSize = 12.sp, textAlign = TextAlign.Center)
                    }
                }
            }
            // The role switcher was removed here. Customer, Rider and Vendor
            // are now separate installs (see the flavors in build.gradle.kts),
            // so an account uses the app for its role and there is nothing to
            // switch between. users.current_role and user_roles still exist and
            // still govern what the server permits.

            // ===== Account details =====
            Spacer(modifier = Modifier.height(20.dp))
            SectionLabel("Account details")
            Spacer(modifier = Modifier.height(8.dp))
            ElevatedCard(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = CardWhite),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) {
                    ProfileInfoRow("First name", user?.firstNameOrDerived)
                    ProfileInfoRow("Last name", user?.lastNameOrDerived)
                    ProfileInfoRow("Email", user?.email)
                    ProfileInfoRow("Mobile", user?.mobile, isLast = !showCustomerActions)
                    // Where an order is delivered TO, and points earned by
                    // buying, are customer concepts. A rider has neither, so
                    // showing them their own blank "Delivery address" was just
                    // confusing.
                    if (showCustomerActions) {
                        val points = user?.loyaltyPoints
                        // areaOrEmpty/addressOrEmpty map the 'Not specified'
                        // sentinel from registration to a blank, so it reads
                        // "Not set" rather than the literal sentinel text.
                        ProfileInfoRow("Delivery area", user?.areaOrEmpty)
                        ProfileInfoRow("Delivery address", user?.addressOrEmpty, isLast = points == null)
                        points?.let { ProfileInfoRow("Loyalty points", it.toString(), isLast = true) }
                    }
                }
            }

            // ===== Actions =====
            Spacer(modifier = Modifier.height(20.dp))
            SectionLabel("Account")
            Spacer(modifier = Modifier.height(8.dp))
            ElevatedCard(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = CardWhite),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(vertical = 6.dp)) {
                    ProfileMenuItem(icon = Icons.Default.Edit, label = "Edit profile", onClick = onEditProfileClick)
                    ProfileMenuItem(icon = Icons.Default.Lock, label = "Change password", onClick = onChangePasswordClick)
                    if (showCustomerActions) {
                        ProfileMenuItem(icon = Icons.Default.Receipt, label = "My Orders", onClick = onOrdersClick)
                        ProfileMenuItem(icon = Icons.Default.LocationOn, label = "Addresses", onClick = onAddressesClick)
                    }
                    ProfileMenuItem(icon = Icons.Default.Settings, label = "Settings", onClick = onSettingsClick, isLast = true)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            ElevatedCard(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = CardWhite),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
            ) {
                ProfileMenuItem(
                    icon = Icons.Default.Logout,
                    label = "Log out",
                    tint = Tomato,
                    onClick = { showLogoutConfirm = true },
                    isLast = true
                )
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }

    if (showRemovePhoto) {
        AlertDialog(
            onDismissRequest = { showRemovePhoto = false },
            title = { Text("Remove photo?") },
            text = { Text("Your profile picture will be removed.") },
            confirmButton = {
                TextButton(onClick = {
                    showRemovePhoto = false
                    authViewModel.removeAvatar()
                }) {
                    Text("Remove", color = Tomato, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemovePhoto = false }) { Text("Cancel") }
            }
        )
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

/** Small caps-style header above a card, matching Material's section-label convention. */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        color = InkMuted,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        modifier = Modifier.padding(start = 4.dp)
    )
}

/**
 * Label/value row inside a card. A blank value reads as "Not set" rather than
 * an empty gap. [isLast] suppresses the divider so the card's final row
 * doesn't draw a line against its own bottom edge.
 */
@Composable
private fun ProfileInfoRow(label: String, value: String?, isLast: Boolean = false) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Text(label, color = InkMuted, fontSize = 14.sp)
            Spacer(modifier = Modifier.width(16.dp))
            val blank = value.isNullOrBlank()
            Text(
                if (blank) "Not set" else value!!,
                color = if (blank) InkMuted else Ink,
                fontSize = 14.sp,
                fontWeight = if (blank) FontWeight.Normal else FontWeight.Medium,
                textAlign = TextAlign.End
            )
        }
        if (!isLast) HorizontalDivider(color = DividerGray)
    }
}

/**
 * Tappable row with a real Material ripple (via `clickable`, not a custom
 * hover state — there is no hover on a touchscreen) and a 48dp+ touch target.
 */
@Composable
private fun ProfileMenuItem(
    icon: ImageVector,
    label: String,
    tint: Color = Ink,
    onClick: () -> Unit,
    isLast: Boolean = false
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(horizontal = 18.dp, vertical = 14.dp)
                .heightIn(min = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Icon sits in a soft tinted circle rather than bare, which is
                // what made the mockup's toggle rows read as considered rather
                // than a plain list — reused here for menu rows too.
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (tint == Tomato) Tomato.copy(alpha = 0.1f) else ForestSurface),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(19.dp))
                }
                Spacer(modifier = Modifier.width(14.dp))
                Text(label, color = if (tint == Tomato) tint else Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
            if (tint == Ink) {
                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = InkMuted, modifier = Modifier.size(20.dp))
            }
        }
        if (!isLast) HorizontalDivider(color = DividerGray, modifier = Modifier.padding(start = 68.dp))
    }
}
