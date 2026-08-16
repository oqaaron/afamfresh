<?php
// =============================================================
// api/admin/Bulk-approval.php
//
// Everything on the admin dashboard's "Pending Bulk Approvals" card: new
// listings awaiting review, vendor-requested order cancellations awaiting
// approval, and refunds Pesapal has been asked for but not yet confirmed
// complete. One card, one place — an admin working this queue should not
// have to remember which of three other pages a given item lives on.
//
// Actual money-moving/state-changing logic lives in includes/Bulk_payment.php
// (cancelBulkOrder()) and admin/Bulk-orders.php's server-rendered page has
// the same actions too — this file is the thin JSON layer the dashboard's
// fetch()-based card calls, not a second copy of that logic.
// =============================================================
header('Content-Type: application/json');
// config.php already starts the session; calling it again raised a notice
// that was printed ahead of this file's JSON.
require_once '../../admin/includes/config.php';
require_once __DIR__ . '/../../includes/csrf.php';
require_once __DIR__ . '/../../includes/Bulk_payment.php';
require_once __DIR__ . '/../../includes/notifications.php';
require_once __DIR__ . '/../../includes/admin_permissions.php';
require_once __DIR__ . '/../../includes/admin_audit.php';

// Base check only -- this file mixes Bulk.manage_listings actions (the
// listings queue) with Bulk.manage_refunds actions (cancellations/refunds),
// so each action below checks its own specific permission, not one blanket
// permission here.
if (!isset($_SESSION['admin_logged_in']) || $_SESSION['admin_logged_in'] !== true) {
    http_response_code(401);
    echo json_encode(['success' => false, 'error' => 'Unauthorized']);
    exit;
}

$action = $_GET['action'] ?? '';

// -------------------------------------------------------------
// Listings
// -------------------------------------------------------------

if ($action === 'pending') {
    requireAdminPermissionApi('Bulk.manage_listings');
    $stmt = $dbh->query(
        "SELECT sl.*, v.business_name, v.user_id AS vendor_user_id, i.name as product_name
           FROM Bulk_listings sl
           LEFT JOIN vendors v ON sl.vendor_id = v.id
           LEFT JOIN items i ON sl.product_id = i.id
          WHERE sl.status = 'pending'
          ORDER BY sl.created_at ASC"
    );
    $listings = $stmt->fetchAll(PDO::FETCH_ASSOC);
    echo json_encode(['success' => true, 'listings' => $listings]);
    exit;
}

if (in_array($action, ['approve', 'reject'], true) && $_SERVER['REQUEST_METHOD'] === 'POST') {
    requireAdminPermissionApi('Bulk.manage_listings');
    verifyCsrfHeader();
    $input = json_decode(file_get_contents('php://input'), true);
    $id = intval($input['id'] ?? 0);
    if (!$id) {
        echo json_encode(['success' => false, 'error' => 'Missing listing ID']);
        exit;
    }

    $stmt = $dbh->prepare(
        "SELECT sl.*, v.user_id AS vendor_user_id, i.name AS product_name
           FROM Bulk_listings sl
           LEFT JOIN vendors v ON sl.vendor_id = v.id
           LEFT JOIN items i ON sl.product_id = i.id
          WHERE sl.id = ?"
    );
    $stmt->execute([$id]);
    $listing = $stmt->fetch(PDO::FETCH_ASSOC);
    if (!$listing) {
        echo json_encode(['success' => false, 'error' => 'No such listing.']);
        exit;
    }

    $newStatus = $action === 'approve' ? 'approved' : 'rejected';
    $dbh->prepare("UPDATE Bulk_listings SET status = ? WHERE id = ?")->execute([$newStatus, $id]);

    // Was a silent status flip with no notification — a vendor whose
    // listing this card approved or rejected never found out except by
    // checking the app themselves. Same message shape as
    // admin/Bulk-listings.php's own full edit form, so a vendor sees the
    // same wording regardless of which admin page acted on their listing.
    if ($listing['vendor_user_id']) {
        $productName = $listing['product_name'] ?: 'your Bulk listing';
        if ($action === 'approve') {
            addNotification(
                (int)$listing['vendor_user_id'],
                'Listing approved: ' . $productName,
                'Your Bulk listing for "' . $productName . '" has been approved and is now on sale.',
                'system', null, ['push', 'email']
            );
        } else {
            addNotification(
                (int)$listing['vendor_user_id'],
                'Listing not approved: ' . $productName,
                'Your Bulk listing for "' . $productName . '" was not approved. Contact support for details.',
                'system', null, ['push', 'email']
            );
        }
    }

    logAdminAction($dbh, 'Bulk_listing.' . $newStatus, 'Bulk_listing', (string)$id,
        ($action === 'approve' ? 'Approved' : 'Rejected') . ' "' . ($listing['product_name'] ?? '') . '"');

    echo json_encode(['success' => true]);
    exit;
}

