<?php
session_start();
require_once '../admin/includes/config.php';
require_once __DIR__ . '/../includes/product_image.php';
header('Content-Type: application/json');

$action = $_GET['action'] ?? '';

/**
 * Adds an absolute `image_url` to a product row.
 *
 * The raw `image` column is a bare filename ("apple.png"). It is passed
 * through unchanged because the installed app still reads that key, but
 * Coil cannot resolve a relative name — which is why product images have
 * never rendered on the device. `image_url` is the resolvable one, and is
 * null when the file is absent from disk so the app draws its placeholder
 * rather than retrying a 404 on every scroll.
 */
function withImageUrl($row) {
    $row['image_url'] = productImageUrl($row['image'] ?? null);
    return $row;
}

if ($action == 'list') {
    $category = $_GET['category'] ?? '';
    $search = $_GET['search'] ?? '';
    
    $sql = "SELECT * FROM items WHERE 1=1";
    $params = [];
    
    if (!empty($category)) {
        $sql .= " AND category = ?";
        $params[] = $category;
    }
    
    if (!empty($search)) {
        // `itemname` does not exist — the column is `name`. This threw an
        // uncaught PDOException (SQLSTATE 42S22), so any search request
        // returned a PHP fatal error page instead of JSON.
        $sql .= " AND (name LIKE ? OR description LIKE ?)";
        $searchTerm = "%$search%";
        $params[] = $searchTerm;
        $params[] = $searchTerm;
    }
    
    $sql .= " ORDER BY id DESC";
    
    $stmt = $dbh->prepare($sql);
    $stmt->execute($params);
    $products = $stmt->fetchAll(PDO::FETCH_ASSOC);
    
    echo json_encode(['success' => true, 'products' => array_map('withImageUrl', $products)]);
    
} elseif ($action == 'categories') {
    $stmt = $dbh->prepare("SELECT DISTINCT category FROM items ORDER BY category");
    $stmt->execute();
    $categories = $stmt->fetchAll(PDO::FETCH_COLUMN);
    
    echo json_encode(['success' => true, 'categories' => $categories]);
    
} elseif ($action == 'detail') {
    $id = intval($_GET['id'] ?? 0);
    
    $stmt = $dbh->prepare("SELECT * FROM items WHERE id = ?");
    $stmt->execute([$id]);
    $product = $stmt->fetch(PDO::FETCH_ASSOC);
    
    if ($product) {
        echo json_encode(['success' => true, 'product' => withImageUrl($product)]);
    } else {
        echo json_encode(['success' => false, 'error' => 'Product not found']);
    }
} else {
    echo json_encode(['success' => false, 'error' => 'Invalid action']);
}
