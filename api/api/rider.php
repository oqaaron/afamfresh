<?php
// =============================================================
// api/rider.php — the rider section's backend
//
// Actions: me | deliveries | delivery_detail | update_status
//        | duty_status | location | upload_proof
//        | earnings | request_payout | my_payouts
//
// IDENTITY. A rider is a normal app account with the Rider role,
// linked to a `riders` row by riders.user_id (UNIQUE). Every action
// resolves $_SESSION['user_id'] -> rider_id through currentRider().
//
// ASSIGNMENT. rider_assignments is the source of truth for who is
// delivering what.
// =============================================================

session_start();
require_once '../admin/includes/config.php';
require_once __DIR__ . '/../includes/user_payload.php';
require_once __DIR__ . '/../includes/rider_earnings.php';
require_once __DIR__ . '/../includes/rider_dispatch.php';
require_once __DIR__ . '/../includes/payment_ledger.php';
require_once __DIR__ . '/../includes/storage.php';
header('Content-Type: application/json');

if (!isset($_SESSION['user_id'])) {
    echo json_encode(['success' => false, 'error' => 'Not logged in']);
    exit;
}

$user_id = $_SESSION['user_id'];
$action  = $_GET['action'] ?? '';

$jsonBody = json_decode(file_get_contents('php://input'), true);
if (!is_array($jsonBody)) { $jsonBody = []; }

function param($key, $default = null) {
    global $jsonBody;
    if (array_key_exists($key, $jsonBody)) return $jsonBody[$key];
    if (array_key_exists($key, $_POST))    return $_POST[$key];
    if (array_key_exists($key, $_GET))     return $_GET[$key];
    return $default;
}

function fail($message, $httpCode = 200, $code = null) {
    if ($httpCode !== 200) http_response_code($httpCode);
    $body = ['success' => false, 'error' => $message];
    if ($code !== null) $body['code'] = $code;
    echo json_encode($body);
    exit;
}

/**
 * Calculates Haversine distance in meters between two lat/lng pairs.
 */
function calculateDistanceMeters($lat1, $lon1, $lat2, $lon2) {
    $earthRadius = 6371000.0;
    $dLat = deg2rad($lat2 - $lat1);
    $dLon = deg2rad($lon2 - $lon1);

    $a = sin($dLat / 2) * sin($dLat / 2) +
         cos(deg2rad($lat1)) * cos(deg2rad($lat2)) *
         sin($dLon / 2) * sin($dLon / 2);

    $c = 2 * atan2(sqrt($a), sqrt(1 - $a));
    return $earthRadius * $c;
}

function currentRider($dbh, $user_id) {
    $stmt = $dbh->prepare("SELECT * FROM riders WHERE user_id = ?");
    $stmt->execute([$user_id]);
    $rider = $stmt->fetch(PDO::FETCH_ASSOC);
    return $rider ?: null;
}

function requireRiderAccount($dbh, $user_id) {
    require_once __DIR__ . '/../includes/account_type.php';
    requireAccountType($dbh, $user_id, 'rider');
}

function requireRider($dbh, $user_id) {
    requireRiderAccount($dbh, $user_id);
    $rider = currentRider($dbh, $user_id);
    if (!$rider) {
        fail('This account is not set up as a rider yet. Ask an administrator to link it to a rider profile.');
    }
    return $rider;
}

// =============================================================
// STATUS MODEL
// =============================================================
$FLOW = ['assigned', 'picked_up', 'on_way', 'delivered'];

$STATUS_MAP = [
    'assigned'  => ['current' => 'ready',     'label' => 'Preparing'],
    'picked_up' => ['current' => 'on_way',    'label' => 'Out for Delivery'],
    'on_way'    => ['current' => 'on_way',    'label' => 'On Way'],
    'delivered' => ['current' => 'delivered', 'label' => 'Delivered'],
];

function assignmentFor($dbh, $riderId, $orderId, $source = 'order') {
    $stmt = $dbh->prepare(
        "SELECT * FROM rider_assignments
          WHERE rider_id = ? AND order_id = ? AND source = ? LIMIT 1"
    );
    $stmt->execute([$riderId, $orderId, $source]);
    return $stmt->fetch(PDO::FETCH_ASSOC) ?: null;
}

