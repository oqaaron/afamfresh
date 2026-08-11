<?php
require_once __DIR__ . '/../admin/includes/config.php';

/**
 * Add a notification for a user.
 *
 * Writes the in-app record AND queues the other channels. It used to be a bare
 * INSERT into user_notifications, which is why nothing in this codebase ever
 * pushed: NotificationManager, the queue, the retry logic and the worker all
 * existed, and every caller went around them straight to the table.
 *
 * Routing it here rather than rewriting each call site means the eight
 * existing helpers in vendor-notification-helper.php gain push without being
 * touched, and anything written later gets it by default.
 *
 * Delivery is asynchronous: this returns once the job is queued, and the
 * worker in the container sends it within the minute. A queue failure is not
 * treated as a failure of this call — the in-app notification is already
 * stored and readable.
 *
 * @param int $userId - User ID from users table
 * @param string $title - Notification title
 * @param string $message - Notification message
 * @param string $type - Notification type (order, promo, system, etc.)
 * @param string|null $link - Optional link to relevant page
 * @param array $channels - Beyond in-app: 'push' and/or 'email'. Email is
 *                          opt-in per call so routine notifications do not
 *                          each become a separate message in someone's inbox.
 * @return bool - Success status
 */
function addNotification($userId, $title, $message, $type = 'system', $link = null, array $channels = ['push']) {
    try {
        $event = new NotificationEvent(
            (int)$userId,
            $type,
            $title,
            $message,
            $link !== null ? ['link' => $link] : [],
            $channels
        );

        $manager = new NotificationManager();
        // 0 means the user does not exist; the manager has already logged it.
        return $manager->send($event) > 0;
    } catch (Throwable $e) {
        error_log("Add notification error: " . $e->getMessage());
        return false;
    }
}

/**
 * Get unread notification count for a user
 * @param int $userId - User ID
 * @return int - Unread count
 */
function getUnreadNotificationCount($userId) {
    global $dbh;
    try {
        $sql = "SELECT COUNT(*) as count FROM user_notifications WHERE user_id = :user_id AND is_read = 0";
        $query = $dbh->prepare($sql);
        $query->bindParam(':user_id', $userId, PDO::PARAM_INT);
        $query->execute();
        $result = $query->fetch(PDO::FETCH_ASSOC);
        return (int)$result['count'];
    } catch (PDOException $e) {
        error_log("Get unread count error: " . $e->getMessage());
        return 0;
    }
}

/**
 * Get all notifications for a user
 * @param int $userId - User ID
 * @param int $limit - Maximum number of notifications to return
 * @return array - Notifications
 */
function getUserNotifications($userId, $limit = 50) {
    global $dbh;
    try {
        $sql = "SELECT * FROM user_notifications WHERE user_id = :user_id ORDER BY created_at DESC LIMIT :limit";
        $query = $dbh->prepare($sql);
        $query->bindParam(':user_id', $userId, PDO::PARAM_INT);
        $query->bindParam(':limit', $limit, PDO::PARAM_INT);
        $query->execute();
        return $query->fetchAll(PDO::FETCH_ASSOC);
    } catch (PDOException $e) {
        error_log("Get notifications error: " . $e->getMessage());
        return [];
    }
}

/**
 * Get recent notifications for a user (last 7 days)
 * @param int $userId - User ID
 * @param int $limit - Maximum number of notifications
 * @return array - Recent notifications
 */
function getRecentUserNotifications($userId, $limit = 10) {
    global $dbh;
    try {
        $sql = "SELECT * FROM user_notifications 
                WHERE user_id = :user_id 
                AND created_at > DATE_SUB(NOW(), INTERVAL 7 DAY)
                ORDER BY created_at DESC 
                LIMIT :limit";
        $query = $dbh->prepare($sql);
        $query->bindParam(':user_id', $userId, PDO::PARAM_INT);
        $query->bindParam(':limit', $limit, PDO::PARAM_INT);
        $query->execute();
        return $query->fetchAll(PDO::FETCH_ASSOC);
    } catch (PDOException $e) {
        error_log("Get recent notifications error: " . $e->getMessage());
        return [];
    }
}

