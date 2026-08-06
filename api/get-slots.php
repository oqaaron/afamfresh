<?php
// =============================================================
// api/get-slots.php — Delivery slot availability
// GET ?date=YYYY-MM-DD
// Returns JSON: { slots: [ { id, label, start, end, available, remaining } ] }
// =============================================================
session_start();
require_once __DIR__ . '/../admin/includes/config.php';

header('Content-Type: application/json');

// ── Auth: must be logged in ──────────────────────────────────
if (!isset($_SESSION['user_id'])) {
    http_response_code(401);
    echo json_encode(['error' => 'Unauthorized']);
    exit;
}

// ── Validate date ────────────────────────────────────────────
$date = $_GET['date'] ?? '';
if (!$date || !preg_match('/^\d{4}-\d{2}-\d{2}$/', $date)) {
    http_response_code(400);
    echo json_encode(['error' => 'Invalid or missing date parameter (expected YYYY-MM-DD)']);
    exit;
}

// Reject past dates
$today = date('Y-m-d');
if ($date < $today) {
    http_response_code(400);
    echo json_encode(['error' => 'Cannot schedule delivery for a past date']);
    exit;
}

// Reject dates more than 7 days out (configurable)
$maxDate = date('Y-m-d', strtotime('+7 days'));
if ($date > $maxDate) {
    http_response_code(400);
    echo json_encode(['error' => 'Delivery can only be scheduled up to 7 days in advance']);
    exit;
}

try {
    // ── Fetch all active slots ───────────────────────────────
    $slots = $dbh->query(
        "SELECT id, slot_label, slot_start, slot_end, max_orders
         FROM   delivery_slots
         WHERE  is_active = 1
         ORDER  BY slot_start ASC"
    )->fetchAll(PDO::FETCH_ASSOC);

    if (empty($slots)) {
        echo json_encode(['slots' => []]);
        exit;
    }

    // ── Count bookings per slot for the requested date ───────
    $slotIds      = array_column($slots, 'id');
    $placeholders = implode(',', array_fill(0, count($slotIds), '?'));

    $params   = array_merge([$date], $slotIds);
    $bookings = $dbh->prepare("
        SELECT scheduled_delivery_slot, COUNT(*) AS booked
        FROM   orders
        WHERE  scheduled_delivery_date = ?
          AND  scheduled_delivery_slot IN ($placeholders)
          AND  status NOT IN ('Cancelled')
        GROUP  BY scheduled_delivery_slot
    ");
    $bookings->execute($params);

    // Map slot_label → booked count
    $bookedMap = [];
    foreach ($bookings->fetchAll(PDO::FETCH_ASSOC) as $row) {
        $bookedMap[$row['scheduled_delivery_slot']] = (int) $row['booked'];
    }

    // ── If requesting today, filter out slots whose end time has passed ──
    $now         = date('H:i:s');
    $isToday     = ($date === $today);

    // ── Build response ───────────────────────────────────────
    $result = [];
    foreach ($slots as $slot) {
        $booked    = $bookedMap[$slot['slot_label']] ?? 0;
        $max       = (int) $slot['max_orders'];
        $remaining = max(0, $max - $booked);
        $available = $remaining > 0;

        // For today: disable slots whose end time has already passed
        if ($isToday && $slot['slot_end'] <= $now) {
            $available = false;
            $remaining = 0;
        }

        $result[] = [
            'id'        => (int) $slot['id'],
            'label'     => $slot['slot_label'],
            'start'     => $slot['slot_start'],
            'end'       => $slot['slot_end'],
            'available' => $available,
            'remaining' => $remaining,
            'max'       => $max,
        ];
    }

    echo json_encode(['slots' => $result]);

} catch (PDOException $e) {
    http_response_code(500);
    echo json_encode(['error' => 'Database error']);
    error_log('get-slots.php: ' . $e->getMessage());
}
