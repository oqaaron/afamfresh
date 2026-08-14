<?php
session_start();
require_once '../admin/includes/config.php';

if (!isset($_SESSION['admin_logged_in']) || $_SESSION['admin_logged_in'] !== true) {
    header('Location: login.php');
    exit;
}

// Handle order status update or rider assignment
if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $orderId = intval($_POST['order_id'] ?? 0);
    $action = $_POST['action'] ?? '';

    if ($orderId && $action === 'update_status') {
        $status = trim($_POST['status'] ?? '');
        $stmt = $dbh->prepare("UPDATE orders SET status = ? WHERE orderid = ?");
        $stmt->execute([$status, $orderId]);
        header('Location: orders.php?updated=1');
        exit;
    }

    if ($orderId && $action === 'assign_rider') {
        $riderId = intval($_POST['rider_id'] ?? 0);
        if ($riderId) {
            try {
                $dbh->beginTransaction();

                // rider_assignments is the source of truth — it is what
                // api/rider.php reads, and unlike a name string it survives a
                // rider being renamed and distinguishes two riders who share a
                // name. The table already existed with correct foreign keys but
                // nothing had ever written to it.
                //
                // Scoped to source='order'. rider_assignments now also holds
                // surplus deliveries, and the two id spaces overlap — without
                // this, assigning a rider to shop order 41 would find the
                // SURPLUS assignment for order 41 and reassign that instead,
                // silently moving a different customer's delivery to a
                // different rider.
                $existing = $dbh->prepare(
                    "SELECT id FROM rider_assignments WHERE order_id = ? AND source = 'order' LIMIT 1"
                );
                $existing->execute([$orderId]);
                $assignmentId = $existing->fetchColumn();

                require_once __DIR__ . '/../includes/rider_dispatch.php';

                if ($assignmentId) {
                    // Reassignment: move the order to the new rider and put the
                    // assignment back to the start of the flow.
                    $dbh->prepare(
                        "UPDATE rider_assignments
                            SET rider_id = ?, assigned_at = NOW(), status = 'assigned',
                                delivered_at = NULL, completed_at = NULL
                          WHERE id = ?"
                    )->execute([$riderId, $assignmentId]);
                } else {
                    $dbh->prepare(
                        "INSERT INTO rider_assignments (order_id, source, rider_id, status)
                         VALUES (?, 'order', ?, 'assigned')"
                    )->execute([$orderId, $riderId]);
                    $assignmentId = (int)$dbh->lastInsertId();
                }

                // Fetched now rather than left for the picked_up transition, so
                // the rider's Navigate screen and the customer's tracking map
                // both have a route to draw as soon as possible. Self-guarding
                // — a no-op if this assignment already has one, which covers
                // both a fresh dispatch and reassigning an order that already
                // had its route cached.
                cacheAssignmentRoute($dbh, 'order', $orderId, (int)$assignmentId);

                // delivery_person is kept in step because the customer's order
                // screen and this page still display the name.
                $dbh->prepare(
                    "UPDATE orders
                        SET delivery_person = (SELECT name FROM riders WHERE id = ?),
                            delivery_assigned_at = NOW()
                      WHERE orderid = ?"
                )->execute([$riderId, $orderId]);

                $dbh->commit();

                // Told after commit, never inside the transaction: a
                // notification failure must not roll back a real assignment.
                // Same shape as admin/surplus-orders.php's own rider-assigned
                // notification, which shop orders never had.
                try {
                    require_once __DIR__ . '/../includes/notifications.php';
                    $riderUser = $dbh->prepare("SELECT user_id FROM riders WHERE id = ?");
                    $riderUser->execute([$riderId]);
                    $riderUserId = $riderUser->fetchColumn();
                    if ($riderUserId) {
                        addNotification(
                            (int)$riderUserId,
                            'New delivery assigned',
                            "You've been assigned order #{$orderId}. Head to the warehouse to collect it.",
                            'order', null, ['push'],
                            ['order_id' => (string)$orderId, 'source' => 'order']
                        );
                    }
                } catch (Throwable $e) {
                    error_log('assign_rider notification failed: ' . $e->getMessage());
                }
            } catch (PDOException $e) {
                if ($dbh->inTransaction()) $dbh->rollBack();
                error_log('assign_rider failed: ' . $e->getMessage());
                header('Location: orders.php?assign_failed=1');
                exit;
            }
        }
        header('Location: orders.php?updated=1');
        exit;
    }
}

// Fetch orders with optional search
$search = isset($_GET['search']) ? trim($_GET['search']) : '';
$sql = "SELECT o.*, u.email as user_email 
        FROM orders o 
        LEFT JOIN users u ON o.user_id = u.id";
$params = [];
if (!empty($search)) {
    $sql .= " WHERE o.orderid LIKE ? OR o.fname LIKE ? OR o.lname LIKE ? OR o.mobile LIKE ?";
    $like = "%$search%";
    $params = [$like, $like, $like, $like];
}
$sql .= " ORDER BY o.ordertime DESC";
$stmt = $dbh->prepare($sql);
$stmt->execute($params);
$orders = $stmt->fetchAll(PDO::FETCH_ASSOC);

