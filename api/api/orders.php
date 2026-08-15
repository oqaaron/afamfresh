<?php
session_start();
require_once '../admin/includes/config.php';
require_once __DIR__ . '/../includes/delivery-fee.php';
header('Content-Type: application/json');

error_reporting(E_ALL);
ini_set('display_errors', 0);
ini_set('log_errors', 1);

// ------------------------------------------------------------------
// 1. READ AND LOG INCOMING DATA
// ------------------------------------------------------------------
$rawInput = file_get_contents('php://input');
$jsonData = json_decode($rawInput, true);
error_log("=== ORDERS REQUEST ===");
error_log("METHOD: " . $_SERVER['REQUEST_METHOD']);
error_log("GET: " . print_r($_GET, true));
error_log("POST: " . print_r($_POST, true));
error_log("RAW: " . $rawInput);

// ------------------------------------------------------------------
// 2. DETECT ACTION (from GET, POST, or JSON)
// ------------------------------------------------------------------
$action = null;
if (isset($_GET['action'])) $action = $_GET['action'];
elseif (isset($_POST['action'])) $action = $_POST['action'];
elseif ($jsonData && isset($jsonData['action'])) $action = $jsonData['action'];

if (!$action) {
    echo json_encode(['success' => false, 'error' => 'Missing action parameter']);
    exit;
}

// ------------------------------------------------------------------
// 3. HELPER: Get value from JSON or POST/GET
// ------------------------------------------------------------------
function getParam($key, $default = null) {
    global $jsonData;
    if ($jsonData && isset($jsonData[$key])) return $jsonData[$key];
    if (isset($_POST[$key])) return $_POST[$key];
    if (isset($_GET[$key])) return $_GET[$key];
    return $default;
}

// ------------------------------------------------------------------
// 3b. HELPERS: shape a DB row into the JSON the app consumes
// ------------------------------------------------------------------
//
// The `orders` table uses legacy column names (orderid, total_amount,
// ordertime). Rather than make every client learn them, this normalises to
// id / total / created_at. Numeric columns come back from PDO as strings, so
// they are cast — otherwise the client sees "6770.51" where it expects a number.

function mapOrderRow(array $row) {
    return [
        'id'                       => (string)$row['orderid'],
        'status'                   => $row['status'],
        'current_status'           => $row['current_status'],
        'payment_status'           => $row['payment_status'],
        'total'                    => (float)$row['total_amount'],
        'created_at'               => $row['ordertime'],
        'fname'                    => $row['fname'],
        'lname'                    => $row['lname'],
        'mobile'                   => $row['mobile'],
        'area'                     => $row['area'],
        'address'                  => $row['address'],
        'delivery_address'         => $row['delivery_address'],
        'delivery_fee'             => (float)$row['delivery_fee'],
        'service_fee'              => (float)$row['service_fee'],
        'insurance_fee'            => (float)$row['insurance_fee'],
        'processing_fee'           => (float)$row['processing_fee'],
        'small_order_surcharge'    => (float)$row['small_order_surcharge'],
        'scheduled_delivery_date'  => $row['scheduled_delivery_date'],
        'scheduled_delivery_slot'  => $row['scheduled_delivery_slot'],
        'cancelled_at'             => $row['cancelled_at'],
        'delivered_at'             => $row['delivered_at'],
        // The rider's own attestation, not the customer's — see
        // includes/order_feedback.php. The app uses these two together to
        // decide whether to offer "Confirm & Rate": delivered by the rider,
        // not yet confirmed by the customer.
        'delivery_confirmed'       => (bool)($row['delivery_confirmed'] ?? false),
        'completed_at'             => $row['completed_at'] ?? null,
        'customer_rating'          => isset($row['customer_rating']) ? (int)$row['customer_rating'] : null,
    ];
}

function mapOrderItemRow(array $item) {
    return [
        'product_id'   => (int)$item['product_id'],
        'product_name' => $item['product_name'],
        'quantity'     => (int)$item['quantity'],
        'price'        => (float)$item['price'],
    ];
}

