<?php
// =============================================================
// admin/vendor-payouts.php
//
// Vendors request a withdrawal; this is where it is approved and then marked
// paid once the money has actually been sent.
//
// Modelled on rider-payouts.php, with one difference: vendor_payout_requests
// has an 'approved' state between pending and paid, so approving and paying
// are separate acts. Approving says "this is authorised"; marking paid says
// "the money has left". Collapsing them would mean the ledger claims a vendor
// was paid at the moment an admin agreed they should be.
//
// Payment itself happens outside the app — mobile money, bank transfer,
// whatever was arranged. Nothing here moves money; it records that someone
// did.
// =============================================================

require_once __DIR__ . '/auth_check.php';
requireAdminLoginWeb();
require_once __DIR__ . '/includes/config.php';
require_once __DIR__ . '/../includes/vendor-notification-helper.php';

$flash = '';
$flashError = '';

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $requestId = (int)($_POST['request_id'] ?? 0);
    $action    = $_POST['action'] ?? '';
    $note      = trim($_POST['notes'] ?? '');

    $stmt = $dbh->prepare(
        "SELECT pr.*, v.business_name, v.user_id
           FROM vendor_payout_requests pr
           JOIN vendors v ON v.id = pr.vendor_id
          WHERE pr.id = ?"
    );
    $stmt->execute([$requestId]);
    $payout = $stmt->fetch(PDO::FETCH_ASSOC);

    if (!$payout) {
        $flashError = 'No such payout request.';
    } elseif ($action === 'approve') {
        if ($payout['status'] !== 'pending') {
            $flashError = 'That request is already ' . $payout['status'] . '.';
        } else {
            $dbh->prepare(
                "UPDATE vendor_payout_requests SET status = 'approved', notes = ? WHERE id = ?"
            )->execute([$note ?: null, $requestId]);

            addNotification(
                (int)$payout['user_id'],
                'Withdrawal approved',
                'Your withdrawal of UGX ' . number_format((float)$payout['amount'], 2)
                    . ' has been approved and will be sent shortly.'
                    . ($note !== '' ? ' Note: ' . $note : ''),
                'payment', null, ['push', 'email']
            );
            $flash = 'Approved. The vendor has been told it is on the way.';
        }
    } elseif ($action === 'mark_paid') {
        try {
            $dbh->beginTransaction();

            if ($payout['status'] === 'paid') {
                throw new RuntimeException('That request is already paid.');
            }

            // Mark unpaid earnings paid, oldest first, only up to the amount
            // this payout covers. A vendor who earned more between requesting
            // and being paid keeps the remainder available for their next
            // request rather than it silently vanishing.
            $covered = $dbh->prepare(
                "SELECT id, net_earnings FROM vendor_earnings
                  WHERE vendor_id = ? AND is_paid = 0
                  ORDER BY created_at ASC"
            );
            $covered->execute([$payout['vendor_id']]);

            $remaining = (float)$payout['amount'];
            $markPaid = $dbh->prepare(
                "UPDATE vendor_earnings SET is_paid = 1, paid_at = NOW() WHERE id = ?"
            );
            foreach ($covered->fetchAll(PDO::FETCH_ASSOC) as $row) {
                if ($remaining <= 0) break;
                $markPaid->execute([$row['id']]);
                $remaining -= (float)$row['net_earnings'];
            }

            $dbh->prepare(
                "UPDATE vendor_payout_requests
                    SET status = 'paid', processed_at = NOW(), notes = COALESCE(?, notes)
                  WHERE id = ?"
            )->execute([$note ?: null, $requestId]);

            $dbh->commit();

            addNotification(
                (int)$payout['user_id'],
                'Withdrawal sent',
                'UGX ' . number_format((float)$payout['amount'], 2) . ' has been sent to you.'
                    . ($note !== '' ? ' Note: ' . $note : ''),
                'payment', null, ['push', 'email']
            );
            $flash = 'Marked as paid and the vendor notified.';
        } catch (Exception $e) {
            if ($dbh->inTransaction()) $dbh->rollBack();
            error_log('vendor payout mark_paid failed: ' . $e->getMessage());
            $flashError = $e instanceof RuntimeException
                ? $e->getMessage()
                : 'Could not mark this payout as paid.';
        }
    } elseif ($action === 'reject') {
        if ($note === '') {
            $flashError = 'Give a reason — the vendor sees it, and an unexplained rejection just gets resubmitted.';
        } else {
            $dbh->prepare(
                "UPDATE vendor_payout_requests
                    SET status = 'rejected', processed_at = NOW(), notes = ?
                  WHERE id = ? AND status IN ('pending','approved')"
            )->execute([$note, $requestId]);

            addNotification(
                (int)$payout['user_id'],
                'Withdrawal not approved',
                'Your withdrawal request was not approved. Reason: ' . $note
                    . ' Your earnings remain available to request again.',
                'payment', null, ['push', 'email']
            );
            $flash = 'Rejected. The vendor has been told why.';
        }
    }
}

$statusFilter = $_GET['status'] ?? 'pending';
if (!in_array($statusFilter, ['pending', 'approved', 'paid', 'rejected', 'all'], true)) {
    $statusFilter = 'pending';
}

$sql = "SELECT pr.*, v.business_name, v.phone,
               (SELECT COALESCE(SUM(net_earnings), 0) FROM vendor_earnings
                 WHERE vendor_id = pr.vendor_id AND is_paid = 0) AS unpaid_now
          FROM vendor_payout_requests pr
          JOIN vendors v ON v.id = pr.vendor_id";
