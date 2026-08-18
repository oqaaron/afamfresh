<?php
/**
 * pesapal-ipn.php — Instant Payment Notification receiver.
 *
 * THE VULNERABILITY THIS FIXES
 *
 * The previous version did:
 *
 *     $status = $ipn_data['payment_status'] ?? '';
 *     $payment_status = ($status === 'Completed') ? 'paid' : 'failed';
 *     UPDATE orders SET payment_status = ? WHERE pesapal_tracking_id = ?
 *
 * Two separate faults:
 *
 * 1. Pesapal never sends `payment_status` in an IPN. The body contains only
 *    OrderTrackingId, OrderMerchantReference and OrderNotificationType. So that
 *    key was always empty, the `if` never passed, and NO payment was ever
 *    marked paid by IPN.
 *
 * 2. It trusted the request body. This endpoint is public and unauthenticated by
 *    design — Pesapal calls it — so anyone could POST
 *    {"order_tracking_id":"<guessable>","payment_status":"Completed"} and have
 *    an arbitrary order marked paid without paying. There is no shared secret or
 *    signature to check, which is precisely why the status must be fetched
 *    rather than accepted.
 *
 * The fix: take ONLY the tracking id from the request, then ask Pesapal what
 * actually happened via GetTransactionStatus.
 */

require_once __DIR__ . '/includes/pesapal.php';
require_once __DIR__ . '/includes/Bulk_payment.php';
require_once __DIR__ . '/includes/payment_ledger.php';

// Pesapal retries on any non-200, so this must answer 200 even on our own
// errors — otherwise a bug here turns into an endless retry loop. Failures are
// logged and reconciled later by api/payment.php?action=verify.
function ipnDone(string $note, array $extra = []): void {
    http_response_code(200);
    header('Content-Type: application/json');
    echo json_encode(array_merge(['status' => 'ok', 'note' => $note], $extra));
    exit;
}

$raw = file_get_contents('php://input');
$body = json_decode($raw, true) ?: [];

// Pesapal has used both POST JSON and GET query parameters, and has varied the
// casing of these keys across versions. Accept every spelling.
function ipnParam(array $body, array $keys): string {
    foreach ($keys as $k) {
        if (isset($body[$k]) && trim((string)$body[$k]) !== '') return trim((string)$body[$k]);
        if (isset($_GET[$k]) && trim((string)$_GET[$k]) !== '')  return trim((string)$_GET[$k]);
        if (isset($_POST[$k]) && trim((string)$_POST[$k]) !== '') return trim((string)$_POST[$k]);
    }
    return '';
}

$trackingId = ipnParam($body, ['OrderTrackingId', 'order_tracking_id', 'orderTrackingId']);
$merchantRef = ipnParam($body, ['OrderMerchantReference', 'order_merchant_reference', 'merchant_reference']);
$notificationType = ipnParam($body, ['OrderNotificationType', 'order_notification_type']);

error_log("Pesapal IPN: tracking=$trackingId ref=$merchantRef type=$notificationType raw=" . substr($raw, 0, 500));

if ($trackingId === '') {
    error_log('Pesapal IPN: no tracking id in payload, ignoring.');
    ipnDone('missing tracking id');
}

// NOTE: any `payment_status` in the request is ignored on purpose. See above.

// $mapped is NOT resolved here. Deciding 'paid' requires knowing what the
// order costs, and which order this even is has not been established yet --
// it could be a Bulk order or a regular one, in different tables with
// different total columns. Each branch below calls mapStatusForOrder() once it
// has the row, so the amount Pesapal reports is checked against that order's
// own total rather than accepted on the strength of a status code alone.
try {
    $pesapal = new PesapalClient();
    $status = $pesapal->getTransactionStatus($trackingId);
} catch (Throwable $e) {
    error_log("Pesapal IPN status lookup failed for $trackingId: " . $e->getMessage());
    // 200 so Pesapal stops retrying; the order stays unreconciled and the app's
    // verify call will settle it.
    ipnDone('status lookup failed');
}

