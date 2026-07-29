package com.techaus.afamfresh.repository

import com.techaus.afamfresh.api.ApiService
import com.techaus.afamfresh.models.SurplusListing
import com.techaus.afamfresh.models.SurplusListingsResponse
import com.techaus.afamfresh.utils.ApiError
import com.techaus.afamfresh.utils.enqueueApi

// Constructor confirmed by MainActivity.kt: SurplusRepository(ApiClient.apiService)
class SurplusRepository(
    private val apiService: ApiService
) {
    fun getPublicListings(
        status: String = "approved",
        callback: (List<SurplusListing>?, ApiError?) -> Unit
    ) {
        apiService.getSurplusListings(status = status)
            .enqueueApi<SurplusListingsResponse>("SurplusRepo", "getPublicListings") { body, error ->
                when {
                    error != null -> callback(null, error)
                    body?.success == true -> callback(body.listings ?: emptyList(), null)
                    else -> callback(null, ApiError.reported(body?.error))
                }
            }
    }
}