$params = [];
if ($statusFilter !== 'all') {
    $sql .= " WHERE pr.status = ?";
    $params[] = $statusFilter;
}
$sql .= " ORDER BY pr.id DESC";
$stmt = $dbh->prepare($sql);
$stmt->execute($params);
$requests = $stmt->fetchAll(PDO::FETCH_ASSOC);

$pendingCount = (int)$dbh->query(
    "SELECT COUNT(*) FROM vendor_payout_requests WHERE status = 'pending'"
)->fetchColumn();
?>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Vendor Payouts — AfamFresh Admin</title>
    <script src="https://cdn.tailwindcss.com"></script>
    <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.5.0/css/all.min.css">
</head>
<body class="bg-gray-50 font-sans antialiased">
<div class="flex min-h-screen">
<?php include __DIR__ . '/includes/nav.php'; ?>
<div class="flex-1 overflow-auto">
    <div class="max-w-7xl mx-auto px-6 py-8">
        <h1 class="text-2xl font-bold text-green-800 mb-1">Vendor Payouts</h1>
        <p class="text-gray-600 mb-6">
            Approving authorises a withdrawal. Mark it paid only once the money has
            actually been sent — that is what clears the vendor's balance.
        </p>

        <?php if ($flash): ?>
            <div class="bg-green-100 border border-green-300 text-green-800 px-4 py-3 rounded mb-4"><?= htmlspecialchars($flash) ?></div>
        <?php endif; ?>
        <?php if ($flashError): ?>
            <div class="bg-red-100 border border-red-300 text-red-700 px-4 py-3 rounded mb-4"><?= htmlspecialchars($flashError) ?></div>
        <?php endif; ?>

        <div class="mb-5 space-x-2">
            <?php foreach (['pending', 'approved', 'paid', 'rejected', 'all'] as $f): ?>
                <a href="?status=<?= $f ?>"
                   class="px-3 py-1 rounded-full text-sm <?= $statusFilter === $f ? 'bg-green-700 text-white' : 'bg-gray-200 text-gray-700' ?>">
                    <?= ucfirst($f) ?><?= $f === 'pending' && $pendingCount > 0 ? " ($pendingCount)" : '' ?>
                </a>
            <?php endforeach; ?>
        </div>

        <?php if (!$requests): ?>
            <div class="bg-white rounded-xl shadow p-8 text-center text-gray-500">
                Nothing <?= htmlspecialchars($statusFilter) ?> right now.
            </div>
        <?php else: ?>
            <div class="space-y-4">
            <?php foreach ($requests as $r): ?>
                <?php
                $badge = ['pending' => 'bg-yellow-100 text-yellow-800',
                          'approved' => 'bg-blue-100 text-blue-800',
                          'paid' => 'bg-green-100 text-green-800',
                          'rejected' => 'bg-red-100 text-red-800'][$r['status']] ?? 'bg-gray-100';
                ?>
                <form method="post" class="bg-white rounded-xl shadow p-5">
                    <input type="hidden" name="request_id" value="<?= (int)$r['id'] ?>">
                    <div class="flex justify-between items-start mb-3">
                        <div>
                            <div class="font-semibold text-gray-900">
                                #<?= (int)$r['id'] ?> · <?= htmlspecialchars($r['business_name']) ?>
                            </div>
                            <div class="text-sm text-gray-500">
                                Requested <?= htmlspecialchars($r['requested_at']) ?>
                                <?php if (!empty($r['phone'])): ?> · <?= htmlspecialchars($r['phone']) ?><?php endif; ?>
                            </div>
                            <?php if (!empty($r['notes'])): ?>
                                <div class="text-sm text-gray-600 mt-1">Note: <?= htmlspecialchars($r['notes']) ?></div>
                            <?php endif; ?>
                        </div>
                        <div class="text-right">
                            <div class="text-lg font-bold text-green-800">UGX <?= number_format((float)$r['amount'], 2) ?></div>
                            <span class="px-2 py-1 rounded-full text-xs font-semibold <?= $badge ?>"><?= htmlspecialchars($r['status']) ?></span>
                        </div>
                    </div>

                    <?php if (abs((float)$r['unpaid_now'] - (float)$r['amount']) > 0.01): ?>
                        <div class="text-xs text-gray-600 mb-3">
                            Unpaid balance is now UGX <?= number_format((float)$r['unpaid_now'], 2) ?> —
                            it has changed since this was requested. Only the requested amount is cleared when you mark it paid.
                        </div>
                    <?php endif; ?>

                    <?php if ($r['status'] !== 'paid' && $r['status'] !== 'rejected'): ?>
                        <input type="text" name="notes" placeholder="Note to vendor (required to reject)"
                               class="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm mb-3">
                        <div class="space-x-2">
                            <?php if ($r['status'] === 'pending'): ?>
                                <button name="action" value="approve"
                                        class="bg-blue-600 hover:bg-blue-700 text-white px-4 py-2 rounded-lg text-sm font-semibold">Approve</button>
                            <?php endif; ?>
                            <button name="action" value="mark_paid"
                                    onclick="return confirm('Confirm the money has actually been sent to this vendor.')"
                                    class="bg-green-700 hover:bg-green-800 text-white px-4 py-2 rounded-lg text-sm font-semibold">Mark as paid</button>
                            <button name="action" value="reject"
                                    class="bg-red-600 hover:bg-red-700 text-white px-4 py-2 rounded-lg text-sm font-semibold">Reject</button>
                        </div>
                    <?php else: ?>
                        <div class="text-sm text-gray-500">
                            Processed <?= htmlspecialchars($r['processed_at'] ?? '—') ?>
                        </div>
                    <?php endif; ?>
                </form>
            <?php endforeach; ?>
            </div>
        <?php endif; ?>
    </div>
</div>
</div>
</body>
</html>