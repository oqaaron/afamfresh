<?php
// =============================================================
// AfamFresh - Main Configuration File
// =============================================================
// Wiring and non-sensitive settings only. Every credential comes
// from env.yaml (or a real environment variable, which wins) via
// includes/env.php — nothing secret is written literally here.
//
// Adding a credential? Put it in env.yaml AND env.yaml.example, then
// read it with env_required() below. A literal here is a leak: this
// file is copied between machines and pasted into support threads.
// =============================================================

require_once __DIR__ . '/../../includes/env.php';

// =============================================================
// DATABASE CONFIGURATION (Cloud SQL + Local Fallback)
// =============================================================
define('DB_HOST', env('DB_HOST', 'localhost'));
define('DB_USER', env('DB_USER', 'root'));
define('DB_PASS', env('DB_PASS', ''));   // empty is normal for local XAMPP
define('DB_NAME', env('DB_NAME', 'kitchen'));

// Cloud SQL Unix Socket (Injected automatically by Cloud Run when connected)
define('DB_SOCKET', env('DB_SOCKET', '/cloudsql/afamfresh-c9afb:us-central1:afamfresh-db-instance'));

// =============================================================
// PESAPAL PAYMENT CONFIGURATION
// =============================================================
// Environment: 'live' or 'sandbox'. Set PESAPAL_ENV in env.yaml.
//
// ⚠️ In 'live' mode every checkout is a REAL charge against a real card or
// mobile-money wallet. Do not run test payments in this mode.
define('PESAPAL_ENV', env('PESAPAL_ENV', 'live'));
define('PESAPAL_IS_SANDBOX', PESAPAL_ENV === 'sandbox');

// ⚠️ The live key/secret that used to sit here as literals were exposed in a
// development transcript. Moving them into env.yaml stops the next leak but
// does not undo that one — reissue the pair from the Pesapal merchant
// dashboard and put the new values in env.yaml.
define('PESAPAL_CONSUMER_KEY', env_required('PESAPAL_CONSUMER_KEY', 'Pesapal live payments'));
define('PESAPAL_CONSUMER_SECRET', env_required('PESAPAL_CONSUMER_SECRET', 'Pesapal live payments'));

// Sandbox credentials.
define('PESAPAL_SANDBOX_CONSUMER_KEY', env('PESAPAL_SANDBOX_CONSUMER_KEY', ''));
define('PESAPAL_SANDBOX_CONSUMER_SECRET', env('PESAPAL_SANDBOX_CONSUMER_SECRET', ''));

// Live API Endpoints
define('PESAPAL_LIVE_API_URL', 'https://pay.pesapal.com/v3/api/Auth/RequestToken');
define('PESAPAL_LIVE_IPN_URL', 'https://pay.pesapal.com/v3/api/PostIPN');
define('PESAPAL_LIVE_PAYMENT_URL', 'https://pay.pesapal.com/v3/api/Transactions/SubmitOrderRequest');

// Live IPN ID
define('PESAPAL_LIVE_IPN_ID', env('PESAPAL_IPN_ID', ''));

// Callback URLs.
//
// ⚠️ afam.techaus.online currently has NO DNS A/AAAA record, so Pesapal cannot
// reach either of these. Until the domain resolves (or these point at the Cloud
// Run URL), IPN will never arrive and payments must be confirmed by polling
// GetTransactionStatus instead — which api/payment.php?action=verify does.
//
// Override with PESAPAL_PUBLIC_BASE_URL in env.yaml, e.g.
//   PESAPAL_PUBLIC_BASE_URL: https://afamfresh-736537583604.us-central1.run.app
define('PESAPAL_PUBLIC_BASE_URL', rtrim(env('PESAPAL_PUBLIC_BASE_URL', 'https://afam.techaus.online'), '/'));
define('PESAPAL_CALLBACK_URL', PESAPAL_PUBLIC_BASE_URL . '/pesapal-callback.php');
define('PESAPAL_IPN_NOTIFICATION_URL', PESAPAL_PUBLIC_BASE_URL . '/pesapal-ipn.php');