/**
 * Mark a notification as read
 * @param int $notificationId - Notification ID
 * @return bool - Success status
 */
function markNotificationAsRead($notificationId) {
    global $dbh;
    try {
        $sql = "UPDATE user_notifications SET is_read = 1 WHERE id = :id";
        $query = $dbh->prepare($sql);
        $query->bindParam(':id', $notificationId, PDO::PARAM_INT);
        return $query->execute();
    } catch (PDOException $e) {
        error_log("Mark as read error: " . $e->getMessage());
        return false;
    }
}

/**
 * Mark all notifications as read for a user
 * @param int $userId - User ID
 * @return bool - Success status
 */
function markAllNotificationsAsRead($userId) {
    global $dbh;
    try {
        $sql = "UPDATE user_notifications SET is_read = 1 WHERE user_id = :user_id";
        $query = $dbh->prepare($sql);
        $query->bindParam(':user_id', $userId, PDO::PARAM_INT);
        return $query->execute();
    } catch (PDOException $e) {
        error_log("Mark all as read error: " . $e->getMessage());
        return false;
    }
}

/**
 * Delete a notification
 * @param int $notificationId - Notification ID
 * @param int $userId - User ID for verification
 * @return bool - Success status
 */
function deleteNotification($notificationId, $userId) {
    global $dbh;
    try {
        $sql = "DELETE FROM user_notifications WHERE id = :id AND user_id = :user_id";
        $query = $dbh->prepare($sql);
        $query->bindParam(':id', $notificationId, PDO::PARAM_INT);
        $query->bindParam(':user_id', $userId, PDO::PARAM_INT);
        return $query->execute();
    } catch (PDOException $e) {
        error_log("Delete notification error: " . $e->getMessage());
        return false;
    }
}

/**
 * Delete old notifications (older than specified days)
 * @param int $days - Days to keep (default 30)
 * @return int - Number of notifications deleted
 */
function deleteOldNotifications($days = 30) {
    global $dbh;
    try {
        $sql = "DELETE FROM user_notifications WHERE created_at < DATE_SUB(NOW(), INTERVAL :days DAY)";
        $query = $dbh->prepare($sql);
        $query->bindParam(':days', $days, PDO::PARAM_INT);
        $query->execute();
        return $query->rowCount();
    } catch (PDOException $e) {
        error_log("Delete old notifications error: " . $e->getMessage());
        return 0;
    }
}

/**
 * Send bulk notification to all users
 * @param string $title - Notification title
 * @param string $message - Notification message
 * @param string $type - Notification type
 * @param string|null $link - Optional link
 * @return int - Number of users notified
 */
function sendBulkNotification($title, $message, $type = 'promo', $link = null) {
    global $dbh;
    try {
        // Get all user IDs
        $sql = "SELECT id FROM users";
        $query = $dbh->prepare($sql);
        $query->execute();
        $users = $query->fetchAll(PDO::FETCH_ASSOC);
        
        $count = 0;
        foreach ($users as $user) {
            if (addNotification($user['id'], $title, $message, $type, $link)) {
                $count++;
            }
        }
        return $count;
    } catch (PDOException $e) {
        error_log("Bulk notification error: " . $e->getMessage());
        return 0;
    }
}

/**
 * Send notification to users with a specific role
 * @param string $role - User role (user, vendor, rider, wholesaler)
 * @param string $title - Notification title
 * @param string $message - Notification message
 * @param string $type - Notification type
 * @param string|null $link - Optional link
 * @return int - Number of users notified
 */
