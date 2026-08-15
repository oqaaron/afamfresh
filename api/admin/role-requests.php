<?php
// =============================================================
// admin/role-requests.php
//
// Approve or reject the role requests filed from the Rider and
// Vendor apps. Those apps let anyone register, but grant nothing
// until a request is approved here — this page is the gate.
//
// Approving does two things (see approveRoleRequest and
// provisionRoleRecord in includes/roles.php): it activates the
// user_roles row, AND it creates the riders/vendors record the
// role's app reads. Granting the role alone would leave the person
// looking at "this account is not set up as a rider yet".
// =============================================================

session_start();
require_once '../admin/includes/config.php';
require_once __DIR__ . '/../includes/roles.php';
require_once __DIR__ . '/../includes/csrf.php';

// === true, not a bare isset(): isset() is satisfied by a value of
// literal false, which would let a logged-out session through.
if (!isset($_SESSION['admin_logged_in']) || $_SESSION['admin_logged_in'] !== true) {
    header('Location: login.php');
    exit;
}

$flash = '';
$flashError = '';

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    verifyCsrf();
    $requestId = intval($_POST['request_id'] ?? 0);
    $action    = $_POST['action'] ?? '';

    if ($requestId && $action === 'approve') {
        // Returns true, or a sentence explaining what stopped it — most often
        // "user already has an extra role", which is a rule, not a failure.
        $result = approveRoleRequest($requestId);
        if ($result === true) {
            $flash = 'Request approved. The role and its profile have been created.';
        } else {
            $flashError = is_string($result) ? $result : 'Could not approve that request.';
        }
    } elseif ($requestId && $action === 'reject') {
        $note = trim($_POST['admin_notes'] ?? '');
        if ($note !== '') {
            $upd = $dbh->prepare("UPDATE role_requests SET admin_notes = ? WHERE id = ?");
            $upd->execute([$note, $requestId]);
        }
        $flashError = rejectRoleRequest($requestId)
            ? ''
            : 'Could not reject that request.';
        if ($flashError === '') {
            $flash = 'Request rejected.';
        }
    }
}

$statusFilter = $_GET['status'] ?? 'pending';
$allowed = ['pending', 'approved', 'rejected', 'all'];
if (!in_array($statusFilter, $allowed, true)) {
    $statusFilter = 'pending';
}

$sql = "SELECT rr.*, u.fname, u.lname, u.email, u.mobile
          FROM role_requests rr
          JOIN users u ON u.id = rr.user_id";
$params = [];
if ($statusFilter !== 'all') {
    $sql .= " WHERE rr.status = ?";
    $params[] = $statusFilter;
}
$sql .= " ORDER BY rr.created_at DESC";
$stmt = $dbh->prepare($sql);
$stmt->execute($params);
$requests = $stmt->fetchAll(PDO::FETCH_ASSOC);

$pendingCount = (int)$dbh->query("SELECT COUNT(*) FROM role_requests WHERE status = 'pending'")->fetchColumn();
?>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>AfamFresh – Role Requests</title>
    <script src="https://cdn.tailwindcss.com"></script>
    <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.5.0/css/all.min.css">
</head>
<body class="bg-gray-100">
<div class="flex h-screen">
    <?php include __DIR__ . "/includes/nav.php"; ?>

    <div class="flex-1 overflow-y-auto p-6">
        <h1 class="text-2xl font-bold text-green-800 mb-4">Role Requests</h1>

        <?php if ($flash): ?>
            <div class="bg-green-100 border border-green-400 text-green-700 px-4 py-3 rounded mb-4"><?= htmlspecialchars($flash) ?></div>
        <?php endif; ?>
        <?php if ($flashError): ?>
            <div class="bg-red-100 border border-red-400 text-red-700 px-4 py-3 rounded mb-4"><?= htmlspecialchars($flashError) ?></div>
        <?php endif; ?>

        <div class="flex gap-2 mb-4">
            <?php foreach (['pending', 'approved', 'rejected', 'all'] as $s): ?>
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
                        <th class="px-4 py-3 text-left">Applicant</th>
                        <th class="px-4 py-3 text-left">Contact</th>
                        <th class="px-4 py-3 text-left">Requested</th>
                        <th class="px-4 py-3 text-left">Status</th>
                        <th class="px-4 py-3 text-left">Submitted</th>
                        <th class="px-4 py-3 text-left">Action</th>
                    </tr>
                </thead>
                <tbody>
                    <?php if (empty($requests)): ?>
                        <tr><td colspan="6" class="px-4 py-6 text-center text-gray-500">
                            No <?= $statusFilter === 'all' ? '' : htmlspecialchars($statusFilter) ?> requests.
                        </td></tr>
                    <?php else: ?>
                        <?php foreach ($requests as $r): ?>
                        <tr class="border-b hover:bg-gray-50">
                            <td class="px-4 py-3">
                                <div class="font-medium"><?= htmlspecialchars(trim($r['fname'] . ' ' . $r['lname'])) ?></div>
                                <div class="text-xs text-gray-400">Account #<?= (int)$r['user_id'] ?></div>
                            </td>
                            <td class="px-4 py-3 text-sm">
                                <?= ($r['email'] ?? '') !== '' ? htmlspecialchars($r['email']) : '<span class="text-gray-400">&mdash;</span>' ?><br>
                                <?= ($r['mobile'] ?? '') !== '' ? htmlspecialchars($r['mobile']) : '<span class="text-gray-400">no phone</span>' ?>
                            </td>
                            <td class="px-4 py-3">
                                <span class="px-2 py-1 text-xs rounded-full bg-blue-100 text-blue-700"><?= htmlspecialchars($r['requested_role']) ?></span>
                            </td>
                            <td class="px-4 py-3">
                                <?php
                                $badge = ['pending' => 'bg-amber-100 text-amber-700',
                                          'approved' => 'bg-green-100 text-green-700',
                                          'rejected' => 'bg-red-100 text-red-700'][$r['status']] ?? 'bg-gray-100 text-gray-700';
                                ?>
                                <span class="px-2 py-1 text-xs rounded-full <?= $badge ?>"><?= ucfirst($r['status']) ?></span>
                                <?php if (!empty($r['admin_notes'])): ?>
                                    <div class="text-xs text-gray-400 mt-1"><?= htmlspecialchars($r['admin_notes']) ?></div>
                                <?php endif; ?>
                            </td>
                            <td class="px-4 py-3 text-sm text-gray-500"><?= htmlspecialchars($r['created_at']) ?></td>
                            <td class="px-4 py-3">
                                <?php if ($r['status'] === 'pending'): ?>
                                    <form method="POST" class="inline-block">
                                        <?= csrfField() ?>
                                        <input type="hidden" name="request_id" value="<?= (int)$r['id'] ?>">
                                        <input type="hidden" name="action" value="approve">
                                        <button type="submit" class="bg-green-600 text-white px-3 py-1 rounded text-sm hover:bg-green-700">Approve</button>
                                    </form>
                                    <form method="POST" class="inline-block ml-1" onsubmit="return confirm('Reject this request?')">
                                        <?= csrfField() ?>
                                        <input type="hidden" name="request_id" value="<?= (int)$r['id'] ?>">
                                        <input type="hidden" name="action" value="reject">
                                        <input type="text" name="admin_notes" placeholder="Reason (optional)" class="border rounded px-2 py-1 text-sm w-36">
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