// =============================================================
// PESAPAL RESOLVED SETTINGS — use these, not the *_LIVE_* ones
// =============================================================
// The constants above are named inconsistently (PESAPAL_LIVE_API_URL is the
// token endpoint, PESAPAL_LIVE_PAYMENT_URL is SubmitOrderRequest) and there was
// no GetTransactionStatus URL at all — which is the one endpoint that can tell
// you whether money actually moved. These derive every URL from a single base
// so sandbox and live cannot drift apart.
define('PESAPAL_API_BASE', PESAPAL_IS_SANDBOX
    ? 'https://cybqa.pesapal.com/pesapalv3/api'
    : 'https://pay.pesapal.com/v3/api');

define('PESAPAL_URL_TOKEN',        PESAPAL_API_BASE . '/Auth/RequestToken');
define('PESAPAL_URL_REGISTER_IPN', PESAPAL_API_BASE . '/URLSetup/RegisterIPN');
define('PESAPAL_URL_SUBMIT_ORDER', PESAPAL_API_BASE . '/Transactions/SubmitOrderRequest');
define('PESAPAL_URL_GET_STATUS',   PESAPAL_API_BASE . '/Transactions/GetTransactionStatus');

define('PESAPAL_KEY', PESAPAL_IS_SANDBOX ? PESAPAL_SANDBOX_CONSUMER_KEY : PESAPAL_CONSUMER_KEY);
define('PESAPAL_SECRET', PESAPAL_IS_SANDBOX ? PESAPAL_SANDBOX_CONSUMER_SECRET : PESAPAL_CONSUMER_SECRET);

// Registered IPN id. Sandbox needs its own — register once via
// PesapalClient::registerIpn() and set PESAPAL_SANDBOX_IPN_ID.
define('PESAPAL_IPN_ID', PESAPAL_IS_SANDBOX
    ? env('PESAPAL_SANDBOX_IPN_ID', '')
    : env('PESAPAL_IPN_ID', PESAPAL_LIVE_IPN_ID));

// Currency
define('CURRENCY', 'UGX');

// =============================================================
// FIREBASE CONFIGURATION (Push Notifications)
// =============================================================
define('FIREBASE_PROJECT_ID', env('FIREBASE_PROJECT_ID', 'afamfresh-c9afb'));

// Two ways in, because the two environments differ in kind.
//
// On Cloud Run there is no persistent disk to put a key file on, so the
// service account arrives as JSON in FIREBASE_CREDENTIALS_JSON (a Secret
// Manager secret). Locally it is a file under storage/, outside the web
// root so Apache cannot serve it.
//
// Whichever is set, getFirebaseServiceAccount() below returns the decoded
// array or null. Nothing else should read these two constants directly.
define('FIREBASE_CREDENTIALS_JSON', env('FIREBASE_CREDENTIALS_JSON', ''));
define('FIREBASE_CREDENTIALS', env(
    'FIREBASE_CREDENTIALS',
    __DIR__ . '/../../storage/firebase/afamfresh-c9afb-firebase-adminsdk-fbsvc-a6dfe39e6d.json'
));

/**
 * The Firebase service account, or null if this deployment has none.
 *
 * Returns null rather than throwing: push is a side effect of orders and
 * deliveries, and a missing key must not take down checkout. Callers log
 * and carry on.
 */
function getFirebaseServiceAccount()
{
    static $cached = false;
    if ($cached !== false) {
        return $cached;
    }

    $raw = FIREBASE_CREDENTIALS_JSON !== ''
        ? FIREBASE_CREDENTIALS_JSON
        : (is_readable(FIREBASE_CREDENTIALS) ? file_get_contents(FIREBASE_CREDENTIALS) : '');

    if ($raw === '') {
        error_log('[FCM] No service account configured — set FIREBASE_CREDENTIALS_JSON, or put the key file at ' . FIREBASE_CREDENTIALS . '. Push notifications are disabled.');
        return $cached = null;
    }

    $account = json_decode($raw, true);
    if (!is_array($account) || empty($account['private_key']) || empty($account['client_email'])) {
        error_log('[FCM] Service account JSON is unreadable or missing private_key/client_email. Push notifications are disabled.');
        return $cached = null;
    }

    return $cached = $account;
}

