<?php
// =============================================================
// admin/Bulk-orders.php
//
// Bulk orders, and dispatching them to riders.
//
// Until this page existed, admin had no view of Bulk orders at all: the
// vendor was the only party who could move one, and delivery was theirs to
// arrange offline. That left two holes — a vendor who stalled mid-order could
// not be helped by anyone, and the platform collected a weight-based delivery
// fee for a service it did not provide.
//
// WHAT DISPATCH MEANS HERE
//
// A Bulk job is not a shop job. It is collected from the VENDOR, not the
// warehouse, and it is bulk — a 250,000-shilling minimum, up to a tonne. So the
// rider is shown a pickup address, and the fee they are paid from is the
// Bulk delivery fee rather than a mileage component Bulk orders do not
// have. See includes/rider_dispatch.php.
//
// WHAT IS DELIBERATELY NOT ASSIGNABLE
//
//   - unpaid orders — a reservation the release sweep may cancel; sending a
//     rider to collect one wastes a trip
//   - pickup-only orders — the customer collects, there is no delivery fee, and
//     so nothing to pay a rider from
//   - orders already delivered or cancelled
// =============================================================

require_once __DIR__ . '/auth_check.php';
requireAdminLoginWeb();
require_once __DIR__ . '/includes/config.php';
require_once __DIR__ . '/../includes/notifications.php';
require_once __DIR__ . '/../includes/rider_dispatch.php';
require_once __DIR__ . '/../includes/csrf.php';
require_once __DIR__ . '/../includes/Bulk_payment.php';

