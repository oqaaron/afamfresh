<?php
// =============================================================
// api/config.php — Public, Read-Only App Configuration Endpoint
// =============================================================

// Ensure errors are logged to the server rather than outputting HTML/Notices into the JSON body
ini_set('display_errors', '0');
ini_set('log_errors', '1');
error_reporting(E_ALL);

header('Content-Type: application/json; charset=utf-8');

// Load database connection and base settings
require_once __DIR__ . '/../admin/includes/config.php';

// Handle preflight OPTIONS requests for CORS
if (($_SERVER['REQUEST_METHOD'] ?? '') === 'OPTIONS') {
    http_response_code(204);
    exit();
}

// Only GET requests are permitted on this endpoint
if ($_SERVER['REQUEST_METHOD'] !== 'GET') {
    http_response_code(405);
    echo json_encode([
        'success' => false,
        'error' => 'Method Not Allowed'
    ], JSON_UNESCAPED_SLASHES);
    exit();
}

try {
    // Fetch configuration key-value pairs from database
    $stmt = $dbh->query("SELECT config_key, config_value FROM app_config");
    $config = [];

    while ($row = $stmt->fetch(PDO::FETCH_ASSOC)) {
        $config[$row['config_key']] = $row['config_value'];
    }

    // Return structured JSON response expected by Android app
    http_response_code(200);
    echo json_encode([
        'success' => true,
        'config' => (object)$config
    ], JSON_UNESCAPED_SLASHES);
    exit();

} catch (PDOException $e) {
    error_log("[api/config.php] Database fetch error: " . $e->getMessage());

    http_response_code(500);
    echo json_encode([
        'success' => false,
        'error' => 'Internal server error while fetching application configuration'
    ], JSON_UNESCAPED_SLASHES);
    exit();
}