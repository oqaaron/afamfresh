<?php
// =============================================================
// admin/rider-payouts.php
//
// Approve, reject, or mark paid the payout requests riders file
// from api/rider.php's ?action=request_payout. Mirrors
// admin/role-requests.php's shape.
//
// Marking a request 'paid' also marks every rider_earnings row it
// covers as paid, so the rider's "available to withdraw" figure drops
// and the same earnings cannot be requested again in a future payout.
//
// Money never moves through this page. Every transition here is
// bookkeeping recorded AFTER an admin has sent funds out of band (mobile
// money, bank transfer, cash). 'approved' means "we have agreed to pay
// this"; 'paid' means "the money has left". Keeping them distinct is what
// lets a rider see their request was accepted before the transfer clears.
//
// Mirrors admin/vendor-payouts.php, which had the approve step, the notes
// on every transition, and the notifications first. This file did not, so
// a rider's request moved straight from pending to paid, and they were
// told nothing at any point.
// =============================================================

session_start();
require_once '../admin/includes/config.php';
require_once __DIR__ . '/../includes/csrf.php';
require_once __DIR__ . '/../includes/admin_permissions.php';
require_once __DIR__ . '/../includes/admin_audit.php';
require_once __DIR__ . '/../includes/notifications.php';
requireAdminPermission('payouts.manage');

/**
 * Tells a rider what just happened to their payout request.
 *
 * riders.user_id is nullable: a rider row can exist without a linked user
 * account (they predate the app, or were created straight in the admin).
 * addNotification() keys off a user id, so there is nobody to send to in
 * that case. Returns false rather than throwing -- a missing notification
 * must never roll back a payout that has already been recorded, and the
 * admin sees the outcome in the flash message either way.
 */
function notifyRiderPayout(PDO $dbh, int $riderId, string $title, string $message): bool {
    $stmt = $dbh->prepare("SELECT user_id FROM riders WHERE id = ?");
    $stmt->execute([$riderId]);
    $userId = $stmt->fetchColumn();

    if (!$userId) {
        error_log("rider payout: rider $riderId has no linked user account, notification skipped");
        return false;
    }

    // 'push' only. The vendor page also sends email, but riders register by
    // phone and users.email is nullable for them -- an email channel would
    // queue a message with no address. SMS is deliberately not used either:
    // it costs per message and the rider is looking at the app already.
    return addNotification((int)$userId, $title, $message, 'payment', null, ['push']);
}

