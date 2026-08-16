<?php
session_start();
require_once '../admin/includes/config.php';
require_once __DIR__ . '/../includes/admin_permissions.php';
requireAdminPermission('reports.view');

$fullAccess = adminHasPermission('reports.view_financial');

$stats = [];
$revenue = null;
$platformFees = null;

if ($fullAccess) {
    require_once __DIR__ . '/../includes/revenue.php';

    try {
        // 'Received'/'Pending', not 'pending': orders.status is written capitalised
        // everywhere, so the old lowercase comparison reported a permanent zero.
        $stmt = $dbh->query("SELECT
            (SELECT COUNT(*) FROM orders) as total_orders,
            (SELECT COUNT(*) FROM users) as total_users,
            (SELECT COUNT(*) FROM items) as total_products,
            (SELECT COUNT(*) FROM Bulk_listings WHERE status = 'pending') as pending_Bulk,
            (SELECT COUNT(*) FROM orders WHERE status IN ('Received','Pending')) as pending_orders
        ");
        $stats = $stmt->fetch(PDO::FETCH_ASSOC);

        // The revenue figure here used to be `SUM(total_amount) FROM orders` with
        // no WHERE clause, counting unpaid, failed, reversed and cancelled orders
        // as revenue while excluding the entire Bulk channel. It now comes from
        // one shared definition — see includes/revenue.php.
        $revenue = revenueSummary($dbh);
        $platformFees = platformFeesSummary($dbh);
    } catch (Exception $e) {
        error_log('admin dashboard stats: ' . $e->getMessage());
        $stats = [];
    }
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

        <?php if ($fullAccess): ?>
        <!-- FULL ACCESS BRANCH: System-wide stats and revenue (super admin) -->

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
                    <p class="text-gray-500 text-sm">Pending Bulk</p>
                    <p class="text-2xl font-bold"><?= number_format($stats['pending_Bulk'] ?? 0) ?></p>
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
                    <p class="text-gray-500">Bulk</p>
                    <p class="font-semibold">UGX <?= number_format($revenue['Bulk']['settled']['value']) ?></p>
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

        <?php if ($platformFees !== null): ?>
        <!-- Fees the platform keeps, independent of vendor commission and
             rider pay. Vendors are credited on goods only and riders are paid
             carriage only (see includes/vendor_earnings.php,
             includes/rider_dispatch.php) — this is the money neither of
             them ever touches. Settled only: a fee on an order nobody paid
             for was never actually collected. -->
        <div class="bg-white p-5 rounded-xl shadow mb-8">
            <div class="flex justify-between items-center mb-3">
                <h2 class="font-semibold text-gray-800">Platform fees collected</h2>
                <span class="text-xs text-gray-400">service + insurance + processing + small-order surcharge, settled orders only</span>
            </div>
            <div class="grid grid-cols-2 md:grid-cols-5 gap-4">
                <div>
                    <p class="text-gray-500 text-xs uppercase tracking-wide">Service fee</p>
                    <p class="text-xl font-bold text-gray-800">UGX <?= number_format($platformFees['service_fee']) ?></p>
                </div>
                <div>
                    <p class="text-gray-500 text-xs uppercase tracking-wide">Insurance fee</p>
                    <p class="text-xl font-bold text-gray-800">UGX <?= number_format($platformFees['insurance_fee']) ?></p>
                </div>
                <div>
                    <p class="text-gray-500 text-xs uppercase tracking-wide">Processing fee</p>
                    <p class="text-xl font-bold text-gray-800">UGX <?= number_format($platformFees['processing_fee']) ?></p>
                </div>
                <div>
                    <p class="text-gray-500 text-xs uppercase tracking-wide">Small order surcharge</p>
                    <p class="text-xl font-bold text-gray-800">UGX <?= number_format($platformFees['small_order_surcharge']) ?></p>
                </div>
                <div class="border-l-4 border-green-600 pl-3">
                    <p class="text-gray-500 text-xs uppercase tracking-wide">Total</p>
                    <p class="text-xl font-bold text-green-800">UGX <?= number_format($platformFees['total']) ?></p>
                </div>
            </div>
            <div class="grid grid-cols-2 gap-4 text-xs text-gray-500 mt-3 pt-3 border-t">
                <div>Shop: UGX <?= number_format($platformFees['shop']['service_fee'] + $platformFees['shop']['insurance_fee'] + $platformFees['shop']['processing_fee'] + $platformFees['shop']['small_order_surcharge']) ?></div>
                <div>Bulk: UGX <?= number_format($platformFees['Bulk']['service_fee'] + $platformFees['Bulk']['insurance_fee'] + $platformFees['Bulk']['processing_fee'] + $platformFees['Bulk']['small_order_surcharge']) ?></div>
            </div>
        </div>
        <?php endif; ?>

        <?php endif; ?>

        <!-- Quick Links / Actions (permission-filtered) -->
        <?php
        $quickLinks = array_values(array_filter([
            ['orders.php', 'Manage Orders', 'fa-shopping-cart', 'text-blue-600', 'orders.manage'],
            ['products.php', 'Manage Products', 'fa-box', 'text-purple-600', 'products.manage_operational'],
            ['Bulk-orders.php', 'Bulk Orders', 'fa-truck-fast', 'text-orange-600', 'Bulk.dispatch'],
            ['users.php', 'Manage Users', 'fa-users', 'text-green-600', 'users.manage'],
            ['riders.php', 'Manage Riders', 'fa-motorcycle', 'text-yellow-600', 'users.manage'],
            ['configuration.php', 'Configuration', 'fa-cog', 'text-gray-600', 'configuration.manage'],
            ['admin-dashboard.php', 'Bulk Approvals', 'fa-tags', 'text-orange-600', 'Bulk.manage_listings'],
        ], fn($l) => adminHasPermission($l[4])));
        ?>
        <div class="grid grid-cols-1 md:grid-cols-3 gap-6 mb-8">
            <?php foreach ($quickLinks as $link): ?>
            <a href="<?= htmlspecialchars($link[0]) ?>" class="bg-white p-6 rounded-xl shadow hover:shadow-md flex items-center justify-between">
                <div><i class="fas <?= htmlspecialchars($link[2]) ?> text-3xl <?= htmlspecialchars($link[3]) ?>"></i></div>
                <div><span class="font-medium"><?= htmlspecialchars($link[1]) ?></span> <span class="text-gray-400">→</span></div>
            </a>
            <?php endforeach; ?>
        </div>
    </div>
</div>

</body>
</html>