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
//   base            flat, per delivery                 Bulk_delivery_settings
//   weight          per kg of the load                 Bulk_delivery_settings
//   distance        per km, vendor -> customer         Bulk_delivery_settings
//   service         flat, platform handling            delivery_pricing
//   insurance       % of goods value                   delivery_pricing
//   processing      % of goods value                   PROCESSING_FEE_PERCENT
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
const Bulk_FEE_DEFAULTS = [
    'base_fee'                => 5000.0,
    'fee_per_kg'              => 500.0,
    'free_delivery_threshold' => 500000.0,
    'max_weight_kg'           => 1000.0,
    'min_order_value'         => 250000.0,
    'min_weight_kg'           => 20.0,
];

/**
 * Per-km rate for Bulk, and the cap.
 *
 * Not in Bulk_delivery_settings, which predates distance pricing. Defined
 * here rather than added to the table so this ships without a second migration;
 * move them into the table when an admin needs to tune them without a deploy.
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
    $processingPercent = defined('PROCESSING_FEE_PERCENT') ? PROCESSING_FEE_PERCENT : 1.8;

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

    // The carriage half: what it costs to actually move the load. This is the
    // part the free-delivery threshold waives; the service, insurance and
    // processing fees are platform costs that do not disappear because an order
    // is large, which mirrors how the shop's Rule 1 behaves.
    $baseFee     = $s['base_fee'];
    $weightFee   = $weightKg * $s['fee_per_kg'];
    $distanceKm  = $distance['km'] ?? null;
    $distanceFee = $distanceKm !== null ? $distanceKm * Bulk_RATE_PER_KM : 0.0;

    $isFree = false;
    if ($goodsValue >= $s['free_delivery_threshold']) {
        $baseFee = $weightFee = $distanceFee = 0.0;
        $isFree = true;
        $reason = 'Carriage is free above UGX ' . number_format($s['free_delivery_threshold'], 0)
                . '; service, insurance and processing still apply.';
    } elseif ($distanceKm === null) {
        $reason = 'Distance not included — no delivery location was pinned.';
    } elseif (!empty($distance['estimated'])) {
        $reason = 'Distance measured from our depot: this vendor has not pinned their premises yet.';
    } else {
        $reason = number_format($distanceKm, 1) . ' km by road from the vendor at UGX '
                . number_format(Bulk_RATE_PER_KM, 0) . '/km, plus UGX '
                . number_format($s['fee_per_kg'], 0) . '/kg on ' . round($weightKg) . ' kg.';

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
    if ($totalFee > Bulk_MAX_FEE) {
        $totalFee = Bulk_MAX_FEE;
        $capped = true;
        $reason .= ' Capped at UGX ' . number_format(Bulk_MAX_FEE, 0) . '.';
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