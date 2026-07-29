package com.techaus.afamfresh.viewmodel

import androidx.lifecycle.ViewModel
import com.techaus.afamfresh.models.CartItem
import com.techaus.afamfresh.models.Product
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// In-memory only (matches the no-persistence-arg constructor `CartViewModel()`
// used in MainActivity.kt). If you need the cart to survive process death,
// back this with SharedPreferences/DB later — not done here to avoid guessing
// at a persistence layer that wasn't shown to me.
class CartViewModel : ViewModel() {

    private val _cartItems = MutableStateFlow<List<CartItem>>(emptyList())
    val cartItems: StateFlow<List<CartItem>> = _cartItems.asStateFlow()

    val subtotal: Double get() = _cartItems.value.sumOf { it.lineTotal }

    fun addToCart(product: Product, quantity: Int) {
        val current = _cartItems.value.toMutableList()
        val existingIndex = current.indexOfFirst { it.product.id == product.id }
        if (existingIndex >= 0) {
            val existing = current[existingIndex]
            current[existingIndex] = existing.copy(quantity = existing.quantity + quantity)
        } else {
            current.add(CartItem(product = product, quantity = quantity))
        }
        _cartItems.value = current
    }

    fun removeFromCart(item: CartItem) {
        _cartItems.value = _cartItems.value.filterNot { it.product.id == item.product.id }
    }

    fun updateQuantity(item: CartItem, quantity: Int) {
        if (quantity <= 0) {
            removeFromCart(item)
            return
        }
        _cartItems.value = _cartItems.value.map {
            if (it.product.id == item.product.id) it.copy(quantity = quantity) else it
        }
    }

    fun clearCart() {
        _cartItems.value = emptyList()
    }
}
