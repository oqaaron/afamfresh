package com.techaus.afamfresh.viewmodel

import androidx.lifecycle.ViewModel
import com.google.gson.Gson
import com.techaus.afamfresh.models.CartItem
import com.techaus.afamfresh.models.DeliveryResult
import com.techaus.afamfresh.models.OrderItem
import com.techaus.afamfresh.repository.OrderRepository
import com.techaus.afamfresh.repository.PaymentRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// Constructor confirmed by MainActivity.kt:
//   checkoutViewModel = CheckoutViewModel(orderRepository, paymentRepository)
//
// ⚠️ INFERRED business logic. Delivery cost is taken from DeliveryResult.cost
// (0.0 if no location was selected) — replace with your real delivery-fee
// policy if orders should be blocked without a selected location.
class CheckoutViewModel(
    private val orderRepository: OrderRepository,
    private val paymentRepository: PaymentRepository
) : ViewModel() {

    private val gson = Gson()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    data class OrderPlaced(val orderId: String, val total: Double)

    private val _orderPlaced = MutableStateFlow<OrderPlaced?>(null)
    val orderPlaced: StateFlow<OrderPlaced?> = _orderPlaced.asStateFlow()

    fun placeOrder(
        cartItems: List<CartItem>,
        fname: String,
        lname: String,
        mobile: String,
        area: String,
        address: String,
        email: String,
        deliveryResult: DeliveryResult?,
        onResult: (OrderPlaced?) -> Unit
    ) {
        if (cartItems.isEmpty()) {
            _error.value = "Your cart is empty"
            onResult(null)
            return
        }
        if (fname.isBlank() || mobile.isBlank() || area.isBlank() || address.isBlank()) {
            _error.value = "Please fill in your name, mobile, area, and address"
            onResult(null)
            return
        }

        _isLoading.value = true
        _error.value = null

        val subtotal = cartItems.sumOf { it.lineTotal }
        val deliveryCost = deliveryResult?.cost ?: 0.0
        val total = subtotal + deliveryCost

        val orderItems = cartItems.map {
            OrderItem(productId = it.product.id, name = it.product.name, price = it.product.price, quantity = it.quantity)
        }
        val itemsJson = gson.toJson(orderItems)

        orderRepository.createOrder(
            fname = fname,
            lname = lname,
            mobile = mobile,
            area = area,
            address = address,
            itemsJson = itemsJson,
            total = total,
            email = email,
            pickupAddress = deliveryResult?.pickupAddress ?: "",
            dropoffAddress = deliveryResult?.dropoffAddress ?: address,
            pickupLat = deliveryResult?.pickupLat ?: 0.0,
            pickupLng = deliveryResult?.pickupLng ?: 0.0,
            dropoffLat = deliveryResult?.dropoffLat ?: 0.0,
            dropoffLng = deliveryResult?.dropoffLng ?: 0.0,
            distanceKm = deliveryResult?.distanceKm ?: 0.0,
            deliveryCost = deliveryCost
        ) { response, error ->
            _isLoading.value = false
            if (response?.success == true && response.orderId != null) {
                val placed = OrderPlaced(orderId = response.orderId, total = total)
                _orderPlaced.value = placed
                onResult(placed)
            } else {
                // Order placement is the one flow where a vague failure is
                // worst: the customer does not know whether they have been
                // charged. Say precisely what went wrong.
                _error.value = error?.userMessage ?: response?.error ?: "Unable to place order"
                onResult(null)
            }
        }
    }

    fun clearError() {
        _error.value = null
    }
}
