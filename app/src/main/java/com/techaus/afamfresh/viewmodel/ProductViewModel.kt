package com.techaus.afamfresh.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techaus.afamfresh.models.Product
import com.techaus.afamfresh.repository.ProductRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// Constructor confirmed by MainActivity.kt: ProductViewModel(productRepository)
class ProductViewModel(
    private val productRepository: ProductRepository
) : ViewModel() {

    private val _products = MutableStateFlow<List<Product>>(emptyList())
    val products: StateFlow<List<Product>> = _products.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        loadProducts()
    }

    fun loadProducts() {
        _isLoading.value = true
        _error.value = null
        productRepository.getProducts { products ->
            _isLoading.value = false
            if (products != null) {
                _products.value = products
            } else {
                _error.value = "Unable to load products"
            }
        }
    }
}
