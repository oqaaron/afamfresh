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
                       cancelled_at, delivered_at
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
                       dest_lat, dest_lng
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
        $fname = trim(getParam('fname', ''));
        $lname = trim(getParam('lname', ''));
        $mobile = trim(getParam('mobile', ''));
        $area = trim(getParam('area', ''));
        $address = trim(getParam('address', ''));
        $itemsJson = getParam('items', '[]');
        $total = floatval(getParam('total', 0));
        $paymentMethod = getParam('payment_method', 'mobile_money');
        $email = trim(getParam('email', ''));
        $pickupAddress = trim(getParam('pickup_address', ''));
        $dropoffAddress = trim(getParam('dropoff_address', ''));
        $pickupLat = floatval(getParam('pickup_lat', 0));
        $pickupLng = floatval(getParam('pickup_lng', 0));
        $dropoffLat = floatval(getParam('dropoff_lat', 0));
        $dropoffLng = floatval(getParam('dropoff_lng', 0));
        $distanceKm = floatval(getParam('distance_km', 0));
        $deliveryCost = floatval(getParam('delivery_cost', 0));

        // Log extracted values
        error_log("Extracted: fname=$fname, lname=$lname, mobile=$mobile, area=$area, address=$address, total=$total");

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

        // The distance component of $deliveryCost, recomputed server-side
        // from $distanceKm rather than trusted from the client. This is what
        // lets rider earnings be computed later without re-deriving distance
        // from coordinates: the app already sends distance_km at checkout,
        // it was just never kept — only the combined total reached this
        // endpoint before today.
        $mileageFee = null;
        if ($distanceKm > 0) {
            $breakdown = calculateDeliveryFee($total, $distanceKm);
            $mileageFee = $breakdown['distance_fee'];
        }

        try {
            $dbh->beginTransaction();

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
                delivery_fee, mileage_fee, service_fee, insurance_fee, processing_fee, small_order_surcharge
            ) VALUES (
                ?, ?, ?, ?, ?, ?, ?,
                NOW(), ?, ?, ?,
                ?, ?, ?,
                ?, ?,
                ?, ?, ?, ?, ?, ?
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
                0.0, 0.0, 0.0, 0.0
            ]);

            if (!$result) {
                $errorInfo = $stmt->errorInfo();
                throw new Exception("Insert failed: " . $errorInfo[2]);
            }

            // Insert order items
            $itemStmt = $dbh->prepare("INSERT INTO order_items (order_id, product_id, product_name, quantity, price) VALUES (?, ?, ?, ?, ?)");
            foreach ($items as $item) {
                // The Android app serialises cart lines as `product_id`; the web
                // checkout sends `id`. Reading only `id` silently wrote 0 for
                // every app order (see order_items rows 471-476), which severs
                // the row from the items table. Accept either spelling.
                $productId = (int)($item['product_id'] ?? $item['id'] ?? 0);
                $productName = $item['product_name'] ?? $item['name'] ?? 'Unknown';
                $quantity = isset($item['quantity']) ? (int)$item['quantity'] : 1;
                $price = isset($item['price']) ? (float)$item['price'] : 0;
                $itemStmt->execute([$orderId, $productId, $productName, $quantity, $price]);
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

            echo json_encode([
                'success' => true,
                'order_id' => $orderId,
                'message' => 'Order placed successfully'
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

    default:
        echo json_encode(['success' => false, 'error' => 'Invalid action']);
        exit;
}

// If no action matched (should not reach here)
echo json_encode(['success' => false, 'error' => 'Invalid action']);
exit;
?>