/**
 * Whether the customer may still edit or cancel an order.
 *
 * Allowed only before anything has been dispatched. `status` is free text in
 * this schema (varchar, not an enum) with values such as "Received",
 * "Awaiting Payment", "Preparing", "Out for Delivery", "On Way", "Delivered",
 * "Completed", "Cancelled" — so this matches against a known-good list rather
 * than trying to exclude the terminal ones.
 */
function isOrderEditable($status) {
    $editable = [
        'received',
        'pending',
        'awaiting payment',
        'awaiting confirmation',
        'preparing',
    ];
    return in_array(strtolower(trim((string)$status)), $editable, true);
}

// ------------------------------------------------------------------
// 4. CHECK AUTHENTICATION
// ------------------------------------------------------------------
$user_id = $_SESSION['user_id'] ?? null;
if (!$user_id) {
    echo json_encode(['success' => false, 'error' => 'User not authenticated']);
    exit;
}

// Shopping is for customer accounts only.
//
// Being signed in used to be enough, so a rider's session got a 200 from
// ?action=list and could browse or place orders. The three apps are a UX
// split, not a security boundary — anyone can point curl at this endpoint,
// so the check belongs here rather than in the client.
require_once __DIR__ . '/../includes/account_type.php';
requireAccountType($dbh, $user_id, 'customer');

