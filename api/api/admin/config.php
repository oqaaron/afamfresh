<?php
// =============================================================
// api/admin/config.php — the actual admin-gated app_config mutation
// endpoint. Despite the /admin/ path, this had no session check at
// all until now — identical unauthenticated write access to
// api/config.php's old PUT branch, just with a transaction wrapped
// around it. Nothing calls this today (confirmed: no Android caller,
// no admin/*.php page), so this is pure hardening ahead of whichever
// admin settings page eventually manages app_config.
// =============================================================
header('Content-Type: application/json');
require_once '../admin/includes/config.php'; // This already has Pesapal constants

if (!isset($_SESSION['admin_logged_in']) || $_SESSION['admin_logged_in'] !== true) {
    http_response_code(401);
    echo json_encode(['success' => false, 'error' => 'Not logged in']);
    exit;
}

$method = $_SERVER['REQUEST_METHOD'];

if ($method === 'GET') {
    $stmt = $dbh->query("SELECT config_key, config_value FROM app_config");
    $config = [];
    while ($row = $stmt->fetch(PDO::FETCH_ASSOC)) {
        $config[$row['config_key']] = $row['config_value'];
    }
    echo json_encode(['success' => true, 'config' => $config]);
} elseif ($method === 'PUT') {
    $input = json_decode(file_get_contents('php://input'), true);
    if (empty($input)) {
        echo json_encode(['success' => false, 'error' => 'No data provided']);
        exit;
    }

    // Fixed, known settings surface — same convention as delivery_pricing/
    // loyalty_settings (single-row tables with a known column set). There is
    // no legitimate reason for this endpoint to create a new config_key that
    // doesn't already exist, so anything outside this list is rejected
    // outright rather than silently ignored.
    $allowedKeys = [
        'current_version',
        'is_maintenance_mode',
        'maintenance_message',
        'min_version_required',
        'Bulk_approval_required',
    ];

    $unknown = array_diff(array_keys($input), $allowedKeys);
    if (!empty($unknown)) {
        echo json_encode(['success' => false, 'error' => 'Unknown config key(s): ' . implode(', ', $unknown)]);
        exit;
    }

    foreach ($input as $key => $value) {
        if (($key === 'is_maintenance_mode' || $key === 'Bulk_approval_required') && !in_array((string)$value, ['0', '1'], true)) {
            echo json_encode(['success' => false, 'error' => "$key must be 0 or 1"]);
            exit;
        }
        if (!is_scalar($value)) {
            echo json_encode(['success' => false, 'error' => "$key must be a plain value"]);
            exit;
        }
    }

    try {
        $dbh->beginTransaction();
        $stmt = $dbh->prepare("UPDATE app_config SET config_value = ? WHERE config_key = ?");
        foreach ($input as $key => $value) {
            $stmt->execute([(string)$value, $key]);
        }
        $dbh->commit();
        echo json_encode(['success' => true, 'message' => 'Configuration updated successfully']);
    } catch (PDOException $e) {
        $dbh->rollBack();
        error_log('admin/config.php update failed: ' . $e->getMessage());
        echo json_encode(['success' => false, 'error' => 'Database error.']);
    }
} else {
    http_response_code(405);
    echo json_encode(['success' => false, 'error' => 'Invalid request method']);
}