<?php
session_start();
require_once 'includes/config.php';
require_once __DIR__ . '/../includes/product_image.php';
require_once __DIR__ . '/../includes/csrf.php';
require_once __DIR__ . '/../includes/admin_permissions.php';
require_once __DIR__ . '/../includes/admin_audit.php';
// Reachable with EITHER permission — a future pricing-only role should not
// be locked out just because it lacks the operational one. Whether
// price/discount can actually be CHANGED is checked separately below, per
// field, not per page — see $canEditPricing.
requireAnyAdminPermission(['products.manage_operational', 'products.manage_pricing']);
$canEditPricing = adminHasPermission('products.manage_pricing');

$id = intval($_GET['id'] ?? 0);
if (!$id) {
    header('Location: products.php');
    exit;
}

$stmt = $dbh->prepare("SELECT * FROM items WHERE id = ?");
$stmt->execute([$id]);
$product = $stmt->fetch(PDO::FETCH_ASSOC);
if (!$product) {
    header('Location: products.php');
    exit;
}

$error = '';
$success = '';

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    verifyCsrf();
    $name = trim($_POST['name'] ?? '');
    $category = trim($_POST['category'] ?? '');
    $description = trim($_POST['description'] ?? '');
    $quantity = intval($_POST['quantity'] ?? 0);
    $quantitytype = trim($_POST['quantitytype'] ?? 'Kg');

    // Without products.manage_pricing, price/discount cannot move — the
    // existing values are used regardless of what was actually posted
    // (including a tampered raw request), never just hidden client-side.
    if ($canEditPricing) {
        $price = floatval($_POST['price'] ?? 0);
        $discount = floatval($_POST['discount'] ?? 0);
    } else {
        $price = (float)$product['price'];
        $discount = (float)$product['discount'];
    }
    // Drive the app's Promos/Flash Sales rows on HomeScreen. Stored as the
    // 'YES'/'NO' strings items.offer/weekly_deal already use — Product.kt's
    // isOffer/isWeeklyDeal do a case-insensitive match against exactly that.
    $offer = isset($_POST['offer']) ? 'YES' : 'NO';
    $weeklyDeal = isset($_POST['weekly_deal']) ? 'YES' : 'NO';

    // Keep the current image unless a new one is successfully stored. A
    // failed upload must not reach the UPDATE: the previous code assigned
    // the new filename regardless of whether the file was written, so a
    // rejected or unwritable upload wiped the product's image while the
    // admin was told it had saved.
    $image = $product['image'];
    if (isset($_FILES['image']) && $_FILES['image']['error'] !== UPLOAD_ERR_NO_FILE) {
        $result = saveProductImage($_FILES['image'], $product['image']);
        if ($result['ok']) {
            $image = $result['filename'];
        } else {
            $error = $result['error'];
        }
    }

    if ($error) {
        // An image error above is fatal for the whole save — fall through and
        // re-render with the message, leaving the row untouched.
    } elseif ($name && $category && $price > 0) {
        $priceAttempted = $canEditPricing && (
            abs((float)$product['price'] - $price) > 0.001 || abs((float)$product['discount'] - $discount) > 0.001
        );
        $stmt = $dbh->prepare("UPDATE items SET name=?, category=?, description=?, price=?, quantity=?, quantitytype=?, discount=?, offer=?, weekly_deal=?, image=? WHERE id=?");
        if ($stmt->execute([$name, $category, $description, $price, $quantity, $quantitytype, $discount, $offer, $weeklyDeal, $image, $id])) {
            $success = 'Product updated successfully!';
            logAdminAction(
                $dbh,
                $priceAttempted ? 'product.price_updated' : 'product.updated',
                'product', (string)$id,
                'Updated "' . $name . '"' . ($priceAttempted ? " (price/discount changed)" : '')
                    . (!$canEditPricing ? ' (price/discount left unchanged — no pricing permission)' : '')
            );
            // Refresh product data
            $stmt = $dbh->prepare("SELECT * FROM items WHERE id = ?");
            $stmt->execute([$id]);
            $product = $stmt->fetch(PDO::FETCH_ASSOC);
        } else {
            $error = 'Failed to update product.';
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
    <title>Edit Product</title>
    <script src="https://cdn.tailwindcss.com"></script>
    <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.5.0/css/all.min.css">
</head>
<body class="bg-gray-100">
<div class="flex h-screen">
    <?php include __DIR__ . "/includes/nav.php"; ?>

    <div class="flex-1 overflow-y-auto">
    <div class="p-6">
    <div class="max-w-3xl mx-auto bg-white p-8 rounded-xl shadow">
        <a href="products.php" class="text-blue-600 hover:text-blue-800 inline-block mb-4"><i class="fas fa-arrow-left mr-1"></i>Back to products</a>
        <h1 class="text-2xl font-bold text-green-800 mb-6">Edit Product</h1>

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
                    <input type="text" name="name" value="<?= htmlspecialchars($product['name']) ?>" class="w-full px-4 py-2 border rounded" required>
                </div>
                <div>
                    <label class="block text-gray-700 font-bold mb-2">Category</label>
                    <input type="text" name="category" value="<?= htmlspecialchars($product['category']) ?>" class="w-full px-4 py-2 border rounded" required>
                </div>
                <div>
                    <label class="block text-gray-700 font-bold mb-2">Price (UGX)</label>
                    <input type="number" name="price" step="0.01" value="<?= $product['price'] ?>"
                           class="w-full px-4 py-2 border rounded <?= $canEditPricing ? '' : 'bg-gray-100 text-gray-500' ?>"
                           <?= $canEditPricing ? 'required' : 'readonly' ?>>
                    <?php if (!$canEditPricing): ?>
                        <p class="text-gray-400 text-xs mt-1">Your account cannot change pricing.</p>
                    <?php endif; ?>
                </div>
                <div>
                    <label class="block text-gray-700 font-bold mb-2">Quantity</label>
                    <input type="number" name="quantity" value="<?= $product['quantity'] ?>" class="w-full px-4 py-2 border rounded">
                </div>
                <div>
                    <label class="block text-gray-700 font-bold mb-2">Quantity Type</label>
                    <select name="quantitytype" class="w-full px-4 py-2 border rounded">
                        <?php foreach (['Kg','Grams','Unit','Packet','ml'] as $opt): ?>
                            <option <?= $opt == $product['quantitytype'] ? 'selected' : '' ?>><?= $opt ?></option>
                        <?php endforeach; ?>
                    </select>
                </div>
                <div>
                    <label class="block text-gray-700 font-bold mb-2">Discount (%)</label>
                    <input type="number" name="discount" step="0.01" value="<?= $product['discount'] ?>"
                           class="w-full px-4 py-2 border rounded <?= $canEditPricing ? '' : 'bg-gray-100 text-gray-500' ?>"
                           <?= $canEditPricing ? '' : 'readonly' ?>>
                    <p class="text-gray-400 text-xs mt-1"><?= $canEditPricing
                        ? 'A discount above 0% puts this product under Hot Sale in the app.'
                        : 'Your account cannot change pricing.' ?></p>
                </div>
                <div class="flex items-center gap-6 md:col-span-2">
                    <label class="flex items-center gap-2">
                        <input type="checkbox" name="offer" value="1" class="h-4 w-4" <?= strcasecmp((string)($product['offer'] ?? 'NO'), 'YES') === 0 ? 'checked' : '' ?>>
                        <span class="text-gray-700 font-bold">Show under Promos</span>
                    </label>
                    <label class="flex items-center gap-2">
                        <input type="checkbox" name="weekly_deal" value="1" class="h-4 w-4" <?= strcasecmp((string)($product['weekly_deal'] ?? 'NO'), 'YES') === 0 ? 'checked' : '' ?>>
                        <span class="text-gray-700 font-bold">Show under Flash Sales</span>
                    </label>
                </div>
                <div class="md:col-span-2">
                    <label class="block text-gray-700 font-bold mb-2">Description</label>
                    <textarea name="description" rows="3" class="w-full px-4 py-2 border rounded"><?= htmlspecialchars($product['description']) ?></textarea>
                </div>
                <div class="md:col-span-2">
                    <label class="block text-gray-700 font-bold mb-2">Current Image</label>
                    <?php $imgPath = productImageUrl($product['image']); ?>
                    <?php if ($imgPath): ?>
                        <img src="<?= htmlspecialchars($imgPath) ?>" class="w-24 h-24 object-cover rounded mb-2">
                    <?php elseif ($product['image']): ?>
                        <p class="text-amber-600 text-sm">Image file missing on the server (<?= htmlspecialchars($product['image']) ?>) — upload a new one.</p>
                    <?php else: ?>
                        <p class="text-gray-400">No image</p>
                    <?php endif; ?>
                    <input type="file" name="image" accept="image/*" class="w-full">
                </div>
            </div>
            <div class="mt-6 flex gap-4">
                <button type="submit" class="bg-green-600 hover:bg-green-700 text-white px-6 py-2 rounded">Update Product</button>
                <a href="products.php" class="bg-gray-300 hover:bg-gray-400 px-6 py-2 rounded">Cancel</a>
            </div>
        </form>
    </div>
    </div>
    </div>
</div>
</body>
</html>