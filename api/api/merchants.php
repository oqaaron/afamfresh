<?php
// api/merchants.php — Public Customer & Mobile Merchant Directory API
declare(strict_types=1);

header('Content-Type: application/json');
header('Access-Control-Allow-Origin: *');
header('Access-Control-Allow-Methods: GET, OPTIONS');
header('Access-Control-Allow-Headers: Content-Type, Authorization, X-CSRF-Token');

if ($_SERVER['REQUEST_METHOD'] === 'OPTIONS') {
    http_response_code(200);
    exit;
}

// Support include path whether called from /api or /api/api
if (file_exists(__DIR__ . '/admin/includes/config.php')) {
    require_once __DIR__ . '/admin/includes/config.php';
} elseif (file_exists(__DIR__ . '/../admin/includes/config.php')) {
    require_once __DIR__ . '/../admin/includes/config.php';
} elseif (file_exists(__DIR__ . '/includes/config.php')) {
    require_once __DIR__ . '/includes/config.php';
} else {
    require_once __DIR__ . '/../../admin/includes/config.php';
}

$action = $_GET['action'] ?? 'list';

/**
 * ACTION: LIST
 * Fetch active merchants, filterable by merchant_type (vendor, market_vendor, fastfood_restaurant, wholesale)
 */
if ($action === 'list') {
    $type = trim((string)($_GET['type'] ?? ''));
    $area = trim((string)($_GET['area'] ?? ''));
    $search = trim((string)($_GET['q'] ?? ''));

    $sql = "
        SELECT m.id, m.name, m.merchant_type, m.description,
               m.phone, m.email, m.logo_url, m.banner_url,
               m.address, m.area, m.latitude, m.longitude,
               m.rating, m.total_ratings, m.delivery_time_min, m.delivery_time_max
        FROM merchants m
        WHERE m.is_active = 1
    ";

    $params = [];

    if ($type !== '' && $type !== 'all') {
        $canonicalType = match($type) {
            'wholesale', 'wholesaler'                   => 'wholesale',
            'fastfood_restaurant', 'fast_food', 'food' => 'fastfood_restaurant',
            'market_vendor', 'farmer'                   => 'market_vendor',
            default                                     => 'vendor'
        };
        $sql .= " AND m.merchant_type = ?";
        $params[] = $canonicalType;
    }

    if ($area !== '') {
        $sql .= " AND m.area LIKE ?";
        $params[] = "%{$area}%";
    }

    if ($search !== '') {
        $sql .= " AND (m.name LIKE ? OR m.description LIKE ?)";
        $params[] = "%{$search}%";
        $params[] = "%{$search}%";
    }

    $sql .= " ORDER BY m.rating DESC, m.id DESC";

    try {
        $stmt = $dbh->prepare($sql);
        $stmt->execute($params);
        $merchants = $stmt->fetchAll(PDO::FETCH_ASSOC);

        // Count each merchant's approved products in PHP rather than as a
        // subquery. `items` has no is_active column, so the subquery the
        // previous version used would have thrown on every call. This also
        // gives one query per merchant rather than a correlated scan.
        foreach ($merchants as &$m) {
            $countStmt = $dbh->prepare(
                "SELECT COUNT(*) FROM items
                  WHERE merchant_id = ? AND status = 'approved'"
            );
            $countStmt->execute([(int)$m['id']]);
            $m['total_products'] = (int)$countStmt->fetchColumn();
        }
        unset($m);

        echo json_encode([
            'success'   => true,
            'count'     => count($merchants),
            'merchants' => $merchants
        ]);
    } catch (Throwable $e) {
        error_log('merchants.php list failed: ' . $e->getMessage());
        echo json_encode([
            'success' => false,
            'error'   => 'Could not load merchants right now.'
        ]);
    }
    exit;
}

/**
 * ACTION: DETAIL
 * Single merchant profile with category-grouped items
 */
if ($action === 'detail') {
    $id = intval($_GET['id'] ?? 0);
    if (!$id) {
        echo json_encode(['success' => false, 'error' => 'Missing merchant ID']);
        exit;
    }

    try {
        $mStmt = $dbh->prepare("SELECT * FROM merchants WHERE id = ? AND is_active = 1 LIMIT 1");
        $mStmt->execute([$id]);
        $merchant = $mStmt->fetch(PDO::FETCH_ASSOC);

        if (!$merchant) {
            echo json_encode(['success' => false, 'error' => 'Merchant not found']);
            exit;
        }

        // Fetch products for this merchant.
        //
        // A merchant's products can be linked two ways: directly through
        // `items.merchant_id` (this merchant's own row), or through
        // `items.vendor_id` (the operational vendor account behind the same
        // user). The two columns live in different id spaces — merchants.id
        // and vendors.id — so both must be resolved explicitly rather than
        // passing the merchant id to the vendor_id comparison and hoping the
        // numbers line up.
        $vendorStmt = $dbh->prepare("SELECT id FROM vendors WHERE user_id = ?");
        $vendorStmt->execute([$merchant['user_id'] ?? 0]);
        $vendorId = (int)($vendorStmt->fetchColumn() ?: 0);

        // The `items` table has no is_active column and no discounted_price,
        // image_url, or unit column either. The status column is the
        // enum('pending','approved','rejected') the rest of the API already
        // filters on. `quantitytype` is the size/unit label the customer
        // sees, aliased to `unit` so the response contract is unchanged.
        $iStmt = $dbh->prepare("
            SELECT id, name, description, price, image, merchant_category,
                   stock_qty, quantitytype AS unit, status
              FROM items
             WHERE (merchant_id = ? OR vendor_id = ?)
               AND status = 'approved'
             ORDER BY merchant_category ASC, id DESC
        ");
        $iStmt->execute([$id, $vendorId]);
        $items = $iStmt->fetchAll(PDO::FETCH_ASSOC);

        // The stored `image` is a bare filename. The customer-facing clients
        // need an absolute URL, so it is derived here rather than returned
        // raw — the same helper the public products endpoint uses.
        require_once __DIR__ . '/../includes/product_image.php';
        foreach ($items as &$item) {
            $item['image_url'] = productImageUrl($item['image'] ?? '');
        }
        unset($item);

        echo json_encode([
            'success'  => true,
            'merchant' => $merchant,
            'products' => $items
        ]);
    } catch (Throwable $e) {
        error_log('merchants.php detail failed: ' . $e->getMessage());
        echo json_encode(['success' => false, 'error' => 'Could not load that merchant right now.']);
    }
    exit;
}

echo json_encode(['success' => false, 'error' => 'Invalid action']);
