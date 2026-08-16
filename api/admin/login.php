<?php
session_start();
require_once 'includes/config.php';
require_once __DIR__ . '/../includes/csrf.php';
require_once __DIR__ . '/../includes/rate_limit.php';

$error = '';

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    verifyCsrf();

    // 5 attempts / 5 minutes per IP. Login has no per-account limiting on
    // top of this (unlike api/auth.php, which also buckets by the
    // submitted identifier) -- admin accounts are few and staff-only, so an
    // IP-only limit already covers it even now that more than one exists.
    if (rateLimited($dbh, 'admin_login:ip:' . ($_SERVER['REMOTE_ADDR'] ?? 'unknown'), 5, 300)) {
        failRateLimited();
    }

    $username = trim($_POST['username'] ?? '');
    $password = trim($_POST['password'] ?? '');

    $stmt = $dbh->prepare("SELECT * FROM admin WHERE UserName = ?");
    $stmt->execute([$username]);
    $admin = $stmt->fetch(PDO::FETCH_ASSOC);

    if ($admin) {
        // Check both MD5 and password_hash formats
        $valid = false;

        // 1. Check if it's password_hash format
        if (password_get_info($admin['Password'])['algo'] !== 0) {
            $valid = password_verify($password, $admin['Password']);
        }

        // 2. If not, check MD5 (legacy)
        if (!$valid && md5($password) === $admin['Password']) {
            $valid = true;
            // Upgraded on the next successful login, not removed outright:
            // this only fires for a row still in the old format, so every
            // legacy admin silently moves to password_hash() the first time
            // they sign in after this shipped, with nothing else to do.
            $hashed = password_hash($password, PASSWORD_DEFAULT);
            $dbh->prepare("UPDATE admin SET Password = ? WHERE id = ?")->execute([$hashed, $admin['id']]);
        }

        if ($valid && (int)($admin['is_active'] ?? 1) !== 1) {
            $error = 'This account has been deactivated. Contact your super admin.';
        } elseif ($valid) {
            // New session id on every privilege change -- an id an attacker
            // fixed before login (session fixation) stops being useful the
            // moment login actually succeeds.
            session_regenerate_id(true);
            $_SESSION['admin_logged_in'] = true;
            $_SESSION['admin_id'] = $admin['id'];
            $_SESSION['admin_name'] = $admin['UserName'];
            $_SESSION['admin_role'] = $admin['role'] ?? 'super_admin';
            header('Location: dashboard.php');
            exit;
        }
    }
    if ($error === '') {
        $error = 'Invalid username or password';
    }
}
?>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>AfamFresh Admin Login</title>
    <script src="https://cdn.tailwindcss.com"></script>
</head>
<body class="bg-gray-100 flex items-center justify-center h-screen">
    <div class="bg-white p-8 rounded-xl shadow-lg w-full max-w-md">
        <div class="text-center mb-8">
            <h1 class="text-2xl font-bold text-green-800">AfamFresh Admin</h1>
            <p class="text-gray-600">Sign in to manage your store</p>
        </div>

        <?php if ($error): ?>
            <div class="bg-red-100 border border-red-400 text-red-700 px-4 py-3 rounded mb-4"><?= htmlspecialchars($error) ?></div>
        <?php endif; ?>

        <form method="POST">
            <?= csrfField() ?>
            <div class="mb-4">
                <label class="block text-gray-700 text-sm font-bold mb-2">Username</label>
                <input type="text" name="username" value="admin" class="w-full px-4 py-2 border rounded-lg focus:outline-none focus:ring-2 focus:ring-green-600" required>
            </div>
            <div class="mb-6">
                <label class="block text-gray-700 text-sm font-bold mb-2">Password</label>
                <input type="password" name="password" placeholder="Enter password" class="w-full px-4 py-2 border rounded-lg focus:outline-none focus:ring-2 focus:ring-green-600" required>
            </div>
            <button type="submit" class="w-full bg-green-600 hover:bg-green-700 text-white font-bold py-2 rounded-lg transition">Sign In</button>
        </form>
    </div>
</body>
</html>
