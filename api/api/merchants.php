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
               m.rating, m.total_ratings, m.delivery_time_min, m.delivery_time_max,
               (SELECT COUNT(*) FROM items WHERE merchant_id = m.id AND is_active = 1) AS total_products
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

        echo json_encode([
            'success'   => true,
            'count'     => count($merchants),
            'merchants' => $merchants
        ]);
    } catch (Throwable $e) {
        echo json_encode([
            'success' => false,
            'error'   => 'Database error: ' . $e->getMessage()
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

        // Fetch products for this merchant
        $iStmt = $dbh->prepare("
            SELECT id, name, description, price, discounted_price, image_url, 
                   merchant_category, stock_qty, unit, is_active
            FROM items
            WHERE (merchant_id = ? OR vendor_id = ?) AND is_active = 1
            ORDER BY merchant_category ASC, id DESC
        ");
        $iStmt->execute([$id, $merchant['user_id'] ?? 0]);
        $items = $iStmt->fetchAll(PDO::FETCH_ASSOC);

        echo json_encode([
            'success'  => true,
            'merchant' => $merchant,
            'products' => $items
        ]);
    } catch (Throwable $e) {
        echo json_encode(['success' => false, 'error' => $e->getMessage()]);
    }
    exit;
}

echo json_encode(['success' => false, 'error' => 'Invalid action']);
