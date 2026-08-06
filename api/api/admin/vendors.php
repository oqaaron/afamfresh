<?php
header('Content-Type: application/json');
require_once '../../admin/includes/config.php';
session_start();

if (!isset($_SESSION['admin_logged_in']) || $_SESSION['admin_logged_in'] !== true) {
    http_response_code(401);
    echo json_encode(['success' => false, 'error' => 'Unauthorized']);
    exit;
}

$action = $_GET['action'] ?? '';

if ($action === 'list') {
    $stmt = $dbh->query("SELECT v.*, 
                         (SELECT COUNT(*) FROM vendor_products WHERE vendor_id = v.id) as total_listings
                         FROM vendors v");
    $vendors = $stmt->fetchAll(PDO::FETCH_ASSOC);
    echo json_encode(['success' => true, 'vendors' => $vendors]);
    exit;
}

if ($action === 'toggle_verification' && $_SERVER['REQUEST_METHOD'] === 'POST') {
    $input = json_decode(file_get_contents('php://input'), true);
    $id = intval($input['id'] ?? 0);
    $verified = intval($input['verified'] ?? 0);
    if (!$id) {
        echo json_encode(['success' => false, 'error' => 'Missing vendor ID']);
        exit;
    }
    $stmt = $dbh->prepare("UPDATE vendors SET is_verified = ? WHERE id = ?");
    $stmt->execute([$verified, $id]);
    echo json_encode(['success' => true]);
    exit;
}

echo json_encode(['success' => false, 'error' => 'Invalid action']);
?>