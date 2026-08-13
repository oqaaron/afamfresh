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
// and excluded every surplus order, which is a whole sales channel. So the
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
// Surplus payable is total_price + delivery_fee, matching surplusPayableTotal()
// in includes/surplus_payment.php. Charging total_price alone would report
// every surplus delivery as free.
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
 * @param string $dateColumn ordertime for orders, created_at for surplus.
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
 * @param string $table       orders | surplus_orders
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
    $surplus = revenueForTable(
        $dbh, 'surplus_orders', '(total_price + delivery_fee)', 'created_at', $from, $to
    );

    $add = fn(string $k) => [
        'value' => $shop[$k]['value'] + $surplus[$k]['value'],
        'count' => $shop[$k]['count'] + $surplus[$k]['count'],
    ];

    // Methods merged across channels. Null from either side means the migration
    // has not run; the whole thing then reports null rather than half a truth.
    $byMethod = null;
    if ($shop['by_method'] !== null && $surplus['by_method'] !== null) {
        $byMethod = [];
        foreach (['cash', 'mobile_money', 'card', 'unknown'] as $m) {
            $byMethod[$m] = [
                'value' => ($shop['by_method'][$m]['value'] ?? 0) + ($surplus['by_method'][$m]['value'] ?? 0),
                'count' => ($shop['by_method'][$m]['count'] ?? 0) + ($surplus['by_method'][$m]['count'] ?? 0),
            ];
        }
    }

    return [
        'from'      => $from,
        'to'        => $to,
        'shop'      => $shop,
        'surplus'   => $surplus,
        'total'     => [
            'settled'     => $add('settled'),
            'outstanding' => $add('outstanding'),
            'lost'        => $add('lost'),
            'gross'       => $add('gross'),
        ],
        'by_method' => $byMethod,
    ];
}