// =============================================================
// GOOGLE OAUTH CONFIGURATION (Google Sign-In)
// =============================================================
// The client ID is not a secret — it ships inside the Android app and
// is sent to Google in the clear on every sign-in. The client SECRET
// is, and lives in a file under storage/, never here.
define('GOOGLE_CLIENT_ID', env('GOOGLE_CLIENT_ID', '157327414248-1rqea452acim3bsqjh9m4966sgg16siv.apps.googleusercontent.com'));
define('GOOGLE_CLIENT_SECRET_STORAGE', __DIR__ . '/../../storage/google/client_secret.json');
define('GOOGLE_REDIRECT_URI', env('GOOGLE_REDIRECT_URI', 'https://afam.techaus.online/google-callback.php'));

// =============================================================
// EMAIL CONFIGURATION (Brevo / Sendinblue API v3)
// =============================================================
define('BREVO_API_KEY', env_required('BREVO_API_KEY', 'transactional email'));
define('BREVO_FROM_EMAIL', 'afamenterprisez@gmail.com');
define('BREVO_FROM_NAME', 'Afam Enterprises');

// Fallback SMTP (if Brevo fails)
define('SMTP_HOST', 'smtp.gmail.com');
define('SMTP_PORT', 587);
define('SMTP_USERNAME', 'noreply@afamfresh.com');
define('SMTP_PASSWORD', env('SMTP_PASSWORD', ''));
define('SMTP_FROM_NAME', 'AfamFresh');
define('SMTP_FROM_EMAIL', 'noreply@afamfresh.com');

// =============================================================
// SMS CONFIGURATION (Twilio)
// =============================================================
define('TWILIO_ACCOUNT_SID', env_required('TWILIO_ACCOUNT_SID', 'SMS'));
define('TWILIO_AUTH_TOKEN', env_required('TWILIO_AUTH_TOKEN', 'SMS'));
define('TWILIO_PHONE_NUMBER', '+19127582805');

// =============================================================
// OFFICE / WAREHOUSE LOCATION (for delivery calculations)
// =============================================================
define('OFFICE_LAT', 0.38082497218633615);
define('OFFICE_LNG', 32.65071116168179);
define('OFFICE_ADDRESS', 'AfamFresh Warehouse, Kampala');

// =============================================================
// DATABASE CONNECTION SETUP
// =============================================================
try {
    // Determine connection type: Cloud SQL UNIX Socket vs TCP Host/IP
    if (file_exists(DB_SOCKET)) {
        // Connect via Cloud SQL Unix Socket.
        //
        // charset=utf8mb4 is not optional here even though it looks like
        // boilerplate: this is the branch that runs on Cloud Run, and without
        // it PDO falls back to the server's default charset. Local XAMPP and
        // Cloud SQL do not necessarily agree on that default, so omitting it
        // corrupts any non-ASCII name or address in production while local
        // testing stays clean — the worst possible failure shape.
        $dsn = sprintf('mysql:dbname=%s;unix_socket=%s;charset=utf8mb4', DB_NAME, DB_SOCKET);
    } else {
        // Fallback to local host connection
        $dsn = sprintf('mysql:host=%s;dbname=%s;charset=utf8mb4', DB_HOST, DB_NAME);
    }

    $dbh = new PDO($dsn, DB_USER, DB_PASS);
    $dbh->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);
    $dbh->setAttribute(PDO::ATTR_DEFAULT_FETCH_MODE, PDO::FETCH_ASSOC);
    $dbh->setAttribute(PDO::ATTR_EMULATE_PREPARES, false);
} catch (PDOException $e) {
    error_log("Database Connection Error: " . $e->getMessage());
    header('Content-Type: application/json');
    echo json_encode([
        'success' => false,
        'error' => 'Database connection failed. Please try again later.'
    ]);
    exit;
}

// =============================================================
// SESSION START (if not already started)
// =============================================================
if (session_status() === PHP_SESSION_NONE) {
    session_start();
}

// =============================================================
// CORS HEADERS (for API access from mobile app)
// =============================================================
header('Access-Control-Allow-Origin: *');
header('Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS');
header('Access-Control-Allow-Headers: Content-Type, Authorization, X-Requested-With');
header('Access-Control-Allow-Credentials: true');

