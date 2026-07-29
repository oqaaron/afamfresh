package com.techaus.afamfresh.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted storage for anything that would let someone impersonate the user —
 * currently the auth token and the cached user profile.
 *
 * Plain SharedPreferences are stored as readable XML in the app's data
 * directory. That is protected from other apps on a healthy device, but not
 * from a rooted phone, an adb backup, or anyone with physical access to an
 * unlocked device. EncryptedSharedPreferences encrypts both keys and values
 * with a key held in the Android Keystore, which is hardware-backed where the
 * device supports it.
 *
 * ## Migration
 *
 * [create] transparently migrates any values already in the plaintext file the
 * first time it runs, then deletes the plaintext file. Existing logged-in users
 * are therefore not signed out by this change — which matters, because the
 * alternative is every current user being kicked to the login screen on update.
 *
 * ## Fallback
 *
 * If the Keystore is unavailable or corrupt (it does happen — some devices
 * invalidate keys on lock-screen changes, and the encrypted file is then
 * unreadable), this falls back to plain SharedPreferences rather than crashing
 * the app on launch. A user locked out of their own app by a Keystore fault is
 * a worse outcome than the storage being temporarily unencrypted, and the
 * fallback is logged.
 */
object SecurePrefs {

    private const val TAG = "SecurePrefs"

    /**
     * Opens [encryptedName], migrating anything found in [plaintextName] on
     * first use.
     */
    fun create(
        context: Context,
        plaintextName: String,
        encryptedName: String = "${plaintextName}_secure"
    ): SharedPreferences {
        val appContext = context.applicationContext

        val encrypted = try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                appContext,
                encryptedName,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Includes the case where a previously written encrypted file can
            // no longer be decrypted.
            Log.e(TAG, "EncryptedSharedPreferences unavailable, falling back to plaintext: ${e.message}", e)
            return appContext.getSharedPreferences(plaintextName, Context.MODE_PRIVATE)
        }

        migrateIfNeeded(appContext, plaintextName, encrypted)
        return encrypted
    }

    /**
     * Copies every value from the old plaintext file into [target], then
     * deletes the old file so the token does not remain readable on disk.
     */
    private fun migrateIfNeeded(
        context: Context,
        plaintextName: String,
        target: SharedPreferences
    ) {
        val legacy = context.getSharedPreferences(plaintextName, Context.MODE_PRIVATE)
        val entries = legacy.all
        if (entries.isEmpty()) return

        Log.d(TAG, "Migrating ${entries.size} value(s) from $plaintextName to encrypted storage")

        val editor = target.edit()
        entries.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Set<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    editor.putStringSet(key, value as Set<String>)
                }
                else -> Log.w(TAG, "Skipping $key: unsupported type ${value?.javaClass}")
            }
        }

        // commit(), not apply(): the plaintext file is deleted immediately
        // after, so the write must have landed first. This runs once, on a
        // handful of values, at repository construction.
        val migrated = editor.commit()

        if (migrated) {
            legacy.edit().clear().commit()
            context.deleteSharedPreferences(plaintextName)
            Log.d(TAG, "Migration complete, plaintext store removed")
        } else {
            Log.e(TAG, "Migration failed; leaving plaintext store intact so nothing is lost")
        }
    }
}
