<?php
// includes/classes/PushNotificationService.php

class PushNotificationService {
    /**
     * Send a push notification via FCM using the existing global function.
     */
    public function send(string $deviceToken, string $title, string $body, array $data = []): bool {
        // The global function is defined in config.php (or elsewhere)
        return sendPushNotification($deviceToken, $title, $body, $data);
    }
}