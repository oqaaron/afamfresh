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
require_once __DIR__ . '/includes/surplus_payment.php';

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

try {
    $pesapal = new PesapalClient();
    $status = $pesapal->getTransactionStatus($trackingId);
    $mapped = $pesapal->mapStatus($status);
} catch (Throwable $e) {
    error_log("Pesapal IPN status lookup failed for $trackingId: " . $e->getMessage());
    // 200 so Pesapal stops retrying; the order stays unreconciled and the app's
    // verify call will settle it.
    ipnDone('status lookup failed');
}

// Locate the order. The tracking id is the reliable key; the merchant reference
// is `<orderid>-<timestamp>` and is used only as a fallback.
try {
    // Surplus orders live in their own table, so the tracking id has to be
    // looked for in both. Checked first when the merchant reference says so:
    // it is written as `SUR-<id>-<timestamp>` by api/payment.php precisely
    // because the two id spaces overlap and a bare id would be ambiguous.
    $looksSurplus = stripos($merchantRef, 'SUR-') === 0;

    $surplus = $dbh->prepare("SELECT id, payment_status FROM surplus_orders WHERE pesapal_tracking_id = ?");
    $surplus->execute([$trackingId]);
    $surplusOrder = $surplus->fetch(PDO::FETCH_ASSOC);

    if (!$surplusOrder && $looksSurplus) {
        $idFromRef = (int)(explode('-', $merchantRef)[1] ?? 0);
        if ($idFromRef > 0) {
            $surplus = $dbh->prepare("SELECT id, payment_status FROM surplus_orders WHERE id = ?");
            $surplus->execute([$idFromRef]);
            $surplusOrder = $surplus->fetch(PDO::FETCH_ASSOC);
        }
    }

    if ($surplusOrder) {
        $surplusId = (int)$surplusOrder['id'];
        if (strcasecmp((string)$surplusOrder['payment_status'], 'paid') === 0) {
            ipnDone('already paid', ['surplus_order_id' => $surplusId]);
        }
        applySurplusPaymentStatus($dbh, $surplusId, $mapped, $trackingId);
        error_log("Pesapal IPN: surplus order $surplusId set to $mapped.");
        ipnDone('processed', ['surplus_order_id' => $surplusId, 'payment_status' => $mapped]);
    }

    $stmt = $dbh->prepare("SELECT orderid, payment_status FROM orders WHERE pesapal_tracking_id = ?");
    $stmt->execute([$trackingId]);
    $order = $stmt->fetch(PDO::FETCH_ASSOC);

    if (!$order && $merchantRef !== '') {
        $orderIdFromRef = (int)explode('-', $merchantRef)[0];
        if ($orderIdFromRef > 0) {
            $stmt = $dbh->prepare("SELECT orderid, payment_status FROM orders WHERE orderid = ?");
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

    ipnDone('processed', ['order_id' => $orderId, 'payment_status' => $mapped]);

} catch (Throwable $e) {
    error_log("Pesapal IPN DB error for $trackingId: " . $e->getMessage());
    ipnDone('db error');
}
