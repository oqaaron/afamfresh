<?php
/**
 * API Endpoint: Reverse Geocoding v2
 * Returns detailed address components including area detection
 */

header('Content-Type: application/json');
header('Access-Control-Allow-Origin: *');
header('Access-Control-Allow-Methods: POST, GET, OPTIONS');
header('Cache-Control: no-cache');

if ($_SERVER['REQUEST_METHOD'] === 'OPTIONS') {
    http_response_code(200);
    exit();
}

require_once dirname(__DIR__) . '/includes/nominatim.php';

// Get input
$input = json_decode(file_get_contents('php://input'), true);
$lat = isset($input['lat']) ? floatval($input['lat']) : 0;
$lng = isset($input['lng']) ? floatval($input['lng']) : 0;

if ($lat == 0 || $lng == 0) {
    echo json_encode(['success' => false, 'error' => 'Valid coordinates required']);
    exit;
}

// Get detailed address
$details = reverseGeocodeDetailed($lat, $lng);

if (!$details) {
    // Fallback to simple reverse geocode
    $address = reverseGeocode($lat, $lng);
    echo json_encode([
        'success' => true,
        'address' => $address ?: '',
        'area' => null,
        'city' => null,
        'locality' => null,
        'lat' => $lat,
        'lng' => $lng
    ]);
    exit;
}

// Determine the best area name (prefer locality over city)
$area = $details['locality'] ?: $details['city'] ?: $details['district'];

echo json_encode([
    'success' => true,
    'address' => $details['address'],
    'area' => $area,
    'city' => $details['city'],
    'locality' => $details['locality'],
    'district' => $details['district'],
    'country' => $details['country'],
    'lat' => $lat,
    'lng' => $lng
]);
?>