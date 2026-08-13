<?php
// =============================================================
// includes/google_routes.php
//
// Real road distance and duration, from Google Routes API.
//
// WHY THIS EXISTS
//
// Two things were wrong with how distance was measured.
//
// calculateDistance() is Haversine — the straight line between two points.
// Roads are not straight. On a typical Kampala journey it understates the real
// driving distance by 20-40%, and the surplus delivery fee charges per km, so
// every bulk order was being under-charged by roughly that much.
//
// getRoadDistance() does call a real router, but it is public OSRM over plain
// HTTP with no key, no SLA and no support. Fine as a fallback, wrong as the
// thing a price depends on.
//
// WHY THE KEY LIVES ON THE SERVER
//
// GOOGLE_MAPS_API_KEY is read from the Render environment and never leaves it.
// A key shipped inside an APK can be extracted from the APK in about a minute,
// and a routing key is billable per request — so an extracted one is somebody
// else spending your money. The app's separate Maps SDK key is unavoidable
// (rendering happens on the device) but can be restricted to your package names
// and signing certificates, which a routing key cannot.
//
// FAILURE IS NOT FATAL
//
// Every function here returns null rather than throwing. A delivery fee must
// still be quotable when Google is unreachable, so callers fall back to OSRM
// and then to Haversine, marking the result estimated. Refusing to price an
// order because a third party is down would be the worse failure.
// =============================================================

require_once __DIR__ . '/delivery-fee.php';   // calculateDistance(), getRoadDistance()

/**
 * The Routes API endpoint. v2 `computeRoutes`, which replaced the older
 * Directions API — Directions is on a deprecation path and new projects are
 * not always granted it.
 */
const GOOGLE_ROUTES_URL = 'https://routes.googleapis.com/directions/v2:computeRoutes';

/** How long to wait on Google before giving up and falling back. */
const GOOGLE_ROUTES_TIMEOUT = 6;

/**
 * Road distance and driving time between two points.
 *
 * @return array{km: float, minutes: float, source: string}|null
 *         null when the key is unset, the call fails, or no route exists —
 *         which is a real answer for two points separated by water.
 */
function googleRouteBetween(float $originLat, float $originLng,
                            float $destLat, float $destLng,
                            bool $withPolyline = false): ?array {
    $key = trim((string)env('GOOGLE_MAPS_API_KEY', ''));
    if ($key === '') {
        // Logged once per call rather than silently degrading: a missing key
        // means every quote is falling back, and that should be visible.
        error_log('Routes API: GOOGLE_MAPS_API_KEY is not set; falling back to OSRM.');
        return null;
    }

    $body = json_encode([
        'origin' => ['location' => ['latLng' => [
            'latitude' => $originLat, 'longitude' => $originLng,
        ]]],
        'destination' => ['location' => ['latLng' => [
            'latitude' => $destLat, 'longitude' => $destLng,
        ]]],
        'travelMode' => 'DRIVE',
        // TRAFFIC_UNAWARE is deliberate. Traffic-aware routing costs more per
        // request and returns a duration that changes minute to minute — which
        // would mean the same order quoted twice gives two different prices.
        // A fee has to be reproducible.
        'routingPreference' => 'TRAFFIC_UNAWARE',
        'units' => 'METRIC',
    ]);

    // A field mask is REQUIRED by this API — a request without one is rejected.
    // Asking only for what is used also keeps the response in the cheapest
    // billing tier.
    //
    // Geometry is opt-in and OFF by default, so the fee-quoting path — which
    // runs on every checkout keystroke — keeps asking for the two small fields
    // it needs. encodedPolyline is in the same Essentials SKU, so requesting it
    // where it IS wanted does not move the call into a dearer tier.
    $mask = 'routes.distanceMeters,routes.duration'
          . ($withPolyline ? ',routes.polyline.encodedPolyline' : '');
    $context = stream_context_create([
        'http' => [
            'method'  => 'POST',
            'header'  => "Content-Type: application/json\r\n"
                       . "X-Goog-Api-Key: {$key}\r\n"
                       . "X-Goog-FieldMask: {$mask}\r\n",
            'content' => $body,
            'timeout' => GOOGLE_ROUTES_TIMEOUT,
            // So a 4xx body can be read and logged rather than becoming a bare
            // "request failed" with no explanation of why.
            'ignore_errors' => true,
        ],
    ]);

    $response = @file_get_contents(GOOGLE_ROUTES_URL, false, $context);
    if ($response === false) {
        error_log('Routes API: no response');
        return null;
    }

    $data = json_decode($response, true);

    if (isset($data['error'])) {
        // The message names the cause — a restricted key, an unenabled API, a
        // billing problem — and every one of those is a configuration issue
        // someone has to fix, not a transient failure.
        error_log('Routes API error: ' . ($data['error']['message'] ?? 'unknown'));
        return null;
    }

    $route = $data['routes'][0] ?? null;
    if (!$route || !isset($route['distanceMeters'])) {
        error_log('Routes API: no route between those points');
        return null;
    }

    // duration arrives as a string like "1234s".
    $seconds = isset($route['duration'])
        ? (float)rtrim((string)$route['duration'], 's')
        : 0.0;

    return [
        'km'       => $route['distanceMeters'] / 1000,
        'minutes'  => $seconds / 60,
        'source'   => 'google',
        // Null when geometry was not asked for. Callers that need a line to
        // draw check for null rather than assuming one is always present.
        'polyline' => $route['polyline']['encodedPolyline'] ?? null,
    ];
}

