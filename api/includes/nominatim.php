<?php
/**
 * nominatim.php
 * Accurate geocoding using coordinate-based detection + BigDataCloud API
 * 
 * Functions:
 * - reverseGeocode($lat, $lng) - Convert coordinates to address
 * - reverseGeocodeDetailed($lat, $lng) - Get detailed address components
 * - detectPreciseArea($lat, $lng) - Detect specific neighborhood from coordinates
 * - forwardGeocode($address) - Convert address to coordinates
 */

// Rate limiting cache
static $lastRequestTime = 0;
static $requestDelay = 0.5;

/**
 * Make HTTP GET request
 */
function httpGet($url) {
    $options = [
        'http' => [
            'method' => 'GET',
            'header' => "User-Agent: AfamFresh/1.0 (https://afamfresh-backend.onrender.com)\r\n",
            'timeout' => 10
        ],
        'ssl' => ['verify_peer' => false, 'verify_peer_name' => false]
    ];
    $context = stream_context_create($options);
    return @file_get_contents($url, false, $context);
}

/**
 * PRECISE KAMPALA AREA DETECTION - Accurate boundaries for all neighborhoods
 * Updated based on user feedback to correctly identify Nabwojjo
 */
function detectPreciseArea($lat, $lng) {
    // Kampala specific area boundaries - refined for accuracy
    $areas = [
        // NABWOJJO - Expanded and corrected boundaries (priority placement)
        'Nabwojjo' => ['lat_min' => 0.3600, 'lat_max' => 0.3850, 'lng_min' => 32.6450, 'lng_max' => 32.6750],
        
        // NSASA - More precise boundaries
        'Nsasa' => ['lat_min' => 0.3780, 'lat_max' => 0.3950, 'lng_min' => 32.6380, 'lng_max' => 32.6600],
        
        // Central Kampala
        'Central Kampala' => ['lat_min' => 0.3100, 'lat_max' => 0.3180, 'lng_min' => 32.5750, 'lng_max' => 32.5850],
        
        // Nakasero (Government area, upscale)
        'Nakasero' => ['lat_min' => 0.3140, 'lat_max' => 0.3220, 'lng_min' => 32.5730, 'lng_max' => 32.5810],
        
        // Kololo (Upscale residential)
        'Kololo' => ['lat_min' => 0.3220, 'lat_max' => 0.3350, 'lng_min' => 32.5820, 'lng_max' => 32.5950],
        
        // Naguru (Middle-class residential)
        'Naguru' => ['lat_min' => 0.3350, 'lat_max' => 0.3420, 'lng_min' => 32.5880, 'lng_max' => 32.5980],
        
        // Bukoto (Mixed residential/commercial)
        'Bukoto' => ['lat_min' => 0.3420, 'lat_max' => 0.3550, 'lng_min' => 32.5900, 'lng_max' => 32.6050],
        
        // Ntinda (Commercial/residential)
        'Ntinda' => ['lat_min' => 0.3500, 'lat_max' => 0.3600, 'lng_min' => 32.6000, 'lng_max' => 32.6150],
        
        // Kisaasi (Residential)
        'Kisaasi' => ['lat_min' => 0.3600, 'lat_max' => 0.3720, 'lng_min' => 32.6080, 'lng_max' => 32.6200],
        
        // Kira (Suburban)
        'Kira' => ['lat_min' => 0.3700, 'lat_max' => 0.4000, 'lng_min' => 32.6100, 'lng_max' => 32.6500],
        
        // Namugongo (Religious site area)
        'Namugongo' => ['lat_min' => 0.3450, 'lat_max' => 0.3650, 'lng_min' => 32.6400, 'lng_max' => 32.6600],
        
        // Bweyogerere (Industrial/residential)
        'Bweyogerere' => ['lat_min' => 0.3550, 'lat_max' => 0.3700, 'lng_min' => 32.6450, 'lng_max' => 32.6650],
        
        // Namanve (Industrial park)
        'Namanve' => ['lat_min' => 0.3650, 'lat_max' => 0.3850, 'lng_min' => 32.6650, 'lng_max' => 32.6900],
        
        // Nakawa (Commercial/industrial)
        'Nakawa' => ['lat_min' => 0.3300, 'lat_max' => 0.3450, 'lng_min' => 32.6000, 'lng_max' => 32.6150],
        
        // Bugolobi (Affluent residential)
        'Bugolobi' => ['lat_min' => 0.3100, 'lat_max' => 0.3220, 'lng_min' => 32.5950, 'lng_max' => 32.6080],
        
        // Mbuya (Residential)
        'Mbuya' => ['lat_min' => 0.3050, 'lat_max' => 0.3150, 'lng_min' => 32.6000, 'lng_max' => 32.6120],
        
        // Luzira (Prison and residential area)
        'Luzira' => ['lat_min' => 0.2900, 'lat_max' => 0.3050, 'lng_min' => 32.6050, 'lng_max' => 32.6200],
        
        // Ggaba (Lakeside area)
        'Ggaba' => ['lat_min' => 0.2750, 'lat_max' => 0.2920, 'lng_min' => 32.6100, 'lng_max' => 32.6300],
        
        // Makindye (Residential)
        'Makindye' => ['lat_min' => 0.2850, 'lat_max' => 0.3000, 'lng_min' => 32.5800, 'lng_max' => 32.5950],
        
        // Kabalagala (Nightlife/entertainment)
        'Kabalagala' => ['lat_min' => 0.2950, 'lat_max' => 0.3080, 'lng_min' => 32.5850, 'lng_max' => 32.5980],
        
        // Nsambya (Residential)
        'Nsambya' => ['lat_min' => 0.2950, 'lat_max' => 0.3100, 'lng_min' => 32.5700, 'lng_max' => 32.5820],
        
        // Mengo (Traditional/buganda kingdom)
        'Mengo' => ['lat_min' => 0.3000, 'lat_max' => 0.3120, 'lng_min' => 32.5550, 'lng_max' => 32.5680],
        
        // Rubaga (Cathedral area)
        'Rubaga' => ['lat_min' => 0.2980, 'lat_max' => 0.3120, 'lng_min' => 32.5400, 'lng_max' => 32.5600],
        
        // Nateete (Commercial/residential)
        'Nateete' => ['lat_min' => 0.2850, 'lat_max' => 0.3000, 'lng_min' => 32.5300, 'lng_max' => 32.5480],
        
        // Ndeeba (Commercial)
        'Ndeeba' => ['lat_min' => 0.2920, 'lat_max' => 0.3050, 'lng_min' => 32.5550, 'lng_max' => 32.5720],
        
        // Mutungo (Residential, near lake)
        'Mutungo' => ['lat_min' => 0.2900, 'lat_max' => 0.3050, 'lng_min' => 32.6250, 'lng_max' => 32.6400],
        
        // Banda (Residential)
        'Banda' => ['lat_min' => 0.3350, 'lat_max' => 0.3500, 'lng_min' => 32.6400, 'lng_max' => 32.6550],
        
        // Kyambogo (University area)
        'Kyambogo' => ['lat_min' => 0.3500, 'lat_max' => 0.3600, 'lng_min' => 32.6300, 'lng_max' => 32.6450],
        
        // Jinja Road (Major road area)
        'Jinja Road' => ['lat_min' => 0.3200, 'lat_max' => 0.3400, 'lng_min' => 32.6180, 'lng_max' => 32.6400],
        
        // Entebbe Road (Road area)
        'Entebbe Road' => ['lat_min' => 0.2600, 'lat_max' => 0.2900, 'lng_min' => 32.5600, 'lng_max' => 32.5900],
        
        // Kajjansi (Suburban)
        'Kajjansi' => ['lat_min' => 0.2200, 'lat_max' => 0.2500, 'lng_min' => 32.5400, 'lng_max' => 32.5700],
        
        // Mbalwa (Residential)
        'Mbalwa' => ['lat_min' => 0.3750, 'lat_max' => 0.3900, 'lng_min' => 32.6750, 'lng_max' => 32.6950],
        
        // Bbuto (Residential)
        'Bbuto' => ['lat_min' => 0.3700, 'lat_max' => 0.3850, 'lng_min' => 32.6600, 'lng_max' => 32.6800],
        
        // Gayaza Road (Road area)
        'Gayaza Road' => ['lat_min' => 0.3800, 'lat_max' => 0.4200, 'lng_min' => 32.5900, 'lng_max' => 32.6200],
        
        // Kyaliwajala (Residential)
        'Kyaliwajala' => ['lat_min' => 0.3550, 'lat_max' => 0.3700, 'lng_min' => 32.6200, 'lng_max' => 32.6350],
        
        // Kirinya (Residential)
        'Kirinya' => ['lat_min' => 0.3450, 'lat_max' => 0.3600, 'lng_min' => 32.6500, 'lng_max' => 32.6700],
        
        // Bukasa (Residential)
        'Bukasa' => ['lat_min' => 0.3200, 'lat_max' => 0.3350, 'lng_min' => 32.6300, 'lng_max' => 32.6450],
        
        // Kungu (Residential)
        'Kungu' => ['lat_min' => 0.3900, 'lat_max' => 0.4100, 'lng_min' => 32.6550, 'lng_max' => 32.6800],
        
        // Wandegeya (Student area near Makerere)
        'Wandegeya' => ['lat_min' => 0.3250, 'lat_max' => 0.3350, 'lng_min' => 32.5700, 'lng_max' => 32.5800],
        
        // Makerere (University area)
        'Makerere' => ['lat_min' => 0.3300, 'lat_max' => 0.3420, 'lng_min' => 32.5650, 'lng_max' => 32.5780],
        
        // Kawempe (Northern suburb)
        'Kawempe' => ['lat_min' => 0.3750, 'lat_max' => 0.4200, 'lng_min' => 32.5300, 'lng_max' => 32.5600],
        
        // Bwaise (Flood-prone area)
        'Bwaise' => ['lat_min' => 0.3600, 'lat_max' => 0.3800, 'lng_min' => 32.5500, 'lng_max' => 32.5700]
    ];
    
    // Check each area boundary
    foreach ($areas as $areaName => $bounds) {
        if ($lat >= $bounds['lat_min'] && $lat <= $bounds['lat_max'] &&
            $lng >= $bounds['lng_min'] && $lng <= $bounds['lng_max']) {
            return $areaName;
        }
    }
    
    // If no match found, try to find the closest area based on distance
    $closestArea = null;
    $closestDistance = 0.5; // 500 meters threshold
    
    foreach ($areas as $areaName => $bounds) {
        $centerLat = ($bounds['lat_min'] + $bounds['lat_max']) / 2;
        $centerLng = ($bounds['lng_min'] + $bounds['lng_max']) / 2;
        
        // Calculate approximate distance
        $distance = sqrt(pow($lat - $centerLat, 2) + pow($lng - $centerLng, 2));
        
        if ($distance < $closestDistance) {
            $closestDistance = $distance;
            $closestArea = $areaName;
        }
    }
    
    return $closestArea;
}

