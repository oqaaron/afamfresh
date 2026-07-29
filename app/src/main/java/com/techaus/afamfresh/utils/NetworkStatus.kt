package com.techaus.afamfresh.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Answers "is there a usable connection right now?".
 *
 * This exists so the app can tell the difference between *the phone has no
 * internet* and *the server is broken* — previously both surfaced as the same
 * generic "Unable to load" message, leaving the user with no idea whether to
 * check their wifi or just wait.
 */
object NetworkStatus {

    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Returns true when a network is connected AND validated (i.e. actually
     * reached the internet). NET_CAPABILITY_VALIDATED matters: a phone joined
     * to a captive-portal wifi is "connected" but cannot reach the API, and
     * treating that as online produces a confusing timeout instead of a clear
     * "you're offline" message.
     *
     * Fails OPEN — if the capability can't be read for any reason, we assume
     * online and let the request itself decide. Wrongly claiming the user is
     * offline would block them for no reason.
     */
    fun isOnline(): Boolean {
        val cm = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true

        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false

        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
