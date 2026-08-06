<?php
header('Content-Type: application/json');
require_once '../admin/includes/config.php';

$method = $_SERVER['REQUEST_METHOD'];

// Check if admin (simplified - in production, use proper admin authentication)
// For now, we'll assume this is called from admin panel

try {
    if ($method === 'POST') {
        // Verify or unverify a vendor
        $input = json_decode(file_get_contents('php://input'), true);
        
        $vendor_id = intval($input['vendor_id'] ?? 0);
        $action = trim($input['action'] ?? ''); // 'verify' or 'unverify'
        
        if ($vendor_id === 0 || empty($action)) {
            echo json_encode(['error' => 'vendor_id and action are required']);
            exit;
        }
        
        if ($action === 'verify') {
            $stmt = $dbh->prepare("
                UPDATE vendors 
                SET is_verified = TRUE, verification_date = NOW()
                WHERE id = ?
            ");
            $stmt->execute([$vendor_id]);
            
            echo json_encode(['success' => true, 'message' => 'Vendor verified successfully']);
            
        } elseif ($action === 'unverify') {
            $stmt = $dbh->prepare("
                UPDATE vendors 
                SET is_verified = FALSE, verification_date = NULL
                WHERE id = ?
            ");
            $stmt->execute([$vendor_id]);
            
            echo json_encode(['success' => true, 'message' => 'Vendor unverified successfully']);
            
        } else {
            echo json_encode(['error' => 'Invalid action. Use "verify" or "unverify"']);
        }
        
    } elseif ($method === 'GET') {
        // Get pending vendors (for admin verification queue)
        $status = isset($_GET['status']) ? trim($_GET['status']) : 'pending';
        
        if ($status === 'pending') {
            $stmt = $dbh->prepare("
                SELECT v.*, u.email, u.fname, u.lname
                FROM vendors v
                JOIN users u ON v.user_id = u.id
                WHERE v.is_verified = FALSE
                ORDER BY v.created_at ASC
            ");
        } else {
            $stmt = $dbh->prepare("
                SELECT v.*, u.email, u.fname, u.lname
                FROM vendors v
                JOIN users u ON v.user_id = u.id
                WHERE v.is_verified = TRUE
                ORDER BY v.verification_date DESC
            ");
        }
        
        $stmt->execute();
        $vendors = $stmt->fetchAll(PDO::FETCH_ASSOC);
        
        echo json_encode(['success' => true, 'vendors' => $vendors]);
        
    } else {
        echo json_encode(['error' => 'Invalid request method']);
    }
    
} catch (PDOException $e) {
    echo json_encode(['error' => 'Database error: ' . $e->getMessage()]);
}
?>
