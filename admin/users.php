<?php
session_start();
require_once '../admin/includes/config.php';

if (!isset($_SESSION['admin_logged_in']) || $_SESSION['admin_logged_in'] !== true) {
    header('Location: login.php');
    exit;
}

// Handle actions: change role, suspend, delete
if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $userId = intval($_POST['user_id'] ?? 0);
    $action = $_POST['action'] ?? '';

    if ($action === 'change_role' && $userId) {
        $role = $_POST['role'] ?? 'user';
        // Validated against the enum rather than trusted: an unexpected value
        // would be coerced to '' by MySQL, leaving the account role-less.
        //
        // Also constrained to the account's own type. Granting a customer
        // account the rider role would be inert anyway — api/rider.php checks
        // users.account_type as well as the riders link — so refusing it here
        // keeps the panel from implying something it cannot deliver.
        require_once __DIR__ . '/../includes/account_type.php';
        $acctType = accountTypeOf($dbh, $userId) ?: 'customer';
        $allowedRole = $acctType === 'customer' ? 'user' : $acctType;
        if (in_array($role, ['user', 'vendor', 'rider', 'wholesaler'], true) && $role === $allowedRole) {
            // `current_role` is a MariaDB reserved word; the backticks are required.
            $stmt = $dbh->prepare("UPDATE users SET `current_role` = ? WHERE id = ?");
            $stmt->execute([$role, $userId]);

            // Keep user_roles in step. api/auth.php's switch_role only allows a
            // role the account holds an ACTIVE user_roles row for ('user' being
            // the implicit baseline), so without this an admin-granted role
            // could not be switched back to from inside the app.
            $grant = $dbh->prepare(
                "INSERT INTO user_roles (user_id, role, status) VALUES (?, ?, 'active')
                 ON DUPLICATE KEY UPDATE status = 'active'"
            );
            $grant->execute([$userId, $role]);
        }
        header('Location: users.php?updated=1');
        exit;
    }

    if ($action === 'delete' && $userId) {
        $stmt = $dbh->prepare("DELETE FROM users WHERE id = ?");
        $stmt->execute([$userId]);
        header('Location: users.php?deleted=1');
        exit;
    }
}

// Search & filter
$search = isset($_GET['search']) ? trim($_GET['search']) : '';
$roleFilter = isset($_GET['role']) ? trim($_GET['role']) : '';

$sql = "SELECT * FROM users WHERE 1=1";
$params = [];
if (!empty($search)) {
    $sql .= " AND (fname LIKE ? OR lname LIKE ? OR email LIKE ? OR mobile LIKE ?)";
    $like = "%$search%";
    $params = [$like, $like, $like, $like];
}
if (!empty($roleFilter)) {
    // Backticked: unquoted, MariaDB reads this as the CURRENT_ROLE() function
    // (NULL), so `NULL = 'vendor'` was never true and the role filter always
    // returned an empty table.
    $sql .= " AND `current_role` = ?";
    $params[] = $roleFilter;
}
$sql .= " ORDER BY id DESC";
$stmt = $dbh->prepare($sql);
$stmt->execute($params);
$users = $stmt->fetchAll(PDO::FETCH_ASSOC);
?>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>AfamFresh – Users</title>
    <script src="https://cdn.tailwindcss.com"></script>
    <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.5.0/css/all.min.css">
