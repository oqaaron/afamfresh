<?php
header('Content-Type: application/json');
require_once '../admin/includes/config.php';

$method = $_SERVER['REQUEST_METHOD'];

try {
    if ($method === 'GET') {
        // Fetch approved surplus listings (public)
        $vendor_id = isset($_GET['vendor_id']) ? intval($_GET['vendor_id']) : 0;
        $status = isset($_GET['status']) ? trim($_GET['status']) : 'approved'; // default to approved
        $listing_type = isset($_GET['listing_type']) ? trim($_GET['listing_type']) : '';
        $limit = isset($_GET['limit']) ? intval($_GET['limit']) : 20;
        $offset = isset($_GET['offset']) ? intval($_GET['offset']) : 0;
        
        $whereClause = "WHERE sl.status = ? AND sl.remaining_quantity > 0 AND sl.expiry_date > NOW()";
        $params = [$status];
        
        if ($vendor_id > 0) {
            $whereClause .= " AND sl.vendor_id = ?";
            $params[] = $vendor_id;
        }
        if (!empty($listing_type)) {
            $whereClause .= " AND sl.listing_type = ?";
            $params[] = $listing_type;
        }
        
        $stmt = $dbh->prepare("
            SELECT sl.*, v.business_name, v.location as vendor_location,
                   i.name as product_name, i.category, i.image,
                   u.fname as vendor_fname, u.lname as vendor_lname
            FROM surplus_listings sl
            JOIN vendors v ON sl.vendor_id = v.id
            JOIN items i ON sl.product_id = i.id
            JOIN users u ON v.user_id = u.id
            $whereClause
            ORDER BY sl.discount_percent DESC, sl.created_at ASC
            LIMIT ? OFFSET ?
        ");
        $params[] = $limit;
        $params[] = $offset;
        $stmt->execute($params);
        $listings = $stmt->fetchAll(PDO::FETCH_ASSOC);
        
        echo json_encode(['success' => true, 'listings' => $listings]);
        
    } elseif ($method === 'POST') {
        // Create new surplus listing (vendor submits, sets status = 'pending')
        $input = json_decode(file_get_contents('php://input'), true);
        
        // Whoever is signed in is the vendor listing this. Taken from the body
        // before, so the is_verified check below could be satisfied by naming
        // a verified vendor's user_id and listing stock in their name.
        require_once __DIR__ . '/../includes/api_auth.php';
        $user_id = requireOwnUserId($input['user_id'] ?? 0);
        $product_id = intval($input['product_id'] ?? 0);
        $original_price = floatval($input['original_price'] ?? 0);
        $discount_percent = floatval($input['discount_percent'] ?? 0);
        $surplus_quantity = floatval($input['surplus_quantity'] ?? 0);
        $expiry_date = trim($input['expiry_date'] ?? '');
        $listing_type = trim($input['listing_type'] ?? 'goodie_bag');
        $description = trim($input['description'] ?? '');
        $condition_rating = trim($input['condition_rating'] ?? 'good');
        $pickup_only = isset($input['pickup_only']) ? (bool)$input['pickup_only'] : false;
        $weight_per_unit_kg = floatval($input['weight_per_unit_kg'] ?? 1.00);
        $is_weight_based = isset($input['is_weight_based']) ? (bool)$input['is_weight_based'] : true;
        
        // Validate
        if ($user_id === 0 || $product_id === 0 || $original_price === 0 || $discount_percent === 0 || $surplus_quantity === 0 || empty($expiry_date)) {
            echo json_encode(['error' => 'Missing required fields']);
            exit;
        }
        if ($discount_percent < 30 || $discount_percent > 70) {
            echo json_encode(['error' => 'Discount must be between 30% and 70%']);
            exit;
        }
        
        // Get vendor_id from user_id
        $vendorStmt = $dbh->prepare("SELECT id FROM vendors WHERE user_id = ? AND is_verified = TRUE");
        $vendorStmt->execute([$user_id]);
        $vendor = $vendorStmt->fetch(PDO::FETCH_ASSOC);
        if (!$vendor) {
            echo json_encode(['error' => 'Verified vendor not found']);
            exit;
        }
        $vendor_id = $vendor['id'];
        
        $discounted_price = $original_price * (1 - ($discount_percent / 100));
        
        $stmt = $dbh->prepare("
            INSERT INTO surplus_listings 
            (vendor_id, product_id, original_price, discount_percent, discounted_price, 
             surplus_quantity, remaining_quantity, expiry_date, listing_type, description, 
             condition_rating, pickup_only, weight_per_unit_kg, is_weight_based, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'pending')
        ");
        $result = $stmt->execute([
            $vendor_id, $product_id, $original_price, $discount_percent, $discounted_price,
            $surplus_quantity, $surplus_quantity, $expiry_date, $listing_type, $description,
            $condition_rating, $pickup_only, $weight_per_unit_kg, $is_weight_based
        ]);
        
        if ($result) {
            $listing_id = $dbh->lastInsertId();
            $fetchStmt = $dbh->prepare("SELECT * FROM surplus_listings WHERE id = ?");
            $fetchStmt->execute([$listing_id]);
            $listing = $fetchStmt->fetch(PDO::FETCH_ASSOC);
            echo json_encode([
                'success' => true,
                'message' => 'Surplus listing submitted for approval',
                'listing' => $listing
            ]);
        } else {
            echo json_encode(['error' => 'Failed to create surplus listing']);
        }
        
    } elseif ($method === 'PUT') {
        // Update surplus listing (status, quantity, admin notes) – admin only in practice
        $input = json_decode(file_get_contents('php://input'), true);
        $listing_id = intval($input['listing_id'] ?? 0);
        $status = isset($input['status']) ? trim($input['status']) : null;
        $remaining_quantity = isset($input['remaining_quantity']) ? floatval($input['remaining_quantity']) : null;
        $admin_notes = isset($input['admin_notes']) ? trim($input['admin_notes']) : null;
        
        if ($listing_id === 0) {
            echo json_encode(['error' => 'listing_id is required']);
            exit;
        }
        $updateFields = [];
        $params = [];
        if ($status !== null) {
            $updateFields[] = "status = ?";
            $params[] = $status;
        }
        if ($remaining_quantity !== null) {
            $updateFields[] = "remaining_quantity = ?";
            $params[] = $remaining_quantity;
        }
        if ($admin_notes !== null) {
            $updateFields[] = "admin_notes = ?";
            $params[] = $admin_notes;
        }
        if (empty($updateFields)) {
            echo json_encode(['error' => 'No fields to update']);
            exit;
        }
        $updateFields[] = "updated_at = NOW()";
        $params[] = $listing_id;
        $sql = "UPDATE surplus_listings SET " . implode(', ', $updateFields) . " WHERE id = ?";
        $stmt = $dbh->prepare($sql);
        $stmt->execute($params);
        echo json_encode(['success' => true, 'message' => 'Listing updated successfully']);
        
    } elseif ($method === 'DELETE') {
        // Cancel surplus listing (soft delete = set status to 'cancelled')
        $listing_id = intval($_GET['listing_id'] ?? 0);
        if ($listing_id === 0) {
            echo json_encode(['error' => 'listing_id is required']);
            exit;
        }
        $stmt = $dbh->prepare("UPDATE surplus_listings SET status = 'cancelled', updated_at = NOW() WHERE id = ?");
        $stmt->execute([$listing_id]);
        echo json_encode(['success' => true, 'message' => 'Listing cancelled successfully']);
        
    } else {
        echo json_encode(['error' => 'Invalid request method']);
    }
} catch (PDOException $e) {
    echo json_encode(['error' => 'Database error: ' . $e->getMessage()]);
}
?>