if (($_SERVER['REQUEST_METHOD'] ?? '') === 'OPTIONS') {
    http_response_code(200);
    exit();
}

// =============================================================
// NOTIFICATION PIPELINE SUB-SYSTEM ENGINES
// =============================================================
require_once __DIR__ . '/../../includes/classes/NotificationEvent.php';
require_once __DIR__ . '/../../includes/classes/PushNotificationService.php';
require_once __DIR__ . '/../../includes/classes/DatabaseNotifier.php';
require_once __DIR__ . '/../../includes/classes/NotificationManager.php';

// =============================================================
// HELPER FUNCTIONS
// =============================================================

/**
 * Get the full URL for a given path
 */
function getFullUrl($path = '') {
    $protocol = isset($_SERVER['HTTPS']) && $_SERVER['HTTPS'] === 'on' ? 'https' : 'http';
    $host = $_SERVER['HTTP_HOST'] ?? 'localhost';
    return $protocol . '://' . $host . '/' . ltrim($path, '/');
}

/**
 * Return a JSON response
 */
function jsonResponse($data, $statusCode = 200) {
    http_response_code($statusCode);
    header('Content-Type: application/json');
    echo json_encode($data);
    exit;
}

/**
 * Log a message to the PHP error log
 */
function logMessage($message, $level = 'INFO') {
    $timestamp = date('Y-m-d H:i:s');
    error_log("[$timestamp] [$level] $message");
}

/**
 * Check if a request is from the mobile app (API)
 */
function isApiRequest() {
    $path = $_SERVER['REQUEST_URI'] ?? '';
    return strpos($path, '/api/') !== false;
}

/**
 * Base64URL Encoding helper for custom JWT compilation
 */
function base64UrlEncode($data) {
    return str_replace(['+', '/', '='], ['-', '_', ''], base64_encode($data));
}

/**
 * Send an FCM v1 Push Notification using Google Service Account Credentials.
 */
function sendPushNotification($deviceToken, $title, $body, $customData = []) {
    $serviceAccount = getFirebaseServiceAccount();
    if ($serviceAccount === null) {
        // Already logged, with the reason and the fix. Nothing to add here.
        return false;
    }

    $header = json_encode(['alg' => 'RS256', 'typ' => 'JWT']);
    $now = time();
    $payload = json_encode([
        'iss' => $serviceAccount['client_email'],
        'scope' => 'https://www.googleapis.com/auth/firebase.messaging',
        'aud' => 'https://oauth2.googleapis.com/token',
        'exp' => $now + 3600,
        'iat' => $now
    ]);

    $base64UrlHeader = base64UrlEncode($header);
    $base64UrlPayload = base64UrlEncode($payload);
    $signatureInput = $base64UrlHeader . "." . $base64UrlPayload;

    $signature = '';
    if (!openssl_sign($signatureInput, $signature, $serviceAccount['private_key'], 'SHA256')) {
        error_log("[FCM Error] Failed to generate OpenSSL signature payload asset context.");
        return false;
    }
    $base64UrlSignature = base64UrlEncode($signature);
    $jwtAssertion = $signatureInput . "." . $base64UrlSignature;

    $tokenUrl = 'https://oauth2.googleapis.com/token';
    $postFields = 'grant_type=' . urlencode('urn:ietf:params:oauth:grant-type:jwt-bearer') . '&assertion=' . urlencode($jwtAssertion);

    $ch = curl_init();
    curl_setopt($ch, CURLOPT_URL, $tokenUrl);
    curl_setopt($ch, CURLOPT_POST, true);
    curl_setopt($ch, CURLOPT_POSTFIELDS, $postFields);
    curl_setopt($ch, CURLOPT_RETURNTRANSFER, true);
    curl_setopt($ch, CURLOPT_HTTPHEADER, ['Content-Type: application/x-www-form-urlencoded']);
    
    $tokenResponse = curl_exec($ch);
    if (curl_errno($ch)) {
        error_log("[FCM Error] Access Token Request failed: " . curl_error($ch));
        curl_close($ch);
        return false;
    }
    curl_close($ch);

    $tokenData = json_decode($tokenResponse, true);
    $accessToken = $tokenData['access_token'] ?? null;
    if (!$accessToken) {
        error_log("[FCM Error] Could not parse access_token block output profile: " . $tokenResponse);
        return false;
    }

    $fcmUrl = 'https://fcm.googleapis.com/v1/projects/' . FIREBASE_PROJECT_ID . '/messages:send';
    
    $stringData = [];
    foreach ($customData as $key => $val) {
        $stringData[(string)$key] = (string)$val;
    }

    $messagePayload = [
        'message' => [
            'token' => $deviceToken,
            'notification' => [
                'title' => $title,
                'body' => $body
            ]
        ]
    ];

    if (!empty($stringData)) {
        $messagePayload['message']['data'] = $stringData;
    }

    $headers = [
        'Authorization: Bearer ' . $accessToken,
        'Content-Type: application/json'
    ];

    $ch = curl_init();
    curl_setopt($ch, CURLOPT_URL, $fcmUrl);
    curl_setopt($ch, CURLOPT_POST, true);
    curl_setopt($ch, CURLOPT_HTTPHEADER, $headers);
    curl_setopt($ch, CURLOPT_RETURNTRANSFER, true);
    curl_setopt($ch, CURLOPT_POSTFIELDS, json_encode($messagePayload));

    $fcmResponse = curl_exec($ch);
    $httpCode = curl_getinfo($ch, CURLINFO_HTTP_CODE);
    
    if (curl_errno($ch)) {
        error_log("[FCM Error] Push dispatcher failed network transmission: " . curl_error($ch));
        curl_close($ch);
        return false;
    }
    curl_close($ch);

    return ($httpCode === 200);
}

