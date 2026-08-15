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
        // Fetch notifications
        $unread_only = isset($_GET['unread_only']) && $_GET['unread_only'] === 'true';
        $limit = isset($_GET['limit']) ? intval($_GET['limit']) : 20;
        $offset = isset($_GET['offset']) ? intval($_GET['offset']) : 0;
        
        $whereClause = "WHERE vendor_id = ?";
        $params = [$vendor_id];
        
        if ($unread_only) {
            $whereClause .= " AND is_read = FALSE";
        }
        
        $stmt = $dbh->prepare("
            SELECT * FROM vendor_notifications
            $whereClause
            ORDER BY created_at DESC
            LIMIT ? OFFSET ?
        ");
        $params[] = $limit;
        $params[] = $offset;
        $stmt->execute($params);
        $notifications = $stmt->fetchAll(PDO::FETCH_ASSOC);
        
        // Count unread
        $countStmt = $dbh->prepare("SELECT COUNT(*) as count FROM vendor_notifications WHERE vendor_id = ? AND is_read = FALSE");
        $countStmt->execute([$vendor_id]);
        $unreadCount = $countStmt->fetch(PDO::FETCH_ASSOC)['count'];
        
        echo json_encode([
            'success' => true,
            'notifications' => $notifications,
            'unread_count' => $unreadCount
        ]);
        
    } elseif ($method === 'POST') {
        // Mark notification as read
        $input = json_decode(file_get_contents('php://input'), true);
        
        $notification_id = intval($input['notification_id'] ?? 0);
        
        if ($notification_id === 0) {
            echo json_encode(['error' => 'notification_id is required']);
            exit;
        }
        
        $stmt = $dbh->prepare("
            UPDATE vendor_notifications 
            SET is_read = TRUE
            WHERE id = ? AND vendor_id = ?
        ");
        $stmt->execute([$notification_id, $vendor_id]);
        
        echo json_encode(['success' => true, 'message' => 'Notification marked as read']);
        
    } elseif ($method === 'PUT') {
        // Mark all as read
        $stmt = $dbh->prepare("
            UPDATE vendor_notifications 
            SET is_read = TRUE
            WHERE vendor_id = ? AND is_read = FALSE
        ");
        $stmt->execute([$vendor_id]);
        
        $affected = $stmt->rowCount();
        
        echo json_encode(['success' => true, 'message' => "$affected notifications marked as read"]);
        
    } else {
        echo json_encode(['error' => 'Invalid request method']);
    }
    
} catch (PDOException $e) {
    error_log('vendor-notifications.php error: ' . $e->getMessage());
    echo json_encode(['error' => 'A database error occurred. Please try again.']);
}
?>