function appendStatusHistory($dbh, $orderId, $status, $note) {
    $stmt = $dbh->prepare("SELECT status_history FROM orders WHERE orderid = ?");
    $stmt->execute([$orderId]);
    $history = json_decode((string)$stmt->fetchColumn(), true);
    if (!is_array($history)) { $history = []; }

    $history[] = [
        'status' => $status,
        'note'   => $note,
        'at'     => date('Y-m-d H:i:s'),
    ];

    $upd = $dbh->prepare("UPDATE orders SET status_history = ? WHERE orderid = ?");
    $upd->execute([json_encode($history), $orderId]);
}

// =============================================================
// ACTION: ME
// =============================================================
if ($action === 'me') {
    $rider = requireRider($dbh, $user_id);

    $counts = $dbh->prepare(
        "SELECT
            SUM(status = 'delivered' AND DATE(delivered_at) = CURDATE()) AS delivered_today,
            SUM(status <> 'delivered') AS active_count
         FROM rider_assignments WHERE rider_id = ?"
    );
    $counts->execute([$rider['id']]);
    $c = $counts->fetch(PDO::FETCH_ASSOC);

    echo json_encode([
        'success' => true,
        'rider' => [
            'id'              => (int)$rider['id'],
            'name'            => $rider['name'],
            'phone'           => $rider['phone'],
            'email'           => $rider['email'],
            'vehicle_type'    => $rider['vehicle_type'],
            'status'          => $rider['status'],
            'is_online'       => $rider['status'] === 'online',
            'avg_rating'      => (float)$rider['avg_rating'],
            'total_ratings'   => (int)$rider['total_ratings'],
            'delivered_today' => (int)($c['delivered_today'] ?? 0),
            'active_count'    => (int)($c['active_count'] ?? 0),
        ],
    ]);
    exit;
}

// =============================================================
// ACTION: DELIVERIES
// =============================================================
if ($action === 'deliveries') {
    $rider = requireRider($dbh, $user_id);

    $stmt = $dbh->prepare(
        "SELECT id AS assignment_id, order_id, source, status AS assignment_status,
                assigned_at, delivered_at AS assignment_delivered_at
           FROM rider_assignments
          WHERE rider_id = ?
          ORDER BY assigned_at DESC
          LIMIT 100"
    );
    $stmt->execute([$rider['id']]);

    $active = [];
    $history = [];
    foreach ($stmt->fetchAll(PDO::FETCH_ASSOC) as $a) {
        $row = loadDeliverable($dbh, $a['source'], (int)$a['order_id']);
        if (!$row) {
            error_log("rider deliveries: assignment {$a['assignment_id']} points at a missing {$a['source']} order {$a['order_id']}");
            continue;
        }

        // Check fallback dropoff coordinates
        $destLat = $row['dest_lat'] ?? $row['dropoff_latitude'] ?? $row['delivery_lat'] ?? null;
        $destLng = $row['dest_lng'] ?? $row['dropoff_longitude'] ?? $row['delivery_lng'] ?? null;

        $d = [
            'assignment_id'   => (int)$a['assignment_id'],
            'order_id'        => (int)$row['order_id'],
            'source'          => $row['source'],
            'status'          => $a['assignment_status'],
            'order_status'    => $row['status'],
            'payment_status'  => $row['payment_status'],
            'customer_name'   => trim($row['fname'] . ' ' . $row['lname']),
            'customer_phone'  => $row['mobile'],
            'area'            => $row['area'],
            'address'         => $row['delivery_address'] ?: $row['address'],
            'landmark_notes'  => $row['landmark_notes'] ?? null,
            'pickup_address'  => $row['pickup_address'],
            'dest_lat'        => $destLat !== null ? (float)$destLat : null,
            'dest_lng'        => $destLng !== null ? (float)$destLng : null,
            'total_amount'    => (float)$row['total_amount'],
            'delivery_fee'    => (float)$row['delivery_fee'],
            'assigned_at'     => $a['assigned_at'],
            'delivered_at'    => $a['assignment_delivered_at'],
            'ordertime'       => $row['ordertime'],
            'scheduled_date'  => $row['scheduled_delivery_date'],
            'scheduled_slot'  => $row['scheduled_delivery_slot'],
            'has_proof_photo' => !empty($row['delivery_photo']),
        ];
        if ($a['assignment_status'] === 'delivered') {
            $history[] = $d;
        } else {
            $active[] = $d;
        }
    }

    echo json_encode(['success' => true, 'active' => $active, 'history' => $history]);
    exit;
}

