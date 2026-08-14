package com.techaus.afamfresh.viewmodel

import androidx.lifecycle.ViewModel
import com.techaus.afamfresh.repository.FavoritesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One shared instance backs both HomeScreen's product-card hearts and
 * ProductDetailScreen's header heart (see AppViewModelFactory/MainScreen
 * wiring), so favoriting a product on one screen is reflected on the other
 * immediately, without a network round trip.
 */
class FavoritesViewModel(
    private val favoritesRepository: FavoritesRepository
) : ViewModel() {

    private val _favoriteIds = MutableStateFlow<Set<Int>>(emptySet())
    val favoriteIds: StateFlow<Set<Int>> = _favoriteIds.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        favoritesRepository.getFavoriteIds { ids ->
            _favoriteIds.value = ids
        }
    }

    /**
     * Flips locally before the network call returns, so the heart responds
     * on the same frame as the tap. Reverts if the call fails — a heart tap
     * is frequent and low-stakes enough that waiting for a round trip first
     * would read as broken, unlike a cart or address mutation.
     */
    fun toggleFavorite(productId: Int) {
        val wasFavorite = productId in _favoriteIds.value
        _favoriteIds.value = if (wasFavorite) {
            _favoriteIds.value - productId
        } else {
            _favoriteIds.value + productId
        }

        favoritesRepository.toggleFavorite(productId) { succeeded, _ ->
            if (!succeeded) {
                // Revert to exactly the pre-tap state, not just "toggle
                // again" — a second failed toggle in between would make
                // that wrong.
                _favoriteIds.value = if (wasFavorite) {
                    _favoriteIds.value + productId
                } else {
                    _favoriteIds.value - productId
                }
            }
        }
    }
}
