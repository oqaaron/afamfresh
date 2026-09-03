<?php
require_once __DIR__ . '/../admin/includes/config.php';

/**
 * Add a notification for a user.
 *
 * Writes the in-app record AND queues the other channels. Routing it here
 * rather than rewriting each call site means helpers gain push without being
 * touched, and anything written later gets it by default[cite: 12].
 *
 * @param int $userId - User ID from users table[cite: 12]
 * @param string $title - Notification title[cite: 12]
 * @param string $message - Notification message[cite: 12]
 * @param string $type - Notification type (order, promo, system, etc.)[cite: 12]
 * @param string|null $link - Optional link to relevant page[cite: 12]
 * @param array $channels - Beyond in-app: 'push', 'email' and/or 'sms'[cite: 12].
 * @param array $extraData - Raw keys merged into the FCM data payload
 *                          alongside 'link' (e.g. ['order_id'=>'123',
 *                          'source'=>'order', 'is_urgent'=>'true'])[cite: 12].
 * @return bool - Success status[cite: 12]
 */
function addNotification($userId, $title, $message, $type = 'system', $link = null, array $channels = ['push'], array $extraData = []) {
    try {
        // Automatically determine urgency for Heads-Up popup banner if not explicitly specified
        if (!isset($extraData['is_urgent'])) {
            $urgentKeywords = ['arrived', 'arriving', 'pickup code', 'collection code', 'out for delivery', 'dispatch'];
            $haystack = strtolower($title . ' ' . $message);
            $isUrgent = false;
            foreach ($urgentKeywords as $kw) {
                if (strpos($haystack, $kw) !== false) {
                    $isUrgent = true;
                    break;
                }
            }
            $extraData['is_urgent'] = $isUrgent ? 'true' : 'false';
        }

        $event = new NotificationEvent(
            (int)$userId,
            $type,
            $title,
            $message,
            array_merge($link !== null ? ['link' => $link] : [], $extraData),[cite: 12]
            $channels
        );

        $manager = new NotificationManager();
        return $manager->send($event) > 0;[cite: 12]
    } catch (Throwable $e) {
        error_log("Add notification error: " . $e->getMessage());[cite: 12]
        return false;[cite: 12]
    }
}

/**
 * Get unread notification count for a user
 * @param int $userId - User ID
 * @return int - Unread count[cite: 12]
 */
function getUnreadNotificationCount($userId) {
    global $dbh;
    try {
        $sql = "SELECT COUNT(*) as count FROM user_notifications WHERE user_id = :user_id AND is_read = 0";[cite: 12]
        $query = $dbh->prepare($sql);[cite: 12]
        $query->bindParam(':user_id', $userId, PDO::PARAM_INT);[cite: 12]
        $query->execute();[cite: 12]
        $result = $query->fetch(PDO::FETCH_ASSOC);[cite: 12]
        return (int)$result['count'];[cite: 12]
    } catch (PDOException $e) {
        error_log("Get unread count error: " . $e->getMessage());[cite: 12]
        return 0;[cite: 12]
    }
}

/**
 * Get all notifications for a user
 * @param int $userId - User ID
 * @param int $limit - Maximum number of notifications to return
 * @return array - Notifications[cite: 12]
 */
function getUserNotifications($userId, $limit = 50) {
    global $dbh;
    try {
        $sql = "SELECT * FROM user_notifications WHERE user_id = :user_id ORDER BY created_at DESC LIMIT :limit";[cite: 12]
        $query = $dbh->prepare($sql);[cite: 12]
        $query->bindParam(':user_id', $userId, PDO::PARAM_INT);[cite: 12]
        $query->bindParam(':limit', $limit, PDO::PARAM_INT);[cite: 12]
        $query->execute();[cite: 12]
        return $query->fetchAll(PDO::FETCH_ASSOC);[cite: 12]
    } catch (PDOException $e) {
        error_log("Get notifications error: " . $e->getMessage());[cite: 12]
        return [];[cite: 12]
    }
}

/**
 * Get recent notifications for a user (last 7 days)
 * @param int $userId - User ID
 * @param int $limit - Maximum number of notifications
 * @return array - Recent notifications[cite: 12]
 */
function getRecentUserNotifications($userId, $limit = 10) {
    global $dbh;
    try {
        $sql = "SELECT * FROM user_notifications 
                WHERE user_id = :user_id 
                AND created_at > DATE_SUB(NOW(), INTERVAL 7 DAY)
                ORDER BY created_at DESC 
                LIMIT :limit";[cite: 12]
        $query = $dbh->prepare($sql);[cite: 12]
        $query->bindParam(':user_id', $userId, PDO::PARAM_INT);[cite: 12]
        $query->bindParam(':limit', $limit, PDO::PARAM_INT);[cite: 12]
        $query->execute();[cite: 12]
        return $query->fetchAll(PDO::FETCH_ASSOC);[cite: 12]
    } catch (PDOException $e) {
        error_log("Get recent notifications error: " . $e->getMessage());[cite: 12]
        return [];[cite: 12]
    }
}