// -------------------------------------------------------------
// Vendor-requested order cancellations
// -------------------------------------------------------------

if ($action === 'pending_cancellations') {
    requireAdminPermissionApi('Bulk.manage_refunds');
    $stmt = $dbh->query(
        "SELECT so.id, so.cancellation_reason, so.cancellation_requested_at,
                so.total_price, so.delivery_fee, so.payment_status,
                i.name AS product_name, v.business_name
           FROM Bulk_orders so
           JOIN Bulk_listings sl ON sl.id = so.listing_id
           JOIN items i ON i.id = sl.product_id
           JOIN vendors v ON v.id = sl.vendor_id
          WHERE so.status = 'cancellation_requested'
          ORDER BY so.cancellation_requested_at ASC"
    );
    echo json_encode(['success' => true, 'cancellations' => $stmt->fetchAll(PDO::FETCH_ASSOC)]);
    exit;
}

if ($action === 'approve_cancellation' && $_SERVER['REQUEST_METHOD'] === 'POST') {
    requireAdminPermissionApi('Bulk.manage_refunds');
    verifyCsrfHeader();
    $input = json_decode(file_get_contents('php://input'), true);
    $id = intval($input['id'] ?? 0);
    if (!$id) {
        echo json_encode(['success' => false, 'error' => 'Missing order ID']);
        exit;
    }

    $orderStmt = $dbh->prepare("SELECT * FROM Bulk_orders WHERE id = ?");
    $orderStmt->execute([$id]);
    $order = $orderStmt->fetch(PDO::FETCH_ASSOC);
    if (!$order || $order['status'] !== 'cancellation_requested') {
        echo json_encode(['success' => false, 'error' => 'That order has no pending cancellation request.']);
        exit;
    }

    $reason = trim((string)($order['cancellation_reason'] ?? '')) ?: 'Cancellation requested by vendor.';
    // This IS the approval — same underlying function as an admin's own
    // direct cancel on admin/Bulk-orders.php, executed for real.
    $result = cancelBulkOrder($dbh, $id, $reason, 'admin', (int)($_SESSION['admin_id'] ?? 0), (string)($_SESSION['admin_name'] ?? 'afamfresh-admin'));

    if (!$result['ok']) {
        echo json_encode(['success' => false, 'error' => $result['error']]);
        exit;
    }

    addNotification(
        (int)$order['user_id'],
        'Order cancelled',
        'Your Bulk order #' . $id . ' was cancelled. Reason: ' . $reason
            . ($result['refund_attempted'] ? ' Any payment made will be refunded.' : ''),
        'order', null, ['push', 'email']
    );
    logAdminAction($dbh, 'Bulk_order.cancellation_approved', 'Bulk_order', (string)$id, "Approved cancellation: $reason");

    echo json_encode([
        'success' => true,
        'refund_requested' => $result['refund_requested'] ?? false,
    ]);
    exit;
}

