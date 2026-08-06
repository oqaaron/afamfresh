<?php
session_start();
require_once '../admin/includes/config.php';

if (!isset($_SESSION['admin_logged_in']) || $_SESSION['admin_logged_in'] !== true) {
    header('Location: login.php');
    exit;
}

$formError = '';

// Handle add / status / delete.
//
// The old inline `edit` action lived here and was removed: its form posted only
// name, phone and vehicle_type while the UPDATE also wrote `email`, so every
// save blanked the rider's email and — because email is UNIQUE — the next save
// collided and threw an uncaught PDOException. Full editing now lives in
// edit-rider.php, which handles all fields and NULLs blank emails.
if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $action = $_POST['action'] ?? '';

    if ($action === 'add') {
        $name = trim($_POST['name'] ?? '');
        $phone = trim($_POST['phone'] ?? '');
        $email = trim($_POST['email'] ?? '');
        $vehicle = trim($_POST['vehicle_type'] ?? '');

        // `?? '123456'` only fires when the key is MISSING, and the field is
        // always submitted — so an empty box used to hash an empty string and
        // create a rider nobody could sign in as.
        $rawPassword = trim($_POST['password'] ?? '');
        if ($rawPassword === '') {
            $rawPassword = '123456';
        }

        if ($name === '' || $phone === '') {
            $formError = 'Name and phone are required.';
        } elseif ($email !== '' && !filter_var($email, FILTER_VALIDATE_EMAIL)) {
            $formError = 'That email address does not look valid.';
        } else {
            try {
                $stmt = $dbh->prepare("INSERT INTO riders (name, phone, email, vehicle_type, password) VALUES (?, ?, ?, ?, ?)");
                $stmt->execute([
                    $name,
                    $phone,
                    // NULL, not '': a second rider with a blank email would
                    // otherwise collide on the UNIQUE idx_email.
                    $email === '' ? null : $email,
                    $vehicle === '' ? null : $vehicle,
                    password_hash($rawPassword, PASSWORD_DEFAULT),
                ]);
                header('Location: riders.php?added=1');
                exit;
            } catch (PDOException $e) {
                if ($e->getCode() === '23000') {
                    $formError = 'Another rider already uses that phone number or email.';
                } else {
                    error_log('rider insert failed: ' . $e->getMessage());
                    $formError = 'Could not add the rider. Please try again.';
                }
            }
        }
    }

    if ($action === 'set_status') {
        $id = intval($_POST['id'] ?? 0);
        $status = $_POST['status'] ?? '';
        // Validated against the enum rather than trusted: an unexpected value
        // would be coerced to '' by MySQL, leaving the rider in neither state.
        if ($id && in_array($status, ['online', 'offline'], true)) {
            $stmt = $dbh->prepare("UPDATE riders SET status = ? WHERE id = ?");
            $stmt->execute([$status, $id]);
        }
        header('Location: riders.php?updated=1');
        exit;
    }

    if ($action === 'delete') {
        $id = intval($_POST['id'] ?? 0);
        $stmt = $dbh->prepare("DELETE FROM riders WHERE id = ?");
        $stmt->execute([$id]);
        header('Location: riders.php?deleted=1');
        exit;
    }
}

$riders = $dbh->query("SELECT * FROM riders ORDER BY id DESC")->fetchAll(PDO::FETCH_ASSOC);
?>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>AfamFresh – Riders</title>
    <script src="https://cdn.tailwindcss.com"></script>
    <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.5.0/css/all.min.css">
