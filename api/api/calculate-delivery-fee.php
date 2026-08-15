<?php
/**
 * api/calculate-delivery-fee.php
 * API endpoint for calculating delivery fee
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
require_once dirname(__DIR__) . '/includes/delivery-fee.php';
require_once dirname(__DIR__) . '/includes/rate_limit.php';

// Public and unauthenticated by design (checkout needs a quote before
// login), but that also means unlimited requests can hit Google Routes/
// Nominatim through this. Loose limit — a customer legitimately re-quotes
// on every map drag.
if (rateLimited($dbh, 'quote:ip:' . ($_SERVER['REMOTE_ADDR'] ?? 'unknown'), 30, 60)) {
    failRateLimited();
}

// Get input
$input = json_decode(file_get_contents('php://input'), true);

if (!$input) {
    echo json_encode([
        'success' => false,
        'error' => 'Invalid input'
    ]);
    exit;
}

$address = $input['address'] ?? '';
$area = $input['area'] ?? '';
$orderValue = floatval($input['order_value'] ?? 0);
$userLat = isset($input['lat']) ? floatval($input['lat']) : null;
$userLng = isset($input['lng']) ? floatval($input['lng']) : null;

if ($orderValue <= 0) {
    echo json_encode([
        'success' => false,
        'error' => 'Invalid order value'
    ]);
    exit;
}

// Reject coordinates that cannot be in Uganda before pricing them.
//
// Asked to quote 51.70892, -88.90354 (Ontario, Canada) this returned
// success with distance 12,076 km and a fee of 8,455,796 UGX — the per-km
// rate applied to an intercontinental distance. The tiering rules have no
// upper bound because they were never meant to see input like this, so the
// guard belongs here at the edge.
//
// null means the caller sent no point and is pricing by area name instead,
// which is a supported path; only supplied coordinates are checked.
if ($userLat !== null && $userLng !== null && ($userLat != 0.0 || $userLng != 0.0)) {
    if ($userLat < -1.5 || $userLat > 4.3 || $userLng < 29.5 || $userLng > 35.1) {
        error_log("Rejected out-of-country quote: lat=$userLat lng=$userLng address=$address");
        echo json_encode([
            'success' => false,
            'error' => 'That delivery location is outside our service area.'
        ]);
        exit;
    }
}

// Calculate delivery fee
$result = calculateDeliveryFeeFromAddress($address, $area, $orderValue, $userLat, $userLng);

echo json_encode($result);
?>