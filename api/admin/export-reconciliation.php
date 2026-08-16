<?php
// =============================================================
// admin/export-reconciliation.php
//
// The reconciliation figures as CSV. The first export anywhere in admin/.
//
// Shares every query with the page itself, via includes/reconciliation.php, so
// the file someone opens in a spreadsheet cannot disagree with the screen they
// were looking at when they clicked download.
//
// Admin-only. This is the whole revenue history of the business in one file.
// =============================================================

require_once __DIR__ . '/auth_check.php';
require_once __DIR__ . '/includes/config.php';
require_once __DIR__ . '/../includes/reconciliation.php';
require_once __DIR__ . '/../includes/admin_permissions.php';
requireAdminPermission('reports.view_financial');

$type = $_GET['type'] ?? 'daily';
if (!in_array($type, ['daily', 'rider_cash', 'orders'], true)) {
    $type = 'daily';
}

$valid = fn($d) => (bool)preg_match('/^\d{4}-\d{2}-\d{2}$/', (string)$d);
$from = $valid($_GET['from'] ?? '') ? $_GET['from'] : date('Y-m-d', strtotime('-29 days'));
$to   = $valid($_GET['to'] ?? '')   ? $_GET['to']   : date('Y-m-d');

$filename = "afamfresh-{$type}-{$from}_to_{$to}.csv";

header('Content-Type: text/csv; charset=utf-8');
header('Content-Disposition: attachment; filename="' . $filename . '"');
// Financial data should not sit in a shared cache.
header('Cache-Control: no-store, private');

$out = fopen('php://output', 'w');

// UTF-8 BOM. Excel on Windows otherwise reads a UTF-8 CSV as Latin-1 and
// mangles any non-ASCII name in it.
fwrite($out, "\xEF\xBB\xBF");

try {
    if ($type === 'daily') {
        fputcsv($out, ['Date', 'Orders', 'Cash', 'Mobile money', 'Card', 'Unattributed', 'Total']);
        foreach (reconciliationDaily($dbh, $from, $to) as $d) {
            $total = $d['cash'] + $d['mobile_money'] + $d['card'] + $d['unknown_method'];
            fputcsv($out, [
                $d['day'], $d['orders_n'], $d['cash'],
                $d['mobile_money'], $d['card'], $d['unknown_method'], $total,
            ]);
        }
    } elseif ($type === 'rider_cash') {
        fputcsv($out, ['Rider', 'Phone', 'Deliveries', 'Cash collected']);
        foreach (reconciliationRiderCash($dbh, $from, $to) as $r) {
            fputcsv($out, [$r['name'], $r['phone'], $r['deliveries'], $r['collected']]);
        }
    } else {
        // Every settled order, both channels, one row each. The drill-down for
        // when a daily total is queried.
        fputcsv($out, ['Source', 'Order', 'Date', 'Amount', 'Method', 'Channel', 'Collected by']);

        [$shopDate, $shopParams] = revenueDateClause('ordertime', $from, $to);
        [$surDate, $surParams]   = revenueDateClause('created_at', $from, $to);

        $stmt = $dbh->prepare("
            SELECT 'shop' AS source, orderid AS id, ordertime AS at, total_amount AS amount,
                   payment_method, payment_channel, cash_collected_by
              FROM orders WHERE payment_status='paid' $shopDate
            UNION ALL
            SELECT 'Bulk', id, created_at, total_price + delivery_fee,
                   payment_method, payment_channel, cash_collected_by
              FROM Bulk_orders WHERE payment_status='paid' $surDate
            ORDER BY at DESC");
        $stmt->execute(array_merge($shopParams, $surParams));

        // Rider names resolved once rather than per row.
        $riders = [];
        foreach ($dbh->query("SELECT id, name FROM riders") as $r) {
            $riders[(int)$r['id']] = $r['name'];
        }

        while ($row = $stmt->fetch(PDO::FETCH_ASSOC)) {
            fputcsv($out, [
                $row['source'], $row['id'], $row['at'], $row['amount'],
                $row['payment_method'], $row['payment_channel'],
                $row['cash_collected_by'] ? ($riders[(int)$row['cash_collected_by']] ?? $row['cash_collected_by']) : '',
            ]);
        }
    }
} catch (PDOException $e) {
    error_log('reconciliation export: ' . $e->getMessage());
    // Headers are already sent, so this cannot become a proper error page. A
    // row saying what went wrong beats a silently truncated file that looks
    // like a complete one.
    fputcsv($out, ['ERROR', 'Export failed — has the payment-method migration been run?']);
}

fclose($out);