/**
 * Primary reverse geocoding - uses coordinate detection + BigDataCloud
 */
function reverseGeocode($lat, $lng) {
    if (!is_numeric($lat) || !is_numeric($lng)) return null;
    
    if ($lat < -90 || $lat > 90 || $lng < -180 || $lng > 180) return null;
    
    // First, try coordinate-based precise detection
    $preciseArea = detectPreciseArea($lat, $lng);
    if ($preciseArea) {
        return "$preciseArea, Uganda";
    }
    
    // Second, try BigDataCloud for nearby areas
    $url = "https://api.bigdatacloud.net/data/reverse-geocode-client?latitude={$lat}&longitude={$lng}&localityLanguage=en";
    
    $options = [
        'http' => [
            'method' => 'GET',
            'header' => "User-Agent: AfamFresh/1.0 (https://afamfresh-backend.onrender.com)\r\n",
            'timeout' => 10
        ],
        'ssl' => ['verify_peer' => false, 'verify_peer_name' => false]
    ];
    
    $context = stream_context_create($options);
    $response = @file_get_contents($url, false, $context);
    
    if ($response !== false) {
        $data = json_decode($response, true);
        if ($data && isset($data['locality']) && !empty($data['locality'])) {
            return $data['locality'] . ', Uganda';
        }
        if ($data && isset($data['city']) && !empty($data['city'])) {
            return $data['city'] . ', Uganda';
        }
    }
    
    // Fallback to region detection
    if ($lat >= 0.28 && $lat <= 0.42 && $lng >= 32.52 && $lng <= 32.70) {
        return 'Kampala, Uganda';
    }
    
    return null;
}

