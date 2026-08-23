<?php
/**
 * includes/delivery_slots.php — validating and atomically booking a
 * scheduled-delivery date/slot against delivery_slots.max_orders.
 *
 * Used by api/orders.php's create and update actions. Mirrors the same
 * date-range rules api/get-slots.php already applies for display, so a slot
 * the customer was shown as available is checked the same way when they
 * actually try to book it — the two were previously separate implementations
 * of the same rules with no shared source of truth.
 */

/**
 * Validates a date/slot pair: both empty is valid ("no schedule requested —
 * as soon as possible"), both must be supplied together, the date must fall
 * within today..+7 days, the slot must name an active row in
 * delivery_slots, and — for today specifically — the slot's window must not
 * have already ended. Same rules get-slots.php uses for display.
 *
 * Does NOT check capacity. That has to happen inside the caller's open
 * transaction with a row lock held (see reserveDeliverySlot()), so it is a
 * separate step, only worth doing once this passes.
 *
 * @return array{ok: bool, error?: string, slot?: array|null, date?: string, label?: string}
 *         slot is null (with ok true) when both inputs were empty — the
 *         caller should store NULL/NULL, not attempt a booking.
 */
function validateScheduledSlot(PDO $dbh, ?string $date, ?string $slotLabel): array {
    $date = $date !== null ? trim($date) : '';
    $slotLabel = $slotLabel !== null ? trim($slotLabel) : '';

    if ($date === '' && $slotLabel === '') {
        return ['ok' => true, 'slot' => null];
    }
    if ($date === '' || $slotLabel === '') {
        return ['ok' => false, 'error' => 'A delivery date and a delivery slot must be provided together.'];
    }
    if (!preg_match('/^\d{4}-\d{2}-\d{2}$/', $date)) {
        return ['ok' => false, 'error' => 'Invalid delivery date format (expected YYYY-MM-DD).'];
    }

    $today = date('Y-m-d');
    if ($date < $today) {
        return ['ok' => false, 'error' => 'Cannot schedule delivery for a past date.'];
    }
    $maxDate = date('Y-m-d', strtotime('+7 days'));
    if ($date > $maxDate) {
        return ['ok' => false, 'error' => 'Delivery can only be scheduled up to 7 days in advance.'];
    }

    $stmt = $dbh->prepare(
        "SELECT id, slot_label, slot_start, slot_end, max_orders
           FROM delivery_slots
          WHERE slot_label = ? AND is_active = 1
          LIMIT 1"
    );
    $stmt->execute([$slotLabel]);
    $slot = $stmt->fetch(PDO::FETCH_ASSOC);
    if (!$slot) {
        return ['ok' => false, 'error' => 'That delivery slot is not available.'];
    }

    // Same same-day cutoff get-slots.php applies for display: a slot whose
    // window has already ended today cannot be booked for today.
    if ($date === $today && $slot['slot_end'] <= date('H:i:s')) {
        return ['ok' => false, 'error' => 'That delivery slot has already ended for today.'];
    }

    return ['ok' => true, 'slot' => $slot, 'date' => $date, 'label' => $slot['slot_label']];
}

/**
 * Atomically checks and reserves capacity for a validated date/slot.
 *
 * Must be called inside an already-open transaction, after
 * validateScheduledSlot() has confirmed the slot is real and in range.
 *
 * Locks the delivery_slots row itself (FOR UPDATE) as the serialization
 * point: there is no per-day booking counter to lock directly, so two
 * concurrent requests for the same date+slot both queue on this row lock
 * instead of both reading "9 of 10 booked" and both squeezing into the
 * last spot. Whichever transaction commits first is the one whose count
 * the second transaction actually sees.
 *
 * Excludes cancelled orders from the count, same as get-slots.php.
 *
 * @param int|null $excludeOrderId When rescheduling an existing order,
 *        that order's own current booking (if any) must not count against
 *        itself — pass its id so the count ignores it.
 * @return array{ok: bool, error?: string}
 */
function reserveDeliverySlot(PDO $dbh, int $slotId, string $date, string $slotLabel, ?int $excludeOrderId = null): array {
    $lock = $dbh->prepare("SELECT max_orders FROM delivery_slots WHERE id = ? FOR UPDATE");
    $lock->execute([$slotId]);
    $maxOrders = $lock->fetchColumn();
    if ($maxOrders === false) {
        return ['ok' => false, 'error' => 'That delivery slot is not available.'];
    }

    $sql = "SELECT COUNT(*) FROM orders
             WHERE scheduled_delivery_date = ? AND scheduled_delivery_slot = ?
               AND status NOT IN ('Cancelled')";
    $params = [$date, $slotLabel];
    if ($excludeOrderId !== null) {
        $sql .= " AND orderid != ?";
        $params[] = $excludeOrderId;
    }
    $count = $dbh->prepare($sql);
    $count->execute($params);
    $booked = (int)$count->fetchColumn();

    if ($booked >= (int)$maxOrders) {
        return ['ok' => false, 'error' => 'That delivery slot is fully booked. Please choose another.'];
    }
    return ['ok' => true];
}
