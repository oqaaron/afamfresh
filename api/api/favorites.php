<?php
// =============================================================
// api/favorites.php — the customer's favorited products.
// =============================================================
// Backs the two heart icons (HomeScreen product cards,
// ProductDetailScreen's header) that have never done anything until now.
// Session-scoped to the signed-in customer, same action-routed shape as
// notifications.php/addresses.php.
// =============================================================

session_start();
require_once '../admin/includes/config.php';
require_once __DIR__ . '/../includes/account_type.php';
header('Content-Type: application/json');

if (!isset($_SESSION['user_id'])) {
    echo json_encode(['success' => false, 'error' => 'Not logged in']);
    exit;
}

$user_id = (int)$_SESSION['user_id'];
// Favoriting a product is a customer-app-only concept (HomeScreen/
// ProductDetailScreen are both customer-flavor screens) — no legitimate
// rider or vendor flow reaches this endpoint.
requireAccountType($dbh, $user_id, 'customer');
$action = $_GET['action'] ?? '';

if ($action === 'list') {
    $stmt = $dbh->prepare("SELECT product_id FROM product_favorites WHERE user_id = ?");
    $stmt->execute([$user_id]);
    $productIds = array_map('intval', $stmt->fetchAll(PDO::FETCH_COLUMN));
    echo json_encode(['success' => true, 'product_ids' => $productIds]);

} elseif ($action === 'toggle') {
    $productId = intval($_POST['product_id'] ?? 0);
    if ($productId === 0) {
        echo json_encode(['success' => false, 'error' => 'product_id is required']);
        exit;
    }

    try {
        $existing = $dbh->prepare("SELECT id FROM product_favorites WHERE user_id = ? AND product_id = ?");
        $existing->execute([$user_id, $productId]);

        if ($existing->fetchColumn()) {
            $dbh->prepare("DELETE FROM product_favorites WHERE user_id = ? AND product_id = ?")
                ->execute([$user_id, $productId]);
            echo json_encode(['success' => true, 'is_favorite' => false]);
        } else {
            try {
                $dbh->prepare("INSERT INTO product_favorites (user_id, product_id) VALUES (?, ?)")
                    ->execute([$user_id, $productId]);
            } catch (PDOException $e) {
                // Race: two taps landed between the SELECT above and this
                // INSERT. The UNIQUE(user_id, product_id) key rejected the
                // second one -- treat that as "already favorited", not an
                // error, same as earnLoyaltyPoints()'s idempotency guard.
                if ($e->getCode() !== '23000') {
                    throw $e;
                }
            }
            echo json_encode(['success' => true, 'is_favorite' => true]);
        }
    } catch (PDOException $e) {
        error_log('favorites.php toggle failed: ' . $e->getMessage());
        echo json_encode(['success' => false, 'error' => 'Could not update favorite.']);
    }

} else {
    echo json_encode(['success' => false, 'error' => 'Invalid action']);
}
