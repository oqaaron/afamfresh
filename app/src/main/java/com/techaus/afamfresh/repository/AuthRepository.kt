package com.techaus.afamfresh.repository

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.techaus.afamfresh.R
import com.techaus.afamfresh.api.ApiClient
import com.techaus.afamfresh.api.ApiService
import com.techaus.afamfresh.models.BaseResponse
import com.techaus.afamfresh.models.LoginRequest
import com.techaus.afamfresh.models.LoginResponse
import com.techaus.afamfresh.models.NotificationPrefs
import com.techaus.afamfresh.models.PhoneVerifyResponse
import com.techaus.afamfresh.models.RegisterRequest
import com.techaus.afamfresh.models.RegisterResponse
import com.techaus.afamfresh.models.RiderRegistrationRequest
import com.techaus.afamfresh.models.RoleSwitchResponse
import com.techaus.afamfresh.models.UpdateProfileRequest
import com.techaus.afamfresh.models.User
import com.techaus.afamfresh.models.UserResponse
import com.techaus.afamfresh.utils.ApiError
import com.techaus.afamfresh.utils.SessionTracker
import com.techaus.afamfresh.utils.SecurePrefs
import com.techaus.afamfresh.utils.enqueueApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

sealed class GoogleSignInResult {
    data class Success(val idToken: String, val email: String?, val displayName: String?) : GoogleSignInResult()
    data class Error(val message: String) : GoogleSignInResult()
    data class Cancelled(val message: String) : GoogleSignInResult()
}

