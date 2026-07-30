package com.techaus.afamfresh.models

import com.google.gson.annotations.SerializedName

/**
 * Request and response for `api/calculate-delivery-fee.php`.
 *
 * Field names verified against the real endpoint, not inferred.
 *
 * Why this exists: the delivery fee is tiered by ORDER VALUE, not by distance
 * alone — free above 100,000 UGX, free above 50,000 within 10 km, otherwise a
 * per-km rate that itself depends on the order value, plus a service fee and
 * insurance/processing percentages of the order value.
 *
 * The app therefore cannot compute this from a map tap: it would need the cart
 * subtotal and an exact copy of the server's rules, and any drift between the
 * two would charge the customer a different figure from the one the backend
 * records. The server is the single source of truth.
 */
data class DeliveryQuoteRequest(
    @SerializedName("address") val address: String,
    @SerializedName("area") val area: String,
    /** Cart subtotal in UGX. The endpoint rejects anything <= 0. */
    @SerializedName("order_value") val orderValue: Double,
    /**
     * User-confirmed coordinates from the map. When present the server skips
     * geocoding and uses OSRM road distance, which is what we want — a tapped
     * pin is more accurate than geocoding a typed address.
     */
    @SerializedName("lat") val lat: Double? = null,
    @SerializedName("lng") val lng: Double? = null
)

data class DeliveryQuoteResponse(
    @SerializedName("success") val success: Boolean,
    /** Road distance in km from the warehouse, rounded to 2dp by the server. */
    @SerializedName("distance") val distance: Double? = null,
    /** TOTAL delivery charge, already including every component below. */
    @SerializedName("fee") val fee: Double? = null,
    @SerializedName("is_free") val isFree: Boolean = false,
    /** Human-readable explanation, e.g. "Free delivery for orders above 100,000 UGX". */
    @SerializedName("reason") val reason: String? = null,
    @SerializedName("dest_lat") val destLat: Double? = null,
    @SerializedName("dest_lng") val destLng: Double? = null,
    @SerializedName("breakdown") val breakdown: DeliveryFeeBreakdown? = null,
    @SerializedName("error") val error: String? = null,
    /** e.g. GEOCODE_FAILED, NO_LOCATION_DATA */
    @SerializedName("error_code") val errorCode: String? = null
)

/**
 * The components that make up [DeliveryQuoteResponse.fee].
 *
 * These map onto the `orders` table's delivery_fee / service_fee /
 * insurance_fee / processing_fee columns, so showing them to the customer
 * matches what gets recorded against their order.
 */
data class DeliveryFeeBreakdown(
    @SerializedName("distance_fee") val distanceFee: Double = 0.0,
    @SerializedName("service_fee") val serviceFee: Double = 0.0,
    @SerializedName("insurance_fee") val insuranceFee: Double = 0.0,
    @SerializedName("processing_fee") val processingFee: Double = 0.0
) {
    val total: Double get() = distanceFee + serviceFee + insuranceFee + processingFee
}
