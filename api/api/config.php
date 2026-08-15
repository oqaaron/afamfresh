<?php
// =============================================================
// api/config.php — public, read-only app config (maintenance mode,
// minimum app version, etc).
// =============================================================
// Used to accept an unauthenticated PUT that wrote any key/value pair
// straight into app_config — no session check, no key whitelist.
// Anyone who found this endpoint could flip is_maintenance_mode (take
// the app offline for every customer), min_version_required (lock
// everyone out), or Bulk_approval_required (skip vendor-listing
// review) with one request. The app itself only ever GETs this
// (ApiService.kt has no write call here at all), so there was no
// legitimate caller to break by removing it.
//
// Mutating this now goes through api/admin/config.php, which is
// session-gated and key-whitelisted.
// =============================================================
header('Content-Type: application/json');
require_once '../admin/includes/config.php';

if ($_SERVER['REQUEST_METHOD'] === 'GET') {
    $stmt = $dbh->query("SELECT config_key, config_value FROM app_config");
    $config = [];
    while ($row = $stmt->fetch(PDO::FETCH_ASSOC)) {
        $config[$row['config_key']] = $row['config_value'];
    }
    echo json_encode(['success' => true, 'config' => $config]);
} else {
    http_response_code(405);
    echo json_encode(['success' => false, 'error' => 'Method not allowed']);
}