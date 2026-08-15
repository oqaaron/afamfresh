<?php
// =============================================================
// admin/edit-rider.php
//
// Full single-record edit for a rider. Replaces the inline row form
// that used to live in riders.php, which submitted only name/phone/
// vehicle_type while the UPDATE also wrote `email` — so every save
// blanked the rider's email, and because `email` is UNIQUE, the
// second such save collided and threw an uncaught PDOException.
//
// Blank email is written as NULL, never ''. Two NULLs coexist happily
// under a UNIQUE key; two empty strings do not. Same trap as
// users.mobile.
// =============================================================

session_start();
require_once 'includes/config.php';
require_once __DIR__ . '/../includes/csrf.php';

// === true, not a bare isset(): isset() is satisfied by a value of
// literal false, which would let a logged-out session through.
if (!isset($_SESSION['admin_logged_in']) || $_SESSION['admin_logged_in'] !== true) {
    header('Location: login.php');
    exit;
}

$id = intval($_GET['id'] ?? 0);
if (!$id) {
    header('Location: riders.php');
    exit;
}

$stmt = $dbh->prepare("SELECT * FROM riders WHERE id = ?");
$stmt->execute([$id]);
$rider = $stmt->fetch(PDO::FETCH_ASSOC);
if (!$rider) {
    header('Location: riders.php');
    exit;
}

$error = '';
$success = '';

$STATUSES = ['online', 'offline'];

/**
 * Accounts that can be linked to this rider: every user not already claimed by
 * a DIFFERENT rider. riders.user_id is UNIQUE, so offering an account that is
 * already taken would only produce a duplicate-key error on save.
 */
function linkableUsers($dbh, $riderId) {
    $stmt = $dbh->prepare(
        "SELECT u.id, u.fname, u.lname, u.email
           FROM users u
           LEFT JOIN riders r ON r.user_id = u.id AND r.id <> ?
          WHERE r.id IS NULL
          ORDER BY u.fname, u.lname"
    );
    $stmt->execute([$riderId]);
    return $stmt->fetchAll(PDO::FETCH_ASSOC);
}

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    verifyCsrf();
    $name    = trim($_POST['name'] ?? '');
    $phone   = trim($_POST['phone'] ?? '');
    $email   = trim($_POST['email'] ?? '');
    $vehicle = trim($_POST['vehicle_type'] ?? '');
    $status  = trim($_POST['status'] ?? '');
    $userId  = trim($_POST['user_id'] ?? '');
    $newPassword = (string)($_POST['new_password'] ?? '');

    if ($name === '' || $phone === '') {
        $error = 'Name and phone are required.';
    } elseif ($email !== '' && !filter_var($email, FILTER_VALIDATE_EMAIL)) {
        $error = 'That email address does not look valid.';
    } elseif (!in_array($status, $STATUSES, true)) {
        // Checked rather than trusted: an out-of-range value would be
        // coerced to '' by MySQL, silently leaving the rider in neither state.
        $error = 'Status must be either online or offline.';
    } elseif ($newPassword !== '' && strlen($newPassword) < 6) {
        $error = 'A new password must be at least 6 characters.';
    } else {
        // NULL, not '', so riders without an email do not collide on idx_email.
        $emailValue = ($email === '') ? null : $email;
        $vehicleValue = ($vehicle === '') ? null : $vehicle;
        // user_id is UNIQUE and is what lets this rider sign in to the app:
        // api/rider.php resolves $_SESSION['user_id'] -> riders.user_id. Blank
        // means "not linked", which must be NULL rather than 0 or ''.
        $userIdValue = ($userId === '') ? null : (int)$userId;

        try {
            if ($newPassword !== '') {
                $sql = "UPDATE riders SET name=?, phone=?, email=?, vehicle_type=?, status=?, user_id=?, password=? WHERE id=?";
                $params = [$name, $phone, $emailValue, $vehicleValue, $status, $userIdValue,
                           password_hash($newPassword, PASSWORD_DEFAULT), $id];
            } else {
                // password left out of the statement entirely, so a blank box
                // cannot overwrite the existing hash.
                $sql = "UPDATE riders SET name=?, phone=?, email=?, vehicle_type=?, status=?, user_id=? WHERE id=?";
                $params = [$name, $phone, $emailValue, $vehicleValue, $status, $userIdValue, $id];
            }
            $dbh->prepare($sql)->execute($params);

            $success = 'Rider updated successfully!';
            if ($newPassword !== '') {
                $success .= ' Password changed.';
            }

            $stmt = $dbh->prepare("SELECT * FROM riders WHERE id = ?");
            $stmt->execute([$id]);
            $rider = $stmt->fetch(PDO::FETCH_ASSOC);
        } catch (PDOException $e) {
            // Caught rather than pre-checked: this also closes the race between
            // a "is it taken?" query and the write. Uncaught, it printed a raw
            // stack trace because display_errors is on outside production.
            if ($e->getCode() === '23000') {
                $error = 'Another rider already uses that phone number, email, or app account.';
            } else {
                error_log('edit-rider update failed: ' . $e->getMessage());
                $error = 'Could not save the rider. Please try again.';
            }
        }
    }
}
?>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>AfamFresh – Edit Rider</title>
    <script src="https://cdn.tailwindcss.com"></script>
    <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.5.0/css/all.min.css">
