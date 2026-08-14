<?php
// =============================================================
// includes/loyalty.php
//
// Loyalty points: earn on delivery, redeem at checkout, admin-adjust.
//
// `users.loyalty_points` is the fast-read cached balance — nothing that only
// wants "how many points does this person have" should have to sum a ledger
// for it. Every event that MOVES that balance also writes an append-only row
// to `loyalty_ledger`, modelled directly on `payment_events`
// (includes/payment_ledger.php), so a balance is always explainable after
// the fact instead of being a single number nobody can audit.
//
// Every function here runs INSIDE THE CALLER'S transaction — none of them
// open their own — matching creditRiderEarnings()/creditVendorEarnings()'s
// convention exactly, since they're always called from inside an existing
// beginTransaction()/commit() block (a delivery transition, an order
// creation, a payment confirmation).
// =============================================================

/**
 * Loyalty settings, cached for the request. Defensive fallback even though
 * the row is pre-seeded by migration — belt-and-suspenders, not trust that
 * the seed row survives forever, matching getDeliveryPricingConfig()'s own
 * shape.
 */
function getLoyaltySettings(PDO $dbh): array {
    static $cached = null;
    if ($cached !== null) {
        return $cached;
    }

    try {
        $row = $dbh->query(
            "SELECT earn_rate_ugx_per_point, redeem_value_ugx_per_point,
                    min_redeemable_points, max_redeem_percent
               FROM loyalty_settings ORDER BY id LIMIT 1"
        )->fetch(PDO::FETCH_ASSOC);
    } catch (PDOException $e) {
        error_log('getLoyaltySettings failed: ' . $e->getMessage());
        $row = false;
    }

    if (!$row) {
        // A redemption is real money — these defaults exist only so the
        // system degrades to "conservative" rather than "broken" if the
        // seeded row is ever somehow missing, not as a value anyone should
        // rely on in normal operation.
        $cached = [
            'earn_rate_ugx_per_point'    => 1000.0,
            'redeem_value_ugx_per_point' => 50.0,
            'min_redeemable_points'      => 100,
            'max_redeem_percent'         => 30.0,
        ];
        return $cached;
    }

    $cached = [
        'earn_rate_ugx_per_point'    => (float)$row['earn_rate_ugx_per_point'],
        'redeem_value_ugx_per_point' => (float)$row['redeem_value_ugx_per_point'],
        'min_redeemable_points'      => (int)$row['min_redeemable_points'],
        'max_redeem_percent'         => (float)$row['max_redeem_percent'],
    ];
    return $cached;
}

/**
 * Goods value for an order — the figure points are earned on and redeemed
 * against. One place, so the earn path and the redeem-quote path can never
 * compute this two different ways.
 *
 * Shop orders: total_amount already includes delivery_fee (which itself
 * already bundles service/insurance/processing/mileage into one number —
 * see api/orders.php's create action), so goods value is the difference.
 * Bulk orders: total_price is already goods-only; delivery_fee is a
 * separate column there from the start.
 */
function goodsValueForOrder(PDO $dbh, string $source, int $orderId): ?float {
    if ($source === 'order') {
        $stmt = $dbh->prepare("SELECT total_amount, delivery_fee FROM orders WHERE orderid = ?");
        $stmt->execute([$orderId]);
        $row = $stmt->fetch(PDO::FETCH_ASSOC);
        if (!$row) return null;
        return max(0.0, (float)$row['total_amount'] - (float)$row['delivery_fee']);
    }

    if ($source === 'Bulk') {
        $stmt = $dbh->prepare("SELECT total_price FROM Bulk_orders WHERE id = ?");
        $stmt->execute([$orderId]);
        $row = $stmt->fetch(PDO::FETCH_ASSOC);
        if (!$row) return null;
        return max(0.0, (float)$row['total_price']);
    }

    error_log("goodsValueForOrder: unknown source '$source' for order $orderId");
    return null;
}

/**
 * Read-only. Works out how many of a customer's REQUESTED points can
 * actually be redeemed against one order, and the resulting discount.
 *
 * Called from BOTH the checkout quote endpoint (api/loyalty-quote.php) and
 * the actual order-creation call sites, so a preview and a real charge can
 * never disagree for the same requested amount — there is exactly one place
 * this math happens.
 *
 * @return array{points_applied:int, discount:float, capped:bool}
 */
