package com.techaus.afamfresh.repository

import com.techaus.afamfresh.api.ApiService
import com.techaus.afamfresh.models.Product
import com.techaus.afamfresh.models.ProductDetailResponse
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

    /**
     * Fetches one product by id.
     *
     * `items.id` is int(100) and the endpoint does `intval($_GET['id'])`, so the
     * id is an Int — it used to be passed as a String against a Product-typed
     * response, and the response is actually a {success, product} envelope.
     */
    fun getProduct(id: Int, callback: (Product?, ApiError?) -> Unit) {
        apiService.getProduct(id = id)
            .enqueueApi<ProductDetailResponse>("ProductRepo", "getProduct") { body, error ->
                when {
                    error != null -> callback(null, error)
                    body?.success == true && body.product != null -> callback(body.product, null)
                    else -> callback(null, ApiError.reported(body?.error))
                }
            }
    }
}
