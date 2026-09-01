<?php
/**
 * api/payment.php — Pesapal payment initiation and verification.
 *
 * WHAT THIS REPLACES
 *
 * The previous version was a skeleton. `initiate` contained the comment "copy
 * your full Pesapal code here – I've omitted it for brevity" followed by a
 * hardcoded response with the literal string 'PESAPAL_TRACKING_ID' and a
 * payment_url of 'https://pay.pesapal.com/...'. `verify` returned
 * {"success":true,"status":"completed"} unconditionally — so any caller could
 * have an order reported as paid without a shilling moving.
 *
 * TWO RULES THIS FILE ENFORCES
 *
 * 1. The amount is read from the `orders` row, never from the request. The
 *    client used to send `amount`, which meant a customer could pay 100 UGX for
 *    a 100,000 UGX order by editing one field. Same class of bug as orders.php
 *    trusting `delivery_cost`.
 *
 * 2. Paid/unpaid is decided only by Pesapal's GetTransactionStatus. See
 *    includes/pesapal.php for why that matters.
 */

session_start();
require_once '../admin/includes/config.php';
require_once '../includes/pesapal.php';
require_once __DIR__ . '/../includes/Bulk_payment.php';
require_once __DIR__ . '/../includes/payment_ledger.php';
header('Content-Type: application/json');

error_reporting(E_ALL);
ini_set('display_errors', 0);
ini_set('log_errors', 1);

// ---------------------------------------------------------------------------
// Input helpers
// ---------------------------------------------------------------------------
$raw  = file_get_contents('php://input');
$json = json_decode($raw, true);

function param($key, $default = null) {
    global $json;
    if (is_array($json) && isset($json[$key])) return $json[$key];
    if (isset($_POST[$key])) return $_POST[$key];
    if (isset($_GET[$key]))  return $_GET[$key];
    return $default;
}

function reply(array $payload, int $httpCode = 200) {
    http_response_code($httpCode);
    echo json_encode($payload);
    exit;
}

function fail(string $message, int $httpCode = 200, ?string $code = null) {
    $body = ['success' => false, 'error' => $message];
    if ($code !== null) $body['error_code'] = $code;
    reply($body, $httpCode);
}

$action = param('action');
if (!$action && preg_match('#/payment/([a-z-]+)#', $_SERVER['REQUEST_URI'] ?? '', $m)) {
    $action = $m[1];
}
if (!$action) fail('Missing action parameter');

// Every action below concerns one customer's own order.
$userId = $_SESSION['user_id'] ?? null;
if (!$userId) fail('Not logged in', 401, 'UNAUTHENTICATED');

/**
 * Which table the order id refers to.
 *
 * 'shop' is the `orders` table and is the default, so every existing caller —
 * the customer checkout, the web front end — keeps working untouched.
 * 'Bulk' is `Bulk_orders`, a different table with different column names
 * and a payable total split across two columns. See includes/Bulk_payment.php.
 *
 * The two id spaces overlap: shop order 7 and Bulk order 7 both exist. That
 * is exactly why this is explicit rather than guessed from the id.
 */
$orderType = strtolower(trim((string)param('order_type', 'shop')));
if (!in_array($orderType, ['shop', 'bulk'], true)) {
    fail('Unknown order_type', 400, 'BAD_REQUEST');
}

/**
 * Loads an order belonging to the signed-in user.
 *
 * user_id sits in the WHERE clause deliberately: order ids come from
 * `500000 + rand(100,999)`, a 900-wide space that is trivial to enumerate, so
 * ownership can never be inferred from the id alone.
 */
