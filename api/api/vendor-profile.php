<?php
header('Content-Type: application/json');
require_once '../admin/includes/config.php';

// Get user_id from query parameter or POST
$user_id = isset($_GET['user_id']) ? intval($_GET['user_id']) : (isset($_POST['user_id']) ? intval($_POST['user_id']) : 0);

if ($user_id === 0) {
    echo json_encode(['error' => 'user_id is required']);
    exit;
}

try {
    // Fetch vendor profile
    $stmt = $dbh->prepare("
        SELECT v.*, u.email as user_email, u.fname, u.lname
        FROM vendors v
        JOIN users u ON v.user_id = u.id
        WHERE v.user_id = ?
    ");
    $stmt->execute([$user_id]);
    $vendor = $stmt->fetch(PDO::FETCH_ASSOC);
    
    if (!$vendor) {
        echo json_encode(['error' => 'Vendor not found']);
        exit;
    }
    
    // Fetch vendor products
    $productsStmt = $dbh->prepare("
        SELECT vp.*, i.name as product_name, i.category, i.image
        FROM vendor_products vp
        JOIN items i ON vp.product_id = i.id
        WHERE vp.vendor_id = ? AND vp.is_active = TRUE
    ");
    $productsStmt->execute([$vendor['id']]);
    $products = $productsStmt->fetchAll(PDO::FETCH_ASSOC);
    
    // Fetch vendor earnings summary
    $earningsStmt = $dbh->prepare("
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
    $earningsStmt->execute([$vendor['id']]);
    $earnings = $earningsStmt->fetch(PDO::FETCH_ASSOC);
    
    // Fetch recent reviews
    $reviewsStmt = $dbh->prepare("
        SELECT vr.*, u.fname as reviewer_name
        FROM vendor_reviews vr
        JOIN users u ON vr.user_id = u.id
        WHERE vr.vendor_id = ? AND vr.is_approved = TRUE
        ORDER BY vr.created_at DESC
        LIMIT 5
    ");
    $reviewsStmt->execute([$vendor['id']]);
    $reviews = $reviewsStmt->fetchAll(PDO::FETCH_ASSOC);
    
    echo json_encode([
        'success' => true,
        'vendor' => $vendor,
        'products' => $products,
        'earnings' => $earnings,
        'reviews' => $reviews
    ]);
    
} catch (PDOException $e) {
    echo json_encode(['error' => 'Database error: ' . $e->getMessage()]);
}
?>
