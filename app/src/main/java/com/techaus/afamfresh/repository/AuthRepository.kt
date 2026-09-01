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
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
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

    /**
     * Delegates to the pure [isSessionValid] so the rule itself is unit-tested.
     *
     * This also fixes two edge cases the inline arithmetic got wrong: a
     * lastActivity of 0 (never recorded) used to compare as a huge elapsed
     * time and happened to work, and a clock moving backwards produced a
     * negative elapsed time that read as a valid session.
     */
    // Fully qualified: this member has the same name as the free function, and
    // a member always wins resolution over an imported top-level, so an
    // unqualified call here would recurse into itself.
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
        // The server-side session lives in a cookie, not the token. Leaving it
        // behind means the app keeps presenting the old PHPSESSID after the
        // user has supposedly been signed out.
        ApiClient.clearCookies()
    }

    // ===== USER MANAGEMENT =====
    fun saveUser(user: User) {
        // roles/currentRole are declared non-null with defaults, but Gson sets
        // fields reflectively and skips the constructor, so a response missing
        // those keys yields null at runtime and the defaults never apply.
        // Treat them as nullable here regardless of what the types claim.
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
            // Booleans are stored as "1"/"0"/absent rather than via putBoolean:
            // getBoolean cannot represent "unknown", and unknown is exactly what
            // a cache written by an older build legitimately means. Collapsing
            // that to false would hide the change-password option from every
            // user who upgraded without signing out again.
            .putString("user_has_password", user.hasPassword?.let { if (it) "1" else "0" })
            .putString("user_is_google", user.isGoogleAccount?.let { if (it) "1" else "0" })
            .putString("user_loyalty", user.loyaltyPoints?.toString())
            .putString("user_notif_email", user.notificationPreferences?.email?.let { if (it) "1" else "0" })
            .putString("user_notif_push", user.notificationPreferences?.push?.let { if (it) "1" else "0" })
            .apply()
        updateLastActivity()
    }

    /**
     * The cached profile, or null when nobody is stored.
     *
     * The previous `prefs.getString("user_id", "") ?: return null` never
     * returned null: getString returns its DEFAULT when the key is absent, so
     * a missing user produced "" and the elvis branch was unreachable. Every
     * caller therefore got a blank User (id="", name="", email="") instead of
     * null — and since MainActivity treats a non-null user as "signed in",
     * a fresh install silently entered the app as that empty phantom account
     * and the login screen was never shown.
     */
    fun getUser(): User? {
        val id = prefs.getString("user_id", null)
        if (id.isNullOrEmpty()) return null
        val name = prefs.getString("user_name", "") ?: ""
        val email = prefs.getString("user_email", "") ?: ""
        val mobile = prefs.getString("user_mobile", null)
        val roles = prefs.getString("user_roles", "user")?.split(",")?.filter { it.isNotEmpty() } ?: listOf("user")
        val currentRole = prefs.getString("user_current_role", "user") ?: "user"

        // Every field below reads with a null default, so a cache written by a
        // build that predates these keys deserialises to exactly the old
        // behaviour — no migration, and nobody gets signed out by upgrading.
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

    /**
     * The cached profile, but only when the session may still be resumed.
     *
     * This is what app startup should ask for, not [getUser]. A stored profile
     * outlives the session it came from: it stays in prefs until the user
     * signs out, so restoring from it alone would sign someone back in days
     * later. Gating on [isLoggedIn] keeps the rule in one place — a token
     * exists AND the last activity was inside the 30 minute window — so
     * reopening the app soon after resumes silently, while opening it cold or
     * after the timeout lands on the login screen.
     */
    fun getRestorableUser(): User? = if (isLoggedIn()) getUser() else null

    fun clearUser() {
        // Every key written by saveUser() must be removed here, or a signed-out
        // device keeps the previous person's name, avatar and email on disk.
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
    //
    // Every success path here calls saveUser(). That is the single mechanism
    // keeping the encrypted cache in step with the server; skip it in one
    // place and the app shows stale data until the next sign-in.

    /**
     * Pulls the current profile from the server.
     *
     * `getCurrentUser()` has existed in ApiService since the beginning but was
     * never called anywhere, which is why the profile screen only ever showed
     * whatever the cache happened to hold from sign-in time.
     */
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
                    // The server's wording is the useful part here ("that email
                    // is already used by another account"), so pass it through
                    // rather than substituting a generic failure.
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

    /**
     * Uploads [imageBytes] as the profile picture.
     *
     * The caller passes already-downscaled JPEG bytes — see
     * [prepareAvatarBytes], which must run off the main thread.
     */
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

    /**
     * Reads a picked image and returns downscaled JPEG bytes, or null.
     *
     * Downscaling is not cosmetic: a photo straight from a modern phone camera
     * is comfortably past the server's 5 MB limit, so uploading the original
     * would fail for most real pictures. Decoding also allocates, so this is a
     * suspend function pinned to Dispatchers.IO — doing it on the main thread
     * would jank or OOM on a large image.
     */
    suspend fun prepareAvatarBytes(uri: Uri, maxEdge: Int = 1024): ByteArray? =
        withContext(Dispatchers.IO) {
            try {
                // First pass reads only the dimensions, not the pixels.
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
    // Third sign-in mechanism alongside password (login/register above) and
    // Google (further down). Follows login/register's callback+enqueueApi
    // style rather than googleLogin's suspend one — that's the majority
    // pattern in this file, and there's no reason for phone auth specifically
    // to be the odd one out.

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

                        // Existing account — this call IS the login, same
                        // shape of consequence as login()/googleLogin() above.
                        body?.success == true && body.isNewUser == false && body.token != null && body.user != null -> {
                            saveToken(body.token)
                            saveUser(body.user)
                            updateLastActivity()
                            callback(body, null)
                        }

                        // New number — verified, but no account yet. Nothing
                        // to save to session here; the caller moves on to
                        // completePhoneSignup() with body.proofToken.
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

    /**
     * Asks the backend to email a reset link.
     *
     * Unknown addresses still resolve as success so account existence is not
     * disclosed. Provider failures for a real account are returned as a
     * generic error so the UI does not claim an email was sent.
     */
    fun requestPasswordReset(email: String, callback: (success: Boolean, errorMessage: String?) -> Unit) {
        apiService.requestPasswordReset(email = email).enqueue(object : Callback<BaseResponse> {
            override fun onResponse(call: Call<BaseResponse>, response: Response<BaseResponse>) {
                if (!response.isSuccessful) {
                    Log.w("AuthRepo", "requestPasswordReset HTTP ${response.code()}")
                }
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

    /**
     * Switches the active role, on the server as well as locally.
     *
     * This used to write SharedPreferences and return true without ever
     * calling the backend, because `auth.php?action=switch_role` did not
     * exist. That made the choice device-local and invisible to the server —
     * and once ProfileScreen started calling refreshUser(), the server's
     * unchanged current_role overwrote it on the very next visit.
     *
     * The local write still happens first so the UI updates immediately, and
     * is rolled back if the server rejects the change (which it does for a
     * role the user does not actually hold).
     */
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

    /**
     * A sentence explaining a Google Sign-In failure, from the exception
     * Credential Manager threw.
     *
     * Credential Manager doesn't expose a numeric status code the way the
     * legacy API's GoogleSignInStatusCodes did — NoCredentialException is the
     * one worth naming precisely, since it's what a missing/misconfigured
     * OAuth client registration in Firebase surfaces as here (the same real
     * cause the old DEVELOPER_ERROR branch used to name), not a generic
     * failure. Anything else falls back to a message that still points the
     * user toward email/password, with the real exception type logged for
     * debugging rather than guessed at.
     */
    private fun googleSignInMessage(e: GetCredentialException): String = when (e) {
        is androidx.credentials.exceptions.NoCredentialException ->
            "No Google account is available to sign in with on this device, " +
                "or this app isn't set up for Google Sign-In yet. " +
                "Please sign in with your email and password."
        else ->
            "Google couldn't sign you in (${e.type}). " +
                "Try again, or use your email and password."
    }

    /**
     * Runs the whole Credential Manager flow — building the request, showing
     * the account picker, and parsing the result — in one suspend call.
     *
     * This replaces what used to be two separate functions:
     * getGoogleSignInClient() (build an Intent to launch) and
     * handleGoogleSignInResult() (parse what came back from
     * onActivityResult). That split doesn't exist anymore because Credential
     * Manager has no Activity Result step at all — getCredential() is a
     * single suspend call — so LoginScreen/RegisterScreen no longer need an
     * ActivityResultLauncher or a separate result-handling call either.
     *
     * default_web_client_id is generated by the google-services plugin from
     * app/google-services.json, so it cannot disagree with the project the
     * rest of Firebase is configured for — same reasoning as before, still
     * true here since Credential Manager's Google ID option wants the same
     * web/server client id requestIdToken() used to.
     */
    suspend fun signInWithGoogle(context: Context): GoogleSignInResult {
        // GetSignInWithGoogleOption, not GetGoogleIdOption. GetGoogleIdOption
        // is built for the automatic "One Tap" bottom-sheet prompt that
        // appears without the user tapping anything — using it behind an
        // explicit "Continue with Google" button (this app's actual UX)
        // produced a specific, documented failure: "During begin sign in,
        // failure response from one tap: 16: [28433] Cannot find a matching
        // credential." That exact error is filed against Google's own
        // android/identity-samples repo (issue #94) with matching code,
        // confirming it's a known mismatch between option type and UX
        // pattern, not a bug in this implementation. GetSignInWithGoogleOption
        // is the option meant for a deliberate button tap like this one.
        //
        // The constructor takes serverClientId directly, not a builder
        // method — a real shape difference from GetGoogleIdOption, not a
        // typo.
        val signInWithGoogleOption = GetSignInWithGoogleOption
            .Builder(context.getString(R.string.default_web_client_id))
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(signInWithGoogleOption)
            .build()

        return try {
            val credentialManager = CredentialManager.create(context)
            // Credential Manager has a documented bug where getCredential()
            // can hang indefinitely — no exception, no crash — if the picker
            // is interacted with before it's fully finished rendering (see
            // android/identity-samples issue #113). withTimeoutOrNull turns
            // that into a clear failure after 30 seconds instead of leaving
            // the user stuck forever with a disabled button and no feedback.
            val result = withTimeoutOrNull(30_000L) {
                credentialManager.getCredential(context, request)
            } ?: run {
                Log.e("AuthRepo", "Google sign-in timed out waiting for Credential Manager to respond")
                return GoogleSignInResult.Error(
                    "Google sign-in took too long to respond. Please try again, or use your email and password."
                )
            }
            val credential = result.credential

            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                try {
                    val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                    GoogleSignInResult.Success(googleIdTokenCredential.idToken)
                } catch (e: GoogleIdTokenParsingException) {
                    Log.e("AuthRepo", "Google ID token parsing failed: ${e.message}", e)
                    GoogleSignInResult.Error("No ID token found")
                }
            } else {
                // Previously silent — nothing logged here meant a failure on
                // this specific path was invisible in logcat, showing only
                // as "Sign-in failed" on screen with no trace of why.
                // credential.javaClass.name shows exactly what type Google
                // actually returned instead of the expected GoogleIdTokenCredential.
                Log.e("AuthRepo", "Google sign-in returned an unexpected credential type: ${credential.javaClass.name}")
                GoogleSignInResult.Error("Sign-in failed")
            }
        } catch (e: GetCredentialCancellationException) {
            // The user backed out of the account chooser. Not a failure, and
            // showing them an error for their own deliberate action reads as
            // a bug — same reasoning the legacy SIGN_IN_CANCELLED branch had.
            GoogleSignInResult.Cancelled
        } catch (e: GetCredentialException) {
            Log.e("AuthRepo", "Google sign-in failed: ${e.type}", e)
            GoogleSignInResult.Error(googleSignInMessage(e))
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
                }
                // Returned even on success=false, not collapsed to null: the
                // caller (AuthViewModel) reads loginResponse.error to show the
                // real reason (e.g. "This is a Customer account...") instead
                // of a generic fallback that hid it.
                loginResponse
            } else {
                Log.e("AuthRepo", "googleLogin HTTP ${response.code()}: ${response.errorBody()?.string()}")
                null
            }
        } catch (e: Exception) {
            Log.e("AuthRepo", "googleLogin failed: ${e.message}", e)
            null
        }
    }

    sealed class GoogleSignInResult {
        // account field removed: it was never read anywhere in
        // AuthRepository/AuthViewModel/LoginScreen/RegisterScreen (confirmed
        // by search across all four before removing it), and Credential
        // Manager has no equivalent to produce — it returns a
        // GoogleIdTokenCredential, not a GoogleSignInAccount, so there was no
        // way to keep populating this field even if something elsewhere did
        // read it. Worth a repo-wide search for `.account` on this type if
        // you have visibility I don't.
        data class Success(val idToken: String) : GoogleSignInResult()
        data class Error(val message: String) : GoogleSignInResult()

        /** The user dismissed the account chooser — nothing to report. */
        object Cancelled : GoogleSignInResult()
    }
}
