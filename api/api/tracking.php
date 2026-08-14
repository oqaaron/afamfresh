<?php
// =============================================================
// api/tracking.php — where is my delivery?
//
// order_tracking_logs has been collecting breadcrumbs since the dispatch work
// and NOTHING has ever read them. This is the read side.
//
// AUTHORISATION IS THE WHOLE POINT OF THIS FILE
//
// api/location.php already demonstrates the failure mode to avoid: its
// `?action=rider&id=N` returns any rider's live position to anyone with a
// session, with no ownership check at all. A person's real-time location is
// about the most sensitive thing this system holds.
//
// So: ownership is resolved FROM THE ORDER, never from a parameter. The
// customer who placed it, the rider carrying it, and an admin may read it.
// Nobody else, and the refusal reads identically whether or not the order
// exists so this cannot be used to enumerate order ids.
//
// The rider's name and phone are returned only while the delivery is actually
// in progress. After it completes they are withheld — a customer does not get
// a courier's contact details for the rest of the day.
// =============================================================

session_start();
header('Content-Type: application/json');
require_once '../admin/includes/config.php';
require_once __DIR__ . '/../includes/rider_dispatch.php';

function trackFail(string $message, int $code = 400): void {
    http_response_code($code);
    echo json_encode(['success' => false, 'error' => $message]);
    exit;
}

$sessionUserId = (int)($_SESSION['user_id'] ?? 0);
$isAdmin = isset($_SESSION['admin_logged_in']) && $_SESSION['admin_logged_in'] === true;

if (!$sessionUserId && !$isAdmin) {
    trackFail('Please sign in again.', 401);
}

$orderId = (int)($_GET['order_id'] ?? 0);
$source  = (string)($_GET['source'] ?? 'order');
$since   = trim((string)($_GET['since'] ?? ''));

if ($orderId <= 0) trackFail('An order is required.');
if (!isDispatchSource($source)) trackFail('That is not a kind of delivery.');