// =============================================================
// ACTION: DELIVERY DETAIL
// =============================================================
if ($action === 'delivery_detail') {
    $rider = requireRider($dbh, $user_id);
    $orderId = (int)param('order_id', 0);
    $source  = (string)param('source', 'order');
    if (!$orderId) fail('No order was specified.');
    if (!isDispatchSource($source)) fail('That is not a kind of delivery.');

    $assignment = assignmentFor($dbh, $rider['id'], $orderId, $source);
    if (!$assignment) fail('That delivery is not assigned to you.');

    $o = loadDeliverable($dbh, $source, $orderId);
    if (!$o) fail('Order not found.');

    // Fetch extra verification coordinates and landmark instructions
    if ($source === 'order') {
        $extraStmt = $dbh->prepare("SELECT landmark_notes, delivery_otp, dropoff_latitude, dropoff_longitude, delivery_lat, delivery_lng, dest_lat, dest_lng FROM orders WHERE orderid = ?");
        $extraStmt->execute([$orderId]);
        $extra = $extraStmt->fetch(PDO::FETCH_ASSOC) ?: [];
    } else {
        $extra = [];
    }

    $items = deliverableItems($dbh, $source, $orderId);

    $proofUrl = null;
    if (!empty($o['delivery_photo']) && storageExists('proof/' . $o['delivery_photo'])) {
        $proofUrl = storageUrl('proof/' . $o['delivery_photo']);
    }

    $destLat = $o['dest_lat'] ?? $extra['dropoff_latitude'] ?? $extra['dest_lat'] ?? $extra['delivery_lat'] ?? null;
    $destLng = $o['dest_lng'] ?? $extra['dropoff_longitude'] ?? $extra['dest_lng'] ?? $extra['delivery_lng'] ?? null;

    echo json_encode([
        'success' => true,
        'delivery' => [
            'assignment_id'   => (int)$assignment['id'],
            'order_id'        => (int)$o['order_id'],
            'source'          => $o['source'],
            'status'          => $assignment['status'],
            'order_status'    => $o['status'],
            'payment_status'  => $o['payment_status'],
            'customer_name'   => trim($o['fname'] . ' ' . $o['lname']),
            'customer_phone'  => $o['mobile'],
            'area'            => $o['area'],
            'address'         => $o['delivery_address'] ?: $o['address'],
            'landmark_notes'  => $extra['landmark_notes'] ?? null,
            'pickup_address'  => $o['pickup_address'],
            'pickup_code'     => $o['pickup_code'],
            'dest_lat'        => $destLat !== null ? (float)$destLat : null,
            'dest_lng'        => $destLng !== null ? (float)$destLng : null,
            'total_amount'    => (float)$o['total_amount'],
            'delivery_fee'    => (float)$o['delivery_fee'],
            'ordertime'       => $o['ordertime'],
            'scheduled_date'  => $o['scheduled_delivery_date'],
            'scheduled_slot'  => $o['scheduled_delivery_slot'],
            'proof_photo_url' => $proofUrl,
            'items'           => $items,
        ],
    ]);
    exit;
}

