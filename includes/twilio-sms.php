<?php
// SMS77.io SMS Helper
// Load environment variables with custom parser to handle special characters
function parseEnvFile($filePath) {
    $env = [];
    if (!file_exists($filePath)) {
        return $env;
    }
    
    $lines = file($filePath, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES);
    foreach ($lines as $line) {
        // Skip comments
        if (strpos(trim($line), '#') === 0) {
            continue;
        }
        
        // Parse key=value
        if (strpos($line, '=') !== false) {
            list($key, $value) = explode('=', $line, 2);
            $env[trim($key)] = trim($value);
        }
    }
    
    return $env;
}

$env = parseEnvFile(__DIR__ . '/../.env');

define('TWILIO_ACCOUNT_SID', $env['TWILIO_ACCOUNT_SID'] ?? '');
define('TWILIO_AUTH_TOKEN', $env['TWILIO_AUTH_TOKEN'] ?? '');
define('TWILIO_PHONE_NUMBER', $env['TWILIO_PHONE_NUMBER'] ?? '');

/**
 * Send SMS using Twilio
 *
 * @param string $to - Phone number to send to (format: +256...)
 * @param string $message - SMS message content
 * @return array - ['success' => bool, 'error' => string|null]
 */
function sendSMS($to, $message) {
    // Check if Twilio credentials are configured
    if (empty(TWILIO_ACCOUNT_SID) || empty(TWILIO_AUTH_TOKEN) || empty(TWILIO_PHONE_NUMBER)) {
        // Log warning but don't fail - return success so the rest of the app works
        error_log('Twilio credentials not configured in .env file. SMS not sent.');
        return ['success' => true, 'skipped' => true, 'message' => 'SMS skipped - Twilio not configured'];
    }

    // Validate and normalize phone number to E.164 format
    $digits = preg_replace('/\D/', '', $to);

    // Convert to E.164 format
    if (str_starts_with($digits, '256')) {
        $to = '+' . $digits;  // Uganda country code
    } elseif (str_starts_with($digits, '0')) {
        $to = '+256' . substr($digits, 1);  // Uganda local format (0...)
    } else {
        $to = '+' . $digits;  // Add + prefix
    }

    $url = 'https://api.twilio.com/2010-04-01/Accounts/' . TWILIO_ACCOUNT_SID . '/Messages.json';

    $data = [
        'From' => TWILIO_PHONE_NUMBER,
        'To' => $to,
        'Body' => $message
    ];

    $ch = curl_init($url);
    curl_setopt($ch, CURLOPT_POST, true);
    curl_setopt($ch, CURLOPT_RETURNTRANSFER, true);
    curl_setopt($ch, CURLOPT_SSL_VERIFYPEER, false);
    curl_setopt($ch, CURLOPT_USERPWD, TWILIO_ACCOUNT_SID . ':' . TWILIO_AUTH_TOKEN);
    curl_setopt($ch, CURLOPT_HTTPHEADER, ['Content-Type: application/x-www-form-urlencoded']);
    curl_setopt($ch, CURLOPT_POSTFIELDS, http_build_query($data));

    $response = curl_exec($ch);
    $error = curl_error($ch);
    $httpCode = curl_getinfo($ch, CURLINFO_HTTP_CODE);
    curl_close($ch);

    if ($error) {
        error_log('Twilio CURL error: ' . $error);
        return ['success' => false, 'error' => 'CURL error: ' . $error];
    }

    $result = json_decode($response, true);

    // Check for API errors
    if ($httpCode !== 201 || !isset($result['sid'])) {
        $errorMessage = $result['message'] ?? 'Twilio API error';
        error_log('Twilio API error: ' . $errorMessage);
        return ['success' => false, 'error' => $errorMessage];
    }

    return ['success' => true, 'messageId' => $result['sid']];
}
?>
