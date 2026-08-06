<?php
header('Content-Type: application/json');
require_once '../../admin/includes/config.php';

try {
    $stmt = $dbh->query("SELECT 
        (SELECT COUNT(*) FROM orders) as total_orders,
        (SELECT COUNT(*) FROM users) as total_users,
        (SELECT COUNT(*) FROM items) as total_products,
        (SELECT COUNT(*) FROM surplus_listings WHERE status = 'pending') as pending_surplus,
        (SELECT COUNT(*) FROM orders WHERE status = 'pending') as pending_orders,
        (SELECT COALESCE(SUM(total_amount), 0) FROM orders) as total_revenue
    ");
    $stats = $stmt->fetch(PDO::FETCH_ASSOC);
    echo json_encode(['success' => true, 'stats' => $stats]);
} catch (Exception $e) {
    echo json_encode(['success' => false, 'error' => $e->getMessage()]);
}
?>