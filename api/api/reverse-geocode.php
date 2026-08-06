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

require_once dirname(__DIR__) . '/includes/nominatim.php';

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

if ($lat == 0 || $lng == 0) {
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