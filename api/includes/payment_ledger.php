<?php
// =============================================================
// includes/payment_ledger.php
//
// An append-only record of everything that happens to a payment.
//
// WHY
//
// `orders.payment_status` and `pesapal_tracking_id` are updated IN PLACE. A
// failed attempt followed by a successful one leaves no trace of the first, and
// each new attempt overwrites the previous tracking id. So when a customer says
// they were charged twice, or a Pesapal settlement line does not match an order,
// there is nothing to check against -- the evidence was overwritten by the thing
// you are trying to investigate.
//
// This table is never updated and never deleted from. A correction is a new row.
//
// THE ONE RULE
//
// A ledger write must NEVER fail a payment. Every call swallows its own
// exceptions and logs. Losing an audit row is bad; failing a customer's payment
// because the audit table was locked is worse, and the payment tables still
// hold the authoritative current state either way.
// =============================================================

/**
 * Appends one payment event.
 *
 * @param string $source 'order' | 'Bulk' — which table $orderId points at.
 *                       The two id spaces overlap, so this is never optional.
 * @param string $event  initiated | ipn | verified | cash_selected |
 *                       cash_collected | reversed | admin_adjust
 * @param array  $fields from_status, to_status, method, channel, amount,
 *                       tracking_id, merchant_ref, actor_type, actor_id, raw
 */
function recordPaymentEvent(PDO $dbh, string $source, int $orderId,
                            string $event, array $fields = []): void {
    try {
        if (!in_array($source, ['order', 'Bulk'], true)) {
            error_log("payment ledger: unknown source '$source' for order $orderId");
            return;
        }

        $stmt = $dbh->prepare(
            "INSERT INTO payment_events
                (source, order_id, event, from_status, to_status, method, channel,
                 amount, currency, tracking_id, merchant_ref, actor_type, actor_id, raw)
             VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
        );

        $method = $fields['method'] ?? 'unknown';
        if (!in_array($method, ['unknown', 'cash', 'mobile_money', 'card'], true)) {
            $method = 'unknown';
        }
        $actorType = $fields['actor_type'] ?? 'system';
        if (!in_array($actorType, ['customer', 'rider', 'admin', 'system', 'pesapal'], true)) {
            $actorType = 'system';
        }

        $stmt->execute([
            $source,
            $orderId,
            $event,
            $fields['from_status']  ?? null,
            $fields['to_status']    ?? null,
            $method,
            $fields['channel']      ?? null,
            $fields['amount']       ?? null,
            $fields['currency']     ?? (defined('CURRENCY') ? CURRENCY : 'UGX'),
            $fields['tracking_id']  ?? null,
            $fields['merchant_ref'] ?? null,
            $actorType,
            $fields['actor_id']     ?? null,
            // Truncated: a provider payload is useful for a dispute, but an
            // unbounded blob per event turns this table into the biggest thing
            // in the database within a month.
            isset($fields['raw']) ? mb_substr((string)$fields['raw'], 0, 2000) : null,
        ]);
    } catch (Throwable $e) {
        // Deliberately swallowed. See the header.
        error_log("payment ledger write failed ($source $orderId $event): " . $e->getMessage());
    }
}

/**
 * Classifies Pesapal's payment_method string into our audit bucket.
 *
 * Pesapal returns things like "MPESA", "AirtelMoney", "Visa", "MasterCard".
 * Only the card/not-card distinction matters for reconciliation — everything
 * else that settles through Pesapal is mobile money as far as the float is
 * concerned. Anything unrecognised becomes mobile_money rather than 'unknown',
 * because a payment that reached Pesapal definitively is not cash.
 */
function classifyPaymentChannel(?string $channel): string {
    if ($channel === null || trim($channel) === '') {
        return 'mobile_money';
    }
    return preg_match('/visa|master|card|amex/i', $channel) ? 'card' : 'mobile_money';
}