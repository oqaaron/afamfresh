<?php
// =============================================================
// includes/revenue.php
//
// One definition of "revenue", for every page that reports it.
//
// WHY THIS FILE EXISTS
//
// The figure on the admin dashboard was `SUM(total_amount) FROM orders` with no
// WHERE clause at all. That counted as revenue:
//
//   - orders nobody ever paid for (payment_status = 'pending', which is what
//     every order is inserted with)
//   - abandoned Pesapal attempts ('authorization_pending')
//   - payments that failed, reversed or were declared invalid
//   - orders the customer cancelled
//   - cash orders where no cash was ever collected
//
// and excluded every Bulk order, which is a whole sales channel. So the
// headline number was simultaneously overstated and understated, and no two
// people reading it would agree what it meant.
//
// api/admin/stats.php held a byte-identical copy of that query. The two had
// already drifted from anything defensible in exactly the same way, which is
// the argument for defining it once, here, and having both call it.
//
// THE DEFINITIONS
//
//   settled       money received. payment_status = 'paid', nothing else.
//   outstanding   cash owed to us: an order placed as cash-on-delivery whose
//                 rider has not confirmed collection.
//   gross_placed  the value of everything ordered, paid or not. A real
//                 operational number -- it was simply never revenue.
//   lost          failed, reversed, invalid or cancelled.
//
// Bulk payable is total_price + delivery_fee, matching BulkPayableTotal()
// in includes/Bulk_payment.php. Charging total_price alone would report
// every Bulk delivery as free.
// =============================================================

/** payment_status values that mean the money is actually ours. */
const REVENUE_SETTLED = ['paid'];

/** Placed as cash-on-delivery and not yet confirmed collected. */
const REVENUE_OUTSTANDING = ['pending_cash'];

/** Terminal failures. 'pending' and 'authorization_pending' are NOT here — they
 *  are still in flight and may yet settle. */
const REVENUE_LOST = ['failed', 'reversed', 'invalid', 'cancelled'];

/**
 * Builds the shared WHERE fragment and its bindings for a date window.
 *
 * @param string $dateColumn ordertime for orders, created_at for Bulk.
 * @return array{0:string,1:array} SQL fragment and positional params.
 */
function revenueDateClause(string $dateColumn, ?string $from, ?string $to): array {
    $sql = '';
    $params = [];
    if ($from !== null && $from !== '') {
        $sql .= " AND $dateColumn >= ?";
        $params[] = $from . ' 00:00:00';
    }
    if ($to !== null && $to !== '') {
        // Inclusive of the whole end day. A range picker that silently drops
        // today's sales because the clock is past midnight-plus-anything is a
        // reliable way to make someone distrust the whole report.
        $sql .= " AND $dateColumn <= ?";
        $params[] = $to . ' 23:59:59';
    }
    return [$sql, $params];
}

/**
 * One channel's figures.
 *
 * @param string $table       orders | Bulk_orders
 * @param string $amountExpr  the payable total expression for that table
 * @param string $dateColumn  which column the date window applies to
 */
function revenueForTable(PDO $dbh, string $table, string $amountExpr,
                         string $dateColumn, ?string $from, ?string $to): array {
    [$dateSql, $dateParams] = revenueDateClause($dateColumn, $from, $to);

    $bucket = function (array $statuses) use ($dbh, $table, $amountExpr, $dateSql, $dateParams) {
        $in = implode(',', array_fill(0, count($statuses), '?'));
        $stmt = $dbh->prepare(
            "SELECT COALESCE(SUM($amountExpr), 0) AS value, COUNT(*) AS n
               FROM $table
              WHERE payment_status IN ($in) $dateSql"
        );
        $stmt->execute(array_merge($statuses, $dateParams));
        $row = $stmt->fetch(PDO::FETCH_ASSOC);
        return ['value' => (float)$row['value'], 'count' => (int)$row['n']];
    };

    // Everything placed, regardless of whether it was ever paid.
    $grossStmt = $dbh->prepare(
        "SELECT COALESCE(SUM($amountExpr), 0) AS value, COUNT(*) AS n
           FROM $table WHERE 1=1 $dateSql"
    );
    $grossStmt->execute($dateParams);
    $gross = $grossStmt->fetch(PDO::FETCH_ASSOC);

    // Settled, split by how it was paid. payment_method only exists after the
    // 2026-08-13 migration; a missing column here means the migration has not
    // been run, and reporting zeroes would be a lie, so it is left absent and
    // callers show "unavailable".
    $byMethod = [];
    try {
        $methodStmt = $dbh->prepare(
            "SELECT payment_method, COALESCE(SUM($amountExpr), 0) AS value, COUNT(*) AS n
               FROM $table
              WHERE payment_status = 'paid' $dateSql
              GROUP BY payment_method"
        );
        $methodStmt->execute($dateParams);
        foreach ($methodStmt->fetchAll(PDO::FETCH_ASSOC) as $r) {
            $byMethod[$r['payment_method']] = [
                'value' => (float)$r['value'],
                'count' => (int)$r['n'],
            ];
        }
    } catch (PDOException $e) {
        error_log("revenue: payment_method unavailable on $table — migration not run? " . $e->getMessage());
        $byMethod = null;
    }

    return [
        'settled'     => $bucket(REVENUE_SETTLED),
        'outstanding' => $bucket(REVENUE_OUTSTANDING),
        'lost'        => $bucket(REVENUE_LOST),
        'gross'       => ['value' => (float)$gross['value'], 'count' => (int)$gross['n']],
        'by_method'   => $byMethod,
    ];
}

