<?php
/**
 * includes/surplus_payment.php — payment state for surplus orders.
 *
 * Surplus orders live in their own table with their own column names, so they
 * cannot reuse the `orders` helpers in api/payment.php. What they CAN share is
 * the rule those helpers exist to enforce: paid/unpaid is decided by asking
 * Pesapal, never by anything the client says, and a settled payment is never
 * rewritten.
 *
 * Three callers: api/payment.php (initiate/verify with order_type=surplus),
 * pesapal-ipn.php (Pesapal's server-to-server notification), and
 * api/surplus-orders.php (releasing abandoned reservations).
 */

/**
 * The payable total of a surplus order.
 *
 * `orders` stores one total_amount with delivery already folded in.
 * surplus_orders keeps the goods and the delivery fee in separate columns, so
 * charging total_price alone would deliver every order for free.
 */
function surplusPayableTotal(array $order): float {
    return (float)$order['total_price'] + (float)($order['delivery_fee'] ?? 0);
}

/**
 * Loads a surplus order belonging to a given user, with the customer details
 * Pesapal wants for its billing address.
 *
 * user_id is in the WHERE clause, not checked afterwards: surplus_orders.id is
 * a plain AUTO_INCREMENT, so ownership can never be inferred from the id.
 *
 * @return array|null null when there is no such order, or it is not theirs.
 */
function loadOwnedSurplusOrder(PDO $dbh, int $orderId, int $userId): ?array {
    $stmt = $dbh->prepare("
        SELECT so.id, so.user_id, so.listing_id, so.total_price, so.delivery_fee,
               so.status, so.payment_status, so.pesapal_tracking_id,
               so.delivery_address, so.delivery_area,
               u.fname, u.lname, u.mobile, u.email
          FROM surplus_orders so
          JOIN users u ON u.id = so.user_id
         WHERE so.id = ? AND so.user_id = ?
    ");
    $stmt->execute([$orderId, $userId]);
    return $stmt->fetch(PDO::FETCH_ASSOC) ?: null;
}

/**
 * Applies a resolved Pesapal status to a surplus order, once.
 *
 * Idempotent by design. A late IPN arriving after the app's own verify call has
 * already settled the order must not flip a paid order back to failed, and a
 * double-tapped retry must not re-run the side effects.
 *
 * On payment the order also moves pending -> confirmed. That is what puts it in
 * front of the vendor as real work: an unpaid surplus order is only a
 * reservation, and vendors should not be picking and packing against one.
 */
function applySurplusPaymentStatus(PDO $dbh, int $orderId, string $mapped, ?string $trackingId): void {
    $current = $dbh->prepare("SELECT payment_status FROM surplus_orders WHERE id = ?");
    $current->execute([$orderId]);
    $now = $current->fetchColumn();

    if ($now === false) return;                       // no such order
    if (strcasecmp((string)$now, 'paid') === 0) return; // already settled

    if ($mapped === 'paid') {
        $stmt = $dbh->prepare("
            UPDATE surplus_orders
               SET payment_status = 'paid',
                   payment_captured_at = NOW(),
                   status = CASE WHEN status = 'pending' THEN 'confirmed' ELSE status END,
                   confirmed_at = COALESCE(confirmed_at, NOW()),
                   pesapal_tracking_id = COALESCE(?, pesapal_tracking_id),
                   updated_at = NOW()
             WHERE id = ?
        ");
        $stmt->execute([$trackingId, $orderId]);
        return;
    }

    // 'pending' means Pesapal has not decided yet. Writing it would overwrite
    // 'authorization_pending' and lose the fact that an attempt is in flight.
    if ($mapped === 'pending') return;

    $stmt = $dbh->prepare("
        UPDATE surplus_orders
           SET payment_status = ?,
               pesapal_tracking_id = COALESCE(?, pesapal_tracking_id),
               updated_at = NOW()
         WHERE id = ?
    ");
    $stmt->execute([$mapped, $trackingId, $orderId]);
}

/**
 * Returns stock held by surplus orders that were never paid for.
 *
 * Creating an order decrements remaining_quantity immediately — it has to, or
 * two customers could each be told the last 40kg was theirs. But that means an
 * abandoned checkout holds stock indefinitely: close the app on the Pesapal
 * page and the listing stays short forever, and once remaining_quantity hits 0
 * the listing is marked 'sold' and disappears from a marketplace where nothing
 * was actually sold.
 *
 * Anything still unpaid after $minutes is treated as abandoned and its quantity
 * given back. Deliberately conservative about what it touches:
 *
 *   - status must still be 'pending'. A vendor who confirmed the order has
 *     accepted it on other terms, and this must not reach in and cancel that.
 *   - payment_status must be 'pending', 'authorization_pending' or 'failed'.
 *     'pending_cash' is excluded — a cash-on-delivery order is not abandoned,
 *     it is simply not paid yet, and cancelling it would be wrong.
 *
 * Only the quantity is restored; the status is left alone. Sold-out is
 * expressed by remaining_quantity reaching zero, not by a status value —
 * api/surplus-listings.php filters on `remaining_quantity > 0` — so putting
 * stock back is enough to make the listing visible again, and an expired or
 * admin-cancelled listing stays down where it belongs.
 *
 * Called at the top of order creation rather than from cron: that is the moment
 * the numbers have to be right, and it bounds the work to one query per order
 * instead of a sweep running all day.
 *
 * @return int how many reservations were released.
 */
function releaseStaleSurplusReservations(PDO $dbh, int $minutes = 30): int {
    $stale = $dbh->prepare("
        SELECT id, listing_id, quantity
          FROM surplus_orders
         WHERE status = 'pending'
           AND payment_status IN ('pending', 'authorization_pending', 'failed')
           AND created_at < DATE_SUB(NOW(), INTERVAL ? MINUTE)
         LIMIT 50
    ");
    $stale->execute([$minutes]);
    $rows = $stale->fetchAll(PDO::FETCH_ASSOC);
    if (!$rows) return 0;

    // Capped at the original quantity so a double release cannot inflate the
    // listing beyond what the vendor actually put up.
    $restore = $dbh->prepare("
        UPDATE surplus_listings
           SET remaining_quantity = LEAST(remaining_quantity + ?, surplus_quantity)
         WHERE id = ?
    ");
    $cancel = $dbh->prepare("
        UPDATE surplus_orders
           SET status = 'cancelled', payment_status = 'cancelled', updated_at = NOW()
         WHERE id = ? AND status = 'pending'
    ");

    $released = 0;
    foreach ($rows as $row) {
        // Cancel first. If this updates nothing the order changed underneath us
        // between the SELECT and here, and returning the stock would double it.
        $cancel->execute([$row['id']]);
        if ($cancel->rowCount() === 0) continue;

        $restore->execute([$row['quantity'], $row['listing_id']]);
        $released++;
    }

    if ($released > 0) {
        error_log("surplus: released $released abandoned reservation(s)");
    }
    return $released;
}