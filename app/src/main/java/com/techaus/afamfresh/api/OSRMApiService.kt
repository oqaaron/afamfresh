package com.techaus.afamfresh.api

import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

// OSRM's public route API contract (documented at project-osrm.org) —
// GET /route/v1/{profile}/{lon1},{lat1};{lon2},{lat2}?overview=false
// This is a well-known external API shape, not project-specific, so
// confidence here is much higher than for your custom endpoints.
interface OSRMApiService {
    @GET("route/v1/driving/{coordinates}")
    fun getRoute(
        @Path(value = "coordinates", encoded = true) coordinates: String, // "lon1,lat1;lon2,lat2"
        @Query("overview") overview: String = "false"
    ): Call<OSRMRouteResponse>
}

data class OSRMRouteResponse(
    val code: String,
    val routes: List<OSRMRoute>? = null
)

data class OSRMRoute(
    val distance: Double, // meters
    val duration: Double  // seconds
)
