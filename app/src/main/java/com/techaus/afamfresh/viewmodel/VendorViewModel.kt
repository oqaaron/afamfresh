package com.techaus.afamfresh.viewmodel

import androidx.lifecycle.ViewModel
import com.techaus.afamfresh.models.Order
import com.techaus.afamfresh.models.Product
import com.techaus.afamfresh.models.SurplusListing
import com.techaus.afamfresh.repository.VendorRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// Constructor confirmed by MainActivity.kt: VendorViewModel(vendorRepository)
// `listings` name is confirmed exactly — MainScreen.kt reads
// `vendorViewModel.listings.collectAsState()` directly.
class VendorViewModel(
    private val vendorRepository: VendorRepository
) : ViewModel() {

    private val _listings = MutableStateFlow<List<SurplusListing>>(emptyList())
    val listings: StateFlow<List<SurplusListing>> = _listings.asStateFlow()

    private val _vendorOrders = MutableStateFlow<List<Order>>(emptyList())
    val vendorOrders: StateFlow<List<Order>> = _vendorOrders.asStateFlow()

    private val _vendorProducts = MutableStateFlow<List<Product>>(emptyList())
    val vendorProducts: StateFlow<List<Product>> = _vendorProducts.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _canRetry = MutableStateFlow(true)
    val canRetry: StateFlow<Boolean> = _canRetry.asStateFlow()

    private val _hasLoaded = MutableStateFlow(false)
    val hasLoaded: StateFlow<Boolean> = _hasLoaded.asStateFlow()

    init {
        loadListings()
    }

    fun loadListings() {
        _isLoading.value = true
        _error.value = null
        vendorRepository.getListings { listings, error ->
            _isLoading.value = false
            _hasLoaded.value = true
            if (listings != null) {
                _listings.value = listings
            } else {
                _error.value = error?.userMessage ?: "Unable to load your listings"
                _canRetry.value = error?.isRetryable ?: true
            }
        }
    }

    fun createListing(listing: SurplusListing, onResult: (Boolean, String?) -> Unit) {
        _isLoading.value = true
        vendorRepository.createListing(listing) { created, error ->
            _isLoading.value = false
            if (created != null) {
                loadListings()
                onResult(true, null)
            } else {
                _error.value = error?.userMessage ?: "Unable to create listing"
                onResult(false, _error.value)
            }
        }
    }

    fun updateListing(
        id: String,
        title: String,
        description: String?,
        originalPrice: Double,
        price: Double,
        quantity: Double,
        unit: String,
        expiresAt: String,
        onResult: (Boolean, String?) -> Unit
    ) {
        _isLoading.value = true
        vendorRepository.updateListing(id, title, description, originalPrice, price, quantity, unit, expiresAt) { success, error ->
            _isLoading.value = false
            if (success) {
                loadListings()
                onResult(true, null)
            } else {
                _error.value = error?.userMessage ?: "Unable to save changes"
                onResult(false, _error.value)
            }
        }
    }

    fun deleteListing(id: String, onResult: (Boolean, String?) -> Unit) {
        _isLoading.value = true
        vendorRepository.deleteListing(id) { success, error ->
            _isLoading.value = false
            if (success) {
                loadListings()
                onResult(true, null)
            } else {
                _error.value = error?.userMessage ?: "Unable to delete listing"
                onResult(false, _error.value)
            }
        }
    }

    fun loadVendorOrders() {
        _isLoading.value = true
        _error.value = null
        vendorRepository.getVendorOrders { orders, error ->
            _isLoading.value = false
            _hasLoaded.value = true
            if (orders != null) {
                _vendorOrders.value = orders
            } else {
                _error.value = error?.userMessage ?: "Unable to load orders"
                _canRetry.value = error?.isRetryable ?: true
            }
        }
    }

    fun loadVendorProducts() {
        _isLoading.value = true
        _error.value = null
        vendorRepository.getVendorProducts { products, error ->
            _isLoading.value = false
            _hasLoaded.value = true
            if (products != null) {
                _vendorProducts.value = products
            } else {
                _error.value = error?.userMessage ?: "Unable to load products"
                _canRetry.value = error?.isRetryable ?: true
            }
        }
    }
}
