<?php
// =============================================================
// includes/Bulk_delivery_fee.php
//
// What a Bulk delivery costs, itemised.
//
// WHY NOT calculateDeliveryFee()
//
// The shop's fee is tiered by order value: above the free threshold, currently
// UGX 100,000, the distance component is waived entirely. Every Bulk order
// is at least 250,000 by rule, so reusing those tiers would make distance free
// on every single one -- for loads up to a tonne, driven anywhere in the
// country. The tiers encode "a big shopping basket deserves free delivery",
// which is a sensible retail idea and a ruinous wholesale one.
//
// So Bulk keeps its own thresholds from `Bulk_delivery_settings` and
// borrows only the two components that are genuinely the same business cost
// wherever they appear -- the service fee and the insurance rate -- from
// `delivery_pricing`. An admin edits those once and both channels follow.
//
// WHAT MAKES UP THE FEE
//
//   base            flat; INCLUDES the first N km      Bulk_delivery_settings
//   weight          per kg of the load                 Bulk_delivery_settings
//   distance        per km BEYOND the included N       Bulk_delivery_settings
//   service         flat, platform handling            delivery_pricing
//   insurance       % of goods value                   delivery_pricing
//   processing      % of goods value                   delivery_pricing
//
// The whole fee is then capped at Bulk_delivery_settings.max_fee. Every one of
// those is admin-editable on the configuration page; none is a literal here.
//
// WHAT "FREE DELIVERY" WAIVES
//
// Above free_delivery_threshold, only the CARRIAGE goes: base, weight and
// distance. Service, insurance and processing are platform costs that do not
// disappear because an order is large, so they are still charged. The fee is
// therefore reduced, not zero -- deliberately.
//
// pickup_only is the one case that charges nothing at all: the customer
// collects, so nothing is carried, nothing is insured in transit and no rider
// is paid.
//
// Weight AND distance both count, unlike the shop where only distance does. A
// tonne is expensive to move three kilometres and a sack is cheap to move
// thirty; charging on one alone gets one of those badly wrong.
// =============================================================

require_once __DIR__ . '/delivery-fee.php';    // getDeliveryPricingConfig()
require_once __DIR__ . '/google_routes.php';  // roadDistanceBetween()

/**
 * Falls back to these only if Bulk_delivery_settings is empty.
 *
 * The last two are order LIMITS rather than fee inputs, and they live here
 * because they come from the same row and the same loader. They were literals
 * inside api/api/Bulk-orders.php until the configurable-limits migration; the
 * values below reproduce them exactly, so an empty settings table behaves as
 * the code always did.
 */
/** Trims 3.000 to "3" and 2.500 to "2.5" for a fee explanation. */
function tidyBulkKm($n): string {
    return rtrim(rtrim(number_format((float)$n, 3, '.', ''), '0'), '.');
}

const Bulk_FEE_DEFAULTS = [
    'base_fee'                => 5000.0,
    // Kilometres the flat base fee already covers. Only the excess is charged
    // per km — see calculateBulkDeliveryFee().
    'base_included_km'        => 3.0,
    'fee_per_kg'              => 500.0,
    'rate_per_km'             => 900.0,
    'max_fee'                 => 120000.0,
    'free_delivery_threshold' => 500000.0,
    'max_weight_kg'           => 1000.0,
    'min_order_value'         => 250000.0,
    'min_weight_kg'           => 20.0,
];

/**
 * Per-km rate for Bulk, and the cap.
 *
 * These are now COLUMNS on Bulk_delivery_settings (rate_per_km, max_fee) and
 * are read through BulkDeliverySettings() like everything else — the move the
 * previous version of this comment said to make "when an admin needs to tune
 * them without a deploy".
 *
 * The constants survive only as the fallback values in Bulk_FEE_DEFAULTS
 * above, and as the names any older code may still reference. Do not read them
 * directly in new code: an admin changing the rate would have no effect on
 * whatever did.
 */
const Bulk_RATE_PER_KM = 900.0;
const Bulk_MAX_FEE     = 120000.0;

/**
 * Reads the Bulk settings row, with defaults for anything missing.
 */
function BulkDeliverySettings(PDO $dbh): array {
    $settings = [];
    try {
        $row = $dbh->query("SELECT * FROM Bulk_delivery_settings LIMIT 1")
                   ->fetch(PDO::FETCH_ASSOC);
        if ($row) $settings = $row;
    } catch (PDOException $e) {
        error_log('Bulk settings unavailable, using defaults: ' . $e->getMessage());
    }

    $out = [];
    foreach (Bulk_FEE_DEFAULTS as $key => $default) {
        // Explicit null check, not ??: a column present but NULL should fall
        // back too, and `?? ` alone would accept a NULL from the row.
        $out[$key] = isset($settings[$key]) && $settings[$key] !== null
            ? (float)$settings[$key]
            : $default;
    }
    return $out;
}

