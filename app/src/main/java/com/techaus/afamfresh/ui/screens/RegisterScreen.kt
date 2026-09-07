package com.techaus.afamfresh.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techaus.afamfresh.BuildConfig
import com.techaus.afamfresh.R
import com.techaus.afamfresh.models.LoginUiState
import com.techaus.afamfresh.ui.theme.*
import com.techaus.afamfresh.viewmodel.AuthViewModel

<<<<<<< HEAD
private const val MIN_PASSWORD_LENGTH = 8

=======
>>>>>>> 2bfbfa13b3c9b2f1ddc9d9d6308e6b83ea1c4f83
@Composable
fun RegisterScreen(
    authViewModel: AuthViewModel,
    onRegister: (String, String, String, String, String, String?) -> Unit,
    onGoogleSignUpSuccess: () -> Unit,
    onBackToLogin: () -> Unit,
    onPhoneSignUp: () -> Unit
) {
    var firstName by remember { mutableStateOf("") }
    var lastName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
<<<<<<< HEAD
    var confirmPassword by remember { mutableStateOf("") }
    val role = BuildConfig.APP_ROLE
    var localError by remember { mutableStateOf<String?>(null) }
=======
    var showPassword by remember { mutableStateOf(false) }
>>>>>>> 2bfbfa13b3c9b2f1ddc9d9d6308e6b83ea1c4f83

    val loginState by authViewModel.loginState.collectAsState()
    val isLoading by authViewModel.isLoading.collectAsState()
    val error by authViewModel.error.collectAsState()

    val isPasswordTooShort = password.isNotEmpty() && password.length < MIN_PASSWORD_LENGTH
    val isPasswordMismatch = confirmPassword.isNotEmpty() && confirmPassword != password

    LaunchedEffect(loginState) {
        if (loginState is LoginUiState.Success) {
            onGoogleSignUpSuccess()
            authViewModel.resetLoginState()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(EcoGreen)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 48.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
<<<<<<< HEAD
            Image(
                painter = painterResource(id = R.drawable.logo),
                contentDescription = "App Logo",
=======
            Box(
>>>>>>> 2bfbfa13b3c9b2f1ddc9d9d6308e6b83ea1c4f83
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.logo),
                    contentDescription = "Logo",
                    modifier = Modifier.size(38.dp),
                    contentScale = ContentScale.Fit
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Create Account",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Text(
                text = "Fill in your details below to register",
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.9f),
                textAlign = TextAlign.Center
            )
        }

<<<<<<< HEAD
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = fname,
                    onValueChange = { fname = it; localError = null },
                    label = { Text("First name") },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )
                OutlinedTextField(
                    value = lname,
                    onValueChange = { lname = it; localError = null },
                    label = { Text("Last name") },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = email,
                onValueChange = { email = it; localError = null },
                label = { Text("Email") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it; localError = null },
                label = { Text("Mobile number") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; localError = null },
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                isError = isPasswordTooShort,
                supportingText = if (isPasswordTooShort) {
                    { Text("At least $MIN_PASSWORD_LENGTH characters required", color = Tomato) }
                } else null
            )

            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = confirmPassword,
                onValueChange = { confirmPassword = it; localError = null },
                label = { Text("Confirm password") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                isError = isPasswordMismatch,
                supportingText = if (isPasswordMismatch) {
                    { Text("Passwords don't match", color = Tomato) }
                } else null
            )

            (localError ?: error)?.let {
                Spacer(modifier = Modifier.height(12.dp))
                Text(it, color = Tomato, fontSize = 13.sp)
            }

            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = {
                    localError = null
                    when {
                        fname.isBlank() || lname.isBlank() -> localError = "Please enter your full name"
                        email.isBlank() -> localError = "Please enter your email"
                        phone.isBlank() -> localError = "Please enter your phone number"
                        password.length < MIN_PASSWORD_LENGTH -> localError = "Password must be at least $MIN_PASSWORD_LENGTH characters"
                        !password.any { it.isDigit() } -> localError = "Password must contain at least one number"
                        !password.any { it.isLetter() } -> localError = "Password must contain at least one letter"
                        password != confirmPassword -> localError = "Passwords do not match"
                        else -> onRegister(fname.trim(), lname.trim(), email.trim(), password, role, phone.trim())
=======
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.78f)
                .align(Alignment.BottomCenter),
            shape = RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp),
            color = Color.White
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // First Name & Last Name
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("First Name", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = NeutralText)
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(
                            value = firstName,
                            onValueChange = { firstName = it; authViewModel.clearError() },
                            placeholder = { Text("John", color = NeutralMuted, fontSize = 14.sp) },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Last Name", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = NeutralText)
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(
                            value = lastName,
                            onValueChange = { lastName = it; authViewModel.clearError() },
                            placeholder = { Text("Doe", color = NeutralMuted, fontSize = 14.sp) },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
>>>>>>> 2bfbfa13b3c9b2f1ddc9d9d6308e6b83ea1c4f83
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

<<<<<<< HEAD
            OutlinedButton(
                onClick = onPhoneSignUp,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Sign up with phone number", color = Ink, fontWeight = FontWeight.Medium)
            }
=======
                // Email
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("Email address", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = NeutralText)
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it; authViewModel.clearError() },
                        placeholder = { Text("example@gmail.com", color = NeutralMuted, fontSize = 14.sp) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))
>>>>>>> 2bfbfa13b3c9b2f1ddc9d9d6308e6b83ea1c4f83

                // Phone
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("Phone number (Optional)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = NeutralText)
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it; authViewModel.clearError() },
                        placeholder = { Text("+256 700 000000", color = NeutralMuted, fontSize = 14.sp) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Password
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("Password", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = NeutralText)
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; authViewModel.clearError() },
                        placeholder = { Text("••••••••", color = NeutralMuted, fontSize = 14.sp) },
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    imageVector = if (showPassword) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = null,
                                    tint = NeutralMuted,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        },
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (error != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = error ?: "",
                        color = DiscountRed,
                        fontSize = 12.sp,
                        modifier = Modifier.align(Alignment.Start)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Submit Button
                Button(
                    onClick = {
                        if (firstName.isNotBlank() && lastName.isNotBlank() && email.isNotBlank() && password.isNotBlank()) {
                            onRegister(
                                firstName.trim(),
                                lastName.trim(),
                                email.trim(),
                                password.trim(),
                                BuildConfig.APP_ROLE,
                                phone.ifBlank { null }
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = EcoGreen),
                    enabled = !isLoading
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), color = Color.White)
                    } else {
                        Text("Create Account", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = onPhoneSignUp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Sign up with phone number", color = NeutralText, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Already have an account?", color = NeutralMuted, fontSize = 13.sp)
                    Text(
                        text = " Sign In",
                        color = EcoGreen,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { onBackToLogin() }
                    )
                }
            }
        }
    }
}