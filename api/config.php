<?php
header('Content-Type: application/json');
require_once '../admin/includes/config.php'; // adjust path

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
        echo json_encode(['error' => 'No data provided']);
        exit;
    }
    foreach ($input as $key => $value) {
        $stmt = $dbh->prepare("UPDATE app_config SET config_value = ? WHERE config_key = ?");
        $stmt->execute([$value, $key]);
    }
    echo json_encode(['success' => true, 'message' => 'Configuration updated successfully']);
} else {
    echo json_encode(['error' => 'Invalid request method']);
}