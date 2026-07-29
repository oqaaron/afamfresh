package com.techaus.afamfresh.utils

import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * A classified failure, replacing the old pattern where repositories swallowed
 * everything and passed `null` to their callback, and ViewModels turned that
 * `null` into one generic string.
 *
 * The point is that a user could not tell the difference between no internet,
 * a server outage, and simply typing the wrong password — three problems with
 * three completely different responses from them.
 *
 * Each case carries a [userMessage] that says what to actually do, and
 * [isRetryable] so screens know whether offering a Retry button makes sense.
 */
sealed class ApiError {

    abstract val userMessage: String

    /** Whether retrying the same request could plausibly succeed. */
    open val isRetryable: Boolean = true

    /** The device has no usable connection. */
    data object Offline : ApiError() {
        override val userMessage =
            "You're offline. Check your connection and try again."
    }

    /** Connected, but the server didn't answer in time. */
    data object Timeout : ApiError() {
        override val userMessage =
            "The server is taking too long to respond. Try again in a moment."
    }

    /** Connected, but the server could not be reached at all. */
    data class Unreachable(val detail: String? = null) : ApiError() {
        override val userMessage =
            "Can't reach AfamFresh right now. It may be down — please try again shortly."
    }

    /** The session expired or was rejected. Retrying will not help. */
    data object Unauthorized : ApiError() {
        override val userMessage = "Your session has expired. Please sign in again."
        override val isRetryable = false
    }

    /** An HTTP error that isn't one of the specific cases above. */
    data class Http(val code: Int) : ApiError() {
        override val userMessage = when (code) {
            403 -> "You don't have permission to do that."
            404 -> "That's no longer available."
            in 500..599 -> "Something went wrong on our side. Please try again shortly."
            else -> "Something went wrong (error $code). Please try again."
        }

        // Client errors won't fix themselves; server errors might.
        override val isRetryable = code >= 500
    }

    /**
     * The request succeeded at the HTTP level but the API reported a failure —
     * e.g. wrong password, item out of stock. [message] is the server's own
     * wording, which is more specific than anything we could invent here.
     */
    data class Reported(val message: String) : ApiError() {
        override val userMessage = message
        override val isRetryable = false
    }

    /** Anything that doesn't fit — parse failures, unexpected exceptions. */
    data class Unexpected(val detail: String? = null) : ApiError() {
        override val userMessage =
            "Something unexpected went wrong. Please try again."
    }

    companion object {

        /**
         * Classifies a Retrofit `onFailure` throwable.
         *
         * Offline is checked first because an unreachable host on a phone with
         * no signal should read as "you're offline", not "our server is down".
         */
        fun from(t: Throwable): ApiError = when {
            !NetworkStatus.isOnline() -> Offline
            t is SocketTimeoutException -> Timeout
            t is UnknownHostException -> Unreachable(t.message)
            t is IOException -> Unreachable(t.message)
            else -> Unexpected(t.message)
        }

        /**
         * Classifies an unsuccessful HTTP response.
         *
         * 401 is special-cased so callers can trigger the sign-out flow rather
         * than showing a dead-end error.
         */
        fun from(response: Response<*>): ApiError = when (response.code()) {
            401 -> Unauthorized
            else -> Http(response.code())
        }

        /**
         * For a 200 response whose body reports `success: false`.
         * Falls back to a generic message when the server sends no wording.
         */
        fun reported(serverMessage: String?): ApiError =
            Reported(serverMessage?.takeIf { it.isNotBlank() } ?: "That didn't work. Please try again.")
    }
}