$flash = '';
$flashError = '';

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    verifyCsrf();
    $orderId = (int)($_POST['order_id'] ?? 0);
    $action  = $_POST['action'] ?? '';

    $orderStmt = $dbh->prepare(
        "SELECT so.*, v.business_name, v.location AS vendor_location,
                sl.pickup_only, i.name AS product_name
           FROM Bulk_orders so
           JOIN Bulk_listings sl ON sl.id = so.listing_id
           JOIN items i ON i.id = sl.product_id
           JOIN vendors v ON v.id = sl.vendor_id
          WHERE so.id = ?"
    );
    $orderStmt->execute([$orderId]);
    $order = $orderStmt->fetch(PDO::FETCH_ASSOC);

    if (!$order) {
        $flashError = 'No such Bulk order.';
    } elseif ($action === 'assign_rider') {
        $riderId = (int)($_POST['rider_id'] ?? 0);

        // Every one of these is a real way to waste a rider's trip, so each is
        // refused with the reason rather than a generic failure.
        if ($riderId <= 0) {
            $flashError = 'Choose a rider first.';
        } elseif (!in_array($order['payment_status'], ['paid', 'pending_cash'], true)) {
            $flashError = 'That order has not been paid for. Nothing to collect yet.';
        } elseif ((int)$order['pickup_only'] === 1) {
            $flashError = 'That listing is collection-only — the customer picks it up themselves.';
        } elseif (in_array($order['status'], ['delivered', 'cancelled', 'refunded'], true)) {
            $flashError = 'That order is already ' . $order['status'] . '.';
        } elseif ((float)$order['delivery_fee'] <= 0) {
            $flashError = 'That order carries no delivery fee, so there is nothing to pay a rider from.';
        } else {
            try {
                $dbh->beginTransaction();

                // source is not optional. rider_assignments.order_id is shared
                // between `orders` and `Bulk_orders`, whose ids OVERLAP —
                // without it the rider app would resolve this to whichever shop
                // order happens to share the number and show a rider the wrong
                // customer's address entirely.
                $existing = $dbh->prepare(
                    "SELECT id FROM rider_assignments
                      WHERE order_id = ? AND source = 'Bulk' LIMIT 1"
                );
                $existing->execute([$orderId]);
                $assignmentId = $existing->fetchColumn();

                if ($assignmentId) {
                    // Reassignment: move it to the new rider and put the
                    // assignment back to the start of the flow, so the new
                    // rider is not handed a job already marked picked up.
                    $dbh->prepare(
                        "UPDATE rider_assignments
                            SET rider_id = ?, assigned_at = NOW(), status = 'assigned',
                                delivered_at = NULL, completed_at = NULL
                          WHERE id = ?"
                    )->execute([$riderId, $assignmentId]);
                } else {
                    $dbh->prepare(
                        "INSERT INTO rider_assignments (order_id, source, rider_id, status)
                         VALUES (?, 'Bulk', ?, 'assigned')"
                    )->execute([$orderId, $riderId]);
                    $assignmentId = (int)$dbh->lastInsertId();
                }

                // Fetched now rather than left for the picked_up transition, so
                // the rider's Navigate screen and the customer's tracking map
                // both have a route to draw as soon as possible. Self-guarding
                // — a no-op if this assignment already has one, which covers
                // both a fresh dispatch and reassigning an order that already
                // had its route cached.
                cacheAssignmentRoute($dbh, 'Bulk', $orderId, (int)$assignmentId);

                $dbh->prepare(
                    "UPDATE Bulk_orders SET rider_assigned_at = NOW(), updated_at = NOW() WHERE id = ?"
                )->execute([$orderId]);

                $dbh->commit();

                // Told after the commit: a notification about an assignment
                // that rolled back is worse than none.
                try {
                    $riderUser = $dbh->prepare(
                        "SELECT r.user_id, r.name FROM riders r WHERE r.id = ?"
                    );
                    $riderUser->execute([$riderId]);
                    $r = $riderUser->fetch(PDO::FETCH_ASSOC);

                    if ($r && !empty($r['user_id'])) {
                        addNotification(
                            (int)$r['user_id'],
                            'New Bulk delivery assigned',
                            'Collect ' . $order['product_name'] . ' from '
                                . ($order['business_name'] ?: 'the vendor')
                                . ($order['vendor_location'] ? ' (' . $order['vendor_location'] . ')' : '')
                                . ' and deliver to ' . ($order['delivery_area'] ?: 'the customer')
                                . '. About ' . number_format((float)$order['total_weight_kg'], 0) . ' kg.',
                            'order',
                            null,
                            ['push']
                        );
                    }

                    addNotification(
                        (int)$order['user_id'],
                        'A rider is on the way',
                        'Your Bulk order #' . $orderId . ' has been assigned to a rider for delivery.',
                        'order',
                        null,
                        ['push']
                    );
                } catch (Throwable $e) {
                    error_log("Bulk order $orderId assigned but notifications failed: " . $e->getMessage());
                }

                $flash = 'Dispatched. The rider has been notified.';
            } catch (Throwable $e) {
                if ($dbh->inTransaction()) $dbh->rollBack();
                error_log('Bulk assign_rider failed: ' . $e->getMessage());
                $flashError = 'Could not assign that rider. Nothing was changed.';
            }
        }
    } elseif ($action === 'unassign') {
        // Only before the rider has collected. Pulling an assignment out from
        // under a rider already carrying the goods would leave the load with
        // someone the system no longer believes is delivering it.
        $del = $dbh->prepare(
            "DELETE FROM rider_assignments
              WHERE order_id = ? AND source = 'Bulk' AND status = 'assigned'"
        );
        $del->execute([$orderId]);

        if ($del->rowCount() > 0) {
            $dbh->prepare("UPDATE Bulk_orders SET rider_assigned_at = NULL WHERE id = ?")
                ->execute([$orderId]);
            $flash = 'Rider removed from that order.';
        } else {
            $flashError = 'That delivery has already been collected — reassign instead of removing it.';
        }
    } elseif ($action === 'cancel_order') {
        // Admin cancelling directly (no vendor request involved): the admin
        // clicking this IS the approval, so it executes for real immediately
        // — same rule as approve_cancellation below.
        $reason = trim($_POST['reason'] ?? '');
        if ($reason === '') {
            $flashError = 'Give a reason — the customer sees it.';
        } else {
            $result = cancelBulkOrder($dbh, $orderId, $reason, 'admin', (int)$_SESSION['admin_id'], (string)($_SESSION['admin_name'] ?? 'afamfresh-admin'));
            if (!$result['ok']) {
                $flashError = $result['error'];
            } else {
                addNotification(
                    (int)$order['user_id'],
                    'Order cancelled',
                    'Your Bulk order #' . $orderId . ' was cancelled. Reason: ' . $reason
                        . ($result['refund_attempted']
                            ? ' Any payment made will be refunded.' : ''),
                    'order', null, ['push', 'email']
                );

                if (!$result['refund_attempted']) {
                    $flash = 'Cancelled and stock returned to the listing.';
                } elseif ($result['refund_requested']) {
                    $flash = 'Cancelled, stock returned, and a refund was requested from Pesapal.';
                } else {
                    $flash = 'Cancelled and stock returned, but the Pesapal refund could not be '
                        . 'requested automatically (' . $result['refund_error'] . '). '
                        . 'Refund this manually in the Pesapal dashboard.';
                }
            }
        }
    } elseif ($action === 'approve_cancellation') {
        // Executes a vendor's cancellation REQUEST for real: this is the
        // approval, same underlying function as an admin's own direct cancel.
        if ($order['status'] !== 'cancellation_requested') {
            $flashError = 'That order has no pending cancellation request.';
        } else {
            $reason = trim((string)($order['cancellation_reason'] ?? '')) ?: 'Cancellation requested by vendor.';
            $result = cancelBulkOrder($dbh, $orderId, $reason, 'admin', (int)$_SESSION['admin_id'], (string)($_SESSION['admin_name'] ?? 'afamfresh-admin'));
            if (!$result['ok']) {
                $flashError = $result['error'];
            } else {
                addNotification(
                    (int)$order['user_id'],
                    'Order cancelled',
                    'Your Bulk order #' . $orderId . ' was cancelled. Reason: ' . $reason
                        . ($result['refund_attempted']
                            ? ' Any payment made will be refunded.' : ''),
                    'order', null, ['push', 'email']
                );
                $flash = $result['refund_attempted'] && $result['refund_requested']
                    ? 'Approved. Cancelled, stock returned, and a refund was requested from Pesapal.'
                    : 'Approved and cancelled.';
            }
        }
    } elseif ($action === 'deny_cancellation') {
        $reason = trim($_POST['reason'] ?? '');
        if ($order['status'] !== 'cancellation_requested') {
            $flashError = 'That order has no pending cancellation request.';
        } elseif ($reason === '') {
            $flashError = 'Give a reason — the vendor sees it.';
        } else {
            $dbh->prepare(
                "UPDATE Bulk_orders
                    SET status = COALESCE(status_before_cancellation, 'confirmed'),
                        status_before_cancellation = NULL, cancellation_reason = NULL,
                        updated_at = NOW()
                  WHERE id = ? AND status = 'cancellation_requested'"
            )->execute([$orderId]);

            if (!empty($order['cancellation_requested_by'])) {
                addNotification(
                    (int)$order['cancellation_requested_by'],
                    'Cancellation request denied',
                    'Your request to cancel Bulk order #' . $orderId . ' was not approved. Reason: ' . $reason,
                    'system', null, ['push', 'email']
                );
            }
            $flash = 'Denied. The order continues as normal and the vendor has been told why.';
        }
    } elseif ($action === 'confirm_refund') {
        // The only place status ever reaches 'refunded' — always a deliberate
        // admin confirmation after checking Pesapal's dashboard, never automatic.
        if ($order['payment_status'] !== 'refund_requested') {
            $flashError = 'That order has no refund awaiting confirmation.';
        } else {
            $dbh->prepare(
                "UPDATE Bulk_orders SET status = 'refunded', payment_status = 'refunded', updated_at = NOW() WHERE id = ?"
            )->execute([$orderId]);

            recordPaymentEvent($dbh, 'Bulk', $orderId, 'refund_confirmed', [
                'from_status' => 'refund_requested',
                'to_status'   => 'refunded',
                'actor_type'  => 'admin',
                'actor_id'    => (int)$_SESSION['admin_id'],
            ]);

            addNotification(
                (int)$order['user_id'],
                'Refund complete',
                'Your refund for Bulk order #' . $orderId . ' has been completed.',
                'order', null, ['push', 'email']
            );
            $flash = 'Refund confirmed and the customer told.';
        }
    }
}

