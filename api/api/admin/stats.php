<?php
// =============================================================
// api/admin/stats.php — dashboard counters as JSON.
//
// TWO THINGS THIS FIXES
//
// 1. THIS ENDPOINT HAD NO AUTH CHECK. It required config.php and ran. Total
//    revenue, the user count and the order count were readable by anyone on
//    the internet who guessed the path -- no session, no token, nothing. Every
//    admin page behind it has always been gated; this JSON copy of them was not.
//
// 2. IT HELD A BYTE-IDENTICAL COPY of the dashboard's revenue query, which was
//    `SUM(total_amount) FROM orders` with no WHERE clause: unpaid, failed,
//    reversed and cancelled orders all counted as revenue, and no Bulk order
//    counted at all. Both now call revenueSummary(), so they cannot disagree
//    again. See includes/revenue.php for what each figure means.
// =============================================================

require_once __DIR__ . '/../../admin/auth_check.php';
requireAdminLogin();

header('Content-Type: application/json');
require_once __DIR__ . '/../../admin/includes/config.php';
require_once __DIR__ . '/../../includes/revenue.php';

try {
    // 'Received' and 'Pending', not 'pending'. orders.status is written
    // capitalised everywhere (api/orders.php, admin/orders.php,
    // pesapal-ipn.php), so the old lowercase comparison matched nothing on a
    // case-sensitive collation and reported a permanent zero.
    $counts = $dbh->query("SELECT
        (SELECT COUNT(*) FROM orders) as total_orders,
        (SELECT COUNT(*) FROM users) as total_users,
        (SELECT COUNT(*) FROM items) as total_products,
        (SELECT COUNT(*) FROM Bulk_listings WHERE status = 'pending') as pending_Bulk,
        (SELECT COUNT(*) FROM orders WHERE status IN ('Received','Pending')) as pending_orders
    ")->fetch(PDO::FETCH_ASSOC);

    $revenue = revenueSummary($dbh);

    echo json_encode([
        'success' => true,
        'stats' => array_merge($counts, [
            // Kept under its old key so nothing reading this breaks, but it now
            // means SETTLED revenue rather than the value of every row in the
            // table. The old meaning is still available as gross_placed.
            'total_revenue'    => $revenue['total']['settled']['value'],
            'gross_placed'     => $revenue['total']['gross']['value'],
            'cash_outstanding' => $revenue['total']['outstanding']['value'],
            'lost_value'       => $revenue['total']['lost']['value'],
        ]),
        'revenue' => $revenue,
    ]);
} catch (Exception $e) {
    // Logged, not returned. The message was echoed verbatim, which on an
    // unauthenticated endpoint disclosed table and column names to anyone.
    error_log('admin stats: ' . $e->getMessage());
    http_response_code(500);
    echo json_encode(['success' => false, 'error' => 'Could not load stats.']);
}