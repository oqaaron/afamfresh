<?php
/**
 * includes/Bulk_payment.php — payment state for Bulk orders.
 *
 * Bulk orders live in their own table with their own column names, so they
 * cannot reuse the `orders` helpers in api/payment.php. What they CAN share is
 * the rule those helpers exist to enforce: paid/unpaid is decided by asking
 * Pesapal, never by anything the client says, and a settled payment is never
 * rewritten.
 *
 * Three callers: api/payment.php (initiate/verify with order_type=Bulk),
 * pesapal-ipn.php (Pesapal's server-to-server notification), and
 * api/Bulk-orders.php (releasing abandoned reservations, and now
 * cancellation/refund handling below).
 */

require_once __DIR__ . '/pesapal.php';
require_once __DIR__ . '/payment_ledger.php';

/**
 * The payable total of a Bulk order.
 *
 * `orders` stores one total_amount with delivery already folded in.
 * Bulk_orders keeps the goods and the delivery fee in separate columns, so
 * charging total_price alone would deliver every order for free.
 */
function BulkPayableTotal(array $order): float {
    // loyalty_discount is deliberately never subtracted from total_price or
    // delivery_fee themselves — those feed the vendor's and rider's payouts
    // respectively (creditVendorEarnings(), BulkRiderFeeFor()), and a
    // loyalty discount is the platform's cost, not theirs. See the
    // 2026-08-14 loyalty-points migration.
    return (float)$order['total_price'] + (float)($order['delivery_fee'] ?? 0)
         - (float)($order['loyalty_discount'] ?? 0);
}

/**
 * Loads a Bulk order belonging to a given user, with the customer details
 * Pesapal wants for its billing address.
 *
 * user_id is in the WHERE clause, not checked afterwards: Bulk_orders.id is
 * a plain AUTO_INCREMENT, so ownership can never be inferred from the id.
 *
 * @return array|null null when there is no such order, or it is not theirs.
 */