/**
 * Mark a notification as read.
 * @param int $notificationId - Notification ID[cite: 12]
 * @param int $userId - The signed-in caller's own id[cite: 12]
 * @return bool - Success status[cite: 12]
 */
function markNotificationAsRead($notificationId, $userId) {
    global $dbh;
    try {
        $sql = "UPDATE user_notifications SET is_read = 1 WHERE id = :id AND user_id = :user_id";[cite: 12]
        $query = $dbh->prepare($sql);[cite: 12]
        $query->bindParam(':id', $notificationId, PDO::PARAM_INT);[cite: 12]
        $query->bindParam(':user_id', $userId, PDO::PARAM_INT);[cite: 12]
        return $query->execute();[cite: 12]
    } catch (PDOException $e) {
        error_log("Mark as read error: " . $e->getMessage());[cite: 12]
        return false;[cite: 12]
    }
}

/**
 * Mark all notifications as read for a user
 * @param int $userId - User ID
 * @return bool - Success status[cite: 12]
 */
function markAllNotificationsAsRead($userId) {
    global $dbh;
    try {
        $sql = "UPDATE user_notifications SET is_read = 1 WHERE user_id = :user_id";[cite: 12]
        $query = $dbh->prepare($sql);[cite: 12]
        $query->bindParam(':user_id', $userId, PDO::PARAM_INT);[cite: 12]
        return $query->execute();[cite: 12]
    } catch (PDOException $e) {
        error_log("Mark all as read error: " . $e->getMessage());[cite: 12]
        return false;[cite: 12]
    }
}

/**
 * Delete a notification
 * @param int $notificationId - Notification ID
 * @param int $userId - User ID for verification
 * @return bool - Success status[cite: 12]
 */
function deleteNotification($notificationId, $userId) {
    global $dbh;
    try {
        $sql = "DELETE FROM user_notifications WHERE id = :id AND user_id = :user_id";[cite: 12]
        $query = $dbh->prepare($sql);[cite: 12]
        $query->bindParam(':id', $notificationId, PDO::PARAM_INT);[cite: 12]
        $query->bindParam(':user_id', $userId, PDO::PARAM_INT);[cite: 12]
        return $query->execute();[cite: 12]
    } catch (PDOException $e) {
        error_log("Delete notification error: " . $e->getMessage());[cite: 12]
        return false;[cite: 12]
    }
}

/**
 * Delete old notifications (older than specified days)
 * @param int $days - Days to keep (default 30)
 * @return int - Number of notifications deleted[cite: 12]
 */
function deleteOldNotifications($days = 30) {
    global $dbh;
    try {
        $sql = "DELETE FROM user_notifications WHERE created_at < DATE_SUB(NOW(), INTERVAL :days DAY)";[cite: 12]
        $query = $dbh->prepare($sql);[cite: 12]
        $query->bindParam(':days', $days, PDO::PARAM_INT);[cite: 12]
        $query->execute();[cite: 12]
        return $query->rowCount();[cite: 12]
    } catch (PDOException $e) {
        error_log("Delete old notifications error: " . $e->getMessage());[cite: 12]
        return 0;[cite: 12]
    }
}

/**
 * Send bulk notification to all users
 * @param string $title - Notification title
 * @param string $message - Notification message
 * @param string $type - Notification type
 * @param string|null $link - Optional link
 * @return int - Number of users notified[cite: 12]
 */
function sendBulkNotification($title, $message, $type = 'promo', $link = null) {
    global $dbh;
    try {
        $sql = "SELECT id FROM users";[cite: 12]
        $query = $dbh->prepare($sql);[cite: 12]
        $query->execute();[cite: 12]
        $users = $query->fetchAll(PDO::FETCH_ASSOC);[cite: 12]
        
        $count = 0;[cite: 12]
        foreach ($users as $user) {
            if (addNotification($user['id'], $title, $message, $type, $link)) {[cite: 12]
                $count++;[cite: 12]
            }
        }
        return $count;[cite: 12]
    } catch (PDOException $e) {
        error_log("Bulk notification error: " . $e->getMessage());[cite: 12]
        return 0;[cite: 12]
    }
}