function loadOwnedOrder(PDO $dbh, int $orderId, int $userId): array {
    $stmt = $dbh->prepare("
        SELECT orderid, user_id, total_amount, payment_status, status,
               pesapal_tracking_id, fname, lname, mobile, address, area,
               points_redeemed
        FROM orders
        WHERE orderid = ? AND user_id = ?
    ");
    $stmt->execute([$orderId, $userId]);
    $order = $stmt->fetch(PDO::FETCH_ASSOC);
    if (!$order) fail('Order not found', 404, 'ORDER_NOT_FOUND');
    return $order;
}

function loadUserEmail(PDO $dbh, int $userId): string {
    $stmt = $dbh->prepare("SELECT email FROM users WHERE id = ?");
    $stmt->execute([$userId]);
    return (string)($stmt->fetchColumn() ?: '');
}

function enforceCashLimit(float $amount, string $paymentMethod): void {
    $maxCash = 50000.0;
    if (strtolower($paymentMethod) === 'cash' && $amount > $maxCash) {
        fail('Cash payments are limited to UGX 50,000. Please use mobile money or card.', 400, 'CASH_LIMIT_EXCEEDED');
    }
}

/**
 * Applies a resolved Pesapal status to the order, once.
 *
 * Idempotent: an order already 'paid' is never rewritten, so a late IPN or a
 * duplicate verify cannot flip a settled payment back to failed.
 */
function applyPaymentStatus(PDO $dbh, int $orderId, string $mapped, ?string $trackingId): void {
    $current = $dbh->prepare("SELECT payment_status FROM orders WHERE orderid = ?");
    $current->execute([$orderId]);
    if (strcasecmp((string)$current->fetchColumn(), 'paid') === 0) {
        return;
    }

    if ($mapped === 'paid') {
        $stmt = $dbh->prepare("
            UPDATE orders
            SET payment_status = 'paid',
                payment_captured_at = NOW(),
                status = CASE WHEN status IN ('Awaiting Payment', 'Pending')
                              THEN 'Received' ELSE status END,
                pesapal_tracking_id = COALESCE(?, pesapal_tracking_id)
            WHERE orderid = ?
        ");
        $stmt->execute([$trackingId, $orderId]);
        return;
    }

    // Leave 'pending' alone rather than overwriting an in-flight attempt.
    if ($mapped === 'pending') return;

    $stmt = $dbh->prepare("
        UPDATE orders
        SET payment_status = ?, pesapal_tracking_id = COALESCE(?, pesapal_tracking_id)
        WHERE orderid = ?
    ");
    $stmt->execute([$mapped, $trackingId, $orderId]);
}

$pesapal = new PesapalClient();

switch ($action) {

    // -----------------------------------------------------------------------
    case 'initiate':
    // -----------------------------------------------------------------------
        $orderId = (int)param('order_id', 0);
        if ($orderId <= 0) fail('order_id is required', 400, 'BAD_REQUEST');

        if ($orderType === 'bulk') {
            $order = loadOwnedBulkOrder($dbh, $orderId, (int)$userId);
            if (!$order) fail('Order not found', 404, 'ORDER_NOT_FOUND');

            if (strcasecmp((string)$order['payment_status'], 'paid') === 0) {
                reply([
                    'success'  => true,
                    'status'   => 'paid',
                    'paid'     => true,
                    'order_id' => (string)$orderId,
                    'message'  => 'This order is already paid.',
                ]);
            }

            // A cancelled order still has a row, and without this check an
            // abandoned reservation that releaseStaleBulkReservations() has
            // already given back to stock could still be paid for.
            if (in_array(strtolower((string)$order['status']), ['cancelled', 'refunded'], true)) {
                fail('This order has been cancelled. Please order again.', 409, 'ORDER_CANCELLED');
            }

            // RULE 1 again: goods plus delivery, both read from the row.
            $amount = BulkPayableTotal($order);
            if ($amount <= 0) fail('This order has no payable total.', 409, 'INVALID_AMOUNT');

            $paymentMethod = strtolower(trim((string)param('payment_method', 'mobile_money')));
            enforceCashLimit((float)$amount, $paymentMethod);

            if ($paymentMethod === 'cash') {
                // payment_method is recorded now, not inferred later. Until
                // this column existed the only evidence an order was cash was
                // the transient 'pending_cash' status, which disappears the
                // moment the rider confirms collection.
                $stmt = $dbh->prepare("
                    UPDATE Bulk_orders
                       SET payment_status = 'pending_cash',
                           payment_method = 'cash',
                           status = CASE WHEN status = 'pending' THEN 'confirmed' ELSE status END,
                           confirmed_at = COALESCE(confirmed_at, NOW()),
                           updated_at = NOW()
                     WHERE id = ? AND user_id = ?
                ");
                $stmt->execute([$orderId, $userId]);

                recordPaymentEvent($dbh, 'Bulk', $orderId, 'cash_selected', [
                    'from_status' => $order['payment_status'] ?? null,
                    'to_status'   => 'pending_cash',
                    'method'      => 'cash',
                    'amount'      => $amount,
                    'actor_type'  => 'customer',
                    'actor_id'    => (int)$userId,
                ]);

                reply([
                    'success'  => true,
                    'status'   => 'pending_cash',
                    'paid'     => false,
                    'order_id' => (string)$orderId,
                    'amount'   => $amount,
                    'currency' => CURRENCY,
                    'message'  => 'Pay with cash when your order arrives.',
                ]);
            }

            // Prefixed so pesapal-ipn.php can tell which table to look in when
            // it has to fall back to the merchant reference. A bare id would be
            // ambiguous: shop order 41 and Bulk order 41 both exist.
            $merchantReference = 'SUR-' . $orderId . '-' . time();

            try {
                $result = $pesapal->submitOrder(
                    $merchantReference,
                    $amount,
                    'AfamFresh Bulk order #' . $orderId,
                    [
                        'email'      => (string)param('email', '') ?: (string)$order['email'],
                        'phone'      => (string)param('phone', '') ?: (string)$order['mobile'],
                        'first_name' => (string)$order['fname'],
                        'last_name'  => (string)$order['lname'],
                        'address'    => (string)$order['delivery_address'],
                        'city'       => (string)$order['delivery_area'],
                    ]
                );
            } catch (PesapalException $e) {
                error_log("Pesapal initiate failed for Bulk order $orderId: " . $e->getMessage());
                fail('We could not start the payment. Please try again in a moment.',
                     502, 'PESAPAL_UNAVAILABLE');
            }

            // Before replying, so an IPN that beats the response can be matched.
            $stmt = $dbh->prepare("
                UPDATE Bulk_orders
                   SET pesapal_tracking_id = ?,
                       payment_status = 'authorization_pending',
                       updated_at = NOW()
                 WHERE id = ? AND user_id = ?
            ");
            $stmt->execute([$result['order_tracking_id'], $orderId, $userId]);

            reply([
                'success'        => true,
                'status'         => 'pending',
                'paid'           => false,
                'order_id'       => (string)$orderId,
                'amount'         => $amount,
                'currency'       => CURRENCY,
                'redirect_url'   => $result['redirect_url'],
                'payment_url'    => $result['redirect_url'],
                'transaction_id' => $result['order_tracking_id'],
                'environment'    => PESAPAL_ENV,
            ]);
        }

        $order = loadOwnedOrder($dbh, $orderId, (int)$userId);

        // Already settled — do not send the customer to a payment page again.
        if (strcasecmp((string)$order['payment_status'], 'paid') === 0) {
            reply([
                'success'  => true,
                'status'   => 'paid',
                'paid'     => true,
                'order_id' => (string)$orderId,
                'message'  => 'This order is already paid.',
            ]);
        }

        // RULE 1: the amount is the stored order total. Any `amount` in the
        // request is deliberately ignored.
        $amount = (float)$order['total_amount'];
        if ($amount <= 0) {
            fail('This order has no payable total.', 409, 'INVALID_AMOUNT');
        }

        $paymentMethod = strtolower(trim((string)param('payment_method', 'mobile_money')));
        enforceCashLimit((float)$amount, $paymentMethod);

        // Cash on delivery never touches Pesapal.
        if ($paymentMethod === 'cash') {
            $stmt = $dbh->prepare("
                UPDATE orders
                SET payment_status = 'pending_cash',
                    payment_method = 'cash',
                    status = CASE WHEN status = 'Awaiting Payment'
                                  THEN 'Received' ELSE status END
                WHERE orderid = ? AND user_id = ?
            ");
            $stmt->execute([$orderId, $userId]);

            recordPaymentEvent($dbh, 'order', $orderId, 'cash_selected', [
                'from_status' => $order['payment_status'] ?? null,
                'to_status'   => 'pending_cash',
                'method'      => 'cash',
                'amount'      => $amount,
                'actor_type'  => 'customer',
                'actor_id'    => (int)$userId,
            ]);

            reply([
                'success'  => true,
                'status'   => 'pending_cash',
                'paid'     => false,
                'order_id' => (string)$orderId,
                'amount'   => $amount,
                'currency' => CURRENCY,
                'message'  => 'Pay with cash when your order arrives.',
            ]);
        }

        // A fresh merchant reference per attempt. Reusing the bare order id makes
        // Pesapal return the earlier (possibly failed) transaction instead of
        // opening a new one, which strands the customer on a dead page.
        $merchantReference = $orderId . '-' . time();

        try {
            $result = $pesapal->submitOrder(
                $merchantReference,
                $amount,
                'AfamFresh order #' . $orderId,
                [
                    'email'      => (string)param('email', '') ?: loadUserEmail($dbh, (int)$userId),
                    'phone'      => (string)param('phone', '') ?: (string)$order['mobile'],
                    'first_name' => (string)$order['fname'],
                    'last_name'  => (string)$order['lname'],
                    'address'    => (string)$order['address'],
                    'city'       => (string)$order['area'],
                ]
            );
        } catch (PesapalException $e) {
            error_log("Pesapal initiate failed for order $orderId: " . $e->getMessage());
            // Detail goes to the log; the customer gets a safe message.
            fail('We could not start the payment. Please try again in a moment.',
                 502, 'PESAPAL_UNAVAILABLE');
        }

        // Store the tracking id BEFORE replying, so an IPN arriving while the
        // customer is still on the payment page can be matched to this order.
        $stmt = $dbh->prepare("
            UPDATE orders
            SET pesapal_tracking_id = ?,
                payment_authorization_id = ?,
                payment_status = 'authorization_pending',
                payment_authorized_at = NOW()
            WHERE orderid = ? AND user_id = ?
        ");
        $stmt->execute([$result['order_tracking_id'], $merchantReference, $orderId, $userId]);

        reply([
            'success'        => true,
            'status'         => 'pending',
            'paid'           => false,
            'order_id'       => (string)$orderId,
            'amount'         => $amount,
            'currency'       => CURRENCY,
            // Both keys carry the same value: the Android app reads
            // redirect_url, older web code reads payment_url.
            'redirect_url'   => $result['redirect_url'],
            'payment_url'    => $result['redirect_url'],
            'transaction_id' => $result['order_tracking_id'],
            'environment'    => PESAPAL_ENV,
        ]);
        break;

    // -----------------------------------------------------------------------
    case 'verify':
    case 'status':
    // -----------------------------------------------------------------------
        // Accepts a tracking id or an order id: the app knows the order, the
        // WebView callback knows the tracking id.
        $trackingId = trim((string)param('transaction_id', param('order_tracking_id', '')));
        $orderId    = (int)param('order_id', 0);

        if ($trackingId === '' && $orderId <= 0) {
            fail('transaction_id or order_id is required', 400, 'BAD_REQUEST');
        }

        if ($orderType === 'bulk') {
            if ($orderId > 0) {
                $order = loadOwnedBulkOrder($dbh, $orderId, (int)$userId);
                if (!$order) fail('Order not found', 404, 'ORDER_NOT_FOUND');
                if ($trackingId === '') $trackingId = (string)$order['pesapal_tracking_id'];
            } else {
                $stmt = $dbh->prepare(
                    "SELECT id FROM Bulk_orders WHERE pesapal_tracking_id = ? AND user_id = ?"
                );
                $stmt->execute([$trackingId, $userId]);
                $orderId = (int)($stmt->fetchColumn() ?: 0);
                if ($orderId === 0) fail('Order not found', 404, 'ORDER_NOT_FOUND');
                $order = loadOwnedBulkOrder($dbh, $orderId, (int)$userId);
                if (!$order) fail('Order not found', 404, 'ORDER_NOT_FOUND');
            }

            if (strcasecmp((string)$order['payment_status'], 'pending_cash') === 0) {
                reply([
                    'success'  => true,
                    'status'   => 'pending_cash',
                    'paid'     => false,
                    'order_id' => (string)$orderId,
                    'message'  => 'This order will be paid in cash on delivery.',
                ]);
            }

            if (strcasecmp((string)$order['payment_status'], 'paid') === 0) {
                reply([
                    'success'        => true,
                    'status'         => 'paid',
                    'paid'           => true,
                    'order_id'       => (string)$orderId,
                    'transaction_id' => $trackingId ?: null,
                ]);
            }

            if ($trackingId === '') {
                reply([
                    'success'  => true,
                    'status'   => 'pending',
                    'paid'     => false,
                    'order_id' => (string)$orderId,
                    'message'  => 'No payment has been started for this order yet.',
                ]);
            }

            try {
                $status = $pesapal->getTransactionStatus($trackingId);
            } catch (PesapalException $e) {
                error_log("Pesapal verify failed for Bulk order $orderId: " . $e->getMessage());
                fail('We could not confirm the payment yet. Please try again shortly.',
                     502, 'VERIFY_UNAVAILABLE');
            }

            $mapped = $pesapal->mapStatusForOrder(
                $status, BulkPayableTotal($order), "Bulk order $orderId"
            );
            applyBulkPaymentStatus($dbh, $orderId, $mapped, $trackingId);

            if ($mapped === 'paid' && (int)($order['points_redeemed'] ?? 0) > 0) {
                require_once __DIR__ . '/../includes/loyalty.php';
                settleLoyaltyRedemption($dbh, (int)$userId, 'Bulk', $orderId, (int)$order['points_redeemed']);
            }

            // Persist how it was paid. Pesapal's own wording was already being
            // read here and returned to the app, then thrown away -- so the one
            // authoritative statement of the instrument used was never stored.
            $channel = $status['payment_method'] ?? null;
            if ($mapped === 'paid') {
                $dbh->prepare(
                    "UPDATE Bulk_orders
                        SET payment_method = ?, payment_channel = ?
                      WHERE id = ? AND payment_method IN ('unknown','mobile_money','card')"
                )->execute([classifyPaymentChannel($channel), $channel, $orderId]);
            }

            recordPaymentEvent($dbh, 'Bulk', $orderId, 'verified', [
                'from_status' => $order['payment_status'] ?? null,
                'to_status'   => $mapped,
                'method'      => classifyPaymentChannel($channel),
                'channel'     => $channel,
                'amount'      => isset($status['amount']) ? (float)$status['amount'] : BulkPayableTotal($order),
                'tracking_id' => $trackingId,
                'actor_type'  => 'customer',
                'actor_id'    => (int)$userId,
                'raw'         => json_encode($status),
            ]);

            reply([
                'success'        => true,
                'status'         => $mapped,
                'paid'           => $mapped === 'paid',
                'order_id'       => (string)$orderId,
                'transaction_id' => $trackingId,
                'amount'         => isset($status['amount'])
                                        ? (float)$status['amount']
                                        : BulkPayableTotal($order),
                'currency'       => $status['currency'] ?? CURRENCY,
                'method'         => $status['payment_method'] ?? null,
                'description'    => $status['payment_status_description'] ?? null,
            ]);
        }

        if ($orderId > 0) {
            $order = loadOwnedOrder($dbh, $orderId, (int)$userId);
            if ($trackingId === '') $trackingId = (string)$order['pesapal_tracking_id'];
        } else {
            // Resolve the order from the tracking id, still scoped to this user.
            $stmt = $dbh->prepare(
                "SELECT orderid FROM orders WHERE pesapal_tracking_id = ? AND user_id = ?"
            );
            $stmt->execute([$trackingId, $userId]);
            $orderId = (int)($stmt->fetchColumn() ?: 0);
            if ($orderId === 0) fail('Order not found', 404, 'ORDER_NOT_FOUND');
            $order = loadOwnedOrder($dbh, $orderId, (int)$userId);
        }

        // Cash orders have no Pesapal transaction to check.
        if (strcasecmp((string)$order['payment_status'], 'pending_cash') === 0) {
            reply([
                'success'  => true,
                'status'   => 'pending_cash',
                'paid'     => false,
                'order_id' => (string)$orderId,
                'message'  => 'This order will be paid in cash on delivery.',
            ]);
        }

        // Already reconciled — no need to ask Pesapal again.
        if (strcasecmp((string)$order['payment_status'], 'paid') === 0) {
            reply([
                'success'  => true,
                'status'   => 'paid',
                'paid'     => true,
                'order_id' => (string)$orderId,
                'transaction_id' => $trackingId ?: null,
            ]);
        }

        if ($trackingId === '') {
            reply([
                'success'  => true,
                'status'   => 'pending',
                'paid'     => false,
                'order_id' => (string)$orderId,
                'message'  => 'No payment has been started for this order yet.',
            ]);
        }

        try {
            $status = $pesapal->getTransactionStatus($trackingId);
        } catch (PesapalException $e) {
            error_log("Pesapal verify failed for order $orderId: " . $e->getMessage());
            // Deliberately NOT reporting 'failed': we do not know the outcome,
            // and telling the app it failed could show a paying customer an
            // error and invite a double payment.
            fail('We could not confirm the payment yet. Please try again shortly.',
                 502, 'VERIFY_UNAVAILABLE');
        }

        $mapped = $pesapal->mapStatusForOrder(
            $status, (float)$order['total_amount'], "order $orderId"
        );
        applyPaymentStatus($dbh, $orderId, $mapped, $trackingId);

        if ($mapped === 'paid' && (int)($order['points_redeemed'] ?? 0) > 0) {
            require_once __DIR__ . '/../includes/loyalty.php';
            settleLoyaltyRedemption($dbh, (int)$userId, 'order', $orderId, (int)$order['points_redeemed']);
        }

        // Told once the status is settled — 'pending' is still in flight and
        // says nothing worth interrupting the customer for. This is the path
        // that actually fires in practice: the app polls this action
        // directly (PaymentConfirmingScreen), unlike the IPN/callback paths,
        // which have their own equivalent notification calls for whenever
        // those become reachable.
        if ($mapped === 'paid' || $mapped === 'failed') {
            try {
                require_once __DIR__ . '/../includes/notifications.php';
                addNotification(
                    (int)$userId,
                    $mapped === 'paid' ? 'Payment confirmed' : 'Payment failed',
                    $mapped === 'paid'
                        ? "Your payment for order #{$orderId} was confirmed."
                        : "Your payment for order #{$orderId} didn't go through. Tap to retry.",
                    'order', null, ['push'],
                    ['order_id' => (string)$orderId, 'source' => 'order']
                );
            } catch (Throwable $e) {
                error_log("payment verify notification failed for order $orderId: " . $e->getMessage());
            }
        }

        // Same as the Bulk branch: record the instrument rather than
        // reporting it to the app and discarding it. The guard on
        // payment_method stops a verify overwriting a confirmed cash collection.
        $channel = $status['payment_method'] ?? null;
        if ($mapped === 'paid') {
            $dbh->prepare(
                "UPDATE orders
                    SET payment_method = ?, payment_channel = ?
                  WHERE orderid = ? AND payment_method IN ('unknown','mobile_money','card')"
            )->execute([classifyPaymentChannel($channel), $channel, $orderId]);
        }

        recordPaymentEvent($dbh, 'order', $orderId, 'verified', [
            'from_status' => $order['payment_status'] ?? null,
            'to_status'   => $mapped,
            'method'      => classifyPaymentChannel($channel),
            'channel'     => $channel,
            'amount'      => isset($status['amount']) ? (float)$status['amount'] : (float)$order['total_amount'],
            'tracking_id' => $trackingId,
            'actor_type'  => 'customer',
            'actor_id'    => (int)$userId,
            'raw'         => json_encode($status),
        ]);

        reply([
            'success'        => true,
            'status'         => $mapped,
            'paid'           => $mapped === 'paid',
            'order_id'       => (string)$orderId,
            'transaction_id' => $trackingId,
            'amount'         => isset($status['amount'])
                                    ? (float)$status['amount']
                                    : (float)$order['total_amount'],
            'currency'       => $status['currency'] ?? CURRENCY,
            'method'         => $status['payment_method'] ?? null,
            'description'    => $status['payment_status_description'] ?? null,
        ]);
        break;

    // -----------------------------------------------------------------------
    default:
        fail('Invalid action');
}