</head>
<body class="bg-gray-100">
<!-- Sidebar (same as dashboard) -->
<div class="flex h-screen">
    <div class="w-64 bg-green-800 text-white flex flex-col">
        <div class="p-6 text-xl font-bold border-b border-green-700">AfamFresh</div>
        <nav class="flex-1 p-4 space-y-2">
            <a href="dashboard.php" class="block py-2 px-4 rounded hover:bg-green-700"><i class="fas fa-chart-pie mr-2"></i> Dashboard</a>
            <a href="products.php" class="block py-2 px-4 rounded hover:bg-green-700"><i class="fas fa-box mr-2"></i> Products</a>
            <a href="orders.php" class="block py-2 px-4 rounded hover:bg-green-700"><i class="fas fa-shopping-cart mr-2"></i> Orders</a>
            <a href="users.php" class="block py-2 px-4 rounded hover:bg-green-700"><i class="fas fa-users mr-2"></i> Users</a>
            <a href="riders.php" class="block py-2 px-4 rounded bg-green-700"><i class="fas fa-motorcycle mr-2"></i> Riders</a>
            <a href="logout.php" class="block py-2 px-4 rounded hover:bg-red-600 mt-8"><i class="fas fa-sign-out-alt mr-2"></i> Logout</a>
        </nav>
    </div>

    <div class="flex-1 overflow-y-auto p-6">
        <h1 class="text-2xl font-bold text-green-800 mb-4">Manage Riders</h1>

        <?php if (isset($_GET['added'])): ?>
            <div class="bg-green-100 border border-green-400 text-green-700 px-4 py-3 rounded mb-4">Rider added successfully.</div>
        <?php endif; ?>
        <?php if (isset($_GET['updated'])): ?>
            <div class="bg-green-100 border border-green-400 text-green-700 px-4 py-3 rounded mb-4">Rider updated.</div>
        <?php endif; ?>
        <?php if (isset($_GET['deleted'])): ?>
            <div class="bg-red-100 border border-red-400 text-red-700 px-4 py-3 rounded mb-4">Rider deleted.</div>
        <?php endif; ?>

        <?php if ($formError): ?>
            <div class="bg-red-100 border border-red-400 text-red-700 px-4 py-3 rounded mb-4"><?= htmlspecialchars($formError) ?></div>
        <?php endif; ?>

        <!-- Add Rider Form -->
        <div class="bg-white p-6 rounded-xl shadow mb-8">
            <h2 class="text-lg font-bold mb-4">Add New Rider</h2>
            <form method="POST" class="grid grid-cols-1 md:grid-cols-4 gap-4">
                <input type="hidden" name="action" value="add">
                <input type="text" name="name" placeholder="Full Name" required class="px-4 py-2 border rounded">
                <input type="text" name="phone" placeholder="Phone" required class="px-4 py-2 border rounded">
                <input type="email" name="email" placeholder="Email" class="px-4 py-2 border rounded">
                <input type="text" name="vehicle_type" placeholder="Vehicle Type (e.g., Motorcycle)" class="px-4 py-2 border rounded">
                <input type="password" name="password" placeholder="Password (default: 123456)" class="px-4 py-2 border rounded">
                <button type="submit" class="bg-green-600 text-white px-4 py-2 rounded hover:bg-green-700">Add Rider</button>
            </form>
        </div>

        <!-- Riders Table -->
        <div class="bg-white rounded-xl shadow overflow-x-auto">
            <table class="w-full">
                <thead class="bg-gray-50 border-b">
                    <tr>
                        <th class="px-4 py-3 text-left">ID</th>
                        <th class="px-4 py-3 text-left">Name</th>
                        <th class="px-4 py-3 text-left">Phone</th>
                        <th class="px-4 py-3 text-left">Email</th>
                        <th class="px-4 py-3 text-left">Vehicle</th>
                        <th class="px-4 py-3 text-left">Rating</th>
                        <th class="px-4 py-3 text-left">Status</th>
                        <th class="px-4 py-3 text-left">Actions</th>
                    </tr>
                </thead>
                <tbody>
                    <?php if (empty($riders)): ?>
                        <tr><td colspan="8" class="px-4 py-6 text-center text-gray-500">No riders registered.</td></tr>
                    <?php else: ?>
                        <?php foreach ($riders as $rider): ?>
                        <tr class="border-b hover:bg-gray-50">
                            <td class="px-4 py-3"><?= $rider['id'] ?></td>
                            <td class="px-4 py-3 font-medium"><?= htmlspecialchars($rider['name']) ?></td>
                            <td class="px-4 py-3"><?= htmlspecialchars($rider['phone']) ?></td>
                            <td class="px-4 py-3"><?= ($rider['email'] ?? '') !== '' ? htmlspecialchars($rider['email']) : '<span class="text-gray-400">&mdash;</span>' ?></td>
                            <td class="px-4 py-3"><?= ($rider['vehicle_type'] ?? '') !== '' ? htmlspecialchars($rider['vehicle_type']) : '<span class="text-gray-400">&mdash;</span>' ?></td>
                            <td class="px-4 py-3 text-sm">
                                <?php if ((int)$rider['total_ratings'] > 0): ?>
                                    <?= number_format((float)$rider['avg_rating'], 2) ?> &#9733;
                                    <span class="text-gray-400">(<?= (int)$rider['total_ratings'] ?>)</span>
                                <?php else: ?>
                                    <span class="text-gray-400">No ratings</span>
                                <?php endif; ?>
                            </td>
                            <td class="px-4 py-3">
                                <!-- Quick on/off duty toggle, same shape as the role dropdown
                                     in users.php. Full editing is on edit-rider.php. -->
                                <form method="POST" class="flex items-center gap-1">
                                    <input type="hidden" name="action" value="set_status">
                                    <input type="hidden" name="id" value="<?= $rider['id'] ?>">
                                    <select name="status" class="border rounded px-2 py-1 text-sm <?= $rider['status'] === 'online' ? 'bg-green-50 text-green-700' : 'bg-gray-50 text-gray-700' ?>">
                                        <option value="online"  <?= $rider['status'] === 'online'  ? 'selected' : '' ?>>Online</option>
                                        <option value="offline" <?= $rider['status'] === 'offline' ? 'selected' : '' ?>>Offline</option>
                                    </select>
                                    <button type="submit" class="bg-blue-600 text-white px-2 py-1 rounded text-sm hover:bg-blue-700">Set</button>
                                </form>
                            </td>
                            <td class="px-4 py-3">
                                <a href="edit-rider.php?id=<?= $rider['id'] ?>" class="text-blue-600 hover:text-blue-800 text-sm mr-3"><i class="fas fa-pen"></i> Edit</a>
                                <form method="POST" class="inline-block" onsubmit="return confirm('Delete this rider?')">
                                    <input type="hidden" name="action" value="delete">
                                    <input type="hidden" name="id" value="<?= $rider['id'] ?>">
                                    <button type="submit" class="text-red-600 hover:text-red-800 text-sm ml-2"><i class="fas fa-trash"></i></button>
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