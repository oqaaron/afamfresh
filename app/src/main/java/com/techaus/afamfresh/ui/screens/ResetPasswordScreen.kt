package com.techaus.afamfresh.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techaus.afamfresh.repository.AuthRepository
import com.techaus.afamfresh.ui.theme.*

/** Minimum password length. Kept here so the rule is stated in one place. */
private const val MIN_PASSWORD_LENGTH = 8

/**
 * Step two of password reset, reached from the emailed deep link
 * `afamfresh://reset-password?token=...`.
 *
 * The manifest already declared that intent filter; nothing handled it until
 * now, so tapping a reset link opened the app on the login screen with no
 * explanation.
 */
@Composable
fun ResetPasswordScreen(
    token: String,
    authRepository: AuthRepository,
    onDone: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }
    var succeeded by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val tooShort = password.isNotEmpty() && password.length < MIN_PASSWORD_LENGTH
    val mismatch = confirm.isNotEmpty() && confirm != password
    val canSubmit = password.length >= MIN_PASSWORD_LENGTH && password == confirm && !isSaving

    Scaffold(containerColor = Cream) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (succeeded) {
                Spacer(modifier = Modifier.height(80.dp))
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = Forest,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(20.dp))
                Text("Password updated", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    "You can now sign in with your new password.",
                    color = InkMuted,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(28.dp))
                Button(
                    onClick = onDone,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Forest)
                ) {
                    Text("SIGN IN", color = Color.White, fontWeight = FontWeight.Bold)
                }
                return@Column
            }

            Spacer(modifier = Modifier.height(60.dp))
            Text("Set a new password", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Choose a password of at least $MIN_PASSWORD_LENGTH characters.",
                color = InkMuted,
                fontSize = 14.sp
            )

            Spacer(modifier = Modifier.height(24.dp))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it; errorMessage = null },
                label = { Text("New password") },
                singleLine = true,
                isError = tooShort,
                supportingText = if (tooShort) {
                    { Text("At least $MIN_PASSWORD_LENGTH characters", color = Tomato) }
                } else null,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = confirm,
                onValueChange = { confirm = it; errorMessage = null },
                label = { Text("Confirm password") },
                singleLine = true,
                isError = mismatch,
                supportingText = if (mismatch) {
                    { Text("Passwords don't match", color = Tomato) }
                } else null,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            )

            errorMessage?.let {
                Spacer(modifier = Modifier.height(10.dp))
                Text(it, color = Tomato, fontSize = 13.sp, textAlign = TextAlign.Center)
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    isSaving = true
                    errorMessage = null
                    authRepository.resetPassword(token, password) { ok, error ->
                        isSaving = false
                        if (ok) succeeded = true else errorMessage = error
                    }
                },
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Forest)
            ) {
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), color = Color.White)
                } else {
                    Text("UPDATE PASSWORD", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            TextButton(onClick = onDone) {
                Text("Back to sign in", color = InkMuted, fontSize = 13.sp)
            }
        }
    }
}
