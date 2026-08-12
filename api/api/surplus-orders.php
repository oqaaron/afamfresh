<?php
header('Content-Type: application/json');
require_once '../admin/includes/config.php';
require_once __DIR__ . '/../includes/api_auth.php';
require_once __DIR__ . '/../includes/surplus_payment.php';
require_once __DIR__ . '/../includes/notifications.php';

$method = $_SERVER['REQUEST_METHOD'];

$isAdminSession = isset($_SESSION['admin_logged_in']) && $_SESSION['admin_logged_in'] === true;
$sessionUserId  = isset($_SESSION['user_id']) ? (int)$_SESSION['user_id'] : 0;

try {
    if ($method === 'GET') {
        // Fetch surplus orders (same as before)
        $user_id = isset($_GET['user_id']) ? intval($_GET['user_id']) : 0;
        $vendor_id = isset($_GET['vendor_id']) ? intval($_GET['vendor_id']) : 0;

        // Both filters were optional and neither was checked, so a request
        // with no parameters at all fell through to "WHERE 1=1" and returned
        // every surplus order on the platform -- addresses, phone numbers and
        // all -- to anyone who asked. Unauthenticated.
        //
        // A caller must now say whose orders they mean, and prove it is
        // theirs. Customers pass user_id; vendors pass the vendor_id of a
        // vendor record they own.
        if (!$isAdminSession) {
            if ($sessionUserId === 0) {
                http_response_code(401);
                echo json_encode(['success' => false, 'error' => 'Please sign in again.']);
                exit;
            }
            if ($vendor_id > 0) {
                $owns = $dbh->prepare("SELECT 1 FROM vendors WHERE id = ? AND user_id = ?");
                $owns->execute([$vendor_id, $sessionUserId]);
                if (!$owns->fetchColumn()) {
                    http_response_code(403);
                    echo json_encode(['success' => false, 'error' => 'Not your vendor account.']);
                    exit;
                }
            } else {
                // No vendor_id: this is a customer asking for their own
                // orders, whatever user_id they typed.
                $user_id = requireOwnUserId($user_id);
            }
        }
        $status = isset($_GET['status']) ? trim($_GET['status']) : '';
        $limit = isset($_GET['limit']) ? intval($_GET['limit']) : 20;
        $offset = isset($_GET['offset']) ? intval($_GET['offset']) : 0;
        
        $whereClause = "WHERE 1=1";
        $params = [];
        
        if ($user_id > 0) {
            $whereClause .= " AND so.user_id = ?";
            $params[] = $user_id;
        }
        
        if ($vendor_id > 0) {
            $whereClause .= " AND sl.vendor_id = ?";
            $params[] = $vendor_id;
        }
        
        if (!empty($status)) {
            $whereClause .= " AND so.status = ?";
            $params[] = $status;
        }
        
        $stmt = $dbh->prepare("
            SELECT so.*, sl.original_price, sl.discount_percent, sl.discounted_price,
                   sl.listing_type, sl.condition_rating, sl.weight_per_unit_kg,
                   i.name as product_name, i.image, sl.is_weight_based,
                   v.business_name, v.location as vendor_location,
                   u.fname as customer_fname, u.lname as customer_lname
            FROM surplus_orders so
            JOIN surplus_listings sl ON so.listing_id = sl.id
            JOIN items i ON sl.product_id = i.id
            JOIN vendors v ON sl.vendor_id = v.id
            JOIN users u ON so.user_id = u.id
            $whereClause
            ORDER BY so.created_at DESC
            LIMIT ? OFFSET ?
        ");
        $params[] = $limit;
        $params[] = $offset;
        $stmt->execute($params);
        $orders = $stmt->fetchAll(PDO::FETCH_ASSOC);
        
        echo json_encode(['success' => true, 'orders' => $orders]);
        
    } elseif ($method === 'POST') {
        // Create new surplus order with weight-based delivery
        $input = json_decode(file_get_contents('php://input'), true);
        
        $listing_id = intval($input['listing_id'] ?? 0);
        // The order is placed for whoever is signed in. This was taken from
        // the request body, so anyone could place an order in anyone else's
        // name -- against their account, to their delivery address.
        $user_id = requireOwnUserId($input['user_id'] ?? 0);
        $quantity = floatval($input['quantity'] ?? 0); // Can be decimal for kg
        $delivery_address = trim($input['delivery_address'] ?? '');
        $delivery_area = trim($input['delivery_area'] ?? '');
        $delivery_lat = isset($input['delivery_lat']) ? floatval($input['delivery_lat']) : null;
        $delivery_lng = isset($input['delivery_lng']) ? floatval($input['delivery_lng']) : null;
        $scheduled_delivery_date = isset($input['scheduled_delivery_date']) ? trim($input['scheduled_delivery_date']) : null;
        $scheduled_delivery_slot = isset($input['scheduled_delivery_slot']) ? trim($input['scheduled_delivery_slot']) : null;
        $order_notes = trim($input['order_notes'] ?? '');
        
        // Validate required fields
        if ($listing_id === 0 || $user_id === 0 || $quantity === 0) {
            echo json_encode(['error' => 'Missing required fields']);
            exit;
        }
        
        // Hand back stock held by checkouts that were abandoned on the payment
        // page. Done here because this is the moment the numbers have to be
        // right — a customer being told "sold out" by an order nobody paid for
        // is the failure this prevents. See includes/surplus_payment.php.
        releaseStaleSurplusReservations($dbh);

        // From here to the commit is one transaction.
        //
        // Reading remaining_quantity, deciding it is enough, and then
        // decrementing it used to be three separate statements with no lock
        // between them. Two customers ordering the last 40kg at the same moment
        // both read 40, both passed the check, and the listing went to -40:
        // oversold, with a vendor who cannot fulfil either order.
        //
        // FOR UPDATE makes the second request wait for the first to commit, so
        // it reads the quantity that actually remains.
        $dbh->beginTransaction();

        $listingStmt = $dbh->prepare("
            SELECT sl.*, i.is_weight_based, i.category, i.name AS product_name,
                   v.user_id AS vendor_user_id, v.business_name
            FROM surplus_listings sl
            JOIN items i ON sl.product_id = i.id
            JOIN vendors v ON v.id = sl.vendor_id
            WHERE sl.id = ? AND sl.status = 'active'
            FOR UPDATE
        ");
        $listingStmt->execute([$listing_id]);
        $listing = $listingStmt->fetch(PDO::FETCH_ASSOC);

        if (!$listing) {
            $dbh->rollBack();
            echo json_encode(['error' => 'Listing not found or not active']);
            exit;
        }

        // Check if enough quantity available
        if ($listing['remaining_quantity'] < $quantity) {
            $dbh->rollBack();
            echo json_encode(['error' => 'Not enough quantity available. Only ' . $listing['remaining_quantity'] . ' left']);
            exit;
        }

        // Calculate total weight
        $weightPerUnit = $listing['weight_per_unit_kg'] ?? 1.00;
        $totalWeightKg = $quantity * $weightPerUnit;
        
        // Check weight limit (max 1000kg / 1 tonne)
        if ($totalWeightKg > 1000) {
            $dbh->rollBack();
            echo json_encode(['error' => 'Maximum order weight is 1000kg (1 tonne). Your order weighs ' . number_format($totalWeightKg, 2) . 'kg']);
            exit;
        }

        // Check minimum order value
        $total_price = $listing['discounted_price'] * $quantity;
        if ($total_price < 250000) {
            $dbh->rollBack();
            echo json_encode(['error' => 'Minimum order value for surplus is UGX 250,000. Current total: UGX ' . number_format($total_price, 0)]);
            exit;
        }

        // Check minimum quantity for weight-based products
        if (($listing['is_weight_based'] || $listing['is_weight_based'] === 1) && $quantity < 20) {
            $dbh->rollBack();
            echo json_encode(['error' => 'Minimum order for bulk/weight-based surplus items is 20 kg']);
            exit;
        }
        
        // Calculate delivery fee based on weight
        $delivery_fee = 0;
        $delivery_fee_breakdown = [];
        
        if (!$listing['pickup_only'] && !empty($delivery_address)) {
            // Get delivery settings
            $settingsStmt = $dbh->query("SELECT * FROM surplus_delivery_settings LIMIT 1");
            $settings = $settingsStmt->fetch(PDO::FETCH_ASSOC);
            
            $base_fee = $settings['base_fee'] ?? 5000;
            $fee_per_kg = $settings['fee_per_kg'] ?? 500;
            $free_delivery_threshold = $settings['free_delivery_threshold'] ?? 500000;
            
            // Calculate delivery fee based on weight
            if ($total_price >= $free_delivery_threshold) {
                $delivery_fee = 0;
                $delivery_fee_breakdown = ['type' => 'free', 'reason' => 'Order exceeds free delivery threshold'];
            } else {
                // Weight-based calculation: base fee + (weight in kg × fee per kg)
                $delivery_fee = $base_fee + ($totalWeightKg * $fee_per_kg);
                
                // Cap maximum delivery fee (optional)
                $max_delivery_fee = 50000;
                if ($delivery_fee > $max_delivery_fee) {
                    $delivery_fee = $max_delivery_fee;
                }
                
                $delivery_fee_breakdown = [
                    'type' => 'weight_based',
                    'base_fee' => $base_fee,
                    'weight_kg' => $totalWeightKg,
                    'fee_per_kg' => $fee_per_kg,
                    'weight_charge' => $totalWeightKg * $fee_per_kg,
                    'total_fee' => $delivery_fee
                ];
            }
        }
        
        // Generate pickup code if pickup-only
        $pickup_code = null;
        if ($listing['pickup_only']) {
            $pickup_code = strtoupper(substr(md5(uniqid() . $listing_id . $user_id), 0, 8));
        }
        
        // Insert order
        $stmt = $dbh->prepare("
            INSERT INTO surplus_orders 
            (listing_id, user_id, quantity, total_price, total_weight_kg, status, 
             delivery_address, delivery_area, delivery_lat, delivery_lng, 
             delivery_fee, delivery_fee_breakdown, pickup_code, 
             scheduled_delivery_date, scheduled_delivery_slot, order_notes)
            VALUES (?, ?, ?, ?, ?, 'pending', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ");
        
        $result = $stmt->execute([
            $listing_id,
            $user_id,
            $quantity,
            $total_price,
            $totalWeightKg,
            $delivery_address ?: null,
            $delivery_area ?: null,
            $delivery_lat,
            $delivery_lng,
            $delivery_fee,
            json_encode($delivery_fee_breakdown),
            $pickup_code,
            $scheduled_delivery_date ?: null,
            $scheduled_delivery_slot ?: null,
            $order_notes ?: null
        ]);
        
        if (!$result) {
            $dbh->rollBack();
            echo json_encode(['error' => 'Failed to create surplus order']);
            exit;
        }

        $order_id = $dbh->lastInsertId();

        // Update listing remaining quantity
        $updateStmt = $dbh->prepare("
            UPDATE surplus_listings
            SET remaining_quantity = remaining_quantity - ?
            WHERE id = ?
        ");
        $updateStmt->execute([$quantity, $listing_id]);

        // Check if listing is now sold out
        $checkStmt = $dbh->prepare("SELECT remaining_quantity FROM surplus_listings WHERE id = ?");
        $checkStmt->execute([$listing_id]);
        $remaining = $checkStmt->fetch(PDO::FETCH_ASSOC)['remaining_quantity'];

        if ($remaining <= 0) {
            $soldStmt = $dbh->prepare("UPDATE surplus_listings SET status = 'sold' WHERE id = ?");
            $soldStmt->execute([$listing_id]);
        }

        // Fetch created order
        $fetchStmt = $dbh->prepare("SELECT * FROM surplus_orders WHERE id = ?");
        $fetchStmt->execute([$order_id]);
        $order = $fetchStmt->fetch(PDO::FETCH_ASSOC);

        $dbh->commit();

        // Notifications go out AFTER the commit, deliberately. Queuing them
        // inside the transaction would mean a rollback still left a job in
        // notification_queue telling a vendor about an order that does not
        // exist. A failure to notify is logged, never surfaced: the order is
        // real and paid-for regardless of whether the message went out.
        $grand_total = $total_price + $delivery_fee;
        try {
            addNotification(
                (int)$listing['vendor_user_id'],
                'New surplus order #' . $order_id,
                'Someone ordered ' . rtrim(rtrim(number_format((float)$quantity, 2, '.', ''), '0'), '.')
                    . ' of "' . $listing['product_name'] . '". Total UGX '
                    . number_format($grand_total, 0) . '. It is not yours to pack until it is paid.',
                'order',
                null,
                ['push']
            );

            // SMS as well as push and email. This is one of only two moments
            // that get a text — the other is out-for-delivery in api/rider.php.
            // A quarter-million-shilling order is worth confirming somewhere the
            // customer will see it without opening anything, and it is the
            // message they need if the payment then fails.
            addNotification(
                (int)$user_id,
                'Order placed: ' . $listing['product_name'],
                'Your surplus order #' . $order_id . ' from ' . $listing['business_name']
                    . ' totals UGX ' . number_format($grand_total, 0)
                    . ($delivery_fee > 0 ? ' including UGX ' . number_format($delivery_fee, 0) . ' delivery' : '')
                    . '. We will confirm once payment is received.',
                'order',
                null,
                ['push', 'email', 'sms']
            );
        } catch (Throwable $e) {
            error_log("Surplus order $order_id created but notifications failed: " . $e->getMessage());
        }

        echo json_encode([
            'success' => true,
            'message' => 'Surplus order created successfully',
            'order' => $order,
            'delivery_fee' => $delivery_fee,
            'total_weight_kg' => $totalWeightKg,
            'grand_total' => $grand_total
        ]);

    } elseif ($method === 'PUT') {
        // Update surplus order status (same as before)
        $input = json_decode(file_get_contents('php://input'), true);
        
        $order_id = intval($input['order_id'] ?? 0);
        $status = trim($input['status'] ?? '');
        
        if ($order_id === 0 || empty($status)) {
            echo json_encode(['error' => 'order_id and status are required']);
            exit;
        }
        
        $valid_statuses = ['pending', 'confirmed', 'processing', 'ready', 'delivered', 'cancelled', 'refunded'];
        if (!in_array($status, $valid_statuses)) {
            echo json_encode(['error' => 'Invalid status']);
            exit;
        }
        
        // Who may move this order.
        //
        // This branch had no authorisation of any kind: order_id and status came
        // from the body and were written straight through, so anyone at all
        // could mark any order delivered. That was already wrong; with earnings
        // credited on delivery below it becomes "anyone can pay a vendor".
        //
        // The vendor who owns the listing may move their own orders; an admin
        // may move any.
        $owner = $dbh->prepare(
            "SELECT sl.vendor_id, v.user_id
               FROM surplus_orders so
               JOIN surplus_listings sl ON sl.id = so.listing_id
               JOIN vendors v ON v.id = sl.vendor_id
              WHERE so.id = ?"
        );
        $owner->execute([$order_id]);
        $ownerRow = $owner->fetch(PDO::FETCH_ASSOC);

        if (!$ownerRow) {
            echo json_encode(['error' => 'No such order']);
            exit;
        }
        if (!$isAdminSession && (int)$ownerRow['user_id'] !== $sessionUserId) {
            http_response_code(403);
            echo json_encode(['success' => false, 'error' => 'That order is not yours.']);
            exit;
        }

        // A vendor cannot ship an order nobody has paid for.
        //
        // Creating an order takes the stock but not the money; until payment
        // lands it is a reservation that the release sweep may cancel out from
        // under everyone. Letting a vendor march that to 'delivered' would
        // credit them for goods they were never paid for, and the credit is
        // idempotent — it cannot be taken back by re-running anything.
        //
        // Admins are exempt: they need to be able to fix a payment that
        // succeeded at Pesapal but never reconciled here.
        $payment = $dbh->prepare("SELECT payment_status FROM surplus_orders WHERE id = ?");
        $payment->execute([$order_id]);
        $paymentStatus = (string)$payment->fetchColumn();
        $isPaidOrCash = in_array($paymentStatus, ['paid', 'pending_cash'], true);

        if (!$isAdminSession && !$isPaidOrCash
            && !in_array($status, ['cancelled', 'pending'], true)) {
            http_response_code(409);
            echo json_encode([
                'success' => false,
                'error'   => 'This order has not been paid for yet.'
            ]);
            exit;
        }

        $updateFields = ["status = ?", "updated_at = NOW()"];
        $params = [$status, $order_id];

        if ($status === 'confirmed') {
            $updateFields[] = "confirmed_at = NOW()";
        } elseif ($status === 'delivered') {
            $updateFields[] = "delivered_at = NOW()";
        }

        $sql = "UPDATE surplus_orders SET " . implode(', ', $updateFields) . " WHERE id = ?";
        $stmt = $dbh->prepare($sql);
        $stmt->execute($params);

        // Credit the vendor on delivery. Idempotent, so a status set to
        // delivered twice does not pay twice.
        //
        // A crediting failure is logged, not surfaced: the delivery genuinely
        // happened, and refusing the status change because the ledger write
        // failed would leave the order stuck instead.
        if ($status === 'delivered') {
            require_once __DIR__ . '/../includes/vendor_earnings.php';
            $credit = creditVendorEarnings($dbh, $order_id);
            if (!$credit['ok']) {
                error_log("Surplus order $order_id delivered but vendor not credited: " . $credit['error']);
            }
        }

        // Tell the customer. Without this the only party who learns an order
        // moved is the vendor who moved it, and the customer is left refreshing
        // a screen to find out whether 250,000 shillings of produce is coming.
        //
        // Only the states a customer can act on get a message. 'processing' is
        // deliberately silent: it means the vendor has started picking, which
        // changes nothing the customer can do and would just be noise.
        $notify = [
            'confirmed' => ['Order confirmed', 'has accepted your order and is preparing it.'],
            'ready'     => ['Order ready', 'has your order ready.'],
            'delivered' => ['Order delivered', 'has marked your order delivered.'],
            'cancelled' => ['Order cancelled', 'has cancelled your order.'],
            'refunded'  => ['Order refunded', 'has refunded your order.'],
        ];

        if (isset($notify[$status])) {
            try {
                $who = $dbh->prepare(
                    "SELECT so.user_id, so.pickup_code, i.name AS product_name, v.business_name
                       FROM surplus_orders so
                       JOIN surplus_listings sl ON sl.id = so.listing_id
                       JOIN items i ON i.id = sl.product_id
                       JOIN vendors v ON v.id = sl.vendor_id
                      WHERE so.id = ?"
                );
                $who->execute([$order_id]);
                $row = $who->fetch(PDO::FETCH_ASSOC);

                if ($row) {
                    [$title, $phrase] = $notify[$status];
                    $body = $row['business_name'] . ' ' . $phrase
                        . ' (order #' . $order_id . ', ' . $row['product_name'] . ')';

                    // The collection code is only useful at the moment of
                    // collection, so it rides along with "ready" rather than
                    // being buried in the order placed weeks earlier.
                    if ($status === 'ready' && !empty($row['pickup_code'])) {
                        $body .= ' Your collection code is ' . $row['pickup_code'] . '.';
                    }

                    addNotification(
                        (int)$row['user_id'],
                        $title,
                        $body,
                        'order',
                        null,
                        // Email on the two that decide whether someone needs to
                        // be somewhere; push alone for the rest.
                        in_array($status, ['ready', 'cancelled'], true)
                            ? ['push', 'email']
                            : ['push']
                    );
                }
            } catch (Throwable $e) {
                error_log("Surplus order $order_id moved to $status but customer not notified: " . $e->getMessage());
            }
        }

        echo json_encode(['success' => true, 'message' => 'Order status updated successfully']);
        
    } else {
        echo json_encode(['error' => 'Invalid request method']);
    }
    
} catch (PDOException $e) {
    // Order creation runs inside a transaction that holds a row lock on the
    // listing. Without this rollback an exception would leave that lock held
    // until the connection closed, blocking every other customer trying to buy
    // the same listing.
    if ($dbh->inTransaction()) {
        $dbh->rollBack();
    }
    error_log('surplus-orders: ' . $e->getMessage());
    echo json_encode(['error' => 'Database error: ' . $e->getMessage()]);
}
?>