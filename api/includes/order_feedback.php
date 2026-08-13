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
// is what it was for. surplus_orders gained the same column, and the six
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
    } elseif ($source === 'surplus') {
        $stmt = $dbh->prepare(
            "SELECT id, delivery_confirmed, completed_at
               FROM surplus_orders WHERE id = ? AND user_id = ?"
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
    $table = $source === 'order' ? 'orders' : 'surplus_orders';
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
}

/** The only emoji_reaction values the column comment documents. */
function validEmojiReactions(): array {
    return ['thumbs_up', 'heart', 'smile', 'fire', 'rocket'];
}
