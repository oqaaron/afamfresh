package com.techaus.afamfresh.viewmodel

import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techaus.afamfresh.models.LoginUiState
import com.techaus.afamfresh.models.RegisterRequest
import com.techaus.afamfresh.models.User
import com.techaus.afamfresh.repository.AuthRepository
import com.techaus.afamfresh.repository.AuthRepository.GoogleSignInResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AuthViewModel(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _user = MutableStateFlow<User?>(authRepository.getUser())
    val user: StateFlow<User?> = _user.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _loginSuccess = MutableStateFlow(false)
    val loginSuccess: StateFlow<Boolean> = _loginSuccess.asStateFlow()

    private val _registerSuccess = MutableStateFlow(false)
    val registerSuccess: StateFlow<Boolean> = _registerSuccess.asStateFlow()

    private val _loginState = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    val loginState: StateFlow<LoginUiState> = _loginState.asStateFlow()

    // ✅ NEW: Reset login state to prevent re-triggering navigation
    fun resetLoginState() {
        _loginState.value = LoginUiState.Idle
    }

    // ===== EMAIL/PASSWORD LOGIN =====
    fun login(email: String, password: String, onSuccess: (String) -> Unit) {
    _isLoading.value = true
    _error.value = null
    _loginSuccess.value = false
    _loginState.value = LoginUiState.Loading

    try {
        authRepository.login(email, password) { response ->
            _isLoading.value = false
            if (response?.success == true && response.user != null) {
                _user.value = response.user
                _loginSuccess.value = true
                _loginState.value = LoginUiState.Success(response.user)
                onSuccess(response.user.name)
            } else {
                val msg = response?.error ?: "Login failed"
                _error.value = msg
                _loginState.value = LoginUiState.Error(msg)
                onSuccess("") // prevents hanging if onSuccess expects a result
            }
        }
    } catch (e: Exception) {
        _isLoading.value = false
        _error.value = "Network error: ${e.message}"
        _loginState.value = LoginUiState.Error(e.message ?: "Unknown error")
        Log.e("AuthVM", "Login exception", e)
    }
}

    // ===== REGISTRATION =====
    fun register(fname: String, lname: String, email: String, password: String, role: String = "user", phone: String = "") {
        _isLoading.value = true
        _error.value = null
        _registerSuccess.value = false

        val fullName = "$fname $lname"
        val request = RegisterRequest(fullName, email, password, phone.ifEmpty { null }, role)
        authRepository.register(request) { response ->
            _isLoading.value = false
            if (response?.success == true) {
                _registerSuccess.value = true
                response.user?.let { _user.value = it }
                // Auto-login after registration
                login(email, password) { name ->
                    // The login function will update the user state and navigate via MainActivity
                }
            } else {
                _error.value = response?.error ?: "Registration failed"
            }
        }
    }

    // ===== LOGOUT =====
    fun logout() {
        authRepository.logout { success ->
            if (success) {
                _user.value = null
                _loginSuccess.value = false
                _registerSuccess.value = false
                _loginState.value = LoginUiState.Idle
            }
        }
    }

    // ===== ROLE SWITCH =====
    fun switchRole(role: String, onComplete: (Boolean) -> Unit) {
        authRepository.switchRole(role) { success ->
            if (success) {
                _user.value = _user.value?.copy(currentRole = role)
            }
            onComplete(success)
        }
    }

    // ===== CLEAR ERROR =====
    fun clearError() {
        _error.value = null
    }

    // ===== GOOGLE SIGN-IN =====
    fun signInWithGoogle(launcher: ActivityResultLauncher<Intent>) {
        _isLoading.value = true
        _error.value = null
        _loginState.value = LoginUiState.Loading
        val signInClient = authRepository.getGoogleSignInClient()
        launcher.launch(signInClient.signInIntent)
    }

    fun handleGoogleSignInResult(result: Intent?) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            val signInResult = authRepository.handleGoogleSignInResult(result)
            when (signInResult) {
                is GoogleSignInResult.Success -> {
                    val loginResponse = authRepository.googleLogin(signInResult.idToken)
                    if (loginResponse?.success == true && loginResponse.user != null) {
                        _user.value = loginResponse.user
                        _loginSuccess.value = true
                        _loginState.value = LoginUiState.Success(loginResponse.user)
                    } else {
                        val msg = loginResponse?.error ?: "Google login failed"
                        _error.value = msg
                        _loginState.value = LoginUiState.Error(msg)
                    }
                }
                is GoogleSignInResult.Error -> {
                    _error.value = signInResult.message
                    _loginState.value = LoginUiState.Error(signInResult.message)
                }
            }
            _isLoading.value = false
        }
    }
}