// -------------------------------------------------------------
// Listing
// -------------------------------------------------------------
$filter = $_GET['filter'] ?? 'needs_rider';
$allowedFilters = ['needs_rider', 'dispatched', 'unpaid', 'active', 'cancellation_requests', 'refunds_pending', 'all'];
if (!in_array($filter, $allowedFilters, true)) {
    $filter = 'needs_rider';
}

$where = [];
switch ($filter) {
    case 'needs_rider':
        // Paid, deliverable, still open, and nobody sent yet. This is the
        // working queue — the default view because it is the only one that
        // represents an action someone has to take.
        $where[] = "so.payment_status IN ('paid','pending_cash')";
        $where[] = "sl.pickup_only = 0";
        $where[] = "so.status NOT IN ('delivered','cancelled','refunded')";
        $where[] = "ra.id IS NULL";
        break;
    case 'dispatched':
        $where[] = "ra.id IS NOT NULL";
        break;
    case 'unpaid':
        $where[] = "so.payment_status NOT IN ('paid','pending_cash')";
        break;
    case 'active':
        $where[] = "so.status NOT IN ('delivered','cancelled','refunded')";
        break;
    case 'cancellation_requests':
        // A vendor asked to cancel a paid order. Nothing has happened yet —
        // this is the queue for the one action that actually moves it.
        $where[] = "so.status = 'cancellation_requested'";
        break;
    case 'refunds_pending':
        // Pesapal accepted the refund request; waiting on an admin to
        // confirm the money actually moved, per Pesapal's own dashboard.
        $where[] = "so.payment_status = 'refund_requested'";
        break;
}

