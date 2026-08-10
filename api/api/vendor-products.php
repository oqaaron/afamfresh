<?php
header('Content-Type: application/json');
require_once '../admin/includes/config.php';

require_once __DIR__ . '/../includes/api_auth.php';

$method = $_SERVER['REQUEST_METHOD'];

// Was: trust $_GET['user_id']. Anyone could read any vendor's
// records by changing the number. See includes/api_auth.php.
$user_id = requireOwnUserId($_GET['user_id'] ?? 0);

try {
    // Get vendor_id from user_id
    $vendorStmt = $dbh->prepare("SELECT id FROM vendors WHERE user_id = ?");
    $vendorStmt->execute([$user_id]);
    $vendor = $vendorStmt->fetch(PDO::FETCH_ASSOC);
    
    if (!$vendor) {
        echo json_encode(['error' => 'Vendor not found']);
        exit;
    }
    
    $vendor_id = $vendor['id'];
    
    if ($method === 'GET') {
        // Fetch all vendor products
        $stmt = $dbh->prepare("
            SELECT vp.*, i.name as product_name, i.category, i.description, i.image, i.quantitytype
            FROM vendor_products vp
            JOIN items i ON vp.product_id = i.id
            WHERE vp.vendor_id = ?
            ORDER BY vp.created_at DESC
        ");
        $stmt->execute([$vendor_id]);
        $products = $stmt->fetchAll(PDO::FETCH_ASSOC);
        
        echo json_encode(['success' => true, 'products' => $products]);
        
    } elseif ($method === 'POST') {
        // Add product to vendor inventory
        $input = json_decode(file_get_contents('php://input'), true);
        
        $product_id = intval($input['product_id'] ?? 0);
        $stock_quantity = intval($input['stock_quantity'] ?? 0);
        $price = isset($input['price']) ? floatval($input['price']) : null;
        
        if ($product_id === 0) {
            echo json_encode(['error' => 'product_id is required']);
            exit;
        }
        
        // Check if product exists
        $productCheck = $dbh->prepare("SELECT id FROM items WHERE id = ?");
        $productCheck->execute([$product_id]);
        
        if (!$productCheck->fetch()) {
            echo json_encode(['error' => 'Product not found']);
            exit;
        }
        
        // Check if vendor already has this product
        $existingCheck = $dbh->prepare("SELECT id FROM vendor_products WHERE vendor_id = ? AND product_id = ?");
        $existingCheck->execute([$vendor_id, $product_id]);
        
        if ($existingCheck->fetch()) {
            // Update existing
            $updateStmt = $dbh->prepare("
                UPDATE vendor_products 
                SET stock_quantity = ?, price = ?, is_active = TRUE, updated_at = NOW()
                WHERE vendor_id = ? AND product_id = ?
            ");
            $updateStmt->execute([$stock_quantity, $price, $vendor_id, $product_id]);
            
            echo json_encode(['success' => true, 'message' => 'Product updated successfully']);
        } else {
            // Insert new
            $insertStmt = $dbh->prepare("
                INSERT INTO vendor_products (vendor_id, product_id, stock_quantity, price)
                VALUES (?, ?, ?, ?)
            ");
            $insertStmt->execute([$vendor_id, $product_id, $stock_quantity, $price]);
            
            echo json_encode(['success' => true, 'message' => 'Product added successfully']);
        }
        
    } elseif ($method === 'DELETE') {
        // Remove product from vendor inventory
        $product_id = intval($_GET['product_id'] ?? 0);
        
        if ($product_id === 0) {
            echo json_encode(['error' => 'product_id is required']);
            exit;
        }
        
        $stmt = $dbh->prepare("
            UPDATE vendor_products 
            SET is_active = FALSE, updated_at = NOW()
            WHERE vendor_id = ? AND product_id = ?
        ");
        $stmt->execute([$vendor_id, $product_id]);
        
        echo json_encode(['success' => true, 'message' => 'Product removed successfully']);
        
    } else {
        echo json_encode(['error' => 'Invalid request method']);
    }
    
} catch (PDOException $e) {
    echo json_encode(['error' => 'Database error: ' . $e->getMessage()]);
}
?>
