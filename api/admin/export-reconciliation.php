<?php
// admin/export-reconciliation.php — CSV Reconciliation & Financial Settlement Exporter
declare(strict_types=1);

require_once __DIR__ . '/auth_check.php';
require_once __DIR__ . '/includes/config.php';
require_once __DIR__ . '/../includes/reconciliation.php';
require_once __DIR__ . '/../includes/admin_permissions.php';
requireAdminPermission('reports.view_financial');

$type = $_GET['type'] ?? 'daily';
if (!in_array($type, ['daily', 'rider_cash', 'orders', 'merchants'], true)) {
    $type = 'daily';
}

$valid = fn($d) => (bool)preg_match('/^\d{4}-\d{2}-\d{2}$/', (string)$d);
$from = $valid($_GET['from'] ?? '') ? $_GET['from'] : date('Y-m-d', strtotime('-29 days'));
$to   = $valid($_GET['to'] ?? '')   ? $_GET['to']   : date('Y-m-d');

$filename = "afamfresh-{$type}-{$from}_to_{$to}.csv";

header('Content-Type: text/csv; charset=utf-8');
header('Content-Disposition: attachment; filename="' . $filename . '"');
header('Cache-Control: no-store, private');

$out = fopen('php://output', 'w');

// UTF-8 BOM for Microsoft Excel compatibility
fwrite($out, "\xEF\xBB\xBF");

try {
    if ($type === 'daily') {
        fputcsv($out, ['Date', 'Orders', 'Cash (UGX)', 'Mobile Money (UGX)', 'Card (UGX)', 'Unattributed (UGX)', 'Total Gross (UGX)']);
        foreach (reconciliationDaily($dbh, $from, $to) as $d) {
            $total = (float)$d['cash'] + (float)$d['mobile_money'] + (float)$d['card'] + (float)$d['unknown_method'];
            fputcsv($out, [
                $d['day'],
                $d['orders_n'],
                $d['cash'],
                $d['mobile_money'],
                $d['card'],
                $d['unknown_method'],
                $total,
            ]);
        }
    } elseif ($type === 'rider_cash') {
        fputcsv($out, ['Rider Name', 'Phone Number', 'Completed Deliveries', 'Cash Collected (UGX)']);
        foreach (reconciliationRiderCash($dbh, $from, $to) as $r) {
            fputcsv($out, [
                $r['name'],
                $r['phone'] ?? '',
                $r['deliveries'],
                $r['collected']
            ]);
        }
    } elseif ($type === 'merchants') {
        // Merchant settlement distribution export
        fputcsv($out, ['Date', 'Order #', 'Merchant ID', 'Merchant Name', 'Category', 'Gross Goods (UGX)', 'Commission Rate (%)', 'Platform Cut (UGX)', 'Net to Merchant (UGX)', 'Payout Status']);
        
        $sql = "
            SELECT wt.created_at, wt.order_id, wt.user_id AS merchant_id,
                   COALESCE(m.name, CONCAT(u.fname, ' ', u.lname), 'Merchant') AS merchant_name,
                   COALESCE(m.merchant_type, 'vendor') AS merchant_type,
                   wt.amount AS net_earnings,
                   COALESCE(m.commission_rate, 0.10) AS commission_rate,
                   ROUND(wt.amount / (1 - COALESCE(m.commission_rate, 0.10)), 0) AS gross_amount,
                   ROUND((wt.amount / (1 - COALESCE(m.commission_rate, 0.10))) * COALESCE(m.commission_rate, 0.10), 0) AS platform_commission
            FROM wallet_transactions wt
            LEFT JOIN merchants m ON (m.user_id = wt.user_id OR m.id = wt.user_id)
            LEFT JOIN users u ON u.id = wt.user_id
            WHERE wt.user_type = 'vendor' AND wt.transaction_type IN ('vendor_sale', 'merchant_sale')
              AND DATE(wt.created_at) BETWEEN ? AND ?
            ORDER BY wt.created_at DESC
        ";
        $stmt = $dbh->prepare($sql);
        $stmt->execute([$from, $to]);

        while ($row = $stmt->fetch(PDO::FETCH_ASSOC)) {
            fputcsv($out, [
                $row['created_at'],
                $row['order_id'],
                $row['merchant_id'],
                $row['merchant_name'],
                ucwords(str_replace('_', ' ', $row['merchant_type'])),
                $row['gross_amount'],
                (float)$row['commission_rate'] * 100,
                $row['platform_commission'],
                $row['net_earnings'],
                'Credited to Wallet'
            ]);
        }
    } else {
        // Full settled orders line items
        fputcsv($out, ['Channel', 'Order ID', 'Date & Time', 'Total Amount (UGX)', 'Payment Method', 'Payment Gateway Channel', 'Cash Collected By']);

        [$shopDate, $shopParams] = revenueDateClause('ordertime', $from, $to);
        [$surDate, $surParams]   = revenueDateClause('created_at', $from, $to);

        // Verify if Bulk_orders table exists before attempting union
        $hasBulkTable = false;
        try {
            $dbh->query("SELECT 1 FROM Bulk_orders LIMIT 1");
            $hasBulkTable = true;
        } catch (Throwable $e) {
            $hasBulkTable = false;
        }

        if ($hasBulkTable) {
            $stmt = $dbh->prepare("
                SELECT 'shop' AS source, orderid AS id, ordertime AS at, total_amount AS amount,
                       payment_method, payment_channel, cash_collected_by
                  FROM orders WHERE payment_status='paid' $shopDate
                UNION ALL
                SELECT 'bulk' AS source, id, created_at, (total_price + delivery_fee) AS amount,
                       payment_method, payment_channel, cash_collected_by
                  FROM Bulk_orders WHERE payment_status='paid' $surDate
                ORDER BY at DESC");
            $stmt->execute(array_merge($shopParams, $surParams));
        } else {
            $stmt = $dbh->prepare("
                SELECT 'shop' AS source, orderid AS id, ordertime AS at, total_amount AS amount,
                       payment_method, payment_channel, cash_collected_by
                  FROM orders WHERE payment_status='paid' $shopDate
                ORDER BY at DESC");
            $stmt->execute($shopParams);
        }

        $riders = [];
        try {
            foreach ($dbh->query("SELECT id, name FROM riders") as $r) {
                $riders[(int)$r['id']] = $r['name'];
            }
        } catch (Throwable $e) {}

        while ($row = $stmt->fetch(PDO::FETCH_ASSOC)) {
            fputcsv($out, [
                strtoupper($row['source']),
                $row['id'],
                $row['at'],
                $row['amount'],
                $row['payment_method'],
                $row['payment_channel'],
                $row['cash_collected_by'] ? ($riders[(int)$row['cash_collected_by']] ?? "Rider #{$row['cash_collected_by']}") : '',
            ]);
        }
    }
} catch (PDOException $e) {
    error_log('Reconciliation export error: ' . $e->getMessage());
    fputcsv($out, ['ERROR', 'Export failed: ' . $e->getMessage()]);
}

fclose($out);
