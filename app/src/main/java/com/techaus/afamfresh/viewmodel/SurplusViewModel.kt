package com.techaus.afamfresh.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techaus.afamfresh.models.CreateSurplusOrderRequest
import com.techaus.afamfresh.models.SurplusListing
import com.techaus.afamfresh.models.SurplusOrder
import com.techaus.afamfresh.models.SurplusQuoteRequest
import com.techaus.afamfresh.models.SurplusQuoteResponse
import com.techaus.afamfresh.repository.SurplusRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// Constructor confirmed by MainActivity.kt: SurplusViewModel(surplusRepository)
class SurplusViewModel(
    private val surplusRepository: SurplusRepository
) : ViewModel() {

    private val _listings = MutableStateFlow<List<SurplusListing>>(emptyList())
    val listings: StateFlow<List<SurplusListing>> = _listings.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _canRetry = MutableStateFlow(true)
    val canRetry: StateFlow<Boolean> = _canRetry.asStateFlow()

    init {
        loadListings()
    }

    fun loadListings() {
        _isLoading.value = true
        _error.value = null
        surplusRepository.getPublicListings { listings, error ->
            _isLoading.value = false
            if (listings != null) {
                _listings.value = listings
            } else {
                _error.value = error?.userMessage ?: "Unable to load surplus deals"
                _canRetry.value = error?.isRetryable ?: true
            }
        }
    }

    // ---------------------------------------------------------------
    // Pricing an order before it exists
    // ---------------------------------------------------------------

    private val _quote = MutableStateFlow<SurplusQuoteResponse?>(null)
    val quote: StateFlow<SurplusQuoteResponse?> = _quote.asStateFlow()

    private val _quoteLoading = MutableStateFlow(false)
    val quoteLoading: StateFlow<Boolean> = _quoteLoading.asStateFlow()

    private val _quoteError = MutableStateFlow<String?>(null)
    val quoteError: StateFlow<String?> = _quoteError.asStateFlow()

    /**
     * Asks the server what an order would cost.
     *
     * Called whenever the quantity or the pinned location changes, because both
     * feed the delivery fee. The app cannot compute this itself: the fee mixes
     * weight, distance, a service charge and percentages of the goods value,
     * all of which live in admin-editable tables.
     *
     * A failed quote clears the previous one rather than leaving it on screen.
     * A stale total next to a changed quantity is worse than no total, because
     * it looks authoritative.
     */
    fun requestQuote(listingId: Int, quantity: Double, lat: Double?, lng: Double?) {
        _quoteLoading.value = true
        _quoteError.value = null

        surplusRepository.getQuote(
            SurplusQuoteRequest(
                listingId = listingId,
                quantity = quantity,
                deliveryLat = lat,
                deliveryLng = lng
            )
        ) { response, error ->
            _quoteLoading.value = false
            if (response != null) {
                _quote.value = response
            } else {
                _quote.value = null
                _quoteError.value = error?.userMessage ?: "Could not price that order."
            }
        }
    }

    fun clearQuote() {
        _quote.value = null
        _quoteError.value = null
    }

    // ---------------------------------------------------------------
    // Loyalty redemption preview
    // ---------------------------------------------------------------

    data class LoyaltyPreview(val pointsApplied: Int, val discount: Double, val capped: Boolean)

    private val _loyaltyPreview = MutableStateFlow<LoyaltyPreview?>(null)
    val loyaltyPreview: StateFlow<LoyaltyPreview?> = _loyaltyPreview.asStateFlow()

    private var loyaltyQuoteJob: Job? = null

    /** Debounced live quote — see CheckoutViewModel.quoteLoyaltyPoints's own doc. */
    fun quoteLoyaltyPoints(points: Int, goodsValue: Double) {
        loyaltyQuoteJob?.cancel()
        if (points <= 0 || goodsValue <= 0) {
            _loyaltyPreview.value = null
            return
        }
        loyaltyQuoteJob = viewModelScope.launch {
            delay(350L)
            surplusRepository.getLoyaltyQuote(points, goodsValue) { body, _ ->
                _loyaltyPreview.value = body?.let {
                    LoyaltyPreview(it.pointsApplied, it.discount, it.capped)
                }
            }
        }
    }

    // ---------------------------------------------------------------
    // Placing an order
    // ---------------------------------------------------------------

    private val _isPlacingOrder = MutableStateFlow(false)
    val isPlacingOrder: StateFlow<Boolean> = _isPlacingOrder.asStateFlow()

    private val _orderError = MutableStateFlow<String?>(null)
    val orderError: StateFlow<String?> = _orderError.asStateFlow()

    /** The order just created, still unpaid. Cleared once checkout is done with it. */
    private val _placedOrder = MutableStateFlow<SurplusOrder?>(null)
    val placedOrder: StateFlow<SurplusOrder?> = _placedOrder.asStateFlow()

    /**
     * Places the order and reports the id and the amount that will be charged.
     *
     * The total comes from the server's response, not from quantity × price:
     * the delivery fee is computed from the order's weight and waived above a
     * threshold, so the app cannot work it out on its own.
     *
     * Creating an order takes the stock off the listing immediately, so the
     * listings are reloaded afterwards — otherwise the customer goes back to a
     * screen still advertising quantity they have just bought.
     */
    fun placeOrder(
        request: CreateSurplusOrderRequest,
        onPlaced: (orderId: Int, grandTotal: Double) -> Unit
    ) {
        if (_isPlacingOrder.value) return   // guard the double tap: this creates money

        _isPlacingOrder.value = true
        _orderError.value = null

        surplusRepository.createOrder(request) { response, error ->
            _isPlacingOrder.value = false

            val order = response?.order
            if (order != null) {
                _placedOrder.value = order
                loadListings()
                onPlaced(order.id, response.grandTotal)
            } else {
                // The server's own message — "Minimum order value for surplus is
                // UGX 250,000" and friends — is written for the customer and says
                // more than anything this layer could substitute.
                _orderError.value = error?.userMessage ?: "Could not place your order."
            }
        }
    }

    fun clearOrderError() {
        _orderError.value = null
    }

    fun clearPlacedOrder() {
        _placedOrder.value = null
    }

    // ---------------------------------------------------------------
    // The customer's own surplus orders
    // ---------------------------------------------------------------

    private val _myOrders = MutableStateFlow<List<SurplusOrder>>(emptyList())
    val myOrders: StateFlow<List<SurplusOrder>> = _myOrders.asStateFlow()

    private val _ordersLoading = MutableStateFlow(false)
    val ordersLoading: StateFlow<Boolean> = _ordersLoading.asStateFlow()

    private val _ordersError = MutableStateFlow<String?>(null)
    val ordersError: StateFlow<String?> = _ordersError.asStateFlow()

    fun loadMyOrders(userId: Int) {
        _ordersLoading.value = true
        _ordersError.value = null
        surplusRepository.getMyOrders(userId) { orders, error ->
            _ordersLoading.value = false
            if (orders != null) {
                _myOrders.value = orders
            } else {
                _ordersError.value = error?.userMessage ?: "Could not load your surplus orders."
            }
        }
    }

    fun confirmReceipt(
        orderId: Int,
        userId: Int,
        rating: Int?,
        ratingSpeed: Int?,
        ratingProfessionalism: Int?,
        ratingPackaging: Int?,
        feedback: String?,
        emojiReaction: String?,
        photoBytes: ByteArray?,
        onResult: (Boolean, String?) -> Unit
    ) {
        _ordersLoading.value = true
        surplusRepository.confirmReceipt(
            orderId, userId, rating, ratingSpeed, ratingProfessionalism, ratingPackaging,
            feedback, emojiReaction, photoBytes
        ) { success, error ->
            _ordersLoading.value = false
            if (success) loadMyOrders(userId)
            onResult(success, error?.userMessage)
        }
    }
}
