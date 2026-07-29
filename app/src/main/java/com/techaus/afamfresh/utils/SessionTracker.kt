package com.techaus.afamfresh.utils

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Owns "when did the user last do something?" and broadcasts session expiry.
 *
 * Two problems this solves:
 *
 *  1. `updateLastActivity()` was only called on login, role switch and save.
 *     A user could browse for an hour and still be considered idle, because
 *     nothing counted browsing as activity. The OkHttp interceptor in
 *     ApiClient now calls [touch] on every successful authenticated response,
 *     which is a good proxy for real use.
 *
 *  2. Nothing handled a session lapsing mid-use. Once the server started
 *     returning 401, API calls simply failed with no explanation. [expired]
 *     lets MainActivity react once, centrally.
 *
 * Reads and writes the same SharedPreferences file and key AuthRepository used
 * before, so existing sessions are unaffected by this change.
 */
/**
 * Whether a session that was last active at [lastActivity] is still valid at
 * [now], given [timeoutMs].
 *
 * A free function rather than a method so it can be unit-tested without an
 * Android Context or SharedPreferences. Session expiry decides whether a user
 * gets silently signed out, so it is worth testing at the boundaries.
 *
 * A [lastActivity] of 0 means "never recorded" and is always invalid — which
 * is what makes a fresh install, or a cleared session, start at the login
 * screen rather than trusting a stale token.
 */
fun isSessionValid(lastActivity: Long, now: Long, timeoutMs: Long): Boolean {
    if (lastActivity <= 0L) return false
    // A clock that has moved backwards (timezone change, NTP correction) would
    // otherwise produce a negative elapsed time and look like a fresh session.
    val elapsed = now - lastActivity
    if (elapsed < 0L) return false
    return elapsed < timeoutMs
}

object SessionTracker {

    private const val PREFS = "auth_prefs"
    private const val KEY_LAST_ACTIVITY = "last_activity"

    private var prefs: SharedPreferences? = null

    /**
     * `extraBufferCapacity = 1` with no replay: a 401 arriving before anyone
     * is collecting is not lost, but a later collector does not get replayed
     * an old expiry and bounce the user out of a fresh session.
     */
    private val _expired = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
    val expired: SharedFlow<Unit> = _expired.asSharedFlow()

    /**
     * Uses the same encrypted store as AuthRepository, deliberately.
     *
     * If this kept reading the plaintext `auth_prefs` while AuthRepository
     * migrated that file to encrypted storage and deleted it, `last_activity`
     * would read as 0 on the next launch, isSessionValid() would return false,
     * and every already-signed-in user would be logged out by the upgrade.
     */
    fun initialize(context: Context) {
        prefs = SecurePrefs.create(context.applicationContext, PREFS)
    }

    /** Records that the user is active right now. */
    fun touch() {
        prefs?.edit()?.putLong(KEY_LAST_ACTIVITY, System.currentTimeMillis())?.apply()
    }

    fun lastActivity(): Long = prefs?.getLong(KEY_LAST_ACTIVITY, 0) ?: 0

    fun clearActivity() {
        prefs?.edit()?.remove(KEY_LAST_ACTIVITY)?.apply()
    }

    /**
     * Called by the OkHttp interceptor on a 401. Safe to call repeatedly — a
     * burst of parallel 401s collapses into at most one buffered emission.
     */
    fun notifyExpired() {
        _expired.tryEmit(Unit)
    }
}
