package com.techaus.afamfresh.repository

import android.util.Log
import com.techaus.afamfresh.api.ApiService
import com.techaus.afamfresh.models.AppConfigResponse
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

// Constructor confirmed by MainActivity.kt: AppRepository(ApiClient.apiService)
// Wraps config.php, which SplashScreen uses to decide whether the app may proceed.
class AppRepository(
    private val apiService: ApiService
) {
    /**
     * Fetches remote app config.
     *
     * Fails OPEN: if the request errors, times out, or the endpoint doesn't
     * exist, this returns null and the caller should let the user through.
     * Failing closed here would mean any backend hiccup locks every user out
     * of the app entirely — which is what the `shouldProceed = true` override
     * in MainActivity was working around.
     */
    fun getAppConfig(callback: (AppConfigResponse?) -> Unit) {
        try {
            apiService.getAppConfig().enqueue(object : Callback<AppConfigResponse> {
                override fun onResponse(call: Call<AppConfigResponse>, response: Response<AppConfigResponse>) {
                    if (response.isSuccessful) {
                        callback(response.body())
                    } else {
                        Log.w("AppRepo", "getAppConfig HTTP ${response.code()} — treating as no config")
                        callback(null)
                    }
                }

                override fun onFailure(call: Call<AppConfigResponse>, t: Throwable) {
                    Log.w("AppRepo", "getAppConfig failed: ${t.message} — treating as no config")
                    callback(null)
                }
            })
        } catch (e: Exception) {
            Log.e("AppRepo", "getAppConfig exception: ${e.message}", e)
            callback(null)
        }
    }
}
