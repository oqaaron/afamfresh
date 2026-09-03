package com.techaus.afamfresh.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techaus.afamfresh.models.Product
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CartItem(
    val product: Product,
    val quantity: Int
)

data class CartUiState(
    val items: List<CartItem> = emptyList(),
    val totalAmount: Double = 0.0,
    val totalCount: Int = 0
)

class CartViewModel : ViewModel() {

    private val _cartState = MutableStateFlow(CartUiState())
    val cartState: StateFlow<CartUiState> = _cartState.asStateFlow()

    fun addToCart(product: Product, quantity: Int = 1) {
        viewModelScope.launch {
            _cartState.update { current ->
                val currentList = current.items.toMutableList()
                val existingIndex = currentList.indexOfFirst { it.product.id == product.id }

                if (existingIndex >= 0) {
                    val existing = currentList[existingIndex]
                    currentList[existingIndex] = existing.copy(quantity = existing.quantity + quantity)
                } else {
                    currentList.add(CartItem(product = product, quantity = quantity))
                }

                val newTotal = currentList.sumOf { (it.product.effectivePrice ?: 0.0) * it.quantity }
                val newCount = currentList.sumOf { it.quantity }

                CartUiState(
                    items = currentList,
                    totalAmount = newTotal,
                    totalCount = newCount
                )
            }
        }
    }

    fun updateQuantity(productId: Int, quantity: Int) {
        viewModelScope.launch {
            _cartState.update { current ->
                val updatedList = current.items.mapNotNull { item ->
                    if (item.product.id == productId) {
                        if (quantity > 0) item.copy(quantity = quantity) else null
                    } else {
                        item
                    }
                }

                val newTotal = updatedList.sumOf { (it.product.effectivePrice ?: 0.0) * it.quantity }
                val newCount = updatedList.sumOf { it.quantity }

                CartUiState(
                    items = updatedList,
                    totalAmount = newTotal,
                    totalCount = newCount
                )
            }
        }
    }

    fun removeFromCart(productId: Int) {
        updateQuantity(productId, 0)
    }

    fun clearCart() {
        _cartState.value = CartUiState()
    }
}