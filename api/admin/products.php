<?php
session_start();
require_once '../admin/includes/config.php'; // adjust path if needed

require_once __DIR__ . '/../includes/product_image.php';
require_once __DIR__ . '/../includes/csrf.php';
require_once __DIR__ . '/../includes/admin_permissions.php';
require_once __DIR__ . '/../includes/admin_audit.php';
// Browsing/editing non-price fields needs only the operational permission;
// deleting a product is pricing/catalog-critical and checked separately
// below, matching add-product.php/delete-product.php's own gating.
requireAdminPermission('products.manage_operational');

// Handle DELETE request
if ($_SERVER['REQUEST_METHOD'] === 'POST' && isset($_POST['delete_id'])) {
    verifyCsrf();
    requireAdminPermission('products.manage_pricing');
    $deleteId = intval($_POST['delete_id']);
    if ($deleteId > 0) {
        $stmt = $dbh->prepare("DELETE FROM items WHERE id = ?");
        $stmt->execute([$deleteId]);
        logAdminAction($dbh, 'product.deleted', 'product', (string)$deleteId, 'Deleted product');
        // Redirect to avoid resubmission
        header('Location: products.php?deleted=1');
        exit;
    }
}

// Get filter parameters
$search = isset($_GET['search']) ? trim($_GET['search']) : '';
$category = isset($_GET['category']) ? trim($_GET['category']) : '';

// Build query
$sql = "SELECT items.*, vendors.business_name AS vendor_business_name
        FROM items
        LEFT JOIN vendors ON vendors.id = items.vendor_id
        WHERE 1=1";
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
    <?php include __DIR__ . "/includes/nav.php"; ?>

    <!-- Main Content -->
    <div class="flex-1 overflow-y-auto">
        <div class="p-6">
            <div class="flex justify-between items-center mb-6">
                <h1 class="text-2xl font-bold text-green-800">Manage Products</h1>
                <?php if (adminHasPermission('products.manage_pricing')): ?>
                    <a href="add-product.php" class="bg-green-600 hover:bg-green-700 text-white px-4 py-2 rounded flex items-center gap-2">
                        <i class="fas fa-plus"></i> Add New Product
                    </a>
                <?php endif; ?>
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
                            <th class="px-6 py-4 text-left text-sm font-semibold text-gray-600">Source</th>
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
                                <td colspan="8" class="px-6 py-8 text-center text-gray-500">No products found.</td>
                            </tr>
                        <?php else: ?>
                            <?php foreach ($products as $row): ?>
                            <tr class="hover:bg-gray-50">
                                <td class="px-6 py-4">
                                    <?php $imgPath = productImageUrl($row['image']); ?>
                                    <?php if ($imgPath): ?>
                                        <img src="<?= htmlspecialchars($imgPath) ?>" class="w-12 h-12 object-cover rounded">
                                    <?php elseif (!empty($row['image'])): ?>
                                        <div class="w-12 h-12 bg-amber-100 border border-amber-300 rounded flex items-center justify-center text-amber-600" title="File missing: <?= htmlspecialchars($row['image']) ?>">⚠</div>
                                    <?php else: ?>
                                        <div class="w-12 h-12 bg-gray-200 rounded flex items-center justify-center text-gray-400">📦</div>
                                    <?php endif; ?>
                                </td>
                                <td class="px-6 py-4 font-medium"><?= htmlspecialchars($row['name']) ?></td>
                                <td class="px-6 py-4">
                                    <?php if ($row['vendor_id'] !== null): ?>
                                        <span class="px-2 py-1 text-xs font-medium rounded-full bg-teal-100 text-teal-700" title="Vendor-owned — manage on the Vendor Catalogue page">
                                            <i class="fas fa-store mr-1"></i><?= htmlspecialchars($row['vendor_business_name'] ?? 'Vendor') ?>
                                        </span>
                                    <?php else: ?>
                                        <span class="px-2 py-1 text-xs font-medium rounded-full bg-gray-100 text-gray-600">
                                            <i class="fas fa-warehouse mr-1"></i>Shop
                                        </span>
                                    <?php endif; ?>
                                </td>
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
                                    <?php if (adminHasPermission('products.manage_pricing')): ?>
                                    <form method="POST" style="display:inline;" onsubmit="return confirm('Are you sure you want to delete this product?');">
                                        <?= csrfField() ?>
                                        <input type="hidden" name="delete_id" value="<?= $row['id'] ?>">
                                        <button type="submit" class="text-red-600 hover:text-red-800 bg-transparent border-0 cursor-pointer">
                                            <i class="fas fa-trash"></i>
                                        </button>
                                    </form>
                                    <?php endif; ?>
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