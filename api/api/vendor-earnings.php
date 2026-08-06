<?php
header('Content-Type: application/json');
require_once '../admin/includes/config.php';

$method = $_SERVER['REQUEST_METHOD'];
$user_id = isset($_GET['user_id']) ? intval($_GET['user_id']) : 0;

if ($user_id === 0) {
    echo json_encode(['error' => 'user_id is required']);
    exit;
}

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
        // Fetch earnings with pagination
        $status = isset($_GET['status']) ? trim($_GET['status']) : 'all';
        $limit = isset($_GET['limit']) ? intval($_GET['limit']) : 20;
        $offset = isset($_GET['offset']) ? intval($_GET['offset']) : 0;
        
        $whereClause = "WHERE vendor_id = ?";
        $params = [$vendor_id];
        
        if ($status === 'paid') {
            $whereClause .= " AND is_paid = TRUE";
        } elseif ($status === 'pending') {
            $whereClause .= " AND is_paid = FALSE";
        }
        
        // Fetch earnings
        $stmt = $dbh->prepare("
            SELECT ve.*, o.orderid
            FROM vendor_earnings ve
            LEFT JOIN orders o ON ve.order_id = o.orderid
            $whereClause
            ORDER BY ve.created_at DESC
            LIMIT ? OFFSET ?
        ");
        $params[] = $limit;
        $params[] = $offset;
        $stmt->execute($params);
        $earnings = $stmt->fetchAll(PDO::FETCH_ASSOC);
        
        // Fetch summary
        $summaryStmt = $dbh->prepare("
            SELECT 
                COUNT(*) as total_orders,
                SUM(order_amount) as total_revenue,
                SUM(commission_amount) as total_commission,
                SUM(net_earnings) as total_net_earnings,
                SUM(CASE WHEN is_paid = TRUE THEN net_earnings ELSE 0 END) as paid_earnings,
                SUM(CASE WHEN is_paid = FALSE THEN net_earnings ELSE 0 END) as pending_earnings
            FROM vendor_earnings
            WHERE vendor_id = ?
        ");
        $summaryStmt->execute([$vendor_id]);
        $summary = $summaryStmt->fetch(PDO::FETCH_ASSOC);
        
        echo json_encode([
            'success' => true,
            'earnings' => $earnings,
            'summary' => $summary
        ]);
        
    } elseif ($method === 'POST') {
        // Request payout (mark pending earnings as paid)
        $input = json_decode(file_get_contents('php://input'), true);
        
        $earning_ids = $input['earning_ids'] ?? [];
        
        if (empty($earning_ids) || !is_array($earning_ids)) {
            echo json_encode(['error' => 'earning_ids array is required']);
            exit;
        }
        
        // Mark as paid
        $placeholders = str_repeat('?,', count($earning_ids) - 1) . '?';
        $updateStmt = $dbh->prepare("
            UPDATE vendor_earnings 
            SET is_paid = TRUE, paid_at = NOW()
            WHERE id IN ($placeholders) AND vendor_id = ? AND is_paid = FALSE
        ");
        
        $params = array_merge($earning_ids, [$vendor_id]);
        $updateStmt->execute($params);
        
        $affected = $updateStmt->rowCount();
        
        echo json_encode([
            'success' => true,
            'message' => "$affected earnings marked as paid"
        ]);
        
    } else {
        echo json_encode(['error' => 'Invalid request method']);
    }
    
} catch (PDOException $e) {
    echo json_encode(['error' => 'Database error: ' . $e->getMessage()]);
}
?>
