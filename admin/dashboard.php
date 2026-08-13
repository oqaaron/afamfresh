<?php
session_start();
require_once '../admin/includes/config.php';

if (!isset($_SESSION['admin_logged_in']) || $_SESSION['admin_logged_in'] !== true) {
    header('Location: login.php');
    exit;
}

require_once __DIR__ . '/../includes/revenue.php';

$stats = [];
$revenue = null;
try {
    // 'Received'/'Pending', not 'pending': orders.status is written capitalised
    // everywhere, so the old lowercase comparison reported a permanent zero.
    $stmt = $dbh->query("SELECT
        (SELECT COUNT(*) FROM orders) as total_orders,
        (SELECT COUNT(*) FROM users) as total_users,
        (SELECT COUNT(*) FROM items) as total_products,
        (SELECT COUNT(*) FROM surplus_listings WHERE status = 'pending') as pending_surplus,
        (SELECT COUNT(*) FROM orders WHERE status IN ('Received','Pending')) as pending_orders
    ");
    $stats = $stmt->fetch(PDO::FETCH_ASSOC);

    // The revenue figure here used to be `SUM(total_amount) FROM orders` with
    // no WHERE clause, counting unpaid, failed, reversed and cancelled orders
    // as revenue while excluding the entire surplus channel. It now comes from
    // one shared definition — see includes/revenue.php.
    $revenue = revenueSummary($dbh);
} catch (Exception $e) {
    error_log('admin dashboard stats: ' . $e->getMessage());
    $stats = [];
}
?>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>AfamFresh Admin Dashboard</title>
    <script src="https://cdn.tailwindcss.com"></script>
    <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.5.0/css/all.min.css">
</head>
<body class="bg-gray-100">

<div class="flex h-screen">
    <!-- Sidebar -->
    <?php include __DIR__ . "/includes/nav.php"; ?>

    <!-- Main Content -->
    <div class="flex-1 overflow-y-auto p-6">
        <h1 class="text-3xl font-bold text-green-800 mb-6">Dashboard</h1>

        <!-- Stats Cards -->
        <div class="grid grid-cols-1 md:grid-cols-3 lg:grid-cols-6 gap-4 mb-8">
            <div class="bg-white p-5 rounded-xl shadow flex items-center gap-4">
                <div class="text-3xl text-blue-600"><i class="fas fa-shopping-cart"></i></div>
                <div>
                    <p class="text-gray-500 text-sm">Orders</p>
                    <p class="text-2xl font-bold"><?= number_format($stats['total_orders'] ?? 0) ?></p>
                </div>
            </div>
            <div class="bg-white p-5 rounded-xl shadow flex items-center gap-4">
                <div class="text-3xl text-green-600"><i class="fas fa-users"></i></div>
                <div>
                    <p class="text-gray-500 text-sm">Users</p>
                    <p class="text-2xl font-bold"><?= number_format($stats['total_users'] ?? 0) ?></p>
                </div>
            </div>
            <div class="bg-white p-5 rounded-xl shadow flex items-center gap-4">
                <div class="text-3xl text-purple-600"><i class="fas fa-box"></i></div>
                <div>
                    <p class="text-gray-500 text-sm">Products</p>
                    <p class="text-2xl font-bold"><?= number_format($stats['total_products'] ?? 0) ?></p>
                </div>
            </div>
            <div class="bg-white p-5 rounded-xl shadow flex items-center gap-4">
                <div class="text-3xl text-yellow-600"><i class="fas fa-clock"></i></div>
                <div>
                    <p class="text-gray-500 text-sm">Pending Orders</p>
                    <p class="text-2xl font-bold"><?= number_format($stats['pending_orders'] ?? 0) ?></p>
                </div>
            </div>
            <div class="bg-white p-5 rounded-xl shadow flex items-center gap-4">
                <div class="text-3xl text-orange-600"><i class="fas fa-tags"></i></div>
                <div>
                    <p class="text-gray-500 text-sm">Pending Surplus</p>
                    <p class="text-2xl font-bold"><?= number_format($stats['pending_surplus'] ?? 0) ?></p>
                </div>
            </div>
            <div class="bg-white p-5 rounded-xl shadow flex items-center gap-4">
                <div class="text-3xl text-green-700"><i class="fas fa-money-bill-wave"></i></div>
                <div>
                    <p class="text-gray-500 text-sm">Settled revenue</p>
                    <p class="text-2xl font-bold">UGX <?= number_format($revenue['total']['settled']['value'] ?? 0) ?></p>
                </div>
            </div>
        </div>

        <?php if ($revenue !== null): ?>
        <!-- The money, split four ways.
             All four are shown together on purpose. The old dashboard reported
             one number -- the gross value of every row in `orders` -- as
             "Revenue", so the figure people have been used to is the one on the
             far left here, not the one above. Showing them side by side is what
             makes the difference legible instead of looking like money went
             missing overnight. -->
        <div class="grid grid-cols-1 md:grid-cols-4 gap-4 mb-8">
            <div class="bg-white p-4 rounded-xl shadow">
                <p class="text-gray-500 text-xs uppercase tracking-wide">Gross value placed</p>
                <p class="text-xl font-bold text-gray-800">UGX <?= number_format($revenue['total']['gross']['value']) ?></p>
                <p class="text-xs text-gray-400"><?= number_format($revenue['total']['gross']['count']) ?> orders, paid or not</p>
            </div>
            <div class="bg-white p-4 rounded-xl shadow border-l-4 border-green-600">
                <p class="text-gray-500 text-xs uppercase tracking-wide">Settled</p>
                <p class="text-xl font-bold text-green-800">UGX <?= number_format($revenue['total']['settled']['value']) ?></p>
                <p class="text-xs text-gray-400">money actually received</p>
            </div>
            <div class="bg-white p-4 rounded-xl shadow border-l-4 border-yellow-500">
                <p class="text-gray-500 text-xs uppercase tracking-wide">Cash outstanding</p>
                <p class="text-xl font-bold text-yellow-700">UGX <?= number_format($revenue['total']['outstanding']['value']) ?></p>
                <p class="text-xs text-gray-400"><?= number_format($revenue['total']['outstanding']['count']) ?> cash orders not confirmed</p>
            </div>
            <div class="bg-white p-4 rounded-xl shadow border-l-4 border-red-500">
                <p class="text-gray-500 text-xs uppercase tracking-wide">Failed or cancelled</p>
                <p class="text-xl font-bold text-red-700">UGX <?= number_format($revenue['total']['lost']['value']) ?></p>
                <p class="text-xs text-gray-400"><?= number_format($revenue['total']['lost']['count']) ?> orders</p>
            </div>
        </div>

        <div class="bg-white p-5 rounded-xl shadow mb-8">
            <div class="flex justify-between items-center mb-3">
                <h2 class="font-semibold text-gray-800">Settled revenue by channel</h2>
                <a href="reconciliation.php" class="text-sm text-green-700 hover:underline">Full reconciliation →</a>
            </div>
            <div class="grid grid-cols-2 md:grid-cols-4 gap-4 text-sm">
                <div>
                    <p class="text-gray-500">Shop</p>
                    <p class="font-semibold">UGX <?= number_format($revenue['shop']['settled']['value']) ?></p>
                </div>
                <div>
                    <p class="text-gray-500">Surplus</p>
                    <p class="font-semibold">UGX <?= number_format($revenue['surplus']['settled']['value']) ?></p>
                </div>
                <?php if ($revenue['by_method'] !== null): ?>
                    <div>
                        <p class="text-gray-500">of which cash</p>
                        <p class="font-semibold">UGX <?= number_format($revenue['by_method']['cash']['value']) ?></p>
                    </div>
                    <div>
                        <p class="text-gray-500">of which electronic</p>
                        <p class="font-semibold">UGX <?= number_format(
                            $revenue['by_method']['mobile_money']['value'] + $revenue['by_method']['card']['value']
                        ) ?></p>
                    </div>
                <?php else: ?>
                    <div class="col-span-2 text-gray-500">
                        Cash/electronic split unavailable — the payment_method migration has not been run.
                    </div>
                <?php endif; ?>
            </div>
        </div>
        <?php endif; ?>

        <!-- Quick Links / Actions -->
        <div class="grid grid-cols-1 md:grid-cols-3 gap-6 mb-8">
            <a href="orders.php" class="bg-white p-6 rounded-xl shadow hover:shadow-md flex items-center justify-between">
                <div><i class="fas fa-shopping-cart text-3xl text-blue-600"></i></div>
                <div><span class="font-medium">Manage Orders</span> <span class="text-gray-400">→</span></div>
            </a>
            <a href="products.php" class="bg-white p-6 rounded-xl shadow hover:shadow-md flex items-center justify-between">
                <div><i class="fas fa-box text-3xl text-purple-600"></i></div>
                <div><span class="font-medium">Manage Products</span> <span class="text-gray-400">→</span></div>
            </a>
            <a href="users.php" class="bg-white p-6 rounded-xl shadow hover:shadow-md flex items-center justify-between">
                <div><i class="fas fa-users text-3xl text-green-600"></i></div>
                <div><span class="font-medium">Manage Users</span> <span class="text-gray-400">→</span></div>
            </a>
            <a href="riders.php" class="bg-white p-6 rounded-xl shadow hover:shadow-md flex items-center justify-between">
                <div><i class="fas fa-motorcycle text-3xl text-yellow-600"></i></div>
                <div><span class="font-medium">Manage Riders</span> <span class="text-gray-400">→</span></div>
            </a>
            <div class="bg-white p-6 rounded-xl shadow flex items-center justify-between">
                <div><i class="fas fa-cog text-3xl text-gray-600"></i></div>
                <div><span class="font-medium">Configuration</span> <span class="text-gray-400">→</span></div>
            </div>
            <div class="bg-white p-6 rounded-xl shadow flex items-center justify-between">
                <div><i class="fas fa-tags text-3xl text-orange-600"></i></div>
                <div><span class="font-medium">Surplus Approvals</span> <span class="text-gray-400">→</span></div>
            </div>
        </div>
    </div>
</div>

</body>
</html>