// Fetch riders for assignment dropdown
$riders = $dbh->query("SELECT id, name FROM riders")->fetchAll(PDO::FETCH_ASSOC);
?>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>AfamFresh – Orders</title>
    <script src="https://cdn.tailwindcss.com"></script>
    <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.5.0/css/all.min.css">
</head>
<body class="bg-gray-100">
<!-- Sidebar (same as dashboard) – omitted for brevity, include same as dashboard -->
<div class="flex h-screen">
    <?php include __DIR__ . "/includes/nav.php"; ?>

    <div class="flex-1 overflow-y-auto p-6">
        <h1 class="text-2xl font-bold text-green-800 mb-4">Manage Orders</h1>

        <?php if (isset($_GET['updated'])): ?>
            <div class="bg-green-100 border border-green-400 text-green-700 px-4 py-3 rounded mb-4">Order updated successfully.</div>
        <?php endif; ?>
        <?php if (isset($_GET['assign_failed'])): ?>
            <div class="bg-red-100 border border-red-400 text-red-700 px-4 py-3 rounded mb-4">Could not assign that rider. Nothing was changed.</div>
        <?php endif; ?>

        <!-- Search -->
        <form method="GET" class="flex gap-4 mb-4">
            <input type="text" name="search" placeholder="Search by order ID, name, or phone..." value="<?= htmlspecialchars($search) ?>" class="flex-1 px-4 py-2 border rounded">
            <button type="submit" class="bg-blue-600 hover:bg-blue-700 text-white px-4 py-2 rounded">Search</button>
            <a href="orders.php" class="bg-gray-300 hover:bg-gray-400 px-4 py-2 rounded">Reset</a>
        </form>

        <!-- Orders Table -->
        <div class="bg-white rounded-xl shadow overflow-x-auto">
            <table class="w-full">
                <thead class="bg-gray-50 border-b">
                    <tr>
                        <th class="px-4 py-3 text-left">Order ID</th>
                        <th class="px-4 py-3 text-left">Customer</th>
                        <th class="px-4 py-3 text-left">Mobile</th>
                        <th class="px-4 py-3 text-left">Amount</th>
                        <th class="px-4 py-3 text-left">Status</th>
                        <th class="px-4 py-3 text-left">Rider</th>
                        <th class="px-4 py-3 text-left">Actions</th>
                    </tr>
                </thead>
                <tbody>
                    <?php if (empty($orders)): ?>
                        <tr><td colspan="7" class="px-4 py-6 text-center text-gray-500">No orders found.</td></tr>
                    <?php else: ?>
                        <?php foreach ($orders as $order): ?>
                        <tr class="border-b hover:bg-gray-50">
                            <td class="px-4 py-3 font-medium">#<?= $order['orderid'] ?></td>
                            <td class="px-4 py-3"><?= htmlspecialchars($order['fname'] . ' ' . $order['lname']) ?></td>
                            <td class="px-4 py-3"><?= htmlspecialchars($order['mobile']) ?></td>
                            <td class="px-4 py-3">UGX <?= number_format($order['total_amount']) ?></td>
                            <td class="px-4 py-3">
                                <form method="POST" class="flex items-center gap-2">
                                    <input type="hidden" name="order_id" value="<?= $order['orderid'] ?>">
                                    <input type="hidden" name="action" value="update_status">
                                    <select name="status" class="border rounded px-2 py-1 text-sm">
                                        <option value="Pending" <?= $order['status'] === 'Pending' ? 'selected' : '' ?>>Pending</option>
                                        <option value="Processing" <?= $order['status'] === 'Processing' ? 'selected' : '' ?>>Processing</option>
                                        <option value="Shipped" <?= $order['status'] === 'Shipped' ? 'selected' : '' ?>>Shipped</option>
                                        <option value="Delivered" <?= $order['status'] === 'Delivered' ? 'selected' : '' ?>>Delivered</option>
                                        <option value="Cancelled" <?= $order['status'] === 'Cancelled' ? 'selected' : '' ?>>Cancelled</option>
                                    </select>
                                    <button type="submit" class="bg-blue-600 text-white px-2 py-1 rounded text-sm hover:bg-blue-700">Update</button>
                                </form>
                            </td>
                            <td class="px-4 py-3">
                                <form method="POST" class="flex items-center gap-2">
                                    <input type="hidden" name="order_id" value="<?= $order['orderid'] ?>">
                                    <input type="hidden" name="action" value="assign_rider">
                                    <select name="rider_id" class="border rounded px-2 py-1 text-sm">
                                        <option value="">Unassigned</option>
                                        <?php foreach ($riders as $rider): ?>
                                            <option value="<?= $rider['id'] ?>" <?= $order['delivery_person'] == $rider['name'] ? 'selected' : '' ?>><?= htmlspecialchars($rider['name']) ?></option>
                                        <?php endforeach; ?>
                                    </select>
                                    <button type="submit" class="bg-green-600 text-white px-2 py-1 rounded text-sm hover:bg-green-700">Assign</button>
                                </form>
                            </td>
                            <td class="px-4 py-3">
                                <a href="order-detail.php?id=<?= $order['orderid'] ?>" class="text-blue-600 hover:text-blue-800"><i class="fas fa-eye"></i></a>
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