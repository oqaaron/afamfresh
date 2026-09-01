package com.techaus.afamfresh.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
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

/**
 * States specific to the phone/OTP sign-in flow — kept separate from
 * [LoginUiState] rather than adding a case to it. NeedsSignup has no
 * equivalent there: LoginUiState only knows success or failure, and this
 * flow has a real third outcome — verified, but no account exists yet.
 * LoginScreen's LaunchedEffect(loginState) pattern-matches LoginUiState
 * elsewhere; adding a case there risks breaking an exhaustive `when` in code
 * this file can't see. Only the final success (Login branch of verify, or
 * completePhoneSignup) writes into loginState/user/loginSuccess — the same
 * shared surface login()/register()/handleGoogleSignInResult() already do.
 */
sealed class PhoneAuthState {
    object Idle : PhoneAuthState()
    object SendingCode : PhoneAuthState()
    object CodeSent : PhoneAuthState()
    object Verifying : PhoneAuthState()
    data class NeedsSignup(val mobile: String, val proofToken: String) : PhoneAuthState()
    object Completing : PhoneAuthState()
    data class Error(val message: String) : PhoneAuthState()
}

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
    // One function now, not two. The old split (signInWithGoogle launches an
    // Intent via an ActivityResultLauncher, handleGoogleSignInResult
    // processes whatever came back in onActivityResult) existed because the
    // legacy API had a real gap in the middle — an Activity Result the app
    // had to wait for. Credential Manager's getCredential() is a single
    // suspend call with no such gap, so there's nothing left to split.
    fun signInWithGoogle(context: Context) {
        _isLoading.value = true
        _error.value = null
        _loginState.value = LoginUiState.Loading
        viewModelScope.launch {
            val signInResult = authRepository.signInWithGoogle(context)
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

    // ===== PHONE / OTP SIGN-IN =====
    // Third sign-in mechanism alongside password (login/register above) and
    // Google (immediately above). See PhoneAuthState's own doc comment for
    // why this doesn't route through loginState for every step.

    private val _phoneAuthState = MutableStateFlow<PhoneAuthState>(PhoneAuthState.Idle)
    val phoneAuthState: StateFlow<PhoneAuthState> = _phoneAuthState.asStateFlow()

    fun sendPhoneOtp(mobile: String) {
        _phoneAuthState.value = PhoneAuthState.SendingCode
        authRepository.sendPhoneOtp(mobile) { response, error ->
            _phoneAuthState.value = if (response?.success == true) {
                PhoneAuthState.CodeSent
            } else {
                PhoneAuthState.Error(error?.userMessage ?: response?.error ?: "Could not send the verification code.")
            }
        }
    }

    fun verifyPhoneOtp(mobile: String, code: String) {
        _phoneAuthState.value = PhoneAuthState.Verifying
        authRepository.verifyPhoneOtp(mobile, code) { response, error ->
            when {
                // Existing number — this IS the login, same shared surface
                // login()/register()/handleGoogleSignInResult() write into.
                response?.success == true && response.isNewUser == false && response.user != null -> {
                    _user.value = response.user
                    _loginSuccess.value = true
                    _loginState.value = LoginUiState.Success(response.user)
                    _phoneAuthState.value = PhoneAuthState.Idle
                }

                // New number — verified, no account yet. The screen layer
                // reacts to NeedsSignup by navigating to the name-collection
                // step, then calls completePhoneSignup with this proofToken.
                response?.success == true && response.isNewUser == true && response.proofToken != null -> {
                    _phoneAuthState.value = PhoneAuthState.NeedsSignup(mobile, response.proofToken)
                }

                else -> {
                    _phoneAuthState.value =
                        PhoneAuthState.Error(error?.userMessage ?: response?.error ?: "Incorrect code. Please try again.")
                }
            }
        }
    }

    fun completePhoneSignup(mobile: String, proofToken: String, fname: String, lname: String) {
        _phoneAuthState.value = PhoneAuthState.Completing
        authRepository.completePhoneSignup(mobile, proofToken, fname, lname) { response, error ->
            if (response?.success == true && response.user != null) {
                _user.value = response.user
                _loginSuccess.value = true
                _loginState.value = LoginUiState.Success(response.user)
                _phoneAuthState.value = PhoneAuthState.Idle
            } else {
                _phoneAuthState.value =
                    PhoneAuthState.Error(error?.userMessage ?: response?.error ?: "Could not create your account.")
            }
        }
    }

    /** Mirrors resetLoginState() above — called when backing out of the
     *  phone flow, so a stale error doesn't reappear on a later attempt. */
    fun resetPhoneAuthState() {
        _phoneAuthState.value = PhoneAuthState.Idle
    }
}
