package com.techaus.afamfresh.viewmodel

import androidx.lifecycle.ViewModel
import com.techaus.afamfresh.BuildConfig
import com.techaus.afamfresh.api.ApiService
import com.techaus.afamfresh.models.RoleGateState
import com.techaus.afamfresh.utils.enqueueApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Decides whether this app's workspace may open for the signed-in account.
 *
 * The role is [BuildConfig.APP_ROLE] — fixed per flavor, so the Rider app only
 * ever asks about "rider". There is no switching: Customer, Rider and Vendor
 * are separate installs.
 *
 * This is a UX gate only. The server still checks the session's role on every
 * workspace endpoint (api/rider.php and friends), because an app is not a
 * security boundary — anyone can call those endpoints directly.
 */
class RoleGateViewModel(
    private val api: ApiService
) : ViewModel() {

    private companion object { const val TAG = "RoleGate" }

    /** The role this build is for; "user" means no gate at all. */
    val role: String = BuildConfig.APP_ROLE

    private val _state = MutableStateFlow(RoleGateState.None)
    val state: StateFlow<RoleGateState> = _state.asStateFlow()

    private val _canRequest = MutableStateFlow(false)
    val canRequest: StateFlow<Boolean> = _canRequest.asStateFlow()

    private val _reason = MutableStateFlow<String?>(null)
    val reason: StateFlow<String?> = _reason.asStateFlow()

    private val _isWorking = MutableStateFlow(false)
    val isWorking: StateFlow<Boolean> = _isWorking.asStateFlow()

    /** True once the first check has completed, so the UI can hold off. */
    private val _checked = MutableStateFlow(false)
    val checked: StateFlow<Boolean> = _checked.asStateFlow()

    /** 'user' is every account's baseline, so the customer app never gates. */
    val gateApplies: Boolean get() = role != "user"

    fun refresh() {
        if (!gateApplies) {
            _state.value = RoleGateState.Approved
            _checked.value = true
            return
        }
        _isWorking.value = true
        api.getRoleStatus(role = role).enqueueApi(TAG, "role status") { body, error ->
            _isWorking.value = false
            _checked.value = true
            if (body?.success == true) {
                _state.value = RoleGateState.from(body.state)
                _canRequest.value = body.canRequest ?: false
                _reason.value = body.reason
            } else {
                // Offline or a server refusal: stay closed rather than guessing
                // open. Opening a workspace the account may not have would only
                // produce a wall of failed calls behind it.
                _reason.value = body?.error ?: error?.userMessage
            }
        }
    }

    fun request() {
        if (!gateApplies) return
        _isWorking.value = true
        api.requestRole(role = role).enqueueApi(TAG, "role request") { body, error ->
            _isWorking.value = false
            if (body?.success == true) {
                _state.value = RoleGateState.from(body.state)
                _canRequest.value = false
                _reason.value = null
            } else {
                _reason.value = body?.error ?: error?.userMessage
                    ?: "Could not send your request."
            }
        }
    }
}