if ($action === 'deny_cancellation' && $_SERVER['REQUEST_METHOD'] === 'POST') {
    requireAdminPermissionApi('Bulk.manage_refunds');
    verifyCsrfHeader();
    $input = json_decode(file_get_contents('php://input'), true);
    $id = intval($input['id'] ?? 0);
    $reason = trim((string)($input['reason'] ?? ''));
    if (!$id) {
        echo json_encode(['success' => false, 'error' => 'Missing order ID']);
        exit;
    }
    if ($reason === '') {
        echo json_encode(['success' => false, 'error' => 'Give a reason — the vendor sees it.']);
        exit;
    }

    $orderStmt = $dbh->prepare("SELECT * FROM Bulk_orders WHERE id = ?");
    $orderStmt->execute([$id]);
    $order = $orderStmt->fetch(PDO::FETCH_ASSOC);
    if (!$order || $order['status'] !== 'cancellation_requested') {
        echo json_encode(['success' => false, 'error' => 'That order has no pending cancellation request.']);
        exit;
    }

    $dbh->prepare(
        "UPDATE Bulk_orders
            SET status = COALESCE(status_before_cancellation, 'confirmed'),
                status_before_cancellation = NULL, cancellation_reason = NULL,
                updated_at = NOW()
          WHERE id = ? AND status = 'cancellation_requested'"
    )->execute([$id]);

    if (!empty($order['cancellation_requested_by'])) {
        addNotification(
            (int)$order['cancellation_requested_by'],
            'Cancellation request denied',
            'Your request to cancel Bulk order #' . $id . ' was not approved. Reason: ' . $reason,
            'system', null, ['push', 'email']
        );
    }
    logAdminAction($dbh, 'Bulk_order.cancellation_denied', 'Bulk_order', (string)$id, "Denied: $reason");

    echo json_encode(['success' => true]);
    exit;
}

// -------------------------------------------------------------
// Refunds awaiting confirmation
// -------------------------------------------------------------

if ($action === 'pending_refunds') {
    requireAdminPermissionApi('Bulk.manage_refunds');
    $stmt = $dbh->query(
        "SELECT so.id, so.total_price, so.delivery_fee, so.updated_at,
                i.name AS product_name, v.business_name
           FROM Bulk_orders so
           JOIN Bulk_listings sl ON sl.id = so.listing_id
           JOIN items i ON i.id = sl.product_id
           JOIN vendors v ON v.id = sl.vendor_id
          WHERE so.payment_status = 'refund_requested'
          ORDER BY so.updated_at ASC"
    );
    echo json_encode(['success' => true, 'refunds' => $stmt->fetchAll(PDO::FETCH_ASSOC)]);
    exit;
}

if ($action === 'confirm_refund' && $_SERVER['REQUEST_METHOD'] === 'POST') {
    requireAdminPermissionApi('Bulk.manage_refunds');
    verifyCsrfHeader();
    $input = json_decode(file_get_contents('php://input'), true);
    $id = intval($input['id'] ?? 0);
    if (!$id) {
        echo json_encode(['success' => false, 'error' => 'Missing order ID']);
        exit;
    }

    $orderStmt = $dbh->prepare("SELECT * FROM Bulk_orders WHERE id = ?");
    $orderStmt->execute([$id]);
    $order = $orderStmt->fetch(PDO::FETCH_ASSOC);
    if (!$order || $order['payment_status'] !== 'refund_requested') {
        echo json_encode(['success' => false, 'error' => 'That order has no refund awaiting confirmation.']);
        exit;
    }

    // The only place status ever reaches 'refunded' — always a deliberate
    // admin confirmation after checking Pesapal's dashboard, never automatic.
    $dbh->prepare(
        "UPDATE Bulk_orders SET status = 'refunded', payment_status = 'refunded', updated_at = NOW() WHERE id = ?"
    )->execute([$id]);

    recordPaymentEvent($dbh, 'Bulk', $id, 'refund_confirmed', [
        'from_status' => 'refund_requested',
        'to_status'   => 'refunded',
        'actor_type'  => 'admin',
        'actor_id'    => (int)($_SESSION['admin_id'] ?? 0),
    ]);

    addNotification(
        (int)$order['user_id'],
        'Refund complete',
        'Your refund for Bulk order #' . $id . ' has been completed.',
        'order', null, ['push', 'email']
    );
    logAdminAction($dbh, 'Bulk_order.refund_confirmed', 'Bulk_order', (string)$id, 'Refund confirmed complete');

    echo json_encode(['success' => true]);
    exit;
}

echo json_encode(['success' => false, 'error' => 'Invalid action']);