// =============================================================
// ACTION: UPDATE STATUS (with Geofence & Handshake Validation)
// =============================================================
if ($action === 'update_status') {
    $rider   = requireRider($dbh, $user_id);
    $orderId = (int)param('order_id', 0);
    $next    = trim((string)param('status', ''));
    $source  = (string)param('source', 'order');

    if (!$orderId) fail('No order was specified.');
    if (!isset($STATUS_MAP[$next])) fail('That is not a valid delivery status.');
    if (!isDispatchSource($source)) fail('That is not a kind of delivery.');

    $assignment = assignmentFor($dbh, $rider['id'], $orderId, $source);
    if (!$assignment) fail('That delivery is not assigned to you.');

    $current = $assignment['status'];
    $fromIdx = array_search($current, $FLOW, true);
    $toIdx   = array_search($next, $FLOW, true);

    if ($fromIdx === false) {
        $fromIdx = 0;
    }
    if ($toIdx <= $fromIdx) {
        fail($toIdx === $fromIdx
            ? 'This delivery is already at that stage.'
            : 'A delivery cannot go back to an earlier stage.');
    }

    $map = $STATUS_MAP[$next];

    // Handshake and Geofence checks for delivery completion
    if ($next === 'delivered') {
        $enteredOtp = trim((string)param('delivery_otp', ''));
        $riderLat   = param('latitude') !== null ? (float)param('latitude') : (param('lat') !== null ? (float)param('lat') : null);
        $riderLng   = param('longitude') !== null ? (float)param('longitude') : (param('lng') !== null ? (float)param('lng') : null);

        // Standard orders require 4-digit customer verification code
        if ($source === 'order') {
            $checkStmt = $dbh->prepare("
                SELECT delivery_otp,
                       COALESCE(dropoff_latitude, dest_lat, delivery_lat) AS target_lat,
                       COALESCE(dropoff_longitude, dest_lng, delivery_lng) AS target_lng
                FROM orders
                WHERE orderid = ?
            ");
            $checkStmt->execute([$orderId]);
            $orderData = $checkStmt->fetch(PDO::FETCH_ASSOC);

            if (!$orderData) {
                fail('Order record missing.', 404);
            }

            // Verify Handshake PIN
            if (!empty($orderData['delivery_otp'])) {
                if (empty($enteredOtp)) {
                    fail('Enter the 4-digit delivery PIN from the customer.', 422, 'PIN_REQUIRED');
                }
                if ($orderData['delivery_otp'] !== $enteredOtp) {
                    fail('Invalid delivery code. Ask the customer for their 4-digit PIN.', 422, 'INVALID_PIN');
                }
            }

            // Verify Geofence proximity (150-meter radius threshold)
            $targetLat = (float)($orderData['target_lat'] ?? 0);
            $targetLng = (float)($orderData['target_lng'] ?? 0);

            if ($riderLat !== null && $riderLng !== null && $targetLat != 0.0 && $targetLng != 0.0) {
                $distance = calculateDistanceMeters($riderLat, $riderLng, $targetLat, $targetLng);
                if ($distance > 150.0) {
                    fail('Geofence check failed: You are ' . round($distance) . 'm away from drop-off. Must be within 150m.', 403, 'GEOFENCE_FAILED');
                }
            }
        }
    }

    // Cash on delivery handling
    $requiresCashConfirm = false;
    $payableForCash = 0.0;
    if ($next === 'delivered') {
        $deliverable = loadDeliverable($dbh, $source, $orderId);
        if ($deliverable && strcasecmp((string)$deliverable['payment_status'], 'pending_cash') === 0) {
            $payableForCash = (float)$deliverable['total_amount'];
            $confirmed = in_array((string)param('cash_collected', ''), ['1', 'true', 'yes'], true);
            $enforce = env('CASH_CONFIRMATION_REQUIRED', '0') === '1';

            if (!$confirmed && $enforce) {
                fail('Confirm you have collected UGX ' . number_format($payableForCash, 0)
                     . ' in cash before marking this delivered.', 409, 'CASH_NOT_CONFIRMED');
            }
            $requiresCashConfirm = $confirmed || !$enforce;
        }
    }

    try {
        $dbh->beginTransaction();

        if ($next === 'delivered') {
            $dbh->prepare(
                "UPDATE rider_assignments SET status = ?, delivered_at = NOW(), completed_at = NOW() WHERE id = ?"
            )->execute([$next, $assignment['id']]);

            applyDeliveryStatus($dbh, $source, $orderId, $map, $next);

            // Synchronize completion timestamps on orders table
            if ($source === 'order') {
                $dbh->prepare("
                    UPDATE orders 
                    SET delivery_confirmed = 1,
                        delivery_confirmed_at = NOW(),
                        delivered_at = NOW(),
                        completed_at = CURRENT_TIMESTAMP
                    WHERE orderid = ?
                ")->execute([$orderId]);
            }

            if ($requiresCashConfirm) {
                if ($source === 'order') {
                    $dbh->prepare(
                        "UPDATE orders
                            SET payment_status = 'paid', payment_method = 'cash',
                                cash_collected_by = ?, cash_collected_at = NOW(),
                                payment_captured_at = COALESCE(payment_captured_at, NOW())
                          WHERE orderid = ? AND payment_status = 'pending_cash'"
                    )->execute([$rider['id'], $orderId]);
                } else {
                    $dbh->prepare(
                        "UPDATE Bulk_orders
                            SET payment_status = 'paid', payment_method = 'cash',
                                cash_collected_by = ?, cash_collected_at = NOW(),
                                payment_captured_at = COALESCE(payment_captured_at, NOW()),
                                updated_at = NOW()
                          WHERE id = ? AND payment_status = 'pending_cash'"
                    )->execute([$rider['id'], $orderId]);
                }
            }

            $credit = creditRiderEarnings($dbh, $rider['id'], $orderId, $source);
            if (!$credit['ok']) {
                throw new RuntimeException($credit['error']);
            }

            if ($source === 'Bulk') {
                require_once __DIR__ . '/../includes/vendor_earnings.php';
                $vendorCredit = creditVendorEarnings($dbh, $orderId);
                if (!$vendorCredit['ok'] && ($vendorCredit['code'] ?? '') !== 'NOT_PAID') {
                    throw new RuntimeException($vendorCredit['error']);
                }
            }

            require_once __DIR__ . '/../includes/loyalty.php';
            $customerIdStmt = $source === 'order'
                ? $dbh->prepare("SELECT user_id FROM orders WHERE orderid = ?")
                : $dbh->prepare("SELECT user_id FROM Bulk_orders WHERE id = ?");
            $customerIdStmt->execute([$orderId]);
            $customerId = (int)($customerIdStmt->fetchColumn() ?: 0);
            if ($customerId > 0) {
                $goodsValue = goodsValueForOrder($dbh, $source, $orderId);
                if ($goodsValue !== null) {
                    $earn = earnLoyaltyPoints($dbh, $customerId, $source, $orderId, $goodsValue);
                    if (!$earn['ok']) {
                        error_log("rider.php: loyalty earn failed for $source order $orderId: " . ($earn['error'] ?? ''));
                    }
                }
            }
        } else {
            $dbh->prepare("UPDATE rider_assignments SET status = ? WHERE id = ?")
                ->execute([$next, $assignment['id']]);

            applyDeliveryStatus($dbh, $source, $orderId, $map, $next);

            if ($next === 'picked_up' && empty($assignment['route_polyline'])) {
                cacheAssignmentRoute($dbh, $source, $orderId, (int)$assignment['id']);
            }
        }

        if ($source === 'order') {
            appendStatusHistory($dbh, $orderId, $map['label'], 'Updated by rider ' . $rider['name']);
        }

        $dbh->commit();

        if ($requiresCashConfirm) {
            recordPaymentEvent($dbh, $source === 'order' ? 'order' : 'Bulk', $orderId,
                'cash_collected', [
                    'from_status' => 'pending_cash',
                    'to_status'   => 'paid',
                    'method'      => 'cash',
                    'amount'      => $payableForCash,
                    'actor_type'  => 'rider',
                    'actor_id'    => (int)$rider['id'],
                ]);
        }
    } catch (Exception $e) {
        if ($dbh->inTransaction()) $dbh->rollBack();
        error_log('rider update_status failed: ' . $e->getMessage());
        fail('Could not update the delivery. Please try again.');
    }

    if ($next === 'picked_up') {
        try {
            require_once __DIR__ . '/../includes/brevo-sms.php';
            $row = loadDeliverable($dbh, $source, $orderId);
            if ($row && !empty($row['mobile'])) {
                sendSmsWithBrevo(
                    $row['mobile'],
                    "AfamFresh: order #{$orderId} is out for delivery and on its way to you."
                );
            }
        } catch (Throwable $e) {
            error_log("Order $orderId marked out for delivery but the SMS failed: " . $e->getMessage());
        }
    }

    if ($next === 'picked_up' && $source === 'order') {
        try {
            require_once __DIR__ . '/../includes/notifications.php';
            $ownerId = $dbh->prepare("SELECT user_id FROM orders WHERE orderid = ?");
            $ownerId->execute([$orderId]);
            if ($userId = $ownerId->fetchColumn()) {
                addNotification(
                    (int)$userId,
                    'Out for delivery',
                    "Your order #{$orderId} is out for delivery and on its way to you.",
                    'order', null, ['push'],
                    ['order_id' => (string)$orderId, 'source' => 'order']
                );
            }
        } catch (Throwable $e) {
            error_log("Order $orderId marked out for delivery but the push notification failed: " . $e->getMessage());
        }
    }

    if ($next === 'delivered' && $source === 'Bulk') {
        try {
            require_once __DIR__ . '/../includes/notifications.php';
            $who = $dbh->prepare(
                "SELECT so.user_id, i.name AS product_name, v.business_name,
                        v.user_id AS vendor_user_id
                   FROM Bulk_orders so
                   JOIN Bulk_listings sl ON sl.id = so.listing_id
                   JOIN items i ON i.id = sl.product_id
                   JOIN vendors v ON v.id = sl.vendor_id
                  WHERE so.id = ?"
            );
            $who->execute([$orderId]);
            if ($row = $who->fetch(PDO::FETCH_ASSOC)) {
                addNotification(
                    (int)$row['user_id'],
                    'Order delivered',
                    'Your Bulk order #' . $orderId . ' (' . $row['product_name']
                        . ' from ' . $row['business_name'] . ') has been delivered.',
                    'order',
                    null,
                    ['push', 'email']
                );

                if (!empty($row['vendor_user_id'])) {
                    addNotification(
                        (int)$row['vendor_user_id'],
                        'Order delivered',
                        'Order #' . $orderId . ' (' . $row['product_name']
                            . ') was delivered by the rider. Your earnings for it have been credited.',
                        'order',
                        null,
                        ['push']
                    );
                }
            }
        } catch (Throwable $e) {
            error_log("Bulk order $orderId delivered but the customer was not notified: " . $e->getMessage());
        }
    }

    if ($next === 'delivered' && $source === 'order') {
        try {
            require_once __DIR__ . '/../includes/notifications.php';
            $ownerId = $dbh->prepare("SELECT user_id FROM orders WHERE orderid = ?");
            $ownerId->execute([$orderId]);
            if ($userId = $ownerId->fetchColumn()) {
                addNotification(
                    (int)$userId,
                    'Order delivered',
                    "Your order #{$orderId} has been delivered. Let us know how it went!",
                    'order', null, ['push'],
                    ['order_id' => (string)$orderId, 'source' => 'order']
                );
            }
        } catch (Throwable $e) {
            error_log("Order $orderId delivered but the push notification failed: " . $e->getMessage());
        }
    }

    echo json_encode([
        'success'        => true,
        'status'         => $next,
        'order_status'   => $map['label'],
        'current_status' => $map['current'],
    ]);
    exit;
}

// =============================================================
// ACTION: DUTY STATUS (online / offline)
// =============================================================
if ($action === 'duty_status') {
    $rider  = requireRider($dbh, $user_id);
    $status = trim((string)param('status', ''));

    if (!in_array($status, ['online', 'offline'], true)) {
        fail('Status must be either online or offline.');
    }

    $dbh->prepare("UPDATE riders SET status = ? WHERE id = ?")->execute([$status, $rider['id']]);

    echo json_encode(['success' => true, 'status' => $status, 'is_online' => $status === 'online']);
    exit;
}

// =============================================================
// ACTION: LOCATION
// =============================================================
if ($action === 'location') {
    $rider = requireRider($dbh, $user_id);
    $lat = param('lat');
    $lng = param('lng');

    if (!is_numeric($lat) || !is_numeric($lng)) fail('A valid latitude and longitude are required.');
    $lat = (float)$lat;
    $lng = (float)$lng;
    if ($lat < -90 || $lat > 90 || $lng < -180 || $lng > 180) {
        fail('Those coordinates are out of range.');
    }

    $dbh->prepare(
        "UPDATE riders SET current_lat = ?, current_lng = ?, last_location_update = NOW() WHERE id = ?"
    )->execute([$lat, $lng, $rider['id']]);

    $accuracy = is_numeric(param('accuracy')) ? (float)param('accuracy') : null;
    $speed    = is_numeric(param('speed'))    ? (float)param('speed')    : null;
    $heading  = is_numeric(param('heading'))  ? (float)param('heading')  : null;

    $active = $dbh->prepare(
        "SELECT id, order_id, source FROM rider_assignments
          WHERE rider_id = ? AND status IN ('picked_up','on_way')
          ORDER BY assigned_at DESC LIMIT 1"
    );
    $active->execute([$rider['id']]);
    $assignmentRow = $active->fetch(PDO::FETCH_ASSOC);
    $orderId = $assignmentRow['order_id'] ?? null;

    $usable = $accuracy === null || $accuracy <= 100;

    if ($orderId && $usable) {
        $dbh->prepare(
            "INSERT INTO order_tracking_logs
                (order_id, source, rider_id, assignment_id, lat, lng, accuracy_m, speed_mps, heading_deg)
             VALUES (?,?,?,?,?,?,?,?,?)"
        )->execute([
            $orderId,
            $assignmentRow['source'] ?? 'order',
            $rider['id'],
            $assignmentRow['id'] ?? null,
            $lat, $lng,
            $accuracy !== null ? (int)round($accuracy) : null,
            $speed,
            $heading !== null ? (int)round($heading) : null,
        ]);
    }

    echo json_encode([
        'success' => true,
        'tracked_order_id' => $orderId ? (int)$orderId : null,
        'source' => $assignmentRow['source'] ?? null,
        'recorded' => (bool)($orderId && $usable),
    ]);
    exit;
}

// =============================================================
// ACTION: UPLOAD PROOF OF DELIVERY
// =============================================================
if ($action === 'upload_proof') {
    $rider   = requireRider($dbh, $user_id);
    $orderId = (int)param('order_id', 0);
    $source  = (string)param('source', 'order');
    if (!$orderId) fail('No order was specified.');
    if (!isDispatchSource($source)) fail('That is not a kind of delivery.');

    $assignment = assignmentFor($dbh, $rider['id'], $orderId, $source);
    if (!$assignment) fail('That delivery is not assigned to you.');

    if (!isset($_FILES['photo'])) fail('No photo was received.');

    require_once __DIR__ . '/../includes/image_upload.php';

    $existing = loadDeliverable($dbh, $source, $orderId);
    if (!$existing) fail('Order not found.');
    $prev = $existing['delivery_photo'] ?? null;

    $result = saveUploadedImage(
        $_FILES['photo'],
        'proof',
        'proof',
        $prev ?: null
    );

    if (!$result['ok']) fail($result['error']);

    saveDeliveryProof($dbh, $source, $orderId, $result['filename']);

    echo json_encode([
        'success' => true,
        'proof_photo_url' => storageUrl('proof/' . $result['filename']),
    ]);
    exit;
}

// =============================================================
// ACTION: EARNINGS
// =============================================================
if ($action === 'earnings') {
    $rider = requireRider($dbh, $user_id);

    $totals = $dbh->prepare(
        "SELECT
            SUM(CASE WHEN DATE(created_at) = CURDATE() THEN net_earnings ELSE 0 END) AS today,
            SUM(CASE WHEN YEARWEEK(created_at, 1) = YEARWEEK(CURDATE(), 1) THEN net_earnings ELSE 0 END) AS this_week,
            SUM(net_earnings) AS all_time,
            SUM(CASE WHEN is_paid = 0 THEN net_earnings ELSE 0 END) AS available,
            COUNT(*) AS delivery_count
         FROM rider_earnings WHERE rider_id = ?"
    );
    $totals->execute([$rider['id']]);
    $t = $totals->fetch(PDO::FETCH_ASSOC);

    $rows = $dbh->prepare(
        "SELECT re.id, re.order_id, re.source, re.mileage_fee, re.commission_rate,
                re.commission_amount, re.net_earnings, re.is_estimated, re.is_paid,
                re.created_at, o.fname, o.lname
         FROM rider_earnings re
         LEFT JOIN orders o ON o.orderid = re.order_id AND re.source = 'order'
         WHERE re.rider_id = ?
         ORDER BY re.created_at DESC
         LIMIT 100"
    );
    $rows->execute([$rider['id']]);
    $earningRows = $rows->fetchAll(PDO::FETCH_ASSOC);

    $BulkIds = array_values(array_unique(array_map(
        fn($r) => (int)$r['order_id'],
        array_filter($earningRows, fn($r) => $r['source'] === 'Bulk')
    )));
    $BulkNames = [];
    if ($BulkIds) {
        $in = implode(',', array_fill(0, count($BulkIds), '?'));
        $namesStmt = $dbh->prepare(
            "SELECT so.id, u.fname, u.lname
               FROM Bulk_orders so JOIN users u ON u.id = so.user_id
              WHERE so.id IN ($in)"
        );
        $namesStmt->execute($BulkIds);
        foreach ($namesStmt->fetchAll(PDO::FETCH_ASSOC) as $n) {
            $BulkNames[(int)$n['id']] = trim($n['fname'] . ' ' . $n['lname']);
        }
    }

    $history = array_map(function ($r) use ($BulkNames) {
        $name = $r['source'] === 'Bulk'
            ? ($BulkNames[(int)$r['order_id']] ?? '')
            : trim($r['fname'] . ' ' . $r['lname']);

        return [
            'id'                => (int)$r['id'],
            'order_id'          => (int)$r['order_id'],
            'source'            => $r['source'],
            'customer_name'     => $name,
            'mileage_fee'       => (float)$r['mileage_fee'],
            'commission_rate'   => (float)$r['commission_rate'],
            'commission_amount' => (float)$r['commission_amount'],
            'net_earnings'      => (float)$r['net_earnings'],
            'is_estimated'      => (bool)$r['is_estimated'],
            'is_paid'           => (bool)$r['is_paid'],
            'created_at'        => $r['created_at'],
        ];
    }, $earningRows);

    echo json_encode([
        'success' => true,
        'summary' => [
            'today'           => round((float)($t['today'] ?? 0), 2),
            'this_week'       => round((float)($t['this_week'] ?? 0), 2),
            'all_time'        => round((float)($t['all_time'] ?? 0), 2),
            'available'       => round((float)($t['available'] ?? 0), 2),
            'delivery_count'  => (int)($t['delivery_count'] ?? 0),
            'commission_rate' => RIDER_COMMISSION_RATE,
        ],
        'history' => $history,
    ]);
    exit;
}

// =============================================================
// ACTION: REQUEST PAYOUT
// =============================================================
if ($action === 'request_payout') {
    $rider = requireRider($dbh, $user_id);

    $availableStmt = $dbh->prepare(
        "SELECT COALESCE(SUM(net_earnings), 0) FROM rider_earnings WHERE rider_id = ? AND is_paid = 0"
    );
    $availableStmt->execute([$rider['id']]);
    $available = round((float)$availableStmt->fetchColumn(), 2);

    if ($available <= 0) {
        fail('You have nothing available to withdraw right now.');
    }

    $pendingStmt = $dbh->prepare(
        "SELECT id FROM rider_payout_requests WHERE rider_id = ? AND status = 'pending'"
    );
    $pendingStmt->execute([$rider['id']]);
    if ($pendingStmt->fetchColumn()) {
        fail('You already have a payout request pending review.');
    }

    $dbh->prepare(
        "INSERT INTO rider_payout_requests (rider_id, amount, status) VALUES (?, ?, 'pending')"
    )->execute([$rider['id'], $available]);

    echo json_encode(['success' => true, 'amount' => $available]);
    exit;
}

// =============================================================
// ACTION: MY PAYOUTS
// =============================================================
if ($action === 'my_payouts') {
    $rider = requireRider($dbh, $user_id);

    $stmt = $dbh->prepare(
        "SELECT id, amount, status, requested_at, processed_at, notes
         FROM rider_payout_requests WHERE rider_id = ? ORDER BY requested_at DESC LIMIT 50"
    );
    $stmt->execute([$rider['id']]);

    $payouts = array_map(function ($r) {
        return [
            'id'            => (int)$r['id'],
            'amount'        => (float)$r['amount'],
            'status'        => $r['status'],
            'requested_at'  => $r['requested_at'],
            'processed_at'  => $r['processed_at'],
            'notes'         => $r['notes'],
        ];
    }, $stmt->fetchAll(PDO::FETCH_ASSOC));

    echo json_encode(['success' => true, 'payouts' => $payouts]);
    exit;
}

fail('Invalid action');