package com.techaus.afamfresh.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techaus.afamfresh.R
import com.techaus.afamfresh.models.LoginUiState
import com.techaus.afamfresh.ui.theme.Forest
import com.techaus.afamfresh.ui.theme.Ink
import com.techaus.afamfresh.ui.theme.InkMuted
import com.techaus.afamfresh.ui.theme.Tomato
import com.techaus.afamfresh.viewmodel.AuthViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    authViewModel: AuthViewModel,
    onLoginSuccess: (String) -> Unit,
    onForgotPassword: () -> Unit,
    onCreateAccount: () -> Unit,
    onPhoneSignIn: () -> Unit
) {
    val context = LocalContext.current
    val packageName = context.packageName

    val isRiderApp = packageName.contains("rider", ignoreCase = true)
    val isVendorApp = packageName.contains("vendor", ignoreCase = true)

    // Each flavor overrides drawable/logo with its own raster brand mark.
    // Adaptive mipmap icons are XML wrappers and cannot be loaded by
    // Compose's painterResource.
    val brandIconRes = R.drawable.logo

    val brandTitle = when {
        isRiderApp -> "AfamFresh Rider Portal"
        isVendorApp -> "AfamFresh Merchant Portal"
        else -> "Welcome to AfamFresh"
    }

    val brandSubtitle = when {
        isRiderApp -> "Manage your deliveries, earnings, and routes"
        isVendorApp -> "Manage catalogue, surplus, and orders"
        else -> "Fresh produce delivered to your doorstep"
    }

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    val loginState by authViewModel.loginState.collectAsState()
    val isLoading by authViewModel.isLoading.collectAsState()
    val error by authViewModel.error.collectAsState()

    // Reset login state after handling to prevent re-triggers
    LaunchedEffect(loginState) {
        when (loginState) {
            is LoginUiState.Success -> {
                onLoginSuccess((loginState as LoginUiState.Success).user.name)
                authViewModel.resetLoginState()
            }
            is LoginUiState.Error -> {
                authViewModel.resetLoginState()
            }
            else -> {}
        }
    }

    Scaffold(
        containerColor = Color.White
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = painterResource(id = brandIconRes),
                contentDescription = "App Logo",
                modifier = Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .then(
                        if (!isRiderApp && !isVendorApp) {
                            Modifier.background(Forest).padding(16.dp)
                        } else {
                            Modifier
                        }
                    )
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = brandTitle,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = Forest
            )

            Text(
                text = brandSubtitle,
                fontSize = 14.sp,
                color = InkMuted,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(28.dp))

            OutlinedTextField(
                value = email,
                onValueChange = {
                    email = it
                    authViewModel.clearError()
                },
                label = { Text("Email") },
                placeholder = { Text("Enter your email") },
                leadingIcon = { Icon(Icons.Default.Email, contentDescription = "Email") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = password,
                onValueChange = {
                    password = it
                    authViewModel.clearError()
                },
                label = { Text("Password") },
                placeholder = { Text("Enter your password") },
                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = "Password") },
                trailingIcon = {
                    TextButton(onClick = { showPassword = !showPassword }) {
                        Text(
                            text = if (showPassword) "Hide" else "Show",
                            color = Forest,
                            fontSize = 12.sp
                        )
                    }
                },
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    text = "Forgot Password?",
                    color = Forest,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { onForgotPassword() }
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            if (error != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))
                ) {
                    Text(
                        text = error ?: "",
                        modifier = Modifier.padding(12.dp),
                        color = Tomato,
                        fontSize = 14.sp
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            Button(
                onClick = {
                    if (email.isNotEmpty() && password.isNotEmpty()) {
                        authViewModel.login(
                            email = email,
                            password = password,
                            onSuccess = { /* Handled by LaunchedEffect */ }
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Forest),
                enabled = !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Color.White
                    )
                } else {
                    Text(
                        text = "Login",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = onPhoneSignIn,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(12.dp),
                enabled = !isLoading
            ) {
                Text("Sign in with phone number", color = Ink, fontWeight = FontWeight.Medium)
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isRiderApp) "Want to ride with us?" else "Don't have an account?",
                    color = InkMuted,
                    fontSize = 14.sp
                )
                Text(
                    text = if (isRiderApp) " Apply Now" else " Sign Up",
                    color = Forest,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { onCreateAccount() }
                )
            }

            if (!isRiderApp && !isVendorApp) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Secure payment via Pesapal",
                    fontSize = 12.sp,
                    color = InkMuted
                )
            }
        }
    }
}