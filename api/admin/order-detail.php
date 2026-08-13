<?php
// =============================================================
// admin/order-detail.php
//
// Single-order drill-down for a SHOP order. orders.php has linked its
// eye icon here (order-detail.php?id=<orderid>) since it was written, but
// this file never existed, so every click 404'd.
//
// Pulls together what orders.php only shows a sliver of: line items, the
// payment_events ledger (introduced by the 2026-08-13 payment-method
// migration), the cached route on the rider assignment, and the delivery
// proof photo. The status-update and rider-assign actions are the same
// two orders.php already has, just scoped to one order and redirecting
// back here instead of to the list.
// =============================================================
session_start();
require_once '../admin/includes/config.php';
require_once __DIR__ . '/../includes/storage.php';

if (!isset($_SESSION['admin_logged_in']) || $_SESSION['admin_logged_in'] !== true) {
    header('Location: login.php');
    exit;
}

$orderId = intval($_GET['id'] ?? 0);
if (!$orderId) {
    header('Location: orders.php');
    exit;
}

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $action = $_POST['action'] ?? '';

    if ($action === 'update_status') {
        $status = trim($_POST['status'] ?? '');
        $stmt = $dbh->prepare("UPDATE orders SET status = ? WHERE orderid = ?");
        $stmt->execute([$status, $orderId]);
        header("Location: order-detail.php?id=$orderId&updated=1");
        exit;
    }

    if ($action === 'assign_rider') {
        $riderId = intval($_POST['rider_id'] ?? 0);
        if ($riderId) {
            try {
                $dbh->beginTransaction();

                // Same source='order' scoping as orders.php — the id space is
                // shared with surplus_orders, so an unscoped lookup could
                // reassign a different customer's surplus delivery.
                $existing = $dbh->prepare(
                    "SELECT id FROM rider_assignments WHERE order_id = ? AND source = 'order' LIMIT 1"
                );
                $existing->execute([$orderId]);
                $assignmentId = $existing->fetchColumn();

                require_once __DIR__ . '/../includes/rider_dispatch.php';

                if ($assignmentId) {
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

                cacheAssignmentRoute($dbh, 'order', $orderId, (int)$assignmentId);

                $dbh->prepare(
                    "UPDATE orders
                        SET delivery_person = (SELECT name FROM riders WHERE id = ?),
                            delivery_assigned_at = NOW()
                      WHERE orderid = ?"
                )->execute([$riderId, $orderId]);

                $dbh->commit();
            } catch (PDOException $e) {
                if ($dbh->inTransaction()) $dbh->rollBack();
                error_log('order-detail assign_rider failed: ' . $e->getMessage());
                header("Location: order-detail.php?id=$orderId&assign_failed=1");
                exit;
            }
        }
        header("Location: order-detail.php?id=$orderId&updated=1");
        exit;
    }
}

$stmt = $dbh->prepare(
    "SELECT o.*, u.email AS user_email
       FROM orders o
       LEFT JOIN users u ON o.user_id = u.id
      WHERE o.orderid = ?"
);
$stmt->execute([$orderId]);
$order = $stmt->fetch(PDO::FETCH_ASSOC);

if (!$order) {
    http_response_code(404);
    ?>
    <!DOCTYPE html><html><head><title>Order not found</title><script src="https://cdn.tailwindcss.com"></script></head>
    <body class="bg-gray-100 flex h-screen">
        <?php include __DIR__ . "/includes/nav.php"; ?>
        <div class="flex-1 flex items-center justify-center">
            <div class="text-center">
                <p class="text-xl text-gray-600 mb-4">Order #<?= $orderId ?> does not exist.</p>
                <a href="orders.php" class="text-blue-600 hover:underline">Back to orders</a>
            </div>
        </div>
    </body></html>
    <?php
    exit;
}

$itemsStmt = $dbh->prepare("SELECT * FROM order_items WHERE order_id = ?");
$itemsStmt->execute([$orderId]);
$items = $itemsStmt->fetchAll(PDO::FETCH_ASSOC);