function quoteLoyaltyRedemption(PDO $dbh, int $userId, int $requestedPoints, float $goodsValue): array {
    if ($requestedPoints <= 0 || $goodsValue <= 0) {
        return ['points_applied' => 0, 'discount' => 0.0, 'capped' => false];
    }

    $settings = getLoyaltySettings($dbh);

    $balanceStmt = $dbh->prepare("SELECT loyalty_points FROM users WHERE id = ?");
    $balanceStmt->execute([$userId]);
    $balance = (int)($balanceStmt->fetchColumn() ?: 0);

    $points = min($requestedPoints, $balance);
    if ($points < $settings['min_redeemable_points']) {
        return ['points_applied' => 0, 'discount' => 0.0, 'capped' => false];
    }

    $discount = $points * $settings['redeem_value_ugx_per_point'];
    $maxDiscount = $goodsValue * ($settings['max_redeem_percent'] / 100);

    $capped = false;
    if ($discount > $maxDiscount) {
        // Rounded down so the points shown and the discount shown always
        // agree exactly — redeeming points for a discount that then gets
        // silently clamped is a UX confusion, not just a number mismatch.
        $points = (int)floor($maxDiscount / $settings['redeem_value_ugx_per_point']);
        $discount = $points * $settings['redeem_value_ugx_per_point'];
        $capped = true;
    }

    return ['points_applied' => $points, 'discount' => round($discount, 2), 'capped' => $capped];
}

/**
 * Awards points for a delivered order. Idempotent per (source, order_id) —
 * safe to call from more than one code path (a Bulk order can reach
 * 'delivered' via api/rider.php or api/Bulk-orders.php's PUT action) and
 * safe to retry.
 *
 * Unlike a failed rider/vendor credit, a failed earn must NEVER fail the
 * delivery transition — not paying a rider for real work is a genuine
 * fault; a customer not earning loyalty points is not in the same tier.
 * Callers should log and continue on ok=false, not abort.
 *
 * @return array{ok:bool, points_awarded:int, error:?string}
 */
function earnLoyaltyPoints(PDO $dbh, int $userId, string $source, int $orderId, float $goodsValue): array {
    if (!in_array($source, ['order', 'Bulk'], true)) {
        return ['ok' => false, 'points_awarded' => 0, 'error' => 'Unknown order source.'];
    }

    $existing = $dbh->prepare(
        "SELECT id FROM loyalty_ledger WHERE source = ? AND order_id = ? AND event = 'earned'"
    );
    $existing->execute([$source, $orderId]);
    if ($existing->fetchColumn()) {
        return ['ok' => true, 'points_awarded' => 0, 'error' => null];
    }

    $settings = getLoyaltySettings($dbh);
    $points = (int)floor($goodsValue / $settings['earn_rate_ugx_per_point']);

    if ($points <= 0) {
        // A genuinely small order earning nothing is a valid outcome, not
        // an error — but still worth a ledger row so a support conversation
        // ("why didn't I earn anything on this order?") has an answer
        // rather than silence. Uses balance_after = current balance since
        // nothing moved.
        $points = 0;
    }

    try {
        $balanceStmt = $dbh->prepare("UPDATE users SET loyalty_points = loyalty_points + ? WHERE id = ?");
        $balanceStmt->execute([$points, $userId]);

        $afterStmt = $dbh->prepare("SELECT loyalty_points FROM users WHERE id = ?");
        $afterStmt->execute([$userId]);
        $balanceAfter = (int)($afterStmt->fetchColumn() ?: 0);

        $dbh->prepare(
            "INSERT INTO loyalty_ledger
                (user_id, source, order_id, event, points_delta, balance_after, actor_type, actor_id)
             VALUES (?, ?, ?, 'earned', ?, ?, 'system', NULL)"
        )->execute([$userId, $source, $orderId, $points, $balanceAfter]);

        return ['ok' => true, 'points_awarded' => $points, 'error' => null];
    } catch (PDOException $e) {
        if ($e->getCode() === '23000') {
            // Concurrent request won the same UNIQUE race — already credited.
            return ['ok' => true, 'points_awarded' => 0, 'error' => null];
        }
        error_log("earnLoyaltyPoints failed for $source order $orderId (user $userId): " . $e->getMessage());
        return ['ok' => false, 'points_awarded' => 0, 'error' => 'Could not record loyalty points.'];
    }
}

/**
 * Debits the points an order recorded an intent to redeem, once its payment
 * is confirmed. Idempotent per (source, order_id) — safe across the three
 * separate payment-confirmation call sites (api/payment.php's verify,
 * pesapal-ipn.php, pesapal-callback.php) in case more than one ever fires
 * for the same order.
 *
 * Re-validates the balance at settlement time rather than trusting the
 * number recorded on the order at creation: if a customer placed two orders
 * each claiming their whole balance before paying either, the balance may
 * have already been spent by the first settlement. Clamps to whatever
 * remains rather than driving the balance negative — the same class of
 * low-severity, already-accepted race as this session's route-recalculation
 * double-fetch case. No row lock; this codebase doesn't use one for this
 * class of problem elsewhere either.
 *
 * MUST NEVER THROW — matches recordPaymentEvent()'s own rule that a ledger
 * write must never fail a payment. Callers do not need their own try/catch.
 *
 * @return array{ok:bool, points_settled:int}
 */
