<?php
// includes/classes/NotificationManager.php

require_once __DIR__ . '/NotificationEvent.php';
require_once __DIR__ . '/DatabaseNotifier.php';
require_once __DIR__ . '/PushNotificationService.php';
// Note: BrevoEmailService is already in includes/brevo-email.php

class NotificationManager {
    private DatabaseNotifier $dbNotifier;
    private PDO $dbh;

    public function __construct() {
        global $dbh;
        $this->dbh = $dbh;
        $this->dbNotifier = new DatabaseNotifier();
    }

    /**
     * Send a notification to the user via all enabled channels.
     * Dispatches jobs to the queue.
     */
    public function send(NotificationEvent $event): int {
        // 1. Get user data
        $user = $this->getUser($event->userId);
        if (!$user) {
            // Log error? We'll still store the notification.
            $event->userId = 0; // or handle gracefully
        }

        $prefs = json_decode($user['notification_preferences'] ?? '{"email":true,"push":true}', true);
        $emailEnabled = $prefs['email'] ?? true;
        $pushEnabled = $prefs['push'] ?? true;

        // 2. Store in DB (always)
        $notificationId = $this->dbNotifier->store($event);

        // 3. Build common payload
        $payload = [
            'user_id' => $event->userId,
            'title' => $event->title,
            'body' => $event->body,
            'data' => $event->data,
        ];

        // 4. Dispatch queue jobs for each enabled channel
        if ($emailEnabled && !empty($user['email'])) {
            $emailPayload = $payload;
            $emailPayload['to'] = $user['email'];
            $emailPayload['subject'] = $event->title;
            $emailPayload['htmlContent'] = $this->buildEmailHtml($event);
            $this->dispatchJob($notificationId, 'email', $emailPayload);
        }

        if ($pushEnabled && !empty($user['fcm_token'])) {
            $pushPayload = $payload;
            $pushPayload['deviceToken'] = $user['fcm_token'];
            $this->dispatchJob($notificationId, 'push', $pushPayload);
        }

        return $notificationId;
    }

    /**
     * Insert a job into the queue table.
     */
    private function dispatchJob(int $notificationId, string $channel, array $payload): void {
        $sql = "INSERT INTO notification_queue 
                (notification_id, channel, payload, status, next_attempt_at) 
                VALUES (:notification_id, :channel, :payload, 'pending', NOW())";
        $stmt = $this->dbh->prepare($sql);
        $stmt->execute([
            ':notification_id' => $notificationId,
            ':channel' => $channel,
            ':payload' => json_encode($payload),
        ]);
    }

    /**
     * Fetch user data from the database.
     */
    private function getUser(int $userId): ?array {
        $stmt = $this->dbh->prepare("SELECT email, fcm_token, notification_preferences FROM users WHERE id = ?");
        $stmt->execute([$userId]);
        $user = $stmt->fetch(PDO::FETCH_ASSOC);
        return $user ?: null;
    }

    /**
     * Build an HTML email body from the event (customise per type).
     */
    private function buildEmailHtml(NotificationEvent $event): string {
        // You can extend this to use templates per type.
        return "<h2>{$event->title}</h2><p>{$event->body}</p>";
    }
}