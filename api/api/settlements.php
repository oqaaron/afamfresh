<?php
/**
 * api/settlements.php
 * Wallet and Payout API for Riders and Vendors
 */

declare(strict_types=1);

session_start();
require_once dirname(__DIR__) . '/admin/includes/config.php';
require_once dirname(__DIR__) . '/includes/settlement.php';

header('Content-Type: application/json');

$userId = $_SESSION['user_id'] ?? null;
if (!$userId) {
    echo json_encode(['success' => false, 'error' => 'Unauthorized']);
    exit;
}

// Fetch user type/role
$stmt = $dbh->prepare("SELECT current_role, account_type FROM users WHERE id = ?");
$stmt->execute([$userId]);
$user = $stmt->fetch(PDO::FETCH_ASSOC);

$userRole = strtolower($user['account_type'] ?? $user['current_role'] ?? '');
if (!in_array($userRole, ['rider', 'vendor'], true)) {
    echo json_encode(['success' => false, 'error' => 'Wallet is only accessible to riders and vendors.']);
    exit;
}

$action = $_GET['action'] ?? $_POST['action'] ?? '';
$rawInput = json_decode(file_get_contents('php://input'), true) ?? [];

switch ($action) {

    // 1. Get current balance
    case 'balance':
        $stmt = $dbh->prepare("SELECT current_balance, pending_balance, currency, updated_at FROM user_wallets WHERE user_id = ? AND user_type = ?");
        $stmt->execute([$userId, $userRole]);
        $wallet = $stmt->fetch(PDO::FETCH_ASSOC);

        if (!$wallet) {
            $wallet = [
                'current_balance' => '0.00',
                'pending_balance' => '0.00',
                'currency' => 'UGX',
                'updated_at' => date('Y-m-d H:i:s')
            ];
        }

        echo json_encode([
            'success' => true,
            'wallet' => [
                'current_balance' => (float)$wallet['current_balance'],
                'pending_balance' => (float)$wallet['pending_balance'],
                'currency'        => $wallet['currency'],
                'updated_at'      => $wallet['updated_at']
            ]
        ]);
        exit;

    // 2. Transaction history
    case 'transactions':
        $limit = max(1, min(50, (int)($_GET['limit'] ?? 20)));
        $offset = max(0, (int)($_GET['offset'] ?? 0));

        $stmt = $dbh->prepare("
            SELECT transaction_uuid, order_id, transaction_type, direction,
                   amount, balance_after, reference_id, notes, created_at
            FROM wallet_transactions
            WHERE user_id = ? AND user_type = ?
            ORDER BY id DESC
            LIMIT ? OFFSET ?
        ");
        $stmt->bindValue(1, $userId, PDO::PARAM_INT);
        $stmt->bindValue(2, $userRole, PDO::PARAM_STR);
        $stmt->bindValue(3, $limit, PDO::PARAM_INT);
        $stmt->bindValue(4, $offset, PDO::PARAM_INT);
        $stmt->execute();
        
        $txs = $stmt->fetchAll(PDO::FETCH_ASSOC);

        $formatted = array_map(function($tx) {
            return [
                'id'            => $tx['transaction_uuid'],
                'order_id'      => $tx['order_id'] ? (int)$tx['order_id'] : null,
                'type'          => $tx['transaction_type'],
                'direction'     => $tx['direction'],
                'amount'        => (float)$tx['amount'],
                'balance_after' => (float)$tx['balance_after'],
                'notes'         => $tx['notes'],
                'created_at'    => $tx['created_at']
            ];
        }, $txs);

        echo json_encode(['success' => true, 'transactions' => $formatted]);
        exit;

    // 3. Request withdrawal / payout
    case 'request_payout':
        $amount = (float)($rawInput['amount'] ?? $_POST['amount'] ?? 0.0);
        $method = trim((string)($rawInput['payment_method'] ?? $_POST['payment_method'] ?? 'mobile_money'));
        $accNum = trim((string)($rawInput['account_number'] ?? $_POST['account_number'] ?? ''));
        $accName = trim((string)($rawInput['account_name'] ?? $_POST['account_name'] ?? ''));

        if ($amount < 5000.0) {
            echo json_encode(['success' => false, 'error' => 'Minimum withdrawal is UGX 5,000.']);
            exit;
        }

        if (empty($accNum) || empty($accName)) {
            echo json_encode(['success' => false, 'error' => 'Account number and account name are required.']);
            exit;
        }

        try {
            $dbh->beginTransaction();

            $wStmt = $dbh->prepare("SELECT current_balance FROM user_wallets WHERE user_id = ? AND user_type = ? FOR UPDATE");
            $wStmt->execute([$userId, $userRole]);
            $currentBal = (float)($wStmt->fetchColumn() ?: 0.0);

            if ($currentBal < $amount) {
                $dbh->rollBack();
                echo json_encode(['success' => false, 'error' => 'Insufficient wallet balance.']);
                exit;
            }

            $newBal = $currentBal - $amount;

            // Deduct from balance
            $dbh->prepare("UPDATE user_wallets SET current_balance = ? WHERE user_id = ? AND user_type = ?")
                ->execute([$newBal, $userId, $userRole]);

            // Ledger entry
            $dbh->prepare("
                INSERT INTO wallet_transactions (
                    transaction_uuid, user_id, user_type, transaction_type,
                    direction, amount, balance_after, notes
                ) VALUES (UUID(), ?, ?, 'payout_withdrawal', 'debit', ?, ?, ?)
            ")->execute([$userId, $userRole, $amount, $newBal, "Payout request to {$accNum} ({$accName})"]);

            // Payout request record
            $dbh->prepare("
                INSERT INTO payout_requests (
                    user_id, user_type, amount, fee, net_payout, payment_method, account_number, account_name, status
                ) VALUES (?, ?, ?, 0.00, ?, ?, ?, ?, 'pending')
            ")->execute([$userId, $userRole, $amount, $amount, $method, $accNum, $accName]);

            $payoutId = (int)$dbh->lastInsertId();
            $dbh->commit();

            echo json_encode([
                'success'   => true,
                'message'   => 'Payout request submitted successfully.',
                'payout_id' => $payoutId,
                'new_balance' => $newBal
            ]);
        } catch (Throwable $e) {
            if ($dbh->inTransaction()) $dbh->rollBack();
            error_log("Payout request failure: " . $e->getMessage());
            echo json_encode(['success' => false, 'error' => 'Could not process payout request.']);
        }
        exit;

    default:
        echo json_encode(['success' => false, 'error' => 'Invalid action']);
        exit;
}