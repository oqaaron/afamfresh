package com.techaus.afamfresh.repository

import com.techaus.afamfresh.api.ApiService
import com.techaus.afamfresh.models.Product
import com.techaus.afamfresh.models.ProductsResponse
import com.techaus.afamfresh.utils.ApiError
import com.techaus.afamfresh.utils.enqueueApi

// Constructor confirmed by MainActivity.kt: ProductRepository(ApiClient.apiService)
class ProductRepository(
    private val apiService: ApiService
) {
    /**
     * Reports the reason on failure instead of a bare null, so the UI can tell
     * the user whether they are offline, the server is down, or the request
     * was rejected.
     */
    fun getProducts(callback: (List<Product>?, ApiError?) -> Unit) {
        apiService.getProducts().enqueueApi<ProductsResponse>("ProductRepo", "getProducts") { body, error ->
            when {
                error != null -> callback(null, error)
                body?.success == true -> callback(body.products ?: emptyList(), null)
                else -> callback(null, ApiError.reported(body?.error))
            }
        }
    }
}
