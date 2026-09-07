package com.techaus.afamfresh.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techaus.afamfresh.models.CartItem
import com.techaus.afamfresh.models.Product
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CartViewModel : ViewModel() {

    private val _cartItems = MutableStateFlow<List<CartItem>>(emptyList())
    val cartItems: StateFlow<List<CartItem>> = _cartItems.asStateFlow()

    // Backward-compatibility or convenience wrapper if needed by other components
    val cartState = MutableStateFlow(CartStateDummy())

    fun addToCart(product: Product, quantity: Int = 1) {
        viewModelScope.launch {
            val currentList = _cartItems.value.toMutableList()
            val existingIndex = currentList.indexOfFirst { it.product.id == product.id }

            if (existingIndex >= 0) {
                val existingItem = currentList[existingIndex]
                val newQty = existingItem.quantity + quantity
                if (newQty > 0) {
                    currentList[existingIndex] = existingItem.copy(quantity = newQty)
                } else {
                    currentList.removeAt(existingIndex)
                }
            } else {
                if (quantity > 0) {
                    currentList.add(CartItem(product = product, quantity = quantity))
                }
            }
            _cartItems.value = currentList
            updateDummyState(currentList)
        }
    }

    fun removeFromCart(cartItem: CartItem) {
        viewModelScope.launch {
            val currentList = _cartItems.value.toMutableList()
            currentList.removeIf { it.product.id == cartItem.product.id }
            _cartItems.value = currentList
            updateDummyState(currentList)
        }
    }

    fun updateQuantity(cartItem: CartItem, quantity: Int) {
        viewModelScope.launch {
            val currentList = _cartItems.value.toMutableList()
            val index = currentList.indexOfFirst { it.product.id == cartItem.product.id }
            
            if (index >= 0) {
                if (quantity > 0) {
                    currentList[index] = currentList[index].copy(quantity = quantity)
                } else {
                    currentList.removeAt(index)
                }
            }
            _cartItems.value = currentList
            updateDummyState(currentList)
        }
    }

    fun clearCart() {
        _cartItems.value = emptyList()
        cartState.value = CartStateDummy()
    }

    private fun updateDummyState(items: List<CartItem>) {
        cartState.value = CartStateDummy(
            totalCount = items.sumOf { it.quantity },
            items = items.associate { it.product.id to it.quantity }
        )
    }
}

data class CartStateDummy(
    val totalCount: Int = 0,
    val items: Map<Int, Int> = emptyMap()
)