/**
 * The distance a Bulk order travels, vendor to customer.
 *
 * ROAD distance, from Google Routes API — not the straight line. Roads are not
 * straight, and this fee charges per kilometre: Haversine understated a typical
 * Kampala journey by 20-40%, so every bulk order was under-charged by about
 * that much. See includes/google_routes.php, which falls back to OSRM and then
 * to Haversine if Google is unreachable.
 *
 * Returns null when either end is unknown — a vendor who has not pinned their
 * premises, or a customer who typed an address instead of dropping a pin. The
 * caller decides what to do about that; this does not invent a number, because
 * a guessed distance becomes a real charge.
 *
 * @return array{km: float, minutes: float, estimated: bool, source: string}|null
 */
function BulkDeliveryDistance(?float $vendorLat, ?float $vendorLng,
                                 ?float $destLat, ?float $destLng): ?array {
    if ($destLat === null || $destLng === null) {
        return null;
    }

    if ($vendorLat !== null && $vendorLng !== null) {
        return roadDistanceBetween($vendorLat, $vendorLng, $destLat, $destLng);
    }

    // The vendor has not pinned their premises yet. Measuring from the depot
    // keeps their listings sellable rather than blocking every order behind a
    // form they have not filled in — but the result is flagged estimated
    // regardless of how the distance itself was obtained, because the ORIGIN is
    // wrong, and that matters more than the routing method.
    $officeLat = defined('OFFICE_LAT') ? OFFICE_LAT : 0.38082497218633615;
    $officeLng = defined('OFFICE_LNG') ? OFFICE_LNG : 32.65071116168179;

    $route = roadDistanceBetween($officeLat, $officeLng, $destLat, $destLng);
    $route['estimated'] = true;
    return $route;
}

/**
 * The full itemised fee for one Bulk delivery.
 *
 * @param float      $goodsValue   discounted price x quantity, before any fee
 * @param float      $weightKg     total load weight
 * @param array|null $distance     from BulkDeliveryDistance(), or null
 * @param bool       $pickupOnly   collection listings are never charged
 *
 * @return array itemised, in the same shape calculateDeliveryFee() returns so
 *               the two can be rendered by the same code.
 */
