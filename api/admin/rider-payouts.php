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
// =============================================================

session_start();
require_once '../admin/includes/config.php';
require_once __DIR__ . '/../includes/csrf.php';
require_once __DIR__ . '/../includes/admin_permissions.php';
require_once __DIR__ . '/../includes/admin_audit.php';
requireAdminPermission('payouts.manage');

$flash = '';
$flashError = '';

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    verifyCsrf();
    $requestId = intval($_POST['request_id'] ?? 0);
    $action    = $_POST['action'] ?? '';

    if ($requestId && $action === 'mark_paid') {
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
                "UPDATE rider_payout_requests SET status = 'paid', processed_at = NOW() WHERE id = ?"
            )->execute([$requestId]);

            $dbh->commit();
            logAdminAction($dbh, 'rider_payout.marked_paid', 'rider_payout_request', (string)$requestId,
                'Marked paid, UGX ' . number_format((float)$payout['amount']));
            $flash = 'Payout marked as paid.';
        } catch (Exception $e) {
            if ($dbh->inTransaction()) $dbh->rollBack();
            error_log('rider payout mark_paid failed: ' . $e->getMessage());
            $flashError = $e instanceof RuntimeException ? $e->getMessage() : 'Could not mark this payout as paid.';
        }
    } elseif ($requestId && $action === 'reject') {
        $note = trim($_POST['notes'] ?? '');
        $stmt = $dbh->prepare(
            "UPDATE rider_payout_requests SET status = 'rejected', processed_at = NOW(), notes = ?
              WHERE id = ? AND status = 'pending'"
        );
        $stmt->execute([$note ?: null, $requestId]);
        logAdminAction($dbh, 'rider_payout.rejected', 'rider_payout_request', (string)$requestId, $note ?: 'Rejected');
        $flash = 'Payout request rejected.';
    }
}

$statusFilter = $_GET['status'] ?? 'pending';
$allowed = ['pending', 'paid', 'rejected', 'all'];
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
            <?php foreach (['pending', 'paid', 'rejected', 'all'] as $s): ?>
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
                                <?php if ($p['status'] === 'pending'): ?>
                                    <form method="POST" class="inline-block" onsubmit="return confirm('Confirm you have paid this rider outside the app?')">
                                        <?= csrfField() ?>
                                        <input type="hidden" name="request_id" value="<?= (int)$p['id'] ?>">
                                        <input type="hidden" name="action" value="mark_paid">
                                        <button type="submit" class="bg-green-600 text-white px-3 py-1 rounded text-sm hover:bg-green-700">Mark Paid</button>
                                    </form>
                                    <form method="POST" class="inline-block ml-1" onsubmit="return confirm('Reject this payout request?')">
                                        <?= csrfField() ?>
                                        <input type="hidden" name="request_id" value="<?= (int)$p['id'] ?>">
                                        <input type="hidden" name="action" value="reject">
                                        <input type="text" name="notes" placeholder="Reason (optional)" class="border rounded px-2 py-1 text-sm w-36">
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