function sendRoleNotification($role, $title, $message, $type = 'system', $link = null) {
    global $dbh;
    try {
        $stmt = $dbh->prepare("
            SELECT DISTINCT u.id 
            FROM users u
            JOIN user_roles ur ON u.id = ur.user_id
            WHERE ur.role = :role AND ur.status = 'active'
        ");
        $stmt->bindParam(':role', $role, PDO::PARAM_STR);
        $stmt->execute();
        $users = $stmt->fetchAll(PDO::FETCH_ASSOC);
        
        $count = 0;
        foreach ($users as $user) {
            if (addNotification($user['id'], $title, $message, $type, $link)) {
                $count++;
            }
        }
        return $count;
    } catch (PDOException $e) {
        error_log("Send role notification error: " . $e->getMessage());
        return 0;
    }
}

/**
 * Send bulk notification to all vendors
 * @param string $title - Notification title
 * @param string $message - Notification message
 * @param string $type - Notification type
 * @param string|null $link - Optional link
 * @return int - Number of vendors notified
 */
function sendBulkVendorNotification($title, $message, $type = 'system', $link = null) {
    return sendRoleNotification('vendor', $title, $message, $type, $link);
}

/**
 * Send bulk notification to all riders
 * @param string $title - Notification title
 * @param string $message - Notification message
 * @param string $type - Notification type
 * @param string|null $link - Optional link
 * @return int - Number of riders notified
 */
function sendBulkRiderNotification($title, $message, $type = 'system', $link = null) {
    return sendRoleNotification('rider', $title, $message, $type, $link);
}

/**
 * Add an admin notification
 * @param string $title - Notification title
 * @param string $description - Notification description
 * @param string $type - Notification type (system, order, vendor, user, rider)
 * @param string $priority - Priority level (low, normal, high, urgent)
 * @param string|null $link - Optional link to relevant page
 * @return bool - Success status
 */
function addAdminNotification($title, $description, $type = 'system', $priority = 'normal', $link = null) {
    global $dbh;
    try {
        $sql = "INSERT INTO notification (title, description, type, priority, link) VALUES (:title, :description, :type, :priority, :link)";
        $query = $dbh->prepare($sql);
        $query->bindParam(':title', $title, PDO::PARAM_STR);
        $query->bindParam(':description', $description, PDO::PARAM_STR);
        $query->bindParam(':type', $type, PDO::PARAM_STR);
        $query->bindParam(':priority', $priority, PDO::PARAM_STR);
        $query->bindParam(':link', $link, PDO::PARAM_STR);
        return $query->execute();
    } catch (PDOException $e) {
        error_log("Add admin notification error: " . $e->getMessage());
        return false;
    }
}

/**
 * Get unread admin notification count
 * @return int - Unread count
 */
function getUnreadAdminNotificationCount() {
    global $dbh;
    try {
        $sql = "SELECT COUNT(*) as count FROM notification WHERE is_read = 0";
        $query = $dbh->prepare($sql);
        $query->execute();
        $result = $query->fetch(PDO::FETCH_ASSOC);
        return (int)$result['count'];
    } catch (PDOException $e) {
        error_log("Get unread admin count error: " . $e->getMessage());
        return 0;
    }
}

/**
 * Get all admin notifications
 * @param int $limit - Maximum number of notifications to return
 * @return array - Notifications
 */
function getAdminNotifications($limit = 50) {
    global $dbh;
    try {
        $sql = "SELECT * FROM notification 
                ORDER BY FIELD(priority, 'urgent', 'high', 'normal', 'low'), created_at DESC 
                LIMIT :limit";
        $query = $dbh->prepare($sql);
        $query->bindParam(':limit', $limit, PDO::PARAM_INT);
        $query->execute();
        return $query->fetchAll(PDO::FETCH_ASSOC);
    } catch (PDOException $e) {
        error_log("Get admin notifications error: " . $e->getMessage());
        return [];
    }
}

/**
 * Get admin notifications by priority
 * @param string $priority - Priority level (low, normal, high, urgent)
 * @param int $limit - Maximum number of notifications
 * @return array - Notifications
 */
function getAdminNotificationsByPriority($priority, $limit = 20) {
    global $dbh;
    try {
        $sql = "SELECT * FROM notification 
                WHERE priority = :priority 
                ORDER BY created_at DESC 
                LIMIT :limit";
        $query = $dbh->prepare($sql);
        $query->bindParam(':priority', $priority, PDO::PARAM_STR);
        $query->bindParam(':limit', $limit, PDO::PARAM_INT);
        $query->execute();
        return $query->fetchAll(PDO::FETCH_ASSOC);
    } catch (PDOException $e) {
        error_log("Get admin notifications by priority error: " . $e->getMessage());
        return [];
    }
}

/**
 * Mark an admin notification as read
 * @param int $notificationId - Notification ID
 * @return bool - Success status
 */
function markAdminNotificationAsRead($notificationId) {
    global $dbh;
    try {
        $sql = "UPDATE notification SET is_read = 1 WHERE id = :id";
        $query = $dbh->prepare($sql);
        $query->bindParam(':id', $notificationId, PDO::PARAM_INT);
        return $query->execute();
    } catch (PDOException $e) {
        error_log("Mark admin notification as read error: " . $e->getMessage());
        return false;
    }
}

/**
 * Mark all admin notifications as read
 * @return bool - Success status
 */
function markAllAdminNotificationsAsRead() {
    global $dbh;
    try {
        $sql = "UPDATE notification SET is_read = 1";
        $query = $dbh->prepare($sql);
        return $query->execute();
    } catch (PDOException $e) {
        error_log("Mark all admin notifications as read error: " . $e->getMessage());
        return false;
    }
}

/**
 * Delete old admin notifications
 * @param int $days - Days to keep (default 90)
 * @return int - Number of notifications deleted
 */
function deleteOldAdminNotifications($days = 90) {
    global $dbh;
    try {
        $sql = "DELETE FROM notification WHERE created_at < DATE_SUB(NOW(), INTERVAL :days DAY)";
        $query = $dbh->prepare($sql);
        $query->bindParam(':days', $days, PDO::PARAM_INT);
        $query->execute();
        return $query->rowCount();
    } catch (PDOException $e) {
        error_log("Delete old admin notifications error: " . $e->getMessage());
        return 0;
    }
}

/**
 * Get notification count by type for a user
 * @param int $userId - User ID
 * @param string $type - Notification type
 * @return int - Count
 */
function getUserNotificationCountByType($userId, $type) {
    global $dbh;
    try {
        $sql = "SELECT COUNT(*) as count FROM user_notifications 
                WHERE user_id = :user_id AND type = :type AND is_read = 0";
        $query = $dbh->prepare($sql);
        $query->bindParam(':user_id', $userId, PDO::PARAM_INT);
        $query->bindParam(':type', $type, PDO::PARAM_STR);
        $query->execute();
        $result = $query->fetch(PDO::FETCH_ASSOC);
        return (int)$result['count'];
    } catch (PDOException $e) {
        error_log("Get notification count by type error: " . $e->getMessage());
        return 0;
    }
}

/**
 * Format notification time for display
 * @param string $timestamp - MySQL timestamp
 * @return string - Human readable time
 */
function formatNotificationTime($timestamp) {
    $time = strtotime($timestamp);
    $now = time();
    $diff = $now - $time;
    
    if ($diff < 60) {
        return 'Just now';
    } elseif ($diff < 3600) {
        $minutes = floor($diff / 60);
        return $minutes . ' minute' . ($minutes != 1 ? 's' : '') . ' ago';
    } elseif ($diff < 86400) {
        $hours = floor($diff / 3600);
        return $hours . ' hour' . ($hours != 1 ? 's' : '') . ' ago';
    } elseif ($diff < 604800) {
        $days = floor($diff / 86400);
        return $days . ' day' . ($days != 1 ? 's' : '') . ' ago';
    } else {
        return date('M j, Y', $time);
    }
}

/**
 * Get notification icon based on type
 * @param string $type - Notification type
 * @return string - Emoji icon
 */
function getNotificationIcon($type) {
    switch($type) {
        case 'order':
            return '📦';
        case 'promo':
            return '🎉';
        case 'system':
            return '🔔';
        case 'vendor':
            return '🏪';
        case 'rider':
            return '🛵';
        default:
            return '📢';
    }
}
?>