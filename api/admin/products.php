<?php
session_start();
require_once '../admin/includes/config.php'; // adjust path if needed

require_once __DIR__ . '/../includes/product_image.php';

// Check if admin is logged in
if (!isset($_SESSION['admin_logged_in']) || $_SESSION['admin_logged_in'] !== true) {
    header('Location: login.php');
    exit;
}

// Handle DELETE request
if ($_SERVER['REQUEST_METHOD'] === 'POST' && isset($_POST['delete_id'])) {
    $deleteId = intval($_POST['delete_id']);
    if ($deleteId > 0) {
        $stmt = $dbh->prepare("DELETE FROM items WHERE id = ?");
        $stmt->execute([$deleteId]);
        // Redirect to avoid resubmission
        header('Location: products.php?deleted=1');
        exit;
    }
}

// Get filter parameters
$search = isset($_GET['search']) ? trim($_GET['search']) : '';
$category = isset($_GET['category']) ? trim($_GET['category']) : '';

// Build query
$sql = "SELECT * FROM items WHERE 1=1";
$params = [];

if (!empty($search)) {
    $sql .= " AND (name LIKE ? OR description LIKE ?)";
    $like = "%$search%";
    $params[] = $like;
    $params[] = $like;
}

if (!empty($category)) {
    $sql .= " AND category = ?";
    $params[] = $category;
}

$sql .= " ORDER BY id DESC";
$stmt = $dbh->prepare($sql);
$stmt->execute($params);
$products = $stmt->fetchAll(PDO::FETCH_ASSOC);

// Get distinct categories for filter dropdown
$catStmt = $dbh->query("SELECT DISTINCT category FROM items ORDER BY category");
$categories = $catStmt->fetchAll(PDO::FETCH_COLUMN);
?>

<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>AfamFresh Admin – Products</title>
    <script src="https://cdn.tailwindcss.com"></script>
    <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.5.0/css/all.min.css">
</head>
<body class="bg-gray-100">

