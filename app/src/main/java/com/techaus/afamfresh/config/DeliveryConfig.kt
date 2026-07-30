package com.techaus.afamfresh.config

/**
 * Where deliveries start from.
 *
 * ## What changed and why
 *
 * This file used to hold a local pricing formula — `baseFare + distance *
 * costPerKm`, with a max radius. That model does not exist in the business.
 * The real rules live in `includes/delivery-fee.php` and the `delivery_pricing`
 * table, and they are tiered by ORDER VALUE:
 *
 *   - above 100,000 UGX          -> no distance charge
 *   - above  50,000 UGX within 10km -> no distance charge
 *   - above  50,000 UGX beyond 10km -> long-distance rate per km
 *   - otherwise                  -> short-distance rate per km (higher)
 *
 * plus a flat service fee, insurance at a percentage of order value, and
 * processing at a percentage of order value.
 *
 * The app cannot reproduce that from a map tap — it would need the cart
 * subtotal and an exact copy of rules that an admin can change at any time.
 * So pricing is no longer computed here at all. DeliveryMapScreen asks
 * `api/calculate-delivery-fee.php` and displays what it returns.
 *
 * All that remains local is the pickup point, used purely to draw the origin
 * marker and centre the map. The server computes distance from its own
 * OFFICE_LAT/OFFICE_LNG, so even if this drifted it could not affect a price.
 */
object DeliveryConfig {

    /**
     * ✅ VERIFIED from the backend's `admin/includes/config.php`:
     *     OFFICE_LAT     = 0.38082497218633615
     *     OFFICE_LNG     = 32.65071116168179
     *     OFFICE_ADDRESS = 'AfamFresh Warehouse, Kampala'
     *
     * The previous placeholder (0.3476, 32.5825 — central Kampala) sat roughly
     * 8 km from the real warehouse, so every distance the app displayed was
     * wrong by up to that much.
     */
    const val PICKUP_LAT = 0.38082497218633615
    const val PICKUP_LNG = 32.65071116168179
    const val PICKUP_LABEL = "AfamFresh Warehouse, Kampala"
}
