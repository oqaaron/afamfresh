package com.techaus.afamfresh.ui.screens.rider

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RiderRegisterScreen(
    onRegisterSuccess: () -> Unit,
    onNavigateToLogin: () -> Unit,
    onSubmitApplication: (RiderRegistrationPayload) -> Unit,
    isSubmitting: Boolean = false,
    errorMessage: String? = null
) {
    var firstName by remember { mutableStateOf("") }
    var lastName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var vehicleType by remember { mutableStateOf("motorcycle") }
    var vehiclePlate by remember { mutableStateOf("") }
    var expandedVehicleMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Apply as AfamFresh Courier") }) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            OutlinedTextField(
                value = firstName,
                onValueChange = { firstName = it },
                label = { Text("First Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = lastName,
                onValueChange = { lastName = it },
                label = { Text("Last Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it },
                label = { Text("Mobile Number (for M-Pesa / Airtel Money)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email Address") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // Vehicle Selection
            ExposedDropdownMenuBox(
                expanded = expandedVehicleMenu,
                onExpandedChange = { expandedVehicleMenu = !expandedVehicleMenu }
            ) {
                OutlinedTextField(
                    value = vehicleType.replaceFirstChar { it.uppercase() },
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Vehicle Type") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedVehicleMenu) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
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
                                    "Motorcycle (Boda)" -> "motorcycle"
                                    "Bicycle" -> "bicycle"
                                    else -> "car"
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
                modifier = Modifier.fillMaxWidth()
            )

            errorMessage?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error)
            }

            Button(
                onClick = {
                    onSubmitApplication(
                        RiderRegistrationPayload(
                            firstName = firstName,
                            lastName = lastName,
                            email = email,
                            phone = phone,
                            password = password,
                            vehicleType = vehicleType,
                            vehiclePlate = vehiclePlate
                        )
                    )
                },
                enabled = !isSubmitting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp))
                } else {
                    Text("Submit Courier Application")
                }
            }

            TextButton(
                onClick = onNavigateToLogin,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Already registered? Sign In")
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