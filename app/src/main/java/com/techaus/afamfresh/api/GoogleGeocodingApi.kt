package com.techaus.afamfresh.api

import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Query

// Google Geocoding API's public contract (documented at
// developers.google.com/maps/documentation/geocoding).
interface GoogleGeocodingApi {
    @GET("maps/api/geocode/json")
    fun geocode(
        @Query("address") address: String,
        @Query("key") apiKey: String
    ): Call<GoogleGeocodingResponse>

    @GET("maps/api/geocode/json")
    fun reverseGeocode(
        @Query("latlng") latLng: String, // "lat,lng"
        @Query("key") apiKey: String
    ): Call<GoogleGeocodingResponse>
}

data class GoogleGeocodingResponse(
    val status: String,
    val results: List<GoogleGeocodingResult> = emptyList()
)

data class GoogleGeocodingResult(
    val formatted_address: String,
    val geometry: GoogleGeometry
)

data class GoogleGeometry(
    val location: GoogleLatLng
)

data class GoogleLatLng(
    val lat: Double,
    val lng: Double
)
