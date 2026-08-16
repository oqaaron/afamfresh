<?php
session_start();
require_once 'includes/config.php';
require_once __DIR__ . '/../includes/product_image.php';
require_once __DIR__ . '/../includes/csrf.php';
require_once __DIR__ . '/../includes/admin_permissions.php';
require_once __DIR__ . '/../includes/admin_audit.php';
// New products need a price to exist at all, so creating one is
// products.manage_pricing-gated, not the operational permission that lets a
// dispatcher edit an existing product's non-price fields.
requireAdminPermission('products.manage_pricing');

$error = '';
$success = '';

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    verifyCsrf();
    $name = trim($_POST['name'] ?? '');
    $category = trim($_POST['category'] ?? '');
    $price = floatval($_POST['price'] ?? 0);
    $description = trim($_POST['description'] ?? '');
    $quantity = intval($_POST['quantity'] ?? 0);
    $quantitytype = trim($_POST['quantitytype'] ?? 'Kg');
    $discount = floatval($_POST['discount'] ?? 0);
    // Drive the app's Promos/Flash Sales rows on HomeScreen. Stored as the
    // 'YES'/'NO' strings items.offer/weekly_deal already use — Product.kt's
    // isOffer/isWeeklyDeal do a case-insensitive match against exactly that.
    $offer = isset($_POST['offer']) ? 'YES' : 'NO';
    $weeklyDeal = isset($_POST['weekly_deal']) ? 'YES' : 'NO';

    // Handle image upload
    $image = '';
    // An image is optional, but a *failed* upload is an error rather than
    // something to shrug off — the old code discarded move_uploaded_file()'s
    // result and inserted a filename that was never written to disk.
    if (isset($_FILES['image']) && $_FILES['image']['error'] !== UPLOAD_ERR_NO_FILE) {
        $result = saveProductImage($_FILES['image']);
        if ($result['ok']) {
            $image = $result['filename'];
        } else {
            $error = $result['error'];
        }
    }

    if ($error) {
        // An image error above is fatal for the whole save — fall through and
        // re-render with the message rather than inserting a broken row.
    } elseif ($name && $category && $price > 0) {
        $stmt = $dbh->prepare("INSERT INTO items (name, category, description, price, quantity, quantitytype, discount, offer, weekly_deal, image) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
        if ($stmt->execute([$name, $category, $description, $price, $quantity, $quantitytype, $discount, $offer, $weeklyDeal, $image])) {
            $success = 'Product added successfully!';
            logAdminAction($dbh, 'product.created', 'product', (string)$dbh->lastInsertId(), "Created \"$name\" at UGX $price");
        } else {
            $error = 'Failed to add product.';
        }
    } else {
        $error = 'Please fill in all required fields.';
    }
}
?>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Add Product</title>
    <script src="https://cdn.tailwindcss.com"></script>
</head>
<body class="bg-gray-100 p-6">
    <div class="max-w-3xl mx-auto bg-white p-8 rounded-xl shadow">
        <h1 class="text-2xl font-bold text-green-800 mb-6">Add New Product</h1>

        <?php if ($error): ?>
            <div class="bg-red-100 border border-red-400 text-red-700 px-4 py-3 rounded mb-4"><?= htmlspecialchars($error) ?></div>
        <?php endif; ?>
        <?php if ($success): ?>
            <div class="bg-green-100 border border-green-400 text-green-700 px-4 py-3 rounded mb-4"><?= htmlspecialchars($success) ?></div>
        <?php endif; ?>

        <form method="POST" enctype="multipart/form-data">
            <?= csrfField() ?>
            <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
                <div>
                    <label class="block text-gray-700 font-bold mb-2">Product Name</label>
                    <input type="text" name="name" class="w-full px-4 py-2 border rounded" required>
                </div>
                <div>
                    <label class="block text-gray-700 font-bold mb-2">Category</label>
                    <input type="text" name="category" class="w-full px-4 py-2 border rounded" required>
                </div>
                <div>
                    <label class="block text-gray-700 font-bold mb-2">Price (UGX)</label>
                    <input type="number" name="price" step="0.01" class="w-full px-4 py-2 border rounded" required>
                </div>
                <div>
                    <label class="block text-gray-700 font-bold mb-2">Quantity</label>
                    <input type="number" name="quantity" class="w-full px-4 py-2 border rounded">
                </div>
                <div>
                    <label class="block text-gray-700 font-bold mb-2">Quantity Type</label>
                    <select name="quantitytype" class="w-full px-4 py-2 border rounded">
                        <option>Kg</option>
                        <option>Grams</option>
                        <option>Unit</option>
                        <option>Packet</option>
                        <option>ml</option>
                    </select>
                </div>
                <div>
                    <label class="block text-gray-700 font-bold mb-2">Discount (%)</label>
                    <input type="number" name="discount" step="0.01" value="0" class="w-full px-4 py-2 border rounded">
                    <p class="text-gray-400 text-xs mt-1">A discount above 0% puts this product under Hot Sale in the app.</p>
                </div>
                <div class="flex items-center gap-6 md:col-span-2">
                    <label class="flex items-center gap-2">
                        <input type="checkbox" name="offer" value="1" class="h-4 w-4">
                        <span class="text-gray-700 font-bold">Show under Promos</span>
                    </label>
                    <label class="flex items-center gap-2">
                        <input type="checkbox" name="weekly_deal" value="1" class="h-4 w-4">
                        <span class="text-gray-700 font-bold">Show under Flash Sales</span>
                    </label>
                </div>
                <div class="md:col-span-2">
                    <label class="block text-gray-700 font-bold mb-2">Description</label>
                    <textarea name="description" rows="3" class="w-full px-4 py-2 border rounded"></textarea>
                </div>
                <div class="md:col-span-2">
                    <label class="block text-gray-700 font-bold mb-2">Image</label>
                    <input type="file" name="image" accept="image/*" class="w-full">
                </div>
            </div>
            <div class="mt-6 flex gap-4">
                <button type="submit" class="bg-green-600 hover:bg-green-700 text-white px-6 py-2 rounded">Save Product</button>
                <a href="products.php" class="bg-gray-300 hover:bg-gray-400 px-6 py-2 rounded">Cancel</a>
            </div>
        </form>
    </div>
</body>
</html>