<?php
header('Content-Type: application/json');
require_once '../admin/includes/config.php';
require_once __DIR__ . '/../includes/api_auth.php';

$method = $_SERVER['REQUEST_METHOD'];

$isAdminSession = isset($_SESSION['admin_logged_in']) && $_SESSION['admin_logged_in'] === true;
$sessionUserId  = isset($_SESSION['user_id']) ? (int)$_SESSION['user_id'] : 0;

try {
    if ($method === 'GET') {
        // Fetch surplus orders (same as before)
        $user_id = isset($_GET['user_id']) ? intval($_GET['user_id']) : 0;
        $vendor_id = isset($_GET['vendor_id']) ? intval($_GET['vendor_id']) : 0;

        // Both filters were optional and neither was checked, so a request
        // with no parameters at all fell through to "WHERE 1=1" and returned
        // every surplus order on the platform -- addresses, phone numbers and
        // all -- to anyone who asked. Unauthenticated.
        //
        // A caller must now say whose orders they mean, and prove it is
        // theirs. Customers pass user_id; vendors pass the vendor_id of a
        // vendor record they own.
        if (!$isAdminSession) {
            if ($sessionUserId === 0) {
                http_response_code(401);
                echo json_encode(['success' => false, 'error' => 'Please sign in again.']);
                exit;
            }
            if ($vendor_id > 0) {
                $owns = $dbh->prepare("SELECT 1 FROM vendors WHERE id = ? AND user_id = ?");
                $owns->execute([$vendor_id, $sessionUserId]);
                if (!$owns->fetchColumn()) {
                    http_response_code(403);
                    echo json_encode(['success' => false, 'error' => 'Not your vendor account.']);
                    exit;
                }
            } else {
                // No vendor_id: this is a customer asking for their own
                // orders, whatever user_id they typed.
                $user_id = requireOwnUserId($user_id);
            }
        }
        $status = isset($_GET['status']) ? trim($_GET['status']) : '';
        $limit = isset($_GET['limit']) ? intval($_GET['limit']) : 20;
        $offset = isset($_GET['offset']) ? intval($_GET['offset']) : 0;
        
        $whereClause = "WHERE 1=1";
        $params = [];
        
        if ($user_id > 0) {
            $whereClause .= " AND so.user_id = ?";
            $params[] = $user_id;
        }
        
        if ($vendor_id > 0) {
            $whereClause .= " AND sl.vendor_id = ?";
            $params[] = $vendor_id;
        }
        
        if (!empty($status)) {
            $whereClause .= " AND so.status = ?";
            $params[] = $status;
        }
        
        $stmt = $dbh->prepare("
            SELECT so.*, sl.original_price, sl.discount_percent, sl.discounted_price,
                   sl.listing_type, sl.condition_rating, sl.weight_per_unit_kg,
                   i.name as product_name, i.image, sl.is_weight_based,
                   v.business_name, v.location as vendor_location,
                   u.fname as customer_fname, u.lname as customer_lname
            FROM surplus_orders so
            JOIN surplus_listings sl ON so.listing_id = sl.id
            JOIN items i ON sl.product_id = i.id
            JOIN vendors v ON sl.vendor_id = v.id
            JOIN users u ON so.user_id = u.id
            $whereClause
            ORDER BY so.created_at DESC
            LIMIT ? OFFSET ?
        ");
        $params[] = $limit;
        $params[] = $offset;
        $stmt->execute($params);
        $orders = $stmt->fetchAll(PDO::FETCH_ASSOC);
        
        echo json_encode(['success' => true, 'orders' => $orders]);
        
    } elseif ($method === 'POST') {
        // Create new surplus order with weight-based delivery
        $input = json_decode(file_get_contents('php://input'), true);
        
        $listing_id = intval($input['listing_id'] ?? 0);
        // The order is placed for whoever is signed in. This was taken from
        // the request body, so anyone could place an order in anyone else's
        // name -- against their account, to their delivery address.
        $user_id = requireOwnUserId($input['user_id'] ?? 0);
        $quantity = floatval($input['quantity'] ?? 0); // Can be decimal for kg
        $delivery_address = trim($input['delivery_address'] ?? '');
        $delivery_area = trim($input['delivery_area'] ?? '');
        $delivery_lat = isset($input['delivery_lat']) ? floatval($input['delivery_lat']) : null;
        $delivery_lng = isset($input['delivery_lng']) ? floatval($input['delivery_lng']) : null;
        $scheduled_delivery_date = isset($input['scheduled_delivery_date']) ? trim($input['scheduled_delivery_date']) : null;
        $scheduled_delivery_slot = isset($input['scheduled_delivery_slot']) ? trim($input['scheduled_delivery_slot']) : null;
        $order_notes = trim($input['order_notes'] ?? '');
        
        // Validate required fields
        if ($listing_id === 0 || $user_id === 0 || $quantity === 0) {
            echo json_encode(['error' => 'Missing required fields']);
            exit;
        }
        
        // Fetch listing details
        $listingStmt = $dbh->prepare("
            SELECT sl.*, i.is_weight_based, i.category 
            FROM surplus_listings sl
            JOIN items i ON sl.product_id = i.id
            WHERE sl.id = ? AND sl.status = 'active'
        ");
        $listingStmt->execute([$listing_id]);
        $listing = $listingStmt->fetch(PDO::FETCH_ASSOC);
        
        if (!$listing) {
            echo json_encode(['error' => 'Listing not found or not active']);
            exit;
        }
        
        // Check if enough quantity available
        if ($listing['remaining_quantity'] < $quantity) {
            echo json_encode(['error' => 'Not enough quantity available. Only ' . $listing['remaining_quantity'] . ' left']);
            exit;
        }
        
        // Calculate total weight
        $weightPerUnit = $listing['weight_per_unit_kg'] ?? 1.00;
        $totalWeightKg = $quantity * $weightPerUnit;
        
        // Check weight limit (max 1000kg / 1 tonne)
        if ($totalWeightKg > 1000) {
            echo json_encode(['error' => 'Maximum order weight is 1000kg (1 tonne). Your order weighs ' . number_format($totalWeightKg, 2) . 'kg']);
            exit;
        }
        
        // Check minimum order value
        $total_price = $listing['discounted_price'] * $quantity;
        if ($total_price < 250000) {
            echo json_encode(['error' => 'Minimum order value for surplus is UGX 250,000. Current total: UGX ' . number_format($total_price, 0)]);
            exit;
        }
        
        // Check minimum quantity for weight-based products
        if (($listing['is_weight_based'] || $listing['is_weight_based'] === 1) && $quantity < 20) {
            echo json_encode(['error' => 'Minimum order for bulk/weight-based surplus items is 20 kg']);
            exit;
        }
        
        // Calculate delivery fee based on weight
        $delivery_fee = 0;
        $delivery_fee_breakdown = [];
        
        if (!$listing['pickup_only'] && !empty($delivery_address)) {
            // Get delivery settings
            $settingsStmt = $dbh->query("SELECT * FROM surplus_delivery_settings LIMIT 1");
            $settings = $settingsStmt->fetch(PDO::FETCH_ASSOC);
            
            $base_fee = $settings['base_fee'] ?? 5000;
            $fee_per_kg = $settings['fee_per_kg'] ?? 500;
            $free_delivery_threshold = $settings['free_delivery_threshold'] ?? 500000;
            
            // Calculate delivery fee based on weight
            if ($total_price >= $free_delivery_threshold) {
                $delivery_fee = 0;
                $delivery_fee_breakdown = ['type' => 'free', 'reason' => 'Order exceeds free delivery threshold'];
            } else {
                // Weight-based calculation: base fee + (weight in kg × fee per kg)
                $delivery_fee = $base_fee + ($totalWeightKg * $fee_per_kg);
                
                // Cap maximum delivery fee (optional)
                $max_delivery_fee = 50000;
                if ($delivery_fee > $max_delivery_fee) {
                    $delivery_fee = $max_delivery_fee;
                }
                
                $delivery_fee_breakdown = [
                    'type' => 'weight_based',
                    'base_fee' => $base_fee,
                    'weight_kg' => $totalWeightKg,
                    'fee_per_kg' => $fee_per_kg,
                    'weight_charge' => $totalWeightKg * $fee_per_kg,
                    'total_fee' => $delivery_fee
                ];
            }
        }
        
        // Generate pickup code if pickup-only
        $pickup_code = null;
        if ($listing['pickup_only']) {
            $pickup_code = strtoupper(substr(md5(uniqid() . $listing_id . $user_id), 0, 8));
        }
        
        // Insert order
        $stmt = $dbh->prepare("
            INSERT INTO surplus_orders 
            (listing_id, user_id, quantity, total_price, total_weight_kg, status, 
             delivery_address, delivery_area, delivery_lat, delivery_lng, 
             delivery_fee, delivery_fee_breakdown, pickup_code, 
             scheduled_delivery_date, scheduled_delivery_slot, order_notes)
            VALUES (?, ?, ?, ?, ?, 'pending', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ");
        
        $result = $stmt->execute([
            $listing_id,
            $user_id,
            $quantity,
            $total_price,
            $totalWeightKg,
            $delivery_address ?: null,
            $delivery_area ?: null,
            $delivery_lat,
            $delivery_lng,
            $delivery_fee,
            json_encode($delivery_fee_breakdown),
            $pickup_code,
            $scheduled_delivery_date ?: null,
            $scheduled_delivery_slot ?: null,
            $order_notes ?: null
        ]);
        
        if ($result) {
            $order_id = $dbh->lastInsertId();
            
            // Update listing remaining quantity
            $updateStmt = $dbh->prepare("
                UPDATE surplus_listings 
                SET remaining_quantity = remaining_quantity - ?
                WHERE id = ?
            ");
            $updateStmt->execute([$quantity, $listing_id]);
            
            // Check if listing is now sold out
            $checkStmt = $dbh->prepare("SELECT remaining_quantity FROM surplus_listings WHERE id = ?");
            $checkStmt->execute([$listing_id]);
            $remaining = $checkStmt->fetch(PDO::FETCH_ASSOC)['remaining_quantity'];
            
            if ($remaining == 0) {
                $soldStmt = $dbh->prepare("UPDATE surplus_listings SET status = 'sold' WHERE id = ?");
                $soldStmt->execute([$listing_id]);
            }
            
            // Fetch created order
            $fetchStmt = $dbh->prepare("SELECT * FROM surplus_orders WHERE id = ?");
            $fetchStmt->execute([$order_id]);
            $order = $fetchStmt->fetch(PDO::FETCH_ASSOC);
            
            echo json_encode([
                'success' => true,
                'message' => 'Surplus order created successfully',
                'order' => $order,
                'delivery_fee' => $delivery_fee,
                'total_weight_kg' => $totalWeightKg,
                'grand_total' => $total_price + $delivery_fee
            ]);
        } else {
            echo json_encode(['error' => 'Failed to create surplus order']);
        }
        
    } elseif ($method === 'PUT') {
        // Update surplus order status (same as before)
        $input = json_decode(file_get_contents('php://input'), true);
        
        $order_id = intval($input['order_id'] ?? 0);
        $status = trim($input['status'] ?? '');
        
        if ($order_id === 0 || empty($status)) {
            echo json_encode(['error' => 'order_id and status are required']);
            exit;
        }
        
        $valid_statuses = ['pending', 'confirmed', 'processing', 'ready', 'delivered', 'cancelled', 'refunded'];
        if (!in_array($status, $valid_statuses)) {
            echo json_encode(['error' => 'Invalid status']);
            exit;
        }
        
        $updateFields = ["status = ?", "updated_at = NOW()"];
        $params = [$status, $order_id];
        
        if ($status === 'confirmed') {
            $updateFields[] = "confirmed_at = NOW()";
        } elseif ($status === 'delivered') {
            $updateFields[] = "delivered_at = NOW()";
        }
        
        $sql = "UPDATE surplus_orders SET " . implode(', ', $updateFields) . " WHERE id = ?";
        $stmt = $dbh->prepare($sql);
        $stmt->execute($params);
        
        echo json_encode(['success' => true, 'message' => 'Order status updated successfully']);
        
    } else {
        echo json_encode(['error' => 'Invalid request method']);
    }
    
} catch (PDOException $e) {
    echo json_encode(['error' => 'Database error: ' . $e->getMessage()]);
}
?>