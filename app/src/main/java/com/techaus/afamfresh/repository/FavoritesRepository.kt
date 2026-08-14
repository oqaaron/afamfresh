package com.techaus.afamfresh.repository

import com.techaus.afamfresh.api.ApiService
import com.techaus.afamfresh.models.FavoritesResponse
import com.techaus.afamfresh.models.ToggleFavoriteResponse
import com.techaus.afamfresh.utils.enqueueApi

class FavoritesRepository(
    private val apiService: ApiService
) {
    private companion object {
        const val TAG = "FavoritesRepo"
    }

    fun getFavoriteIds(callback: (Set<Int>) -> Unit) {
        apiService.getFavorites().enqueueApi<FavoritesResponse>(TAG, "getFavoriteIds") { body, error ->
            if (error != null || body?.success != true) {
                callback(emptySet())
            } else {
                callback(body.productIds?.toSet() ?: emptySet())
            }
        }
    }

    /** [callback] reports (succeeded, newIsFavoriteState). */
    fun toggleFavorite(productId: Int, callback: (Boolean, Boolean) -> Unit) {
        apiService.toggleFavorite(productId = productId)
            .enqueueApi<ToggleFavoriteResponse>(TAG, "toggleFavorite") { body, error ->
                if (error != null || body?.success != true || body.isFavorite == null) {
                    callback(false, false)
                } else {
                    callback(true, body.isFavorite)
                }
            }
    }
}