$sql = "SELECT so.*, sl.pickup_only, i.name AS product_name,
               v.business_name, v.location AS vendor_location,
               u.fname, u.lname, u.mobile,
               ra.id AS assignment_id, ra.status AS assignment_status,
               ra.assigned_at, ra.rider_id, r.name AS rider_name
          FROM Bulk_orders so
          JOIN Bulk_listings sl ON sl.id = so.listing_id
          JOIN items i ON i.id = sl.product_id
          JOIN vendors v ON v.id = sl.vendor_id
          JOIN users u ON u.id = so.user_id
          LEFT JOIN rider_assignments ra
                 ON ra.order_id = so.id AND ra.source = 'Bulk'
          LEFT JOIN riders r ON r.id = ra.rider_id";
if ($where) {
    $sql .= " WHERE " . implode(' AND ', $where);
}
$sql .= " ORDER BY so.id DESC LIMIT 200";
$orders = $dbh->query($sql)->fetchAll(PDO::FETCH_ASSOC);

$riders = $dbh->query(
    "SELECT id, name, status FROM riders ORDER BY status = 'online' DESC, name"
)->fetchAll(PDO::FETCH_ASSOC);

// The badge count: how many are waiting on a rider right now.
$needsRider = (int)$dbh->query(
    "SELECT COUNT(*)
       FROM Bulk_orders so
       JOIN Bulk_listings sl ON sl.id = so.listing_id
       LEFT JOIN rider_assignments ra ON ra.order_id = so.id AND ra.source = 'Bulk'
      WHERE so.payment_status IN ('paid','pending_cash')
        AND sl.pickup_only = 0
        AND so.status NOT IN ('delivered','cancelled','refunded')
        AND ra.id IS NULL"
)->fetchColumn();

$cancellationRequestCount = (int)$dbh->query(
    "SELECT COUNT(*) FROM Bulk_orders WHERE status = 'cancellation_requested'"
)->fetchColumn();
$refundsPendingCount = (int)$dbh->query(
    "SELECT COUNT(*) FROM Bulk_orders WHERE payment_status = 'refund_requested'"
)->fetchColumn();
?>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Bulk Orders — AfamFresh Admin</title>
    <script src="https://cdn.tailwindcss.com"></script>
    <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.5.0/css/all.min.css">