try {
    // --- who owns this order -------------------------------------------
    $ownerStmt = $source === 'order'
        ? $dbh->prepare("SELECT user_id FROM orders WHERE orderid = ?")
        : $dbh->prepare("SELECT user_id FROM Bulk_orders WHERE id = ?");
    $ownerStmt->execute([$orderId]);
    $ownerId = $ownerStmt->fetchColumn();

    // --- who is carrying it --------------------------------------------
    $assignStmt = $dbh->prepare(
        "SELECT ra.id, ra.rider_id, ra.status, ra.assigned_at, ra.delivered_at,
                ra.route_polyline, ra.route_distance_km, ra.route_duration_min,
                ra.route_fetched_at,
                r.name AS rider_name, r.phone AS rider_phone,
                r.vehicle_type, r.avg_rating, r.user_id AS rider_user_id
           FROM rider_assignments ra
           LEFT JOIN riders r ON r.id = ra.rider_id
          WHERE ra.order_id = ? AND ra.source = ?
          ORDER BY ra.assigned_at DESC LIMIT 1"
    );
    $assignStmt->execute([$orderId, $source]);
    $assignment = $assignStmt->fetch(PDO::FETCH_ASSOC);

    $isOwner = $ownerId !== false && (int)$ownerId === $sessionUserId;
    $isRider = $assignment && (int)($assignment['rider_user_id'] ?? 0) === $sessionUserId;

    if (!$isAdmin && !$isOwner && !$isRider) {
        // Identical wording whether or not the order exists — the convention
        // includes/api_auth.php already sets, so a caller cannot probe which
        // ids are real.
        trackFail('Not your order.', 403);
    }

    $deliverable = loadDeliverable($dbh, $source, $orderId);
    if (!$deliverable) trackFail('Not your order.', 403);

    // --- the trail ------------------------------------------------------
    // `since` makes a steady-state poll a few hundred bytes instead of
    // re-sending the whole journey every ten seconds.
    $trailSql =
        "SELECT lat, lng, heading_deg, recorded_at
           FROM order_tracking_logs
          WHERE order_id = ? AND source = ?";
    $trailParams = [$orderId, $source];
    if ($since !== '' && strtotime($since) !== false) {
        $trailSql .= " AND recorded_at > ?";
        $trailParams[] = date('Y-m-d H:i:s', strtotime($since));
    }
    $trailSql .= " ORDER BY recorded_at ASC LIMIT 500";

    $trailStmt = $dbh->prepare($trailSql);
    $trailStmt->execute($trailParams);
    $trail = $trailStmt->fetchAll(PDO::FETCH_ASSOC);

    // The latest point overall, not just within the `since` window — a poll
    // that returns no new breadcrumbs still needs somewhere to draw the marker.
    $lastStmt = $dbh->prepare(
        "SELECT lat, lng, heading_deg, speed_mps, recorded_at
           FROM order_tracking_logs
          WHERE order_id = ? AND source = ?
          ORDER BY recorded_at DESC LIMIT 1"
    );
    $lastStmt->execute([$orderId, $source]);
    $last = $lastStmt->fetch(PDO::FETCH_ASSOC);

    $assignmentStatus = $assignment['status'] ?? null;
    $inProgress = in_array($assignmentStatus, ['picked_up', 'on_way'], true);
    $finished = $assignmentStatus === 'delivered'
        || in_array(strtolower((string)$deliverable['status']), ['delivered', 'cancelled', 'refunded'], true);

    // --- reroute if the rider has drifted off the cached route -----------
    // Only once actually en route to the customer ('on_way') — before that,
    // the rider isn't on the pickup->dropoff polyline at all (they're on a
    // different, ungeometried leg travelling TO pickup), so a distance check
    // against it here would be comparing against the wrong thing.
    if ($assignmentStatus === 'on_way' && $last
        && $deliverable['dest_lat'] !== null && $deliverable['dest_lng'] !== null) {
        $rerouted = rerouteIfNeeded(
            $dbh,
            (int)$assignment['id'],
            $assignment['route_polyline'] ?? null,
            $assignment['route_fetched_at'] ?? null,
            (float)$last['lat'], (float)$last['lng'],
            (float)$deliverable['dest_lat'], (float)$deliverable['dest_lng']
        );
        if ($rerouted) {
            // So the response built below serves the corrected route on this
            // same poll, rather than one more cycle of the stale line.
            $assignment['route_polyline'] = $rerouted['polyline'];
            $assignment['route_distance_km'] = $rerouted['distance_km'];
            $assignment['route_duration_min'] = $rerouted['duration_min'];
        }
    }

    // --- ETA -------------------------------------------------------------
    // Straight-line remaining distance over a rolling median speed. Deliberately
    // NOT a Google call: this runs on every poll, and one route lookup per
    // customer per ten seconds would be an expensive way to refine a number
    // that is a guess either way.
    $eta = null;
    $remainingKm = null;
    if ($inProgress && $last && $deliverable['dest_lat'] !== null && $deliverable['dest_lng'] !== null) {
        $remainingKm = calculateDistance(
            (float)$last['lat'], (float)$last['lng'],
            (float)$deliverable['dest_lat'], (float)$deliverable['dest_lng']
        );

        $speedStmt = $dbh->prepare(
            "SELECT speed_mps FROM order_tracking_logs
              WHERE order_id = ? AND source = ? AND speed_mps IS NOT NULL AND speed_mps > 0
              ORDER BY recorded_at DESC LIMIT 5"
        );
        $speedStmt->execute([$orderId, $source]);
        $speeds = array_map('floatval', $speedStmt->fetchAll(PDO::FETCH_COLUMN));

        // 6.9 m/s ~ 25km/h, a fair guess for a boda in Kampala traffic when the
        // device has not reported speed.
        $mps = 6.9;
        if ($speeds) {
            sort($speeds);
            $mps = $speeds[intdiv(count($speeds), 2)];
        }
        // Road distance is longer than the straight line; 1.3 is the usual
        // rule of thumb and keeps the ETA from being cheerfully optimistic.
        $eta = max(2, (int)round(($remainingKm * 1000 * 1.3) / max($mps, 1.0) / 60));
    }

    echo json_encode([
        'success'        => true,
        'order_id'       => $orderId,
        'source'         => $source,
        'status'         => $assignmentStatus,
        'order_status'   => $deliverable['status'],
        'has_rider'      => $assignment !== false && $assignment !== null,
        'rider' => ($assignment && ($inProgress || $isAdmin || $isRider)) ? [
            'name'         => $assignment['rider_name'],
            // Withheld once the delivery is over. See the header.
            'phone'        => $inProgress ? $assignment['rider_phone'] : null,
            'vehicle_type' => $assignment['vehicle_type'],
            'avg_rating'   => $assignment['avg_rating'] !== null ? (float)$assignment['avg_rating'] : null,
        ] : null,
        'position' => $last ? [
            'lat'         => (float)$last['lat'],
            'lng'         => (float)$last['lng'],
            'heading_deg' => $last['heading_deg'] !== null ? (int)$last['heading_deg'] : null,
            'recorded_at' => $last['recorded_at'],
        ] : null,
        'pickup' => [
            'lat'   => isset($deliverable['pickup_lat']) ? (float)$deliverable['pickup_lat'] : null,
            'lng'   => isset($deliverable['pickup_lng']) ? (float)$deliverable['pickup_lng'] : null,
            'label' => $deliverable['pickup_address'] ?: 'AfamFresh',
        ],
        'dropoff' => [
            'lat'   => $deliverable['dest_lat'] !== null ? (float)$deliverable['dest_lat'] : null,
            'lng'   => $deliverable['dest_lng'] !== null ? (float)$deliverable['dest_lng'] : null,
            'label' => $deliverable['delivery_address'] ?: $deliverable['address'],
            'area'  => $deliverable['area'],
        ],
        'route_polyline' => $assignment['route_polyline'] ?? null,
        'route_distance_km' => isset($assignment['route_distance_km'])
            ? (float)$assignment['route_distance_km'] : null,
        'trail' => array_map(fn($p) => [
            'lat' => (float)$p['lat'],
            'lng' => (float)$p['lng'],
            'at'  => $p['recorded_at'],
        ], $trail),
        'eta_minutes' => $eta,
        'distance_remaining_km' => $remainingKm !== null ? round($remainingKm, 2) : null,
        // Server-controlled so the cadence can be changed without an app
        // release — which matters when a release takes 16 minutes and cannot be
        // forced onto an install base. Slow right down once there is nothing
        // left to watch.
        'poll_after_seconds' => $finished ? 0 : ($inProgress ? 10 : 30),
        'finished' => $finished,
    ]);
} catch (PDOException $e) {
    error_log('tracking: ' . $e->getMessage());
    trackFail('Could not load tracking right now.', 500);
}