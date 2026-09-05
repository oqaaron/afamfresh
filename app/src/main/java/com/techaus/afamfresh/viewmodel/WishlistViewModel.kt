package com.techaus.afamfresh.viewmodel

import androidx.lifecycle.ViewModel
import com.techaus.afamfresh.models.Product
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class WishlistViewModel : ViewModel() {

    private val _wishlistItems = MutableStateFlow<List<Product>>(emptyList())
    val wishlistItems: StateFlow<List<Product>> = _wishlistItems.asStateFlow()

    fun addToWishlist(product: Product) {
        val currentList = _wishlistItems.value.toMutableList()
        if (!currentList.any { it.id == product.id }) {
            currentList.add(product)
            _wishlistItems.value = currentList
        }
    }

    fun removeFromWishlist(product: Product) {
        _wishlistItems.value = _wishlistItems.value.filter { it.id != product.id }
    }
}