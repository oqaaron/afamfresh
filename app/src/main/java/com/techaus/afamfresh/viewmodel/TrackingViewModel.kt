package com.techaus.afamfresh.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techaus.afamfresh.models.TrackingResponse
import com.techaus.afamfresh.repository.TrackingRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Polls for a delivery's position while its screen is open.
 *
 * WHY POLLING AND NOT PUSH
 *
 * A position is only as fresh as the rider's device reporting it — currently
 * every 30s or 25m. A push cannot make the server know sooner, so a 10s poll is
 * never more than one rider-interval behind the truth, which is the same
 * staleness a push would have.
 *
 * Data-only FCM is also unreliable in exactly the conditions that matter: Doze,
 * App Standby, and the aggressive OEM ROMs common in this market. A tracking
 * map that silently freezes is worse than one that visibly retries. Push is
 * used for the three state TRANSITIONS instead, where it is genuinely good —
 * see AfamFreshMessagingService.
 *
 * THE INTERVAL COMES FROM THE SERVER
 *
 * `poll_after_seconds` is obeyed rather than chosen here. A release build takes
 * sixteen minutes and cannot be forced onto an install base, so an interval
 * baked into the app is one that can never be tuned. Zero means stop.
 */
class TrackingViewModel(
    private val trackingRepository: TrackingRepository
) : ViewModel() {

    private val _tracking = MutableStateFlow<TrackingResponse?>(null)
    val tracking: StateFlow<TrackingResponse?> = _tracking.asStateFlow()

    /** True after a failed poll, cleared by the next success. Drives the "Reconnecting…" strip. */
    private val _stale = MutableStateFlow(false)
    val stale: StateFlow<Boolean> = _stale.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var pollJob: Job? = null

    /** Cursor for incremental trail fetches, so a steady poll stays small. */
    private var lastPointAt: String? = null

    fun start(orderId: Int, source: String) {
        pollJob?.cancel()
        lastPointAt = null

        pollJob = viewModelScope.launch {
            while (isActive) {
                val response = fetchOnce(orderId, source)

                if (response != null) {
                    _stale.value = false
                    _error.value = null

                    // Merge rather than replace: `trail` only contains points
                    // newer than the cursor, so replacing would shorten the
                    // drawn path on every poll.
                    val previous = _tracking.value
                    _tracking.value = if (previous != null && !response.trail.isNullOrEmpty()) {
                        response.copy(trail = (previous.trail.orEmpty() + response.trail))
                    } else {
                        response
                    }

                    response.trail?.lastOrNull()?.at?.let { lastPointAt = it }

                    val wait = response.pollAfterSeconds ?: 15
                    // Zero means the delivery is over and there is nothing more
                    // to watch. Stop entirely rather than idling on a loop.
                    if (wait <= 0 || response.finished) return@launch
                    delay(wait * 1000L)
                } else {
                    // A failed poll is not a reason to give up — a customer in a
                    // lift loses signal for thirty seconds. Back off a little so
                    // a genuinely offline device is not hammering the radio.
                    _stale.value = true
                    delay(15_000L)
                }
            }
        }
    }

    /**
     * Polls immediately, out of turn.
     *
     * Called when an FCM transition push arrives: the state has just changed, so
     * waiting out the remaining interval would show the customer stale
     * information for up to ten seconds after the app already knew better.
     */
    fun refreshNow(orderId: Int, source: String) {
        viewModelScope.launch {
            fetchOnce(orderId, source)?.let { _tracking.value = it }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    override fun onCleared() {
        super.onCleared()
        pollJob?.cancel()
    }

    private suspend fun fetchOnce(orderId: Int, source: String): TrackingResponse? =
        suspendCancellableCoroutine { cont ->
            trackingRepository.getTracking(orderId, source, lastPointAt) { body, error ->
                if (error != null && body == null) {
                    // Only surfaced once there is nothing on screen at all —
                    // an intermittent failure mid-journey shows the amber strip
                    // instead, because the last known position is still useful.
                    if (_tracking.value == null) {
                        _error.value = error.userMessage
                    }
                }
                if (cont.isActive) cont.resume(body) {}
            }
        }
}