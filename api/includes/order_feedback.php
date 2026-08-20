<?php
// =============================================================
// includes/order_feedback.php
//
// The CUSTOMER's confirm-and-rate step, for whichever table owns the order.
//
// Not to be confused with `delivery_confirmed` / `delivery_photo`, which are
// written by the RIDER's proof-of-delivery upload (saveDeliveryProof() in
// rider_dispatch.php) — that is the rider attesting they delivered it, not
// the customer agreeing they received it. This file is the other half.
//
// `completed_at` already existed on `orders` with nothing setting it; this
// is what it was for. Bulk_orders gained the same column, and the six
// rating columns alongside it, in the 2026-08-13 migration this file was
// added with.
// =============================================================

/**
 * Loads just enough of an order to decide whether the signed-in customer may
 * confirm it, and to guard against confirming twice.
 *
 * Scoped to user_id in the query itself, not checked afterwards — so asking
 * about an order that is not yours reads as "not found", the same answer as
 * an order id that does not exist, and reveals nothing about who owns it.
 *
 * @return array|null null when there is no such order owned by this user.
 */
function loadFeedbackTarget(PDO $dbh, string $source, int $orderId, int $userId): ?array {
    if ($source === 'order') {
        $stmt = $dbh->prepare(
            "SELECT orderid AS id, delivery_confirmed, completed_at
               FROM orders WHERE orderid = ? AND user_id = ?"
        );
    } elseif ($source === 'Bulk') {
        $stmt = $dbh->prepare(
            "SELECT id, delivery_confirmed, completed_at
               FROM Bulk_orders WHERE id = ? AND user_id = ?"
        );
    } else {
        return null;
    }
    $stmt->execute([$orderId, $userId]);
    $row = $stmt->fetch(PDO::FETCH_ASSOC);
    return $row ?: null;
}

/**
 * Records the customer's rating, feedback and confirmation photo, and marks
 * the order completed.
 *
 * $table is one of two fixed literals chosen by this function itself, never
 * derived from caller input, so building the query with it in place is safe
 * even though it cannot be a bound parameter.
 */
function saveCustomerReceiptConfirmation(PDO $dbh, string $source, int $orderId, array $fields): void {
    $table = $source === 'order' ? 'orders' : 'Bulk_orders';
    $pk    = $source === 'order' ? 'orderid' : 'id';

    $stmt = $dbh->prepare(
        "UPDATE $table
            SET customer_rating = ?, rating_speed = ?, rating_professionalism = ?,
                rating_packaging = ?, customer_feedback = ?, emoji_reaction = ?,
                delivery_confirmed_photo = ?, completed_at = NOW()
          WHERE $pk = ?"
    );
    $stmt->execute([
        $fields['rating'] ?? null,
        $fields['rating_speed'] ?? null,
        $fields['rating_professionalism'] ?? null,
        $fields['rating_packaging'] ?? null,
        $fields['feedback'] ?? null,
        $fields['emoji'] ?? null,
        $fields['photo_filename'] ?? null,
        $orderId,
    ]);

    // Roll the new score up onto the rider straight away, so the figure the
    // customer sees on the tracking screen and the one the rider sees on their
    // dashboard both reflect this delivery.
    //
    // Guarded rather than assumed: an order can be confirmed with no rating at
    // all (every rating field is optional), and an order can have no rider
    // recorded -- a self-collected or manually closed order never gets a
    // rider_assignments row. Neither is an error, and neither should reach the
    // recompute.
    //
    // Failures are swallowed on purpose. This runs inside the caller's
    // transaction alongside the customer's confirmation, and a rating average
    // is a derived convenience: losing it must never roll back the customer's
    // confirmation, their photo or the completed_at that closes the order.
    // recalculateRiderRatings() is idempotent, so a rider whose average is
    // missed here is corrected by their next rated delivery.
    if (($fields['rating'] ?? null) !== null) {
        try {
            $riderId = riderForOrder($dbh, $source, $orderId);
            if ($riderId !== null) {
                recalculateRiderRatings($dbh, $riderId);
            }
        } catch (Throwable $e) {
            error_log("rider rating recompute failed for $source order $orderId: " . $e->getMessage());
        }
    }
}

