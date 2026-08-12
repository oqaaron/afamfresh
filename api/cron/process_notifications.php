<?php
// cron/process_notifications.php
// Run this every minute via cron (or Task Scheduler on Windows)

// Load configuration
require_once __DIR__ . '/../admin/includes/config.php';
require_once __DIR__ . '/../includes/classes/PushNotificationService.php';
require_once __DIR__ . '/../includes/brevo-email.php';
require_once __DIR__ . '/../includes/brevo-sms.php';

// Prevent multiple concurrent runs (lock file)
$lockFile = __DIR__ . '/process_notifications.lock';
if (file_exists($lockFile) && filemtime($lockFile) > time() - 60) {
    // If lock file is less than 60 seconds old, another instance is running.
    exit;
}
touch($lockFile);

// Recover jobs stranded in 'processing'.
//
// A job is marked processing before it is sent. If the container is killed in
// between -- a deploy, an OOM, a Render restart -- nothing ever moves it on,
// and because the query below only looks at 'pending' and 'failed', it would
// sit there permanently and never be delivered or retried.
//
// Ten minutes is well past the longest a single send can take, so anything
// older is a corpse rather than work in progress. Returned to 'pending' with
// its retry count untouched, since the send was never actually attempted.
try {
    $recovered = $dbh->exec(
        "UPDATE notification_queue
            SET status = 'pending', updated_at = NOW()
          WHERE status = 'processing'
            AND updated_at < DATE_SUB(NOW(), INTERVAL 10 MINUTE)"
    );
    if ($recovered > 0) {
        error_log("notification worker: recovered $recovered job(s) stuck in processing");
    }
} catch (PDOException $e) {
    error_log('notification worker: could not recover stuck jobs: ' . $e->getMessage());
}

// Fetch up to 20 pending jobs
$stmt = $dbh->prepare("
    SELECT * FROM notification_queue 
    WHERE status IN ('pending','failed') 
      AND retries < max_retries 
      AND (next_attempt_at IS NULL OR next_attempt_at <= NOW()) 
    ORDER BY id ASC 
    LIMIT 20
");
$stmt->execute();
$jobs = $stmt->fetchAll();

foreach ($jobs as $job) {
    // Mark as processing to prevent duplicate pickup
    $update = $dbh->prepare("UPDATE notification_queue SET status = 'processing', updated_at = NOW() WHERE id = ?");
    $update->execute([$job['id']]);

    $payload = json_decode($job['payload'], true);
    $success = false;
    $error = null;

    try {
        // Process based on channel
        if ($job['channel'] === 'email' || $job['channel'] === 'both') {
            $emailService = new BrevoEmailService();
            $result = $emailService->send(
                $payload['to'],
                $payload['subject'],
                $payload['htmlContent']
            );
            if ($result['success']) {
                // Update user_notifications
                $dbh->prepare("UPDATE user_notifications SET email_sent_at = NOW() WHERE id = ?")
                   ->execute([$job['notification_id']]);
            } else {
                throw new Exception($result['error'] ?? 'Email delivery failed');
            }
        }

        if ($job['channel'] === 'push' || $job['channel'] === 'both') {
            $pushService = new PushNotificationService();
            $sent = $pushService->send(
                $payload['deviceToken'],
                $payload['title'],
                $payload['body'],
                $payload['data'] ?? []
            );
            if ($sent) {
                $dbh->prepare("UPDATE user_notifications SET push_sent_at = NOW() WHERE id = ?")
                   ->execute([$job['notification_id']]);
            } else {
                throw new Exception('Push delivery failed');
            }
        }

        if ($job['channel'] === 'sms') {
            // sendSmsWithBrevo normalises the number itself and refuses one it
            // cannot make sense of, so a malformed mobile fails loudly here
            // rather than being posted to Brevo and billed for.
            $result = sendSmsWithBrevo($payload['to'], $payload['text']);
            if (!empty($result['success'])) {
                $dbh->prepare("UPDATE user_notifications SET sms_sent_at = NOW() WHERE id = ?")
                   ->execute([$job['notification_id']]);
            } else {
                throw new Exception($result['error'] ?? 'SMS delivery failed');
            }
        }

        $success = true;
    } catch (Exception $e) {
        $error = $e->getMessage();
    }

    // Update job status
    $status = $success ? 'sent' : 'failed';
    $retries = $job['retries'] + 1;
    $nextAttempt = ($retries < $job['max_retries']) 
        ? date('Y-m-d H:i:s', strtotime("+" . pow(2, $retries) . " seconds")) 
        : null;

    $update = $dbh->prepare("
        UPDATE notification_queue 
        SET status = ?, retries = ?, next_attempt_at = ?, updated_at = NOW() 
        WHERE id = ?
    ");
    $update->execute([$status, $retries, $nextAttempt, $job['id']]);

    // If failed, log error to the notification record
    if (!$success && $error) {
        // Was a two-way choice, so an SMS failure would have been filed under
        // push_error and looked like a broken device token.
        $errField = match ($job['channel']) {
            'email', 'both' => 'email_error',
            'sms'           => 'sms_error',
            default         => 'push_error',
        };
        $dbh->prepare("UPDATE user_notifications SET $errField = ? WHERE id = ?")
           ->execute([$error, $job['notification_id']]);
    }
}

// Remove lock file
unlink($lockFile);