/**
 * Send notification to users with a specific role
 * @param string $role - User role (user, vendor, rider, wholesaler)
 * @param string $title - Notification title
 * @param string $message - Notification message
 * @param string $type - Notification type
 * @param string|null $link - Optional link
 * @return int - Number of users notified[cite: 12]
 */
function sendRoleNotification($role, $title, $message, $type = 'system', $link = null) {
    global $dbh;
    try {
        $stmt = $dbh->prepare("
            SELECT DISTINCT u.id 
            FROM users u
            JOIN user_roles ur ON u.id = ur.user_id
            WHERE ur.role = :role AND ur.status = 'active'
        ");[cite: 12]
        $stmt->bindParam(':role', $role, PDO::PARAM_STR);[cite: 12]
        $stmt->execute();[cite: 12]
        $users = $stmt->fetchAll(PDO::FETCH_ASSOC);[cite: 12]
        
        $count = 0;[cite: 12]
        foreach ($users as $user) {
            if (addNotification($user['id'], $title, $message, $type, $link)) {[cite: 12]
                $count++;[cite: 12]
            }
        }
        return $count;[cite: 12]
    } catch (PDOException $e) {
        error_log("Send role notification error: " . $e->getMessage());[cite: 12]
        return 0;[cite: 12]
    }
}

function sendBulkVendorNotification($title, $message, $type = 'system', $link = null) {
    return sendRoleNotification('vendor', $title, $message, $type, $link);[cite: 12]
}

function sendBulkRiderNotification($title, $message, $type = 'system', $link = null) {
    return sendRoleNotification('rider', $title, $message, $type, $link);[cite: 12]
}

function addAdminNotification($title, $description, $type = 'system', $priority = 'normal', $link = null) {
    global $dbh;
    try {
        $sql = "INSERT INTO notification (title, description, type, priority, link) VALUES (:title, :description, :type, :priority, :link)";[cite: 12]
        $query = $dbh->prepare($sql);[cite: 12]
        $query->bindParam(':title', $title, PDO::PARAM_STR);[cite: 12]
        $query->bindParam(':description', $description, PDO::PARAM_STR);[cite: 12]
        $query->bindParam(':type', $type, PDO::PARAM_STR);[cite: 12]
        $query->bindParam(':priority', $priority, PDO::PARAM_STR);[cite: 12]
        $query->bindParam(':link', $link, PDO::PARAM_STR);[cite: 12]
        return $query->execute();[cite: 12]
    } catch (PDOException $e) {
        error_log("Add admin notification error: " . $e->getMessage());[cite: 12]
        return false;[cite: 12]
    }
}

function getUnreadAdminNotificationCount() {
    global $dbh;
    try {
        $sql = "SELECT COUNT(*) as count FROM notification WHERE is_read = 0";[cite: 12]
        $query = $dbh->prepare($sql);[cite: 12]
        $query->execute();[cite: 12]
        $result = $query->fetch(PDO::FETCH_ASSOC);[cite: 12]
        return (int)$result['count'];[cite: 12]
    } catch (PDOException $e) {
        error_log("Get unread admin count error: " . $e->getMessage());[cite: 12]
        return 0;[cite: 12]
    }
}

function getAdminNotifications($limit = 50) {
    global $dbh;
    try {
        $sql = "SELECT * FROM notification 
                ORDER BY FIELD(priority, 'urgent', 'high', 'normal', 'low'), created_at DESC 
                LIMIT :limit";[cite: 12]
        $query = $dbh->prepare($sql);[cite: 12]
        $query->bindParam(':limit', $limit, PDO::PARAM_INT);[cite: 12]
        $query->execute();[cite: 12]
        return $query->fetchAll(PDO::FETCH_ASSOC);[cite: 12]
    } catch (PDOException $e) {
        error_log("Get admin notifications error: " . $e->getMessage());[cite: 12]
        return [];[cite: 12]
    }
}

function getAdminNotificationsByPriority($priority, $limit = 20) {
    global $dbh;
    try {
        $sql = "SELECT * FROM notification 
                WHERE priority = :priority 
                ORDER BY created_at DESC 
                LIMIT :limit";[cite: 12]
        $query = $dbh->prepare($sql);[cite: 12]
        $query->bindParam(':priority', $priority, PDO::PARAM_STR);[cite: 12]
        $query->bindParam(':limit', $limit, PDO::PARAM_INT);[cite: 12]
        $query->execute();[cite: 12]
        return $query->fetchAll(PDO::FETCH_ASSOC);[cite: 12]
    } catch (PDOException $e) {
        error_log("Get admin notifications by priority error: " . $e->getMessage());[cite: 12]
        return [];[cite: 12]
    }
}

function markAdminNotificationAsRead($notificationId) {
    global $dbh;
    try {
        $sql = "UPDATE notification SET is_read = 1 WHERE id = :id";[cite: 12]
        $query = $dbh->prepare($sql);[cite: 12]
        $query->bindParam(':id', $notificationId, PDO::PARAM_INT);[cite: 12]
        return $query->execute();[cite: 12]
    } catch (PDOException $e) {
        error_log("Mark admin notification as read error: " . $e->getMessage());[cite: 12]
        return false;[cite: 12]
    }
}

function markAllAdminNotificationsAsRead() {
    global $dbh;
    try {
        $sql = "UPDATE notification SET is_read = 1";[cite: 12]
        $query = $dbh->prepare($sql);[cite: 12]
        return $query->execute();[cite: 12]
    } catch (PDOException $e) {
        error_log("Mark all admin notifications as read error: " . $e->getMessage());[cite: 12]
        return false;[cite: 12]
    }
}

function deleteOldAdminNotifications($days = 90) {
    global $dbh;
    try {
        $sql = "DELETE FROM notification WHERE created_at < DATE_SUB(NOW(), INTERVAL :days DAY)";[cite: 12]
        $query = $dbh->prepare($sql);[cite: 12]
        $query->bindParam(':days', $days, PDO::PARAM_INT);[cite: 12]
        $query->execute();[cite: 12]
        return $query->rowCount();[cite: 12]
    } catch (PDOException $e) {
        error_log("Delete old admin notifications error: " . $e->getMessage());[cite: 12]
        return 0;[cite: 12]
    }
}

function getUserNotificationCountByType($userId, $type) {
    global $dbh;
    try {
        $sql = "SELECT COUNT(*) as count FROM user_notifications 
                WHERE user_id = :user_id AND type = :type AND is_read = 0";[cite: 12]
        $query = $dbh->prepare($sql);[cite: 12]
        $query->bindParam(':user_id', $userId, PDO::PARAM_INT);[cite: 12]
        $query->bindParam(':type', $type, PDO::PARAM_STR);[cite: 12]
        $query->execute();[cite: 12]
        $result = $query->fetch(PDO::FETCH_ASSOC);[cite: 12]
        return (int)$result['count'];[cite: 12]
    } catch (PDOException $e) {
        error_log("Get notification count by type error: " . $e->getMessage());[cite: 12]
        return 0;[cite: 12]
    }
}

function formatNotificationTime($timestamp) {
    $time = strtotime($timestamp);[cite: 12]
    $now = time();[cite: 12]
    $diff = $now - $time;[cite: 12]
    
    if ($diff < 60) {
        return 'Just now';[cite: 12]
    } elseif ($diff < 3600) {
        $minutes = floor($diff / 60);[cite: 12]
        return $minutes . ' minute' . ($minutes != 1 ? 's' : '') . ' ago';[cite: 12]
    } elseif ($diff < 86400) {
        $hours = floor($diff / 3600);[cite: 12]
        return $hours . ' hour' . ($hours != 1 ? 's' : '') . ' ago';[cite: 12]
    } elseif ($diff < 604800) {
        $days = floor($diff / 86400);[cite: 12]
        return $days . ' day' . ($days != 1 ? 's' : '') . ' ago';[cite: 12]
    } else {
        return date('M j, Y', $time);[cite: 12]
    }
}

function getNotificationIcon($type) {
    switch($type) {
        case 'order':
            return '📦';[cite: 12]
        case 'promo':
            return '🎉';[cite: 12]
        case 'system':
            return '🔔';[cite: 12]
        case 'vendor':
            return '🏪';[cite: 12]
        case 'rider':
            return '🛵';[cite: 12]
        default:
            return '📢';[cite: 12]
    }
}

function notifyWelcome($userId, $firstName, $accountType = 'customer') {
    $name = trim((string)$firstName);[cite: 12]
    $greeting = $name !== '' ? "Hi {$name}," : 'Hi,';[cite: 12]

    if ($accountType === 'customer') {
        $title = 'Welcome to AfamFresh';[cite: 12]
        $body  = "{$greeting} your account is ready. Browse fresh produce from "
               . 'vendors near you and get it delivered.';[cite: 12]
    } else {
        $label = $accountType === 'rider' ? 'rider' : 'vendor';[cite: 12]
        $title = 'Welcome to AfamFresh';[cite: 12]
        $body  = "{$greeting} your {$label} account has been created. An "
               . 'administrator needs to approve it before you can start — '
               . "we'll let you know as soon as that happens.";[cite: 12]
    }

    return addNotification($userId, $title, $body, 'system', null, ['email']);[cite: 12]
}
?>