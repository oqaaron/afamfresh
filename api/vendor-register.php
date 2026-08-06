<?php
header('Content-Type: application/json');
require_once '../admin/includes/config.php';

// Get POST data
$input = json_decode(file_get_contents('php://input'), true);

if (!$input) {
    echo json_encode(['error' => 'Invalid input']);
    exit;
}

$business_name = trim($input['business_name'] ?? '');
$business_type = trim($input['business_type'] ?? 'market_vendor');
$phone = trim($input['phone'] ?? '');
$email = trim($input['email'] ?? '');
$location = trim($input['location'] ?? '');
$market_stall = trim($input['market_stall'] ?? '');
$user_id = intval($input['user_id'] ?? 0);

// Validate required fields
if (empty($business_name) || empty($phone) || $user_id === 0) {
    echo json_encode(['error' => 'Business name, phone, and user_id are required']);
    exit;
}

// Validate business type
$valid_types = ['farmer', 'market_vendor', 'wholesaler'];
if (!in_array($business_type, $valid_types)) {
    echo json_encode(['error' => 'Invalid business type']);
    exit;
}

try {
    // Check if user exists
    $userCheck = $dbh->prepare("SELECT id, email FROM users WHERE id = ?");
    $userCheck->execute([$user_id]);
    $user = $userCheck->fetch(PDO::FETCH_ASSOC);
    
    if (!$user) {
        echo json_encode(['error' => 'User not found']);
        exit;
    }
    
    // Check if vendor already exists for this user
    $vendorCheck = $dbh->prepare("SELECT id FROM vendors WHERE user_id = ?");
    $vendorCheck->execute([$user_id]);
    
    if ($vendorCheck->fetch()) {
        echo json_encode(['error' => 'Vendor already registered for this user']);
        exit;
    }
    
    // Insert vendor
    $stmt = $dbh->prepare("
        INSERT INTO vendors (user_id, business_name, business_type, phone, email, location, market_stall)
        VALUES (?, ?, ?, ?, ?, ?, ?)
    ");
    
    $result = $stmt->execute([
        $user_id,
        $business_name,
        $business_type,
        $phone,
        $email ?: null,
        $location ?: null,
        $market_stall ?: null
    ]);
    
    if ($result) {
        $vendor_id = $dbh->lastInsertId();
        
        // Fetch created vendor
        $vendorStmt = $dbh->prepare("SELECT * FROM vendors WHERE id = ?");
        $vendorStmt->execute([$vendor_id]);
        $vendor = $vendorStmt->fetch(PDO::FETCH_ASSOC);
        
        echo json_encode([
            'success' => true,
            'message' => 'Vendor registered successfully',
            'vendor' => $vendor
        ]);
    } else {
        echo json_encode(['error' => 'Failed to register vendor']);
    }
    
} catch (PDOException $e) {
    echo json_encode(['error' => 'Database error: ' . $e->getMessage()]);
}
?>
