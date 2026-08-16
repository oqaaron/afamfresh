<?php
// =============================================================
// admin/audit-log.php
//
// Read-only viewer for admin_action_log — the "who did what" trail
// written by includes/admin_audit.php's logAdminAction(), called from
// every money-critical action and everything on manage-admins.php.
// Super-admin-only, same as manage-admins.php itself.
// =============================================================

session_start();
require_once 'includes/config.php';
require_once __DIR__ . '/../includes/admin_permissions.php';
requireAdminPermission('admins.manage');

$adminFilter = (int)($_GET['admin_id'] ?? 0);
$from = trim((string)($_GET['from'] ?? ''));
$to = trim((string)($_GET['to'] ?? ''));

$where = [];
$params = [];
if ($adminFilter > 0) {
    $where[] = 'l.admin_id = ?';
    $params[] = $adminFilter;
}
if ($from !== '') {
    $where[] = 'l.created_at >= ?';
    $params[] = $from . ' 00:00:00';
}
if ($to !== '') {
    $where[] = 'l.created_at <= ?';
    $params[] = $to . ' 23:59:59';
}

$page = max(1, (int)($_GET['page'] ?? 1));
$perPage = 50;
$offset = ($page - 1) * $perPage;

$sql = "SELECT l.* FROM admin_action_log l";
if ($where) {
    $sql .= " WHERE " . implode(' AND ', $where);
}
$sql .= " ORDER BY l.created_at DESC LIMIT $perPage OFFSET $offset";
$stmt = $dbh->prepare($sql);
$stmt->execute($params);
$rows = $stmt->fetchAll(PDO::FETCH_ASSOC);

$countSql = "SELECT COUNT(*) FROM admin_action_log l";
if ($where) {
    $countSql .= " WHERE " . implode(' AND ', $where);
}
$countStmt = $dbh->prepare($countSql);
$countStmt->execute($params);
$totalRows = (int)$countStmt->fetchColumn();
$totalPages = max(1, (int)ceil($totalRows / $perPage));

$adminsList = $dbh->query("SELECT id, UserName FROM admin ORDER BY UserName")->fetchAll(PDO::FETCH_ASSOC);
?>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>AfamFresh – Audit Log</title>
    <script src="https://cdn.tailwindcss.com"></script>
    <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.5.0/css/all.min.css">
</head>
<body class="bg-gray-100">
<div class="flex h-screen">
    <?php include __DIR__ . "/includes/nav.php"; ?>

    <div class="flex-1 overflow-y-auto p-6">
        <h1 class="text-2xl font-bold text-green-800 mb-2">Audit Log</h1>
        <p class="text-gray-500 text-sm mb-6"><?= number_format($totalRows) ?> recorded action<?= $totalRows === 1 ? '' : 's' ?>.</p>

        <form method="GET" class="flex flex-wrap gap-4 mb-6 bg-white p-4 rounded-xl shadow">
            <div>
                <label class="block text-xs font-medium text-gray-600 mb-1">Admin</label>
                <select name="admin_id" class="border rounded px-3 py-2 text-sm">
                    <option value="0">All admins</option>
                    <?php foreach ($adminsList as $a): ?>
                        <option value="<?= (int)$a['id'] ?>" <?= $adminFilter === (int)$a['id'] ? 'selected' : '' ?>><?= htmlspecialchars($a['UserName']) ?></option>
                    <?php endforeach; ?>
                </select>
            </div>
            <div>
                <label class="block text-xs font-medium text-gray-600 mb-1">From</label>
                <input type="date" name="from" value="<?= htmlspecialchars($from) ?>" class="border rounded px-3 py-2 text-sm">
            </div>
            <div>
                <label class="block text-xs font-medium text-gray-600 mb-1">To</label>
                <input type="date" name="to" value="<?= htmlspecialchars($to) ?>" class="border rounded px-3 py-2 text-sm">
            </div>
            <div class="self-end">
                <button type="submit" class="bg-green-600 hover:bg-green-700 text-white px-4 py-2 rounded text-sm">Filter</button>
                <a href="audit-log.php" class="bg-gray-200 hover:bg-gray-300 px-4 py-2 rounded text-sm">Reset</a>
            </div>
        </form>

        <div class="bg-white rounded-xl shadow overflow-x-auto">
            <table class="min-w-full divide-y divide-gray-200">
                <thead class="bg-gray-50">
                    <tr>
                        <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">When</th>
                        <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Admin</th>
                        <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Action</th>
                        <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Target</th>
                        <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Description</th>
                    </tr>
                </thead>
                <tbody class="divide-y divide-gray-200">
                    <?php if (!$rows): ?>
                        <tr><td colspan="5" class="px-4 py-6 text-center text-gray-500">No matching actions.</td></tr>
                    <?php else: ?>
                        <?php foreach ($rows as $r): ?>
                            <tr>
                                <td class="px-4 py-3 text-sm text-gray-500 whitespace-nowrap"><?= htmlspecialchars($r['created_at']) ?></td>
                                <td class="px-4 py-3 text-sm font-medium"><?= htmlspecialchars($r['admin_name']) ?></td>
                                <td class="px-4 py-3 text-sm"><code class="bg-gray-100 px-1.5 py-0.5 rounded text-xs"><?= htmlspecialchars($r['action']) ?></code></td>
                                <td class="px-4 py-3 text-sm text-gray-500">
                                    <?= $r['target_type'] ? htmlspecialchars($r['target_type']) . ' #' . htmlspecialchars($r['target_id'] ?? '') : '—' ?>
                                </td>
                                <td class="px-4 py-3 text-sm"><?= htmlspecialchars($r['description'] ?? '') ?></td>
                            </tr>
                        <?php endforeach; ?>
                    <?php endif; ?>
                </tbody>
            </table>
        </div>

        <?php if ($totalPages > 1): ?>
            <div class="mt-4 flex gap-2">
                <?php for ($p = 1; $p <= $totalPages; $p++): ?>
                    <a href="?page=<?= $p ?>&admin_id=<?= $adminFilter ?>&from=<?= htmlspecialchars($from) ?>&to=<?= htmlspecialchars($to) ?>"
                       class="px-3 py-1 rounded text-sm <?= $p === $page ? 'bg-green-700 text-white' : 'bg-white text-gray-700 shadow' ?>">
                        <?= $p ?>
                    </a>
                <?php endfor; ?>
            </div>
        <?php endif; ?>
    </div>
</div>
</body>
</html>
