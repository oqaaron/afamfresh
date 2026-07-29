package com.techaus.afamfresh.viewmodel

import androidx.lifecycle.ViewModel
import com.techaus.afamfresh.models.DeliveryResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// No-arg constructor confirmed by MainActivity.kt: DeliveryResultViewModel()
// setDeliveryResult(...) confirmed by MainScreen.kt's onLocationSelected callback.
class DeliveryResultViewModel : ViewModel() {

    private val _deliveryResult = MutableStateFlow<DeliveryResult?>(null)
    val deliveryResult: StateFlow<DeliveryResult?> = _deliveryResult.asStateFlow()

    fun setDeliveryResult(result: DeliveryResult) {
        _deliveryResult.value = result
    }

    fun clear() {
        _deliveryResult.value = null
    }
}
