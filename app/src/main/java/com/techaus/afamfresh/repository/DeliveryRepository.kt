package com.techaus.afamfresh.repository

import com.techaus.afamfresh.api.ApiService
import com.techaus.afamfresh.models.DeliveryQuoteRequest
import com.techaus.afamfresh.models.DeliveryQuoteResponse
import com.techaus.afamfresh.utils.ApiError
import com.techaus.afamfresh.utils.enqueueApi

/**
 * Asks the server what delivery costs.
 *
 * There is deliberately no local fallback calculation. The fee depends on the
 * order value, the distance, a service fee and two percentage components, all
 * configured server-side in `delivery_pricing` and applied by
 * `includes/delivery-fee.php`. A second copy of those rules in the app would
 * drift, and the customer would be shown a figure different from the one
 * recorded against their order.
 *
 * When the quote cannot be obtained, callers must say so rather than guess —
 * see DeliveryMapScreen, which refuses to let the user confirm.
 */
class DeliveryRepository(private val apiService: ApiService) {

    fun quote(
        orderValue: Double,
        lat: Double,
        lng: Double,
        address: String = "",
        area: String = "",
        callback: (DeliveryQuoteResponse?, ApiError?) -> Unit
    ) {
        // The endpoint rejects a non-positive order value outright, so catch it
        // here with a message the user can act on.
        if (orderValue <= 0.0) {
            callback(null, ApiError.Reported("Add something to your cart before choosing a delivery point."))
            return
        }

        val request = DeliveryQuoteRequest(
            address = address,
            area = area,
            orderValue = orderValue,
            lat = lat,
            lng = lng
        )

        apiService.calculateDeliveryFee(request)
            .enqueueApi<DeliveryQuoteResponse>("DeliveryRepo", "calculateDeliveryFee") { body, error ->
                when {
                    error != null -> callback(null, error)
                    body?.success == true && body.fee != null -> callback(body, null)
                    // The server distinguishes GEOCODE_FAILED from
                    // NO_LOCATION_DATA and its wording is more specific than
                    // anything we could write here.
                    else -> callback(null, ApiError.reported(body?.error))
                }
            }
    }
}
