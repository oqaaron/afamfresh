<?php
header('Content-Type: application/json');
// config.php already starts the session; calling it again raised a notice
// that was printed ahead of this file's JSON.
require_once '../../admin/includes/config.php';

// Simple auth – you can reuse admin check
if (!isset($_SESSION['admin_logged_in']) || $_SESSION['admin_logged_in'] !== true) {
    http_response_code(401);
    echo json_encode(['success' => false, 'error' => 'Unauthorized']);
    exit;
}

$action = $_GET['action'] ?? '';

if ($action === 'pending') {
    $stmt = $dbh->query("SELECT sl.*, v.business_name, i.name as product_name 
                         FROM surplus_listings sl 
                         LEFT JOIN vendors v ON sl.vendor_id = v.id 
                         LEFT JOIN items i ON sl.product_id = i.id 
                         WHERE sl.status = 'pending'");
    $listings = $stmt->fetchAll(PDO::FETCH_ASSOC);
    echo json_encode(['success' => true, 'listings' => $listings]);
    exit;
}

if (in_array($action, ['approve', 'reject']) && $_SERVER['REQUEST_METHOD'] === 'POST') {
    $input = json_decode(file_get_contents('php://input'), true);
    $id = intval($input['id'] ?? 0);
    if (!$id) {
        echo json_encode(['success' => false, 'error' => 'Missing listing ID']);
        exit;
    }
    $newStatus = $action === 'approve' ? 'approved' : 'rejected';
    $stmt = $dbh->prepare("UPDATE surplus_listings SET status = ? WHERE id = ?");
    $stmt->execute([$newStatus, $id]);
    echo json_encode(['success' => true]);
    exit;
}

echo json_encode(['success' => false, 'error' => 'Invalid action']);
?>