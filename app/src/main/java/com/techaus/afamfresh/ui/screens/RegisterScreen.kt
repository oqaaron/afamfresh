package com.techaus.afamfresh.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techaus.afamfresh.R
import com.techaus.afamfresh.models.LoginUiState
import com.techaus.afamfresh.BuildConfig
import com.techaus.afamfresh.ui.theme.*
import com.techaus.afamfresh.viewmodel.AuthViewModel

// ⚠️ INFERRED screen. Signature matches MainActivity.kt's composable("register")
// call exactly: RegisterScreen(authViewModel, onRegister, onGoogleSignUpSuccess, onBackToLogin).
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
    // The account type comes from the build, not the person registering.
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
            // Same logo, same rounded-square/Forest treatment as
            // LoginScreen.kt — this screen had no branding at all before,
            // which read as inconsistent sitting one tap away from a screen
            // that does.
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
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp), singleLine = true
            )

            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = phone, onValueChange = { phone = it },
                label = { Text("Mobile number") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp), singleLine = true
            )

            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = password, onValueChange = { password = it },
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp), singleLine = true
            )

            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = confirmPassword, onValueChange = { confirmPassword = it },
                label = { Text("Confirm password") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp), singleLine = true
            )

            // The "sign up as" picker was removed. Which app you install now
            // decides the account type (BuildConfig.APP_ROLE -> users.account_type),
            // and it is fixed for the life of the account. Letting someone choose
            // here is what allowed one account to be both a shopper and a rider.

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
                        password.length < 6 -> localError = "Password must be at least 6 characters"
                        password != confirmPassword -> localError = "Passwords do not match"
                        else -> onRegister(fname, lname, email, password, role, phone)
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

            // Google Sign-In removed from the UI here — not deleted from the
            // codebase. AuthRepository.signInWithGoogle() and
            // AuthViewModel's handling of it are both left fully intact;
            // only this button is gone. Credential Manager's getCredential()
            // proved unreliable enough in real testing (a documented library
            // race condition — see android/identity-samples issue #113,
            // "the suspend function locked and does not proceed, but not
            // crash and not throw exception") to not ship behind a live
            // button right now.
            //
            // Known, accepted trade-off: existing production accounts
            // created via Google Sign-In have no password set and have no
            // way to sign in until this is either fixed and re-enabled, or
            // those specific accounts are handled directly.

            // Same underlying "phone_entry" route LoginScreen's equivalent
            // button leads to — the flow is unified (verify_phone_otp itself
            // decides signup vs login based on whether the number already
            // has an account), just worded for whichever screen someone
            // happened to land on first.
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
