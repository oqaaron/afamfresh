<?php
// api/admin/config.php — Admin app_config mutation endpoint
declare(strict_types=1);

header('Content-Type: application/json');

require_once __DIR__ . '/../../admin/includes/config.php';
require_once __DIR__ . '/../../includes/csrf.php';
require_once __DIR__ . '/../../includes/admin_permissions.php';

requireAdminPermissionApi('configuration.manage');

$method = $_SERVER['REQUEST_METHOD'];

if ($method === 'GET') {
    try {
        $stmt = $dbh->query("SELECT config_key, config_value FROM app_config");
        $config = [];
        while ($row = $stmt->fetch(PDO::FETCH_ASSOC)) {
            $config[$row['config_key']] = $row['config_value'];
        }
        echo json_encode(['success' => true, 'config' => $config]);
    } catch (PDOException $e) {
        echo json_encode(['success' => false, 'error' => 'Database error: ' . $e->getMessage()]);
    }
    exit;
}

if ($method === 'PUT') {
    verifyCsrfHeader();

    $input = json_decode(file_get_contents('php://input'), true);
    if (empty($input)) {
        echo json_encode(['success' => false, 'error' => 'No data provided']);
        exit;
    }

    // Extended settings whitelist including platform commissions and merchant switches
    $allowedKeys = [
        'current_version',
        'is_maintenance_mode',
        'maintenance_message',
        'min_version_required',
        'Bulk_approval_required',
        'merchant_approval_required',
        'default_rider_commission_rate',
        'default_vendor_commission_rate',
        'default_market_vendor_commission_rate',
        'default_fastfood_commission_rate',
        'default_wholesale_commission_rate',
    ];

    $unknown = array_diff(array_keys($input), $allowedKeys);
    if (!empty($unknown)) {
        echo json_encode(['success' => false, 'error' => 'Unknown config key(s): ' . implode(', ', $unknown)]);
        exit;
    }

    foreach ($input as $key => $value) {
        if (in_array($key, ['is_maintenance_mode', 'Bulk_approval_required', 'merchant_approval_required'], true) 
            && !in_array((string)$value, ['0', '1'], true)) {
            echo json_encode(['success' => false, 'error' => "$key must be 0 or 1"]);
            exit;
        }

        if (str_ends_with($key, '_commission_rate')) {
            if (!is_numeric($value) || (float)$value < 0 || (float)$value > 100) {
                echo json_encode(['success' => false, 'error' => "$key must be a percentage between 0 and 100"]);
                exit;
            }
        }

        if (!is_scalar($value)) {
            echo json_encode(['success' => false, 'error' => "$key must be a plain value"]);
            exit;
        }
    }

    try {
        $dbh->beginTransaction();
        $stmt = $dbh->prepare("
            INSERT INTO app_config (config_key, config_value) 
            VALUES (?, ?)
            ON DUPLICATE KEY UPDATE config_value = VALUES(config_value)
        ");
        foreach ($input as $key => $value) {
            $stmt->execute([$key, (string)$value]);
        }
        $dbh->commit();
        echo json_encode(['success' => true, 'message' => 'Configuration updated successfully']);
    } catch (PDOException $e) {
        $dbh->rollBack();
        error_log('admin/config.php update failed: ' . $e->getMessage());
        echo json_encode(['success' => false, 'error' => 'Database error: ' . $e->getMessage()]);
    }
    exit;
}

http_response_code(405);
echo json_encode(['success' => false, 'error' => 'Invalid request method']);