<div class="flex h-screen">
    <!-- Sidebar -->
    <div class="w-64 bg-green-800 text-white flex flex-col">
        <div class="p-6 text-xl font-bold border-b border-green-700">AfamFresh</div>
        <nav class="flex-1 p-4 space-y-2">
            <a href="dashboard.php" class="block py-2 px-4 rounded hover:bg-green-700"><i class="fas fa-chart-pie mr-2"></i> Dashboard</a>
            <a href="products.php" class="block py-2 px-4 rounded bg-green-700"><i class="fas fa-box mr-2"></i> Products</a>
            <a href="#" class="block py-2 px-4 rounded hover:bg-green-700"><i class="fas fa-shopping-cart mr-2"></i> Orders</a>
            <a href="#" class="block py-2 px-4 rounded hover:bg-green-700"><i class="fas fa-users mr-2"></i> Users</a>
            <a href="logout.php" class="block py-2 px-4 rounded hover:bg-red-600 mt-8"><i class="fas fa-sign-out-alt mr-2"></i> Logout</a>
        </nav>
    </div>

    <!-- Main Content -->
    <div class="flex-1 overflow-y-auto">
        <div class="p-6">
            <div class="flex justify-between items-center mb-6">
                <h1 class="text-2xl font-bold text-green-800">Manage Products</h1>
                <a href="add-product.php" class="bg-green-600 hover:bg-green-700 text-white px-4 py-2 rounded flex items-center gap-2">
                    <i class="fas fa-plus"></i> Add New Product
                </a>
            </div>

            <?php if (isset($_GET['deleted'])): ?>
                <div class="bg-green-100 border border-green-400 text-green-700 px-4 py-3 rounded mb-4">Product deleted successfully.</div>
            <?php endif; ?>

            <!-- Filters -->
            <form method="GET" class="flex flex-wrap gap-4 mb-6">
                <div class="flex-1 min-w-[200px]">
                    <input type="text" name="search" placeholder="Search products..." value="<?= htmlspecialchars($search) ?>" class="w-full px-4 py-2 border rounded">
                </div>
                <div>
                    <select name="category" class="px-4 py-2 border rounded">
                        <option value="">All Categories</option>
                        <?php foreach ($categories as $cat): ?>
                            <option value="<?= htmlspecialchars($cat) ?>" <?= $cat == $category ? 'selected' : '' ?>><?= htmlspecialchars($cat) ?></option>
                        <?php endforeach; ?>
                    </select>
                </div>
                <button type="submit" class="bg-blue-600 hover:bg-blue-700 text-white px-4 py-2 rounded">Filter</button>
                <a href="products.php" class="bg-gray-300 hover:bg-gray-400 px-4 py-2 rounded">Reset</a>
            </form>

            <!-- Product Table -->
            <div class="bg-white rounded-xl shadow overflow-hidden">
                <table class="w-full">
                    <thead class="bg-gray-50 border-b">
                        <tr>
                            <th class="px-6 py-4 text-left text-sm font-semibold text-gray-600">Image</th>
                            <th class="px-6 py-4 text-left text-sm font-semibold text-gray-600">Name</th>
                            <th class="px-6 py-4 text-left text-sm font-semibold text-gray-600">Category</th>
                            <th class="px-6 py-4 text-left text-sm font-semibold text-gray-600">Price</th>
                            <th class="px-6 py-4 text-left text-sm font-semibold text-gray-600">Qty</th>
                            <th class="px-6 py-4 text-left text-sm font-semibold text-gray-600">Status</th>
                            <th class="px-6 py-4 text-center text-sm font-semibold text-gray-600">Actions</th>
                        </tr>
                    </thead>
                    <tbody class="divide-y">
                        <?php if (count($products) === 0): ?>
                            <tr>
                                <td colspan="7" class="px-6 py-8 text-center text-gray-500">No products found.</td>
                            </tr>
                        <?php else: ?>
                            <?php foreach ($products as $row): ?>
                            <tr class="hover:bg-gray-50">
                                <td class="px-6 py-4">
                                    <?php $imgPath = productImageRelPath($row['image']); ?>
                                    <?php if ($imgPath): ?>
                                        <img src="..<?= htmlspecialchars($imgPath) ?>" class="w-12 h-12 object-cover rounded">
                                    <?php elseif (!empty($row['image'])): ?>
                                        <div class="w-12 h-12 bg-amber-100 border border-amber-300 rounded flex items-center justify-center text-amber-600" title="File missing: <?= htmlspecialchars($row['image']) ?>">⚠</div>
                                    <?php else: ?>
                                        <div class="w-12 h-12 bg-gray-200 rounded flex items-center justify-center text-gray-400">📦</div>
                                    <?php endif; ?>
                                </td>
                                <td class="px-6 py-4 font-medium"><?= htmlspecialchars($row['name']) ?></td>
                                <td class="px-6 py-4"><?= htmlspecialchars($row['category']) ?></td>
                                <td class="px-6 py-4 font-semibold">UGX <?= number_format($row['price']) ?></td>
                                <td class="px-6 py-4"><?= htmlspecialchars($row['quantity']) . ' ' . htmlspecialchars($row['quantitytype']) ?></td>
                                <td class="px-6 py-4">
                                    <div class="flex flex-wrap gap-1">
                                        <?php if (strtoupper($row['homepage']) === 'YES'): ?>
                                            <span class="px-2 py-1 text-xs font-medium rounded-full bg-blue-100 text-blue-700">Homepage</span>
                                        <?php endif; ?>
                                        <?php if (strtoupper($row['offer']) === 'YES'): ?>
                                            <span class="px-2 py-1 text-xs font-medium rounded-full bg-orange-100 text-orange-700">Offer</span>
                                        <?php endif; ?>
                                        <?php if ($row['weekly_deal'] === 'YES'): ?>
                                            <span class="px-2 py-1 text-xs font-medium rounded-full bg-purple-100 text-purple-700">Weekly Deal</span>
                                        <?php endif; ?>
                                    </div>
                                </td>
                                <td class="px-6 py-4 text-center space-x-3">
                                    <a href="edit-product.php?id=<?= $row['id'] ?>" class="text-blue-600 hover:text-blue-800">
                                        <i class="fas fa-edit"></i>
                                    </a>
                                    <form method="POST" style="display:inline;" onsubmit="return confirm('Are you sure you want to delete this product?');">
                                        <input type="hidden" name="delete_id" value="<?= $row['id'] ?>">
                                        <button type="submit" class="text-red-600 hover:text-red-800 bg-transparent border-0 cursor-pointer">
                                            <i class="fas fa-trash"></i>
                                        </button>
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
</div>

</body>
</html>