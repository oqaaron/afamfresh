package com.techaus.afamfresh.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techaus.afamfresh.repository.AuthRepository
import com.techaus.afamfresh.ui.theme.*

/**
 * Step one of password reset: ask for the account email.
 *
 * The confirmation deliberately does NOT say whether the address had an
 * account — that would let anyone check which emails are registered. The
 * backend is specified to behave the same way.
 */
@Composable
fun ForgotPasswordScreen(
    authRepository: AuthRepository,
    onBack: () -> Unit
) {
    var email by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val emailLooksValid = email.contains("@") && email.contains(".") && email.length > 5

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
                Text("Reset password", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (sent) {
                Spacer(modifier = Modifier.height(60.dp))
                Icon(
                    Icons.Default.MarkEmailRead,
                    contentDescription = null,
                    tint = Forest,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(20.dp))
                Text("Check your email", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    "If an account exists for $email, we've sent a link to reset " +
                        "your password. The link expires in 30 minutes.",
                    color = InkMuted,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(28.dp))
                Button(
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Forest)
                ) {
                    Text("BACK TO SIGN IN", color = Color.White, fontWeight = FontWeight.Bold)
                }
            } else {
                Spacer(modifier = Modifier.height(30.dp))
                Text(
                    "Enter the email address on your account and we'll send you a link to set a new password.",
                    color = InkMuted,
                    fontSize = 14.sp
                )

                Spacer(modifier = Modifier.height(24.dp))

                OutlinedTextField(
                    value = email,
                    onValueChange = {
                        email = it
                        errorMessage = null
                    },
                    label = { Text("Email address") },
                    singleLine = true,
                    isError = errorMessage != null,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                )

                errorMessage?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(it, color = Tomato, fontSize = 13.sp)
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = {
                        isSending = true
                        errorMessage = null
                        authRepository.requestPasswordReset(email.trim()) { success, _ ->
                            isSending = false
                            if (success) {
                                sent = true
                            } else {
                                errorMessage =
                                    "Couldn't reach the server. Check your connection and try again."
                            }
                        }
                    },
                    enabled = emailLooksValid && !isSending,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Forest)
                ) {
                    if (isSending) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), color = Color.White)
                    } else {
                        Text("SEND RESET LINK", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
