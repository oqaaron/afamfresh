package com.techaus.afamfresh.utils

import android.content.Context
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import com.techaus.afamfresh.api.ApiClient
import com.techaus.afamfresh.models.BaseResponse
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

// Referenced by MainActivity.kt (`FirebaseTokenManager.initialize(applicationContext)`)
// but never defined — new file. The object-with-initialize(context) shape is
// confirmed by that call site.
object FirebaseTokenManager {

    private const val TAG = "FCM"
    private const val PREFS = "fcm_prefs"
    private const val KEY_TOKEN = "fcm_token"
    private const val KEY_REGISTERED = "fcm_registered"
    private const val KEY_ENABLED = "notifications_enabled"

    private var appContext: Context? = null

    private fun prefs() = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * The user's push-notification preference, controlled from Settings.
     * Defaults to on — a customer who has just placed an order expects to hear
     * about it.
     */
    fun areNotificationsEnabled(): Boolean =
        prefs()?.getBoolean(KEY_ENABLED, true) ?: true

    /**
     * Persists the preference and acts on it immediately.
     *
     * NOTE: there is no unregister endpoint in ApiService, so turning
     * notifications off cannot tell the server to stop sending them. What it
     * does do is stop this device registering its token, and
     * AfamFreshMessagingService drops incoming messages without posting them —
     * so the user sees nothing either way. Adding
     * `notifications.php?action=unregister_token` would let us stop the sends
     * at source, which is better for battery and data.
     */
    fun setNotificationsEnabled(enabled: Boolean) {
        prefs()?.edit()?.putBoolean(KEY_ENABLED, enabled)?.apply()
        if (enabled) {
            registerTokenWithBackend()
        } else {
            // Force re-registration if the user turns them back on later.
            prefs()?.edit()?.putBoolean(KEY_REGISTERED, false)?.apply()
        }
    }

    fun initialize(context: Context) {
        appContext = context.applicationContext

        FirebaseMessaging.getInstance().token
            .addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    Log.w(TAG, "Fetching FCM token failed", task.exception)
                    return@addOnCompleteListener
                }
                val token = task.result
                Log.d(TAG, "FCM token acquired")
                saveToken(token)
            }
    }

    fun getToken(): String? =
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.getString(KEY_TOKEN, null)

    private fun saveToken(token: String) {
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(KEY_TOKEN, token)
            ?.putBoolean(KEY_REGISTERED, false)
            ?.apply()
    }

    /**
     * Sends the FCM token to the backend so it can target this device.
     *
     * Must be called AFTER login: `notifications.php?action=register_token` is
     * authenticated, so calling it without a session just 401s. It is invoked
     * from MainActivity when `authViewModel.user` first emits non-null.
     */
    fun registerTokenWithBackend(deviceId: String? = null, onResult: (Boolean) -> Unit = {}) {
        if (!areNotificationsEnabled()) {
            Log.d(TAG, "Notifications disabled by user — not registering token")
            onResult(false)
            return
        }

        val token = getToken()
        if (token.isNullOrEmpty()) {
            Log.w(TAG, "No FCM token available to register")
            onResult(false)
            return
        }

        ApiClient.apiService.registerFCMToken(fcmToken = token, deviceId = deviceId)
            .enqueue(object : Callback<BaseResponse> {
                override fun onResponse(call: Call<BaseResponse>, response: Response<BaseResponse>) {
                    val ok = response.isSuccessful && response.body()?.success == true
                    if (ok) {
                        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            ?.edit()?.putBoolean(KEY_REGISTERED, true)?.apply()
                    }
                    Log.d(TAG, "Token registration result: $ok")
                    onResult(ok)
                }

                override fun onFailure(call: Call<BaseResponse>, t: Throwable) {
                    Log.e(TAG, "Token registration failed: ${t.message}", t)
                    onResult(false)
                }
            })
    }
}
