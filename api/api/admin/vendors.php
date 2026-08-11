<?php
header('Content-Type: application/json');
// config.php already starts the session. Calling it again raised a notice that
// was printed ahead of this file's JSON.
require_once '../../admin/includes/config.php';

if (!isset($_SESSION['admin_logged_in']) || $_SESSION['admin_logged_in'] !== true) {
    http_response_code(401);
    echo json_encode(['success' => false, 'error' => 'Unauthorized']);
    exit;
}

$action = $_GET['action'] ?? '';

if ($action === 'list') {
    $stmt = $dbh->query("SELECT v.*, 
                         (SELECT COUNT(*) FROM vendor_products WHERE vendor_id = v.id) as total_listings
                         FROM vendors v");
    $vendors = $stmt->fetchAll(PDO::FETCH_ASSOC);
    echo json_encode(['success' => true, 'vendors' => $vendors]);
    exit;
}

if ($action === 'toggle_verification' && $_SERVER['REQUEST_METHOD'] === 'POST') {
    $input = json_decode(file_get_contents('php://input'), true);
    $id = intval($input['id'] ?? 0);
    $verified = intval($input['verified'] ?? 0);
    if (!$id) {
        echo json_encode(['success' => false, 'error' => 'Missing vendor ID']);
        exit;
    }
    // Read the prior state first, because the notification below must fire
    // only on the 0 -> 1 transition. rowCount() cannot answer that: the
    // statement rewrites verification_date to NOW(), so re-verifying an
    // already-verified vendor still counts as a changed row.
    $prev = $dbh->prepare("SELECT is_verified, user_id FROM vendors WHERE id = ?");
    $prev->execute([$id]);
    $before = $prev->fetch(PDO::FETCH_ASSOC);
    if (!$before) {
        echo json_encode(['success' => false, 'error' => 'No such vendor.']);
        exit;
    }
    $wasVerified = (int)$before['is_verified'] === 1;

    // Was is_verified alone, so a vendor verified through this button ended up
    // verified with a NULL verification_date and no way to tell when it
    // happened. Cleared again on revoke, so the column describes the current
    // state rather than the last time someone happened to be verified.
    $stmt = $dbh->prepare(
        "UPDATE vendors
            SET is_verified = ?,
                verification_date = CASE WHEN ? = 1 THEN NOW() ELSE NULL END
          WHERE id = ?"
    );
    $stmt->execute([$verified, $verified, $id]);

    $notified = false;
    if ($verified === 1 && !$wasVerified) {
        require_once __DIR__ . '/../../includes/vendor-notification-helper.php';
        $vendorUserId = (int)$before['user_id'];
        if ($vendorUserId > 0) {
            // Not fatal: the verification itself succeeded, and failing the
            // request here would tell the admin nothing happened when it did.
            $notified = notifyVendorVerified($vendorUserId);
            if (!$notified) {
                error_log("Vendor $id verified but the notification could not be written.");
            }
        }
    }

    echo json_encode(['success' => true, 'notified' => $notified]);
    exit;
}

echo json_encode(['success' => false, 'error' => 'Invalid action']);
?>