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

// Set BEFORE anything else can emit a diagnostic. This block used to sit at the
// bottom of the file, roughly 300 lines after the database connection -- so any
// warning raised while connecting was printed with display_errors still at its
// default, which is exactly how /tmp CA-file permission errors ended up in the
// body of an API response. Nothing above this line may fail loudly.
// =============================================================
// ERROR HANDLING
// =============================================================
// APP_ENV was read here but defined nowhere, in this file or any other. So
// defined() was always false, the else branch always ran, and production served
// with display_errors on. Not theoretical: GET /api/admin/vendors.php returned
// a PHP notice naming /var/www/html/... ahead of its JSON, which both corrupted
// the response and defeated http_response_code() -- the 401 went out as a 200,
// because output had already been flushed.
//
// Defaults to production. Guessing wrong that way sends errors to the log
// instead of the screen; guessing the other way puts file paths and SQL
// messages in front of whoever asked.
if (!defined('APP_ENV')) {
    define('APP_ENV', env('APP_ENV', 'production') === 'development' ? 'development' : 'production');
}

if (APP_ENV === 'production') {
    // E_ALL, not 0: these errors still matter, they just belong in the log
    // rather than the response body. error_reporting(0) silenced them
    // everywhere, which makes a fault invisible rather than quiet.
    error_reporting(E_ALL);
    ini_set('display_errors', 0);
    ini_set('log_errors', 1);
} else {
    error_reporting(E_ALL);
    ini_set('display_errors', 1);
}

// =============================================================
// DATABASE CONFIGURATION (Cloud SQL + Local Fallback)
// =============================================================
define('DB_HOST', env('DB_HOST', 'localhost'));
define('DB_USER', env('DB_USER', 'root'));
define('DB_PASS', env('DB_PASS', ''));   // empty is normal for local XAMPP
define('DB_NAME', env('DB_NAME', 'kitchen'));

