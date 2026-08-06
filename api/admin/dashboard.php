<?php
session_start();
require_once '../admin/includes/config.php';

if (!isset($_SESSION['admin_logged_in']) || $_SESSION['admin_logged_in'] !== true) {
    header('Location: login.php');
    exit;
}

// Fetch stats via API (or direct DB queries)
$stats = [];
try {
    $stmt = $dbh->query("SELECT 
        (SELECT COUNT(*) FROM orders) as total_orders,
        (SELECT COUNT(*) FROM users) as total_users,
        (SELECT COUNT(*) FROM items) as total_products,
        (SELECT COUNT(*) FROM surplus_listings WHERE status = 'pending') as pending_surplus,
        (SELECT COUNT(*) FROM orders WHERE status = 'pending') as pending_orders,
        (SELECT COALESCE(SUM(total_amount), 0) FROM orders) as total_revenue
    ");
    $stats = $stmt->fetch(PDO::FETCH_ASSOC);
} catch (Exception $e) {
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
    <div class="w-64 bg-green-800 text-white flex flex-col">
        <div class="p-6 text-xl font-bold border-b border-green-700">AfamFresh</div>
        <nav class="flex-1 p-4 space-y-2">
            <a href="dashboard.php" class="block py-2 px-4 rounded bg-green-700"><i class="fas fa-chart-pie mr-2"></i> Dashboard</a>
            <a href="products.php" class="block py-2 px-4 rounded hover:bg-green-700"><i class="fas fa-box mr-2"></i> Products</a>
            <a href="orders.php" class="block py-2 px-4 rounded hover:bg-green-700"><i class="fas fa-shopping-cart mr-2"></i> Orders</a>
            <a href="users.php" class="block py-2 px-4 rounded hover:bg-green-700"><i class="fas fa-users mr-2"></i> Users</a>
            <a href="riders.php" class="block py-2 px-4 rounded hover:bg-green-700"><i class="fas fa-motorcycle mr-2"></i> Riders</a>
            <a href="logout.php" class="block py-2 px-4 rounded hover:bg-red-600 mt-8"><i class="fas fa-sign-out-alt mr-2"></i> Logout</a>
        </nav>
    </div>

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
                <div class="text-3xl text-red-600"><i class="fas fa-money-bill-wave"></i></div>
                <div>
                    <p class="text-gray-500 text-sm">Revenue</p>
                    <p class="text-2xl font-bold">UGX <?= number_format($stats['total_revenue'] ?? 0) ?></p>
                </div>
            </div>
        </div>

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