/**
 * Road distance, however we can get it.
 *
 * Tries Google, then OSRM, then straight-line. The `source` says which answered
 * and `estimated` is true for anything but Google, so a fee computed from a
 * fallback is never presented as exact.
 *
 * Cached per request: a quote and the order that follows measure the same
 * journey, and paying for two identical Google calls in one request is waste.
 *
 * @return array{km: float, minutes: float, source: string, estimated: bool}
 */
function roadDistanceBetween(float $originLat, float $originLng,
                             float $destLat, float $destLng,
                             bool $withPolyline = false): array {
    static $cache = [];
    // The flag is PART OF THE KEY. Without it, a geometry-free result cached by
    // the fee quote would be handed to the tracking code, which would then draw
    // no route and look broken for reasons nothing in the logs would explain.
    $cacheKey = implode(',', [
        round($originLat, 5), round($originLng, 5),
        round($destLat, 5), round($destLng, 5),
        $withPolyline ? 'geo' : 'nogeo',
    ]);
    if (isset($cache[$cacheKey])) {
        return $cache[$cacheKey];
    }

    $google = googleRouteBetween($originLat, $originLng, $destLat, $destLng, $withPolyline);
    if ($google !== null) {
        return $cache[$cacheKey] = $google + ['estimated' => false];
    }

    $osrmKm = getRoadDistance($originLat, $originLng, $destLat, $destLng);
    if ($osrmKm !== null) {
        return $cache[$cacheKey] = [
            'km' => (float)$osrmKm,
            // OSRM was asked for distance only (overview=false), so there is no
            // duration to report. Zero, not a guess.
            'minutes' => 0.0,
            'source' => 'osrm',
            'estimated' => true,
            // No geometry either. The client draws a dashed straight line
            // labelled approximate rather than being handed a different
            // router's route presented as the real one.
            'polyline' => null,
        ];
    }

    // Last resort. Real roads are always longer than the straight line, so this
    // under-charges — but a quotable order at a slightly low price beats no
    // quote at all, and `estimated` says the figure is soft.
    return $cache[$cacheKey] = [
        'km' => calculateDistance($originLat, $originLng, $destLat, $destLng),
        'minutes' => 0.0,
        'source' => 'haversine',
        'estimated' => true,
        'polyline' => null,
    ];
}