class AuthRepository(
    private val apiService: ApiService,
    private val context: Context
) {
    // Encrypted: this file holds the auth token and the cached user profile.
    private val prefs: SharedPreferences = SecurePrefs.create(context, "auth_prefs")

    companion object {
        private const val SESSION_TIMEOUT_MS = 30 * 60 * 1000L // 30 minutes
    }

    // ===== TOKEN MANAGEMENT =====
    fun saveToken(token: String) {
        prefs.edit().putString("auth_token", token).apply()
        updateLastActivity()
    }

    fun getToken(): String? = prefs.getString("auth_token", null)

    fun clearToken() {
        prefs.edit().remove("auth_token").apply()
    }

    // ===== SESSION TIMEOUT =====
    fun updateLastActivity() = SessionTracker.touch()

    fun getLastActivity(): Long = SessionTracker.lastActivity()

    fun isSessionValid(): Boolean = com.techaus.afamfresh.utils.isSessionValid(
        lastActivity = getLastActivity(),
        now = System.currentTimeMillis(),
        timeoutMs = SESSION_TIMEOUT_MS
    )

    // ===== LOGIN STATUS =====
    fun isLoggedIn(): Boolean {
        val token = getToken()
        return !token.isNullOrEmpty() && isSessionValid()
    }

    fun clearSession() {
        clearToken()
        SessionTracker.clearActivity()
        ApiClient.clearCookies()
    }

    // ===== USER MANAGEMENT =====
    fun saveUser(user: User) {
        @Suppress("USELESS_ELVIS")
        val roles = user.roles ?: listOf("user")
        @Suppress("USELESS_ELVIS")
        val currentRole = user.currentRole ?: "user"

        prefs.edit()
            .putString("user_id", user.id)
            .putString("user_name", user.name)
            .putString("user_email", user.email)
            .putString("user_mobile", user.mobile)
            .putString("user_roles", roles.joinToString(","))
            .putString("user_current_role", currentRole)
            .putString("user_fname", user.fname)
            .putString("user_lname", user.lname)
            .putString("user_area", user.area)
            .putString("user_address", user.address)
            .putString("user_avatar", user.avatarUrl)
            .putString("user_has_password", user.hasPassword?.let { if (it) "1" else "0" })
            .putString("user_is_google", user.isGoogleAccount?.let { if (it) "1" else "0" })
            .putString("user_loyalty", user.loyaltyPoints?.toString())
            .putString("user_notif_email", user.notificationPreferences?.email?.let { if (it) "1" else "0" })
            .putString("user_notif_push", user.notificationPreferences?.push?.let { if (it) "1" else "0" })
            .apply()
        updateLastActivity()
    }

    fun getUser(): User? {
        val id = prefs.getString("user_id", null)
        if (id.isNullOrEmpty()) return null
        val name = prefs.getString("user_name", "") ?: ""
        val email = prefs.getString("user_email", "") ?: ""
        val mobile = prefs.getString("user_mobile", null)
        val roles = prefs.getString("user_roles", "user")?.split(",")?.filter { it.isNotEmpty() } ?: listOf("user")
        val currentRole = prefs.getString("user_current_role", "user") ?: "user"

        fun flag(key: String): Boolean? = when (prefs.getString(key, null)) {
            "1" -> true
            "0" -> false
            else -> null
        }
        val notifEmail = flag("user_notif_email")
        val notifPush = flag("user_notif_push")

        return User(
            id = id,
            name = name,
            email = email,
            mobile = mobile,
            fname = prefs.getString("user_fname", null),
            lname = prefs.getString("user_lname", null),
            area = prefs.getString("user_area", null),
            address = prefs.getString("user_address", null),
            avatarUrl = prefs.getString("user_avatar", null),
            hasPassword = flag("user_has_password"),
            isGoogleAccount = flag("user_is_google"),
            loyaltyPoints = prefs.getString("user_loyalty", null)?.toIntOrNull(),
            notificationPreferences = if (notifEmail == null && notifPush == null) null
                else NotificationPrefs(email = notifEmail ?: true, push = notifPush ?: true),
            roles = roles,
            currentRole = currentRole
        )
    }

    fun getRestorableUser(): User? = if (isLoggedIn()) getUser() else null

    fun clearUser() {
        prefs.edit()
            .remove("user_id")
            .remove("user_name")
            .remove("user_email")
            .remove("user_mobile")
            .remove("user_roles")
            .remove("user_current_role")
            .remove("user_fname")
            .remove("user_lname")
            .remove("user_area")
            .remove("user_address")
            .remove("user_avatar")
            .remove("user_has_password")
            .remove("user_is_google")
            .remove("user_loyalty")
            .remove("user_notif_email")
            .remove("user_notif_push")
            .apply()
    }

    // ===== PROFILE =====
    fun refreshUser(callback: (User?, ApiError?) -> Unit) {
        apiService.getCurrentUser().enqueueApi<UserResponse>("AuthRepo", "refreshUser") { body, error ->
            when {
                error != null -> callback(null, error)
                body?.success == true && body.user != null -> {
                    saveUser(body.user)
                    callback(body.user, null)
                }
                else -> callback(null, ApiError.reported(body?.error))
            }
        }
    }

    fun updateProfile(request: UpdateProfileRequest, callback: (User?, ApiError?) -> Unit) {
        apiService.updateProfile(body = request)
            .enqueueApi<UserResponse>("AuthRepo", "updateProfile") { body, error ->
                when {
                    error != null -> callback(null, error)
                    body?.success == true && body.user != null -> {
                        saveUser(body.user)
                        callback(body.user, null)
                    }
                    else -> callback(null, ApiError.reported(body?.error))
                }
            }
    }

    fun changePassword(currentPassword: String, newPassword: String, callback: (Boolean, String?) -> Unit) {
        apiService.changePassword(currentPassword = currentPassword, newPassword = newPassword)
            .enqueueApi<BaseResponse>("AuthRepo", "changePassword") { body, error ->
                when {
                    error != null -> callback(false, error.userMessage)
                    body?.success == true -> callback(true, null)
                    else -> callback(false, body?.error ?: "Could not change your password.")
                }
            }
    }

    fun uploadAvatar(imageBytes: ByteArray, callback: (User?, ApiError?) -> Unit) {
        val body = imageBytes.toRequestBody("image/jpeg".toMediaTypeOrNull())
        val part = MultipartBody.Part.createFormData("avatar", "avatar.jpg", body)
        apiService.uploadAvatar(avatar = part)
            .enqueueApi<UserResponse>("AuthRepo", "uploadAvatar") { resp, error ->
                when {
                    error != null -> callback(null, error)
                    resp?.success == true && resp.user != null -> {
                        saveUser(resp.user)
                        callback(resp.user, null)
                    }
                    else -> callback(null, ApiError.reported(resp?.error))
                }
            }
    }

    fun removeAvatar(callback: (User?, ApiError?) -> Unit) {
        apiService.removeAvatar().enqueueApi<UserResponse>("AuthRepo", "removeAvatar") { resp, error ->
            when {
                error != null -> callback(null, error)
                resp?.success == true && resp.user != null -> {
                    saveUser(resp.user)
                    callback(resp.user, null)
                }
                else -> callback(null, ApiError.reported(resp?.error))
            }
        }
    }

    fun updateNotificationPrefs(email: Boolean, push: Boolean, callback: (User?, ApiError?) -> Unit) {
        apiService.updateNotificationPrefs(email = email, push = push)
            .enqueueApi<UserResponse>("AuthRepo", "updateNotificationPrefs") { resp, error ->
                when {
                    error != null -> callback(null, error)
                    resp?.success == true && resp.user != null -> {
                        saveUser(resp.user)
                        callback(resp.user, null)
                    }
                    else -> callback(null, ApiError.reported(resp?.error))
                }
            }
    }

    suspend fun prepareAvatarBytes(uri: Uri, maxEdge: Int = 1024): ByteArray? =
        withContext(Dispatchers.IO) {
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

                var sample = 1
                while (bounds.outWidth / sample > maxEdge || bounds.outHeight / sample > maxEdge) {
                    sample *= 2
                }

                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                val bitmap = context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                } ?: return@withContext null

                ByteArrayOutputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    bitmap.recycle()
                    out.toByteArray()
                }
            } catch (e: Exception) {
                Log.e("AuthRepo", "prepareAvatarBytes failed: ${e.message}", e)
                null
            }
        }

    // ===== ROLE MANAGEMENT =====
    fun getCurrentRole(): String {
        return prefs.getString("user_current_role", "user") ?: "user"
    }

    fun saveCurrentRole(role: String) {
        prefs.edit().putString("user_current_role", role).apply()
        updateLastActivity()
    }

    fun getAvailableRoles(): List<String> {
        val rolesStr = prefs.getString("user_roles", "user") ?: "user"
        return rolesStr.split(",").filter { it.isNotEmpty() }
    }

    fun hasRole(role: String): Boolean = getAvailableRoles().contains(role)

    // ===== API CALLS =====
    fun login(email: String, password: String, callback: (LoginResponse?, ApiError?) -> Unit) {
        try {
            apiService.login(body = LoginRequest(email, password))
                .enqueueApi<LoginResponse>("AuthRepo", "login") { body, error ->
                    when {
                        error is ApiError.Unauthorized ->
                            callback(null, ApiError.Reported("Incorrect email or password."))

                        error != null -> callback(null, error)

                        body?.success == true && body.token != null && body.user != null -> {
                            saveToken(body.token)
                            saveUser(body.user)
                            updateLastActivity()
                            callback(body, null)
                        }

                        else -> callback(null, ApiError.reported(body?.error ?: "Incorrect email or password."))
                    }
                }
        } catch (e: Exception) {
            Log.e("AuthRepo", "Login exception: ${e.message}", e)
            callback(null, ApiError.Unexpected(e.message))
        }
    }

    fun register(request: RegisterRequest, callback: (RegisterResponse?, ApiError?) -> Unit) {
        apiService.register(body = request)
            .enqueueApi<RegisterResponse>("AuthRepo", "register") { body, error ->
                when {
                    error != null -> callback(null, error)
                    body?.success == true -> callback(body, null)
                    else -> callback(null, ApiError.reported(body?.error))
                }
            }
    }

    fun registerRider(request: RiderRegistrationRequest, callback: (RegisterResponse?, ApiError?) -> Unit) {
        try {
            apiService.registerRider(body = request)
                .enqueueApi<RegisterResponse>("AuthRepo", "registerRider") { body, error ->
                    when {
                        error != null -> callback(null, error)
                        body?.success == true -> callback(body, null)
                        else -> callback(null, ApiError.reported(body?.error))
                    }
                }
        } catch (e: Exception) {
            Log.e("AuthRepo", "registerRider exception: ${e.message}", e)
            callback(null, ApiError.Unexpected(e.message))
        }
    }

    // ===== PHONE / OTP SIGN-IN =====
    fun sendPhoneOtp(mobile: String, callback: (BaseResponse?, ApiError?) -> Unit) {
        try {
            apiService.sendPhoneOtp(mobile = mobile)
                .enqueueApi<BaseResponse>("AuthRepo", "sendPhoneOtp") { body, error ->
                    when {
                        error != null -> callback(null, error)
                        body?.success == true -> callback(body, null)
                        else -> callback(null, ApiError.reported(body?.error ?: "Could not send the verification code."))
                    }
                }
        } catch (e: Exception) {
            Log.e("AuthRepo", "sendPhoneOtp exception: ${e.message}", e)
            callback(null, ApiError.Unexpected(e.message))
        }
    }

    fun verifyPhoneOtp(mobile: String, code: String, callback: (PhoneVerifyResponse?, ApiError?) -> Unit) {
        try {
            apiService.verifyPhoneOtp(mobile = mobile, code = code)
                .enqueueApi<PhoneVerifyResponse>("AuthRepo", "verifyPhoneOtp") { body, error ->
                    when {
                        error != null -> callback(null, error)

                        body?.success == true && body.isNewUser == false && body.token != null && body.user != null -> {
                            saveToken(body.token)
                            saveUser(body.user)
                            updateLastActivity()
                            callback(body, null)
                        }

                        body?.success == true && body.isNewUser == true -> callback(body, null)

                        else -> callback(null, ApiError.reported(body?.error ?: "Incorrect code. Please try again."))
                    }
                }
        } catch (e: Exception) {
            Log.e("AuthRepo", "verifyPhoneOtp exception: ${e.message}", e)
            callback(null, ApiError.Unexpected(e.message))
        }
    }

    fun completePhoneSignup(
        mobile: String,
        proofToken: String,
        fname: String,
        lname: String,
        callback: (LoginResponse?, ApiError?) -> Unit
    ) {
        try {
            apiService.completePhoneSignup(mobile = mobile, proofToken = proofToken, fname = fname, lname = lname)
                .enqueueApi<LoginResponse>("AuthRepo", "completePhoneSignup") { body, error ->
                    when {
                        error != null -> callback(null, error)

                        body?.success == true && body.token != null && body.user != null -> {
                            saveToken(body.token)
                            saveUser(body.user)
                            updateLastActivity()
                            callback(body, null)
                        }

                        else -> callback(null, ApiError.reported(body?.error ?: "Could not create your account."))
                    }
                }
        } catch (e: Exception) {
            Log.e("AuthRepo", "completePhoneSignup exception: ${e.message}", e)
            callback(null, ApiError.Unexpected(e.message))
        }
    }

    fun logout(callback: (Boolean) -> Unit) {
        apiService.logout().enqueue(object : Callback<BaseResponse> {
            override fun onResponse(call: Call<BaseResponse>, response: Response<BaseResponse>) {
                clearSession()
                clearUser()
                callback(response.isSuccessful)
            }
            override fun onFailure(call: Call<BaseResponse>, t: Throwable) {
                clearSession()
                clearUser()
                callback(false)
            }
        })
    }

    // ===== PASSWORD RESET =====
    fun requestPasswordReset(email: String, callback: (success: Boolean, errorMessage: String?) -> Unit) {
        apiService.requestPasswordReset(email = email).enqueue(object : Callback<BaseResponse> {
            override fun onResponse(call: Call<BaseResponse>, response: Response<BaseResponse>) {
                val body = response.body()
                if (response.isSuccessful && body?.success == true) {
                    callback(true, null)
                } else {
                    callback(false, body?.error ?: "We could not send the reset email right now. Please try again later.")
                }
            }

            override fun onFailure(call: Call<BaseResponse>, t: Throwable) {
                Log.e("AuthRepo", "requestPasswordReset network failure: ${t.message}", t)
                callback(false, "Couldn't reach the server. Check your connection and try again.")
            }
        })
    }

    fun resetPassword(token: String, newPassword: String, callback: (Boolean, String?) -> Unit) {
        apiService.resetPassword(token = token, newPassword = newPassword)
            .enqueue(object : Callback<BaseResponse> {
                override fun onResponse(call: Call<BaseResponse>, response: Response<BaseResponse>) {
                    val body = response.body()
                    if (response.isSuccessful && body?.success == true) {
                        callback(true, null)
                    } else {
                        callback(false, body?.error ?: "That reset link is invalid or has expired.")
                    }
                }

                override fun onFailure(call: Call<BaseResponse>, t: Throwable) {
                    Log.e("AuthRepo", "resetPassword network failure: ${t.message}", t)
                    callback(false, "Couldn't reach the server. Check your connection and try again.")
                }
            })
    }

    fun switchRole(role: String, callback: (Boolean) -> Unit) {
        val previous = getCurrentRole()
        saveCurrentRole(role)

        apiService.switchRole(role = role)
            .enqueueApi<RoleSwitchResponse>("AuthRepo", "switchRole") { body, error ->
                if (error == null && body?.success == true) {
                    body.currentRole?.let { saveCurrentRole(it) }
                    callback(true)
                } else {
                    Log.w("AuthRepo", "switchRole rejected: ${body?.error ?: error?.userMessage}")
                    saveCurrentRole(previous)
                    callback(false)
                }
            }
    }

    // ===== GOOGLE SIGN-IN =====
    private fun googleSignInMessage(e: GetCredentialException): String = when (e) {
        is androidx.credentials.exceptions.NoCredentialException ->
            "No Google account is available to sign in with on this device, " +
                "or this app isn't set up for Google Sign-In yet. " +
                "Please sign in with your email and password."
        else ->
            "Google couldn't sign you in (${e.type}). " +
                "Try again, or use your email and password."
    }

    suspend fun googleLogin(idToken: String): LoginResponse? = withContext(Dispatchers.IO) {
        try {
            val response = apiService.googleLogin(mapOf("id_token" to idToken)).execute()
            if (response.isSuccessful) {
                val body = response.body()
                if (body?.success == true && body.token != null && body.user != null) {
                    saveToken(body.token)
                    saveUser(body.user)
                    updateLastActivity()
                }
                body
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("AuthRepo", "googleLogin exception: ${e.message}", e)
            null
        }
    }

    suspend fun signInWithGoogle(context: Context): GoogleSignInResult = withContext(Dispatchers.IO) {
        try {
            val serverClientId = context.getString(R.string.default_web_client_id)
            val googleIdOption = GetSignInWithGoogleOption.Builder(serverClientId)
                .setFilterByAuthorizedAccounts(false)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val credentialManager = CredentialManager.create(context)
            val result = credentialManager.getCredential(
                request = request,
                context = context
            )

            when (val credential = result.credential) {
                is CustomCredential -> {
                    if (credential.type == GoogleIdTokenCredential.MAX_GOOGLE_ID_TOKEN_CREDENTIAL_TYPE) {
                        try {
                            val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                            GoogleSignInResult.Success(
                                idToken = googleIdTokenCredential.idToken,
                                email = googleIdTokenCredential.id,
                                displayName = googleIdTokenCredential.displayName
                            )
                        } catch (e: GoogleIdTokenParsingException) {
                            Log.e("AuthRepo", "Received an invalid Google ID token response", e)
                            GoogleSignInResult.Error("Failed to parse Google sign-in credentials.")
                        }
                    } else {
                        GoogleSignInResult.Error("An unexpected credential type was received.")
                    }
                }
                else -> GoogleSignInResult.Error("Unsupported credential type encountered.")
            }
        } catch (e: GetCredentialCancellationException) {
            GoogleSignInResult.Cancelled("Sign-in was cancelled.")
        } catch (e: GetCredentialException) {
            Log.e("AuthRepo", "GetCredentialException during Google Sign-In: ${e.message}", e)
            GoogleSignInResult.Error(googleSignInMessage(e))
        } catch (e: Exception) {
            Log.e("AuthRepo", "Unexpected error during Google sign-in: ${e.message}", e)
            GoogleSignInResult.Error("An unexpected error occurred during Google Sign-In.")
        }
    }
}