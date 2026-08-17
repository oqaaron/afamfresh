<?php
// =============================================================
// api/Bulk-quote.php
//
// What a Bulk order will cost, before placing it.
//
// The checkout screen used to show "Delivery: added at checkout" and an
// estimate that excluded it, because the fee depends on weight and distance
// and the app cannot work either out. That meant the first time a customer saw
// the real total was after the order existed and the stock had been taken off
// the listing.
//
// This runs exactly the same calculation the order will, through
// calculateBulkDeliveryFee(), so the quote and the charge cannot disagree.
// It writes nothing.
// =============================================================

header('Content-Type: application/json');
require_once '../admin/includes/config.php';
require_once __DIR__ . '/../includes/api_auth.php';
require_once __DIR__ . '/../includes/Bulk_delivery_fee.php';
require_once __DIR__ . '/../includes/service_area.php';
require_once __DIR__ . '/../includes/bulk_listing_rules.php';

// Signed in, but nothing here is scoped to the user: a quote reveals only the
// listing's own public price and the pricing rules. The session check is here
// so the endpoint cannot be used as an anonymous pricing oracle.
if (empty($_SESSION['user_id'])) {
    http_response_code(401);
    echo json_encode(['success' => false, 'error' => 'Please sign in again.']);
    exit;
}

$input = json_decode(file_get_contents('php://input'), true) ?: [];

$listing_id = (int)($input['listing_id'] ?? 0);
$quantity   = (float)($input['quantity'] ?? 0);
$destLat    = isset($input['delivery_lat']) && $input['delivery_lat'] !== ''
                ? (float)$input['delivery_lat'] : null;
$destLng    = isset($input['delivery_lng']) && $input['delivery_lng'] !== ''
                ? (float)$input['delivery_lng'] : null;

if ($listing_id <= 0 || $quantity <= 0) {
    http_response_code(400);
    echo json_encode(['success' => false, 'error' => 'A listing and a quantity are required.']);
    exit;
}

// A pin outside Greater Kampala is refused here rather than priced. Quoting it
// would produce a plausible total for a delivery no rider can make, and the
// customer would only find out after paying.
if ($destLat !== null && $destLng !== null && !isInServiceArea($destLat, $destLng)) {
    echo json_encode([
        'success' => false,
        'error' => serviceAreaMessage(),
        'error_code' => 'OUT_OF_SERVICE_AREA',
    ]);
    exit;
}

try {
    // No FOR UPDATE and no transaction: this is a read, and holding a row lock
    // while a customer thinks about a quantity would block every other buyer.
    $stmt = $dbh->prepare(
        "SELECT sl.discounted_price, sl.weight_per_unit_kg, sl.pickup_only,
                sl.remaining_quantity, sl.is_weight_based,
                sl.listing_type, sl.min_order_quantity,
                v.lat AS vendor_lat, v.lng AS vendor_lng, v.business_name
           FROM Bulk_listings sl
           JOIN vendors v ON v.id = sl.vendor_id
          WHERE sl.id = ? AND sl.status = 'approved'"
    );
    $stmt->execute([$listing_id]);
    $listing = $stmt->fetch(PDO::FETCH_ASSOC);

    if (!$listing) {
        echo json_encode(['success' => false, 'error' => 'That deal is no longer available.']);
        exit;
    }

    $goodsValue = (float)$listing['discounted_price'] * $quantity;
    $weightKg   = $quantity * (float)($listing['weight_per_unit_kg'] ?: 1);
    $pickupOnly = (bool)$listing['pickup_only'];

    $distance = $pickupOnly
        ? null
        : BulkDeliveryDistance(
            $listing['vendor_lat'] !== null ? (float)$listing['vendor_lat'] : null,
            $listing['vendor_lng'] !== null ? (float)$listing['vendor_lng'] : null,
            $destLat,
            $destLng
        );

    $breakdown = calculateBulkDeliveryFee($dbh, $goodsValue, $weightKg, $distance, $pickupOnly);

    // The order rules, answered here rather than at order time.
    //
    // This endpoint priced an order it had no opinion on: a customer could be
    // shown a clean total and a working Pay button, and only be refused once
    // they committed. bulkOrderViolation() is the SAME function
    // api/api/Bulk-orders.php refuses with, so the two cannot disagree — a
    // quote that says ok is an order that will be accepted, on these rules.
    $limits    = BulkDeliverySettings($dbh);
    $violation = bulkOrderViolation($listing, $quantity, $limits);

    echo json_encode([
        'success'        => true,
        'goods_total'    => round($goodsValue),
        'delivery_fee'   => $breakdown['total_fee'],
        'grand_total'    => round($goodsValue + $breakdown['total_fee']),
        'total_weight_kg'=> round($weightKg, 2),
        'breakdown'      => $breakdown,
        // So the screen can warn before the customer commits, rather than
        // letting the order endpoint refuse it afterwards.
        'exceeds_stock'  => $quantity > (float)$listing['remaining_quantity'],

        // Whether THIS quantity may be ordered, and why not if it may not.
        'can_order'      => $violation === null,
        'blocked_reason' => $violation['error'] ?? null,
        'blocked_code'   => $violation['code'] ?? null,

        // What applies to this listing at all, so the screen can state the
        // rules up front instead of hardcoding numbers an admin can change.
        // Irrelevant fields come back as 0 and are simply not rendered.
        'limits'         => bulkOrderLimitsFor($listing, $limits),
    ]);
} catch (PDOException $e) {
    error_log('Bulk-quote: ' . $e->getMessage());
    http_response_code(500);
    echo json_encode(['success' => false, 'error' => 'Could not price that order right now.']);
}