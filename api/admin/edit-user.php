<?php
// =============================================================
// admin/edit-user.php
//
// Full single-record edit for an app user. users.php previously
// offered only "change role" and "delete", so nothing in the panel
// could correct a name, email, phone or delivery address.
//
// Three column traps this file exists to respect:
//
//  * `email` and `mobile` are both UNIQUE. A blank value must be
//    written as NULL, never '' — two NULLs coexist under a UNIQUE
//    key, two empty strings collide.
//  * `area` and `address` are NOT NULL, so the opposite applies
//    there: blank must be '' and never NULL.
//  * `current_role` is a MariaDB reserved word and must be
//    backticked in writes (see api/auth.php, includes/roles.php).
// =============================================================

session_start();
require_once 'includes/config.php';

// === true, not a bare isset(): isset() is satisfied by a value of
// literal false, which would let a logged-out session through.
if (!isset($_SESSION['admin_logged_in']) || $_SESSION['admin_logged_in'] !== true) {
    header('Location: login.php');
    exit;
}

$id = intval($_GET['id'] ?? 0);
if (!$id) {
    header('Location: users.php');
    exit;
}

$stmt = $dbh->prepare("SELECT * FROM users WHERE id = ?");
$stmt->execute([$id]);
$user = $stmt->fetch(PDO::FETCH_ASSOC);
if (!$user) {
    header('Location: users.php');
    exit;
}

// The roles this account may hold are decided by what it IS, not by a free
// choice. Granting a customer account the rider role would be misleading:
// api/rider.php checks users.account_type as well as the riders link, so the
// role would be inert. Offering only the valid option keeps the panel honest.
require_once __DIR__ . '/../includes/account_type.php';
$accountType = accountTypeOf($dbh, $id) ?: 'customer';
$ROLES = $accountType === 'customer' ? ['user'] : [$accountType];

$error = '';
$success = '';

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $fname   = trim($_POST['fname'] ?? '');
    $lname   = trim($_POST['lname'] ?? '');
    $email   = trim($_POST['email'] ?? '');
    $mobile  = trim($_POST['mobile'] ?? '');
    $area    = trim($_POST['area'] ?? '');
    $address = trim($_POST['address'] ?? '');
    $role    = trim($_POST['current_role'] ?? '');
    $loyalty = trim($_POST['loyalty_points'] ?? '0');
    $newPassword = (string)($_POST['new_password'] ?? '');

    if ($fname === '' || $lname === '') {
        $error = 'First and last name are required.';
    } elseif ($email !== '' && !filter_var($email, FILTER_VALIDATE_EMAIL)) {
        $error = 'That email address does not look valid.';
    } elseif ($mobile !== '' && !preg_match('/^\+?[0-9]{7,15}$/', $mobile)) {
        $error = 'Mobile must be 7 to 15 digits, optionally starting with +.';
    } elseif (!in_array($role, $ROLES, true)) {
        $error = 'Please choose a valid role.';
    } elseif (!ctype_digit($loyalty)) {
        $error = 'Loyalty points must be a whole number.';
    } elseif ($newPassword !== '' && strlen($newPassword) < 6) {
        $error = 'A new password must be at least 6 characters.';
    } else {
        // NULL for the UNIQUE columns when blank...
        $emailValue  = ($email === '')  ? null : $email;
        $mobileValue = ($mobile === '') ? null : $mobile;
        // ...but '' for the NOT NULL ones. Opposite rule, same form.
        $areaValue    = $area;
        $addressValue = $address;

        try {
            $dbh->beginTransaction();

            if ($newPassword !== '') {
                $sql = "UPDATE users SET fname=?, lname=?, email=?, mobile=?, area=?, address=?, `current_role`=?, loyalty_points=?, password=? WHERE id=?";
                $params = [$fname, $lname, $emailValue, $mobileValue, $areaValue, $addressValue,
                           $role, (int)$loyalty, password_hash($newPassword, PASSWORD_DEFAULT), $id];
            } else {
                // password omitted entirely, so a blank box cannot clear the
                // existing hash (or hand a Google-only account an empty one).
                $sql = "UPDATE users SET fname=?, lname=?, email=?, mobile=?, area=?, address=?, `current_role`=?, loyalty_points=? WHERE id=?";
                $params = [$fname, $lname, $emailValue, $mobileValue, $areaValue, $addressValue,
                           $role, (int)$loyalty, $id];
            }
            $dbh->prepare($sql)->execute($params);

            // Keep user_roles in step with the role just granted.
            //
            // Without this the panel and the app disagree: api/auth.php's
            // switch_role checks for an ACTIVE user_roles row and refuses
            // anything else (except 'user', the implicit baseline). So an
            // admin-granted vendor role worked until the person switched back
            // to customer, at which point they could never switch to vendor
            // again. None of the current users have any user_roles rows at all.
            $grant = $dbh->prepare(
                "INSERT INTO user_roles (user_id, role, status) VALUES (?, ?, 'active')
                 ON DUPLICATE KEY UPDATE status = 'active'"
            );
            $grant->execute([$id, $role]);

            $dbh->commit();

            $success = 'User updated successfully!';
            if ($newPassword !== '') {
                $success .= ' Password set.';
            }

            $stmt = $dbh->prepare("SELECT * FROM users WHERE id = ?");
            $stmt->execute([$id]);
            $user = $stmt->fetch(PDO::FETCH_ASSOC);
        } catch (PDOException $e) {
            if ($dbh->inTransaction()) {
                $dbh->rollBack();
            }
            // Caught rather than pre-checked, which also closes the race
            // between "is it taken?" and the write. Uncaught, this printed a
            // raw stack trace: display_errors is on outside production.
            if ($e->getCode() === '23000') {
                $error = 'Another account already uses that email address or mobile number.';
            } else {
                error_log('edit-user update failed: ' . $e->getMessage());
                $error = 'Could not save the user. Please try again.';
            }
        }
    }
}

