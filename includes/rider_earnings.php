<?php
// =============================================================
// includes/rider_earnings.php
//
// What a rider earns per delivery, and how it gets credited.
//
// Riders are paid from the MILEAGE component of delivery_fee only —
// not the service fee, insurance, or processing fee, which cover the
// platform's own costs. AfamFresh retains RIDER_COMMISSION_RATE of
// that mileage amount; the rest is the rider's net earning.
//
// orders.mileage_fee is what was actually quoted at order time (see
// api/orders.php's create action). For orders placed before that
// column existed, mileageFeeFor() recomputes an ESTIMATE from the
// order's stored dest_lat/dest_lng using the same Haversine distance
// and tiered per-km rate calculateDeliveryFee() uses — flagged with
// is_estimated so it is never presented as exact.
// =============================================================

require_once __DIR__ . '/delivery-fee.php';

/** Percentage of the mileage fee retained by AfamFresh. */
define('RIDER_COMMISSION_RATE', 10.00);

/**
 * The mileage fee for one order: the persisted value if there is one,
 * otherwise a Haversine-based estimate from stored coordinates.
 *
 * @return array{amount: float, estimated: bool}|null null when neither
 *         a persisted value nor coordinates to estimate from exist.
 */
function mileageFeeFor($dbh, $orderId) {
    $stmt = $dbh->prepare(
        "SELECT mileage_fee, total_amount, dest_lat, dest_lng FROM orders WHERE orderid = ?"
    );
    $stmt->execute([$orderId]);
    $order = $stmt->fetch(PDO::FETCH_ASSOC);
    if (!$order) {
        return null;
    }

    if ($order['mileage_fee'] !== null) {
        return ['amount' => (float)$order['mileage_fee'], 'estimated' => false];
    }

    if ($order['dest_lat'] === null || $order['dest_lng'] === null) {
        return null;
    }

    // Road distance, not the straight line. This is a RIDER'S PAY: Haversine
    // understates a real journey by 20-40%, so every legacy order reconciled
    // this way was paying the rider for a trip shorter than the one they made.
    // Still flagged estimated — the fee was never quoted at order time, and
    // this is a reconstruction whatever the routing.
    require_once __DIR__ . '/google_routes.php';
    $officeLat = defined('OFFICE_LAT') ? OFFICE_LAT : 0.38082497218633615;
    $officeLng = defined('OFFICE_LNG') ? OFFICE_LNG : 32.65071116168179;

    $route = roadDistanceBetween(
        $officeLat, $officeLng,
        (float)$order['dest_lat'], (float)$order['dest_lng']
    );
    $breakdown = calculateDeliveryFee((float)$order['total_amount'], $route['km']);

    return ['amount' => (float)$breakdown['distance_fee'], 'estimated' => true];
}

/**
 * Credits a rider for a completed delivery, if not already credited.
 *
 * Idempotent via the UNIQUE key on rider_earnings (source, order_id) — calling
 * this twice for the same delivery (e.g. a retried request) does not double-pay.
 *
 * @param string $source 'order' for the shop, 'surplus' for surplus_orders.
 *                       Required because the two id spaces overlap.
 * @return array{ok: bool, error: ?string}
 */
function creditRiderEarnings($dbh, $riderId, $orderId, $source = 'order') {
    if (!in_array($source, ['order', 'surplus'], true)) {
        error_log("creditRiderEarnings: unknown source '$source' for order $orderId");
        return ['ok' => false, 'error' => 'Unknown order source.'];
    }

    // Scoped by source as well as id. Without it, shop order 41 having been
    // credited makes surplus order 41 look already-paid: the rider does the
    // work, no row is written, and nothing anywhere reports a problem.
    $existing = $dbh->prepare("SELECT id FROM rider_earnings WHERE source = ? AND order_id = ?");
    $existing->execute([$source, $orderId]);
    if ($existing->fetchColumn()) {
        return ['ok' => true, 'error' => null];
    }

    // A surplus delivery is paid from the surplus delivery fee, because that
    // fee is charged on weight and carries no mileage component to draw from.
    // See surplusRiderFeeFor() in includes/rider_dispatch.php.
    $fee = $source === 'surplus'
        ? surplusRiderFeeFor($dbh, $orderId)
        : mileageFeeFor($dbh, $orderId);

    if ($fee === null) {
        // Nothing to estimate from. Logged rather than failing the delivery
        // itself — the rider still completed the job, they just cannot be
        // paid for this specific order until someone reconciles it.
        error_log("creditRiderEarnings: no fee available for $source order $orderId (rider $riderId)");
        return ['ok' => false, 'error' => 'No delivery fee could be determined for this order.'];
    }

    $commission = round($fee['amount'] * (RIDER_COMMISSION_RATE / 100), 2);
    $net = round($fee['amount'] - $commission, 2);

    try {
        $dbh->prepare(
            "INSERT INTO rider_earnings
                (rider_id, order_id, source, mileage_fee, commission_rate, commission_amount, net_earnings, is_estimated)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
        )->execute([
            $riderId, $orderId, $source, $fee['amount'], RIDER_COMMISSION_RATE, $commission, $net,
            $fee['estimated'] ? 1 : 0,
        ]);
        return ['ok' => true, 'error' => null];
    } catch (PDOException $e) {
        // 23000 here means a concurrent request won the same UNIQUE race —
        // not a real failure, the delivery is credited either way.
        if ($e->getCode() === '23000') {
            return ['ok' => true, 'error' => null];
        }
        error_log("creditRiderEarnings failed for $source order $orderId: " . $e->getMessage());
        return ['ok' => false, 'error' => 'Could not record earnings for this delivery.'];
    }
}