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
            // Was: set userId = 0 and carry on, which wrote a notification
            // addressed to a user that does not exist. Nobody could read it,
            // and the queue's foreign key would have rejected the jobs anyway.
            // A missing user is a caller bug; say so in the log and stop.
            error_log("NotificationManager: no such user {$event->userId}; dropped notification: {$event->title}");
            return 0;
        }

        // json_decode returns null for an empty or malformed column, and null
        // is not an array — so the defaults have to survive that case, not just
        // the absent-key one.
        $prefs = json_decode($user['notification_preferences'] ?? '', true);
        if (!is_array($prefs)) {
            $prefs = [];
        }

        // A channel has to be both asked for by the caller and permitted by the
        // user. Either one saying no is enough to skip it.
        $wants = fn(string $c) => in_array($c, $event->channels, true);
        $emailEnabled = $wants('email') && ($prefs['email'] ?? true);
        $pushEnabled  = $wants('push')  && ($prefs['push']  ?? true);

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