package com.techaus.afamfresh.viewmodel

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techaus.afamfresh.BuildConfig
import com.techaus.afamfresh.models.LoginUiState
import com.techaus.afamfresh.models.NotificationPrefs
import com.techaus.afamfresh.models.ProfileSaveState
import com.techaus.afamfresh.models.RegisterRequest
import com.techaus.afamfresh.models.UpdateProfileRequest
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

    // getRestorableUser(), not getUser(): the cached profile persists until
    // sign-out, so seeding from it directly would restore a user whose session
    // expired long ago. This yields a user only while the session is resumable.
    private val _user = MutableStateFlow<User?>(authRepository.getRestorableUser())
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

    // ===== PROFILE STATE =====
    //
    // Deliberately separate from _isLoading/_error above. Those are collected
    // by LoginScreen and RegisterScreen, so a failed profile save written into
    // them would surface as an error on the sign-in screen the next time the
    // user signed out — an error about something they are no longer doing.

    private val _profileSaveState = MutableStateFlow<ProfileSaveState>(ProfileSaveState.Idle)
    val profileSaveState: StateFlow<ProfileSaveState> = _profileSaveState.asStateFlow()

    private val _passwordSaveState = MutableStateFlow<ProfileSaveState>(ProfileSaveState.Idle)
    val passwordSaveState: StateFlow<ProfileSaveState> = _passwordSaveState.asStateFlow()

    private val _avatarUploading = MutableStateFlow(false)
    val avatarUploading: StateFlow<Boolean> = _avatarUploading.asStateFlow()

    private val _avatarError = MutableStateFlow<String?>(null)
    val avatarError: StateFlow<String?> = _avatarError.asStateFlow()

    /**
     * Re-reads the profile from the server.
     *
     * Silent by design: this runs when the profile screen opens, and a user who
     * is merely offline should still see their cached details rather than an
     * error over the top of them.
     */
    fun refreshUser() {
        authRepository.refreshUser { user, error ->
            if (user != null) {
                _user.value = user
            } else {
                Log.w("AuthViewModel", "refreshUser failed: ${error?.userMessage}")
            }
        }
    }

    fun updateProfile(request: UpdateProfileRequest) {
        _profileSaveState.value = ProfileSaveState.Saving
        authRepository.updateProfile(request) { user, error ->
            if (user != null) {
                _user.value = user
                _profileSaveState.value = ProfileSaveState.Saved
            } else {
                _profileSaveState.value =
                    ProfileSaveState.Error(error?.userMessage ?: "Could not save your changes.")
            }
        }
    }

    fun changePassword(currentPassword: String, newPassword: String) {
        _passwordSaveState.value = ProfileSaveState.Saving
        authRepository.changePassword(currentPassword, newPassword) { success, message ->
            _passwordSaveState.value =
                if (success) ProfileSaveState.Saved
                else ProfileSaveState.Error(message ?: "Could not change your password.")
        }
    }

    fun uploadAvatar(uri: Uri) {
        _avatarUploading.value = true
        _avatarError.value = null
        viewModelScope.launch {
            // Decoding/compressing happens off the main thread inside the repo.
            val bytes = authRepository.prepareAvatarBytes(uri)
            if (bytes == null) {
                _avatarUploading.value = false
                _avatarError.value = "Couldn't read that image. Try another one."
                return@launch
            }
            authRepository.uploadAvatar(bytes) { user, error ->
                _avatarUploading.value = false
                if (user != null) _user.value = user
                else _avatarError.value = error?.userMessage ?: "Couldn't upload that image."
            }
        }
    }

    fun removeAvatar() {
        _avatarUploading.value = true
        _avatarError.value = null
        authRepository.removeAvatar { user, error ->
            _avatarUploading.value = false
            if (user != null) _user.value = user
            else _avatarError.value = error?.userMessage ?: "Couldn't remove your photo."
        }
    }

    /**
     * Writes notification preferences to the server.
     *
     * Applied optimistically so the switch moves the instant it is tapped, then
     * reverted if the write fails — a toggle that waits for a round trip before
     * moving feels broken.
     */
    fun setNotificationPrefs(email: Boolean, push: Boolean) {
        val previous = _user.value?.notificationPreferences
        _user.value = _user.value?.copy(notificationPreferences = NotificationPrefs(email, push))
        authRepository.updateNotificationPrefs(email, push) { user, error ->
            if (user != null) {
                _user.value = user
            } else {
                _user.value = _user.value?.copy(notificationPreferences = previous)
                Log.w("AuthViewModel", "notification prefs failed: ${error?.userMessage}")
            }
        }
    }

    fun clearProfileSaveState() { _profileSaveState.value = ProfileSaveState.Idle }
    fun clearPasswordSaveState() { _passwordSaveState.value = ProfileSaveState.Idle }
    fun clearAvatarError() { _avatarError.value = null }

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
            authRepository.login(email, password) { response, error ->
                _isLoading.value = false
                if (response?.success == true && response.user != null) {
                    _user.value = response.user
                    _loginSuccess.value = true
                    _loginState.value = LoginUiState.Success(response.user)
                    onSuccess(response.user.name)
                } else {
                    // "You're offline" and "incorrect password" are now
                    // distinguishable, where both previously read "Login failed".
                    val msg = error?.userMessage ?: response?.error ?: "Login failed"
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
    // `role` decides users.account_type on the server and is fixed for the
    // life of the account. It defaults to this build's role rather than
    // "user", so registering in the Rider app cannot create a shopper.
    fun register(fname: String, lname: String, email: String, password: String, role: String = BuildConfig.APP_ROLE, phone: String = "") {
        _isLoading.value = true
        _error.value = null
        _registerSuccess.value = false

        val fullName = "$fname $lname"
        val request = RegisterRequest(fullName, email, password, phone.ifEmpty { null }, role)
        authRepository.register(request) { response, error ->
            _isLoading.value = false
            if (response?.success == true) {
                _registerSuccess.value = true
                response.user?.let { _user.value = it }
                // Auto-login after registration
                login(email, password) { name ->
                    // The login function will update the user state and navigate via MainActivity
                }
            } else {
                _error.value = error?.userMessage ?: response?.error ?: "Registration failed"
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
    /**
     * Switches role, applying it immediately and reverting if the server says no.
     *
     * The optimistic flip is what makes the pills feel responsive; the revert
     * is what stops the UI claiming a role the account does not actually hold,
     * which is possible now that the server validates the request.
     */
    fun switchRole(role: String, onComplete: (Boolean) -> Unit) {
        val previous = _user.value?.currentRole
        _user.value = _user.value?.copy(currentRole = role)
        authRepository.switchRole(role) { success ->
            if (!success && previous != null) {
                _user.value = _user.value?.copy(currentRole = previous)
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
                // Backing out of the account chooser returns the screen to
                // where it was, with no error shown.
                is GoogleSignInResult.Cancelled -> {
                    _loginState.value = LoginUiState.Idle
                }
            }
            _isLoading.value = false
        }
    }
}
