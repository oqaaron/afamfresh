package com.techaus.afamfresh.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techaus.afamfresh.BuildConfig
import com.techaus.afamfresh.R
import com.techaus.afamfresh.models.LoginUiState
import com.techaus.afamfresh.ui.theme.*
import com.techaus.afamfresh.utils.PasswordValidator
import com.techaus.afamfresh.viewmodel.AuthViewModel

@Composable
fun RegisterScreen(
    authViewModel: AuthViewModel,
    onRegister: (fname: String, lname: String, email: String, password: String, role: String, phone: String) -> Unit,
    onGoogleSignUpSuccess: () -> Unit,
    onBackToLogin: () -> Unit,
    onPhoneSignUp: () -> Unit
) {
    val context = LocalContext.current
    val isRiderApp = context.packageName.contains("rider", ignoreCase = true)
    val isVendorApp = context.packageName.contains("vendor", ignoreCase = true)

    var fname by remember { mutableStateOf("") }
    var lname by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    val role = BuildConfig.APP_ROLE
    var localError by remember { mutableStateOf<String?>(null) }

    val isLoading by authViewModel.isLoading.collectAsState()
    val error by authViewModel.error.collectAsState()
    val loginState by authViewModel.loginState.collectAsState()

    LaunchedEffect(loginState) {
        if (loginState is LoginUiState.Success) {
            onGoogleSignUpSuccess()
            authViewModel.resetLoginState()
        }
    }

    Scaffold(containerColor = Cream) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.logo),
                contentDescription = "App Logo",
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Forest)
                    .padding(14.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                when {
                    isRiderApp -> "Apply as AfamFresh Courier"
                    isVendorApp -> "Apply as AfamFresh Vendor"
                    else -> "Create Account"
                },
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = Forest
            )
            Text(
                when {
                    isRiderApp -> "Create your courier account to join the delivery team"
                    isVendorApp -> "Create your vendor account to sell fresh produce"
                    else -> "Join AfamFresh for fresh produce delivered to your door"
                },
                fontSize = 14.sp,
                color = InkMuted,
                modifier = Modifier.padding(top = 4.dp, bottom = 24.dp)
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = fname, onValueChange = { fname = it },
                    label = { Text("First name") },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp), singleLine = true
                )
                OutlinedTextField(
                    value = lname, onValueChange = { lname = it },
                    label = { Text("Last name") },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp), singleLine = true
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = email, onValueChange = { email = it },
                label = { Text("Email") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp), singleLine = true
            )

            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = phone, onValueChange = { phone = it },
                label = { Text("Mobile number") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp), singleLine = true
            )

            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = password, onValueChange = { password = it },
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp), singleLine = true
            )

            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = confirmPassword, onValueChange = { confirmPassword = it },
                label = { Text("Confirm password") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp), singleLine = true
            )

            Text(
                "Must be 8+ characters with uppercase, lowercase, digit, and special symbol.",
                fontSize = 12.sp,
                color = InkMuted,
                modifier = Modifier.padding(top = 6.dp, start = 4.dp)
            )

            (localError ?: error)?.let {
                Spacer(modifier = Modifier.height(12.dp))
                Text(it, color = Tomato, fontSize = 13.sp)
            }

            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = {
                    localError = null
                    val validation = PasswordValidator.validate(password)
                    when {
                        fname.isBlank() || lname.isBlank() -> localError = "Please enter your full name"
                        email.isBlank() -> localError = "Please enter your email"
                        phone.isBlank() -> localError = "Please enter your mobile number"
                        !validation.isValid -> localError = validation.errorMessage
                        password != confirmPassword -> localError = "Passwords do not match"
                        else -> onRegister(fname.trim(), lname.trim(), email.trim(), password.trim(), role, phone.trim())
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Forest),
                enabled = !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), color = Color.White)
                } else {
                    Text("Create Account", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = onPhoneSignUp,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Sign up with phone number", color = Ink, fontWeight = FontWeight.Medium)
            }

            Spacer(modifier = Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Text("Already have an account?", color = InkMuted, fontSize = 14.sp)
                Text(
                    " Log in",
                    color = Forest,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable(onClick = onBackToLogin)
                )
            }
        }
    }
}