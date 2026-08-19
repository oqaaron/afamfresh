<?php
// =============================================================
// includes/brevo-sms.php — transactional SMS through Brevo.
// =============================================================
// Brevo rather than Twilio because the account already exists and the API key
// is already configured for email. Note SMS credits are bought SEPARATELY from
// email on Brevo: the same key authenticates both, but a zero SMS balance
// fails with a perfectly valid key, which is a confusing way to lose messages.
//
// Reserved for the two moments a customer actually wants interrupting: the
// order being placed, and it leaving for their address. Every status change
// would be several texts per order at real cost per message, and the ones that
// matter would be lost among them.
// =============================================================

require_once __DIR__ . '/../admin/includes/config.php';

/**
 * Normalise a Ugandan mobile number to the international form Brevo wants.
 *
 * Brevo expects digits only, country code included, no leading + or zero.
 * Numbers reach us in every shape people type them: 0700 123456,
 * +256 700 123456, 256700123456. Sending any of those unmodified is a silent
 * non-delivery -- the API accepts the request and nothing arrives.
 *
 * @param string $phone
 * @return string|null digits in international form, or null if unusable
 */
function normaliseUgandanMsisdn($phone) {
    $digits = preg_replace('/\D+/', '', (string)$phone);
    if ($digits === '') {
        return null;
    }

    // 0700123456 -> 256700123456
    if (strlen($digits) === 10 && $digits[0] === '0') {
        return '256' . substr($digits, 1);
    }
    // 700123456 -> 256700123456
    if (strlen($digits) === 9 && $digits[0] === '7') {
        return '256' . $digits;
    }
    // Already 256XXXXXXXXX
    if (strlen($digits) === 12 && str_starts_with($digits, '256')) {
        return $digits;
    }

    // Anything else is not a number this can reason about. Refused rather than
    // guessed at, because a wrong guess bills for a message to a stranger.
    return null;
}

/**
 * Send one transactional SMS.
 *
 * @param string $phone   recipient, in any common local format
 * @param string $message body; keep it under 160 characters or it bills as two
 * @return array{success: bool, error: ?string}
 */
/**
 * Shared transactional SMS path for all direct sends.
 *
 * OTP sends are special: they occur before a user row exists, so they cannot be
 * queued into user_notifications and must stay a direct send. For all other live
 * customer messaging, the generic addNotification() path is preferred.
 */
function sendSmsWithBrevo($phone, $message) {
    $sender = trim((string)env('BREVO_SMS_SENDER', ''));
    if ($sender === '') {
        // No silent default. An unregistered sender id is the single most
        // common reason Brevo accepts an SMS that never arrives, so refusing
        // here is better than appearing to work.
        error_log('SMS not sent: BREVO_SMS_SENDER is not set.');
        return ['success' => false, 'error' => 'SMS sender is not configured.'];
    }

    $to = normaliseUgandanMsisdn($phone);
    if ($to === null) {
        error_log('SMS not sent: unusable phone number "' . $phone . '"');
        return ['success' => false, 'error' => 'That phone number is not usable.'];
    }

    $payload = [
        'sender'    => $sender,
        'recipient' => $to,
        'content'   => $message,
        'type'      => 'transactional',
    ];

    $ch = curl_init('https://api.brevo.com/v3/transactionalSMS/sms');
    curl_setopt_array($ch, [
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_POST           => true,
        CURLOPT_POSTFIELDS     => json_encode($payload),
        CURLOPT_HTTPHEADER     => [
            'accept: application/json',
            'content-type: application/json',
            'api-key: ' . BREVO_API_KEY,
        ],
        CURLOPT_TIMEOUT        => 15,
    ]);
    $response = curl_exec($ch);
    $code     = curl_getinfo($ch, CURLINFO_HTTP_CODE);
    $curlErr  = curl_error($ch);
    curl_close($ch);

    if ($response === false) {
        error_log("SMS to $to failed at the network level: $curlErr");
        return ['success' => false, 'error' => 'Could not reach the SMS provider.'];
    }

    if ($code >= 200 && $code < 300) {
        return ['success' => true, 'error' => null];
    }

    // Logged in full: the body carries why, and "insufficient credits" is the
    // answer often enough that guessing wastes an afternoon.
    error_log("SMS to $to rejected with HTTP $code: $response");
    return ['success' => false, 'error' => 'The SMS provider rejected the message.'];
}