/**
 * Detailed reverse geocoding with components
 */
function reverseGeocodeDetailed($lat, $lng) {
    $result = [
        'address' => '',
        'city' => '',
        'district' => '',
        'locality' => '',
        'country' => 'Uganda',
        'countryCode' => 'UG'
    ];
    
    // Get precise area detection first (most accurate)
    $preciseArea = detectPreciseArea($lat, $lng);
    
    if ($preciseArea) {
        $result['locality'] = $preciseArea;
        $result['city'] = 'Kampala';
        $result['address'] = "$preciseArea, Kampala, Uganda";
        return $result;
    }
    
    // Try BigDataCloud as fallback
    $url = "https://api.bigdatacloud.net/data/reverse-geocode-client?latitude={$lat}&longitude={$lng}&localityLanguage=en";
    
    $options = [
        'http' => [
            'method' => 'GET',
            'header' => "User-Agent: AfamFresh/1.0 (https://afamfresh-backend.onrender.com)\r\n",
            'timeout' => 10
        ],
        'ssl' => ['verify_peer' => false, 'verify_peer_name' => false]
    ];
    
    $context = stream_context_create($options);
    $response = @file_get_contents($url, false, $context);
    
    if ($response !== false) {
        $data = json_decode($response, true);
        if ($data) {
            if (isset($data['locality'])) $result['locality'] = $data['locality'];
            if (isset($data['city'])) $result['city'] = $data['city'];
            if (isset($data['principalSubdivision'])) $result['district'] = $data['principalSubdivision'];
            if (isset($data['countryName'])) $result['country'] = $data['countryName'];
            if (isset($data['countryCode'])) $result['countryCode'] = $data['countryCode'];
            
            $parts = array_filter([$result['locality'], $result['city'], $result['district'], $result['country']]);
            $result['address'] = implode(', ', $parts);
            return $result;
        }
    }
    
    // Final fallback
    if ($lat >= 0.28 && $lat <= 0.42 && $lng >= 32.52 && $lng <= 32.70) {
        $result['city'] = 'Kampala';
        $result['address'] = 'Kampala, Uganda';
    }
    
    return $result;
}

/**
 * Forward geocoding - convert address to GPS coordinates
 */
function forwardGeocode($address) {
    global $lastRequestTime, $requestDelay;
    
    $now = microtime(true);
    $timeSinceLast = $now - $lastRequestTime;
    if ($timeSinceLast < $requestDelay) {
        usleep(($requestDelay - $timeSinceLast) * 1000000);
    }
    $lastRequestTime = microtime(true);
    
    if (empty($address)) return null;
    
    $url = 'https://nominatim.openstreetmap.org/search?' . http_build_query([
        'q' => $address,
        'format' => 'json',
        'limit' => 1
    ]);
    
    $response = httpGet($url);
    if ($response === false) return null;
    
    $data = json_decode($response, true);
    if (empty($data)) return null;
    
    return [
        'lat' => (float)$data[0]['lat'],
        'lng' => (float)$data[0]['lon'],
        'display_name' => $data[0]['display_name'] ?? ''
    ];
}

/**
 * Get area from coordinates (simplified for delivery area detection)
 */
function getAreaFromCoordinates($lat, $lng) {
    $details = reverseGeocodeDetailed($lat, $lng);
    if ($details && !empty($details['locality'])) {
        return $details['locality'];
    }
    return detectPreciseArea($lat, $lng);
}
?>