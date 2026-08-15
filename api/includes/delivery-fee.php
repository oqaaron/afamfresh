<?php
/**
 * Delivery Fee Calculation Functions
 * Calculates distance-based delivery fees with tiered pricing rules
 * Returns breakdown of components: distance_fee, service_fee, insurance_fee, processing_fee, total_fee
 * 
 * UPDATED: No cURL required - uses file_get_contents with stream context
 */

require_once __DIR__ . '/../admin/includes/config.php';
require_once __DIR__ . '/nominatim.php';

/**
 * Make HTTP GET request without cURL (uses file_get_contents)
 * 
 * @param string $url The URL to request
 * @return string|false Response body or false on failure
 */
function httpGetNoCurl($url) {
    $options = [
        'http' => [
            'method' => 'GET',
            'header' => "User-Agent: AfamFresh/1.0 (https://afam.techaus.online)\r\n",
            'timeout' => 10
        ],
        'ssl' => [
            'verify_peer' => false,
            'verify_peer_name' => false
        ]
    ];
    $context = stream_context_create($options);
    return @file_get_contents($url, false, $context);
}

/**
 * Calculate distance between two coordinates using Haversine formula
 * @param float $lat1 Latitude of point 1
 * @param float $lng1 Longitude of point 1
 * @param float $lat2 Latitude of point 2
 * @param float $lng2 Longitude of point 2
 * @return float Distance in kilometers
 */
function calculateDistance($lat1, $lng1, $lat2, $lng2) {
    $earthRadius = 6371; // Earth's radius in kilometers
    
    $latDiff = deg2rad($lat2 - $lat1);
    $lngDiff = deg2rad($lng2 - $lng1);
    
    $a = sin($latDiff / 2) * sin($latDiff / 2) +
         cos(deg2rad($lat1)) * cos(deg2rad($lat2)) *
         sin($lngDiff / 2) * sin($lngDiff / 2);
    
    $c = 2 * atan2(sqrt($a), sqrt(1 - $a));
    
    return $earthRadius * $c;
}

/**
 * Get road distance using OSRM routing API (without cURL)
 * @param float $lat1 Origin latitude
 * @param float $lng1 Origin longitude
 * @param float $lat2 Destination latitude
 * @param float $lng2 Destination longitude
 * @return float|null Distance in km or null if API fails
 */
function getRoadDistance($lat1, $lng1, $lat2, $lng2) {
    $url = "http://router.project-osrm.org/route/v1/driving/{$lng1},{$lat1};{$lng2},{$lat2}?overview=false";
    
    $response = httpGetNoCurl($url);
    
    if ($response === false) {
        error_log("OSRM API error: No response");
        return null;
    }
    
    $data = json_decode($response, true);
    
    if (isset($data['routes'][0]['distance'])) {
        return $data['routes'][0]['distance'] / 1000; // Convert to km
    }
    
    error_log("OSRM API error: Invalid response");
    return null;
}

/**
 * Reads the admin-configured pricing row, cached for the life of the request.
 *
 * `delivery_pricing` holds one row of live settings (service fee, per-km
 * rates, thresholds) that an admin can edit, but until now nothing in the
 * app ever queried it — calculateDeliveryFee() used its own hardcoded
 * fallbacks unconditionally, so editing this table had zero effect on what
 * customers were actually charged. Returns [] if the table is empty or the
 * query fails, so callers fall back to the previous hardcoded defaults
 * rather than pricing every delivery at 0.
 *
 * @return array<string, mixed>
 */
