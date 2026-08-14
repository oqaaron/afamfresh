<?php
header('Content-Type: application/json');
require_once '../admin/includes/config.php';

require_once __DIR__ . '/../includes/api_auth.php';

$method = $_SERVER['REQUEST_METHOD'];

// Was: trust $_GET['user_id']. Anyone could read any vendor's
// records by changing the number. See includes/api_auth.php.
$user_id = requireOwnUserId($_GET['user_id'] ?? 0);

try {
    // Get vendor_id from user_id
    $vendorStmt = $dbh->prepare("SELECT id FROM vendors WHERE user_id = ?");
    $vendorStmt->execute([$user_id]);
    $vendor = $vendorStmt->fetch(PDO::FETCH_ASSOC);
    
    if (!$vendor) {
        echo json_encode(['error' => 'Vendor not found']);
        exit;
    }
    
    $vendor_id = $vendor['id'];
    
    if ($method === 'GET') {
        // Fetch earnings with pagination
        $status = isset($_GET['status']) ? trim($_GET['status']) : 'all';
        $limit = isset($_GET['limit']) ? intval($_GET['limit']) : 20;
        $offset = isset($_GET['offset']) ? intval($_GET['offset']) : 0;
        
        $whereClause = "WHERE vendor_id = ?";
        $params = [$vendor_id];
        
        if ($status === 'paid') {
            $whereClause .= " AND is_paid = TRUE";
        } elseif ($status === 'pending') {
            $whereClause .= " AND is_paid = FALSE";
        }
        
        // Fetch earnings.
        //
        // The join is scoped to source='order'. vendor_earnings.order_id points
        // at `orders` or at `Bulk_orders` depending on `source`, and the two
        // id spaces overlap — unscoped, a Bulk earning joins to whichever
        // shop order happens to share its number and reports that order as the
        // source of the money.
        //
        // The product name is filled in separately for Bulk rows, which is
        // every row in practice: the shop does not credit vendors at all.
        $stmt = $dbh->prepare("
            SELECT ve.*, o.orderid
            FROM vendor_earnings ve
            LEFT JOIN orders o ON ve.order_id = o.orderid AND ve.source = 'order'
            $whereClause
            ORDER BY ve.created_at DESC
            LIMIT ? OFFSET ?
        ");
        $params[] = $limit;
        $params[] = $offset;
        $stmt->execute($params);
        $earnings = $stmt->fetchAll(PDO::FETCH_ASSOC);

        // Name what each credit was for. A ledger of bare amounts and dates is
        // not something a vendor can check against their own records.
        $BulkIds = array_values(array_unique(array_map(
            fn($e) => (int)$e['order_id'],
            array_filter($earnings, fn($e) => ($e['source'] ?? 'order') === 'Bulk')
        )));
        if ($BulkIds) {
            $in = implode(',', array_fill(0, count($BulkIds), '?'));
            $namesStmt = $dbh->prepare(
                "SELECT so.id, i.name
                   FROM Bulk_orders so
                   JOIN Bulk_listings sl ON sl.id = so.listing_id
                   JOIN items i ON i.id = sl.product_id
                  WHERE so.id IN ($in)"
            );
            $namesStmt->execute($BulkIds);
            $names = [];
            foreach ($namesStmt->fetchAll(PDO::FETCH_ASSOC) as $n) {
                $names[(int)$n['id']] = $n['name'];
            }
            foreach ($earnings as &$e) {
                if (($e['source'] ?? 'order') === 'Bulk') {
                    $e['product_name'] = $names[(int)$e['order_id']] ?? null;
                }
            }
            unset($e);
        }
        
        // Fetch summary
        $summaryStmt = $dbh->prepare("
            SELECT 
                COUNT(*) as total_orders,
                SUM(order_amount) as total_revenue,
                SUM(commission_amount) as total_commission,
                SUM(net_earnings) as total_net_earnings,
                SUM(CASE WHEN is_paid = TRUE THEN net_earnings ELSE 0 END) as paid_earnings,
                SUM(CASE WHEN is_paid = FALSE THEN net_earnings ELSE 0 END) as pending_earnings
            FROM vendor_earnings
            WHERE vendor_id = ?
        ");
        $summaryStmt->execute([$vendor_id]);
        $summary = $summaryStmt->fetch(PDO::FETCH_ASSOC);
        
        // The vendor's own withdrawal requests, newest first.
        //
        // Returned alongside the ledger rather than from a second endpoint: the
        // screen needs both to say anything useful. "You have UGX 288,000
        // available" is misleading on its own when a request for that exact
        // amount is already sitting with an admin, and a vendor who cannot see
        // the request they made will simply make it again.
        $payoutsStmt = $dbh->prepare(
            "SELECT id, amount, status, requested_at, processed_at, notes
               FROM vendor_payout_requests
              WHERE vendor_id = ?
              ORDER BY id DESC
              LIMIT 20"
        );
        $payoutsStmt->execute([$vendor_id]);
        $payouts = $payoutsStmt->fetchAll(PDO::FETCH_ASSOC);

        echo json_encode([
            'success' => true,
            'earnings' => $earnings,
            'summary' => $summary,
            'payouts' => $payouts,
            // True when a request is already with an admin. The app disables
            // the withdraw button on this rather than working it out from the
            // list, so the rule lives in one place — here, next to the check
            // that enforces it below.
            'has_open_request' => (bool)array_filter(
                $payouts,
                fn($p) => in_array($p['status'], ['pending', 'approved'], true)
            ),
        ]);

    } elseif ($method === 'POST' && ($_GET['action'] ?? '') === 'request_payout') {
        // =============================================================
        // The vendor asks to withdraw. An admin approves and dispatches.
        // =============================================================
        // The amount is the unpaid total read server-side right now and is
        // never taken from the request — the same rule as the rider payout in
        // api/rider.php, and for the same reason: a client-supplied amount is
        // a client-supplied withdrawal.
        $availableStmt = $dbh->prepare(
            "SELECT COALESCE(SUM(net_earnings), 0) FROM vendor_earnings
              WHERE vendor_id = ? AND is_paid = 0"
        );
        $availableStmt->execute([$vendor_id]);
        $available = round((float)$availableStmt->fetchColumn(), 2);

        if ($available <= 0) {
            echo json_encode(['success' => false, 'error' => 'You have nothing available to withdraw right now.']);
            exit;
        }

        // One at a time. Two open requests would each claim the same balance,
        // and approving both pays it out twice.
        $pendingStmt = $dbh->prepare(
            "SELECT id FROM vendor_payout_requests WHERE vendor_id = ? AND status IN ('pending','approved')"
        );
        $pendingStmt->execute([$vendor_id]);
        if ($pendingStmt->fetchColumn()) {
            echo json_encode(['success' => false, 'error' => 'You already have a withdrawal request being processed.']);
            exit;
        }

        $dbh->prepare(
            "INSERT INTO vendor_payout_requests (vendor_id, amount, status) VALUES (?, ?, 'pending')"
        )->execute([$vendor_id, $available]);

        echo json_encode([
            'success' => true,
            'amount'  => $available,
            'message' => 'Withdrawal requested. An administrator will review it.',
        ]);

    } elseif ($method === 'POST') {
        // Marking earnings paid is an ADMIN action.
        //
        // This branch was reachable by the vendor whose earnings they are:
        // vendor_id came from their own session, so a vendor could flip their
        // own ledger to paid without any money moving. Payment is dispatched
        // by an admin, so only an admin may record it.
        if (!isset($_SESSION['admin_logged_in']) || $_SESSION['admin_logged_in'] !== true) {
            http_response_code(403);
            echo json_encode(['success' => false, 'error' => 'Only an administrator can mark earnings as paid.']);
            exit;
        }

        $input = json_decode(file_get_contents('php://input'), true);

        $earning_ids = $input['earning_ids'] ?? [];

        if (empty($earning_ids) || !is_array($earning_ids)) {
            echo json_encode(['error' => 'earning_ids array is required']);
            exit;
        }
        
        // Mark as paid
        $placeholders = str_repeat('?,', count($earning_ids) - 1) . '?';
        $updateStmt = $dbh->prepare("
            UPDATE vendor_earnings 
            SET is_paid = TRUE, paid_at = NOW()
            WHERE id IN ($placeholders) AND vendor_id = ? AND is_paid = FALSE
        ");
        
        $params = array_merge($earning_ids, [$vendor_id]);
        $updateStmt->execute($params);
        
        $affected = $updateStmt->rowCount();
        
        echo json_encode([
            'success' => true,
            'message' => "$affected earnings marked as paid"
        ]);
        
    } else {
        echo json_encode(['error' => 'Invalid request method']);
    }
    
} catch (PDOException $e) {
    echo json_encode(['error' => 'Database error: ' . $e->getMessage()]);
}
?>