</head>
<body class="bg-gray-100">
<!-- Sidebar – same as dashboard -->
<div class="flex h-screen">
    <?php include __DIR__ . "/includes/nav.php"; ?>

    <div class="flex-1 overflow-y-auto p-6">
        <h1 class="text-2xl font-bold text-green-800 mb-4">Manage Users</h1>

        <?php if (isset($_GET['updated'])): ?>
            <div class="bg-green-100 border border-green-400 text-green-700 px-4 py-3 rounded mb-4">User updated.</div>
        <?php endif; ?>
        <?php if (isset($_GET['deleted'])): ?>
            <div class="bg-red-100 border border-red-400 text-red-700 px-4 py-3 rounded mb-4">User deleted.</div>
        <?php endif; ?>

        <!-- Search & Filter -->
        <form method="GET" class="flex flex-wrap gap-4 mb-4">
            <input type="text" name="search" placeholder="Search by name, email, phone..." value="<?= htmlspecialchars($search) ?>" class="flex-1 px-4 py-2 border rounded min-w-[200px]">
            <select name="role" class="px-4 py-2 border rounded">
                <option value="">All Roles</option>
                <option value="user" <?= $roleFilter === 'user' ? 'selected' : '' ?>>User</option>
                <option value="vendor" <?= $roleFilter === 'vendor' ? 'selected' : '' ?>>Vendor</option>
                <option value="rider" <?= $roleFilter === 'rider' ? 'selected' : '' ?>>Rider</option>
                <option value="wholesaler" <?= $roleFilter === 'wholesaler' ? 'selected' : '' ?>>Wholesaler</option>
            </select>
            <button type="submit" class="bg-blue-600 hover:bg-blue-700 text-white px-4 py-2 rounded">Filter</button>
            <a href="users.php" class="bg-gray-300 hover:bg-gray-400 px-4 py-2 rounded">Reset</a>
        </form>

        <!-- Users Table -->
        <div class="bg-white rounded-xl shadow overflow-x-auto">
            <table class="w-full">
                <thead class="bg-gray-50 border-b">
                    <tr>
                        <th class="px-4 py-3 text-left">ID</th>
                        <th class="px-4 py-3 text-left">Name</th>
                        <th class="px-4 py-3 text-left">Email</th>
                        <th class="px-4 py-3 text-left">Mobile</th>
                        <th class="px-4 py-3 text-left">Type</th>
                        <th class="px-4 py-3 text-left">Role</th>
                        <th class="px-4 py-3 text-left">Actions</th>
                    </tr>
                </thead>
                <tbody>
                    <?php if (empty($users)): ?>
                        <tr><td colspan="7" class="px-4 py-6 text-center text-gray-500">No users found.</td></tr>
                    <?php else: ?>
                        <?php foreach ($users as $user): ?>
                        <tr class="border-b hover:bg-gray-50">
                            <td class="px-4 py-3"><?= $user['id'] ?></td>
                            <td class="px-4 py-3"><?= htmlspecialchars($user['fname'] . ' ' . $user['lname']) ?></td>
                            <td class="px-4 py-3"><?= ($user['email'] ?? '') !== '' ? htmlspecialchars($user['email']) : '<span class="text-gray-400">&mdash;</span>' ?></td>
                            <td class="px-4 py-3"><?= ($user['mobile'] ?? '') !== '' ? htmlspecialchars($user['mobile']) : '<span class="text-gray-400">&mdash;</span>' ?></td>
                            <td class="px-4 py-3">
                                <span class="px-2 py-1 text-xs rounded-full bg-blue-100 text-blue-700"><?= htmlspecialchars($user["account_type"] ?? "customer") ?></span>
                            </td>
                            <td class="px-4 py-3">
                                <form method="POST" class="flex items-center gap-2">
                                    <input type="hidden" name="user_id" value="<?= $user['id'] ?>">
                                    <input type="hidden" name="action" value="change_role">
                                    <select name="role" class="border rounded px-2 py-1 text-sm">
                                        <option value="user" <?= ($user['current_role'] ?? 'user') === 'user' ? 'selected' : '' ?>>User</option>
                                        <option value="vendor" <?= ($user['current_role'] ?? 'user') === 'vendor' ? 'selected' : '' ?>>Vendor</option>
                                        <option value="rider" <?= ($user['current_role'] ?? 'user') === 'rider' ? 'selected' : '' ?>>Rider</option>
                                        <option value="wholesaler" <?= ($user['current_role'] ?? 'user') === 'wholesaler' ? 'selected' : '' ?>>Wholesaler</option>
                                    </select>
                                    <button type="submit" class="bg-blue-600 text-white px-2 py-1 rounded text-sm hover:bg-blue-700">Update</button>
                                </form>
                            </td>
                            <td class="px-4 py-3">
                                <a href="edit-user.php?id=<?= $user['id'] ?>" class="text-blue-600 hover:text-blue-800 text-sm mr-3"><i class="fas fa-pen"></i> Edit</a>
                                <form method="POST" class="inline-block" onsubmit="return confirm('Delete this user and all associated data?')">
                                    <input type="hidden" name="user_id" value="<?= $user['id'] ?>">
                                    <input type="hidden" name="action" value="delete">
                                    <button type="submit" class="text-red-600 hover:text-red-800"><i class="fas fa-trash"></i></button>
                                </form>
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