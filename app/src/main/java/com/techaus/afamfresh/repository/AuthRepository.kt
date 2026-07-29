package com.techaus.afamfresh.repository

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.techaus.afamfresh.api.ApiClient
import com.techaus.afamfresh.api.ApiService
import com.techaus.afamfresh.models.BaseResponse
import com.techaus.afamfresh.models.LoginRequest
import com.techaus.afamfresh.models.LoginResponse
import com.techaus.afamfresh.models.RegisterRequest
import com.techaus.afamfresh.models.RegisterResponse
import com.techaus.afamfresh.models.User
import com.techaus.afamfresh.utils.ApiError
import com.techaus.afamfresh.utils.SessionTracker
import com.techaus.afamfresh.utils.SecurePrefs
import com.techaus.afamfresh.utils.enqueueApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AuthRepository(
    private val apiService: ApiService,
    private val context: Context
) {
    // Encrypted: this file holds the auth token and the cached user profile.
    // Existing plaintext values are migrated on first open, so users who are
    // already signed in stay signed in across this change.
    private val prefs: SharedPreferences = SecurePrefs.create(context, "auth_prefs")

    companion object {
        private const val SESSION_TIMEOUT_MS = 30 * 60 * 1000L // 30 minutes
    }

    // ===== TOKEN MANAGEMENT =====
    // These all use apply() rather than commit(). commit() performs the disk
    // write on the calling thread, and every one of these is called from the
    // main thread (login callbacks, role switches, activity tracking), so it
    // was blocking the UI on file I/O.
    //
    // No call site needed restructuring to make this safe: apply() updates the
    // in-memory preference map SYNCHRONOUSLY and only defers the disk write.
    // A getToken() immediately after saveToken() therefore still reads the new
    // value — the read never goes to disk. The only behaviour given up is
    // durability if the process is killed within milliseconds of the write,
    // which is the standard trade-off and far preferable to an ANR.
    fun saveToken(token: String) {
        prefs.edit().putString("auth_token", token).apply()
        updateLastActivity()
    }

    fun getToken(): String? = prefs.getString("auth_token", null)

    fun clearToken() {
        prefs.edit().remove("auth_token").apply()
    }

    // ===== SESSION TIMEOUT =====
    // Delegated to SessionTracker so that the OkHttp interceptor and this
    // class cannot disagree about when the user was last active. The
    // interceptor calls touch() on every successful response, which is what
    // makes the timeout measure genuine idleness rather than time since login.
    fun updateLastActivity() = SessionTracker.touch()

    fun getLastActivity(): Long = SessionTracker.lastActivity()

    fun isSessionValid(): Boolean {
        val lastActivity = getLastActivity()
        val now = System.currentTimeMillis()
        return (now - lastActivity) < SESSION_TIMEOUT_MS
    }

    // ===== LOGIN STATUS =====
    fun isLoggedIn(): Boolean {
        val token = getToken()
        return !token.isNullOrEmpty() && isSessionValid()
    }

    fun clearSession() {
        clearToken()
        SessionTracker.clearActivity()
        // The server-side session lives in a cookie, not the token. Leaving it
        // behind means the app keeps presenting the old PHPSESSID after the
        // user has supposedly been signed out.
        ApiClient.clearCookies()
    }

    // ===== USER MANAGEMENT =====
    fun saveUser(user: User) {
        prefs.edit()
            .putString("user_id", user.id)
            .putString("user_name", user.name)
            .putString("user_email", user.email)
            .putString("user_mobile", user.mobile)
            .putString("user_roles", user.roles.joinToString(","))
            .putString("user_current_role", user.currentRole)
            .apply()
        updateLastActivity()
    }

    fun getUser(): User? {
        val id = prefs.getString("user_id", "") ?: return null
        val name = prefs.getString("user_name", "") ?: ""
        val email = prefs.getString("user_email", "") ?: ""
        val mobile = prefs.getString("user_mobile", null)
        val roles = prefs.getString("user_roles", "user")?.split(",")?.filter { it.isNotEmpty() } ?: listOf("user")
        val currentRole = prefs.getString("user_current_role", "user") ?: "user"
        return User(
            id = id,
            name = name,
            email = email,
            mobile = mobile,
            roles = roles,
            currentRole = currentRole
        )
    }

    fun clearUser() {
        prefs.edit()
            .remove("user_id")
            .remove("user_name")
            .remove("user_email")
            .remove("user_mobile")
            .remove("user_roles")
            .remove("user_current_role")
            .apply()
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
    /**
     * A 401 here means "wrong email or password", not "your session expired" —
     * there is no session yet. It is mapped explicitly so the generic session
     * message never appears on the login screen.
     */
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
                    // The server's wording matters most here — "that email is
                    // already registered" is far more useful than a generic
                    // failure, and only the backend knows it.
                    else -> callback(null, ApiError.reported(body?.error))
                }
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

    /**
     * Asks the backend to email a reset link.
     *
     * Reports success to the caller even on an HTTP error, deliberately: the
     * UI must not reveal whether an address has an account. Genuine network
     * failures are still surfaced as false so the user can retry.
     */
    fun requestPasswordReset(email: String, callback: (success: Boolean, networkError: Boolean) -> Unit) {
        apiService.requestPasswordReset(email = email).enqueue(object : Callback<BaseResponse> {
            override fun onResponse(call: Call<BaseResponse>, response: Response<BaseResponse>) {
                if (!response.isSuccessful) {
                    Log.w("AuthRepo", "requestPasswordReset HTTP ${response.code()}")
                }
                callback(true, false)
            }

            override fun onFailure(call: Call<BaseResponse>, t: Throwable) {
                Log.e("AuthRepo", "requestPasswordReset network failure: ${t.message}", t)
                callback(false, true)
            }
        })
    }

    /** Completes the reset using the token from the emailed deep link. */
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
        saveCurrentRole(role)
        callback(true)
    }

    // ===== GOOGLE SIGN-IN =====

    fun getGoogleSignInClient(): GoogleSignInClient {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken("736537583604-anb6k5tufbkkvbskvg02f4iatl93tuuf.apps.googleusercontent.com")
            .requestEmail()
            .build()
        return GoogleSignIn.getClient(context, gso)
    }

    suspend fun handleGoogleSignInResult(data: Intent?): GoogleSignInResult {
        return suspendCancellableCoroutine { continuation ->
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            task.addOnCompleteListener { task ->
                try {
                    val account = task.getResult(ApiException::class.java)
                    if (account != null) {
                        val idToken = account.idToken
                        if (idToken != null) {
                            continuation.resume(GoogleSignInResult.Success(idToken, account))
                        } else {
                            continuation.resume(GoogleSignInResult.Error("No ID token found"))
                        }
                    } else {
                        continuation.resume(GoogleSignInResult.Error("Sign-in failed"))
                    }
                } catch (e: ApiException) {
                    continuation.resume(GoogleSignInResult.Error(e.message ?: "Sign-in error"))
                }
            }
        }
    }

    /**
     * Retrofit's [Call.execute] is a BLOCKING network call. This is a suspend
     * function with no dispatcher of its own, so it inherited whatever context
     * the caller had — and it is invoked from `viewModelScope`, which is
     * Dispatchers.Main. That froze the UI for the whole round trip.
     *
     * withContext(Dispatchers.IO) moves the blocking call off the main thread
     * while keeping the simple sequential shape of the function. The
     * saveToken / saveUser calls inside now also run on IO, which is a bonus:
     * they touch SharedPreferences.
     */
    suspend fun googleLogin(idToken: String): LoginResponse? = withContext(Dispatchers.IO) {
        try {
            val response = apiService.googleLogin(idToken = idToken).execute()
            if (response.isSuccessful) {
                val loginResponse = response.body()
                if (loginResponse?.success == true && loginResponse.user != null) {
                    loginResponse.token?.let { saveToken(it) }
                    saveUser(loginResponse.user)
                    updateLastActivity()
                    loginResponse
                } else {
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("AuthRepo", "googleLogin failed: ${e.message}", e)
            null
        }
    }

    sealed class GoogleSignInResult {
        data class Success(val idToken: String, val account: com.google.android.gms.auth.api.signin.GoogleSignInAccount) : GoogleSignInResult()
        data class Error(val message: String) : GoogleSignInResult()
    }
}