function getDeliveryPricingConfig() {
    static $cached = null;
    if ($cached !== null) {
        return $cached;
    }

    global $dbh;
    try {
        $row = $dbh->query(
            "SELECT service_fee, insurance_percent, free_delivery_threshold,
                    free_delivery_distance_threshold, medium_order_threshold,
                    medium_order_rate, low_order_rate, profit_percent_enabled,
                    profit_percent
             FROM delivery_pricing ORDER BY id LIMIT 1"
        )->fetch(PDO::FETCH_ASSOC);
    } catch (PDOException $e) {
        error_log('getDeliveryPricingConfig failed: ' . $e->getMessage());
        $row = false;
    }

    if (!$row) {
        $cached = [];
        return $cached;
    }

    $cached = [
        'service_fee'                      => (float)$row['service_fee'],
        'insurance_percent'                => (float)$row['insurance_percent'],
        'free_delivery_threshold'          => (float)$row['free_delivery_threshold'],
        'free_delivery_distance_threshold' => (float)$row['free_delivery_distance_threshold'],
        'medium_order_threshold'           => (float)$row['medium_order_threshold'],
        'medium_order_rate'                => (float)$row['medium_order_rate'],
        'low_order_rate'                   => (float)$row['low_order_rate'],
        'profit_percent_enabled'           => (bool)$row['profit_percent_enabled'],
        'profit_percent'                   => (float)$row['profit_percent'],
    ];
    return $cached;
}

/**
 * Calculate delivery fee based on order value and distance
 * Returns individual components: distance_fee, service_fee, insurance_fee, processing_fee, total_fee
 * @param float $orderValue Order subtotal in UGX
 * @param float $distance Distance from office in km
 * @return array Delivery fee details with breakdown
 */
function calculateDeliveryFee($orderValue, $distance) {
    // Pricing comes from the `delivery_pricing` table, which an admin can
    // already edit through some settings page — it just had nothing reading
    // it. Every quote silently used the hardcoded fallbacks below instead, so
    // changing the service fee or per-km rate in the database had zero effect
    // on what customers were actually charged. Falls back to the previous
    // defaults only if the table is somehow empty.
    $pricing = getDeliveryPricingConfig();

    $serviceFee = $pricing['service_fee'] ?? (defined('SERVICE_FEE') ? SERVICE_FEE : 1000);
    $insurancePercent = $pricing['insurance_percent'] ?? (defined('INSURANCE_PERCENT') ? INSURANCE_PERCENT : 0.9);
    $processingPercent = defined('PROCESSING_FEE_PERCENT') ? PROCESSING_FEE_PERCENT : 1.8;
    $freeThreshold = $pricing['free_delivery_threshold'] ?? (defined('FREE_DELIVERY_THRESHOLD') ? FREE_DELIVERY_THRESHOLD : 100000);
    $partialThreshold = $pricing['medium_order_threshold'] ?? (defined('PARTIAL_FREE_THRESHOLD') ? PARTIAL_FREE_THRESHOLD : 50000);
    $partialDistanceLimit = $pricing['free_delivery_distance_threshold'] ?? (defined('PARTIAL_FREE_DISTANCE_LIMIT') ? PARTIAL_FREE_DISTANCE_LIMIT : 10);
    $shortRate = $pricing['low_order_rate'] ?? (defined('SHORT_DISTANCE_RATE') ? SHORT_DISTANCE_RATE : 700);
    $longRate = $pricing['medium_order_rate'] ?? (defined('LONG_DISTANCE_RATE') ? LONG_DISTANCE_RATE : 375);
    $profitEnabled = $pricing['profit_percent_enabled'] ?? (defined('PROFIT_PERCENT_ENABLED') ? PROFIT_PERCENT_ENABLED : false);
    $profitPercent = $pricing['profit_percent'] ?? (defined('PROFIT_PERCENT') ? PROFIT_PERCENT : 8);
    $minFee = defined('MIN_DELIVERY_FEE') ? MIN_DELIVERY_FEE : 0;
    
    $insuranceCharge = $orderValue * ($insurancePercent / 100);
    $processingFee = $orderValue * ($processingPercent / 100);
    $distanceFee = 0;
    $profitMargin = 0;
    $totalFee = 0;
    $isFree = false;
    $reason = '';
    
    // Rule 1: Orders above free threshold
    if ($orderValue > $freeThreshold) {
        $distanceFee = 0;
        $totalFee = $serviceFee + $insuranceCharge + $processingFee;
        $reason = 'Free delivery for orders above ' . number_format($freeThreshold) . ' UGX';
    }
    // Rule 2: Orders above partial free threshold
    elseif ($orderValue > $partialThreshold) {
        if ($distance <= $partialDistanceLimit) {
            $distanceFee = 0;
            $totalFee = $serviceFee + $insuranceCharge + $processingFee;
            $reason = 'Free delivery for orders above ' . number_format($partialThreshold) . ' UGX within ' . $partialDistanceLimit . 'km';
        } else {
            $distanceFee = $distance * $longRate;
            $totalFee = $distanceFee + $serviceFee + $insuranceCharge + $processingFee;
            $reason = 'Long distance rate: ' . number_format($longRate) . ' UGX/km';
        }
    }
    // Rule 3: Orders below partial threshold
    else {
        $distanceFee = $distance * $shortRate;
        if ($profitEnabled) {
            $profitMargin = $orderValue * ($profitPercent / 100);
        }
        $totalFee = $distanceFee + $profitMargin + $serviceFee + $insuranceCharge + $processingFee;
        
        if ($totalFee < $minFee) {
            $totalFee = $minFee;
            $isFree = true;
            $reason = 'Free delivery (minimum fee applied)';
        } else {
            $reason = 'Standard rate: ' . number_format($shortRate) . ' UGX/km, plus ' . number_format($serviceFee) . ' UGX service fee, plus ' . $insurancePercent . '% insurance';
        }
    }
    
    // Round all fees
    $breakdown = [
        'distance_fee' => round($distanceFee),
        'service_fee' => round($serviceFee),
        'insurance_fee' => round($insuranceCharge),
        'processing_fee' => round($processingFee),
        'profit_margin' => round($profitMargin),
        'total_fee' => round($totalFee),
        'is_free' => $isFree,
        'distance' => round($distance, 2),
        'reason' => $reason
    ];
    
    return $breakdown;
}

