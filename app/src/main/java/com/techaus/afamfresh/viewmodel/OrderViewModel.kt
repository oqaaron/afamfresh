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

    fun loadOrders() {
        _isLoading.value = true
        _error.value = null
        orderRepository.getOrders { orders ->
            _isLoading.value = false
            if (orders != null) {
                _orders.value = orders
            } else {
                _error.value = "Unable to load orders"
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
        deliveryNotes: String? = null,
        onResult: (Boolean) -> Unit
    ) {
        _isLoading.value = true
        orderRepository.updateOrder(
            orderId, address, area, mobile, scheduledDeliveryDate, scheduledDeliverySlot, deliveryNotes
        ) { success ->
            _isLoading.value = false
            if (success) loadOrders()
            onResult(success)
        }
    }

    fun cancelOrder(orderId: String, onResult: (Boolean) -> Unit) {
        _isLoading.value = true
        orderRepository.cancelOrder(orderId) { success ->
            _isLoading.value = false
            if (success) loadOrders()
            onResult(success)
        }
    }
}
