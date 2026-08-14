<?php
// =============================================================
// includes/reconciliation.php
//
// The queries behind the reconciliation page and its CSV export.
//
// Kept out of the page so the export cannot drift from what is on screen —
// which is exactly how admin/dashboard.php and api/admin/stats.php ended up
// with two copies of the same wrong revenue query.
//
// Every function here takes the same inclusive 'YYYY-MM-DD' window and answers
// for BOTH channels, because "how much did we take last week" is not a question
// about one table.
// =============================================================

require_once __DIR__ . '/revenue.php';

/**
 * Day-by-day takings, split cash vs electronic.
 *
 * This is the artefact you hold next to the Pesapal settlement statement: the
 * electronic column should tie to it line by line. Cash will not appear there
 * at all, which is the point of separating them.
 */
function reconciliationDaily(PDO $dbh, ?string $from, ?string $to): array {
    [$shopDate, $shopParams] = revenueDateClause('ordertime', $from, $to);
    [$surDate, $surParams]   = revenueDateClause('created_at', $from, $to);

    $sql = "
        SELECT day, SUM(cash) AS cash, SUM(mobile_money) AS mobile_money,
               SUM(card) AS card, SUM(unknown_method) AS unknown_method,
               SUM(orders_n) AS orders_n
          FROM (
            SELECT DATE(ordertime) AS day,
                   SUM(CASE WHEN payment_method='cash' THEN total_amount ELSE 0 END) AS cash,
                   SUM(CASE WHEN payment_method='mobile_money' THEN total_amount ELSE 0 END) AS mobile_money,
                   SUM(CASE WHEN payment_method='card' THEN total_amount ELSE 0 END) AS card,
                   SUM(CASE WHEN payment_method='unknown' THEN total_amount ELSE 0 END) AS unknown_method,
                   COUNT(*) AS orders_n
              FROM orders
             WHERE payment_status = 'paid' $shopDate
             GROUP BY DATE(ordertime)
            UNION ALL
            SELECT DATE(created_at) AS day,
                   SUM(CASE WHEN payment_method='cash' THEN total_price + delivery_fee ELSE 0 END),
                   SUM(CASE WHEN payment_method='mobile_money' THEN total_price + delivery_fee ELSE 0 END),
                   SUM(CASE WHEN payment_method='card' THEN total_price + delivery_fee ELSE 0 END),
                   SUM(CASE WHEN payment_method='unknown' THEN total_price + delivery_fee ELSE 0 END),
                   COUNT(*)
              FROM Bulk_orders
             WHERE payment_status = 'paid' $surDate
             GROUP BY DATE(created_at)
          ) AS combined
         GROUP BY day
         ORDER BY day DESC";

    $stmt = $dbh->prepare($sql);
    $stmt->execute(array_merge($shopParams, $surParams));
    return $stmt->fetchAll(PDO::FETCH_ASSOC);
}

/**
 * Cash each rider has collected in the window.
 *
 * The number you chase someone for. Grouped on cash_collected_by, which is only
 * ever written by the confirmation step in api/rider.php, so a row here means a
 * specific rider pressed a specific button saying they took the money.
 */
function reconciliationRiderCash(PDO $dbh, ?string $from, ?string $to): array {
    [$shopDate, $shopParams] = revenueDateClause('cash_collected_at', $from, $to);
    [$surDate, $surParams]   = revenueDateClause('cash_collected_at', $from, $to);

    $sql = "
        SELECT r.id, r.name, r.phone,
               SUM(t.amount) AS collected, SUM(t.n) AS deliveries
          FROM (
            SELECT cash_collected_by AS rider_id, SUM(total_amount) AS amount, COUNT(*) AS n
              FROM orders
             WHERE payment_method='cash' AND cash_collected_by IS NOT NULL $shopDate
             GROUP BY cash_collected_by
            UNION ALL
            SELECT cash_collected_by, SUM(total_price + delivery_fee), COUNT(*)
              FROM Bulk_orders
             WHERE payment_method='cash' AND cash_collected_by IS NOT NULL $surDate
             GROUP BY cash_collected_by
          ) AS t
          JOIN riders r ON r.id = t.rider_id
         GROUP BY r.id, r.name, r.phone
         ORDER BY collected DESC";

    $stmt = $dbh->prepare($sql);
    $stmt->execute(array_merge($shopParams, $surParams));
    return $stmt->fetchAll(PDO::FETCH_ASSOC);
}

/**
 * The exceptions. This is the part of the page worth reading every day.
 *
 * Two kinds, and they mean different things:
 *
 *   delivered_unpaid  goods handed over with no money recorded. Either a rider
 *                     did not confirm collection, or nobody paid. Both need a
 *                     person.
 *   paid_unattributed money received that cannot be assigned to cash or
 *                     electronic. Pre-migration rows mostly, but a new one
 *                     means a payment path is not recording its method.
 */
function reconciliationExceptions(PDO $dbh, ?string $from, ?string $to): array {
    [$shopDate, $shopParams] = revenueDateClause('ordertime', $from, $to);
    [$surDate, $surParams]   = revenueDateClause('created_at', $from, $to);

    $unpaid = $dbh->prepare("
        SELECT 'order' AS source, orderid AS id, total_amount AS amount,
               payment_status, payment_method, delivered_at
          FROM orders
         WHERE delivered_at IS NOT NULL AND payment_status <> 'paid' $shopDate
        UNION ALL
        SELECT 'Bulk', id, total_price + delivery_fee,
               payment_status, payment_method, delivered_at
          FROM Bulk_orders
         WHERE delivered_at IS NOT NULL AND payment_status <> 'paid' $surDate
         ORDER BY delivered_at DESC LIMIT 200");
    $unpaid->execute(array_merge($shopParams, $surParams));

    $unattributed = $dbh->prepare("
        SELECT 'order' AS source, orderid AS id, total_amount AS amount, ordertime AS at
          FROM orders
         WHERE payment_status = 'paid' AND payment_method = 'unknown' $shopDate
        UNION ALL
        SELECT 'Bulk', id, total_price + delivery_fee, created_at
          FROM Bulk_orders
         WHERE payment_status = 'paid' AND payment_method = 'unknown' $surDate
         ORDER BY at DESC LIMIT 200");
    $unattributed->execute(array_merge($shopParams, $surParams));

    return [
        'delivered_unpaid'  => $unpaid->fetchAll(PDO::FETCH_ASSOC),
        'paid_unattributed' => $unattributed->fetchAll(PDO::FETCH_ASSOC),
    ];
}

/** Every recorded event for one order, oldest first. The dispute view. */
function reconciliationEvents(PDO $dbh, string $source, int $orderId): array {
    $stmt = $dbh->prepare(
        "SELECT * FROM payment_events
          WHERE source = ? AND order_id = ?
          ORDER BY created_at ASC, id ASC"
    );
    $stmt->execute([$source, $orderId]);
    return $stmt->fetchAll(PDO::FETCH_ASSOC);
}