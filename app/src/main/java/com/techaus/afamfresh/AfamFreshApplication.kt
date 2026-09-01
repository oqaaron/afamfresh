package com.techaus.afamfresh

import android.app.Application
import android.util.Log
import com.techaus.afamfresh.utils.SecurePrefs

class AfamFreshApplication : Application() {

    companion object {
        private const val TAG = "AfamFreshApplication"

        @Volatile
        var secureStorageReady = true
            private set
    }

    override fun onCreate() {
        super.onCreate()

        try {
            SecurePrefs.create(applicationContext, "auth_prefs")
            SecurePrefs.create(applicationContext, "cookie_prefs")
            SecurePrefs.create(applicationContext, "fcm_prefs")
            secureStorageReady = true
        } catch (e: IllegalStateException) {
            secureStorageReady = false
            Log.e(TAG, "Secure storage initialization failed; app startup is blocked.", e)
        }
    }
}
