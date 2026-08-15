<?php
header('Content-Type: application/json');
require_once '../admin/includes/config.php';
require_once __DIR__ . '/../includes/api_auth.php';
require_once __DIR__ . '/../includes/Bulk_payment.php';
require_once __DIR__ . '/../includes/Bulk_delivery_fee.php';
require_once __DIR__ . '/../includes/service_area.php';
require_once __DIR__ . '/../includes/notifications.php';

$method = $_SERVER['REQUEST_METHOD'];

$isAdminSession = isset($_SESSION['admin_logged_in']) && $_SESSION['admin_logged_in'] === true;
$sessionUserId  = isset($_SESSION['user_id']) ? (int)$_SESSION['user_id'] : 0;

try {
    if ($method === 'GET') {
        // Fetch Bulk orders (same as before)
        $user_id = isset($_GET['user_id']) ? intval($_GET['user_id']) : 0;
        $vendor_id = isset($_GET['vendor_id']) ? intval($_GET['vendor_id']) : 0;

        // Both filters were optional and neither was checked, so a request
        // with no parameters at all fell through to "WHERE 1=1" and returned
        // every Bulk order on the platform -- addresses, phone numbers and
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
            FROM Bulk_orders so
            JOIN Bulk_listings sl ON so.listing_id = sl.id
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
        
    } elseif ($method === 'POST' && ($_GET['action'] ?? '') === 'confirm_receipt') {
        // The customer's confirm-and-rate step. Kept as a plain POST rather
        // than PUT/PATCH (which is what updateBulkOrderStatus above uses)
        // because PHP does not auto-populate $_FILES for those methods, and
        // this action optionally takes a photo. Gated on the action query
        // param specifically so it does not fall into "create order" below,
        // which is what an unqualified POST to this file has always meant.
        require_once __DIR__ . '/../includes/order_feedback.php';

        $orderId = intval($_POST['order_id'] ?? 0);
        $userId  = requireOwnUserId(intval($_POST['user_id'] ?? 0));
        if ($orderId === 0) {
            echo json_encode(['success' => false, 'error' => 'order_id is required']);
            exit;
        }

        $ratingRaw = isset($_POST['rating']) ? intval($_POST['rating']) : null;
        if ($ratingRaw !== null && ($ratingRaw < 1 || $ratingRaw > 5)) {
            echo json_encode(['success' => false, 'error' => 'Rating must be between 1 and 5']);
            exit;
        }

        $target = loadFeedbackTarget($dbh, 'Bulk', $orderId, $userId);
        if (!$target) {
            echo json_encode(['success' => false, 'error' => 'Order not found']);
            exit;
        }
        if (!$target['delivery_confirmed']) {
            echo json_encode([
                'success' => false,
                'error' => 'This order has not been marked delivered yet.'
            ]);
            exit;
        }
        if ($target['completed_at'] !== null) {
            echo json_encode([
                'success' => false,
                'error' => 'You already confirmed receipt of this order.'
            ]);
            exit;
        }

        $photoFilename = null;
        if (isset($_FILES['photo']) && $_FILES['photo']['error'] !== UPLOAD_ERR_NO_FILE) {
            require_once __DIR__ . '/../includes/image_upload.php';
            $result = saveUploadedImage($_FILES['photo'], 'proof', 'customer_confirm', null);
            if (!$result['ok']) {
                echo json_encode(['success' => false, 'error' => $result['error']]);
                exit;
            }
            $photoFilename = $result['filename'];
        }

        $emoji = trim((string)($_POST['emoji_reaction'] ?? ''));
        if ($emoji !== '' && !in_array($emoji, validEmojiReactions(), true)) {
            $emoji = '';
        }

        $intOrNull = function ($v) {
            return $v === null || $v === '' ? null : (int)$v;
        };

        saveCustomerReceiptConfirmation($dbh, 'Bulk', $orderId, [
            'rating'                 => $ratingRaw,
            'rating_speed'           => $intOrNull($_POST['rating_speed'] ?? null),
            'rating_professionalism' => $intOrNull($_POST['rating_professionalism'] ?? null),
            'rating_packaging'       => $intOrNull($_POST['rating_packaging'] ?? null),
            'feedback'               => trim((string)($_POST['feedback'] ?? '')) ?: null,
            'emoji'                  => $emoji ?: null,
            'photo_filename'         => $photoFilename,
        ]);

        echo json_encode(['success' => true, 'message' => 'Thanks for confirming!']);
        exit;

    } elseif ($method === 'POST') {
        // Create new Bulk order with weight-based delivery
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

        // Checked before the transaction opens: refusing an out-of-area pin is
        // the one validation that does not need a row lock, and doing it here
        // means an impossible order never touches the listing's stock.
        if ($delivery_lat !== null && $delivery_lng !== null
            && !isInServiceArea($delivery_lat, $delivery_lng)) {
            echo json_encode(['error' => serviceAreaMessage()]);
            exit;
        }
        
        // Hand back stock held by checkouts that were abandoned on the payment
        // page. Done here because this is the moment the numbers have to be
        // right — a customer being told "sold out" by an order nobody paid for
        // is the failure this prevents. See includes/Bulk_payment.php.
        releaseStaleBulkReservations($dbh);

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

        // is_weight_based comes from sl.*, not from items.
        //
        // This selected `i.is_weight_based` and there is no such column on
        // `items` — it lives on Bulk_listings, set when the vendor creates
        // the listing. Every order creation therefore died with SQLSTATE 42S22
        // before touching a row. It went unnoticed because nothing in any app
        // could reach this endpoint until Bulk checkout was built.
        //
        // 'approved', not 'active'. The status enum is
        // ('pending','approved','rejected','cancelled') and an admin approval
        // writes 'approved', so this condition matched nothing that had ever
        // existed and every order would have been refused as "not active".
        $listingStmt = $dbh->prepare("
            SELECT sl.*, i.category, i.name AS product_name,
                   v.user_id AS vendor_user_id, v.business_name,
                   v.lat AS vendor_lat, v.lng AS vendor_lng
            FROM Bulk_listings sl
            JOIN items i ON sl.product_id = i.id
            JOIN vendors v ON v.id = sl.vendor_id
            WHERE sl.id = ? AND sl.status = 'approved'
            FOR UPDATE
        ");
        $listingStmt->execute([$listing_id]);
        $listing = $listingStmt->fetch(PDO::FETCH_ASSOC);

        if (!$listing) {
            $dbh->rollBack();
            echo json_encode(['error' => 'That deal is no longer available.']);
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
            echo json_encode(['error' => 'Minimum order value for Bulk is UGX 250,000. Current total: UGX ' . number_format($total_price, 0)]);
            exit;
        }

        // Check minimum quantity for weight-based products
        if (($listing['is_weight_based'] || $listing['is_weight_based'] === 1) && $quantity < 20) {
            $dbh->rollBack();
            echo json_encode(['error' => 'Minimum order for bulk/weight-based Bulk items is 20 kg']);
            exit;
        }
        
        // The delivery fee, itemised.
        //
        // Was weight-only: base + kg, blind to how far the load actually
        // travelled, and carrying none of the service, insurance or processing
        // charges the shop side has always applied. A tonne moved 3km and the
        // same tonne moved 40km cost the customer exactly the same.
        //
        // Computed by the same function the quote endpoint uses, so what the
        // customer was shown at checkout and what they are charged here cannot
        // drift apart. See includes/Bulk_delivery_fee.php.
        $distance = null;
        if (!$listing['pickup_only'] && !empty($delivery_address)) {
            $distance = BulkDeliveryDistance(
                isset($listing['vendor_lat']) ? (float)$listing['vendor_lat'] : null,
                isset($listing['vendor_lng']) ? (float)$listing['vendor_lng'] : null,
                $delivery_lat,
                $delivery_lng
            );
        }

        $feeBreakdown = calculateBulkDeliveryFee(
            $dbh,
            (float)$total_price,
            (float)$totalWeightKg,
            $distance,
            (bool)$listing['pickup_only'] || empty($delivery_address)
        );

        $delivery_fee = (float)$feeBreakdown['total_fee'];
        $delivery_fee_breakdown = $feeBreakdown;
        $delivery_distance_km = $feeBreakdown['distance'];

        // Loyalty redemption, if requested. Quoted against total_price,
        // which is already goods-only for a Bulk order. The discount is
        // stored SEPARATELY (loyalty_discount), never subtracted from
        // total_price or delivery_fee themselves — those feed the vendor's
        // and rider's payouts, and a loyalty discount is the platform's
        // cost, not theirs. BulkPayableTotal() (includes/Bulk_payment.php)
        // is what actually nets it out of the Pesapal charge. The point
        // DEBIT itself waits for payment to confirm — see
        // settleLoyaltyRedemption(), called from api/payment.php's verify
        // action and the Pesapal IPN/callback handlers.
        require_once __DIR__ . '/../includes/loyalty.php';
        $pointsToRedeem = intval($input['points_redeem'] ?? 0);
        $redeemQuote = quoteLoyaltyRedemption($dbh, $user_id, $pointsToRedeem, (float)$total_price);
        $loyalty_discount = $redeemQuote['discount'];

        // Generate pickup code if pickup-only
        $pickup_code = null;
        if ($listing['pickup_only']) {
            $pickup_code = strtoupper(substr(md5(uniqid() . $listing_id . $user_id), 0, 8));
        }
        
        // Insert order
        $stmt = $dbh->prepare("
            INSERT INTO Bulk_orders
            (listing_id, user_id, quantity, total_price, total_weight_kg, status,
             delivery_address, delivery_area, delivery_lat, delivery_lng,
             delivery_fee, delivery_fee_breakdown, delivery_distance_km, pickup_code,
             scheduled_delivery_date, scheduled_delivery_slot, order_notes,
             points_redeemed, loyalty_discount)
            VALUES (?, ?, ?, ?, ?, 'pending', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
            $delivery_distance_km,
            $pickup_code,
            $scheduled_delivery_date ?: null,
            $scheduled_delivery_slot ?: null,
            $order_notes ?: null,
            $redeemQuote['points_applied'],
            $loyalty_discount
        ]);
        
        if (!$result) {
            $dbh->rollBack();
            echo json_encode(['error' => 'Failed to create Bulk order']);
            exit;
        }

        $order_id = $dbh->lastInsertId();

        // Update listing remaining quantity
        $updateStmt = $dbh->prepare("
            UPDATE Bulk_listings
            SET remaining_quantity = remaining_quantity - ?
            WHERE id = ?
        ");
        $updateStmt->execute([$quantity, $listing_id]);

        // Sold out needs no status change.
        //
        // This used to write status = 'sold', which is not one of the enum's
        // values ('pending','approved','rejected','cancelled'). MySQL coerces
        // an unknown ENUM value to the empty string, so a sold-out listing
        // ended up with NO status at all — invisible to every query that
        // filters on one, and unrecoverable by an admin looking at the page.
        //
        // Nothing is needed instead: api/Bulk-listings.php already hides
        // sold-out listings with `remaining_quantity > 0`, so the quantity is
        // the single source of truth and the status stays meaningful.

        // Fetch created order
        $fetchStmt = $dbh->prepare("SELECT * FROM Bulk_orders WHERE id = ?");
        $fetchStmt->execute([$order_id]);
        $order = $fetchStmt->fetch(PDO::FETCH_ASSOC);

        $dbh->commit();

        // Notifications go out AFTER the commit, deliberately. Queuing them
        // inside the transaction would mean a rollback still left a job in
        // notification_queue telling a vendor about an order that does not
        // exist. A failure to notify is logged, never surfaced: the order is
        // real and paid-for regardless of whether the message went out.
        $grand_total = $total_price + $delivery_fee;
        // What the customer is actually asked to pay — mirrors
        // BulkPayableTotal() so this message and the real Pesapal charge
        // never disagree.
        $payable_total = $grand_total - $loyalty_discount;
        try {
            addNotification(
                (int)$listing['vendor_user_id'],
                'New Bulk order #' . $order_id,
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
                'Your Bulk order #' . $order_id . ' from ' . $listing['business_name']
                    . ' totals UGX ' . number_format($payable_total, 0)
                    . ($delivery_fee > 0 ? ' including UGX ' . number_format($delivery_fee, 0) . ' delivery' : '')
                    . '. We will confirm once payment is received.',
                'order',
                null,
                ['push', 'email', 'sms']
            );
        } catch (Throwable $e) {
            error_log("Bulk order $order_id created but notifications failed: " . $e->getMessage());
        }

        echo json_encode([
            'success' => true,
            'message' => 'Bulk order created successfully',
            'order' => $order,
            'delivery_fee' => $delivery_fee,
            'total_weight_kg' => $totalWeightKg,
            'grand_total' => $grand_total,
            'points_applied' => $redeemQuote['points_applied'],
            'loyalty_discount' => $loyalty_discount,
            'payable_total' => $payable_total
        ]);

    } elseif ($method === 'PUT') {
        // Update Bulk order status (same as before)
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
               FROM Bulk_orders so
               JOIN Bulk_listings sl ON sl.id = so.listing_id
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
        $payment = $dbh->prepare("SELECT payment_status, payment_method FROM Bulk_orders WHERE id = ?");
        $payment->execute([$order_id]);
        $paymentRow = $payment->fetch(PDO::FETCH_ASSOC) ?: [];
        $paymentStatus = (string)($paymentRow['payment_status'] ?? '');
        $paymentMethod = (string)($paymentRow['payment_method'] ?? '');
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

        // 'refunded' is only ever reached through admin/Bulk-orders.php's own
        // confirm_refund action — never declared directly by a plain status
        // write, from either the vendor or admin app. ('cancellation_requested'
        // needs no check here — it isn't in $valid_statuses above, so a plain
        // status write can never reach it either; it's only ever set by
        // requestBulkOrderCancellation() below.)
        if ($status === 'refunded') {
            http_response_code(403);
            echo json_encode([
                'success' => false,
                'error'   => 'Use the admin panel to confirm a refund.'
            ]);
            exit;
        }

        if ($status === 'cancelled') {
            require_once __DIR__ . '/../includes/Bulk_payment.php';
            $reason = trim((string)($input['reason'] ?? '')) ?: 'Order cancelled.';

            // A vendor cancelling their own order that actually collected
            // money electronically only REQUESTS it — nothing changes until
            // an admin approves, exactly like vendor_payout_requests. Cash
            // (never captured electronically) and an admin's own
            // cancellation both execute immediately: there is nothing to
            // protect in the first case, and the admin clicking this IS the
            // approval in the second.
            $needsApproval = !$isAdminSession
                && $paymentStatus === 'paid'
                && $paymentMethod !== 'cash';

            if ($needsApproval) {
                $result = requestBulkOrderCancellation($dbh, $order_id, $reason, $sessionUserId);
                echo json_encode($result['ok']
                    ? ['success' => true, 'status' => 'cancellation_requested',
                       'message' => 'Cancellation requested. An admin will review it shortly.']
                    : ['success' => false, 'error' => $result['error']]);
                exit;
            }

            $actorType = $isAdminSession ? 'admin' : 'vendor';
            $actorId   = $isAdminSession ? (int)($_SESSION['admin_id'] ?? 0) : $sessionUserId;
            $username  = $isAdminSession ? (string)($_SESSION['admin_name'] ?? 'afamfresh-admin') : 'afamfresh-vendor-app';
            $result = cancelBulkOrder($dbh, $order_id, $reason, $actorType, $actorId ?: null, $username);

            if (!$result['ok']) {
                echo json_encode(['success' => false, 'error' => $result['error']]);
                exit;
            }

            try {
                $who = $dbh->prepare(
                    "SELECT so.user_id, i.name AS product_name, v.business_name
                       FROM Bulk_orders so
                       JOIN Bulk_listings sl ON sl.id = so.listing_id
                       JOIN items i ON i.id = sl.product_id
                       JOIN vendors v ON v.id = sl.vendor_id
                      WHERE so.id = ?"
                );
                $who->execute([$order_id]);
                $row = $who->fetch(PDO::FETCH_ASSOC);
                if ($row) {
                    addNotification(
                        (int)$row['user_id'],
                        'Order cancelled',
                        $row['business_name'] . ' has cancelled your order'
                            . ' (order #' . $order_id . ', ' . $row['product_name'] . ').'
                            . ($result['refund_attempted'] ? ' Any payment made will be refunded.' : ''),
                        'order', null, ['push', 'email']
                    );
                }
            } catch (Throwable $e) {
                error_log("Bulk order $order_id cancelled but notification failed: " . $e->getMessage());
            }

            echo json_encode([
                'success' => true,
                'status'  => 'cancelled',
                'refund_requested' => $result['refund_requested'] ?? false,
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

        $sql = "UPDATE Bulk_orders SET " . implode(', ', $updateFields) . " WHERE id = ?";
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
                error_log("Bulk order $order_id delivered but vendor not credited: " . $credit['error']);
            }

            // Customer loyalty points — idempotent per (source, order_id),
            // see earnLoyaltyPoints()'s own doc, so this is a safe no-op if
            // api/rider.php already awarded them for the same order (a
            // Bulk order can reach 'delivered' via either path).
            require_once __DIR__ . '/../includes/loyalty.php';
            $customerIdStmt = $dbh->prepare("SELECT user_id FROM Bulk_orders WHERE id = ?");
            $customerIdStmt->execute([$order_id]);
            $customerId = (int)($customerIdStmt->fetchColumn() ?: 0);
            if ($customerId > 0) {
                $goodsValue = goodsValueForOrder($dbh, 'Bulk', $order_id);
                if ($goodsValue !== null) {
                    $earn = earnLoyaltyPoints($dbh, $customerId, 'Bulk', $order_id, $goodsValue);
                    if (!$earn['ok']) {
                        error_log("Bulk-orders.php: loyalty earn failed for order $order_id: " . ($earn['error'] ?? ''));
                    }
                }
            }
        }

        // Tell the customer. Without this the only party who learns an order
        // moved is the vendor who moved it, and the customer is left refreshing
        // a screen to find out whether 250,000 shillings of produce is coming.
        //
        // Only the states a customer can act on get a message. 'processing' is
        // deliberately silent: it means the vendor has started picking, which
        // changes nothing the customer can do and would just be noise.
        // 'cancelled' and 'refunded' never reach here — both return earlier
        // above, with their own notification handling.
        $notify = [
            'confirmed' => ['Order confirmed', 'has accepted your order and is preparing it.'],
            'ready'     => ['Order ready', 'has your order ready.'],
            'delivered' => ['Order delivered', 'has marked your order delivered.'],
        ];

        if (isset($notify[$status])) {
            try {
                $who = $dbh->prepare(
                    "SELECT so.user_id, so.pickup_code, i.name AS product_name, v.business_name
                       FROM Bulk_orders so
                       JOIN Bulk_listings sl ON sl.id = so.listing_id
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
                        // Email on 'ready', which decides whether someone needs
                        // to be somewhere; push alone for the rest. 'cancelled'
                        // used to be in this set, but it never reaches here now.
                        $status === 'ready'
                            ? ['push', 'email']
                            : ['push']
                    );
                }
            } catch (Throwable $e) {
                error_log("Bulk order $order_id moved to $status but customer not notified: " . $e->getMessage());
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
    error_log('Bulk-orders: ' . $e->getMessage());
    echo json_encode(['error' => 'Database error: ' . $e->getMessage()]);
}
?>