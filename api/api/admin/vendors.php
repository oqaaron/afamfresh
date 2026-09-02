<?php
// api/admin/vendors.php — Admin merchant management & transaction ledger
declare(strict_types=1);

header('Content-Type: application/json');

require_once '../../admin/includes/config.php';
require_once __DIR__ . '/../../includes/csrf.php';
require_once __DIR__ . '/../../includes/admin_permissions.php';
require_once __DIR__ . '/../../includes/admin_audit.php';

requireAdminPermissionApi('vendors.manage');

$action = $_GET['action'] ?? '';

/**
 * ACTION: LIST
 * Returns all registered merchants/vendors with listing counts and commission tiers.
 */
if ($action === 'list') {
    // Check if merchants table exists, otherwise fall back to vendors
    $hasMerchantsTable = false;
    try {
        $test = $dbh->query("SELECT 1 FROM merchants LIMIT 1");
        $hasMerchantsTable = true;
    } catch (Throwable $e) {
        $hasMerchantsTable = false;
    }

    if ($hasMerchantsTable) {
        $stmt = $dbh->query("
            SELECT m.id, m.user_id, m.name AS business_name, m.name, m.email, m.phone,
                   m.merchant_type, m.merchant_type AS business_type,
                   m.commission_rate, m.min_payout_threshold,
                   m.area, m.address, m.rating, m.is_active AS is_verified,
                   (SELECT COUNT(*) FROM items WHERE merchant_id = m.id OR vendor_id = m.user_id) AS total_listings
            FROM merchants m
            ORDER BY m.id DESC
        ");
        $merchants = $stmt->fetchAll(PDO::FETCH_ASSOC);
    } else {
        $stmt = $dbh->query("
            SELECT v.*,
                   COALESCE(v.business_type, 'vendor') AS merchant_type,
                   0.100 AS commission_rate,
                   5000.00 AS min_payout_threshold,
                   (SELECT COUNT(*) FROM vendor_products WHERE vendor_id = v.id) AS total_listings
            FROM vendors v
            ORDER BY v.id DESC
        ");
        $merchants = $stmt->fetchAll(PDO::FETCH_ASSOC);
    }

    echo json_encode(['success' => true, 'vendors' => $merchants, 'merchants' => $merchants]);
    exit;
}

/**
 * ACTION: EARNINGS
 * Ledger breakdown per delivered order transaction.
 */
if ($action === 'earnings') {
    $vendorId = (int)($_GET['vendor_id'] ?? 0);
    $limit    = min(200, max(1, (int)($_GET['limit'] ?? 100)));

    // Check if the unified wallet_transactions table exists for the ledger
    $hasWalletTx = false;
    try {
        $dbh->query("SELECT 1 FROM wallet_transactions LIMIT 1");
        $hasWalletTx = true;
    } catch (Throwable $e) {
        $hasWalletTx = false;
    }

    if ($hasWalletTx) {
        $sql = "
            SELECT wt.id, wt.order_id, wt.user_id AS vendor_id,
                   'Store' AS source,
                   wt.amount AS net_earnings,
                   wt.direction, wt.balance_after, wt.created_at, wt.notes,
                   1 AS is_paid,
                   COALESCE(m.name, CONCAT(u.fname, ' ', u.lname), 'Merchant') AS business_name,
                   COALESCE(m.merchant_type, u.current_role, 'vendor') AS merchant_type,
                   COALESCE(m.merchant_type, u.current_role, 'vendor') AS business_type,
                   COALESCE(m.commission_rate * 100, 10.0) AS commission_rate,
                   ROUND(wt.amount / (1 - COALESCE(m.commission_rate, 0.10)), 0) AS order_amount,
                   ROUND((wt.amount / (1 - COALESCE(m.commission_rate, 0.10))) * COALESCE(m.commission_rate, 0.10), 0) AS commission_amount,
                   o.status AS order_status
            FROM wallet_transactions wt
            LEFT JOIN users u ON u.id = wt.user_id
            LEFT JOIN merchants m ON (m.user_id = wt.user_id OR m.id = wt.user_id)
            LEFT JOIN orders o ON o.orderid = wt.order_id
            WHERE wt.user_type = 'vendor' AND wt.transaction_type IN ('vendor_sale', 'merchant_sale')
        ";
        $params = [];
        if ($vendorId > 0) {
            $sql .= " AND (wt.user_id = ? OR m.id = ?)";
            $params[] = $vendorId;
            $params[] = $vendorId;
        }
        $sql .= " ORDER BY wt.id DESC LIMIT " . $limit;

        $stmt = $dbh->prepare($sql);
        $stmt->execute($params);
        $rows = $stmt->fetchAll(PDO::FETCH_ASSOC);

        // Summaries
        $totalSql = "
            SELECT COALESCE(SUM(ROUND(wt.amount / (1 - COALESCE(m.commission_rate, 0.10)), 0)), 0) AS gross,
                   COALESCE(SUM(ROUND((wt.amount / (1 - COALESCE(m.commission_rate, 0.10))) * COALESCE(m.commission_rate, 0.10), 0)), 0) AS commission,
                   COALESCE(SUM(wt.amount), 0) AS net,
                   COALESCE(SUM(uw.current_balance), 0) AS unpaid,
                   COUNT(wt.id) AS transactions
            FROM wallet_transactions wt
            LEFT JOIN merchants m ON (m.user_id = wt.user_id OR m.id = wt.user_id)
            LEFT JOIN user_wallets uw ON uw.user_id = wt.user_id AND uw.user_type = 'vendor'
            WHERE wt.user_type = 'vendor' AND wt.transaction_type IN ('vendor_sale', 'merchant_sale')
        ";
        $totParams = [];
        if ($vendorId > 0) {
            $totalSql .= " AND (wt.user_id = ? OR m.id = ?)";
            $totParams = [$vendorId, $vendorId];
        }
        $tStmt = $dbh->prepare($totalSql);
        $tStmt->execute($totParams);
        $totals = $tStmt->fetch(PDO::FETCH_ASSOC);

        echo json_encode([
            'success'  => true,
            'earnings' => $rows,
            'totals'   => $totals
        ]);
        exit;
    }

    // Fallback legacy vendor_earnings ledger
    $sql = "SELECT ve.id, ve.vendor_id, ve.order_id, ve.source,
                   ve.order_amount, ve.commission_amount, ve.net_earnings,
                   ve.is_paid, ve.paid_at, ve.created_at,
                   v.business_name, v.business_type, v.commission_rate,
                   i.name AS product_name
              FROM vendor_earnings ve
              JOIN vendors v ON v.id = ve.vendor_id
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

/**
 * ACTION: TOGGLE_VERIFICATION
 * Updates approval/verification status for a merchant.
 */
if ($action === 'toggle_verification' && $_SERVER['REQUEST_METHOD'] === 'POST') {
    verifyCsrfHeader();
    $input = json_decode(file_get_contents('php://input'), true);
    $id = intval($input['id'] ?? 0);
    $verified = intval($input['verified'] ?? 0);
    
    if (!$id) {
        echo json_encode(['success' => false, 'error' => 'Missing merchant/vendor ID']);
        exit;
    }

    // Check if entry exists in merchants table
    $isMerchantRow = false;
    $prev = $dbh->prepare("SELECT is_active, user_id FROM merchants WHERE id = ?");
    $prev->execute([$id]);
    $before = $prev->fetch(PDO::FETCH_ASSOC);

    if ($before) {
        $isMerchantRow = true;
        $wasVerified = (int)$before['is_active'] === 1;
        $vendorUserId = (int)($before['user_id'] ?? 0);

        $dbh->prepare("UPDATE merchants SET is_active = ? WHERE id = ?")->execute([$verified, $id]);
        if ($vendorUserId > 0) {
            $dbh->prepare("UPDATE users SET account_type = 'vendor' WHERE id = ?")->execute([$vendorUserId]);
        }
    } else {
        // Fallback to vendors table
        $vPrev = $dbh->prepare("SELECT is_verified, user_id FROM vendors WHERE id = ?");
        $vPrev->execute([$id]);
        $before = $vPrev->fetch(PDO::FETCH_ASSOC);

        if (!$before) {
            echo json_encode(['success' => false, 'error' => 'No such merchant or vendor found.']);
            exit;
        }

        $wasVerified = (int)$before['is_verified'] === 1;
        $vendorUserId = (int)($before['user_id'] ?? 0);

        $stmt = $dbh->prepare(
            "UPDATE vendors
                SET is_verified = ?,
                    verification_date = CASE WHEN ? = 1 THEN NOW() ELSE NULL END
              WHERE id = ?"
        );
        $stmt->execute([$verified, $verified, $id]);
    }

    $notified = false;
    if ($verified === 1 && !$wasVerified && $vendorUserId > 0) {
        if (file_exists(__DIR__ . '/../../includes/vendor-notification-helper.php')) {
            require_once __DIR__ . '/../../includes/vendor-notification-helper.php';
            if (function_exists('notifyVendorVerified')) {
                $notified = notifyVendorVerified($vendorUserId);
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

/**
 * ACTION: SET_BUSINESS_TYPE
 * Updates the category tier, tiered commission rate, and minimum payout threshold.
 */
if ($action === 'set_business_type' && $_SERVER['REQUEST_METHOD'] === 'POST') {
    verifyCsrfHeader();
    $input = json_decode(file_get_contents('php://input'), true);
    $id = intval($input['id'] ?? 0);
    $type = trim((string)($input['merchant_type'] ?? $input['business_type'] ?? ''));

    // Four valid merchant categories (with backwards-compatibility aliases)
    $typeConfig = [
        'vendor'              => ['rate' => 0.100, 'min_payout' => 5000.00],
        'farmer'              => ['rate' => 0.060, 'min_payout' => 5000.00],
        'market_vendor'       => ['rate' => 0.060, 'min_payout' => 5000.00],
        'fastfood_restaurant' => ['rate' => 0.180, 'min_payout' => 5000.00],
        'wholesale'           => ['rate' => 0.035, 'min_payout' => 50000.00],
        'wholesaler'          => ['rate' => 0.035, 'min_payout' => 50000.00]
    ];

    if (!$id || !array_key_exists($type, $typeConfig)) {
        echo json_encode(['success' => false, 'error' => 'Please select a valid merchant type.']);
        exit;
    }

    // Normalize canonical keys
    $canonicalType = match($type) {
        'wholesaler' => 'wholesale',
        'farmer'     => 'market_vendor',
        default      => $type
    };

    $config = $typeConfig[$canonicalType];
    $commRate = $config['rate'];
    $minPayout = $config['min_payout'];

    // Update in merchants table if it exists
    $mStmt = $dbh->prepare("
        UPDATE merchants 
        SET merchant_type = ?, commission_rate = ?, min_payout_threshold = ? 
        WHERE id = ? OR user_id = ?
    ");
    $mStmt->execute([$canonicalType, $commRate, $minPayout, $id, $id]);

    // Update in vendors table if present
    try {
        $vStmt = $dbh->prepare("
            UPDATE vendors 
            SET business_type = ?, commission_rate = ? 
            WHERE id = ? OR user_id = ?
        ");
        $vStmt->execute([$type, $commRate * 100, $id, $id]);
    } catch (Throwable $e) {
        // vendors table might not have commission_rate or might not be used
    }

    logAdminAction(
        $dbh, 'merchant.tier_changed', 'merchant', (string)$id,
        "Classification updated to $canonicalType (Commission: " . ($commRate * 100) . "%, Min Payout: UGX $minPayout)"
    );

    echo json_encode([
        'success'              => true,
        'merchant_type'        => $canonicalType,
        'business_type'        => $canonicalType,
        'commission_rate'      => $commRate,
        'min_payout_threshold' => $minPayout
    ]);
    exit;
}

echo json_encode(['success' => false, 'error' => 'Invalid action']);
