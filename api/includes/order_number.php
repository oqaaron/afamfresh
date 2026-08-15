<?php
/**
 * includes/order_number.php — the order number a person sees, vs. the id
 * the database actually uses.
 *
 * orders.orderid and Bulk_orders.id are plain AUTO_INCREMENT integers, and
 * stay that way everywhere internally — foreign keys (rider_assignments,
 * order_tracking_logs, payment_events, ...) all reference the bare integer.
 * This file exists only so a display prefix, set on admin/configuration.php's
 * "Order number display" section, can be applied consistently wherever an
 * order number is actually shown to someone, without that prefix ever
 * touching a WHERE clause or a foreign key.
 */

/**
 * The configured display prefix, e.g. "AF-". Empty string if none is set.
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

/** "AF-500943" if a prefix is configured, otherwise plain "500943". */
function formatOrderNumber(PDO $dbh, int $orderId): string {
    $prefix = orderNumberPrefix($dbh);
    return $prefix !== '' ? $prefix . $orderId : (string)$orderId;
}