/**
 * The whole picture, both channels.
 *
 * @param string|null $from 'YYYY-MM-DD', inclusive. Null = all time.
 * @param string|null $to   'YYYY-MM-DD', inclusive.
 */
function revenueSummary(PDO $dbh, ?string $from = null, ?string $to = null): array {
    $shop = revenueForTable($dbh, 'orders', 'total_amount', 'ordertime', $from, $to);
    $Bulk = revenueForTable(
        $dbh, 'Bulk_orders', '(total_price + delivery_fee)', 'created_at', $from, $to
    );

    $add = fn(string $k) => [
        'value' => $shop[$k]['value'] + $Bulk[$k]['value'],
        'count' => $shop[$k]['count'] + $Bulk[$k]['count'],
    ];

    // Methods merged across channels. Null from either side means the migration
    // has not run; the whole thing then reports null rather than half a truth.
    $byMethod = null;
    if ($shop['by_method'] !== null && $Bulk['by_method'] !== null) {
        $byMethod = [];
        foreach (['cash', 'mobile_money', 'card', 'unknown'] as $m) {
            $byMethod[$m] = [
                'value' => ($shop['by_method'][$m]['value'] ?? 0) + ($Bulk['by_method'][$m]['value'] ?? 0),
                'count' => ($shop['by_method'][$m]['count'] ?? 0) + ($Bulk['by_method'][$m]['count'] ?? 0),
            ];
        }
    }

    return [
        'from'      => $from,
        'to'        => $to,
        'shop'      => $shop,
        'Bulk'   => $Bulk,
        'total'     => [
            'settled'     => $add('settled'),
            'outstanding' => $add('outstanding'),
            'lost'        => $add('lost'),
            'gross'       => $add('gross'),
        ],
        'by_method' => $byMethod,
    ];
}

/**
 * Platform fees collected independently of vendor commission and rider pay —
 * the service, insurance and processing fees folded into every delivery fee.
 *
 * These are NOT part of a vendor's cut (includes/vendor_earnings.php credits
 * only on the goods total) and NOT part of a rider's cut since the
 * carriage-only fix (includes/rider_dispatch.php's BulkRiderFeeFor(),
 * includes/rider_earnings.php's mileageFeeFor()) — they are pure platform
 * revenue, and until now nothing anywhere totalled them up.
 *
 * Only counted once money has actually settled (payment_status = 'paid'),
 * matching revenueForTable()'s definition — a fee on an order nobody paid
 * for was never collected.
 *
 * Shop orders persist these as their own columns (api/orders.php's create
 * action). Bulk orders keep them inside the delivery_fee_breakdown JSON;
 * older ("weight_based") rows predate these fees existing at all and simply
 * have no such keys, which JSON_EXTRACT reports as NULL and SUM() ignores —
 * so this is accurate for both eras of Bulk order without special-casing.
 */
function platformFeesSummary(PDO $dbh, ?string $from = null, ?string $to = null): array {
    [$shopDateSql, $shopDateParams] = revenueDateClause('ordertime', $from, $to);
    $shopStmt = $dbh->prepare(
        "SELECT COALESCE(SUM(service_fee), 0)    AS service_fee,
                COALESCE(SUM(insurance_fee), 0)  AS insurance_fee,
                COALESCE(SUM(processing_fee), 0) AS processing_fee
           FROM orders
          WHERE payment_status = 'paid' $shopDateSql"
    );
    $shopStmt->execute($shopDateParams);
    $shop = array_map('floatval', $shopStmt->fetch(PDO::FETCH_ASSOC));

    [$BulkDateSql, $BulkDateParams] = revenueDateClause('created_at', $from, $to);
    $BulkStmt = $dbh->prepare(
        "SELECT
            COALESCE(SUM(CAST(JSON_UNQUOTE(JSON_EXTRACT(delivery_fee_breakdown, '$.service_fee')) AS DECIMAL(12,2))), 0)    AS service_fee,
            COALESCE(SUM(CAST(JSON_UNQUOTE(JSON_EXTRACT(delivery_fee_breakdown, '$.insurance_fee')) AS DECIMAL(12,2))), 0)  AS insurance_fee,
            COALESCE(SUM(CAST(JSON_UNQUOTE(JSON_EXTRACT(delivery_fee_breakdown, '$.processing_fee')) AS DECIMAL(12,2))), 0) AS processing_fee
           FROM Bulk_orders
          WHERE payment_status = 'paid' $BulkDateSql"
    );
    $BulkStmt->execute($BulkDateParams);
    $Bulk = array_map('floatval', $BulkStmt->fetch(PDO::FETCH_ASSOC));

    $add = fn(string $k) => $shop[$k] + $Bulk[$k];

    return [
        'service_fee'    => $add('service_fee'),
        'insurance_fee'  => $add('insurance_fee'),
        'processing_fee' => $add('processing_fee'),
        'total'          => $add('service_fee') + $add('insurance_fee') + $add('processing_fee'),
        'shop'           => $shop,
        'Bulk'        => $Bulk,
    ];
}