/**
 * Get cached geocode result from database
 * @param string $address Full address to look up
 * @return array|null ['lat' => float, 'lng' => float] or null if not cached or stale
 */
function getCachedGeocode($address) {
    global $dbh;
    
    try {
        $stmt = $dbh->prepare("SELECT lat, lng FROM geocode_cache WHERE address = ? AND updated_at > DATE_SUB(NOW(), INTERVAL 30 DAY)");
        $stmt->execute([$address]);
        $result = $stmt->fetch(PDO::FETCH_ASSOC);
        
        if ($result) {
            return [
                'lat' => (float)$result['lat'],
                'lng' => (float)$result['lng']
            ];
        }
    } catch (PDOException $e) {
        error_log("Geocode cache query error: " . $e->getMessage());
    }
    
    return null;
}

/**
 * Save geocode result to cache
 * @param string $address Full address
 * @param float $lat Latitude
 * @param float $lng Longitude
 * @return bool Success status
 */
function saveGeocodeCache($address, $lat, $lng) {
    global $dbh;
    
    try {
        $stmt = $dbh->prepare("INSERT INTO geocode_cache (address, lat, lng) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE lat = VALUES(lat), lng = VALUES(lng), updated_at = NOW()");
        return $stmt->execute([$address, $lat, $lng]);
    } catch (PDOException $e) {
        error_log("Geocode cache save error: " . $e->getMessage());
        return false;
    }
}

/**
 * Calculate delivery fee from address (main entry point for checkout)
 * @param string $address Delivery address
 * @param string $area Delivery area
 * @param float $orderValue Order subtotal in UGX
 * @param float|null $userLat User-confirmed latitude (optional)
 * @param float|null $userLng User-confirmed longitude (optional)
 * @return array Delivery fee details including breakdown
 */
