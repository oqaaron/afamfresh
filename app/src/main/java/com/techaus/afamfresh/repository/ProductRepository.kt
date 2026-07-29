package com.techaus.afamfresh.repository

import android.util.Log
import com.techaus.afamfresh.api.ApiService
import com.techaus.afamfresh.models.Product
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

// Constructor confirmed by MainActivity.kt: ProductRepository(ApiClient.apiService)
class ProductRepository(
    private val apiService: ApiService
) {
    fun getProducts(callback: (List<Product>?) -> Unit) {
        apiService.getProducts().enqueue(object : Callback<com.techaus.afamfresh.models.ProductsResponse> {
            override fun onResponse(
                call: Call<com.techaus.afamfresh.models.ProductsResponse>,
                response: Response<com.techaus.afamfresh.models.ProductsResponse>
            ) {
                if (response.isSuccessful && response.body()?.success == true) {
                    callback(response.body()?.products ?: emptyList())
                } else {
                    Log.e("ProductRepo", "getProducts failed: ${response.code()}")
                    callback(null)
                }
            }

            override fun onFailure(call: Call<com.techaus.afamfresh.models.ProductsResponse>, t: Throwable) {
                Log.e("ProductRepo", "getProducts network failure: ${t.message}", t)
                callback(null)
            }
        })
    }
}