$flash = '';
$flashError = '';

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    verifyCsrf();
    $requestId = intval($_POST['request_id'] ?? 0);
    $action    = $_POST['action'] ?? '';

    $note = trim($_POST['notes'] ?? '');

    if ($requestId && $action === 'approve') {
        // Agreeing to pay, before the money moves. Deliberately does NOT touch
        // rider_earnings: those rows stay unpaid until the transfer actually
        // happens, so an approved-but-not-yet-sent payout still shows in the
        // rider's available balance and cannot be quietly lost if the transfer
        // later fails.
        $stmt = $dbh->prepare("SELECT rider_id, amount, status FROM rider_payout_requests WHERE id = ?");
        $stmt->execute([$requestId]);
        $payout = $stmt->fetch(PDO::FETCH_ASSOC);

        if (!$payout) {
            $flashError = 'No such payout request.';
        } elseif ($payout['status'] !== 'pending') {
            $flashError = 'That request is already ' . $payout['status'] . '.';
        } else {
            $dbh->prepare(
                "UPDATE rider_payout_requests SET status = 'approved', notes = ? WHERE id = ?"
            )->execute([$note ?: null, $requestId]);

            notifyRiderPayout($dbh, (int)$payout['rider_id'], 'Withdrawal approved',
                'Your withdrawal of UGX ' . number_format((float)$payout['amount'], 2)
                    . ' has been approved and will be sent shortly.'
                    . ($note !== '' ? ' Note: ' . $note : ''));

            logAdminAction($dbh, 'rider_payout.approved', 'rider_payout_request', (string)$requestId,
                'Approved UGX ' . number_format((float)$payout['amount'], 2));
            $flash = 'Approved. The rider has been told it is on the way.';
        }

    } elseif ($requestId && $action === 'mark_paid') {
        try {
            $dbh->beginTransaction();

            $stmt = $dbh->prepare("SELECT rider_id, amount, status FROM rider_payout_requests WHERE id = ?");
            $stmt->execute([$requestId]);
            $payout = $stmt->fetch(PDO::FETCH_ASSOC);

            if (!$payout || $payout['status'] === 'paid') {
                throw new RuntimeException('That request no longer needs action.');
            }

            // Marks every unpaid earning as paid, oldest first, up to the
            // amount this specific payout covers — so a rider who earned
            // more between requesting and being paid keeps the remainder
            // available for their next request rather than it vanishing.
            $covered = $dbh->prepare(
                "SELECT id, net_earnings FROM rider_earnings
                  WHERE rider_id = ? AND is_paid = 0
                  ORDER BY created_at ASC"
            );
            $covered->execute([$payout['rider_id']]);

            $remaining = (float)$payout['amount'];
            $markPaid = $dbh->prepare("UPDATE rider_earnings SET is_paid = 1, paid_at = NOW() WHERE id = ?");
            foreach ($covered->fetchAll(PDO::FETCH_ASSOC) as $row) {
                if ($remaining <= 0) break;
                $markPaid->execute([$row['id']]);
                $remaining -= (float)$row['net_earnings'];
            }

            $dbh->prepare(
                // COALESCE so marking paid without a note does not erase the
                // note left at approval -- the two are separate moments and
                // both may have something worth keeping.
                "UPDATE rider_payout_requests
                    SET status = 'paid', processed_at = NOW(), notes = COALESCE(?, notes)
                  WHERE id = ?"
            )->execute([$note ?: null, $requestId]);

            $dbh->commit();

            // After the commit, never inside it: the notification path makes a
            // network call, and a payout that is already recorded must not be
            // rolled back because a push failed to queue.
            notifyRiderPayout($dbh, (int)$payout['rider_id'], 'Withdrawal sent',
                'UGX ' . number_format((float)$payout['amount'], 2) . ' has been sent to you.'
                    . ($note !== '' ? ' Note: ' . $note : ''));

            logAdminAction($dbh, 'rider_payout.marked_paid', 'rider_payout_request', (string)$requestId,
                'Marked paid, UGX ' . number_format((float)$payout['amount']));
            $flash = 'Marked as paid and the rider notified.';
        } catch (Exception $e) {
            if ($dbh->inTransaction()) $dbh->rollBack();
            error_log('rider payout mark_paid failed: ' . $e->getMessage());
            $flashError = $e instanceof RuntimeException ? $e->getMessage() : 'Could not mark this payout as paid.';
        }
    } elseif ($requestId && $action === 'reject') {
        // Rejection is the transition where silence hurts most: the request
        // disappears from the rider's pending list either way, and without a
        // reason they cannot tell refusal from a system fault. The note is
        // required here for that reason, and optional elsewhere.
        if ($note === '') {
            $flashError = 'Give a reason — the rider is told what it says.';
        } else {
            $stmt = $dbh->prepare(
                "SELECT rider_id, amount FROM rider_payout_requests
                  WHERE id = ? AND status IN ('pending', 'approved')"
            );
            $stmt->execute([$requestId]);
            $payout = $stmt->fetch(PDO::FETCH_ASSOC);

            if (!$payout) {
                $flashError = 'That request is no longer open.';
            } else {
                // Also allows rejecting an approved request: agreeing to pay
                // and then finding a reason not to is a real sequence, and
                // approval does not touch rider_earnings, so nothing needs
                // unwinding.
                $dbh->prepare(
                    "UPDATE rider_payout_requests SET status = 'rejected', processed_at = NOW(), notes = ?
                      WHERE id = ? AND status IN ('pending', 'approved')"
                )->execute([$note, $requestId]);

                notifyRiderPayout($dbh, (int)$payout['rider_id'], 'Withdrawal not approved',
                    'Your withdrawal request for UGX ' . number_format((float)$payout['amount'], 2)
                        . ' was not approved. Reason: ' . $note
                        . ' Your earnings remain available to withdraw.');

                logAdminAction($dbh, 'rider_payout.rejected', 'rider_payout_request', (string)$requestId, $note);
                $flash = 'Payout request rejected and the rider told why.';
            }
        }
    }
}

$statusFilter = $_GET['status'] ?? 'pending';
// 'approved' must be listed here, or a request that has been approved but
// not yet sent falls out of every tab -- invisible in 'pending' because its
// status changed, and absent from the others. That is the exact state an
// admin most needs to see: money promised, not yet moved.
$allowed = ['pending', 'approved', 'paid', 'rejected', 'all'];
if (!in_array($statusFilter, $allowed, true)) {
    $statusFilter = 'pending';
}

$sql = "SELECT rp.*, r.name AS rider_name, r.phone AS rider_phone
          FROM rider_payout_requests rp
          JOIN riders r ON r.id = rp.rider_id";
$params = [];
if ($statusFilter !== 'all') {
    $sql .= " WHERE rp.status = ?";
    $params[] = $statusFilter;
}
$sql .= " ORDER BY rp.requested_at DESC";
$stmt = $dbh->prepare($sql);
$stmt->execute($params);
$payouts = $stmt->fetchAll(PDO::FETCH_ASSOC);

$pendingCount = (int)$dbh->query("SELECT COUNT(*) FROM rider_payout_requests WHERE status = 'pending'")->fetchColumn();
?>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>AfamFresh – Rider Payouts</title>
    <script src="https://cdn.tailwindcss.com"></script>
    <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.5.0/css/all.min.css">
