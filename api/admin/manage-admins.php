<?php
// =============================================================
// admin/manage-admins.php
//
// Super-admin-only: create limited-access admin accounts (e.g. a
// dispatcher who can only touch orders and non-price product fields —
// see includes/admin_permissions.php's ADMIN_ROLES), and deactivate/
// reactivate existing ones. Never a hard DELETE: admin_action_log keeps a
// name attached to an account's history, and deactivating preserves that
// while still blocking login.
// =============================================================

session_start();
require_once 'includes/config.php';
require_once __DIR__ . '/../includes/csrf.php';
require_once __DIR__ . '/../includes/admin_permissions.php';
require_once __DIR__ . '/../includes/admin_audit.php';
requireAdminPermission('admins.manage');

$error = '';
$success = '';

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    verifyCsrf();
    $action = $_POST['action'] ?? '';

    if ($action === 'create') {
        $username = trim($_POST['username'] ?? '');
        $password = (string)($_POST['password'] ?? '');
        $role = $_POST['role'] ?? '';

        if ($username === '' || strlen($password) < 8) {
            $error = 'Give a username and a password of at least 8 characters.';
        } elseif (!array_key_exists($role, ADMIN_ROLES)) {
            $error = 'Choose a valid role.';
        } else {
            $exists = $dbh->prepare("SELECT id FROM admin WHERE UserName = ?");
            $exists->execute([$username]);
            if ($exists->fetchColumn()) {
                $error = 'That username is already taken.';
            } else {
                $hashed = password_hash($password, PASSWORD_DEFAULT);
                $stmt = $dbh->prepare(
                    "INSERT INTO admin (UserName, Password, role, created_by, is_active) VALUES (?, ?, ?, ?, 1)"
                );
                $stmt->execute([$username, $hashed, $role, $_SESSION['admin_id']]);
                $newId = (int)$dbh->lastInsertId();
                logAdminAction($dbh, 'admin.created', 'admin', (string)$newId, "Created \"$username\" with role \"$role\"");
                $success = "Admin \"$username\" created with role \"$role\".";
            }
        }
    } elseif ($action === 'deactivate' || $action === 'reactivate') {
        $targetId = (int)($_POST['id'] ?? 0);
        if ($targetId === (int)$_SESSION['admin_id']) {
            $error = 'You cannot deactivate your own account.';
        } elseif ($targetId) {
            $newActive = $action === 'reactivate' ? 1 : 0;
            $dbh->prepare("UPDATE admin SET is_active = ? WHERE id = ?")->execute([$newActive, $targetId]);
            logAdminAction($dbh, $action === 'reactivate' ? 'admin.reactivated' : 'admin.deactivated', 'admin', (string)$targetId, ucfirst($action) . 'd admin #' . $targetId);
            $success = $action === 'reactivate' ? 'Admin reactivated.' : 'Admin deactivated.';
        }
    }
}

$admins = $dbh->query(
    "SELECT a.id, a.UserName, a.role, a.is_active, a.updationDate, c.UserName AS created_by_name
       FROM admin a
       LEFT JOIN admin c ON c.id = a.created_by
      ORDER BY a.id ASC"
)->fetchAll(PDO::FETCH_ASSOC);
?>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>AfamFresh – Manage Admins</title>
    <script src="https://cdn.tailwindcss.com"></script>
    <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.5.0/css/all.min.css">