/**
 * Recomputes a rider's rating averages from every delivery they have been
 * rated on, and writes them onto `riders`.
 *
 * WHY THIS EXISTS
 *
 * riders.avg_rating, total_ratings, avg_speed, avg_professionalism and
 * avg_packaging are READ in five places -- admin/riders.php,
 * admin/edit-rider.php, api/tracking.php (the customer watching their
 * delivery), and api/rider.php (the rider's own dashboard) -- and until now
 * were WRITTEN by nothing at all. Every rider showed 0.00 with 0 ratings
 * however many five-star deliveries they completed, because the scores the
 * customer gave at confirm-receipt were stored on the order row and never
 * rolled up.
 *
 * RECOMPUTE, NOT INCREMENT
 *
 * A running average (new_avg = old_avg + (x - old_avg)/n) would be cheaper,
 * but drifts: any rating edited, any order deleted, any failed write, and the
 * stored figure silently disagrees with the rows it claims to summarise, with
 * no way to notice. Recomputing from the source rows makes the columns a
 * cache that is always reproducible -- and lets this same function repair a
 * rider's figures by simply being called again. At delivery volumes measured
 * in hundreds the cost is irrelevant; both queries hit indexed columns.
 *
 * BOTH SOURCES
 *
 * A rider's deliveries are split across `orders` and `Bulk_orders`, whose id
 * spaces OVERLAP -- shop order 41 and Bulk order 41 both exist. They are tied
 * to a rider only through rider_assignments, which carries the `source`
 * discriminator added in the 2026-08-12 migration for exactly this reason.
 * Joining on order_id alone would mix one rider's shop deliveries with
 * another's Bulk deliveries and produce averages belonging to nobody.
 *
 * Rows where customer_rating IS NULL are excluded rather than counted as
 * zero: a customer who confirmed receipt without rating has expressed no
 * opinion, and treating that as the worst possible score would punish riders
 * for their customers' indifference. total_ratings therefore counts ratings
 * given, not deliveries made.
 *
 * @return bool false when the rider has no rated deliveries yet (columns are
 *         zeroed, which is the correct display state), true otherwise.
 */
function recalculateRiderRatings(PDO $dbh, int $riderId): bool {
    // UNION ALL, not UNION: two identical ratings from different orders are
    // distinct data points, and UNION would silently collapse them into one.
    $stmt = $dbh->prepare(
        "SELECT AVG(customer_rating)        AS avg_overall,
                COUNT(*)                    AS n,
                AVG(rating_speed)           AS avg_speed,
                AVG(rating_professionalism) AS avg_prof,
                AVG(rating_packaging)       AS avg_pack
           FROM (
                SELECT o.customer_rating, o.rating_speed,
                       o.rating_professionalism, o.rating_packaging
                  FROM rider_assignments ra
                  JOIN orders o ON o.orderid = ra.order_id
                 WHERE ra.rider_id = ? AND ra.source = 'order'
                   AND o.customer_rating IS NOT NULL

                UNION ALL

                SELECT b.customer_rating, b.rating_speed,
                       b.rating_professionalism, b.rating_packaging
                  FROM rider_assignments ra
                  JOIN Bulk_orders b ON b.id = ra.order_id
                 WHERE ra.rider_id = ? AND ra.source = 'Bulk'
                   AND b.customer_rating IS NOT NULL
           ) AS rated"
    );
    $stmt->execute([$riderId, $riderId]);
    $agg = $stmt->fetch(PDO::FETCH_ASSOC);

    $n = (int)($agg['n'] ?? 0);

    // The three sub-scores are optional even when an overall rating is given,
    // so AVG() over them can be NULL while n > 0. COALESCE to 0.00 because the
    // columns are NOT NULL DEFAULT 0.00 and every display site formats them
    // with number_format(), which would render NULL as "0.00" anyway.
    $dbh->prepare(
        "UPDATE riders
            SET avg_rating          = ?,
                total_ratings       = ?,
                avg_speed           = ?,
                avg_professionalism = ?,
                avg_packaging       = ?
          WHERE id = ?"
    )->execute([
        $n > 0 ? round((float)$agg['avg_overall'], 2) : 0.00,
        $n,
        $n > 0 ? round((float)($agg['avg_speed'] ?? 0), 2) : 0.00,
        $n > 0 ? round((float)($agg['avg_prof']  ?? 0), 2) : 0.00,
        $n > 0 ? round((float)($agg['avg_pack']  ?? 0), 2) : 0.00,
        $riderId,
    ]);

    return $n > 0;
}

/**
 * The rider who delivered a given order, or null if none is recorded.
 *
 * rider_assignments is the only table tying a rider to an order across both
 * sources: `orders` has no rider_id column, and Bulk_orders has neither.
 */
function riderForOrder(PDO $dbh, string $source, int $orderId): ?int {
    $stmt = $dbh->prepare(
        "SELECT rider_id FROM rider_assignments
          WHERE source = ? AND order_id = ?
          ORDER BY id DESC LIMIT 1"
    );
    $stmt->execute([$source, $orderId]);
    $riderId = $stmt->fetchColumn();
    return $riderId ? (int)$riderId : null;
}

/** The only emoji_reaction values the column comment documents. */
function validEmojiReactions(): array {
    return ['thumbs_up', 'heart', 'smile', 'fire', 'rocket'];
}
