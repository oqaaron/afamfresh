package com.techaus.afamfresh.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.techaus.afamfresh.models.CartItem
import com.techaus.afamfresh.models.DeliveryResult
import com.techaus.afamfresh.repository.OrderRepository
import com.techaus.afamfresh.utils.OrderCalc
import com.techaus.afamfresh.repository.PaymentRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

    data class LoyaltyPreview(val pointsApplied: Int, val discount: Double, val capped: Boolean)

    private val _loyaltyPreview = MutableStateFlow<LoyaltyPreview?>(null)
    val loyaltyPreview: StateFlow<LoyaltyPreview?> = _loyaltyPreview.asStateFlow()

    private var quoteJob: Job? = null

    /**
     * Debounced live quote as the customer adjusts how many points to
     * redeem — calls the server (loyalty-quote.php via OrderRepository) so
     * this preview can never disagree with what create-order actually
     * applies for the same requested amount; see quoteLoyaltyRedemption()
     * server-side.
     */
    fun quoteLoyaltyPoints(points: Int, goodsValue: Double) {
        quoteJob?.cancel()
        if (points <= 0 || goodsValue <= 0) {
            _loyaltyPreview.value = null
            return
        }
        quoteJob = viewModelScope.launch {
            delay(350L)
            orderRepository.getLoyaltyQuote(points, goodsValue) { body, _ ->
                _loyaltyPreview.value = body?.let {
                    LoyaltyPreview(it.pointsApplied, it.discount, it.capped)
                }
            }
        }
    }

    fun placeOrder(
        cartItems: List<CartItem>,
        fname: String,
        lname: String,
        mobile: String,
        area: String,
        address: String,
        email: String,
        deliveryResult: DeliveryResult?,
        pointsRedeem: Int = 0,
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

        // Delegated to OrderCalc so the money arithmetic and the JSON the
        // backend receives are covered by unit tests rather than only being
        // exercised by placing a real order.
        val deliveryCost = OrderCalc.deliveryCost(deliveryResult)
        val total = OrderCalc.total(cartItems, deliveryResult)
        val itemsJson = OrderCalc.serialiseItems(OrderCalc.toOrderItems(cartItems), gson)

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
            deliveryCost = deliveryCost,
            pointsRedeem = pointsRedeem
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
