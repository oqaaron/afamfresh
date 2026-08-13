package com.techaus.afamfresh.viewmodel

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techaus.afamfresh.models.Delivery
import com.techaus.afamfresh.models.EarningsEntry
import com.techaus.afamfresh.models.EarningsSummary
import com.techaus.afamfresh.models.PayoutRequest
import com.techaus.afamfresh.models.RiderActionState
import com.techaus.afamfresh.models.RiderProfile
import com.techaus.afamfresh.repository.RiderRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * State for the rider section.
 *
 * Deliberately does NOT load anything from its init block. Most accounts are
 * not riders, and `api/rider.php` refuses them by design — firing a request for
 * every signed-in customer would put a guaranteed error in the log on every
 * launch and re-fire on each rotation. Screens call [refresh] when they open.
 */
class RiderViewModel(
    private val repository: RiderRepository
) : ViewModel() {

    private val _profile = MutableStateFlow<RiderProfile?>(null)
    val profile: StateFlow<RiderProfile?> = _profile.asStateFlow()

    private val _active = MutableStateFlow<List<Delivery>>(emptyList())
    val active: StateFlow<List<Delivery>> = _active.asStateFlow()

    private val _earningsSummary = MutableStateFlow<EarningsSummary?>(null)
    val earningsSummary: StateFlow<EarningsSummary?> = _earningsSummary.asStateFlow()

    private val _earningsHistory = MutableStateFlow<List<EarningsEntry>>(emptyList())
    val earningsHistory: StateFlow<List<EarningsEntry>> = _earningsHistory.asStateFlow()

    private val _payouts = MutableStateFlow<List<PayoutRequest>>(emptyList())
    val payouts: StateFlow<List<PayoutRequest>> = _payouts.asStateFlow()

    private val _earningsLoading = MutableStateFlow(false)
    val earningsLoading: StateFlow<Boolean> = _earningsLoading.asStateFlow()

    private val _history = MutableStateFlow<List<Delivery>>(emptyList())
    val history: StateFlow<List<Delivery>> = _history.asStateFlow()

    private val _selected = MutableStateFlow<Delivery?>(null)
    val selected: StateFlow<Delivery?> = _selected.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /**
     * Blocking-ish problem with the section as a whole — most importantly
     * "this account is not set up as a rider yet", which is a state an admin
     * can leave an account in and which the screens must explain rather than
     * showing an empty list.
     */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Separate from [_error] so a failed action does not blank the whole screen. */
    private val _actionState = MutableStateFlow<RiderActionState>(RiderActionState.Idle)
    val actionState: StateFlow<RiderActionState> = _actionState.asStateFlow()

    val isOnline: Boolean get() = _profile.value?.online == true

    fun refresh() {
        _isLoading.value = true
        _error.value = null
        repository.loadProfile { profile, error ->
            _profile.value = profile
            if (profile == null) {
                _isLoading.value = false
                _error.value = error
                return@loadProfile
            }
            repository.loadDeliveries { active, history, listError ->
                _isLoading.value = false
                if (active != null) {
                    _active.value = active
                    _history.value = history.orEmpty()
                } else {
                    _error.value = listError
                }
            }
        }
    }

    // `source` says which table orderId belongs to. It travels with every call
    // about a delivery because the shop and surplus id spaces overlap — an id
    // on its own can resolve to a different customer's job entirely.
    fun loadDelivery(orderId: Int, source: String = "order") {
        _selected.value = null
        repository.loadDeliveryDetail(orderId, source) { delivery, error ->
            _selected.value = delivery
            if (delivery == null) _error.value = error
        }
    }

    /**
     * Advances a delivery, then re-reads it.
     *
     * No optimistic write here, unlike the role pills: the server owns the
     * transition rules and refuses anything out of order, so guessing the new
     * state would risk showing a stage the order never reached.
     */
    fun advance(
        orderId: Int,
        status: String,
        source: String = "order",
        cashCollected: Boolean = false,
        onDone: (Boolean) -> Unit = {}
    ) {
        _actionState.value = RiderActionState.Working
        repository.updateDeliveryStatus(orderId, status, source, cashCollected) { ok, message, code ->
            when {
                ok -> {
                    _actionState.value = RiderActionState.Done
                    loadDelivery(orderId, source)
                    refreshListsOnly()
                }
                code == "CASH_NOT_CONFIRMED" -> {
                    // The amount to show comes from the delivery already loaded
                    // on screen, not the error message text — parsing UGX back
                    // out of a sentence the server is free to reword is fragile.
                    val amount = _selected.value?.totalAmount ?: 0.0
                    _actionState.value = RiderActionState.NeedsCashConfirm(orderId, source, amount)
                }
                else -> {
                    _actionState.value = RiderActionState.Error(message ?: "Could not update the delivery.")
                }
            }
            onDone(ok)
        }
    }

    /**
     * Applied optimistically so the switch moves the instant it is tapped, then
     * reverted if the write fails — a toggle that waits for a round trip before
     * moving feels broken.
     */
    fun setOnline(online: Boolean) {
        val previous = _profile.value
        _profile.value = previous?.copy(status = if (online) "online" else "offline", isOnline = online)
        repository.setDutyStatus(online) { ok, message ->
            if (!ok) {
                _profile.value = previous
                _actionState.value = RiderActionState.Error(message ?: "Could not change your status.")
            }
        }
    }

    fun postLocation(lat: Double, lng: Double, accuracy: Float? = null, speed: Float? = null, heading: Float? = null) {
        repository.postLocation(lat, lng, accuracy, speed, heading) { ok ->
            if (!ok) Log.w("RiderViewModel", "location post failed")
        }
    }

    fun uploadProof(orderId: Int, uri: Uri, source: String = "order") {
        _actionState.value = RiderActionState.Working
        viewModelScope.launch {
            // Decoding and compression happen off the main thread inside the repo.
            val bytes = repository.prepareProofBytes(uri)
            if (bytes == null) {
                _actionState.value = RiderActionState.Error("Couldn't read that photo. Try another one.")
                return@launch
            }
            repository.uploadProof(orderId, bytes, source) { _, message ->
                if (message == null) {
                    _actionState.value = RiderActionState.Done
                    loadDelivery(orderId, source)
                } else {
                    _actionState.value = RiderActionState.Error(message)
                }
            }
        }
    }

    /** Refreshes the lists without touching the loading flag, after an action. */
    private fun refreshListsOnly() {
        repository.loadDeliveries { active, history, _ ->
            if (active != null) {
                _active.value = active
                _history.value = history.orEmpty()
            }
        }
    }

    fun loadEarnings() {
        _earningsLoading.value = true
        repository.loadEarnings { summary, history, error ->
            _earningsLoading.value = false
            if (summary != null) {
                _earningsSummary.value = summary
                _earningsHistory.value = history.orEmpty()
            } else {
                _actionState.value = RiderActionState.Error(error ?: "Could not load your earnings.")
            }
        }
        repository.loadPayouts { list, _ ->
            // Silent on failure: the earnings summary above is the primary
            // content of the screen, and a rider with no pending payout
            // simply sees no request card — nothing to alarm them with.
            if (list != null) _payouts.value = list
        }
    }

    fun requestPayout() {
        _actionState.value = RiderActionState.Working
        repository.requestPayout { amount, error ->
            if (amount != null) {
                _actionState.value = RiderActionState.Done
                loadEarnings()
            } else {
                _actionState.value = RiderActionState.Error(error ?: "Could not request a payout.")
            }
        }
    }

    fun clearActionState() { _actionState.value = RiderActionState.Idle }
    fun clearError() { _error.value = null }
}