$assignStmt = $dbh->prepare(
    "SELECT ra.*, r.name AS rider_name, r.phone AS rider_phone
       FROM rider_assignments ra
       JOIN riders r ON r.id = ra.rider_id
      WHERE ra.order_id = ? AND ra.source = 'order'
      LIMIT 1"
);
$assignStmt->execute([$orderId]);
$assignment = $assignStmt->fetch(PDO::FETCH_ASSOC);

$eventsStmt = $dbh->prepare(
    "SELECT * FROM payment_events WHERE source = 'order' AND order_id = ? ORDER BY created_at ASC"
);
$eventsStmt->execute([$orderId]);
$paymentEvents = $eventsStmt->fetchAll(PDO::FETCH_ASSOC);

$riders = $dbh->query("SELECT id, name FROM riders")->fetchAll(PDO::FETCH_ASSOC);

$proofUrl = null;
if (!empty($order['delivery_photo']) && storageExists('proof/' . $order['delivery_photo'])) {
    $proofUrl = storageUrl('proof/' . $order['delivery_photo']);
}

function money($v) { return 'UGX ' . number_format((float)$v, 0); }
?>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>AfamFresh – Order #<?= $orderId ?></title>
    <script src="https://cdn.tailwindcss.com"></script>
    <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.5.0/css/all.min.css">
