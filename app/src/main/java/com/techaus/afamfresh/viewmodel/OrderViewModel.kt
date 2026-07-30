package com.techaus.afamfresh.viewmodel

import androidx.lifecycle.ViewModel
import com.techaus.afamfresh.models.Order
import com.techaus.afamfresh.repository.OrderRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// Constructor confirmed by MainActivity.kt: OrderViewModel(orderRepository)
class OrderViewModel(
    private val orderRepository: OrderRepository
) : ViewModel() {

    private val _orders = MutableStateFlow<List<Order>>(emptyList())
    val orders: StateFlow<List<Order>> = _orders.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _canRetry = MutableStateFlow(true)
    val canRetry: StateFlow<Boolean> = _canRetry.asStateFlow()

    /** True once a load has completed, so screens can distinguish
     *  "not fetched yet" from "fetched and genuinely empty". */
    private val _hasLoaded = MutableStateFlow(false)
    val hasLoaded: StateFlow<Boolean> = _hasLoaded.asStateFlow()

    fun loadOrders() {
        _isLoading.value = true
        _error.value = null
        orderRepository.getOrders { orders, error ->
            _isLoading.value = false
            _hasLoaded.value = true
            if (orders != null) {
                _orders.value = orders
            } else {
                _error.value = error?.userMessage ?: "Unable to load orders"
                _canRetry.value = error?.isRetryable ?: true
            }
        }
    }

    fun updateOrder(
        orderId: String,
        address: String,
        area: String,
        mobile: String,
        scheduledDeliveryDate: String? = null,
        scheduledDeliverySlot: String? = null,
        onResult: (Boolean, String?) -> Unit
    ) {
        _isLoading.value = true
        orderRepository.updateOrder(
            orderId, address, area, mobile, scheduledDeliveryDate, scheduledDeliverySlot
        ) { success, error ->
            _isLoading.value = false
            if (success) loadOrders()
            // The reason travels to the caller so the screen can say why the
            // save failed rather than just refusing to close.
            onResult(success, error?.userMessage)
        }
    }

    fun cancelOrder(orderId: String, onResult: (Boolean, String?) -> Unit) {
        _isLoading.value = true
        orderRepository.cancelOrder(orderId) { success, error ->
            _isLoading.value = false
            if (success) loadOrders()
            onResult(success, error?.userMessage)
        }
    }
}