// Locate the order. The tracking id is the reliable key; the merchant reference
// is `<orderid>-<timestamp>` and is used only as a fallback.
try {
    // Bulk orders live in their own table, so the tracking id has to be
    // looked for in both. Checked first when the merchant reference says so:
    // it is written as `SUR-<id>-<timestamp>` by api/payment.php precisely
    // because the two id spaces overlap and a bare id would be ambiguous.
    $looksBulk = stripos($merchantRef, 'SUR-') === 0;

    $Bulk = $dbh->prepare("SELECT id, payment_status, user_id, points_redeemed,
                                  total_price, delivery_fee, loyalty_discount
                             FROM Bulk_orders WHERE pesapal_tracking_id = ?");
    $Bulk->execute([$trackingId]);
    $BulkOrder = $Bulk->fetch(PDO::FETCH_ASSOC);

    if (!$BulkOrder && $looksBulk) {
        $idFromRef = (int)(explode('-', $merchantRef)[1] ?? 0);
        if ($idFromRef > 0) {
            $Bulk = $dbh->prepare("SELECT id, payment_status, user_id, points_redeemed,
                                          total_price, delivery_fee, loyalty_discount
                                     FROM Bulk_orders WHERE id = ?");
            $Bulk->execute([$idFromRef]);
            $BulkOrder = $Bulk->fetch(PDO::FETCH_ASSOC);
        }
    }

    if ($BulkOrder) {
        $BulkId = (int)$BulkOrder['id'];
        if (strcasecmp((string)$BulkOrder['payment_status'], 'paid') === 0) {
            ipnDone('already paid', ['Bulk_order_id' => $BulkId]);
        }

        $mapped = $pesapal->mapStatusForOrder(
            $status, BulkPayableTotal($BulkOrder), "Bulk order $BulkId (IPN)"
        );
        applyBulkPaymentStatus($dbh, $BulkId, $mapped, $trackingId);

        if ($mapped === 'paid' && (int)($BulkOrder['points_redeemed'] ?? 0) > 0) {
            require_once __DIR__ . '/includes/loyalty.php';
            settleLoyaltyRedemption($dbh, (int)$BulkOrder['user_id'], 'Bulk', $BulkId, (int)$BulkOrder['points_redeemed']);
        }

        $channel = $status['payment_method'] ?? null;
        if ($mapped === 'paid') {
            $dbh->prepare(
                "UPDATE Bulk_orders SET payment_method = ?, payment_channel = ?
                  WHERE id = ? AND payment_method IN ('unknown','mobile_money','card')"
            )->execute([classifyPaymentChannel($channel), $channel, $BulkId]);
        }
        recordPaymentEvent($dbh, 'Bulk', $BulkId, 'ipn', [
            'from_status' => $BulkOrder['payment_status'] ?? null,
            'to_status'   => $mapped,
            'method'      => classifyPaymentChannel($channel),
            'channel'     => $channel,
            'tracking_id' => $trackingId,
            'merchant_ref'=> $merchantRef ?: null,
            'actor_type'  => 'pesapal',
            'raw'         => json_encode($status),
        ]);
        error_log("Pesapal IPN: Bulk order $BulkId set to $mapped.");
        ipnDone('processed', ['Bulk_order_id' => $BulkId, 'payment_status' => $mapped]);
    }

    $stmt = $dbh->prepare("SELECT orderid, payment_status, user_id, points_redeemed, total_amount FROM orders WHERE pesapal_tracking_id = ?");
    $stmt->execute([$trackingId]);
    $order = $stmt->fetch(PDO::FETCH_ASSOC);

    if (!$order && $merchantRef !== '') {
        $orderIdFromRef = (int)explode('-', $merchantRef)[0];
        if ($orderIdFromRef > 0) {
            $stmt = $dbh->prepare("SELECT orderid, payment_status, user_id, points_redeemed, total_amount FROM orders WHERE orderid = ?");
            $stmt->execute([$orderIdFromRef]);
            $order = $stmt->fetch(PDO::FETCH_ASSOC);
        }
    }

    if (!$order) {
        error_log("Pesapal IPN: no order matches tracking=$trackingId ref=$merchantRef");
        ipnDone('no matching order');
    }

    $orderId = (int)$order['orderid'];

    // Idempotent: never rewrite a settled payment. A duplicate or late IPN must
    // not flip a paid order to failed.
    if (strcasecmp((string)$order['payment_status'], 'paid') === 0) {
        ipnDone('already paid', ['order_id' => $orderId]);
    }

    $mapped = $pesapal->mapStatusForOrder(
        $status, (float)$order['total_amount'], "order $orderId (IPN)"
    );

    if ($mapped === 'paid') {
        $stmt = $dbh->prepare("
            UPDATE orders
            SET payment_status = 'paid',
                payment_captured_at = NOW(),
                status = CASE WHEN status IN ('Awaiting Payment', 'Pending')
                              THEN 'Received' ELSE status END,
                pesapal_tracking_id = ?
            WHERE orderid = ?
        ");
        $stmt->execute([$trackingId, $orderId]);
        error_log("Pesapal IPN: order $orderId marked paid.");
    } elseif ($mapped === 'pending') {
        // Nothing decided yet — leave the row as it is.
        error_log("Pesapal IPN: order $orderId still pending.");
    } else {
        $stmt = $dbh->prepare("
            UPDATE orders SET payment_status = ?, pesapal_tracking_id = ? WHERE orderid = ?
        ");
        $stmt->execute([$mapped, $trackingId, $orderId]);
        error_log("Pesapal IPN: order $orderId set to $mapped.");
    }

    $channel = $status['payment_method'] ?? null;
    if ($mapped === 'paid') {
        $dbh->prepare(
            "UPDATE orders SET payment_method = ?, payment_channel = ?
              WHERE orderid = ? AND payment_method IN ('unknown','mobile_money','card')"
        )->execute([classifyPaymentChannel($channel), $channel, $orderId]);
    }

    if ($mapped === 'paid' && (int)($order['points_redeemed'] ?? 0) > 0 && !empty($order['user_id'])) {
        require_once __DIR__ . '/includes/loyalty.php';
        settleLoyaltyRedemption($dbh, (int)$order['user_id'], 'order', $orderId, (int)$order['points_redeemed']);
    }

    // Same notification api/payment.php's verify action sends — added here
    // for whenever the IPN host actually has a DNS record and this path
    // starts firing in practice (it doesn't today, per known ops gap).
    if (($mapped === 'paid' || $mapped === 'failed') && !empty($order['user_id'])) {
        try {
            require_once __DIR__ . '/includes/notifications.php';
            addNotification(
                (int)$order['user_id'],
                $mapped === 'paid' ? 'Payment confirmed' : 'Payment failed',
                $mapped === 'paid'
                    ? "Your payment for order #{$orderId} was confirmed."
                    : "Your payment for order #{$orderId} didn't go through. Tap to retry.",
                'order', null, ['push'],
                ['order_id' => (string)$orderId, 'source' => 'order']
            );
        } catch (Throwable $e) {
            error_log("Pesapal IPN notification failed for order $orderId: " . $e->getMessage());
        }
    }

    // Recorded even when $mapped changed nothing. An IPN that arrived and did
    // not move the order is exactly the evidence wanted in a dispute -- it
    // proves the notification was received and what it said.
    recordPaymentEvent($dbh, 'order', $orderId, 'ipn', [
        'from_status' => $order['payment_status'] ?? null,
        'to_status'   => $mapped,
        'method'      => classifyPaymentChannel($channel),
        'channel'     => $channel,
        'tracking_id' => $trackingId,
        'merchant_ref'=> $merchantRef ?: null,
        'actor_type'  => 'pesapal',
        'raw'         => json_encode($status),
    ]);

    ipnDone('processed', ['order_id' => $orderId, 'payment_status' => $mapped]);

} catch (Throwable $e) {
    error_log("Pesapal IPN DB error for $trackingId: " . $e->getMessage());
    ipnDone('db error');
}
