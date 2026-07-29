package com.techaus.afamfresh.repository

import android.util.Log
import com.techaus.afamfresh.api.ApiService
import com.techaus.afamfresh.models.SurplusListing
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

// Constructor confirmed by MainActivity.kt: SurplusRepository(ApiClient.apiService)
class SurplusRepository(
    private val apiService: ApiService
) {
    fun getPublicListings(status: String = "approved", callback: (List<SurplusListing>?) -> Unit) {
        apiService.getSurplusListings(status = status).enqueue(object : Callback<com.techaus.afamfresh.models.SurplusListingsResponse> {
            override fun onResponse(
                call: Call<com.techaus.afamfresh.models.SurplusListingsResponse>,
                response: Response<com.techaus.afamfresh.models.SurplusListingsResponse>
            ) {
                if (response.isSuccessful) {
                    callback(response.body()?.listings ?: emptyList())
                } else {
                    Log.e("SurplusRepo", "getPublicListings HTTP error: ${response.code()}")
                    callback(null)
                }
            }

            override fun onFailure(call: Call<com.techaus.afamfresh.models.SurplusListingsResponse>, t: Throwable) {
                Log.e("SurplusRepo", "getPublicListings network failure: ${t.message}", t)
                callback(null)
            }
        })
    }
}