</head>
<body class="bg-gray-100">
<div class="flex h-screen">
    <?php include __DIR__ . "/includes/nav.php"; ?>

    <div class="flex-1 overflow-y-auto p-6">
        <div class="max-w-4xl">
            <h1 class="text-2xl font-bold text-green-800 mb-2">Manage Admins</h1>
            <p class="text-gray-500 text-sm mb-6">
                Onboard staff with limited access. A role's exact permissions live in
                includes/admin_permissions.php — every action here, and everything a
                limited admin does, is recorded in the <a href="audit-log.php" class="text-green-700 underline">Audit Log</a>.
            </p>

            <?php if ($error): ?>
                <div class="bg-red-100 border border-red-400 text-red-700 px-4 py-3 rounded mb-4"><?= htmlspecialchars($error) ?></div>
            <?php endif; ?>
            <?php if ($success): ?>
                <div class="bg-green-100 border border-green-400 text-green-700 px-4 py-3 rounded mb-4"><?= htmlspecialchars($success) ?></div>
            <?php endif; ?>

            <div class="bg-white p-8 rounded-xl shadow mb-6">
                <h2 class="text-lg font-bold text-gray-800 mb-4">Create a new admin</h2>
                <form method="POST">
                    <?= csrfField() ?>
                    <input type="hidden" name="action" value="create">
                    <div class="grid grid-cols-1 md:grid-cols-3 gap-4">
                        <div>
                            <label class="block text-gray-700 font-bold mb-2">Username</label>
                            <input type="text" name="username" class="w-full px-4 py-2 border rounded" required>
                        </div>
                        <div>
                            <label class="block text-gray-700 font-bold mb-2">Password</label>
                            <input type="password" name="password" minlength="8" class="w-full px-4 py-2 border rounded" required>
                        </div>
                        <div>
                            <label class="block text-gray-700 font-bold mb-2">Role</label>
                            <select name="role" class="w-full px-4 py-2 border rounded">
                                <?php foreach (array_keys(ADMIN_ROLES) as $roleKey): ?>
                                    <option value="<?= htmlspecialchars($roleKey) ?>"><?= htmlspecialchars($roleKey) ?></option>
                                <?php endforeach; ?>
                            </select>
                        </div>
                    </div>
                    <button type="submit" class="mt-4 bg-green-600 hover:bg-green-700 text-white px-6 py-2 rounded">Create admin</button>
                </form>
            </div>

            <div class="bg-white rounded-xl shadow overflow-hidden">
                <table class="min-w-full divide-y divide-gray-200">
                    <thead class="bg-gray-50">
                        <tr>
                            <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Username</th>
                            <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Role</th>
                            <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Status</th>
                            <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Created by</th>
                            <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Action</th>
                        </tr>
                    </thead>
                    <tbody class="divide-y divide-gray-200">
                        <?php foreach ($admins as $a): ?>
                            <tr>
                                <td class="px-4 py-3 text-sm font-medium"><?= htmlspecialchars($a['UserName']) ?></td>
                                <td class="px-4 py-3 text-sm"><?= htmlspecialchars($a['role']) ?></td>
                                <td class="px-4 py-3 text-sm">
                                    <span class="px-2 py-1 rounded-full text-xs font-semibold <?= $a['is_active'] ? 'bg-green-100 text-green-800' : 'bg-gray-200 text-gray-600' ?>">
                                        <?= $a['is_active'] ? 'Active' : 'Deactivated' ?>
                                    </span>
                                </td>
                                <td class="px-4 py-3 text-sm text-gray-500"><?= htmlspecialchars($a['created_by_name'] ?? '—') ?></td>
                                <td class="px-4 py-3 text-sm">
                                    <?php if ((int)$a['id'] === (int)$_SESSION['admin_id']): ?>
                                        <span class="text-gray-400">This is you</span>
                                    <?php else: ?>
                                        <form method="POST" onsubmit="return confirm('<?= $a['is_active'] ? 'Deactivate' : 'Reactivate' ?> this admin?');">
                                            <?= csrfField() ?>
                                            <input type="hidden" name="action" value="<?= $a['is_active'] ? 'deactivate' : 'reactivate' ?>">
                                            <input type="hidden" name="id" value="<?= (int)$a['id'] ?>">
                                            <button type="submit" class="<?= $a['is_active'] ? 'text-red-600 hover:text-red-800' : 'text-green-700 hover:text-green-900' ?> font-semibold">
                                                <?= $a['is_active'] ? 'Deactivate' : 'Reactivate' ?>
                                            </button>
                                        </form>
                                    <?php endif; ?>
                                </td>
                            </tr>
                        <?php endforeach; ?>
                    </tbody>
                </table>
            </div>
        </div>
    </div>
</div>
</body>
</html>
