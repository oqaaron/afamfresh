<?php
// =============================================================
// api/vendor-catalogue.php — a vendor's own products.
// =============================================================
// Vendors create products here; an admin approves them; only then can they be
// listed as Bulk. Before this, `items` was the admin catalogue alone and
// Bulk_listings.product_id has a hard foreign key to it, so a vendor could
// only ever list Bulk of something an admin had already entered.
//
// These rows live in `items` alongside the catalogue, distinguished by
// vendor_id. api/products.php filters them out of the shop: a vendor product
// has no stock or fulfilment path there, and exists so the vendor can sell
// Bulk of it.
//
// Actions
//   GET  ?action=mine     the signed-in vendor's own products, any status
//   POST ?action=create   create one, always as 'pending'
// =============================================================

header('Content-Type: application/json');
require_once '../admin/includes/config.php';
require_once __DIR__ . '/../includes/api_auth.php';
require_once __DIR__ . '/../includes/product_image.php';

$user_id = requireOwnUserId(0);   // session only; never a caller-supplied id
$action  = $_GET['action'] ?? '';

/** The vendors row for the signed-in user, or null. */
$vendorStmt = $dbh->prepare("SELECT id, is_verified FROM vendors WHERE user_id = ?");
$vendorStmt->execute([$user_id]);
$vendor = $vendorStmt->fetch(PDO::FETCH_ASSOC);

if (!$vendor) {
    echo json_encode(['success' => false, 'error' => 'This account is not a vendor.']);
    exit;
}
$vendorId = (int)$vendor['id'];

// =============================================================
// GET: the vendor's own products
// =============================================================
if ($action === 'mine' || ($action === '' && $_SERVER['REQUEST_METHOD'] === 'GET')) {
    // Every status, unlike the shop: a vendor needs to see what is pending and
    // what was rejected, or a rejection is invisible and they simply resubmit.
    // No created_at: `items` has no such column. Selecting it made this
    // endpoint a 500 on every call, which the app rendered as "no products
    // yet" -- indistinguishable from an admin not having approved anything.
    // id DESC is the ordering that is actually available, and on an
    // auto-increment key it means newest first anyway.
    $stmt = $dbh->prepare(
        "SELECT id, name, category, description, price, image, status, rejection_reason
           FROM items
          WHERE vendor_id = ?
          ORDER BY FIELD(status, 'rejected', 'pending', 'approved'), id DESC"
    );
    $stmt->execute([$vendorId]);
    $rows = $stmt->fetchAll(PDO::FETCH_ASSOC);

    foreach ($rows as &$r) {
        $r['image_url'] = productImageUrl($r['image'] ?? '');
    }
    unset($r);

    echo json_encode(['success' => true, 'products' => $rows]);
    exit;
}

// =============================================================
// POST: create a product, pending approval
// =============================================================
if ($action === 'create' && $_SERVER['REQUEST_METHOD'] === 'POST') {
    // Verification gates creating products the same way it gates listing
    // Bulk. An unverified vendor filling the catalogue with pending rows an
    // admin then has to sift is the thing verification exists to prevent.
    if ((int)$vendor['is_verified'] !== 1) {
        echo json_encode([
            'success' => false,
            'error'   => 'Your vendor account has to be verified before you can add products.',
        ]);
        exit;
    }

    // multipart, because a photo comes with it. Falls back to JSON so the
    // endpoint is still usable without one.
    $input = [];
    if (empty($_POST)) {
        $input = json_decode(file_get_contents('php://input'), true) ?: [];
    }
    $field = fn($k) => trim((string)($_POST[$k] ?? $input[$k] ?? ''));

    $name        = $field('name');
    $category    = $field('category');
    $description = $field('description');
    $price       = $field('price');
    $quantityType = $field('quantitytype') ?: 'kg';

    if ($name === '' || $category === '' || $price === '') {
        echo json_encode([
            'success' => false,
            'error'   => 'Name, category and price are all required.',
        ]);
        exit;
    }
    if (!is_numeric($price) || (float)$price <= 0) {
        echo json_encode(['success' => false, 'error' => 'Enter a valid price.']);
        exit;
    }

    // Same vendor, same product name, already waiting: almost always a double
    // submission rather than a second product.
    $dupe = $dbh->prepare(
        "SELECT id FROM items WHERE vendor_id = ? AND name = ? AND status IN ('pending','approved')"
    );
    $dupe->execute([$vendorId, $name]);
    if ($dupe->fetchColumn()) {
        echo json_encode([
            'success' => false,
            'error'   => 'You already have a product with that name.',
        ]);
        exit;
    }

    $imageName = '';
    if (!empty($_FILES['image']['name'])) {
        require_once __DIR__ . '/../includes/image_upload.php';
        $saved = saveUploadedImage($_FILES['image'], productImageDir(), 'vendor_' . $vendorId . '_');
        if (!empty($saved['error'])) {
            echo json_encode(['success' => false, 'error' => $saved['error']]);
            exit;
        }
        $imageName = $saved['filename'] ?? '';
    }

    try {
        // status is 'pending' explicitly. The column defaults to 'approved' so
        // the 72 pre-existing catalogue rows kept working through the
        // migration -- relying on that default here would publish vendor
        // products without any review at all.
        $ins = $dbh->prepare(
            "INSERT INTO items
                (vendor_id, status, name, category, description, price, quantity,
                 quantitytype, image, stock_qty)
             VALUES (?, 'pending', ?, ?, ?, ?, '0', ?, ?, 0)"
        );
        $ins->execute([
            $vendorId, $name, $category, $description, $price, $quantityType, $imageName,
        ]);

        echo json_encode([
            'success' => true,
            'product_id' => (int)$dbh->lastInsertId(),
            'message' => 'Sent for approval. You can list Bulk of it once an administrator approves it.',
        ]);
    } catch (PDOException $e) {
        error_log("vendor-catalogue create failed for vendor $vendorId: " . $e->getMessage());
        echo json_encode(['success' => false, 'error' => 'Could not save that product.']);
    }
    exit;
}

echo json_encode(['success' => false, 'error' => 'Invalid action']);
