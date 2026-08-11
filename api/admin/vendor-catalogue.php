<?php
// =============================================================
// admin/vendor-catalogue.php
//
// Approve or reject the products vendors create for themselves.
//
// A vendor product lands in `items` with status='pending' and cannot be listed
// as surplus until approved here. It is never sold in the main shop either way
// — api/products.php filters vendor-owned rows out, because a vendor product
// has no stock or fulfilment behind it there. Approval means "this may be sold
// as surplus", not "this is now in the catalogue".
// =============================================================

require_once __DIR__ . '/auth_check.php';
requireAdminLoginWeb();
require_once __DIR__ . '/includes/config.php';
require_once __DIR__ . '/../includes/product_image.php';
require_once __DIR__ . '/../includes/vendor-notification-helper.php';

$flash = '';
$flashError = '';

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $productId = (int)($_POST['product_id'] ?? 0);
    $decision  = $_POST['decision'] ?? '';
    $reason    = trim($_POST['rejection_reason'] ?? '');

    // Read the owner before changing anything: the notification needs the
    // vendor's user id, and after a rejection we still want to name the product.
    $owner = $dbh->prepare(
        "SELECT i.name, v.user_id
           FROM items i JOIN vendors v ON v.id = i.vendor_id
          WHERE i.id = ? AND i.vendor_id IS NOT NULL"
    );
    $owner->execute([$productId]);
    $row = $owner->fetch(PDO::FETCH_ASSOC);

    if (!$row) {
        $flashError = 'No such vendor product.';
    } elseif ($decision === 'approve') {
        $dbh->prepare("UPDATE items SET status = 'approved', rejection_reason = NULL WHERE id = ?")
            ->execute([$productId]);
        addNotification(
            (int)$row['user_id'],
            '✅ Product approved: ' . $row['name'],
            'Your product "' . $row['name'] . '" has been approved. You can now list surplus of it.',
            'system',
            null,
            ['push', 'email']
        );
        $flash = 'Approved. The vendor has been notified.';
    } elseif ($decision === 'reject') {
        if ($reason === '') {
            $flashError = 'Give a reason — a rejection with no explanation just gets resubmitted.';
        } else {
            $dbh->prepare("UPDATE items SET status = 'rejected', rejection_reason = ? WHERE id = ?")
                ->execute([$reason, $productId]);
            addNotification(
                (int)$row['user_id'],
                '❌ Product not approved: ' . $row['name'],
                'Your product "' . $row['name'] . '" was not approved. Reason: ' . $reason,
                'system',
                null,
                ['push', 'email']
            );
            $flash = 'Rejected. The vendor has been told why.';
        }
    }
}

$statusFilter = $_GET['status'] ?? 'pending';
if (!in_array($statusFilter, ['pending', 'approved', 'rejected', 'all'], true)) {
    $statusFilter = 'pending';
}

$sql = "SELECT i.*, v.business_name
          FROM items i
          JOIN vendors v ON v.id = i.vendor_id
         WHERE i.vendor_id IS NOT NULL";
$params = [];
if ($statusFilter !== 'all') {
    $sql .= " AND i.status = ?";
    $params[] = $statusFilter;
}
$sql .= " ORDER BY i.id DESC";
$stmt = $dbh->prepare($sql);
$stmt->execute($params);
$products = $stmt->fetchAll(PDO::FETCH_ASSOC);

$pendingCount = (int)$dbh->query(
    "SELECT COUNT(*) FROM items WHERE vendor_id IS NOT NULL AND status = 'pending'"
)->fetchColumn();
?>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Vendor Products — AfamFresh Admin</title>
    <script src="https://cdn.tailwindcss.com"></script>
    <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.5.0/css/all.min.css">
