package com.techaus.afamfresh.config

import android.util.Log
import com.techaus.afamfresh.models.DeliveryPricingConfig

/**
 * Delivery pricing — the single place in the app where it is defined.
 *
 * These values used to sit as `private const val`s inside DeliveryMapScreen,
 * which had two problems beyond being guesses:
 *
 *  1. Changing a rate needed a new app release, and until every customer
 *     updated, different people would be quoted different prices.
 *  2. A screen is the wrong place to decide what to charge. The server has to
 *     validate the amount anyway (a client-computed price is trivially
 *     tampered with), so the server should be the one that states it.
 *
 * So [current] is refreshed from `config.php` at startup when the backend
 * supplies pricing, and [FALLBACK] is used only when it doesn't.
 */
object DeliveryConfig {

    /**
     * ⚠️ THESE FIVE VALUES ARE NOT CONFIRMED — they are inherited placeholders.
     *
     * They are used ONLY when the server sends no pricing. Set them to your
     * real figures, or better, return `delivery_pricing` from config.php and
     * these stop mattering.
     *
     *  - pickup coordinates: currently central Kampala. EVERY delivery distance
     *    is measured from this point, so if it is wrong, every price is wrong.
     *  - baseFareUgx / costPerKmUgx: what you actually charge.
     *  - maxDeliveryKm: refuse deliveries beyond this radius.
     */
    val FALLBACK = DeliveryPricing(
        pickupLat = 0.3476,
        pickupLng = 32.5825,
        pickupLabel = "AfamFresh Store, Kampala",
        baseFareUgx = 2000.0,
        costPerKmUgx = 1000.0,
        maxDeliveryKm = 25.0
    )

    /**
     * Read by DeliveryMapScreen. Written once at startup from the app config.
     * `@Volatile` because it is written on a network callback thread and read
     * during composition on the main thread.
     */
    @Volatile
    var current: DeliveryPricing = FALLBACK
        private set

    /** True when [current] came from the server rather than [FALLBACK]. */
    @Volatile
    var isServerProvided: Boolean = false
        private set

    /**
     * Applies server-supplied pricing. Partial configs are ignored rather than
     * merged: quoting a price from a half-populated config would be worse than
     * using a fallback we at least know the shape of.
     */
    fun applyServerPricing(pricing: DeliveryPricing?) {
        if (pricing == null) return
        current = pricing
        isServerProvided = true
    }
}

/**
 * Converts a server config block into usable pricing, or null if it is
 * incomplete.
 *
 * All-or-nothing on purpose: if the backend sends `base_fare` but omits
 * `cost_per_km`, silently treating the missing rate as zero would quote every
 * delivery at the flat base fare. Rejecting the whole block and falling back
 * is the safer failure.
 */
fun DeliveryPricingConfig.toPricingOrNull(): DeliveryPricing? {
    val lat = pickupLat
    val lng = pickupLng
    val base = baseFare
    val perKm = costPerKm
    val maxKm = maxDeliveryKm

    if (lat == null || lng == null || base == null || perKm == null || maxKm == null) {
        Log.w(
            "DeliveryConfig",
            "Server delivery_pricing is incomplete — ignoring it and using the " +
                "built-in fallback. Missing one of: pickup_lat, pickup_lng, " +
                "base_fare, cost_per_km, max_delivery_km."
        )
        return null
    }

    if (base < 0 || perKm < 0 || maxKm <= 0) {
        Log.w("DeliveryConfig", "Server delivery_pricing has invalid values — ignoring it.")
        return null
    }

    return DeliveryPricing(
        pickupLat = lat,
        pickupLng = lng,
        pickupLabel = pickupLabel?.takeIf { it.isNotBlank() } ?: "Pickup point",
        baseFareUgx = base,
        costPerKmUgx = perKm,
        maxDeliveryKm = maxKm
    )
}

/**
 * An immutable snapshot of what delivery costs.
 */
data class DeliveryPricing(
    val pickupLat: Double,
    val pickupLng: Double,
    val pickupLabel: String,
    val baseFareUgx: Double,
    val costPerKmUgx: Double,
    val maxDeliveryKm: Double
)

/**
 * The delivery quote, as a pure function of distance.
 *
 * Deliberately free of Android and Compose types so it can be unit-tested
 * directly — this is the calculation that decides what a customer pays, so it
 * is the single most important thing in the app to have tests around.
 *
 * Negative distances are treated as zero rather than discounting the fare.
 */
fun DeliveryPricing.quoteFor(distanceKm: Double): Double =
    baseFareUgx + (distanceKm.coerceAtLeast(0.0) * costPerKmUgx)

/** Whether a drop-off at this distance is inside the delivery radius. */
fun DeliveryPricing.isWithinRange(distanceKm: Double): Boolean =
    distanceKm <= maxDeliveryKm