function loadOwnedBulkOrder(PDO $dbh, int $orderId, int $userId): ?array {
    $stmt = $dbh->prepare("
        SELECT so.id, so.user_id, so.listing_id, so.total_price, so.delivery_fee,
               so.loyalty_discount, so.points_redeemed,
               so.status, so.payment_status, so.pesapal_tracking_id,
               so.delivery_address, so.delivery_area,
               u.fname, u.lname, u.mobile, u.email
          FROM Bulk_orders so
          JOIN users u ON u.id = so.user_id
         WHERE so.id = ? AND so.user_id = ?
    ");
    $stmt->execute([$orderId, $userId]);
    return $stmt->fetch(PDO::FETCH_ASSOC) ?: null;
}

/**
 * Applies a resolved Pesapal status to a Bulk order, once.
 *
 * Idempotent by design. A late IPN arriving after the app's own verify call has
 * already settled the order must not flip a paid order back to failed, and a
 * double-tapped retry must not re-run the side effects.
 *
 * On payment the order also moves pending -> confirmed. That is what puts it in
 * front of the vendor as real work: an unpaid Bulk order is only a
 * reservation, and vendors should not be picking and packing against one.
 */
function applyBulkPaymentStatus(PDO $dbh, int $orderId, string $mapped, ?string $trackingId): void {
    $current = $dbh->prepare("SELECT payment_status FROM Bulk_orders WHERE id = ?");
    $current->execute([$orderId]);
    $now = $current->fetchColumn();

    if ($now === false) return;                       // no such order
    if (strcasecmp((string)$now, 'paid') === 0) return; // already settled

    if ($mapped === 'paid') {
        $stmt = $dbh->prepare("
            UPDATE Bulk_orders
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
        UPDATE Bulk_orders
           SET payment_status = ?,
               pesapal_tracking_id = COALESCE(?, pesapal_tracking_id),
               updated_at = NOW()
         WHERE id = ?
    ");
    $stmt->execute([$mapped, $trackingId, $orderId]);
}

/**
 * Returns stock held by Bulk orders that were never paid for.
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
 * api/Bulk-listings.php filters on `remaining_quantity > 0` — so putting
 * stock back is enough to make the listing visible again, and an expired or
 * admin-cancelled listing stays down where it belongs.
 *
 * Called at the top of order creation rather than from cron: that is the moment
 * the numbers have to be right, and it bounds the work to one query per order
 * instead of a sweep running all day.
 *
 * @return int how many reservations were released.
 */
function releaseStaleBulkReservations(PDO $dbh, int $minutes = 30): int {
    $stale = $dbh->prepare("
        SELECT id, listing_id, quantity
          FROM Bulk_orders
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
        UPDATE Bulk_listings
           SET remaining_quantity = LEAST(remaining_quantity + ?, Bulk_quantity)
         WHERE id = ?
    ");
    $cancel = $dbh->prepare("
        UPDATE Bulk_orders
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
        error_log("Bulk: released $released abandoned reservation(s)");
    }
    return $released;
}

/**
 * Actually cancels a Bulk order: marks it cancelled, returns stock to the
 * listing, drops any rider assignment, and — only if it was actually paid
 * via mobile money — requests a real refund from Pesapal.
 *
 * Only ever call this once cancellation is authorised: directly for an
 * admin's own action, or as the execution step of an admin-approved
 * cancellation request (requestBulkOrderCancellation() below). A refund
 * failure never blocks the cancellation itself — the order is cancelled
 * either way, with the refund outcome reported back for the caller to
 * surface rather than silently swallowed.
 *
 * @return array{
 *   ok: bool, error?: string, order?: array,
 *   refund_attempted?: bool, refund_requested?: bool, refund_error?: string
 * }
 */
function cancelBulkOrder(
    PDO $dbh, int $orderId, string $reason, string $actorType, ?int $actorId,
    string $refundUsername = 'afamfresh-admin'
): array {
    $stmt = $dbh->prepare("SELECT * FROM Bulk_orders WHERE id = ?");
    $stmt->execute([$orderId]);
    $order = $stmt->fetch(PDO::FETCH_ASSOC);

    if (!$order) {
        return ['ok' => false, 'error' => 'No such order.'];
    }
    if (in_array($order['status'], ['delivered', 'cancelled', 'refunded'], true)) {
        return ['ok' => false, 'error' => 'That order is already ' . $order['status'] . '.'];
    }

    try {
        $dbh->beginTransaction();

        $upd = $dbh->prepare(
            "UPDATE Bulk_orders
                SET status = 'cancelled', status_before_cancellation = NULL,
                    cancellation_reason = NULL, updated_at = NOW()
              WHERE id = ? AND status NOT IN ('delivered','cancelled','refunded')"
        );
        $upd->execute([$orderId]);
        if ($upd->rowCount() === 0) {
            // Changed underneath us between the SELECT above and here.
            $dbh->rollBack();
            return ['ok' => false, 'error' => 'That order changed while this was being processed. Please retry.'];
        }

        // Capped at the original quantity, same guard releaseStaleBulkReservations()
        // and the admin cancel flow already use — a double cancellation cannot
        // inflate the listing beyond what the vendor actually put up.
        $dbh->prepare(
            "UPDATE Bulk_listings
                SET remaining_quantity = LEAST(remaining_quantity + ?, Bulk_quantity)
              WHERE id = ?"
        )->execute([$order['quantity'], $order['listing_id']]);

        $dbh->prepare(
            "DELETE FROM rider_assignments WHERE order_id = ? AND source = 'Bulk'"
        )->execute([$orderId]);

        $dbh->commit();
    } catch (Throwable $e) {
        if ($dbh->inTransaction()) $dbh->rollBack();
        error_log("cancelBulkOrder failed for order $orderId: " . $e->getMessage());
        return ['ok' => false, 'error' => 'Could not cancel that order.'];
    }

    recordPaymentEvent($dbh, 'Bulk', $orderId, 'cancelled', [
        'from_status' => $order['status'],
        'to_status'   => 'cancelled',
        'actor_type'  => $actorType,
        'actor_id'    => $actorId,
    ]);

    $result = ['ok' => true, 'order' => $order, 'refund_attempted' => false, 'refund_requested' => false];

    // Cash was never captured electronically — there is nothing for Pesapal
    // to reverse, and returning it (if any changed hands) is an operational
    // matter outside this system, same as how a rider's cash float already
    // reconciles physically rather than through this ledger.
    $wasElectronicallyPaid = strcasecmp((string)$order['payment_status'], 'paid') === 0
        && (string)($order['payment_method'] ?? '') !== 'cash'
        && trim((string)($order['pesapal_tracking_id'] ?? '')) !== '';

    if ($wasElectronicallyPaid) {
        $result['refund_attempted'] = true;
        try {
            $pesapal = new PesapalClient();

            // Never trust the locally-stored payment_status for this —
            // same discipline applyBulkPaymentStatus() already enforces.
            $liveStatus = $pesapal->getTransactionStatus($order['pesapal_tracking_id']);
            if ($pesapal->mapStatus($liveStatus) !== 'paid') {
                throw new PesapalException('Pesapal no longer shows this payment as completed.');
            }

            $confirmationCode = trim((string)($liveStatus['confirmation_code'] ?? ''));
            if ($confirmationCode === '') {
                throw new PesapalException('No confirmation code on this transaction.');
            }

            $amount = BulkPayableTotal($order);
            $pesapal->submitRefundRequest($confirmationCode, $amount, $reason, $refundUsername);

            $dbh->prepare(
                "UPDATE Bulk_orders SET payment_status = 'refund_requested', updated_at = NOW() WHERE id = ?"
            )->execute([$orderId]);

            recordPaymentEvent($dbh, 'Bulk', $orderId, 'refund_requested', [
                'from_status' => 'paid',
                'to_status'   => 'refund_requested',
                'method'      => 'mobile_money',
                'amount'      => $amount,
                'tracking_id' => $order['pesapal_tracking_id'],
                'actor_type'  => $actorType,
                'actor_id'    => $actorId,
            ]);

            $result['refund_requested'] = true;
        } catch (PesapalException $e) {
            error_log("cancelBulkOrder: refund request failed for order $orderId: " . $e->getMessage());
            recordPaymentEvent($dbh, 'Bulk', $orderId, 'refund_failed', [
                'from_status' => 'paid',
                'to_status'   => 'paid',
                'tracking_id' => $order['pesapal_tracking_id'],
                'actor_type'  => $actorType,
                'actor_id'    => $actorId,
                'raw'         => $e->getMessage(),
            ]);
            // payment_status is left as 'paid' on purpose: the mismatch
            // between "order cancelled" and "payment still shows paid"
            // stays visible and actionable rather than silently swallowed.
            $result['refund_error'] = $e->getMessage();
        }
    }

    return $result;
}

/**
 * A vendor asking to cancel their own PAID order. Does nothing but record
 * the request and make it visible to admin — no stock change, no Pesapal
 * call. cancelBulkOrder() only ever runs once an admin approves it via
 * admin/Bulk-orders.php's approve_cancellation action.
 *
 * @return array{ok: bool, error?: string}
 */
function requestBulkOrderCancellation(
    PDO $dbh, int $orderId, string $reason, int $vendorUserId
): array {
    $stmt = $dbh->prepare(
        "UPDATE Bulk_orders
            SET status_before_cancellation = status,
                status = 'cancellation_requested',
                cancellation_reason = ?,
                cancellation_requested_by = ?,
                cancellation_requested_at = NOW(),
                updated_at = NOW()
          WHERE id = ?
            AND status NOT IN ('delivered','cancelled','refunded','cancellation_requested')"
    );
    $stmt->execute([$reason, $vendorUserId, $orderId]);

    if ($stmt->rowCount() === 0) {
        return ['ok' => false, 'error' => 'That order cannot be cancelled right now.'];
    }

    recordPaymentEvent($dbh, 'Bulk', $orderId, 'cancellation_requested', [
        'to_status'  => 'cancellation_requested',
        'actor_type' => 'vendor',
        'actor_id'   => $vendorUserId,
    ]);

    return ['ok' => true];
}