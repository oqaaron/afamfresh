package com.techaus.afamfresh.models

import com.google.gson.annotations.SerializedName

/**
 * ✅ Read field-for-field from api/tracking.php.
 *
 * ⚠️ EVERY FIELD IS NULLABLE WITH A NULL DEFAULT. Gson allocates data classes
 * through Unsafe and bypasses Kotlin constructors, so a non-null field with a
 * default silently becomes null when the JSON omits it — and then throws at the
 * first use, far from the cause. RiderModels.kt already documents this having
 * bitten this codebase. Half these fields are legitimately absent: there is no
 * position before the rider collects, no route before it is fetched, no ETA
 * once delivered.
 */
data class TrackingResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("order_id") val orderId: Int? = null,
    @SerializedName("source") val source: String? = null,

    /** assigned | picked_up | on_way | delivered — the ASSIGNMENT's stage. */
    @SerializedName("status") val status: String? = null,

    /** The order's own status, which uses a different vocabulary per channel. */
    @SerializedName("order_status") val orderStatus: String? = null,

    @SerializedName("has_rider") val hasRider: Boolean = false,
    @SerializedName("rider") val rider: TrackedRider? = null,
    @SerializedName("position") val position: TrackedPosition? = null,
    @SerializedName("pickup") val pickup: TrackedPlace? = null,
    @SerializedName("dropoff") val dropoff: TrackedPlace? = null,

    /** Google encoded polyline. Null until the rider collects. */
    @SerializedName("route_polyline") val routePolyline: String? = null,
    @SerializedName("route_distance_km") val routeDistanceKm: Double? = null,

    @SerializedName("trail") val trail: List<TrackedPoint>? = null,
    @SerializedName("eta_minutes") val etaMinutes: Int? = null,
    @SerializedName("distance_remaining_km") val distanceRemainingKm: Double? = null,

    /**
     * How long to wait before asking again — the SERVER decides the cadence.
     *
     * Deliberately not a client constant: a release build takes sixteen minutes
     * and cannot be forced onto an install base, so a polling interval baked
     * into the app is one that can never be changed. Zero means stop.
     */
    @SerializedName("poll_after_seconds") val pollAfterSeconds: Int? = null,
    @SerializedName("finished") val finished: Boolean = false,
    @SerializedName("error") val error: String? = null
) {
    val isOnTheWay: Boolean get() = status == "picked_up" || status == "on_way"

    /** What the customer should read as the headline. */
    val headline: String
        get() = when (status) {
            "assigned" -> "A rider is on the way to collect it"
            "picked_up" -> "Picked up — heading to you"
            "on_way" -> "On the way to you"
            "delivered" -> "Delivered"
            else -> if (hasRider) "Preparing your order" else "Waiting for a rider"
        }

    /** 0-3, for the stage stepper. */
    val stageIndex: Int
        get() = when (status) {
            "picked_up" -> 1
            "on_way" -> 2
            "delivered" -> 3
            else -> 0
        }
}

data class TrackedRider(
    @SerializedName("name") val name: String? = null,
    /** Withheld by the server once the delivery is over. */
    @SerializedName("phone") val phone: String? = null,
    @SerializedName("vehicle_type") val vehicleType: String? = null,
    @SerializedName("avg_rating") val avgRating: Double? = null
)

data class TrackedPosition(
    @SerializedName("lat") val lat: Double? = null,
    @SerializedName("lng") val lng: Double? = null,
    @SerializedName("heading_deg") val headingDeg: Int? = null,
    @SerializedName("recorded_at") val recordedAt: String? = null
)

data class TrackedPlace(
    @SerializedName("lat") val lat: Double? = null,
    @SerializedName("lng") val lng: Double? = null,
    @SerializedName("label") val label: String? = null,
    @SerializedName("area") val area: String? = null
)

data class TrackedPoint(
    @SerializedName("lat") val lat: Double? = null,
    @SerializedName("lng") val lng: Double? = null,
    @SerializedName("at") val at: String? = null
)