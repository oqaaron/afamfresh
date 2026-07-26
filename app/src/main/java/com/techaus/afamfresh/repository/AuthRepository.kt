package com.techaus.afamfresh.repository

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.techaus.afamfresh.api.ApiService
import com.techaus.afamfresh.models.BaseResponse
import com.techaus.afamfresh.models.LoginRequest
import com.techaus.afamfresh.models.LoginResponse
import com.techaus.afamfresh.models.RegisterRequest
import com.techaus.afamfresh.models.RegisterResponse
import com.techaus.afamfresh.models.User
import kotlinx.coroutines.suspendCancellableCoroutine
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AuthRepository(
    private val apiService: ApiService,
    private val context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val SESSION_TIMEOUT_MS = 30 * 60 * 1000L // 15 minutes
        private const val KEY_LAST_ACTIVITY = "last_activity"
    }

    // ===== TOKEN MANAGEMENT =====
    // ✅ FIX: Changed apply() to commit() for synchronous writes
    fun saveToken(token: String) {
        prefs.edit().putString("auth_token", token).commit()
        updateLastActivity()
    }

    fun getToken(): String? = prefs.getString("auth_token", null)

    fun clearToken() {
        prefs.edit().remove("auth_token").commit()
    }

    // ===== SESSION TIMEOUT =====
    fun updateLastActivity() {
        prefs.edit().putLong(KEY_LAST_ACTIVITY, System.currentTimeMillis()).commit()
    }

    fun getLastActivity(): Long {
        return prefs.getLong(KEY_LAST_ACTIVITY, 0)
    }

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
        prefs.edit().remove(KEY_LAST_ACTIVITY).commit()
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
            .commit() // ✅ Fixed
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
            .commit() // ✅ Fixed
    }

    // ===== ROLE MANAGEMENT =====
    fun getCurrentRole(): String {
        return prefs.getString("user_current_role", "user") ?: "user"
    }

    fun saveCurrentRole(role: String) {
        prefs.edit().putString("user_current_role", role).commit() // ✅ Fixed
        updateLastActivity()
    }

    fun getAvailableRoles(): List<String> {
        val rolesStr = prefs.getString("user_roles", "user") ?: "user"
        return rolesStr.split(",").filter { it.isNotEmpty() }
    }

    fun hasRole(role: String): Boolean = getAvailableRoles().contains(role)

    // ===== API CALLS =====
    fun login(email: String, password: String, callback: (LoginResponse?) -> Unit) {
    try {
        apiService.login(body = LoginRequest(email, password)).enqueue(object : Callback<LoginResponse> {
            override fun onResponse(call: Call<LoginResponse>, response: Response<LoginResponse>) {
                Log.d("AuthRepo", "Login response received. Code: ${response.code()}")
                if (response.isSuccessful) {
                    val loginResponse = response.body()
                    if (loginResponse?.success == true && loginResponse.token != null && loginResponse.user != null) {
                        saveToken(loginResponse.token)
                        saveUser(loginResponse.user)
                        updateLastActivity()
                    } else {
                        Log.e("AuthRepo", "Login failed: success=${loginResponse?.success}, token=${loginResponse?.token != null}")
                    }
                    callback(loginResponse)
                } else {
                    Log.e("AuthRepo", "Login HTTP error: ${response.code()} - ${response.message()}")
                    callback(null)
                }
            }

            override fun onFailure(call: Call<LoginResponse>, t: Throwable) {
                Log.e("AuthRepo", "Login network failure: ${t.message}", t)
                callback(null)
            }
        })
    } catch (e: Exception) {
        Log.e("AuthRepo", "Login exception: ${e.message}", e)
        callback(null)
    }
}

    fun register(request: RegisterRequest, callback: (RegisterResponse?) -> Unit) {
        apiService.register(body = request).enqueue(object : Callback<RegisterResponse> {
            override fun onResponse(call: Call<RegisterResponse>, response: Response<RegisterResponse>) {
                if (response.isSuccessful) {
                    callback(response.body())
                } else {
                    callback(null)
                }
            }
            override fun onFailure(call: Call<RegisterResponse>, t: Throwable) {
                callback(null)
            }
        })
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

    suspend fun googleLogin(idToken: String): LoginResponse? {
        return try {
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
            null
        }
    }

    sealed class GoogleSignInResult {
        data class Success(val idToken: String, val account: com.google.android.gms.auth.api.signin.GoogleSignInAccount) : GoogleSignInResult()
        data class Error(val message: String) : GoogleSignInResult()
    }
}