</head>
<body class="bg-gray-100">
<div class="flex h-screen">
    <?php include __DIR__ . "/includes/nav.php"; ?>

    <div class="flex-1 overflow-y-auto p-6">
        <div class="max-w-3xl">
            <h1 class="text-2xl font-bold text-green-800 mb-6">Edit Rider #<?= (int)$rider['id'] ?></h1>

            <?php if ($error): ?>
                <div class="bg-red-100 border border-red-400 text-red-700 px-4 py-3 rounded mb-4"><?= htmlspecialchars($error) ?></div>
            <?php endif; ?>
            <?php if ($success): ?>
                <div class="bg-green-100 border border-green-400 text-green-700 px-4 py-3 rounded mb-4"><?= htmlspecialchars($success) ?></div>
            <?php endif; ?>

            <div class="bg-white p-8 rounded-xl shadow">
                <form method="POST">
                    <?= csrfField() ?>
                    <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
                        <div>
                            <label class="block text-gray-700 font-bold mb-2">Full Name</label>
                            <input type="text" name="name" value="<?= htmlspecialchars($rider['name']) ?>" class="w-full px-4 py-2 border rounded" required>
                        </div>
                        <div>
                            <label class="block text-gray-700 font-bold mb-2">Phone</label>
                            <input type="text" name="phone" value="<?= htmlspecialchars($rider['phone']) ?>" class="w-full px-4 py-2 border rounded" required>
                        </div>
                        <div>
                            <label class="block text-gray-700 font-bold mb-2">Email</label>
                            <input type="email" name="email" value="<?= htmlspecialchars($rider['email'] ?? '') ?>" class="w-full px-4 py-2 border rounded">
                            <p class="text-gray-400 text-xs mt-1">Optional. Leave blank if the rider has none.</p>
                        </div>
                        <div>
                            <label class="block text-gray-700 font-bold mb-2">Vehicle Type</label>
                            <input type="text" name="vehicle_type" value="<?= htmlspecialchars($rider['vehicle_type'] ?? '') ?>" class="w-full px-4 py-2 border rounded" placeholder="e.g. Motorcycle">
                        </div>
                        <div>
                            <label class="block text-gray-700 font-bold mb-2">Status</label>
                            <select name="status" class="w-full px-4 py-2 border rounded">
                                <?php foreach ($STATUSES as $s): ?>
                                    <option value="<?= $s ?>" <?= $rider['status'] === $s ? 'selected' : '' ?>><?= ucfirst($s) ?></option>
                                <?php endforeach; ?>
                            </select>
                            <p class="text-gray-400 text-xs mt-1">Whether the rider is currently on duty.</p>
                        </div>
                        <div class="md:col-span-2">
                            <label class="block text-gray-700 font-bold mb-2">Linked App Account</label>
                            <select name="user_id" class="w-full px-4 py-2 border rounded">
                                <option value="">&mdash; Not linked &mdash;</option>
                                <?php foreach (linkableUsers($dbh, $id) as $u): ?>
                                    <option value="<?= (int)$u['id'] ?>" <?= (int)($rider['user_id'] ?? 0) === (int)$u['id'] ? 'selected' : '' ?>>
                                        #<?= (int)$u['id'] ?> &middot; <?= htmlspecialchars(trim($u['fname'] . ' ' . $u['lname'])) ?><?= $u['email'] ? ' (' . htmlspecialchars($u['email']) . ')' : '' ?>
                                    </option>
                                <?php endforeach; ?>
                            </select>
                            <?php
                            // A dangling user_id is a real state: the column has no
                            // foreign key, so deleting a user leaves the rider pointing
                            // at an id that no longer exists. Both live riders were in
                            // exactly this state (76 and 87) when this page was built.
                            $linkedExists = false;
                            if (!empty($rider['user_id'])) {
                                $chk = $dbh->prepare("SELECT 1 FROM users WHERE id = ?");
                                $chk->execute([$rider['user_id']]);
                                $linkedExists = (bool)$chk->fetchColumn();
                            }
                            ?>
                            <?php if (!empty($rider['user_id']) && !$linkedExists): ?>
                                <p class="text-red-600 text-xs mt-1">
                                    Linked to account #<?= (int)$rider['user_id'] ?>, which <strong>no longer exists</strong>.
                                    The rider cannot sign in. Pick a real account, or choose &ldquo;Not linked&rdquo; to clear it.
                                </p>
                            <?php elseif (empty($rider['user_id'])): ?>
                                <p class="text-amber-600 text-xs mt-1">
                                    Not linked &mdash; this rider <strong>cannot sign in to the app</strong>.
                                    Link an account, then set that account's role to Rider on the Users page.
                                </p>
                            <?php else: ?>
                                <p class="text-gray-400 text-xs mt-1">The rider signs in with this account and switches to the Rider role.</p>
                            <?php endif; ?>
                        </div>
                        <div>
                            <label class="block text-gray-700 font-bold mb-2">New Password</label>
                            <input type="password" name="new_password" class="w-full px-4 py-2 border rounded" placeholder="Leave blank to keep current">
                            <p class="text-gray-400 text-xs mt-1">At least 6 characters. Blank leaves the password unchanged.</p>
                        </div>
                    </div>

                    <div class="mt-6 pt-6 border-t text-sm text-gray-500 grid grid-cols-2 md:grid-cols-4 gap-4">
                        <div><span class="block text-gray-400">Rating</span><?= number_format((float)$rider['avg_rating'], 2) ?> ★</div>
                        <div><span class="block text-gray-400">Ratings</span><?= (int)$rider['total_ratings'] ?></div>
                        <div><span class="block text-gray-400">Registered</span><?= htmlspecialchars($rider['created_at']) ?></div>
                        <div><span class="block text-gray-400">Last location</span><?= $rider['last_location_update'] ? htmlspecialchars($rider['last_location_update']) : '—' ?></div>
                    </div>

                    <div class="mt-6 flex gap-4">
                        <button type="submit" class="bg-green-600 hover:bg-green-700 text-white px-6 py-2 rounded">Save Changes</button>
                        <a href="riders.php" class="bg-gray-300 hover:bg-gray-400 px-6 py-2 rounded">Back to Riders</a>
                    </div>
                </form>
            </div>
        </div>
    </div>
</div>
</body>
</html>