/**
 * Sends a Transactional Email via Brevo API v3 HTTP Protocol endpoints.
 */
function sendEmailWithBrevo($toEmail, $toName, $subject, $htmlBody, $textBody = '') {
    $url = 'https://api.brevo.com/v3/smtp/email';
    
    $payload = [
        'sender' => [
            'name' => BREVO_FROM_NAME,
            'email' => BREVO_FROM_EMAIL
        ],
        'to' => [
            [
                'email' => $toEmail,
                'name' => $toName
            ]
        ],
        'replyTo' => [
            'email' => BREVO_FROM_EMAIL,
            'name' => BREVO_FROM_NAME
        ],
        'subject' => $subject,
        'htmlContent' => $htmlBody
    ];

    if (!empty($textBody)) {
        $payload['textContent'] = $textBody;
    }

    $headers = [
        'api-key: ' . BREVO_API_KEY,
        'Content-Type: application/json',
        'Accept: application/json'
    ];

    $ch = curl_init();
    curl_setopt($ch, CURLOPT_URL, $url);
    curl_setopt($ch, CURLOPT_POST, true);
    curl_setopt($ch, CURLOPT_HTTPHEADER, $headers);
    curl_setopt($ch, CURLOPT_RETURNTRANSFER, true);
    curl_setopt($ch, CURLOPT_POSTFIELDS, json_encode($payload));

    $response = curl_exec($ch);
    $httpCode = curl_getinfo($ch, CURLINFO_HTTP_CODE);

    if (curl_errno($ch)) {
        $errorMsg = curl_error($ch);
        curl_close($ch);
        error_log("[Brevo API Error] Network request transmission failure: " . $errorMsg);
        return ['result' => false, 'message' => 'Network error: ' . $errorMsg];
    }
    curl_close($ch);

    if ($httpCode >= 200 && $httpCode < 300) {
        return ['result' => true, 'message' => 'Email sent'];
    } else {
        error_log("[Brevo API Failure] Server responded with Code $httpCode: " . $response);
        return ['result' => false, 'message' => 'Server rejection response: ' . $response];
    }
}

// =============================================================
// ERROR HANDLING
// =============================================================
if (defined('APP_ENV') && APP_ENV === 'production') {
    error_reporting(0);
    ini_set('display_errors', 0);
} else {
    error_reporting(E_ALL);
    ini_set('display_errors', 1);
}

// =============================================================
// TIMEZONE
// =============================================================
date_default_timezone_set('Africa/Kampala');

// =============================================================
// END OF CONFIGURATION
// =============================================================
?>