</head>
<body class="bg-gray-50 font-sans antialiased">
<div class="flex min-h-screen">
<?php include __DIR__ . '/includes/nav.php'; ?>
<div class="flex-1 overflow-auto">
    <div class="max-w-7xl mx-auto px-6 py-8">
        <h1 class="text-2xl font-bold text-green-800 mb-1">Bulk Orders</h1>
        <p class="text-gray-600 mb-6">
            Bulk orders bought from the Bulk marketplace. A rider collects these
            from the <strong>vendor</strong>, not the warehouse.
        </p>

        <?php if ($flash): ?>
            <div class="bg-green-100 border border-green-300 text-green-800 px-4 py-3 rounded mb-4"><?= htmlspecialchars($flash) ?></div>
        <?php endif; ?>
        <?php if ($flashError): ?>
            <div class="bg-red-100 border border-red-300 text-red-700 px-4 py-3 rounded mb-4"><?= htmlspecialchars($flashError) ?></div>
        <?php endif; ?>

        <div class="mb-5 space-x-2">
            <?php
            $labels = [
                'needs_rider'           => 'Needs a rider',
                'dispatched'            => 'Dispatched',
                'unpaid'                => 'Unpaid',
                'active'                => 'Active',
                'cancellation_requests' => 'Cancellation requests',
                'refunds_pending'       => 'Refunds pending',
                'all'                   => 'All',
            ];
            $badgeCounts = [
                'needs_rider'           => $needsRider,
                'cancellation_requests' => $cancellationRequestCount,
                'refunds_pending'       => $refundsPendingCount,
            ];
            foreach ($labels as $key => $label): ?>
                <a href="?filter=<?= $key ?>"
                   class="px-3 py-1 rounded-full text-sm <?= $filter === $key ? 'bg-green-700 text-white' : 'bg-gray-200 text-gray-700' ?>">
                    <?= $label ?><?= !empty($badgeCounts[$key]) ? " ({$badgeCounts[$key]})" : '' ?>
                </a>
            <?php endforeach; ?>
        </div>

        <?php if (!$orders): ?>
            <div class="bg-white rounded-xl shadow p-8 text-center text-gray-500">
                Nothing here right now.
            </div>
        <?php else: ?>
            <div class="space-y-4">
            <?php foreach ($orders as $o): ?>
                <?php
                $paid = in_array($o['payment_status'], ['paid', 'pending_cash'], true);
                $pickupOnly = (int)$o['pickup_only'] === 1;
                $closed = in_array($o['status'], ['delivered', 'cancelled', 'refunded'], true);
                $assignable = $paid && !$pickupOnly && !$closed && (float)$o['delivery_fee'] > 0;
                ?>
                <div class="bg-white rounded-xl shadow p-5">
                    <div class="flex justify-between items-start mb-3">
                        <div>
                            <div class="font-semibold text-gray-900">
                                #<?= (int)$o['id'] ?> · <?= htmlspecialchars($o['product_name']) ?>
                            </div>
                            <div class="text-sm text-gray-500">
                                <?= htmlspecialchars(rtrim(rtrim(number_format((float)$o['quantity'], 2, '.', ''), '0'), '.')) ?>
                                · <?= number_format((float)$o['total_weight_kg'], 0) ?> kg
                                · <?= htmlspecialchars(trim($o['fname'] . ' ' . $o['lname'])) ?>
                                <?php if ($o['mobile']): ?> · <?= htmlspecialchars($o['mobile']) ?><?php endif; ?>
                            </div>
                            <div class="text-sm text-gray-600 mt-1">
                                <i class="fas fa-store text-gray-400 mr-1"></i>
                                Collect from <?= htmlspecialchars($o['business_name']) ?><?php
                                    if ($o['vendor_location']) echo ' — ' . htmlspecialchars($o['vendor_location']); ?>
                            </div>
                            <div class="text-sm text-gray-600">
                                <i class="fas fa-location-dot text-gray-400 mr-1"></i>
                                Deliver to <?= htmlspecialchars($o['delivery_address'] ?: '—') ?><?php
                                    if ($o['delivery_area']) echo ', ' . htmlspecialchars($o['delivery_area']); ?>
                            </div>
                        </div>
                        <div class="text-right">
                            <div class="text-lg font-bold text-green-800">
                                UGX <?= number_format((float)$o['total_price'] + (float)$o['delivery_fee'], 0) ?>
                            </div>
                            <div class="text-xs text-gray-500">
                                incl. UGX <?= number_format((float)$o['delivery_fee'], 0) ?> delivery
                            </div>
                            <?php
                            $badge = $paid ? 'bg-green-100 text-green-800' : 'bg-yellow-100 text-yellow-800';
                            ?>
                            <span class="inline-block mt-1 px-2 py-1 rounded-full text-xs font-semibold <?= $badge ?>">
                                <?= htmlspecialchars($o['payment_status']) ?>
                            </span>
                            <span class="inline-block mt-1 px-2 py-1 rounded-full text-xs font-semibold bg-gray-100 text-gray-700">
                                <?= htmlspecialchars($o['status']) ?>
                            </span>
                        </div>
                    </div>

                    <?php if ($o['assignment_id']): ?>
                        <div class="bg-blue-50 border border-blue-200 rounded-lg px-3 py-2 text-sm text-blue-900 mb-3">
                            <i class="fas fa-motorcycle mr-1"></i>
                            <?= htmlspecialchars($o['rider_name'] ?? 'Rider') ?> —
                            <?= htmlspecialchars($o['assignment_status']) ?>
                            <span class="text-blue-700">(assigned <?= htmlspecialchars($o['assigned_at']) ?>)</span>
                        </div>
                    <?php endif; ?>

                    <?php if ($o['payment_status'] === 'refund_requested'): ?>
                        <div class="bg-amber-50 border border-amber-200 rounded-lg px-3 py-2 text-sm text-amber-900 mb-3 flex items-center justify-between gap-3">
                            <span>
                                <i class="fas fa-hourglass-half mr-1"></i>
                                A refund was requested from Pesapal. Confirm here once its dashboard
                                shows the money actually moved.
                            </span>
                            <form method="post" class="shrink-0">
                                <?= csrfField() ?>
                                <input type="hidden" name="order_id" value="<?= (int)$o['id'] ?>">
                                <input type="hidden" name="action" value="confirm_refund">
                                <button onclick="return confirm('Confirm the refund has actually completed in Pesapal?')"
                                        class="bg-amber-600 hover:bg-amber-700 text-white px-3 py-1.5 rounded-lg text-sm font-semibold">
                                    Confirm refund complete
                                </button>
                            </form>
                        </div>
                    <?php endif; ?>

                    <?php if ($o['status'] === 'cancellation_requested'): ?>
                        <div class="bg-red-50 border border-red-200 rounded-lg px-3 py-3 text-sm text-red-900 mb-3">
                            <div class="mb-2">
                                <i class="fas fa-circle-exclamation mr-1"></i>
                                The vendor asked to cancel this order.
                                <?php if (!empty($o['cancellation_reason'])): ?>
                                    Reason: <?= htmlspecialchars($o['cancellation_reason']) ?>
                                <?php endif; ?>
                                Nothing has changed yet — approving will cancel it for real, return the
                                stock, and request a refund if it was paid electronically.
                            </div>
                            <div class="flex flex-wrap items-center gap-2">
                                <form method="post">
                                    <?= csrfField() ?>
                                    <input type="hidden" name="order_id" value="<?= (int)$o['id'] ?>">
                                    <input type="hidden" name="action" value="approve_cancellation">
                                    <button onclick="return confirm('Approve this cancellation? Stock returns to the listing and a refund will be requested if it was paid.')"
                                            class="bg-red-600 hover:bg-red-700 text-white px-3 py-1.5 rounded-lg text-sm font-semibold">
                                        Approve cancellation
                                    </button>
                                </form>
                                <form method="post" class="flex items-center gap-2">
                                    <?= csrfField() ?>
                                    <input type="hidden" name="order_id" value="<?= (int)$o['id'] ?>">
                                    <input type="hidden" name="action" value="deny_cancellation">
                                    <input type="text" name="reason" placeholder="Reason (required)"
                                           class="border border-gray-300 rounded-lg px-2 py-1.5 text-sm w-48">
                                    <button class="bg-gray-200 hover:bg-gray-300 text-gray-800 px-3 py-1.5 rounded-lg text-sm font-semibold">
                                        Deny
                                    </button>
                                </form>
                            </div>
                        </div>
                    <?php elseif ($pickupOnly): ?>
                        <div class="text-sm text-gray-500">
                            Collection-only listing — the customer picks this up from the vendor.
                            <?php if (!empty($o['pickup_code'])): ?>
                                Code <strong><?= htmlspecialchars($o['pickup_code']) ?></strong>.
                            <?php endif; ?>
                        </div>
                    <?php elseif (!$paid): ?>
                        <div class="text-sm text-gray-500">
                            Not paid for. Nothing to dispatch until it is — unpaid orders are
                            released back to the listing after 30 minutes.
                        </div>
                    <?php elseif ($closed): ?>
                        <div class="text-sm text-gray-500">This order is <?= htmlspecialchars($o['status']) ?>.</div>
                    <?php else: ?>
                        <div class="flex flex-wrap items-center gap-2">
                            <?php if ($assignable): ?>
                                <form method="post" class="flex items-center gap-2">
                                    <?= csrfField() ?>
                                    <input type="hidden" name="order_id" value="<?= (int)$o['id'] ?>">
                                    <input type="hidden" name="action" value="assign_rider">
                                    <select name="rider_id" class="border border-gray-300 rounded-lg px-2 py-1.5 text-sm">
                                        <option value="">Choose a rider…</option>
                                        <?php foreach ($riders as $r): ?>
                                            <option value="<?= (int)$r['id'] ?>" <?= (int)$r['id'] === (int)($o['rider_id'] ?? 0) ? 'selected' : '' ?>>
                                                <?= htmlspecialchars($r['name']) ?><?= $r['status'] === 'online' ? ' (online)' : '' ?>
                                            </option>
                                        <?php endforeach; ?>
                                    </select>
                                    <button class="bg-green-700 hover:bg-green-800 text-white px-4 py-1.5 rounded-lg text-sm font-semibold">
                                        <?= $o['assignment_id'] ? 'Reassign' : 'Dispatch' ?>
                                    </button>
                                </form>
                            <?php else: ?>
                                <div class="text-sm text-gray-500">
                                    No delivery fee on this order, so there is nothing to pay a rider from.
                                </div>
                            <?php endif; ?>

                            <?php if ($o['assignment_id'] && $o['assignment_status'] === 'assigned'): ?>
                                <form method="post">
                                    <?= csrfField() ?>
                                    <input type="hidden" name="order_id" value="<?= (int)$o['id'] ?>">
                                    <input type="hidden" name="action" value="unassign">
                                    <button class="text-sm text-gray-600 hover:text-gray-900 px-3 py-1.5">Remove rider</button>
                                </form>
                            <?php endif; ?>

                            <form method="post" class="flex items-center gap-2 ml-auto">
                                <?= csrfField() ?>
                                <input type="hidden" name="order_id" value="<?= (int)$o['id'] ?>">
                                <input type="hidden" name="action" value="cancel_order">
                                <input type="text" name="reason" placeholder="Reason (required)"
                                       class="border border-gray-300 rounded-lg px-2 py-1.5 text-sm w-48">
                                <button onclick="return confirm('Cancel this order and return the stock to the listing?')"
                                        class="bg-red-600 hover:bg-red-700 text-white px-3 py-1.5 rounded-lg text-sm font-semibold">
                                    Cancel order
                                </button>
                            </form>
                        </div>
                    <?php endif; ?>
                </div>
            <?php endforeach; ?>
            </div>
        <?php endif; ?>
    </div>
</div>
</div>
</body>
</html>