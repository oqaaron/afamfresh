<?php
header('Content-Type: application/json');
require_once '../admin/includes/config.php';

$method = $_SERVER['REQUEST_METHOD'];

try {
    if ($method === 'GET') {
        // Fetch all verified vendors
        $business_type = isset($_GET['business_type']) ? trim($_GET['business_type']) : '';
        $location = isset($_GET['location']) ? trim($_GET['location']) : '';
        $limit = isset($_GET['limit']) ? intval($_GET['limit']) : 20;
        $offset = isset($_GET['offset']) ? intval($_GET['offset']) : 0;
        
        $whereClause = "WHERE v.is_verified = TRUE";
        $params = [];
        
        if (!empty($business_type)) {
            $whereClause .= " AND v.business_type = ?";
            $params[] = $business_type;
        }
        
        if (!empty($location)) {
            $whereClause .= " AND v.location LIKE ?";
            $params[] = "%$location%";
        }
        
        $stmt = $dbh->prepare("
            SELECT v.*, u.email, u.fname, u.lname,
                   (SELECT COUNT(*) FROM vendor_products WHERE vendor_id = v.id AND is_active = TRUE) as product_count
            FROM vendors v
            JOIN users u ON v.user_id = u.id
            $whereClause
            ORDER BY v.rating DESC, v.total_sales DESC
            LIMIT ? OFFSET ?
        ");
        
        $params[] = $limit;
        $params[] = $offset;
        $stmt->execute($params);
        $vendors = $stmt->fetchAll(PDO::FETCH_ASSOC);
        
        echo json_encode(['success' => true, 'vendors' => $vendors]);
        
    } else {
        echo json_encode(['error' => 'Invalid request method']);
    }
    
} catch (PDOException $e) {
    error_log('vendor-listings.php error: ' . $e->getMessage());
    echo json_encode(['error' => 'A database error occurred. Please try again.']);
}
?>
