package com.techaus.afamfresh.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Session expiry decides whether a user is silently signed out mid-use, so
 * the boundaries matter.
 */
class SessionValidityTest {

    private val timeout = 30 * 60 * 1000L // 30 minutes, matching AuthRepository
    private val now = 1_700_000_000_000L

    @Test
    fun `activity just now is valid`() {
        assertTrue(isSessionValid(lastActivity = now, now = now, timeoutMs = timeout))
    }

    @Test
    fun `activity within the window is valid`() {
        val fiveMinutesAgo = now - 5 * 60 * 1000L
        assertTrue(isSessionValid(fiveMinutesAgo, now, timeout))
    }

    @Test
    fun `activity just inside the window is valid`() {
        val almostExpired = now - (timeout - 1)
        assertTrue(isSessionValid(almostExpired, now, timeout))
    }

    /**
     * Exactly at the timeout is expired, not valid. Picking one side of this
     * boundary explicitly avoids an off-by-one that would keep sessions alive
     * a fraction longer than intended.
     */
    @Test
    fun `activity exactly at the timeout is expired`() {
        assertFalse(isSessionValid(now - timeout, now, timeout))
    }

    @Test
    fun `activity beyond the timeout is expired`() {
        assertFalse(isSessionValid(now - (timeout + 1), now, timeout))
        assertFalse(isSessionValid(now - 24 * 60 * 60 * 1000L, now, timeout))
    }

    /**
     * Zero means "never recorded" — a fresh install or a cleared session. It
     * must not be treated as an enormously old but arithmetically valid time.
     */
    @Test
    fun `never-recorded activity is invalid`() {
        assertFalse(isSessionValid(0L, now, timeout))
        assertFalse(isSessionValid(-1L, now, timeout))
    }

    /**
     * A clock moving backwards — timezone change, NTP correction, user editing
     * the date — produces a negative elapsed time. Without a guard that reads
     * as "less than the timeout" and therefore as a valid session, which would
     * let a long-expired session come back to life.
     */
    @Test
    fun `a clock that has moved backwards does not resurrect a session`() {
        val futureActivity = now + 60_000L
        assertFalse(isSessionValid(futureActivity, now, timeout))
    }
}
