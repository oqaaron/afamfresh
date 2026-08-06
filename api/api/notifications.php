<?php
session_start();
require_once '../admin/includes/config.php';
require_once '../includes/notifications.php';
header('Content-Type: application/json');

if (!isset($_SESSION['user_id'])) {
    echo json_encode(['success' => false, 'error' => 'Not logged in']);
    exit;
}

$user_id = $_SESSION['user_id'];
$action = $_GET['action'] ?? '';

if ($action == 'list') {
    $notifications = getUserNotifications($user_id, 20);
    echo json_encode(['success' => true, 'notifications' => $notifications]);
    
} elseif ($action == 'unread-count') {
    $count = getUnreadNotificationCount($user_id);
    echo json_encode(['success' => true, 'count' => $count]);
    
} elseif ($action == 'mark-read') {
    $notification_id = intval($_POST['id'] ?? 0);
    $result = markNotificationAsRead($notification_id);
    echo json_encode(['success' => $result]);
    
} elseif ($action == 'mark-all-read') {
    $result = markAllNotificationsAsRead($user_id);
    echo json_encode(['success' => $result]);

} elseif ($action == 'register-token') {
    $fcmToken = trim($_POST['fcm_token'] ?? '');
    if ($fcmToken === '') {
        echo json_encode(['success' => false, 'error' => 'fcm_token is required']);
        exit;
    }

    // Overwrite, never append — a token is per-install and Firebase rotates
    // it, so any previous value is stale the instant this is called.
    $stmt = $dbh->prepare("UPDATE users SET fcm_token = ? WHERE id = ?");
    $stmt->execute([$fcmToken, $user_id]);
    echo json_encode(['success' => true]);

} else {
    echo json_encode(['success' => false, 'error' => 'Invalid action']);
}
