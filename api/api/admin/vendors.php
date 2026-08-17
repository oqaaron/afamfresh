<?php
header('Content-Type: application/json');
// config.php already starts the session. Calling it again raised a notice that
// was printed ahead of this file's JSON.
require_once '../../admin/includes/config.php';
require_once __DIR__ . '/../../includes/csrf.php';
require_once __DIR__ . '/../../includes/admin_permissions.php';
require_once __DIR__ . '/../../includes/admin_audit.php';

requireAdminPermissionApi('vendors.manage');

$action = $_GET['action'] ?? '';

if ($action === 'list') {
    $stmt = $dbh->query("SELECT v.*,
                         (SELECT COUNT(*) FROM vendor_products WHERE vendor_id = v.id) as total_listings
                         FROM vendors v");
    $vendors = $stmt->fetchAll(PDO::FETCH_ASSOC);
    echo json_encode(['success' => true, 'vendors' => $vendors]);
    exit;
}

/**
 * The vendor money ledger, per transaction.
 *
 * vendor-payouts.php shows payout REQUESTS and an unpaid total per seller,
 * which answers "what do we owe?" but never "what was this made of?". There
 * was no view of vendor_earnings itself anywhere in the admin panel, so a
 * seller querying their balance could not be answered without a database
 * client.
 *
 * One row per delivered, paid order: what it sold for, what the platform kept,
 * what the seller is owed, and whether it has been paid out yet.
 */
if ($action === 'earnings') {
    $vendorId = (int)($_GET['vendor_id'] ?? 0);
    $limit    = min(200, max(1, (int)($_GET['limit'] ?? 100)));

    $sql = "SELECT ve.id, ve.vendor_id, ve.order_id, ve.source,
                   ve.order_amount, ve.commission_amount, ve.net_earnings,
                   ve.is_paid, ve.paid_at, ve.created_at,
                   v.business_name, v.business_type, v.commission_rate,
                   i.name AS product_name
              FROM vendor_earnings ve
              JOIN vendors v ON v.id = ve.vendor_id
              -- LEFT, and via the listing: an earning outlives the listing it
              -- came from, and a deleted listing must not drop the money row
              -- out of the ledger.
              LEFT JOIN Bulk_orders so ON so.id = ve.order_id AND ve.source = 'Bulk'
              LEFT JOIN Bulk_listings sl ON sl.id = so.listing_id
              LEFT JOIN items i ON i.id = sl.product_id";
    $params = [];
    if ($vendorId > 0) {
        $sql .= " WHERE ve.vendor_id = ?";
        $params[] = $vendorId;
    }
    $sql .= " ORDER BY ve.created_at DESC, ve.id DESC LIMIT " . $limit;

    $stmt = $dbh->prepare($sql);
    $stmt->execute($params);
    $rows = $stmt->fetchAll(PDO::FETCH_ASSOC);

    // Totals over the SAME filter, computed in SQL rather than from $rows —
    // the LIMIT above would otherwise make them a total of the first page.
    $totalSql = "SELECT COALESCE(SUM(order_amount), 0)      AS gross,
                        COALESCE(SUM(commission_amount), 0) AS commission,
                        COALESCE(SUM(net_earnings), 0)      AS net,
                        COALESCE(SUM(CASE WHEN is_paid = 0 THEN net_earnings ELSE 0 END), 0) AS unpaid,
                        COUNT(*) AS transactions
                   FROM vendor_earnings"
              . ($vendorId > 0 ? " WHERE vendor_id = ?" : "");
    $totalStmt = $dbh->prepare($totalSql);
    $totalStmt->execute($vendorId > 0 ? [$vendorId] : []);

    echo json_encode([
        'success'  => true,
        'earnings' => $rows,
        'totals'   => $totalStmt->fetch(PDO::FETCH_ASSOC),
    ]);
    exit;
}

if ($action === 'toggle_verification' && $_SERVER['REQUEST_METHOD'] === 'POST') {
    verifyCsrfHeader();
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

    logAdminAction(
        $dbh, $verified === 1 ? 'vendor.verified' : 'vendor.unverified', 'vendor', (string)$id,
        $verified === 1 ? 'Verified' : 'Verification revoked'
    );

    echo json_encode(['success' => true, 'notified' => $notified]);
    exit;
}

if ($action === 'set_business_type' && $_SERVER['REQUEST_METHOD'] === 'POST') {
    verifyCsrfHeader();
    $input = json_decode(file_get_contents('php://input'), true);
    $id = intval($input['id'] ?? 0);
    $type = trim((string)($input['business_type'] ?? ''));

    // The only way to change this, by design. api/vendor-profile.php used to
    // accept it on a seller's own profile save, which let any verified vendor
    // hand themselves 'wholesaler' — and with it the right to post a listing
    // with no discount and no expiry — by choosing an option in a dropdown.
    // Taking it away there left no way to change it at all; this is that way.
    $allowed = ['farmer', 'market_vendor', 'wholesaler'];
    if (!$id || !in_array($type, $allowed, true)) {
        echo json_encode(['success' => false, 'error' => 'Pick a valid business type.']);
        exit;
    }

    $prev = $dbh->prepare("SELECT business_type FROM vendors WHERE id = ?");
    $prev->execute([$id]);
    $before = $prev->fetchColumn();
    if ($before === false) {
        echo json_encode(['success' => false, 'error' => 'No such vendor.']);
        exit;
    }
    if ($before === $type) {
        echo json_encode(['success' => true, 'unchanged' => true]);
        exit;
    }

    $dbh->prepare("UPDATE vendors SET business_type = ? WHERE id = ?")->execute([$type, $id]);

    // Existing listings are deliberately left alone. Switching a seller to
    // wholesaler does not retrospectively make their surplus listings
    // wholesale ones, and switching away does not strip a wholesale listing of
    // the price it is already selling at — either would rewrite live offers
    // customers may be part-way through ordering. Only what they post NEXT
    // follows the new type.
    logAdminAction(
        $dbh, 'vendor.business_type_changed', 'vendor', (string)$id,
        "Business type $before -> $type"
    );

    echo json_encode(['success' => true, 'business_type' => $type]);
    exit;
}

echo json_encode(['success' => false, 'error' => 'Invalid action']);
?>