</head>
<body class="bg-gray-100">
<div class="flex h-screen">
    <?php include __DIR__ . "/includes/nav.php"; ?>

    <div class="flex-1 overflow-y-auto p-6">
        <h1 class="text-2xl font-bold text-green-800 mb-4">Rider Payouts</h1>

        <?php if ($flash): ?>
            <div class="bg-green-100 border border-green-400 text-green-700 px-4 py-3 rounded mb-4"><?= htmlspecialchars($flash) ?></div>
        <?php endif; ?>
        <?php if ($flashError): ?>
            <div class="bg-red-100 border border-red-400 text-red-700 px-4 py-3 rounded mb-4"><?= htmlspecialchars($flashError) ?></div>
        <?php endif; ?>

        <div class="flex gap-2 mb-4">
            <?php foreach (['pending', 'approved', 'paid', 'rejected', 'all'] as $s): ?>
                <a href="?status=<?= $s ?>"
                   class="px-4 py-2 rounded text-sm <?= $statusFilter === $s ? 'bg-green-600 text-white' : 'bg-white hover:bg-gray-200' ?>">
                    <?= ucfirst($s) ?>
                </a>
            <?php endforeach; ?>
        </div>

        <div class="bg-white rounded-xl shadow overflow-x-auto">
            <table class="w-full">
                <thead class="bg-gray-50 border-b">
                    <tr>
                        <th class="px-4 py-3 text-left">Rider</th>
                        <th class="px-4 py-3 text-left">Amount</th>
                        <th class="px-4 py-3 text-left">Status</th>
                        <th class="px-4 py-3 text-left">Requested</th>
                        <th class="px-4 py-3 text-left">Action</th>
                    </tr>
                </thead>
                <tbody>
                    <?php if (empty($payouts)): ?>
                        <tr><td colspan="5" class="px-4 py-6 text-center text-gray-500">
                            No <?= $statusFilter === 'all' ? '' : htmlspecialchars($statusFilter) ?> payout requests.
                        </td></tr>
                    <?php else: ?>
                        <?php foreach ($payouts as $p): ?>
                        <tr class="border-b hover:bg-gray-50">
                            <td class="px-4 py-3">
                                <div class="font-medium"><?= htmlspecialchars($p['rider_name']) ?></div>
                                <div class="text-xs text-gray-400"><?= htmlspecialchars($p['rider_phone']) ?></div>
                            </td>
                            <td class="px-4 py-3 font-medium">UGX <?= number_format((float)$p['amount']) ?></td>
                            <td class="px-4 py-3">
                                <?php
                                $badge = ['pending' => 'bg-amber-100 text-amber-700',
                                          'paid' => 'bg-green-100 text-green-700',
                                          'rejected' => 'bg-red-100 text-red-700',
                                          'approved' => 'bg-blue-100 text-blue-700'][$p['status']] ?? 'bg-gray-100 text-gray-700';
                                ?>
                                <span class="px-2 py-1 text-xs rounded-full <?= $badge ?>"><?= ucfirst($p['status']) ?></span>
                                <?php if (!empty($p['notes'])): ?>
                                    <div class="text-xs text-gray-400 mt-1"><?= htmlspecialchars($p['notes']) ?></div>
                                <?php endif; ?>
                            </td>
                            <td class="px-4 py-3 text-sm text-gray-500"><?= htmlspecialchars($p['requested_at']) ?></td>
                            <td class="px-4 py-3">
                                <?php if (in_array($p['status'], ['pending', 'approved'], true)): ?>
                                    <?php if ($p['status'] === 'pending'): ?>
                                    <form method="POST" class="inline-block">
                                        <?= csrfField() ?>
                                        <input type="hidden" name="request_id" value="<?= (int)$p['id'] ?>">
                                        <input type="hidden" name="action" value="approve">
                                        <input type="text" name="notes" placeholder="Note (optional)" class="border rounded px-2 py-1 text-sm w-32">
                                        <button type="submit" class="bg-blue-600 text-white px-3 py-1 rounded text-sm hover:bg-blue-700">Approve</button>
                                    </form>
                                    <?php endif; ?>
                                    <form method="POST" class="inline-block ml-1" onsubmit="return confirm('Confirm you have ALREADY sent this money to the rider. This only records it.')">
                                        <?= csrfField() ?>
                                        <input type="hidden" name="request_id" value="<?= (int)$p['id'] ?>">
                                        <input type="hidden" name="action" value="mark_paid">
                                        <input type="text" name="notes" placeholder="Reference (optional)" class="border rounded px-2 py-1 text-sm w-32">
                                        <button type="submit" class="bg-green-600 text-white px-3 py-1 rounded text-sm hover:bg-green-700">Mark Paid</button>
                                    </form>
                                    <form method="POST" class="inline-block ml-1" onsubmit="return confirm('Reject this payout request?')">
                                        <?= csrfField() ?>
                                        <input type="hidden" name="request_id" value="<?= (int)$p['id'] ?>">
                                        <input type="hidden" name="action" value="reject">
                                        <input type="text" name="notes" placeholder="Reason (required)" class="border rounded px-2 py-1 text-sm w-36">
                                        <button type="submit" class="bg-red-600 text-white px-3 py-1 rounded text-sm hover:bg-red-700">Reject</button>
                                    </form>
                                <?php else: ?>
                                    <span class="text-gray-400 text-sm">&mdash;</span>
                                <?php endif; ?>
                            </td>
                        </tr>
                        <?php endforeach; ?>
                    <?php endif; ?>
                </tbody>
            </table>
        </div>
    </div>
</div>
</body>
</html>