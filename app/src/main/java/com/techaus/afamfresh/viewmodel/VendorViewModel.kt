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

    init {
        loadListings()
    }

    fun loadListings() {
        _isLoading.value = true
        _error.value = null
        vendorRepository.getListings { listings ->
            _isLoading.value = false
            if (listings != null) _listings.value = listings else _error.value = "Unable to load your listings"
        }
    }

    fun createListing(listing: SurplusListing, onResult: (Boolean) -> Unit) {
        _isLoading.value = true
        vendorRepository.createListing(listing) { created ->
            _isLoading.value = false
            if (created != null) {
                loadListings()
                onResult(true)
            } else {
                _error.value = "Unable to create listing"
                onResult(false)
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
        onResult: (Boolean) -> Unit
    ) {
        _isLoading.value = true
        vendorRepository.updateListing(id, title, description, originalPrice, price, quantity, unit, expiresAt) { success ->
            _isLoading.value = false
            if (success) loadListings() else _error.value = "Unable to save changes"
            onResult(success)
        }
    }

    fun deleteListing(id: String, onResult: (Boolean) -> Unit) {
        _isLoading.value = true
        vendorRepository.deleteListing(id) { success ->
            _isLoading.value = false
            if (success) loadListings() else _error.value = "Unable to delete listing"
            onResult(success)
        }
    }

    fun loadVendorOrders() {
        _isLoading.value = true
        vendorRepository.getVendorOrders { orders ->
            _isLoading.value = false
            if (orders != null) _vendorOrders.value = orders else _error.value = "Unable to load orders"
        }
    }

    fun loadVendorProducts() {
        _isLoading.value = true
        vendorRepository.getVendorProducts { products ->
            _isLoading.value = false
            if (products != null) _vendorProducts.value = products else _error.value = "Unable to load products"
        }
    }
}