function calculateDeliveryFeeFromAddress($address, $area, $orderValue, $userLat = null, $userLng = null) {
    $destLat = null;
    $destLng = null;
    $fullAddress = !empty($address) ? $address : '';
    if (!empty($area)) {
        $fullAddress .= !empty($address) ? ", $area, Uganda" : "$area, Uganda";
    } elseif (!empty($address)) {
        $fullAddress .= ", Uganda";
    }
    $useRoadDistance = false;
    
    // Step 0: If user provided confirmed coordinates, use them directly
    if ($userLat !== null && $userLng !== null) {
        $destLat = $userLat;
        $destLng = $userLng;
        $useRoadDistance = true;
        error_log("Using user-confirmed coordinates: $userLat, $userLng");
    } elseif (!empty($address)) {
        // Step 1: Check database cache first (30-day validity)
        $cached = getCachedGeocode($fullAddress);
        if ($cached) {
            $destLat = $cached['lat'];
            $destLng = $cached['lng'];
            error_log("Using cached coordinates for: $fullAddress");
        }
        
        // Step 2: Not cached or stale - try live geocoding
        if (!$destLat || !$destLng) {
            $geoResult = forwardGeocode($fullAddress);
            
            if ($geoResult && isset($geoResult['lat']) && isset($geoResult['lng'])) {
                $destLat = $geoResult['lat'];
                $destLng = $geoResult['lng'];
                saveGeocodeCache($fullAddress, $destLat, $destLng);
                error_log("Geocoded successfully and cached: $fullAddress");
            } else {
                error_log("Geocoding failed for: $fullAddress");
                return [
                    'success' => false,
                    'error' => 'Unable to determine delivery location. Please provide a more specific address or enable location services.',
                    'error_code' => 'GEOCODE_FAILED'
                ];
            }
        }
    } else {
        return [
            'success' => false,
            'error' => 'Please provide either an address or enable GPS location services.',
            'error_code' => 'NO_LOCATION_DATA'
        ];
    }
    
    // Calculate distance from office
    $officeLat = defined('OFFICE_LAT') ? OFFICE_LAT : 0.3136;
    $officeLng = defined('OFFICE_LNG') ? OFFICE_LNG : 32.5811;
    $distance = null;
    
    if ($useRoadDistance) {
        // Google Routes first, then OSRM, then straight-line — see
        // includes/google_routes.php. Was OSRM-then-Haversine, which meant a
        // public unauthenticated router with no SLA decided what customers were
        // charged, and silently fell back to a straight line that under-charges
        // by 20-40% whenever it was unavailable.
        require_once __DIR__ . '/google_routes.php';
        $route = roadDistanceBetween($officeLat, $officeLng, $destLat, $destLng);
        $distance = $route['km'];
        if ($route['source'] !== 'google') {
            error_log("Delivery distance from {$route['source']} fallback: {$distance} km");
        }
    } else {
        $distance = calculateDistance($officeLat, $officeLng, $destLat, $destLng);
    }
    
    // Calculate fee breakdown
    $feeDetails = calculateDeliveryFee($orderValue, $distance);
    
    return [
        'success' => true,
        'distance' => $feeDetails['distance'],
        'fee' => $feeDetails['total_fee'],
        'is_free' => $feeDetails['is_free'],
        'reason' => $feeDetails['reason'],
        'dest_lat' => $destLat,
        'dest_lng' => $destLng,
        'breakdown' => [
            'distance_fee' => $feeDetails['distance_fee'],
            'service_fee' => $feeDetails['service_fee'],
            'insurance_fee' => $feeDetails['insurance_fee'],
            'processing_fee' => $feeDetails['processing_fee'],
            // Present in calculateDeliveryFee()'s own breakdown but dropped
            // here until now — the profit margin charged on small orders
            // (Rule 3) was folded into `fee` with nowhere for a caller to
            // see it separately. 0 for any order not under that rule.
            'profit_margin' => $feeDetails['profit_margin']
        ]
    ];
}
?>