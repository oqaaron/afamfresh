package com.techaus.afamfresh.repository

import com.techaus.afamfresh.api.ApiService
import com.techaus.afamfresh.models.BaseResponse
import com.techaus.afamfresh.models.Order
import com.techaus.afamfresh.models.Product
import com.techaus.afamfresh.models.SurplusListing
import com.techaus.afamfresh.models.SurplusListingResponse
import com.techaus.afamfresh.models.SurplusListingsResponse
import com.techaus.afamfresh.models.VendorOrdersResponse
import com.techaus.afamfresh.models.VendorProductsResponse
import com.techaus.afamfresh.utils.ApiError
import com.techaus.afamfresh.utils.enqueueApi

// Constructor confirmed by MainActivity.kt: VendorRepository(ApiClient.apiService)
// Method params mirror ApiService.kt's vendor/*.php declarations exactly.
class VendorRepository(
    private val apiService: ApiService
) {
    fun getListings(callback: (List<SurplusListing>?, ApiError?) -> Unit) {
        apiService.getVendorSurplusListings()
            .enqueueApi<SurplusListingsResponse>("VendorRepo", "getListings") { body, error ->
                when {
                    error != null -> callback(null, error)
                    body?.success == true -> callback(body.listings ?: emptyList(), null)
                    else -> callback(null, ApiError.reported(body?.error))
                }
            }
    }

    fun createListing(listing: SurplusListing, callback: (SurplusListing?, ApiError?) -> Unit) {
        apiService.createVendorSurplusListing(listing = listing)
            .enqueueApi<SurplusListingResponse>("VendorRepo", "createListing") { body, error ->
                when {
                    error != null -> callback(null, error)
                    body?.success == true && body.listing != null -> callback(body.listing, null)
                    else -> callback(null, ApiError.reported(body?.error))
                }
            }
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
        callback: (Boolean, ApiError?) -> Unit
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
        ).enqueueApi<BaseResponse>("VendorRepo", "updateListing") { body, error ->
            when {
                error != null -> callback(false, error)
                body?.success == true -> callback(true, null)
                else -> callback(false, ApiError.reported(body?.error))
            }
        }
    }

    fun deleteListing(id: String, callback: (Boolean, ApiError?) -> Unit) {
        apiService.deleteVendorSurplusListing(id = id)
            .enqueueApi<BaseResponse>("VendorRepo", "deleteListing") { body, error ->
                when {
                    error != null -> callback(false, error)
                    body?.success == true -> callback(true, null)
                    else -> callback(false, ApiError.reported(body?.error))
                }
            }
    }

    fun getVendorOrders(callback: (List<Order>?, ApiError?) -> Unit) {
        apiService.getVendorOrders()
            .enqueueApi<VendorOrdersResponse>("VendorRepo", "getVendorOrders") { body, error ->
                when {
                    error != null -> callback(null, error)
                    body?.success == true -> callback(body.orders ?: emptyList(), null)
                    else -> callback(null, ApiError.reported(body?.error))
                }
            }
    }

    fun getVendorProducts(callback: (List<Product>?, ApiError?) -> Unit) {
        apiService.getVendorProducts()
            .enqueueApi<VendorProductsResponse>("VendorRepo", "getVendorProducts") { body, error ->
                when {
                    error != null -> callback(null, error)
                    body?.success == true -> callback(body.products ?: emptyList(), null)
                    else -> callback(null, ApiError.reported(body?.error))
                }
            }
    }
}
