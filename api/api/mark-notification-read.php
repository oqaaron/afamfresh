<?php
/**
 * api/mark-notification-read.php
 * AJAX endpoint to mark a notification as read
 */

session_start();
header('Content-Type: application/json');

require_once '../admin/includes/config.php';
require_once '../includes/notifications.php';

// Check if user is logged in
if (!isset($_SESSION['user_id'])) {
    http_response_code(401);
    echo json_encode(['success' => false, 'error' => 'Unauthorized']);
    exit;
}

// Get notification ID from request
$notification_id = $_POST['notification_id'] ?? $_GET['notification_id'] ?? 0;

if (!$notification_id) {
    echo json_encode(['success' => false, 'error' => 'Missing notification ID']);
    exit;
}

// Verify notification belongs to this user
$stmt = $dbh->prepare("SELECT user_id FROM user_notifications WHERE id = ?");
$stmt->execute([$notification_id]);
$notification = $stmt->fetch();

if (!$notification) {
    echo json_encode(['success' => false, 'error' => 'Notification not found']);
    exit;
}

if ($notification['user_id'] != $_SESSION['user_id']) {
    echo json_encode(['success' => false, 'error' => 'Unauthorized']);
    exit;
}

// Mark as read
$result = markNotificationAsRead($notification_id);

echo json_encode(['success' => $result]);
?>