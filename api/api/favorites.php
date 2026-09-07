<?php
header("Content-Type: application/json");
require_once 'db_connection.php'; // Update with your actual DB connection file

$method = $_SERVER['REQUEST_METHOD'];

switch ($method) {
    case 'GET':
        // Get all favorite product IDs for a specific user
        $user_id = isset($_GET['user_id']) ? intval($_GET['user_id']) : 0;
        
        if ($user_id <= 0) {
            echo json_encode(["status" => "error", "message" => "Invalid user ID"]);
            exit;
        }

        $stmt = $conn->prepare("SELECT product_id FROM favorites WHERE user_id = ?");
        $stmt->bind_param("i", $user_id);
        $stmt->execute();
        $result = $stmt->get_result();
        
        $favorites = [];
        while ($row = $result->fetch_assoc()) {
            $favorites[] = intval($row['product_id']);
        }

        echo json_encode(["status" => "success", "favorites" => $favorites]);
        break;

    case 'POST':
        // Add a product to favorites
        $data = json_decode(file_get_contents("php://input"), true);
        $user_id = isset($data['user_id']) ? intval($data['user_id']) : 0;
        $product_id = isset($data['product_id']) ? intval($data['product_id']) : 0;

        if ($user_id <= 0 || $product_id <= 0) {
            echo json_encode(["status" => "error", "message" => "Invalid parameters"]);
            exit;
        }

        $stmt = $conn->prepare("INSERT INTO favorites (user_id, product_id) VALUES (?, ?) ON DUPLICATE KEY UPDATE created_at = CURRENT_TIMESTAMP");
        $stmt->bind_param("ii", $user_id, $product_id);
        
        if ($stmt->execute()) {
            echo json_encode(["status" => "success", "message" => "Added to favorites"]);
        } else {
            echo json_encode(["status" => "error", "message" => "Failed to add favorite"]);
        }
        break;

    case 'DELETE':
        // Remove a product from favorites
        $user_id = isset($_GET['user_id']) ? intval($_GET['user_id']) : 0;
        $product_id = isset($_GET['product_id']) ? intval($_GET['product_id']) : 0;

        if ($user_id <= 0 || $product_id <= 0) {
            echo json_encode(["status" => "error", "message" => "Invalid parameters"]);
            exit;
        }

        $stmt = $conn->prepare("DELETE FROM favorites WHERE user_id = ? AND product_id = ?");
        $stmt->bind_param("ii", $user_id, $product_id);
        
        if ($stmt->execute()) {
            echo json_encode(["status" => "success", "message" => "Removed from favorites"]);
        } else {
            echo json_encode(["status" => "error", "message" => "Failed to remove favorite"]);
        }
        break;

    default:
        header("HTTP/1.1 405 Method Not Allowed");
        echo json_encode(["status" => "error", "message" => "Method not allowed"]);
        break;
}
?>