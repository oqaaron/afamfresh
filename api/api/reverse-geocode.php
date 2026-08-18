<?php
/**
 * api/reverse-geocode.php
 * API endpoint for reverse geocoding
 */

header('Content-Type: application/json');
header('Access-Control-Allow-Origin: *');
header('Access-Control-Allow-Methods: POST, GET, OPTIONS');
header('Access-Control-Allow-Headers: Content-Type');

if ($_SERVER['REQUEST_METHOD'] === 'OPTIONS') {
    http_response_code(200);
    exit();
}

require_once dirname(__DIR__) . '/admin/includes/config.php';
require_once dirname(__DIR__) . '/includes/nominatim.php';
require_once dirname(__DIR__) . '/includes/rate_limit.php';

// No login is required to reach this, and every call spends a request against
// the upstream geocoding provider's quota. Unthrottled, that makes the app a
// free geocoding proxy for anyone who finds the URL, payable by us. Same
// bucket shape and window as calculate-delivery-fee.php, which throttles the
// same class of unauthenticated, externally-proxied lookup.
if (rateLimited($dbh, 'geocode:ip:' . ($_SERVER['REMOTE_ADDR'] ?? 'unknown'), 30, 60)) {
    failRateLimited();
}

$input = json_decode(file_get_contents('php://input'), true);

if (!$input) {
    echo json_encode([
        'success' => false,
        'error' => 'Invalid input'
    ]);
    exit;
}

$lat = isset($input['lat']) ? floatval($input['lat']) : 0;
$lng = isset($input['lng']) ? floatval($input['lng']) : 0;

// Range-checked, not just non-zero. Coordinates outside these bounds are not
// points on Earth, so they can only waste an upstream call.
if ($lat == 0 || $lng == 0 || $lat < -90 || $lat > 90 || $lng < -180 || $lng > 180) {
    echo json_encode([
        'success' => false,
        'error' => 'Valid coordinates required',
        'lat' => $lat,
        'lng' => $lng
    ]);
    exit;
}

// Get detailed address
$details = reverseGeocodeDetailed($lat, $lng);

if (!$details || empty($details['address'])) {
    $address = reverseGeocode($lat, $lng);
    echo json_encode([
        'success' => true,
        'address' => $address ?: '',
        'area' => detectPreciseArea($lat, $lng),
        'lat' => $lat,
        'lng' => $lng
    ]);
    exit;
}

$area = $details['locality'] ?: $details['city'] ?: detectPreciseArea($lat, $lng);

echo json_encode([
    'success' => true,
    'address' => $details['address'],
    'area' => $area,
    'locality' => $details['locality'],
    'city' => $details['city'],
    'lat' => $lat,
    'lng' => $lng
]);
?>