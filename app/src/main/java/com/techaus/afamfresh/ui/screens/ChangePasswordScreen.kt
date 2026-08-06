package com.techaus.afamfresh.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techaus.afamfresh.models.ProfileSaveState
import com.techaus.afamfresh.ui.theme.*
import com.techaus.afamfresh.viewmodel.AuthViewModel

/**
 * Change the password of the account that is already signed in.
 *
 * Distinct from the forgot-password flow, which is for people who cannot sign
 * in at all. A Google account has no password to verify, so rather than a form
 * that can only fail, it gets the server's explanation and a pointer to the
 * reset flow — which does work for those accounts, since reset_password sets
 * the column unconditionally.
 *
 * Ends on a success pane rather than popping silently: changing a password
 * produces no other visible evidence, so a quiet return reads as a no-op.
 */
@Composable
fun ChangePasswordScreen(
    authViewModel: AuthViewModel,
    onBack: () -> Unit
) {
    val user by authViewModel.user.collectAsState()
    val saveState by authViewModel.passwordSaveState.collectAsState()

    var current by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var showPasswords by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }

    val saving = saveState is ProfileSaveState.Saving
    val saved = saveState is ProfileSaveState.Saved
    val serverError = (saveState as? ProfileSaveState.Error)?.message
    val isGoogleOnly = user?.hasPassword == false

    // Reset when leaving, so returning later doesn't show a stale success pane.
    DisposableEffect(Unit) {
        onDispose { authViewModel.clearPasswordSaveState() }
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
                Text("Change password", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
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
            when {
                saved -> {
                    Spacer(modifier = Modifier.height(40.dp))
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.CheckCircle, contentDescription = null,
                            tint = Forest, modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Password changed", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "You're still signed in on this device. Use your new password next time you sign in.",
                            fontSize = 14.sp, color = InkMuted, textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = onBack,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Forest)
                        ) {
                            Text("DONE", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                isGoogleOnly -> {
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        "You sign in with Google",
                        fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Ink
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "This account has no password yet. To set one, sign out and use " +
                            "\"Forgot password\" on the sign-in screen — we'll email you a link. " +
                            "After that you'll be able to sign in either way.",
                        fontSize = 14.sp, color = InkMuted
                    )
                }

                else -> {
                    Spacer(modifier = Modifier.height(4.dp))
                    PasswordField(current, { current = it; localError = null }, "Current password", showPasswords)
                    PasswordField(newPassword, { newPassword = it; localError = null }, "New password", showPasswords)
                    PasswordField(confirm, { confirm = it; localError = null }, "Confirm new password", showPasswords)

                    TextButton(onClick = { showPasswords = !showPasswords }) {
                        Text(if (showPasswords) "Hide passwords" else "Show passwords", color = Forest, fontSize = 13.sp)
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    (localError ?: serverError)?.let {
                        Text(it, color = Tomato, fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    Button(
                        onClick = {
                            localError = null
                            when {
                                current.isBlank() ->
                                    localError = "Enter your current password"
                                // Same minimum the server and the reset flow use.
                                newPassword.length < 6 ->
                                    localError = "Password must be at least 6 characters"
                                newPassword != confirm ->
                                    localError = "Passwords do not match"
                                newPassword == current ->
                                    localError = "Choose a password different from your current one"
                                else -> authViewModel.changePassword(current, newPassword)
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Forest),
                        enabled = current.isNotBlank() && newPassword.isNotBlank() &&
                            confirm.isNotBlank() && !saving
                    ) {
                        if (saving) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White)
                        } else {
                            Text("CHANGE PASSWORD", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    visible: Boolean
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    )
}
