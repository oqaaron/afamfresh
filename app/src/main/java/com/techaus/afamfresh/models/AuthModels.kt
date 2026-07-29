package com.techaus.afamfresh.models

import com.google.gson.annotations.SerializedName

// ⚠️ INFERRED from usage across AuthRepository.kt / AuthViewModel.kt / LoginScreen.kt.
// Field names (esp. @SerializedName values) are guesses matching typical PHP/MySQL
// snake_case conventions — verify these against your real auth.php JSON responses.

data class User(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("email") val email: String,
    @SerializedName("mobile") val mobile: String? = null,
    @SerializedName("roles") val roles: List<String> = listOf("user"),
    @SerializedName("current_role") val currentRole: String = "user"
)

data class LoginRequest(
    @SerializedName("email") val email: String,
    @SerializedName("password") val password: String
)

data class LoginResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("token") val token: String? = null,
    @SerializedName("user") val user: User? = null,
    @SerializedName("error") val error: String? = null
)

data class RegisterRequest(
    @SerializedName("name") val name: String,
    @SerializedName("email") val email: String,
    @SerializedName("password") val password: String,
    @SerializedName("mobile") val mobile: String? = null,
    @SerializedName("role") val role: String = "user"
)

data class RegisterResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("user") val user: User? = null,
    @SerializedName("error") val error: String? = null
)

data class UserResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("user") val user: User? = null,
    @SerializedName("error") val error: String? = null
)

data class BaseResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("error") val error: String? = null
)

data class RoleSwitchResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("current_role") val currentRole: String? = null,
    @SerializedName("error") val error: String? = null
)

// Drives LoginScreen's LaunchedEffect(loginState) block
sealed class LoginUiState {
    object Idle : LoginUiState()
    object Loading : LoginUiState()
    data class Success(val user: User) : LoginUiState()
    data class Error(val message: String) : LoginUiState()
}
