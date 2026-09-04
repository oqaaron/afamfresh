package com.techaus.afamfresh.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.techaus.afamfresh.api.ApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class FavoritesViewModel(application: Application) : AndroidViewModel(application) {

    private val _favoriteIds = MutableStateFlow<Set<Int>>(emptySet())
    val favoriteIds: StateFlow<Set<Int>> = _favoriteIds.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Load favorites for a specific user from backend
    fun loadFavorites(userId: Int) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // Assuming you create a favorites endpoint or use ApiClient service
                // val response = ApiClient.apiService.getFavorites(userId)
                // if (response.isSuccessful) { _favoriteIds.value = response.body()?.map { it.productId }?.toSet() ?: emptySet() }
            } catch (e: Exception) {
                Log.e("FavoritesViewModel", "Error loading favorites", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    // Toggle favorite locally and sync with backend
    fun toggleFavorite(productId: Int, userId: Int? = null) {
        viewModelScope.launch {
            val currentFavorites = _favoriteIds.value.toMutableSet()
            val isCurrentlyFavorite = currentFavorites.contains(productId)

            if (isCurrentlyFavorite) {
                currentFavorites.remove(productId)
            } else {
                currentFavorites.add(productId)
            }
            _favoriteIds.value = currentFavorites

            // TODO: Fire network request to sync with backend table
            // userId?.let { 
            //     if (isCurrentlyFavorite) ApiClient.apiService.removeFavorite(it, productId)
            //     else ApiClient.apiService.addFavorite(it, productId)
            // }
        }
    }
}