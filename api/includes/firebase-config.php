<?php
// =============================================================
// Firebase Configuration
// =============================================================

// Load main config first
require_once __DIR__ . '/config.php';

// config.php above already defines most of these from env.yaml.
// Guard each one so loading this file cannot re-define a constant.
if (!defined('FIREBASE_PROJECT_ID')) {
    define('FIREBASE_PROJECT_ID', 'afamfresh-c9afb');
}
if (!defined('FIREBASE_CREDENTIALS_PATH')) {
    define('FIREBASE_CREDENTIALS_PATH', __DIR__ . '/../../storage/firebase/afamfresh-c9afb-firebase-adminsdk-fbsvc-a6dfe39e6d.json');
}
if (!defined('GOOGLE_CLIENT_ID')) {
    define('GOOGLE_CLIENT_ID', '157327414248-1rqea452acim3bsqjh9m4966sgg16siv.apps.googleusercontent.com');
}
if (!defined('GOOGLE_CLIENT_SECRET_PATH')) {
    define('GOOGLE_CLIENT_SECRET_PATH', __DIR__ . '/../../storage/google/client_secret.json');
}
if (!defined('GOOGLE_REDIRECT_URI')) {
    define('GOOGLE_REDIRECT_URI', 'https://afam.techaus.online/google-callback.php');
}

/**
 * Verify Firebase ID Token
 */
function verifyFirebaseToken($idToken) {
    // Implement Firebase token verification using Firebase JWT library
    // This is a placeholder - you need to install firebase/php-jwt
    try {
        // $decoded = Firebase\JWT\JWT::decode($idToken, ...);
        // Return user data from token
        return null;
    } catch (Exception $e) {
        logMessage("Firebase token verification failed: " . $e->getMessage(), 'ERROR');
        return false;
    }
}

/**
 * Get Google OAuth Client
 */
function getGoogleClient() {
    $client = new Google\Client();
    $client->setClientId(GOOGLE_CLIENT_ID);
    $client->setClientSecret(file_get_contents(GOOGLE_CLIENT_SECRET_PATH));
    $client->setRedirectUri(GOOGLE_REDIRECT_URI);
    $client->addScope('email');
    $client->addScope('profile');
    return $client;
}
?>