function settleLoyaltyRedemption(PDO $dbh, int $userId, string $source, int $orderId, int $pointsRedeemed): array {
    if ($pointsRedeemed <= 0) {
        return ['ok' => true, 'points_settled' => 0];
    }

    try {
        if (!in_array($source, ['order', 'Bulk'], true)) {
            error_log("settleLoyaltyRedemption: unknown source '$source' for order $orderId");
            return ['ok' => false, 'points_settled' => 0];
        }

        $existing = $dbh->prepare(
            "SELECT id FROM loyalty_ledger WHERE source = ? AND order_id = ? AND event = 'redeemed'"
        );
        $existing->execute([$source, $orderId]);
        if ($existing->fetchColumn()) {
            return ['ok' => true, 'points_settled' => 0];
        }

        $balanceStmt = $dbh->prepare("SELECT loyalty_points FROM users WHERE id = ?");
        $balanceStmt->execute([$userId]);
        $balance = (int)($balanceStmt->fetchColumn() ?: 0);

        $toDebit = min($pointsRedeemed, $balance);
        if ($toDebit <= 0) {
            // Already spent elsewhere by the time this settled. Logged, not
            // silently dropped — the discount the customer already received
            // at checkout stands regardless; this only concerns the ledger.
            error_log("settleLoyaltyRedemption: user $userId has 0 points left to settle $pointsRedeemed against $source order $orderId");
            return ['ok' => true, 'points_settled' => 0];
        }

        $dbh->prepare("UPDATE users SET loyalty_points = loyalty_points - ? WHERE id = ?")
            ->execute([$toDebit, $userId]);

        $afterStmt = $dbh->prepare("SELECT loyalty_points FROM users WHERE id = ?");
        $afterStmt->execute([$userId]);
        $balanceAfter = (int)($afterStmt->fetchColumn() ?: 0);

        $dbh->prepare(
            "INSERT INTO loyalty_ledger
                (user_id, source, order_id, event, points_delta, balance_after, actor_type, actor_id)
             VALUES (?, ?, ?, 'redeemed', ?, ?, 'customer', ?)"
        )->execute([$userId, $source, $orderId, -$toDebit, $balanceAfter, $userId]);

        return ['ok' => true, 'points_settled' => $toDebit];
    } catch (Throwable $e) {
        if ($e instanceof PDOException && $e->getCode() === '23000') {
            return ['ok' => true, 'points_settled' => 0];
        }
        error_log("settleLoyaltyRedemption failed for $source order $orderId (user $userId): " . $e->getMessage());
        return ['ok' => false, 'points_settled' => 0];
    }
}

/**
 * Admin sets a user's balance to an absolute new value (matching the
 * existing edit-user.php form, which is a plain number field, not a
 * delta). Computes and records the delta in the ledger rather than just
 * overwriting silently.
 *
 * No uniqueness guard — unlike order-driven earn/redeem, an admin
 * adjustment is inherently intentional and may legitimately repeat.
 */
function adjustLoyaltyPointsAdmin(PDO $dbh, int $userId, int $newBalance, ?int $adminId, string $reason = ''): array {
    $currentStmt = $dbh->prepare("SELECT loyalty_points FROM users WHERE id = ?");
    $currentStmt->execute([$userId]);
    $current = $currentStmt->fetchColumn();
    if ($current === false) {
        return ['ok' => false, 'error' => 'User not found.'];
    }
    $current = (int)$current;

    $newBalance = max(0, $newBalance);
    $delta = $newBalance - $current;

    $dbh->prepare("UPDATE users SET loyalty_points = ? WHERE id = ?")
        ->execute([$newBalance, $userId]);

    // A ledger row promising "admin adjusted this" when nothing actually
    // moved would be misleading noise in the history view.
    if ($delta !== 0) {
        $dbh->prepare(
            "INSERT INTO loyalty_ledger
                (user_id, source, order_id, event, points_delta, balance_after, reason, actor_type, actor_id)
             VALUES (?, NULL, NULL, 'admin_adjust', ?, ?, ?, 'admin', ?)"
        )->execute([$userId, $delta, $newBalance, $reason !== '' ? $reason : null, $adminId]);
    }

    return ['ok' => true, 'error' => null, 'delta' => $delta];
}

/** A user's recent loyalty history, most recent first — for the admin panel. */
function loyaltyHistoryFor(PDO $dbh, int $userId, int $limit = 20): array {
    $stmt = $dbh->prepare(
        "SELECT * FROM loyalty_ledger WHERE user_id = ? ORDER BY created_at DESC LIMIT ?"
    );
    $stmt->bindValue(1, $userId, PDO::PARAM_INT);
    $stmt->bindValue(2, $limit, PDO::PARAM_INT);
    $stmt->execute();
    return $stmt->fetchAll(PDO::FETCH_ASSOC);
}