// Cloud SQL Unix Socket (Injected automatically by Cloud Run when connected)
define('DB_SOCKET', env('DB_SOCKET', '/cloudsql/afamfresh-f68c6:europe-west3:afamfresh'));

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
//   PESAPAL_PUBLIC_BASE_URL: https://afamfresh-backend-xxxxxx-ew.a.run.app
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
define('FIREBASE_PROJECT_ID', env('FIREBASE_PROJECT_ID', 'afamfresh-f68c6'));

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
    __DIR__ . '/../../storage/firebase/service-account.json'
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

    // Message is deliberately neutral about the caller: this account backs
    // both push and Cloud Storage now, and a log line claiming push is
    // disabled while the real casualty was an image upload sends whoever
    // reads it looking in the wrong place.
    if ($raw === '') {
        error_log('[google-auth] No service account configured — set FIREBASE_CREDENTIALS_JSON, or put the key file at ' . FIREBASE_CREDENTIALS . '. Push notifications and bucket uploads are both disabled.');
        return $cached = null;
    }

    $account = json_decode($raw, true);
    if (!is_array($account) || empty($account['private_key']) || empty($account['client_email'])) {
        error_log('[google-auth] Service account JSON is unreadable or missing private_key/client_email. Push notifications and bucket uploads are both disabled.');
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
// Nothing reads these. They described a server-side OAuth redirect flow that
// does not exist -- google-callback.php was never written, and the only
// consumer, getGoogleClient(), lived in the firebase-config.php that has been
// deleted. The client id here also named a third Google project
// (157327414248), unrelated to both afamfresh-f68c6 and the app.
//
// Google Sign-In verification is GOOGLE_WEB_CLIENT_ID, read in api/auth.php.
// Left out entirely rather than carried as a wrong default nobody reads.

// =============================================================
// EMAIL CONFIGURATION (Brevo / Sendinblue API v3)
// =============================================================
define('BREVO_API_KEY', env_required('BREVO_API_KEY', 'transactional email'));

// Sends from the verified domain sender, not the old Gmail address.
//
// noreply@techaus.online is verified on the Brevo account with DKIM and DMARC
// configured, which matters more than it looks: mail sent as an @gmail.com
// address by a third party fails DMARC alignment, and Gmail publishes a
// policy telling receivers to treat that harshly. It works in testing and
// then quietly lands in spam at volume — the worst kind of failure, because
// the send reports success.
//
// Overridable by environment so a sender change does not need a code deploy.
define('BREVO_FROM_EMAIL', env('BREVO_FROM_EMAIL', 'noreply@techaus.online'));
define('BREVO_FROM_NAME', env('BREVO_FROM_NAME', 'AfamFresh'));

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
// Optional, not required. sendSMS() in includes/twilio-sms.php has no callers
// anywhere in the app — SMS is wired up but never sent. Declaring these with
// env_required() logged a missing-credential warning on every request, and
// made them mandatory deployment secrets, for a feature that does not run.
// If SMS is ever switched on, set all three and move the number out of here.
define('TWILIO_ACCOUNT_SID', env('TWILIO_ACCOUNT_SID', ''));
define('TWILIO_AUTH_TOKEN', env('TWILIO_AUTH_TOKEN', ''));
define('TWILIO_PHONE_NUMBER', env('TWILIO_PHONE_NUMBER', '+19127582805'));

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
        // TCP. Two very different situations use this branch: local XAMPP over
        // loopback, and a host outside Google (Render, a VPS) reaching Cloud
        // SQL's public IP across the open internet.
        $dsn = sprintf(
            'mysql:host=%s;port=%d;dbname=%s;charset=utf8mb4',
            DB_HOST, (int)env('DB_PORT', 3306), DB_NAME
        );
    }

    // TLS for the TCP case. Without it, the database password and every row
    // returned cross the public internet in clear text — fine over loopback,
    // not fine from another cloud. Cloud SQL will accept an unencrypted
    // connection unless told otherwise, so this cannot be left to the server.
    //
    // DB_SSL_CA holds the PEM itself rather than a path, because the platforms
    // this runs on inject configuration as environment variables and have no
    // persistent disk to put a file on.
    $pdoOptions = [];
    $sslCa = trim((string)env('DB_SSL_CA', ''));
    if ($sslCa !== '' && !file_exists(DB_SOCKET)) {
        // The filename carries the effective uid. Two processes share this
        // container -- Apache as www-data and the notification worker -- and
        // with a single shared path whichever wrote first owned a 0600 file
        // the other could not read or replace. That took the API down with
        // "Permission denied" on md5_file() and a database connection with no
        // usable CA.
        //
        // A path per uid rather than a relaxed mode: the file is a private key
        // trust anchor, and widening it to fix a permissions clash would be
        // solving the wrong half of the problem.
        $uid = function_exists('posix_geteuid') ? posix_geteuid() : 'shared';
        $caPath = sys_get_temp_dir() . '/cloudsql-server-ca-' . $uid . '.pem';
        if (!is_file($caPath) || !is_readable($caPath) || md5_file($caPath) !== md5($sslCa)) {
            file_put_contents($caPath, $sslCa);
            chmod($caPath, 0600);
        }
        $pdoOptions[PDO::MYSQL_ATTR_SSL_CA] = $caPath;
        // Cloud SQL's certificate is issued for the instance name, not the IP
        // being dialled, so hostname verification cannot succeed. The CA check
        // above is what establishes we are talking to the right server.
        $pdoOptions[PDO::MYSQL_ATTR_SSL_VERIFY_SERVER_CERT] = false;
    }

    $dbh = new PDO($dsn, DB_USER, DB_PASS, $pdoOptions);
    $dbh->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);
    $dbh->setAttribute(PDO::ATTR_DEFAULT_FETCH_MODE, PDO::FETCH_ASSOC);
    $dbh->setAttribute(PDO::ATTR_EMULATE_PREPARES, false);
} catch (PDOException $e) {
    // Say which target was dialled and how, never just the driver's message.
    //
    // "SQLSTATE[HY000] [2002] No such file or directory" on its own reads like
    // a missing file and sends you hunting for one. It actually means PDO used
    // a Unix socket — which is what mysql:host=localhost does — so the real
    // fault is an unset DB_HOST, not anything on disk. Naming the target makes
    // that obvious, and distinguishes it from a refused or timed-out TCP
    // connection, which is a firewall or allowlist problem instead.
    $target = file_exists(DB_SOCKET)
        ? 'unix socket ' . DB_SOCKET
        : 'tcp ' . DB_HOST . ':' . (int)env('DB_PORT', 3306)
            . (DB_HOST === 'localhost' ? '  <-- "localhost" makes PDO use a socket; set DB_HOST to an IP' : '')
            . (trim((string)env('DB_SSL_CA', '')) === '' ? '  (no TLS: DB_SSL_CA unset)' : '  (TLS on)');

    error_log('Database Connection Error: ' . $e->getMessage()
        . ' | db=' . DB_NAME . ' user=' . DB_USER . ' via ' . $target);

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
 * OAuth access token for the Firebase/GCP service account, for one scope.
 *
 * Shared by push (firebase.messaging) and object storage (devstorage), which
 * differ only in the scope they ask for — the JWT assembly and exchange are
 * identical, and having two copies of it would mean fixing signing bugs twice.
 *
 * Cached per scope for the life of the request. Tokens are valid an hour, but
 * a single PHP request lasts seconds; this only avoids a second round trip
 * when one request both stores an image and sends a notification.
 *
 * Returns null on any failure, having logged the reason.
 */
function googleAccessToken($scope) {
    static $cache = [];
    if (isset($cache[$scope])) {
        return $cache[$scope];
    }

    $serviceAccount = getFirebaseServiceAccount();
    if ($serviceAccount === null) {
        return null;   // already logged, with the fix
    }

    $now = time();
    $signatureInput = base64UrlEncode(json_encode(['alg' => 'RS256', 'typ' => 'JWT']))
        . '.'
        . base64UrlEncode(json_encode([
            'iss'   => $serviceAccount['client_email'],
            'scope' => $scope,
            'aud'   => 'https://oauth2.googleapis.com/token',
            'exp'   => $now + 3600,
            'iat'   => $now,
        ]));

    $signature = '';
    if (!openssl_sign($signatureInput, $signature, $serviceAccount['private_key'], 'SHA256')) {
        error_log('[google-auth] openssl_sign failed — is private_key intact?');
        return null;
    }
    $assertion = $signatureInput . '.' . base64UrlEncode($signature);

    $ch = curl_init('https://oauth2.googleapis.com/token');
    curl_setopt_array($ch, [
        CURLOPT_POST           => true,
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_TIMEOUT        => 20,
        CURLOPT_HTTPHEADER     => ['Content-Type: application/x-www-form-urlencoded'],
        CURLOPT_POSTFIELDS     => 'grant_type=' . urlencode('urn:ietf:params:oauth:grant-type:jwt-bearer')
            . '&assertion=' . urlencode($assertion),
    ]);
    $response = curl_exec($ch);
    if ($response === false) {
        error_log('[google-auth] token request failed: ' . curl_error($ch));
        curl_close($ch);
        return null;
    }
    curl_close($ch);

    $token = json_decode($response, true)['access_token'] ?? null;
    if (!$token) {
        error_log('[google-auth] no access_token for scope ' . $scope . ': ' . substr($response, 0, 200));
        return null;
    }

    return $cache[$scope] = $token;
}

/**
 * Send an FCM v1 Push Notification using Google Service Account Credentials.
 */
function sendPushNotification($deviceToken, $title, $body, $customData = []) {
    $accessToken = googleAccessToken('https://www.googleapis.com/auth/firebase.messaging');
    if ($accessToken === null) {
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
// TIMEZONE
// =============================================================
date_default_timezone_set('Africa/Kampala');

// =============================================================
// END OF CONFIGURATION
// =============================================================
?>