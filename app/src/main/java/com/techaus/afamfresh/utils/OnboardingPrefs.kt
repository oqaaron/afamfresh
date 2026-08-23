package com.techaus.afamfresh.utils

import android.content.Context

/**
 * Whether the customer has already been through the onboarding slides.
 *
 * Plain SharedPreferences rather than DataStore: a single boolean flag
 * doesn't need DataStore's async/Flow machinery, and this avoids introducing
 * a new Gradle dependency (androidx.datastore:datastore-preferences) that
 * may not already be in this project's build.gradle. Nothing else in
 * MainActivity.kt uses a local-storage pattern I could confirm and reuse
 * instead — if the app adopts one elsewhere later, this is a natural
 * candidate to migrate onto it too.
 */
object OnboardingPrefs {
    private const val PREFS_NAME = "afamfresh_onboarding"
    private const val KEY_HAS_SEEN_ONBOARDING = "has_seen_onboarding"

    fun hasSeenOnboarding(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_HAS_SEEN_ONBOARDING, false)

    fun markOnboardingSeen(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_HAS_SEEN_ONBOARDING, true)
            .apply()
    }
}
