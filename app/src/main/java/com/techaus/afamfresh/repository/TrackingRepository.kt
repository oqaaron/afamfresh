package com.techaus.afamfresh.repository

import com.techaus.afamfresh.api.ApiService
import com.techaus.afamfresh.models.TrackingResponse
import com.techaus.afamfresh.utils.ApiError
import com.techaus.afamfresh.utils.enqueueApi

/**
 * Where a delivery is.
 *
 * One method, called on a loop while a tracking screen is open. The whole
 * response is handed back rather than a distilled position: the screen needs
 * the route, the trail, the rider, the ETA and the server's polling interval,
 * and picking them apart here would only mean reassembling them there.
 */
class TrackingRepository(
    private val apiService: ApiService
) {
    fun getTracking(
        orderId: Int,
        source: String,
        since: String? = null,
        callback: (TrackingResponse?, ApiError?) -> Unit
    ) {
        apiService.getOrderTracking(orderId = orderId, source = source, since = since)
            .enqueueApi<TrackingResponse>("TrackingRepo", "getTracking") { body, error ->
                when {
                    error != null -> callback(null, error)
                    body?.success == true -> callback(body, null)
                    else -> callback(null, ApiError.reported(body?.error))
                }
            }
    }
}