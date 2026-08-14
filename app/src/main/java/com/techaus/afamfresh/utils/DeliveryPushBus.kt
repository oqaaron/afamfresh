package com.techaus.afamfresh.utils

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Carries delivery-transition pushes from the FCM service to whatever screen
 * happens to be watching that delivery.
 *
 * A process-wide object rather than something injected: [AfamFreshMessagingService]
 * is constructed by Firebase, not by us, so it has no way to reach a ViewModel
 * and no lifecycle to hang one off.
 *
 * `extraBufferCapacity = 4` with the default DROP_OLDEST-free suspension avoids
 * `tryEmit` failing when nothing is collecting — which is the normal case, since
 * most pushes arrive with no tracking screen open. A dropped nudge costs a poll
 * interval of freshness, never correctness.
 */
object DeliveryPushBus {

    /** (orderId, source) — source is tracking.php's vocabulary: "order" or "Bulk". */
    private val _transitions = MutableSharedFlow<Pair<Int, String>>(extraBufferCapacity = 4)
    val transitions: SharedFlow<Pair<Int, String>> = _transitions.asSharedFlow()

    fun publish(orderId: Int, source: String) {
        _transitions.tryEmit(orderId to source)
    }
}