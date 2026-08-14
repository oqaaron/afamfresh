<?php
/**
 * pesapal-callback.php — where Pesapal returns the CUSTOMER'S BROWSER after
 * they finish (or abandon) a payment.
 *
 * This is a user-facing redirect, not a server-to-server notification. The
 * authoritative update is pesapal-ipn.php; this page exists so the customer
 * lands somewhere sensible. It still reconciles the order, because IPN can be
 * delayed — or, as things currently stand, undeliverable: the configured IPN
 * host has no DNS record.
 *
 * WHAT CHANGED
 *
 * The previous version did `$status = $_GET['status']` and wrote
 * `payment_status = ($status === 'Completed') ? 'paid' : 'failed'`. Pesapal does
 * not send a `status` query parameter, so every completed payment was recorded
 * as FAILED. And since the value came from the URL, appending
 * `?status=Completed` to this page marked an order paid for free.
 *
 * Status now comes from GetTransactionStatus only.
 *
 * The Android app does not depend on this page's HTML — PaymentWebViewScreen
 * detects the callback URL and then calls api/payment.php?action=verify itself.
 * The markup below is for the web checkout and for anyone who opens the link
 * outside the app.
 */

require_once __DIR__ . '/includes/pesapal.php';

$trackingId  = trim((string)($_GET['OrderTrackingId'] ?? $_GET['order_tracking_id'] ?? ''));
$merchantRef = trim((string)($_GET['OrderMerchantReference'] ?? $_GET['merchant_reference'] ?? ''));

/** Renders a minimal result page. No order details — this URL is shareable. */
function renderResult(string $heading, string $message, string $tone, ?int $orderId = null): void
{
    $colour = $tone === 'good' ? '#1B7F3B' : ($tone === 'bad' ? '#C62828' : '#B26A00');
    http_response_code(200);
    header('Content-Type: text/html; charset=utf-8');
    $orderLine = $orderId ? '<p class="ref">Order #' . (int)$orderId . '</p>' : '';
    echo <<<HTML
<!doctype html>
<html lang="en"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>AfamFresh payment</title>
<style>
  body { font-family: system-ui, -apple-system, "Segoe UI", sans-serif; background:#FAF7F2;
         margin:0; min-height:100vh; display:grid; place-items:center; padding:24px; }
  .card { background:#fff; border-radius:16px; padding:32px 28px; max-width:420px; width:100%;
          box-shadow:0 8px 30px rgba(0,0,0,.08); text-align:center; }
  h1 { color:{$colour}; font-size:1.35rem; margin:0 0 12px; }
  p { color:#4A4A4A; line-height:1.5; margin:0 0 8px; }
  .ref { color:#8A8A8A; font-size:.875rem; margin-top:16px; }
</style></head>
<body><div class="card">
  <h1>{$heading}</h1>
  <p>{$message}</p>
  {$orderLine}
  <p class="ref">You can close this page and return to the AfamFresh app.</p>
</div></body></html>
HTML;
    exit;
}

if ($trackingId === '') {
    renderResult(
        'Something went wrong',
        'We did not receive a payment reference. If you were charged, your order will still be updated automatically — please check the app in a few minutes.',
        'warn'
    );
}

// NOTE: any `status` in the query string is ignored on purpose. See above.

try {
    $pesapal = new PesapalClient();
    $status  = $pesapal->getTransactionStatus($trackingId);
    $mapped  = $pesapal->mapStatus($status);
} catch (Throwable $e) {
    error_log("Pesapal callback status lookup failed for $trackingId: " . $e->getMessage());
    renderResult(
        'Payment received — confirming',
        'We are still confirming this payment with Pesapal. Your order will update automatically; no need to pay again.',
        'warn'
    );
}

$orderId = null;
try {
    $stmt = $dbh->prepare("SELECT orderid, payment_status, user_id FROM orders WHERE pesapal_tracking_id = ?");
    $stmt->execute([$trackingId]);
    $order = $stmt->fetch(PDO::FETCH_ASSOC);

    if (!$order && $merchantRef !== '') {
        $fromRef = (int)explode('-', $merchantRef)[0];
        if ($fromRef > 0) {
            $stmt = $dbh->prepare("SELECT orderid, payment_status, user_id FROM orders WHERE orderid = ?");
            $stmt->execute([$fromRef]);
            $order = $stmt->fetch(PDO::FETCH_ASSOC);
        }
    }

    if ($order) {
        $orderId = (int)$order['orderid'];
        $alreadyPaid = strcasecmp((string)$order['payment_status'], 'paid') === 0;

        // Idempotent, same rule as the IPN handler.
        if (!$alreadyPaid) {
            if ($mapped === 'paid') {
                $stmt = $dbh->prepare("
                    UPDATE orders
                    SET payment_status = 'paid',
                        payment_captured_at = NOW(),
                        status = CASE WHEN status IN ('Awaiting Payment', 'Pending')
                                      THEN 'Received' ELSE status END
                    WHERE orderid = ?
                ");
                $stmt->execute([$orderId]);
            } elseif ($mapped !== 'pending') {
                $stmt = $dbh->prepare("UPDATE orders SET payment_status = ? WHERE orderid = ?");
                $stmt->execute([$mapped, $orderId]);
            }

            // Same notification api/payment.php's verify action sends.
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
                    error_log("Pesapal callback notification failed for order $orderId: " . $e->getMessage());
                }
            }
        }
    } else {
        error_log("Pesapal callback: no order matches tracking=$trackingId ref=$merchantRef");
    }
} catch (Throwable $e) {
    error_log("Pesapal callback DB error for $trackingId: " . $e->getMessage());
}

switch ($mapped) {
    case 'paid':
        renderResult('Payment successful',
            'Thank you. Your order is confirmed and we have started preparing it.',
            'good', $orderId);

    case 'failed':
        renderResult('Payment failed',
            'The payment did not go through and you have not been charged. You can try again from the app.',
            'bad', $orderId);

    case 'reversed':
        renderResult('Payment reversed',
            'This payment was reversed. Any amount taken will be returned by your provider.',
            'bad', $orderId);

    case 'invalid':
        renderResult('Payment could not be processed',
            'Pesapal reported this transaction as invalid. You have not been charged.',
            'bad', $orderId);

    default:
        renderResult('Payment pending',
            'Your payment is still being processed. Your order will update automatically once it clears — please do not pay again.',
            'warn', $orderId);
}
