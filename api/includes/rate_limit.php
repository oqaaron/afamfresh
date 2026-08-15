<?php
// =============================================================
// includes/rate_limit.php — plain DB-backed sliding-window rate limiter.
// =============================================================

/**
 * Records one hit for $bucket and reports whether the caller is over the
 * limit.
 *
 * Deliberately checks BEFORE counting whether this hit would exceed the
 * cap, then records it either way -- so a caller already over the limit
 * keeps resetting their own window with every retry rather than ever
 * getting a fresh one for free by spamming faster than the window drains.
 *
 * @param PDO    $dbh
 * @param string $bucket        e.g. "login:ip:1.2.3.4" or "login:id:someone@example.com"
 * @param int    $maxHits       hits allowed within the window
 * @param int    $windowSeconds window length
 * @return bool  true if the caller is over the limit and should be rejected
 */
function rateLimited(PDO $dbh, string $bucket, int $maxHits, int $windowSeconds): bool {
    try {
        $dbh->prepare("DELETE FROM rate_limit_hits WHERE bucket = ? AND created_at < DATE_SUB(NOW(), INTERVAL ? SECOND)")
            ->execute([$bucket, $windowSeconds]);

        $count = $dbh->prepare("SELECT COUNT(*) FROM rate_limit_hits WHERE bucket = ?");
        $count->execute([$bucket]);
        $hits = (int)$count->fetchColumn();

        $dbh->prepare("INSERT INTO rate_limit_hits (bucket) VALUES (?)")->execute([$bucket]);

        return $hits >= $maxHits;
    } catch (PDOException $e) {
        // A rate-limit check failing must never be the reason a real login
        // or quote request fails -- log it and let the request through,
        // same "must never fail the primary operation" rule this codebase
        // already applies to notifications/ledger writes.
        error_log('rateLimited() failed for bucket ' . $bucket . ': ' . $e->getMessage());
        return false;
    }
}

/** Sends the standard 429 response and stops. */
function failRateLimited(string $message = 'Too many attempts. Please wait a few minutes and try again.'): void {
    http_response_code(429);
    echo json_encode(['success' => false, 'error' => $message]);
    exit;
}