// Roles this account actually holds, for the read-only summary below.
$heldStmt = $dbh->prepare("SELECT role, status FROM user_roles WHERE user_id = ? ORDER BY role");
$heldStmt->execute([$id]);
$heldRoles = $heldStmt->fetchAll(PDO::FETCH_ASSOC);

$isGoogle   = !empty($user['google_id']);
$hasPassword = !empty($user['password']);
?>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>AfamFresh – Edit User</title>
    <script src="https://cdn.tailwindcss.com"></script>
    <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.5.0/css/all.min.css">
</head>
<body class="bg-gray-100">
<div class="flex h-screen">
    <div class="w-64 bg-green-800 text-white flex flex-col">
        <div class="p-6 text-xl font-bold border-b border-green-700">AfamFresh</div>
        <nav class="flex-1 p-4 space-y-2">
            <a href="dashboard.php" class="block py-2 px-4 rounded hover:bg-green-700"><i class="fas fa-chart-pie mr-2"></i> Dashboard</a>
            <a href="products.php" class="block py-2 px-4 rounded hover:bg-green-700"><i class="fas fa-box mr-2"></i> Products</a>
            <a href="orders.php" class="block py-2 px-4 rounded hover:bg-green-700"><i class="fas fa-shopping-cart mr-2"></i> Orders</a>
            <a href="users.php" class="block py-2 px-4 rounded bg-green-700"><i class="fas fa-users mr-2"></i> Users</a>
            <a href="riders.php" class="block py-2 px-4 rounded hover:bg-green-700"><i class="fas fa-motorcycle mr-2"></i> Riders</a>
            <a href="logout.php" class="block py-2 px-4 rounded hover:bg-red-600 mt-8"><i class="fas fa-sign-out-alt mr-2"></i> Logout</a>
        </nav>
    </div>

    <div class="flex-1 overflow-y-auto p-6">
        <div class="max-w-3xl">
            <h1 class="text-2xl font-bold text-green-800 mb-6">Edit User #<?= (int)$user['id'] ?></h1>

            <?php if ($error): ?>
                <div class="bg-red-100 border border-red-400 text-red-700 px-4 py-3 rounded mb-4"><?= htmlspecialchars($error) ?></div>
            <?php endif; ?>
            <?php if ($success): ?>
                <div class="bg-green-100 border border-green-400 text-green-700 px-4 py-3 rounded mb-4"><?= htmlspecialchars($success) ?></div>
            <?php endif; ?>

            <div class="bg-white p-8 rounded-xl shadow">
                <form method="POST">
                    <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
                        <div>
                            <label class="block text-gray-700 font-bold mb-2">First Name</label>
                            <input type="text" name="fname" value="<?= htmlspecialchars($user['fname']) ?>" class="w-full px-4 py-2 border rounded" required>
                        </div>
                        <div>
                            <label class="block text-gray-700 font-bold mb-2">Last Name</label>
                            <input type="text" name="lname" value="<?= htmlspecialchars($user['lname']) ?>" class="w-full px-4 py-2 border rounded" required>
                        </div>
                        <div>
                            <label class="block text-gray-700 font-bold mb-2">Email</label>
                            <input type="email" name="email" value="<?= htmlspecialchars($user['email'] ?? '') ?>" class="w-full px-4 py-2 border rounded">
                            <?php if ($isGoogle): ?>
                                <p class="text-amber-600 text-xs mt-1">Signed in with Google. Changing this will not change the Google account they sign in with.</p>
                            <?php endif; ?>
                        </div>
                        <div>
                            <label class="block text-gray-700 font-bold mb-2">Mobile</label>
                            <input type="text" name="mobile" value="<?= htmlspecialchars($user['mobile'] ?? '') ?>" class="w-full px-4 py-2 border rounded" placeholder="e.g. 0758589867">
                            <p class="text-gray-400 text-xs mt-1">Optional. Must be unique across accounts.</p>
                        </div>
                        <div>
                            <label class="block text-gray-700 font-bold mb-2">Delivery Area</label>
                            <input type="text" name="area" value="<?= htmlspecialchars($user['area']) ?>" class="w-full px-4 py-2 border rounded" placeholder="e.g. Kyaliwajala">
                        </div>
                        <div>
                            <label class="block text-gray-700 font-bold mb-2">Delivery Address</label>
                            <input type="text" name="address" value="<?= htmlspecialchars($user['address']) ?>" class="w-full px-4 py-2 border rounded">
                        </div>
                        <div>
                            <label class="block text-gray-700 font-bold mb-2">Role</label>
                            <select name="current_role" class="w-full px-4 py-2 border rounded">
                                <?php foreach ($ROLES as $r): ?>
                                    <option value="<?= $r ?>" <?= ($user['current_role'] ?? 'user') === $r ? 'selected' : '' ?>><?= ucfirst($r) ?></option>
                                <?php endforeach; ?>
                            </select>
                            <p class="text-gray-400 text-xs mt-1">Fixed by the account type &mdash; saving grants this role in the app.</p>
                        </div>
                        <div>
                            <label class="block text-gray-700 font-bold mb-2">Account Type</label>
                            <div class="w-full px-4 py-2 border rounded bg-gray-50 text-gray-700"><?= htmlspecialchars(ucfirst($accountType)) ?></div>
                            <p class="text-gray-400 text-xs mt-1">
                                Set at registration by whichever app was used, and not editable here.
                                A <?= htmlspecialchars($accountType) ?> account cannot sign in to the other apps.
                            </p>
                        </div>
                        <div>
                            <label class="block text-gray-700 font-bold mb-2">Loyalty Points</label>
                            <input type="number" name="loyalty_points" min="0" value="<?= (int)$user['loyalty_points'] ?>" class="w-full px-4 py-2 border rounded">
                        </div>
                        <div class="md:col-span-2">
                            <label class="block text-gray-700 font-bold mb-2">
                                <?= $hasPassword ? 'New Password' : 'Set a Password' ?>
                            </label>
                            <input type="password" name="new_password" class="w-full px-4 py-2 border rounded" placeholder="<?= $hasPassword ? 'Leave blank to keep current' : 'This account has no password yet' ?>">
                            <p class="text-gray-400 text-xs mt-1">
                                At least 6 characters. Blank leaves it unchanged.
                                <?php if (!$hasPassword): ?>
                                    This account currently signs in with Google only — setting a password adds email sign-in as well.
                                <?php endif; ?>
                            </p>
                        </div>
                    </div>

                    <div class="mt-6 pt-6 border-t text-sm text-gray-500 grid grid-cols-2 md:grid-cols-4 gap-4">
                        <div>
                            <span class="block text-gray-400">Roles held</span>
                            <?php if ($heldRoles): ?>
                                <?php foreach ($heldRoles as $hr): ?>
                                    <span class="inline-block px-2 py-0.5 mr-1 mb-1 text-xs rounded-full <?= $hr['status'] === 'active' ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-600' ?>"><?= htmlspecialchars($hr['role']) ?></span>
                                <?php endforeach; ?>
                            <?php else: ?>
                                <span class="text-gray-400">None recorded</span>
                            <?php endif; ?>
                        </div>
                        <div><span class="block text-gray-400">Sign-in</span><?= $isGoogle ? 'Google' : 'Email' ?><?= $hasPassword ? ' + password' : '' ?></div>
                        <div><span class="block text-gray-400">Logins</span><?= (int)$user['login_count'] ?></div>
                        <div><span class="block text-gray-400">Last login</span><?= $user['last_login'] ? htmlspecialchars($user['last_login']) : '&mdash;' ?></div>
                    </div>

                    <div class="mt-6 flex gap-4">
                        <button type="submit" class="bg-green-600 hover:bg-green-700 text-white px-6 py-2 rounded">Save Changes</button>
                        <a href="users.php" class="bg-gray-300 hover:bg-gray-400 px-6 py-2 rounded">Back to Users</a>
                    </div>
                </form>
            </div>
        </div>
    </div>
</div>
</body>
</html>