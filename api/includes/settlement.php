<?php
/**
 * includes/settlement.php
 * Core business logic for Rider & Vendor automated settlements.
 */

declare(strict_types=1);

/**
 * Executes financial settlement across all order vendors and the assigned rider.
 *
 * @param PDO $dbh Database connection
 * @param int $orderId The orders.orderid
 * @param float $vendorCommissionRate Decimal rate e.g., 0.10 for 10%
 * @param float $riderCommissionRate Decimal platform cut on delivery e.g., 0.15 for 15%
 * @return array ['success' => bool, 'error' => ?string]
 */
function settleOrderFulfillment(
    PDO $dbh,
    int $orderId,
    float $vendorCommissionRate = 0.10,
    float $riderCommissionRate = 0.15
): array {
    try {
        $dbh->beginTransaction();

        // 1. Lock and load the order
        $stmt = $dbh->prepare("
            SELECT orderid, user_id, delivery_person, status, payment_status,
                   payment_method, total_amount, delivery_fee, mileage_fee
            FROM orders
            WHERE orderid = ?
            FOR UPDATE
        ");
        $stmt->execute([$orderId]);
        $order = $stmt->fetch(PDO::FETCH_ASSOC);

        if (!$order) {
            $dbh->rollBack();
            return ['success' => false, 'error' => "Order #{$orderId} not found."];
        }

        // 2. Idempotency Check: Don't settle if transactions already exist for this order
        $checkStmt = $dbh->prepare("SELECT COUNT(*) FROM wallet_transactions WHERE order_id = ?");
        $checkStmt->execute([$orderId]);
        if ((int)$checkStmt->fetchColumn() > 0) {
            $dbh->rollBack();
            return ['success' => true, 'message' => "Order #{$orderId} already settled."];
        }

        // 3. Resolve Rider ID
        // delivery_person can store the rider's ID or name; look up user ID if needed
        $riderUserId = null;
        if (!empty($order['delivery_person'])) {
            if (is_numeric($order['delivery_person'])) {
                $riderUserId = (int)$order['delivery_person'];
            } else {
                $riderLookup = $dbh->prepare("SELECT id FROM users WHERE (CONCAT(fname, ' ', lname) = ? OR fname = ?) AND (account_type = 'rider' OR current_role = 'rider') LIMIT 1");
                $riderLookup->execute([$order['delivery_person'], $order['delivery_person']]);
                $riderUserId = $riderLookup->fetchColumn() ? (int)$riderLookup->fetchColumn() : null;
            }
        }

        // 4. Settle Rider Earnings & COD Liability
        if ($riderUserId) {
            $baseDelivery = (float)($order['delivery_fee'] ?? 0.00);
            $mileage = (float)($order['mileage_fee'] ?? 0.00);
            $totalDeliveryComp = $baseDelivery + $mileage;

            // Rider gets (1 - riderCommissionRate) of the delivery compensation
            $riderNetEarning = round($totalDeliveryComp * (1.0 - $riderCommissionRate), 2);

            // Ensure wallet exists
            $dbh->prepare("INSERT INTO user_wallets (user_id, user_type) VALUES (?, 'rider') ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id)")->execute([$riderUserId]);

            // Lock rider wallet
            $rWallet = $dbh->prepare("SELECT current_balance FROM user_wallets WHERE user_id = ? AND user_type = 'rider' FOR UPDATE");
            $rWallet->execute([$riderUserId]);
            $currentRiderBal = (float)($rWallet->fetchColumn() ?: 0.00);

            if ($riderNetEarning > 0) {
                $currentRiderBal += $riderNetEarning;
                $dbh->prepare("UPDATE user_wallets SET current_balance = ? WHERE user_id = ? AND user_type = 'rider'")->execute([$currentRiderBal, $riderUserId]);

                $dbh->prepare("
                    INSERT INTO wallet_transactions (
                        transaction_uuid, order_id, user_id, user_type,
                        transaction_type, direction, amount, balance_after, notes
                    ) VALUES (UUID(), ?, ?, 'rider', 'delivery_earning', 'credit', ?, ?, ?)
                ")->execute([
                    $orderId,
                    $riderUserId,
                    $riderNetEarning,
                    $currentRiderBal,
                    "Earnings for delivery of order #{$orderId}"
                ]);
            }

            // If Cash on Delivery, record rider liability (money held in hand)
            $isCOD = strtolower((string)$order['payment_method']) === 'cash' || strtolower((string)$order['payment_status']) === 'pending_cash';
            if ($isCOD) {
                $totalCollected = (float)$order['total_amount'];
                $currentRiderBal -= $totalCollected;

                $dbh->prepare("UPDATE user_wallets SET current_balance = ? WHERE user_id = ? AND user_type = 'rider'")->execute([$currentRiderBal, $riderUserId]);

                $dbh->prepare("
                    INSERT INTO wallet_transactions (
                        transaction_uuid, order_id, user_id, user_type,
                        transaction_type, direction, amount, balance_after, notes
                    ) VALUES (UUID(), ?, ?, 'rider', 'cash_collected_debit', 'debit', ?, ?, ?)
                ")->execute([
                    $orderId,
                    $riderUserId,
                    $totalCollected,
                    $currentRiderBal,
                    "Cash collected from customer on order #{$orderId}"
                ]);
            }
        }

        // 5. Settle Vendors (Grouped by vendor_id from items)
        $itemsStmt = $dbh->prepare("
            SELECT oi.price, oi.quantity, oi.product_id, i.vendor_id
            FROM order_items oi
            LEFT JOIN items i ON oi.product_id = i.id
            WHERE oi.order_id = ?
        ");
        $itemsStmt->execute([$orderId]);
        $items = $itemsStmt->fetchAll(PDO::FETCH_ASSOC);

        $vendorTotals = [];
        foreach ($items as $item) {
            $vId = $item['vendor_id'] ? (int)$item['vendor_id'] : null;
            if ($vId) {
                $lineTotal = (float)$item['price'] * (int)$item['quantity'];
                $vendorTotals[$vId] = ($vendorTotals[$vId] ?? 0.00) + $lineTotal;
            }
        }

        foreach ($vendorTotals as $vId => $grossSales) {
            $vendorNet = round($grossSales * (1.0 - $vendorCommissionRate), 2);

            // Ensure vendor wallet exists
            $dbh->prepare("INSERT INTO user_wallets (user_id, user_type) VALUES (?, 'vendor') ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id)")->execute([$vId]);

            // Lock vendor wallet
            $vWallet = $dbh->prepare("SELECT current_balance FROM user_wallets WHERE user_id = ? AND user_type = 'vendor' FOR UPDATE");
            $vWallet->execute([$vId]);
            $currentVendorBal = (float)($vWallet->fetchColumn() ?: 0.00);

            $currentVendorBal += $vendorNet;
            $dbh->prepare("UPDATE user_wallets SET current_balance = ? WHERE user_id = ? AND user_type = 'vendor'")->execute([$currentVendorBal, $vId]);

            $dbh->prepare("
                INSERT INTO wallet_transactions (
                    transaction_uuid, order_id, user_id, user_type,
                    transaction_type, direction, amount, balance_after, notes
                ) VALUES (UUID(), ?, ?, 'vendor', 'vendor_sale', 'credit', ?, ?, ?)
            ")->execute([
                $orderId,
                $vId,
                $vendorNet,
                $currentVendorBal,
                "Payout for items sold in order #{$orderId}"
            ]);
        }

        $dbh->commit();
        return ['success' => true];

    } catch (Throwable $e) {
        if ($dbh->inTransaction()) {
            $dbh->rollBack();
        }
        error_log("Order settlement failure on Order #{$orderId}: " . $e->getMessage());
        return ['success' => false, 'error' => $e->getMessage()];
    }
}