function calculateBulkDeliveryFee(PDO $dbh, float $goodsValue, float $weightKg,
                                     ?array $distance, bool $pickupOnly = false): array {
    $s = BulkDeliverySettings($dbh);
    $pricing = getDeliveryPricingConfig();

    $serviceFee        = (float)($pricing['service_fee'] ?? 1000);
    $insurancePercent  = (float)($pricing['insurance_percent'] ?? 0.9);
    // From delivery_pricing, like the service fee and insurance rate beside
    // it. PROCESSING_FEE_PERCENT was never defined anywhere, so this always
    // evaluated to the 1.8 literal on both channels.
    $processingPercent = (float)($pricing['processing_percent']
        ?? (defined('PROCESSING_FEE_PERCENT') ? PROCESSING_FEE_PERCENT : 1.8));

    if ($pickupOnly) {
        // The customer collects. Nothing is carried, nothing is insured in
        // transit, and no rider is paid — so there is nothing to charge.
        return [
            'base_fee'       => 0,
            'weight_fee'     => 0,
            'distance_fee'   => 0,
            'service_fee'    => 0,
            'insurance_fee'  => 0,
            'processing_fee' => 0,
            'total_fee'      => 0,
            'is_free'        => true,
            'weight_kg'      => round($weightKg, 2),
            'distance'       => null,
            'distance_estimated' => false,
            'reason'         => 'Collection only — you pick this up from the vendor.',
        ];
    }

    $insuranceFee  = $goodsValue * ($insurancePercent / 100);
    $processingFee = $goodsValue * ($processingPercent / 100);

    $baseFee    = $s['base_fee'];
    $weightFee  = $weightKg * $s['fee_per_kg'];
    $distanceKm = $distance['km'] ?? null;

    // The base fee INCLUDES the first base_included_km kilometres; only the
    // excess is charged per km.
    //
    // Before this, base_fee and the full distance were both charged, so the
    // short leg of every journey was paid for twice — once inside the flat fee
    // and again per kilometre. A 2 km delivery came to 5,000 + 1,800, which
    // describes nothing real. Setting base_included_km to 0 restores the old
    // behaviour.
    $includedKm   = (float)$s['base_included_km'];
    $chargeableKm = $distanceKm !== null ? max(0.0, $distanceKm - $includedKm) : 0.0;
    $distanceFee  = $chargeableKm * $s['rate_per_km'];

    $isFree = false;
    if ($goodsValue >= $s['free_delivery_threshold']) {
        // Only the CARRIAGE is waived — what it costs to move the load. The
        // service, insurance and processing fees are platform costs that do
        // not disappear because an order is large, so they are still charged.
        // Mirrors how the shop's Rule 1 behaves.
        $baseFee = $weightFee = $distanceFee = 0.0;
        $isFree = true;
        $reason = 'Carriage is free above UGX ' . number_format($s['free_delivery_threshold'], 0)
                . '; service, insurance and processing still apply.';
    } elseif ($distanceKm === null) {
        $reason = 'Distance not included — no delivery location was pinned.';
    } elseif (!empty($distance['estimated'])) {
        $reason = 'Distance measured from our depot: this vendor has not pinned their premises yet.';
    } else {
        // Says what was actually charged for distance, which is no longer the
        // same as the distance travelled.
        $reason = number_format($distanceKm, 1) . ' km by road from the vendor. ';
        if ($includedKm > 0) {
            $reason .= 'The first ' . tidyBulkKm($includedKm) . ' km '
                     . ($chargeableKm > 0 ? 'are included in the base fee; ' : 'are included in the base fee, so there is no distance charge. ');
            if ($chargeableKm > 0) {
                $reason .= tidyBulkKm($chargeableKm) . ' km beyond that at UGX '
                         . number_format($s['rate_per_km'], 0) . '/km. ';
            }
        } else {
            $reason .= 'Charged at UGX ' . number_format($s['rate_per_km'], 0) . '/km. ';
        }
        $reason .= 'Plus UGX ' . number_format($s['fee_per_kg'], 0) . '/kg on '
                 . round($weightKg) . ' kg.';

        // Said plainly when the figure did not come from Google. A straight-line
        // fallback under-charges, and a customer should not later be told the
        // price was wrong because a third party was down.
        if (($distance['source'] ?? 'google') === 'haversine') {
            $reason .= ' Distance is approximate — routing was unavailable.';
        }
    }

    $totalFee = $baseFee + $weightFee + $distanceFee + $serviceFee + $insuranceFee + $processingFee;

    // Capped so a pathological combination — a tonne driven across the country
    // — cannot produce a fee larger than the goods. The cap applies to the
    // whole thing, and the itemised parts below are what was charged before it,
    // so a capped quote is visibly capped rather than silently rescaled.
    $capped = false;
    if ($totalFee > $s['max_fee']) {
        $totalFee = $s['max_fee'];
        $capped = true;
        $reason .= ' Capped at UGX ' . number_format($s['max_fee'], 0) . '.';
    }

    return [
        'base_fee'       => round($baseFee),
        'weight_fee'     => round($weightFee),
        'distance_fee'   => round($distanceFee),
        'service_fee'    => round($serviceFee),
        'insurance_fee'  => round($insuranceFee),
        'processing_fee' => round($processingFee),
        'total_fee'      => round($totalFee),
        'is_free'        => $isFree,
        'is_capped'      => $capped,
        'weight_kg'      => round($weightKg, 2),
        'distance'       => $distanceKm !== null ? round($distanceKm, 2) : null,
        // What the base fee covered, and what was actually billed per km, so a
        // customer reading "8 km" next to a 4,500 distance charge can see why
        // it is not 8 x the rate.
        'included_km'    => round($includedKm, 3),
        'chargeable_km'  => round($chargeableKm, 3),
        'distance_estimated' => (bool)($distance['estimated'] ?? false),
        // 'google' | 'osrm' | 'haversine'. Kept in the stored breakdown so a
        // fee queried months later can be explained — including the fact that
        // it was computed from a fallback.
        'distance_source'    => $distance['source'] ?? null,
        // Driving time, when the router gave one. Shown to the customer as a
        // rough ETA; zero means unknown rather than instant.
        'duration_minutes'   => isset($distance['minutes']) ? round($distance['minutes']) : null,
        'insurance_percent'  => $insurancePercent,
        'processing_percent' => $processingPercent,
        'reason'         => $reason,
    ];
}