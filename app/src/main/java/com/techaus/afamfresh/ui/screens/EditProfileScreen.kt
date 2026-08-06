package com.techaus.afamfresh.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import com.techaus.afamfresh.models.ProfileSaveState
import com.techaus.afamfresh.models.UpdateProfileRequest
import com.techaus.afamfresh.BuildConfig
import com.techaus.afamfresh.models.addressOrEmpty
import com.techaus.afamfresh.models.areaOrEmpty
import com.techaus.afamfresh.models.firstNameOrDerived
import com.techaus.afamfresh.models.lastNameOrDerived
import com.techaus.afamfresh.ui.theme.*
import com.techaus.afamfresh.viewmodel.AuthViewModel

/**
 * The profile edit form.
 *
 * Two rules shape this screen, both coming from the server's constraints:
 *
 *  - Changing the email needs the current password, because email is the login
 *    identifier and an unattended phone would otherwise be enough to take over
 *    the account. The field only appears once the email actually differs, so
 *    the common case (fixing a typo in a name) stays a single tap.
 *  - A Google account has no password at all, so it cannot satisfy that check.
 *    Its email field is read-only with an explanation rather than a control
 *    that always fails.
 */
@Composable
fun EditProfileScreen(
    authViewModel: AuthViewModel,
    onBack: () -> Unit
) {
    val user by authViewModel.user.collectAsState()
    val saveState by authViewModel.profileSaveState.collectAsState()

    // Keyed on the user id so the form seeds once per account, and is not
    // wiped mid-typing when the server response updates _user.
    val seedKey = user?.id
    var fname by remember(seedKey) { mutableStateOf(user?.firstNameOrDerived ?: "") }
    var lname by remember(seedKey) { mutableStateOf(user?.lastNameOrDerived ?: "") }
    var email by remember(seedKey) { mutableStateOf(user?.email ?: "") }
    var mobile by remember(seedKey) { mutableStateOf(user?.mobile ?: "") }
    var area by remember(seedKey) { mutableStateOf(user?.areaOrEmpty ?: "") }
    var address by remember(seedKey) { mutableStateOf(user?.addressOrEmpty ?: "") }
    var currentPassword by remember(seedKey) { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    val originalEmail = user?.email ?: ""
    val isGoogleOnly = user?.hasPassword == false
    val emailChanged = email.trim() != originalEmail && !isGoogleOnly

    val saving = saveState is ProfileSaveState.Saving
    val serverError = (saveState as? ProfileSaveState.Error)?.message

    // Leave on success, the same way EditOrderScreen does — there is no
    // snackbar anywhere in this app, and a form that stays open after saving
    // reads as if nothing happened.
    LaunchedEffect(saveState) {
        if (saveState is ProfileSaveState.Saved) {
            authViewModel.clearProfileSaveState()
            onBack()
        }
    }

    // Only the Customer app requires a delivery address; api/profile.php
    // applies the same rule server-side, keyed on users.account_type.
    val isCustomerApp = BuildConfig.APP_ROLE == "user"

    val canSave = fname.isNotBlank() && lname.isNotBlank() && email.isNotBlank() &&
            (!isCustomerApp || (area.isNotBlank() && address.isNotBlank())) &&
            (!emailChanged || currentPassword.isNotBlank())

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
                Text("Edit profile", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
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
            ProfileField(fname, { fname = it; localError = null }, "First name")
            ProfileField(lname, { lname = it; localError = null }, "Last name")
            ProfileField(
                mobile, { mobile = it; localError = null }, "Mobile number",
                keyboardType = KeyboardType.Phone
            )

            ProfileField(
                email,
                { email = it; localError = null },
                "Email",
                keyboardType = KeyboardType.Email,
                readOnly = isGoogleOnly
            )
            if (isGoogleOnly) {
                Text(
                    "Your email is managed by your Google account and can't be changed here.",
                    color = InkMuted, fontSize = 12.sp,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                )
            }

            // Only asked for when it is actually needed.
            if (emailChanged) {
                ProfileField(
                    currentPassword, { currentPassword = it; localError = null },
                    "Current password",
                    isPassword = true
                )
                Text(
                    "Changing your email needs your current password.",
                    color = InkMuted, fontSize = 12.sp,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                )
            }

            // Customer-only: this is where an order gets delivered TO. Riders
            // and vendors have no such address, and these fields were also
            // REQUIRED by canSave below — so a rider could not save their
            // profile at all until this was scoped.
            if (isCustomerApp) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("Default delivery", fontWeight = FontWeight.SemiBold, color = Ink, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(4.dp))
                ProfileField(area, { area = it; localError = null }, "Area / neighbourhood")
                ProfileField(address, { address = it; localError = null }, "Delivery address")
            }

            Spacer(modifier = Modifier.height(12.dp))

            (localError ?: serverError)?.let {
                Text(it, color = Tomato, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(10.dp))
            }

            Button(
                onClick = {
                    localError = null
                    val trimmedMobile = mobile.trim()
                    when {
                        fname.isBlank() || lname.isBlank() ->
                            localError = "Please enter your full name"
                        !email.trim().contains("@") ->
                            localError = "Please enter a valid email"
                        isCustomerApp && (area.isBlank() || address.isBlank()) ->
                            localError = "Please enter your delivery area and address"
                        emailChanged && currentPassword.isBlank() ->
                            localError = "Enter your current password to change your email"
                        else -> authViewModel.updateProfile(
                            UpdateProfileRequest(
                                fname = fname.trim(),
                                lname = lname.trim(),
                                email = email.trim(),
                                mobile = trimmedMobile.ifBlank { null },
                                area = area.trim(),
                                address = address.trim(),
                                currentPassword = currentPassword.ifBlank { null }
                            )
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Forest),
                enabled = canSave && !saving
            ) {
                if (saving) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White)
                } else {
                    Text("SAVE CHANGES", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

@Composable
private fun ProfileField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false,
    readOnly: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        readOnly = readOnly,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    )
}
