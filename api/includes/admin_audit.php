<?php
/**
 * includes/admin_audit.php — the append-only "who did what" log.
 *
 * Modelled on includes/payment_ledger.php's recordPaymentEvent(): an audit
 * write must never fail the real action it's describing, so this always
 * swallows its own exceptions and just logs them.
 */

/**
 * @param string      $action      e.g. 'order.status_changed', 'product.price_updated', 'admin.created'
 * @param string|null $targetType  e.g. 'order', 'product', 'admin', 'vendor', 'config'
 * @param string|null $targetId    Not necessarily numeric (e.g. a config key) — always cast to string.
 * @param string      $description Human-readable summary shown in the audit viewer.
 */
function logAdminAction(
    PDO $dbh, string $action, ?string $targetType, ?string $targetId, string $description
): void {
    try {
        $stmt = $dbh->prepare(
            "INSERT INTO admin_action_log
                (admin_id, admin_name, action, target_type, target_id, description, ip_address)
             VALUES (?, ?, ?, ?, ?, ?, ?)"
        );
        $stmt->execute([
            $_SESSION['admin_id'] ?? null,
            (string)($_SESSION['admin_name'] ?? 'unknown'),
            $action,
            $targetType,
            $targetId !== null ? (string)$targetId : null,
            $description,
            $_SERVER['REMOTE_ADDR'] ?? null,
        ]);
    } catch (Throwable $e) {
        // Deliberately swallowed -- an audit write must never fail the action
        // it describes -- but not quietly. Every line this catch emits is an
        // admin action that happened and left no record, so the log is the
        // only remaining evidence it occurred at all. Tagged CRITICAL and
        // carrying who/what/which so it can be alerted on and reconstructed;
        // a bare "failed" tells whoever reads it nothing they can act on.
        error_log(sprintf(
            "CRITICAL: admin action NOT audited — action=%s target=%s/%s admin_id=%s admin_name=%s ip=%s error=%s",
            $action,
            $targetType ?? '-',
            $targetId !== null ? (string)$targetId : '-',
            (string)($_SESSION['admin_id'] ?? '-'),
            (string)($_SESSION['admin_name'] ?? 'unknown'),
            $_SERVER['REMOTE_ADDR'] ?? '-',
            $e->getMessage()
        ));
    }
}
