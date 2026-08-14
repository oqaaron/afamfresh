<?php
// =============================================================
// api/loyalty-quote.php
//
// How many of a customer's requested loyalty points can actually be
// redeemed against an order, and the resulting discount — before the order
// exists.
//
// Runs the exact same function (quoteLoyaltyRedemption()) the order-creation
// endpoints call to apply the real discount, so this preview and the actual
// charge can never disagree for the same requested amount. Writes nothing.
// =============================================================

header('Content-Type: application/json');
require_once '../admin/includes/config.php';
require_once __DIR__ . '/../includes/loyalty.php';

if (empty($_SESSION['user_id'])) {
    http_response_code(401);
    echo json_encode(['success' => false, 'error' => 'Please sign in again.']);
    exit;
}

$userId = (int)$_SESSION['user_id'];

$input = json_decode(file_get_contents('php://input'), true) ?: [];

$requestedPoints = (int)($input['points'] ?? 0);
$goodsValue = (float)($input['goods_value'] ?? 0);

if ($goodsValue <= 0) {
    echo json_encode(['success' => false, 'error' => 'A goods value is required.']);
    exit;
}

try {
    $quote = quoteLoyaltyRedemption($dbh, $userId, $requestedPoints, $goodsValue);

    $balanceStmt = $dbh->prepare("SELECT loyalty_points FROM users WHERE id = ?");
    $balanceStmt->execute([$userId]);
    $balance = (int)($balanceStmt->fetchColumn() ?: 0);

    echo json_encode([
        'success' => true,
        'points_applied' => $quote['points_applied'],
        'discount' => $quote['discount'],
        'capped' => $quote['capped'],
        'balance' => $balance,
    ]);
} catch (PDOException $e) {
    error_log('loyalty-quote: ' . $e->getMessage());
    http_response_code(500);
    echo json_encode(['success' => false, 'error' => 'Could not price that redemption right now.']);
}