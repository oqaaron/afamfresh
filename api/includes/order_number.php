<?php
/**
 * includes/order_number.php — the order number a person sees, vs. the id
 * the database actually uses.
 *
 * orders.orderid and Bulk_orders.id are plain AUTO_INCREMENT integers, and
 * stay that way everywhere internally — foreign keys (rider_assignments,
 * order_tracking_logs, payment_events, ...) all reference the bare integer,
 * and nothing here ever resets or alters that counter. This file exists only
 * so a display prefix, set on admin/configuration.php's "Order number
 * display" section, can be applied consistently wherever an order number is
 * actually shown to someone.
 */

/**
 * The configured display prefix, e.g. "AF". Empty string if none is set.
 *
 * Cached per-request (a static, not app_config's own per-request cache in
 * admin/includes/config.php, since this file may be required standalone by
 * scripts that do not go through that bootstrap).
 */
function orderNumberPrefix(PDO $dbh): string {
    static $cached = null;
    if ($cached !== null) return $cached;

    try {
        $stmt = $dbh->prepare("SELECT config_value FROM app_config WHERE config_key = 'order_number_prefix'");
        $stmt->execute();
        $cached = (string)($stmt->fetchColumn() ?: '');
    } catch (PDOException $e) {
        // A broken lookup should never block an SMS/notification from going
        // out over a cosmetic label — fall back to no prefix.
        error_log('orderNumberPrefix lookup failed: ' . $e->getMessage());
        $cached = '';
    }
    return $cached;
}

/**
 * Where this order was the Nth placed that calendar day, counting only
 * orders from the same channel (shop orders and Bulk orders each start
 * their own count at 1 every day — they live in separate tables with
 * separate id ranges, so sharing one sequence would not mean anything).
 *
 * Computed from existing data every call, not a separate counter column: an
 * order's id (already permanent and gap-free within a day, since ids are
 * assigned in insertion order) makes "how many same-day orders have an id
 * <= this one" an exact, race-free answer with nothing new to keep in sync.
 *
 * Date bounds are a half-open range on the indexed timestamp column
 * (ordertime / created_at), not DATE(column) = DATE(?), so this can still
 * use the existing index rather than scanning every row.
 */
function dailySequenceNumber(PDO $dbh, string $source, int $orderId, string $createdAt): int {
    $isBulk = ($source === 'Bulk');
    $table = $isBulk ? 'Bulk_orders' : 'orders';
    $idColumn = $isBulk ? 'id' : 'orderid';
    $dateColumn = $isBulk ? 'created_at' : 'ordertime';

    $timestamp = strtotime($createdAt) ?: time();
    $dayStart = date('Y-m-d 00:00:00', $timestamp);
    $dayEnd = date('Y-m-d 00:00:00', strtotime('+1 day', $timestamp));

    try {
        $stmt = $dbh->prepare(
            "SELECT COUNT(*) FROM $table
              WHERE $dateColumn >= ? AND $dateColumn < ? AND $idColumn <= ?"
        );
        $stmt->execute([$dayStart, $dayEnd, $orderId]);
        return max(1, (int)$stmt->fetchColumn());
    } catch (PDOException $e) {
        // Falls back to the real id — still unique, just not the pretty
        // reset-daily count. A broken lookup must never block a
        // notification going out over a cosmetic label.
        error_log("dailySequenceNumber lookup failed for $source #$orderId: " . $e->getMessage());
        return $orderId;
    }
}

/**
 * "AF-150826-001" (prefix-DDMMYY-daily sequence) if a prefix is configured,
 * otherwise "150826-001". The date is fixed to when the order was actually
 * PLACED, not today, and the sequence number is that channel's count for
 * that specific day — both stay the same forever for a given order, they
 * just are not derived from the real internal id at all past this point.
 *
 * @param string $source     'order' (shop) or 'Bulk' — which table/count to use.
 * @param string|null $createdAt Anything strtotime() understands (e.g. a
 *        DATETIME column value already in hand). Defaults to right now,
 *        which is only correct when called immediately after the order was
 *        created — pass the real value when formatting an older order.
 */
function formatOrderNumber(PDO $dbh, int $orderId, string $source = 'order', ?string $createdAt = null): string {
    // rtrim '-': a prefix saved before this format existed may already carry
    // its own trailing hyphen (the original placeholder text was "AF-").
    // Stripped so the assembled hyphens below never double up.
    $prefix = rtrim(orderNumberPrefix($dbh), '-');

    $timestamp = $createdAt !== null ? strtotime($createdAt) : false;
    $resolvedTimestamp = $timestamp !== false ? $timestamp : time();
    $resolvedCreatedAt = date('Y-m-d H:i:s', $resolvedTimestamp);
    $datePart = date('dmy', $resolvedTimestamp);

    $seq = dailySequenceNumber($dbh, $source, $orderId, $resolvedCreatedAt);
    $seqPart = str_pad((string)$seq, 3, '0', STR_PAD_LEFT);

    return $prefix !== '' ? "{$prefix}-{$datePart}-{$seqPart}" : "{$datePart}-{$seqPart}";
}
