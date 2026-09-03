package com.techaus.afamfresh.ui.screens.rider

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techaus.afamfresh.R
import com.techaus.afamfresh.ui.theme.Cream
import com.techaus.afamfresh.ui.theme.Forest
import com.techaus.afamfresh.ui.theme.Ink
import com.techaus.afamfresh.ui.theme.InkMuted
import com.techaus.afamfresh.ui.theme.Tomato
import com.techaus.afamfresh.utils.PasswordValidator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RiderRegisterScreen(
    onRegisterSuccess: () -> Unit,
    onNavigateToLogin: () -> Unit,
    onSubmitApplication: (RiderRegistrationPayload) -> Unit,
    isSubmitting: Boolean = false,
    errorMessage: String? = null,
    isSubmittedSuccess: Boolean = false
) {
    var firstName by remember { mutableStateOf("") }
    var lastName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var vehicleType by remember { mutableStateOf("motorcycle") }
    var vehiclePlate by remember { mutableStateOf("") }
    var expandedVehicleMenu by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }

    Scaffold(containerColor = Cream) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.logo),
                contentDescription = "App Logo",
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Forest)
                    .padding(12.dp)
            )

            Text(
                "Apply as Courier",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = Forest
            )
            Text(
                "Join the AfamFresh delivery team. Submitting this form creates your courier account and notifies administration for verification.",
                fontSize = 14.sp,
                color = InkMuted
            )

            if (isSubmittedSuccess) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "Application Submitted!",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Forest
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Your application has been received and added to the admin review queue. You will receive an SMS and email notification once verified.",
                            fontSize = 14.sp,
                            color = Ink,
                            lineHeight = 20.sp
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = onNavigateToLogin,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Forest)
                        ) {
                            Text("Back to Sign In", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = firstName,
                        onValueChange = { firstName = it },
                        label = { Text("First Name") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    )
                    OutlinedTextField(
                        value = lastName,
                        onValueChange = { lastName = it },
                        label = { Text("Last Name") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    )
                }

                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("Mobile Number (e.g. 07XXXXXXXX)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email Address") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                ExposedDropdownMenuBox(
                    expanded = expandedVehicleMenu,
                    onExpandedChange = { expandedVehicleMenu = !expandedVehicleMenu }
                ) {
                    OutlinedTextField(
                        value = when (vehicleType) {
                            "bicycle" -> "Bicycle"
                            "car" -> "Car / Van"
                            else -> "Motorcycle (Boda)"
                        },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Vehicle Type") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedVehicleMenu) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    ExposedDropdownMenu(
                        expanded = expandedVehicleMenu,
                        onDismissRequest = { expandedVehicleMenu = false }
                    ) {
                        listOf("Motorcycle (Boda)", "Bicycle", "Car / Van").forEach { selection ->
                            DropdownMenuItem(
                                text = { Text(selection) },
                                onClick = {
                                    vehicleType = when (selection) {
                                        "Bicycle" -> "bicycle"
                                        "Car / Van" -> "car"
                                        else -> "motorcycle"
                                    }
                                    expandedVehicleMenu = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = vehiclePlate,
                    onValueChange = { vehiclePlate = it },
                    label = { Text("Vehicle Number Plate (e.g. UFA 123X)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it },
                    label = { Text("Confirm Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                Text(
                    "Must be 8+ characters with uppercase, lowercase, digit, and special symbol.",
                    fontSize = 12.sp,
                    color = InkMuted,
                    modifier = Modifier.padding(top = 2.dp, start = 4.dp)
                )

                (validationError ?: errorMessage)?.let { message ->
                    Text(message, color = Tomato, fontSize = 13.sp)
                }

                Button(
                    onClick = {
                        validationError = null
                        val validation = PasswordValidator.validate(password)
                        when {
                            firstName.isBlank() || lastName.isBlank() -> validationError = "Please enter your full name"
                            phone.isBlank() -> validationError = "Please enter your phone number"
                            email.isBlank() -> validationError = "Please enter your email address"
                            vehiclePlate.isBlank() -> validationError = "Please enter your vehicle plate number"
                            !validation.isValid -> validationError = validation.errorMessage
                            password != confirmPassword -> validationError = "Passwords do not match"
                            else -> {
                                onSubmitApplication(
                                    RiderRegistrationPayload(
                                        firstName = firstName.trim(),
                                        lastName = lastName.trim(),
                                        email = email.trim(),
                                        phone = phone.trim(),
                                        password = password.trim(),
                                        vehicleType = vehicleType,
                                        vehiclePlate = vehiclePlate.trim()
                                    )
                                )
                            }
                        }
                    },
                    enabled = !isSubmitting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Forest)
                ) {
                    if (isSubmitting) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), color = Color.White)
                    } else {
                        Text("Submit Courier Application", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }

                TextButton(
                    onClick = onNavigateToLogin,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Already registered? Sign In", color = Forest, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

data class RiderRegistrationPayload(
    val firstName: String,
    val lastName: String,
    val email: String,
    val phone: String,
    val password: String,
    val vehicleType: String,
    val vehiclePlate: String
)