</head>
<body class="bg-gray-100">
<div class="flex h-screen">
    <?php include __DIR__ . "/includes/nav.php"; ?>

    <div class="flex-1 overflow-y-auto p-6">
        <div class="flex items-center justify-between mb-4">
            <h1 class="text-2xl font-bold text-green-800">Order #<?= $orderId ?></h1>
            <a href="orders.php" class="text-blue-600 hover:text-blue-800"><i class="fas fa-arrow-left mr-1"></i>Back to orders</a>
        </div>

        <?php if (isset($_GET['updated'])): ?>
            <div class="bg-green-100 border border-green-400 text-green-700 px-4 py-3 rounded mb-4">Order updated successfully.</div>
        <?php endif; ?>
        <?php if (isset($_GET['assign_failed'])): ?>
            <div class="bg-red-100 border border-red-400 text-red-700 px-4 py-3 rounded mb-4">Could not assign that rider. Nothing was changed.</div>
        <?php endif; ?>

        <div class="grid grid-cols-1 lg:grid-cols-3 gap-6">
            <!-- Left column: customer, items, delivery -->
            <div class="lg:col-span-2 space-y-6">

                <div class="bg-white rounded-xl shadow p-5">
                    <h2 class="font-bold text-gray-800 mb-3">Customer</h2>
                    <div class="grid grid-cols-2 gap-3 text-sm">
                        <div><span class="text-gray-500">Name</span><br><?= htmlspecialchars($order['fname'] . ' ' . $order['lname']) ?></div>
                        <div><span class="text-gray-500">Mobile</span><br><?= htmlspecialchars($order['mobile']) ?></div>
                        <div><span class="text-gray-500">Email</span><br><?= htmlspecialchars($order['user_email'] ?? '—') ?></div>
                        <div><span class="text-gray-500">Area</span><br><?= htmlspecialchars($order['area']) ?></div>
                        <div class="col-span-2"><span class="text-gray-500">Address</span><br><?= htmlspecialchars($order['address']) ?></div>
                    </div>
                </div>

                <div class="bg-white rounded-xl shadow p-5">
                    <h2 class="font-bold text-gray-800 mb-3">Items</h2>
                    <table class="w-full text-sm">
                        <thead class="text-left text-gray-500 border-b">
                            <tr><th class="py-2">Product</th><th class="py-2">Qty</th><th class="py-2">Price</th><th class="py-2 text-right">Total</th></tr>
                        </thead>
                        <tbody>
                        <?php if (empty($items)): ?>
                            <tr><td colspan="4" class="py-4 text-center text-gray-400">No line items recorded.</td></tr>
                        <?php else: foreach ($items as $item): ?>
                            <tr class="border-b last:border-0">
                                <td class="py-2"><?= htmlspecialchars($item['product_name']) ?></td>
                                <td class="py-2"><?= (int)$item['quantity'] ?></td>
                                <td class="py-2"><?= money($item['price']) ?></td>
                                <td class="py-2 text-right"><?= money($item['price'] * $item['quantity']) ?></td>
                            </tr>
                        <?php endforeach; endif; ?>
                        </tbody>
                    </table>
                </div>

                <div class="bg-white rounded-xl shadow p-5">
                    <h2 class="font-bold text-gray-800 mb-3">Delivery</h2>
                    <div class="grid grid-cols-2 gap-3 text-sm mb-4">
                        <div><span class="text-gray-500">Status</span><br><?= htmlspecialchars($order['status']) ?></div>
                        <div><span class="text-gray-500">Placed</span><br><?= htmlspecialchars($order['ordertime']) ?></div>
                        <div><span class="text-gray-500">Scheduled</span><br>
                            <?= $order['scheduled_delivery_date']
                                ? htmlspecialchars($order['scheduled_delivery_date'] . ' ' . ($order['scheduled_delivery_slot'] ?? ''))
                                : '—' ?>
                        </div>
                        <div><span class="text-gray-500">Delivered at</span><br><?= htmlspecialchars($order['delivered_at'] ?? '—') ?></div>
                    </div>

                    <?php if ($assignment): ?>
                        <div class="border-t pt-3 text-sm space-y-1">
                            <div><span class="text-gray-500">Rider</span> — <?= htmlspecialchars($assignment['rider_name']) ?> (<?= htmlspecialchars($assignment['rider_phone']) ?>)</div>
                            <div><span class="text-gray-500">Assignment status</span> — <?= htmlspecialchars($assignment['status']) ?></div>
                            <?php if ($assignment['route_distance_km'] !== null): ?>
                                <div><span class="text-gray-500">Route</span> — <?= number_format((float)$assignment['route_distance_km'], 1) ?> km, ~<?= number_format((float)$assignment['route_duration_min']) ?> min</div>
                            <?php endif; ?>
                        </div>
                    <?php else: ?>
                        <p class="text-sm text-gray-400 border-t pt-3">No rider assigned yet.</p>
                    <?php endif; ?>

                    <?php if ($proofUrl): ?>
                        <div class="border-t pt-3 mt-3">
                            <span class="text-gray-500 text-sm">Proof of delivery</span><br>
                            <a href="<?= htmlspecialchars($proofUrl) ?>" target="_blank">
                                <img src="<?= htmlspecialchars($proofUrl) ?>" class="mt-2 max-h-64 rounded-lg border" alt="Delivery proof">
                            </a>
                        </div>
                    <?php endif; ?>

                    <?php if ($order['customer_rating'] || $order['customer_feedback']): ?>
                        <div class="border-t pt-3 mt-3 text-sm">
                            <span class="text-gray-500">Customer rating</span> — <?= $order['customer_rating'] ? str_repeat('★', (int)$order['customer_rating']) : '—' ?>
                            <?php if ($order['customer_feedback']): ?><p class="text-gray-600 mt-1"><?= htmlspecialchars($order['customer_feedback']) ?></p><?php endif; ?>
                        </div>
                    <?php endif; ?>
                </div>

                <?php if (!empty($paymentEvents)): ?>
                <div class="bg-white rounded-xl shadow p-5">
                    <h2 class="font-bold text-gray-800 mb-3">Payment history</h2>
                    <table class="w-full text-sm">
                        <thead class="text-left text-gray-500 border-b">
                            <tr>
                                <th class="py-2">When</th><th class="py-2">Event</th><th class="py-2">Status</th>
                                <th class="py-2">Method</th><th class="py-2">Amount</th><th class="py-2">Actor</th>
                            </tr>
                        </thead>
                        <tbody>
                        <?php foreach ($paymentEvents as $ev): ?>
                            <tr class="border-b last:border-0">
                                <td class="py-2 whitespace-nowrap"><?= htmlspecialchars($ev['created_at']) ?></td>
                                <td class="py-2"><?= htmlspecialchars($ev['event']) ?></td>
                                <td class="py-2"><?= htmlspecialchars(($ev['from_status'] ?? '—') . ' → ' . ($ev['to_status'] ?? '—')) ?></td>
                                <td class="py-2"><?= htmlspecialchars($ev['method']) ?><?= $ev['channel'] ? ' (' . htmlspecialchars($ev['channel']) . ')' : '' ?></td>
                                <td class="py-2"><?= $ev['amount'] !== null ? money($ev['amount']) : '—' ?></td>
                                <td class="py-2"><?= htmlspecialchars($ev['actor_type']) ?></td>
                            </tr>
                        <?php endforeach; ?>
                        </tbody>
                    </table>
                </div>
                <?php endif; ?>
            </div>

            <!-- Right column: totals, status/rider actions -->
            <div class="space-y-6">
                <div class="bg-white rounded-xl shadow p-5">
                    <h2 class="font-bold text-gray-800 mb-3">Payment</h2>
                    <div class="text-sm space-y-1">
                        <div class="flex justify-between"><span class="text-gray-500">Payment status</span><span><?= htmlspecialchars($order['payment_status']) ?></span></div>
                        <div class="flex justify-between"><span class="text-gray-500">Method</span><span><?= htmlspecialchars($order['payment_method'] ?? 'unknown') ?></span></div>
                        <?php if (!empty($order['payment_channel'])): ?>
                        <div class="flex justify-between"><span class="text-gray-500">Channel</span><span><?= htmlspecialchars($order['payment_channel']) ?></span></div>
                        <?php endif; ?>
                        <?php if (!empty($order['cash_collected_by'])): ?>
                        <div class="flex justify-between"><span class="text-gray-500">Cash collected</span><span><?= htmlspecialchars($order['cash_collected_at'] ?? '') ?></span></div>
                        <?php endif; ?>
                        <div class="border-t my-2"></div>
                        <div class="flex justify-between"><span class="text-gray-500">Delivery fee</span><span><?= money($order['delivery_fee']) ?></span></div>
                        <div class="flex justify-between"><span class="text-gray-500">Service fee</span><span><?= money($order['service_fee']) ?></span></div>
                        <div class="flex justify-between"><span class="text-gray-500">Insurance fee</span><span><?= money($order['insurance_fee']) ?></span></div>
                        <div class="flex justify-between"><span class="text-gray-500">Processing fee</span><span><?= money($order['processing_fee']) ?></span></div>
                        <?php if ((float)($order['small_order_surcharge'] ?? 0) > 0): ?>
                        <div class="flex justify-between"><span class="text-gray-500">Small order surcharge</span><span><?= money($order['small_order_surcharge']) ?></span></div>
                        <?php endif; ?>
                        <div class="border-t my-2"></div>
                        <div class="flex justify-between font-bold text-green-800"><span>Total</span><span><?= money($order['total_amount']) ?></span></div>
                    </div>
                </div>

                <div class="bg-white rounded-xl shadow p-5">
                    <h2 class="font-bold text-gray-800 mb-3">Update status</h2>
                    <form method="POST" class="flex flex-col gap-2">
                        <input type="hidden" name="action" value="update_status">
                        <select name="status" class="border rounded px-2 py-2 text-sm">
                            <?php foreach (['Pending', 'Processing', 'Shipped', 'Delivered', 'Cancelled'] as $s): ?>
                                <option value="<?= $s ?>" <?= $order['status'] === $s ? 'selected' : '' ?>><?= $s ?></option>
                            <?php endforeach; ?>
                        </select>
                        <button type="submit" class="bg-blue-600 hover:bg-blue-700 text-white px-3 py-2 rounded text-sm">Update</button>
                    </form>
                </div>

                <div class="bg-white rounded-xl shadow p-5">
                    <h2 class="font-bold text-gray-800 mb-3">Assign rider</h2>
                    <form method="POST" class="flex flex-col gap-2">
                        <input type="hidden" name="action" value="assign_rider">
                        <select name="rider_id" class="border rounded px-2 py-2 text-sm">
                            <option value="">Unassigned</option>
                            <?php foreach ($riders as $rider): ?>
                                <option value="<?= $rider['id'] ?>" <?= ($assignment && (int)$assignment['rider_id'] === (int)$rider['id']) ? 'selected' : '' ?>><?= htmlspecialchars($rider['name']) ?></option>
                            <?php endforeach; ?>
                        </select>
                        <button type="submit" class="bg-green-600 hover:bg-green-700 text-white px-3 py-2 rounded text-sm">Assign</button>
                    </form>
                </div>
            </div>
        </div>
    </div>
</div>
</body>
</html>
