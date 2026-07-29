package com.techaus.afamfresh.api

import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Query

// Nominatim's public search/reverse API contract (documented at
// nominatim.org/release-docs/latest/api/Search and .../Reverse).
// ⚠️ Your ApiClient.kt points this at a self-hosted instance
// (192.168.3.41:8080), not the public nominatim.openstreetmap.org — the
// response SHAPE below matches Nominatim generally, but confirm your
// self-hosted instance returns the same fields (most standard installs do).
interface NominatimApiService {
    @GET("search")
    fun search(
        @Query("q") query: String,
        @Query("format") format: String = "json",
        @Query("limit") limit: Int = 5,
        @Query("addressdetails") addressDetails: Int = 1
    ): Call<List<NominatimResult>>

    @GET("reverse")
    fun reverseGeocode(
        @Query("lat") lat: Double,
        @Query("lon") lon: Double,
        @Query("format") format: String = "json"
    ): Call<NominatimResult>
}

data class NominatimResult(
    val place_id: Long? = null,
    val lat: String,
    val lon: String,
    val display_name: String
)