// ------------------------------------------------------------------
// 5. ACTION HANDLERS
// ------------------------------------------------------------------
switch ($action) {

    // ----------------------------------------------------------------------
    // NOTE: 'list', 'detail', 'update' and 'cancel' were placeholder comments
    // followed by `break;`. Because the switch is followed by a final
    // json_encode(['error' => 'Invalid action']), every one of those four
    // actions replied "Invalid action" — order history, order detail, editing
    // and cancellation were all dead. Reimplemented below against the real
    // schema (orders.orderid / total_amount / ordertime).
    // ----------------------------------------------------------------------

    case 'list':
        try {
            $stmt = $dbh->prepare("
                SELECT orderid, status, current_status, payment_status,
                       total_amount, ordertime,
                       fname, lname, mobile, area, address, delivery_address,
                       delivery_fee, service_fee, insurance_fee,
                       processing_fee, small_order_surcharge,
                       scheduled_delivery_date, scheduled_delivery_slot,
                       cancelled_at, delivered_at,
                       delivery_confirmed, completed_at, customer_rating
                FROM orders
                WHERE user_id = ?
                ORDER BY ordertime DESC
                LIMIT 100
            ");
            $stmt->execute([$user_id]);
            $rows = $stmt->fetchAll(PDO::FETCH_ASSOC);

            // Build the list first, remembering which array slot holds which
            // order id, then attach items in ONE extra query rather than N+1.
            $orders = [];
            $slotForOrderId = [];
            foreach ($rows as $row) {
                $order = mapOrderRow($row);
                $order['items'] = [];
                $slotForOrderId[(string)$row['orderid']] = count($orders);
                $orders[] = $order;
            }

            if (!empty($slotForOrderId)) {
                $ids = array_keys($slotForOrderId);
                $placeholders = implode(',', array_fill(0, count($ids), '?'));
                $itemStmt = $dbh->prepare("
                    SELECT order_id, product_id, product_name, quantity, price
                    FROM order_items
                    WHERE order_id IN ($placeholders)
                ");
                $itemStmt->execute($ids);
                foreach ($itemStmt->fetchAll(PDO::FETCH_ASSOC) as $item) {
                    $key = (string)$item['order_id'];
                    if (isset($slotForOrderId[$key])) {
                        $orders[$slotForOrderId[$key]]['items'][] = mapOrderItemRow($item);
                    }
                }
            }

            echo json_encode(['success' => true, 'orders' => $orders]);
        } catch (Exception $e) {
            error_log("List orders error: " . $e->getMessage());
            echo json_encode(['success' => false, 'error' => 'Could not load orders']);
        }
        exit;

    case 'detail':
        $orderId = intval(getParam('id', getParam('order_id', 0)));
        if ($orderId === 0) {
            echo json_encode(['success' => false, 'error' => 'Order id is required']);
            exit;
        }
        try {
            // user_id in the WHERE clause, so one customer cannot read another's
            // order by guessing an id — the id space is only 900 wide.
            $stmt = $dbh->prepare("
                SELECT orderid, status, current_status, payment_status,
                       total_amount, ordertime,
                       fname, lname, mobile, area, address, delivery_address,
                       delivery_fee, service_fee, insurance_fee,
                       processing_fee, small_order_surcharge,
                       scheduled_delivery_date, scheduled_delivery_slot,
                       cancelled_at, delivered_at,
                       delivery_person, estimated_delivery,
                       dest_lat, dest_lng,
                       delivery_confirmed, completed_at, customer_rating
                FROM orders
                WHERE orderid = ? AND user_id = ?
            ");
            $stmt->execute([$orderId, $user_id]);
            $row = $stmt->fetch(PDO::FETCH_ASSOC);

            if (!$row) {
                echo json_encode(['success' => false, 'error' => 'Order not found']);
                exit;
            }

            $order = mapOrderRow($row);
            $order['delivery_person'] = $row['delivery_person'];
            $order['estimated_delivery'] = $row['estimated_delivery'];
            $order['dest_lat'] = $row['dest_lat'] === null ? null : (float)$row['dest_lat'];
            $order['dest_lng'] = $row['dest_lng'] === null ? null : (float)$row['dest_lng'];

            $itemStmt = $dbh->prepare("
                SELECT order_id, product_id, product_name, quantity, price
                FROM order_items WHERE order_id = ?
            ");
            $itemStmt->execute([$orderId]);
            $order['items'] = array_map('mapOrderItemRow', $itemStmt->fetchAll(PDO::FETCH_ASSOC));

            echo json_encode(['success' => true, 'order' => $order]);
        } catch (Exception $e) {
            error_log("Order detail error: " . $e->getMessage());
            echo json_encode(['success' => false, 'error' => 'Could not load order']);
        }
        exit;

    case 'create':
        // ------------------------------------------------------------------
        // CREATE ORDER – SUPPORTS JSON AND FORM DATA
        // ------------------------------------------------------------------
        //
        // total, delivery_cost, distance_km, and each item's price/
        // product_name are deliberately NOT read from the request. They used
        // to be — the client sent the final total and per-line prices, and
        // this endpoint stored them verbatim. A raw POST with a fabricated
        // total and item prices bought real products at any price the
        // request claimed, and api/payment.php later charged exactly that
        // number: it reads total_amount from the orders row rather than the
        // request, which is correct, but that row had already been poisoned
        // here at creation. Only product_id/quantity are trusted from the
        // client now; price, delivery fee, and the final total are all
        // computed from the database below.
        $fname = trim(getParam('fname', ''));
        $lname = trim(getParam('lname', ''));
        $mobile = trim(getParam('mobile', ''));
        $area = trim(getParam('area', ''));
        $address = trim(getParam('address', ''));
        $itemsJson = getParam('items', '[]');
        $paymentMethod = getParam('payment_method', 'mobile_money');
        $email = trim(getParam('email', ''));
        $pickupAddress = trim(getParam('pickup_address', ''));
        $dropoffAddress = trim(getParam('dropoff_address', ''));
        $pickupLat = floatval(getParam('pickup_lat', 0));
        $pickupLng = floatval(getParam('pickup_lng', 0));
        $dropoffLat = floatval(getParam('dropoff_lat', 0));
        $dropoffLng = floatval(getParam('dropoff_lng', 0));
        $pointsToRedeem = intval(getParam('points_redeem', 0));

        // Log extracted values
        error_log("Extracted: fname=$fname, lname=$lname, mobile=$mobile, area=$area, address=$address");

        // Validate
        if (empty($fname) || empty($lname) || empty($mobile) || empty($area) || empty($address)) {
            echo json_encode([
                'success' => false,
                'error' => 'Missing required fields',
                'debug' => [
                    'fname' => $fname,
                    'lname' => $lname,
                    'mobile' => $mobile,
                    'area' => $area,
                    'address' => $address
                ]
            ]);
            exit;
        }

        // Reject a destination that cannot be in Uganda.
        //
        // Order 500384 was stored with dest_lat/dest_lng 51.70892, -88.90354 —
        // Northwestern Ontario, Canada — because the app's map, left
        // unauthenticated by a blank Maps API key, renders at world scale and
        // a tap projects anywhere on earth. The client blocks this now too,
        // but the client is not a trust boundary: this endpoint accepts any
        // POST, so the check has to exist here as well.
        //
        // Absent coordinates and (0,0) mean "no map point supplied" — 85 of
        // the existing orders look like that, having been placed without the
        // map — so only coordinates actually provided are range-checked.
        $hasDropoffPoint = ($dropoffLat != 0.0 || $dropoffLng != 0.0);
        if ($hasDropoffPoint &&
            ($dropoffLat < -1.5 || $dropoffLat > 4.3 || $dropoffLng < 29.5 || $dropoffLng > 35.1)) {
            error_log("Rejected out-of-country dropoff: lat=$dropoffLat lng=$dropoffLng address=$address");
            echo json_encode([
                'success' => false,
                'error' => 'That delivery location is outside our service area.'
            ]);
            exit;
        }

        $items = json_decode($itemsJson, true);
        if (empty($items) || !is_array($items)) {
            echo json_encode(['success' => false, 'error' => 'Invalid items data']);
            exit;
        }

        try {
            $dbh->beginTransaction();

            // ------------------------------------------------------------
            // Price every line from the database, not the request. Only
            // product_id/quantity are trusted from $items now.
            // ------------------------------------------------------------
            $productIds = [];
            $quantityByProduct = [];
            foreach ($items as $item) {
                $productId = (int)($item['product_id'] ?? $item['id'] ?? 0);
                $quantity = isset($item['quantity']) ? (int)$item['quantity'] : 1;
                if ($productId <= 0 || $quantity < 1) {
                    $dbh->rollBack();
                    echo json_encode(['success' => false, 'error' => 'Invalid item in cart']);
                    exit;
                }
                $productIds[] = $productId;
                // Same product id appearing twice in the cart sums correctly.
                $quantityByProduct[$productId] = ($quantityByProduct[$productId] ?? 0) + $quantity;
            }
            $productIds = array_values(array_unique($productIds));

            $placeholders = implode(',', array_fill(0, count($productIds), '?'));
            $itemLookup = $dbh->prepare(
                "SELECT id, name, price, discount, stock_qty, status, vendor_id
                 FROM items WHERE id IN ($placeholders)"
            );
            $itemLookup->execute($productIds);
            $rowsById = [];
            foreach ($itemLookup->fetchAll(PDO::FETCH_ASSOC) as $row) {
                $rowsById[(int)$row['id']] = $row;
            }

            $subtotal = 0.0;
            $orderLines = []; // [product_id, name, unit_price, quantity]
            foreach ($productIds as $productId) {
                $row = $rowsById[$productId] ?? null;
                // Same rule products.php's own list/detail actions enforce —
                // a product that isn't in the public catalogue (vendor-owned,
                // or awaiting/rejected approval) can't be bought here either.
                if ($row === null || $row['vendor_id'] !== null || $row['status'] !== 'approved') {
                    $dbh->rollBack();
                    echo json_encode(['success' => false, 'error' => 'One of these products is no longer available.']);
                    exit;
                }
                if ($row['stock_qty'] !== null && (int)$row['stock_qty'] === 0) {
                    $dbh->rollBack();
                    echo json_encode(['success' => false, 'error' => $row['name'] . ' is out of stock.']);
                    exit;
                }

                $quantity = $quantityByProduct[$productId];
                // Mirrors Product.kt's effectivePrice exactly, so what is
                // charged matches what the listing displayed.
                $discount = (float)($row['discount'] ?? 0.0);
                $unitPrice = (float)$row['price'] * (1 - $discount / 100);

                $subtotal += $unitPrice * $quantity;
                $orderLines[] = [
                    'product_id' => $productId,
                    'name' => $row['name'],
                    'price' => $unitPrice,
                    'quantity' => $quantity,
                ];
            }

            // ------------------------------------------------------------
            // Delivery fee, computed server-side from the real subtotal —
            // the exact function api/calculate-delivery-fee.php already uses
            // for the checkout screen's live preview, so the final charge
            // agrees with what the customer was shown.
            // ------------------------------------------------------------
            $feeResult = calculateDeliveryFeeFromAddress(
                $address, $area, $subtotal,
                $hasDropoffPoint ? $dropoffLat : null,
                $hasDropoffPoint ? $dropoffLng : null
            );
            if (!$feeResult['success']) {
                $dbh->rollBack();
                echo json_encode(['success' => false, 'error' => $feeResult['error']]);
                exit;
            }
            $deliveryCost = (float)$feeResult['fee'];
            $mileageFee = (float)$feeResult['breakdown']['distance_fee'];
            $serviceFee = (float)$feeResult['breakdown']['service_fee'];
            $insuranceFee = (float)$feeResult['breakdown']['insurance_fee'];
            $processingFee = (float)$feeResult['breakdown']['processing_fee'];
            // Already included in $deliveryCost (calculateDeliveryFee() folds
            // it into total_fee) — stored separately too so admin/order-detail.php
            // can show it as its own line instead of it being invisible inside
            // the combined delivery fee. Never touches rider pay: riders are
            // credited from orders.mileage_fee alone (creditRiderEarnings() /
            // mileageFeeFor()), which this is not part of.
            $smallOrderSurcharge = (float)$feeResult['breakdown']['profit_margin'];

            // Loyalty redemption, if requested. $subtotal is already
            // goods-only, so it's exactly the value points are quoted
            // against — no delivery-fee subtraction needed, unlike when this
            // came from a client-combined total. Only the DISCOUNT is
            // applied now; the point DEBIT itself waits for payment to
            // actually confirm (see settleLoyaltyRedemption(), called from
            // api/payment.php's verify action and the Pesapal IPN/callback
            // handlers) so an abandoned or failed order never costs the
            // customer points they never actually spent.
            require_once __DIR__ . '/../includes/loyalty.php';
            $redeemQuote = quoteLoyaltyRedemption($dbh, (int)$user_id, $pointsToRedeem, $subtotal);
            $total = $subtotal + $deliveryCost - $redeemQuote['discount'];

            // Generate unique order ID
            do {
                $orderId = 500000 + rand(100, 999);
                $check = $dbh->prepare("SELECT orderid FROM orders WHERE orderid = ?");
                $check->execute([$orderId]);
            } while ($check->fetch());

            // Insert order – adjust columns as needed
            $sql = "INSERT INTO orders (
                orderid, user_id, fname, lname, mobile, area, address,
                ordertime, total_amount, payment_status, status,
                delivery_lat, delivery_lng, delivery_address,
                dest_lat, dest_lng,
                delivery_fee, mileage_fee, service_fee, insurance_fee, processing_fee, small_order_surcharge,
                points_redeemed
            ) VALUES (
                ?, ?, ?, ?, ?, ?, ?,
                NOW(), ?, ?, ?,
                ?, ?, ?,
                ?, ?,
                ?, ?, ?, ?, ?, ?,
                ?
            )";

            $stmt = $dbh->prepare($sql);
            $result = $stmt->execute([
                $orderId,
                $user_id,
                $fname,
                $lname,
                $mobile,
                $area,
                $address,
                $total,
                'pending',
                'Received',
                $pickupLat,
                $pickupLng,
                $dropoffAddress,
                $dropoffLat,
                $dropoffLng,
                $deliveryCost,
                $mileageFee,
                $serviceFee, $insuranceFee, $processingFee, $smallOrderSurcharge,
                $redeemQuote['points_applied']
            ]);

            if (!$result) {
                $errorInfo = $stmt->errorInfo();
                throw new Exception("Insert failed: " . $errorInfo[2]);
            }

            // Insert order items — from $orderLines (the DB-validated
            // name/price computed above), not the client's $items.
            $itemStmt = $dbh->prepare("INSERT INTO order_items (order_id, product_id, product_name, quantity, price) VALUES (?, ?, ?, ?, ?)");
            foreach ($orderLines as $line) {
                $itemStmt->execute([
                    $orderId, $line['product_id'], $line['name'], $line['quantity'], $line['price']
                ]);
            }

            $dbh->commit();

            // After the commit, never inside the transaction: an SMS cannot be
            // rolled back, and a provider timeout would hold rows locked on the
            // way to failing anyway.
            //
            // Failure is logged, not surfaced. The order exists and is paid
            // for; telling the customer it failed because a text message did
            // would be a lie with consequences.
            try {
                require_once __DIR__ . '/../includes/brevo-sms.php';
                sendSmsWithBrevo(
                    $mobile,
                    "AfamFresh: order #{$orderId} received. We'll text you again when it's out for delivery."
                );
            } catch (Throwable $e) {
                error_log("Order $orderId placed but the order-placed SMS failed: " . $e->getMessage());
            }

            // Push alongside the SMS. Bulk orders have had this since they
            // were built; shop orders only ever got the text.
            try {
                require_once __DIR__ . '/../includes/notifications.php';
                addNotification(
                    (int)$user_id,
                    'Order placed',
                    "Your order #{$orderId} was received. We'll text you when it's out for delivery.",
                    'order', null, ['push'],
                    ['order_id' => (string)$orderId, 'source' => 'order']
                );
            } catch (Throwable $e) {
                error_log("Order $orderId placed but the push notification failed: " . $e->getMessage());
            }

            echo json_encode([
                'success' => true,
                'order_id' => $orderId,
                'message' => 'Order placed successfully',
                'points_applied' => $redeemQuote['points_applied'],
                'loyalty_discount' => $redeemQuote['discount']
            ]);

        } catch (Exception $e) {
            $dbh->rollBack();
            error_log("Create order error: " . $e->getMessage());
            echo json_encode([
                'success' => false,
                'error' => 'Order creation failed: ' . $e->getMessage()
            ]);
        }
        exit;

    case 'update':
        $orderId = intval(getParam('order_id', 0));
        if ($orderId === 0) {
            echo json_encode(['success' => false, 'error' => 'order_id is required']);
            exit;
        }
        try {
            $own = $dbh->prepare("SELECT status FROM orders WHERE orderid = ? AND user_id = ?");
            $own->execute([$orderId, $user_id]);
            $existing = $own->fetch(PDO::FETCH_ASSOC);
            if (!$existing) {
                echo json_encode(['success' => false, 'error' => 'Order not found']);
                exit;
            }
            if (!isOrderEditable($existing['status'])) {
                echo json_encode([
                    'success' => false,
                    'error' => 'This order can no longer be changed because it is already ' .
                               strtolower($existing['status']) . '.'
                ]);
                exit;
            }

            // Only these five are editable by the customer. Notably NOT the
            // items or the total — letting the client rewrite those would let it
            // set its own price.
            $editable = [
                'address' => 'address',
                'area' => 'area',
                'mobile' => 'mobile',
                'scheduled_delivery_date' => 'scheduled_delivery_date',
                'scheduled_delivery_slot' => 'scheduled_delivery_slot',
            ];

            $set = [];
            $params = [];
            foreach ($editable as $field => $column) {
                $value = getParam($field, null);
                if ($value === null) continue;
                $value = trim($value);
                // An empty date/slot means "clear it"; empty address/mobile is
                // rejected because those columns are NOT NULL and required.
                if ($value === '' && in_array($column, ['address', 'area', 'mobile'], true)) {
                    continue;
                }
                $set[] = "$column = ?";
                $params[] = ($value === '') ? null : $value;
            }

            if (empty($set)) {
                echo json_encode(['success' => false, 'error' => 'Nothing to update']);
                exit;
            }

            $params[] = $orderId;
            $params[] = $user_id;
            $stmt = $dbh->prepare(
                "UPDATE orders SET " . implode(', ', $set) . " WHERE orderid = ? AND user_id = ?"
            );
            $stmt->execute($params);

            echo json_encode(['success' => true, 'message' => 'Order updated']);
        } catch (Exception $e) {
            error_log("Update order error: " . $e->getMessage());
            echo json_encode(['success' => false, 'error' => 'Could not update the order']);
        }
        exit;

    case 'cancel':
        $orderId = intval(getParam('order_id', 0));
        if ($orderId === 0) {
            echo json_encode(['success' => false, 'error' => 'order_id is required']);
            exit;
        }
        try {
            $own = $dbh->prepare("SELECT status FROM orders WHERE orderid = ? AND user_id = ?");
            $own->execute([$orderId, $user_id]);
            $existing = $own->fetch(PDO::FETCH_ASSOC);
            if (!$existing) {
                echo json_encode(['success' => false, 'error' => 'Order not found']);
                exit;
            }
            if (strcasecmp($existing['status'], 'Cancelled') === 0) {
                echo json_encode(['success' => true, 'message' => 'Order was already cancelled']);
                exit;
            }
            if (!isOrderEditable($existing['status'])) {
                echo json_encode([
                    'success' => false,
                    'error' => 'This order is already ' . strtolower($existing['status']) .
                               ' and can no longer be cancelled.'
                ]);
                exit;
            }

            $stmt = $dbh->prepare("
                UPDATE orders
                SET status = 'Cancelled', current_status = 'cancelled', cancelled_at = NOW()
                WHERE orderid = ? AND user_id = ?
            ");
            $stmt->execute([$orderId, $user_id]);

            echo json_encode(['success' => true, 'message' => 'Order cancelled']);
        } catch (Exception $e) {
            error_log("Cancel order error: " . $e->getMessage());
            echo json_encode(['success' => false, 'error' => 'Could not cancel the order']);
        }
        exit;

    case 'confirm_receipt':
        // The customer's half of delivery confirmation — see
        // includes/order_feedback.php for why this is separate from the
        // rider's delivery_confirmed flag.
        $orderId = intval(getParam('order_id', 0));
        if ($orderId === 0) {
            echo json_encode(['success' => false, 'error' => 'order_id is required']);
            exit;
        }

        $ratingRaw = getParam('rating', null);
        if ($ratingRaw !== null && ($ratingRaw < 1 || $ratingRaw > 5)) {
            echo json_encode(['success' => false, 'error' => 'Rating must be between 1 and 5']);
            exit;
        }

        try {
            require_once __DIR__ . '/../includes/order_feedback.php';

            $target = loadFeedbackTarget($dbh, 'order', $orderId, $user_id);
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

            // Optional — most confirmations will just be a rating, not a
            // photo. $_FILES populates fine here because this action is
            // reached over a plain POST, unlike a PUT/PATCH multipart body,
            // which PHP does not parse automatically.
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

            $emoji = trim((string)getParam('emoji_reaction', ''));
            if ($emoji !== '' && !in_array($emoji, validEmojiReactions(), true)) {
                $emoji = '';
            }

            $intOrNull = function ($v) {
                return $v === null || $v === '' ? null : (int)$v;
            };

            saveCustomerReceiptConfirmation($dbh, 'order', $orderId, [
                'rating'                 => $intOrNull($ratingRaw),
                'rating_speed'           => $intOrNull(getParam('rating_speed')),
                'rating_professionalism' => $intOrNull(getParam('rating_professionalism')),
                'rating_packaging'       => $intOrNull(getParam('rating_packaging')),
                'feedback'               => trim((string)getParam('feedback', '')) ?: null,
                'emoji'                  => $emoji ?: null,
                'photo_filename'         => $photoFilename,
            ]);

            echo json_encode(['success' => true, 'message' => 'Thanks for confirming!']);
        } catch (Exception $e) {
            error_log("confirm_receipt error: " . $e->getMessage());
            echo json_encode(['success' => false, 'error' => 'Could not confirm receipt']);
        }
        exit;

    default:
        echo json_encode(['success' => false, 'error' => 'Invalid action']);
        exit;
}

// If no action matched (should not reach here)
echo json_encode(['success' => false, 'error' => 'Invalid action']);
exit;
?>