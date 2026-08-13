<?php
session_start();
require_once '../admin/includes/config.php';
header('Content-Type: application/json');

$action = $_GET['action'] ?? '';

if ($action == 'update') {
    $input = json_decode(file_get_contents('php://input'), true);
    $lat = floatval($input['lat'] ?? 0);
    $lng = floatval($input['lng'] ?? 0);
    
    if (isset($_SESSION['user_id'])) {
        $user_id = $_SESSION['user_id'];
        $stmt = $dbh->prepare("UPDATE users SET last_lat = ?, last_lng = ?, last_location_update = NOW() WHERE id = ?");
        $stmt->execute([$lat, $lng, $user_id]);

        // If this account is also a rider, keep the rider row in step.
        //
        // The branch that used to be here keyed on $_SESSION['rider_id'],
        // which NOTHING in the codebase has ever set, so it could never run.
        // Riders are ordinary app accounts linked by riders.user_id — see
        // api/rider.php, whose ?action=location is the fuller version of this
        // (it also breadcrumbs order_tracking_logs).
        $riderStmt = $dbh->prepare("SELECT id FROM riders WHERE user_id = ?");
        $riderStmt->execute([$user_id]);
        $rider_id = $riderStmt->fetchColumn();
        if ($rider_id) {
            $dbh->prepare(
                "UPDATE riders SET current_lat = ?, current_lng = ?, last_location_update = NOW() WHERE id = ?"
            )->execute([$lat, $lng, $rider_id]);
        }

        echo json_encode(['success' => true, 'rider_id' => $rider_id ? (int)$rider_id : null]);
    } else {
        echo json_encode(['success' => false, 'error' => 'Not logged in']);
    }
    
} elseif ($action == 'rider') {
    // REMOVED. This took a rider id straight from the query string and returned
    // that rider's live position to any signed-in user — no ownership check, no
    // relationship to an order, nothing. Anyone with an account could have
    // watched any courier move around the city all day by incrementing a number.
    //
    // No Kotlin file ever called it, so nothing breaks by refusing it.
    //
    // The replacement is api/tracking.php, which resolves permission from the
    // ORDER: its customer, its rider, or an admin — and withholds the rider's
    // phone number once the delivery is over.
    http_response_code(410);
    echo json_encode([
        'success' => false,
        'error' => 'This endpoint has been removed. Use tracking.php?order_id=&source=.',
    ]);

} else {
    echo json_encode(['success' => false, 'error' => 'Invalid action']);
}