</head>
<body class="bg-gray-50 font-sans antialiased">
<div class="flex min-h-screen">
<?php include __DIR__ . '/includes/nav.php'; ?>
<div class="flex-1 overflow-auto">
    <div class="max-w-7xl mx-auto px-6 py-8">
        <h1 class="text-2xl font-bold text-green-800 mb-1">Vendor Products</h1>
        <p class="text-gray-600 mb-6">
            Products vendors created themselves. Approving one lets that vendor list
            surplus of it; it is not added to the customer shop.
        </p>

        <?php if ($flash): ?>
            <div class="bg-green-100 border border-green-300 text-green-800 px-4 py-3 rounded mb-4"><?= htmlspecialchars($flash) ?></div>
        <?php endif; ?>
        <?php if ($flashError): ?>
            <div class="bg-red-100 border border-red-300 text-red-700 px-4 py-3 rounded mb-4"><?= htmlspecialchars($flashError) ?></div>
        <?php endif; ?>

        <div class="mb-5 space-x-2">
            <?php foreach (['pending', 'approved', 'rejected', 'all'] as $f): ?>
                <a href="?status=<?= $f ?>"
                   class="px-3 py-1 rounded-full text-sm <?= $statusFilter === $f ? 'bg-green-700 text-white' : 'bg-gray-200 text-gray-700' ?>">
                    <?= ucfirst($f) ?><?= $f === 'pending' && $pendingCount > 0 ? " ($pendingCount)" : '' ?>
                </a>
            <?php endforeach; ?>
        </div>

        <?php if (!$products): ?>
            <div class="bg-white rounded-xl shadow p-8 text-center text-gray-500">
                Nothing <?= htmlspecialchars($statusFilter) ?> right now.
            </div>
        <?php else: ?>
            <div class="bg-white rounded-xl shadow overflow-hidden">
                <table class="min-w-full divide-y divide-gray-200">
                    <thead class="bg-gray-50">
                        <tr>
                            <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Product</th>
                            <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Vendor</th>
                            <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Category</th>
                            <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Price</th>
                            <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Status</th>
                            <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Action</th>
                        </tr>
                    </thead>
                    <tbody class="divide-y divide-gray-200">
                    <?php foreach ($products as $p): ?>
                        <tr>
                            <td class="px-4 py-3">
                                <div class="flex items-center space-x-3">
                                    <?php $img = productImageUrl($p['image'] ?? ''); ?>
                                    <?php if ($img): ?>
                                        <img src="<?= htmlspecialchars($img) ?>" class="w-10 h-10 rounded object-cover" alt="">
                                    <?php endif; ?>
                                    <div>
                                        <div class="font-medium text-gray-900"><?= htmlspecialchars($p['name']) ?></div>
                                        <div class="text-xs text-gray-500"><?= htmlspecialchars(mb_substr($p['description'] ?? '', 0, 60)) ?></div>
                                    </div>
                                </div>
                            </td>
                            <td class="px-4 py-3 text-sm text-gray-700"><?= htmlspecialchars($p['business_name']) ?></td>
                            <td class="px-4 py-3 text-sm text-gray-700"><?= htmlspecialchars($p['category']) ?></td>
                            <td class="px-4 py-3 text-sm text-gray-700">UGX <?= htmlspecialchars($p['price']) ?></td>
                            <td class="px-4 py-3">
                                <?php
                                $badge = ['pending' => 'bg-yellow-100 text-yellow-800',
                                          'approved' => 'bg-green-100 text-green-800',
                                          'rejected' => 'bg-red-100 text-red-800'][$p['status']] ?? 'bg-gray-100';
                                ?>
                                <span class="px-2 py-1 rounded-full text-xs font-semibold <?= $badge ?>"><?= htmlspecialchars($p['status']) ?></span>
                                <?php if ($p['status'] === 'rejected' && $p['rejection_reason']): ?>
                                    <div class="text-xs text-gray-500 mt-1"><?= htmlspecialchars($p['rejection_reason']) ?></div>
                                <?php endif; ?>
                            </td>
                            <td class="px-4 py-3">
                                <?php if ($p['status'] !== 'approved'): ?>
                                    <form method="post" class="inline">
                                        <input type="hidden" name="product_id" value="<?= (int)$p['id'] ?>">
                                        <input type="hidden" name="decision" value="approve">
                                        <button class="bg-green-700 hover:bg-green-800 text-white px-3 py-1 rounded text-xs font-semibold">Approve</button>
                                    </form>
                                <?php endif; ?>
                                <?php if ($p['status'] !== 'rejected'): ?>
                                    <form method="post" class="inline-flex items-center space-x-1 mt-1">
                                        <input type="hidden" name="product_id" value="<?= (int)$p['id'] ?>">
                                        <input type="hidden" name="decision" value="reject">
                                        <input type="text" name="rejection_reason" placeholder="Reason"
                                               class="border border-gray-300 rounded px-2 py-1 text-xs w-32">
                                        <button class="bg-red-600 hover:bg-red-700 text-white px-3 py-1 rounded text-xs font-semibold">Reject</button>
                                    </form>
                                <?php endif; ?>
                            </td>
                        </tr>
                    <?php endforeach; ?>
                    </tbody>
                </table>
            </div>
        <?php endif; ?>
    </div>
</div>
</div>
</body>
</html>
