package com.techaus.afamfresh.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.techaus.afamfresh.models.LoginUiState
import com.techaus.afamfresh.models.ProfileSaveState
import com.techaus.afamfresh.models.UpdateProfileRequest
import com.techaus.afamfresh.models.User
import com.techaus.afamfresh.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class PhoneAuthState {
    object Idle : PhoneAuthState()
    object SendingCode : PhoneAuthState()
    object CodeSent : PhoneAuthState()
    object Verifying : PhoneAuthState()
    object Completing : PhoneAuthState()
    data class NeedsSignup(val mobile: String, val proofToken: String) : PhoneAuthState()
    data class Error(val message: String) : PhoneAuthState()
}

class AuthViewModel(
    application: Application,
    private val authRepository: AuthRepository
) : AndroidViewModel(application) {

    private val _user = MutableStateFlow<User?>(authRepository.getUser())
    val user: StateFlow<User?> = _user.asStateFlow()

    private val _phoneAuthState = MutableStateFlow<PhoneAuthState>(PhoneAuthState.Idle)
    val phoneAuthState: StateFlow<PhoneAuthState> = _phoneAuthState.asStateFlow()

    private val _loginState = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    val loginState: StateFlow<LoginUiState> = _loginState.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _profileSaveState = MutableStateFlow<ProfileSaveState>(ProfileSaveState.Idle)
    val profileSaveState: StateFlow<ProfileSaveState> = _profileSaveState.asStateFlow()

    private val _passwordSaveState = MutableStateFlow<ProfileSaveState>(ProfileSaveState.Idle)
    val passwordSaveState: StateFlow<ProfileSaveState> = _passwordSaveState.asStateFlow()

    private val _avatarUploading = MutableStateFlow(false)
    val avatarUploading: StateFlow<Boolean> = _avatarUploading.asStateFlow()

    private val _avatarError = MutableStateFlow<String?>(null)
    val avatarError: StateFlow<String?> = _avatarError.asStateFlow()

    fun clearError() {
        _error.value = null
    }

    fun resetPhoneAuthState() {
        _phoneAuthState.value = PhoneAuthState.Idle
    }

    fun resetLoginState() {
        _loginState.value = LoginUiState.Idle
    }

    fun clearProfileSaveState() {
        _profileSaveState.value = ProfileSaveState.Idle
    }

    fun clearPasswordSaveState() {
        _passwordSaveState.value = ProfileSaveState.Idle
    }

    fun register(_fname: String, _lname: String, _email: String, _password: String, _role: String, _phone: String?) {
        viewModelScope.launch {
            // Implementation handled via repo callback or direct call
        }
    }

    fun login(email: String, pass: String, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            authRepository.login(email, pass) { _, error ->
                _isLoading.value = false
                if (error == null) {
                    val currentUser = authRepository.getUser()
                    _user.value = currentUser
                    if (currentUser != null) {
                        _loginState.value = LoginUiState.Success(currentUser)
                    }
                } else {
                    _error.value = error.userMessage
                }
                onComplete()
            }
        }
    }

    fun updateProfile(request: UpdateProfileRequest) {
        viewModelScope.launch {
            _profileSaveState.value = ProfileSaveState.Saving
            authRepository.updateProfile(request) { _, error ->
                if (error == null) {
                    val updatedUser = authRepository.getUser()
                    _user.value = updatedUser
                    _profileSaveState.value = ProfileSaveState.Saved
                } else {
                    _profileSaveState.value = ProfileSaveState.Error(error.userMessage)
                }
            }
        }
    }

    fun changePassword(currentPass: String, newPass: String) {
        viewModelScope.launch {
            _passwordSaveState.value = ProfileSaveState.Saving
            authRepository.changePassword(currentPass, newPass) { success, errorMsg ->
                if (success) {
                    _passwordSaveState.value = ProfileSaveState.Saved
                } else {
                    _passwordSaveState.value = ProfileSaveState.Error(
                        errorMsg ?: "Failed to change password"
                    )
                }
            }
        }
    }

    fun refreshUser() {
        _user.value = authRepository.getUser()
    }

    fun uploadAvatar(_uri: Uri) {
        viewModelScope.launch {
            _avatarUploading.value = true
            _avatarError.value = null
            // Add repository implementation call for avatar upload here when ready
            _avatarUploading.value = false
        }
    }

    fun removeAvatar() {
        viewModelScope.launch {
            // Add repository implementation call for avatar removal here when ready
        }
    }

    fun setNotificationPrefs(email: Boolean? = null, push: Boolean? = null, enabled: Boolean? = null) {
        // Handle saving notification preferences
    }

    fun sendPhoneOtp(mobile: String) {
        _phoneAuthState.value = PhoneAuthState.SendingCode
        authRepository.sendPhoneOtp(mobile) { body, error ->
            if (error == null && body?.success == true) {
                _phoneAuthState.value = PhoneAuthState.CodeSent
            } else {
                _phoneAuthState.value = PhoneAuthState.Error(error?.userMessage ?: body?.error ?: "Failed to send code")
            }
        }
    }

    fun verifyPhoneOtp(mobile: String, code: String) {
        _phoneAuthState.value = PhoneAuthState.Verifying
        authRepository.verifyPhoneOtp(mobile, code) { body, error ->
            if (error == null && body != null) {
                if (body.isNewUser == true) {
                    _phoneAuthState.value = PhoneAuthState.NeedsSignup(mobile, body.proofToken.orEmpty())
                } else if (body.token != null && body.user != null) {
                    _user.value = body.user
                    _loginState.value = LoginUiState.Success(body.user)
                } else {
                    _phoneAuthState.value = PhoneAuthState.Error("Verification failed")
                }
            } else {
                _phoneAuthState.value = PhoneAuthState.Error(error?.userMessage ?: body?.error ?: "Invalid code")
            }
        }
    }

    fun completePhoneSignup(mobile: String, proofToken: String, fname: String, lname: String) {
        _phoneAuthState.value = PhoneAuthState.Verifying
        authRepository.completePhoneSignup(mobile, proofToken, fname, lname) { body, error ->
            if (error == null && body?.user != null) {
                _user.value = body.user
                _loginState.value = LoginUiState.Success(body.user)
            } else {
                _phoneAuthState.value = PhoneAuthState.Error(error?.userMessage ?: body?.error ?: "Signup failed")
            }
        }
    }

    fun logout() {
        authRepository.logout {
            _user.value = null
        }
    }
}