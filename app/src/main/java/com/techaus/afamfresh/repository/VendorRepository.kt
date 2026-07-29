package com.techaus.afamfresh.repository

import android.util.Log
import com.techaus.afamfresh.api.ApiService
import com.techaus.afamfresh.models.BaseResponse
import com.techaus.afamfresh.models.Order
import com.techaus.afamfresh.models.Product
import com.techaus.afamfresh.models.SurplusListing
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

// Constructor confirmed by MainActivity.kt: VendorRepository(ApiClient.apiService)
// Method params mirror ApiService.kt's vendor/*.php declarations exactly.
class VendorRepository(
    private val apiService: ApiService
) {
    fun getListings(callback: (List<SurplusListing>?) -> Unit) {
        apiService.getVendorSurplusListings().enqueue(object : Callback<com.techaus.afamfresh.models.SurplusListingsResponse> {
            override fun onResponse(
                call: Call<com.techaus.afamfresh.models.SurplusListingsResponse>,
                response: Response<com.techaus.afamfresh.models.SurplusListingsResponse>
            ) {
                callback(if (response.isSuccessful) response.body()?.listings else null)
            }

            override fun onFailure(call: Call<com.techaus.afamfresh.models.SurplusListingsResponse>, t: Throwable) {
                Log.e("VendorRepo", "getListings network failure: ${t.message}", t)
                callback(null)
            }
        })
    }

    fun createListing(listing: SurplusListing, callback: (SurplusListing?) -> Unit) {
        apiService.createVendorSurplusListing(listing = listing).enqueue(object : Callback<com.techaus.afamfresh.models.SurplusListingResponse> {
            override fun onResponse(
                call: Call<com.techaus.afamfresh.models.SurplusListingResponse>,
                response: Response<com.techaus.afamfresh.models.SurplusListingResponse>
            ) {
                callback(if (response.isSuccessful) response.body()?.listing else null)
            }

            override fun onFailure(call: Call<com.techaus.afamfresh.models.SurplusListingResponse>, t: Throwable) {
                Log.e("VendorRepo", "createListing network failure: ${t.message}", t)
                callback(null)
            }
        })
    }

    fun updateListing(
        id: String,
        title: String,
        description: String?,
        originalPrice: Double,
        price: Double,
        quantity: Double,
        unit: String,
        expiresAt: String,
        callback: (Boolean) -> Unit
    ) {
        apiService.updateVendorSurplusListing(
            id = id,
            title = title,
            description = description,
            originalPrice = originalPrice,
            price = price,
            quantity = quantity,
            unit = unit,
            expiresAt = expiresAt
        ).enqueue(object : Callback<BaseResponse> {
            override fun onResponse(call: Call<BaseResponse>, response: Response<BaseResponse>) {
                callback(response.isSuccessful && response.body()?.success == true)
            }

            override fun onFailure(call: Call<BaseResponse>, t: Throwable) {
                Log.e("VendorRepo", "updateListing network failure: ${t.message}", t)
                callback(false)
            }
        })
    }

    fun deleteListing(id: String, callback: (Boolean) -> Unit) {
        apiService.deleteVendorSurplusListing(id = id).enqueue(object : Callback<BaseResponse> {
            override fun onResponse(call: Call<BaseResponse>, response: Response<BaseResponse>) {
                callback(response.isSuccessful && response.body()?.success == true)
            }

            override fun onFailure(call: Call<BaseResponse>, t: Throwable) {
                Log.e("VendorRepo", "deleteListing network failure: ${t.message}", t)
                callback(false)
            }
        })
    }

    fun getVendorOrders(callback: (List<Order>?) -> Unit) {
        apiService.getVendorOrders().enqueue(object : Callback<com.techaus.afamfresh.models.VendorOrdersResponse> {
            override fun onResponse(
                call: Call<com.techaus.afamfresh.models.VendorOrdersResponse>,
                response: Response<com.techaus.afamfresh.models.VendorOrdersResponse>
            ) {
                callback(if (response.isSuccessful) response.body()?.orders else null)
            }

            override fun onFailure(call: Call<com.techaus.afamfresh.models.VendorOrdersResponse>, t: Throwable) {
                Log.e("VendorRepo", "getVendorOrders network failure: ${t.message}", t)
                callback(null)
            }
        })
    }

    fun getVendorProducts(callback: (List<Product>?) -> Unit) {
        apiService.getVendorProducts().enqueue(object : Callback<com.techaus.afamfresh.models.VendorProductsResponse> {
            override fun onResponse(
                call: Call<com.techaus.afamfresh.models.VendorProductsResponse>,
                response: Response<com.techaus.afamfresh.models.VendorProductsResponse>
            ) {
                callback(if (response.isSuccessful) response.body()?.products else null)
            }

            override fun onFailure(call: Call<com.techaus.afamfresh.models.VendorProductsResponse>, t: Throwable) {
                Log.e("VendorRepo", "getVendorProducts network failure: ${t.message}", t)
                callback(null)
            }
        })
    }
}
