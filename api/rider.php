<?php
// =============================================================
// api/rider.php — the rider section's backend
//
// Actions: me | deliveries | delivery_detail | update_status
//        | duty_status | location | upload_proof
//
// IDENTITY. A rider is a normal app account with the Rider role,
// linked to a `riders` row by riders.user_id (UNIQUE). Every action
// resolves $_SESSION['user_id'] -> rider_id through currentRider().
// The alternative that half-exists in the codebase — a separate
// rider login setting $_SESSION['rider_id'] — is dead: nothing has
// ever set that key. api/location.php's rider branch was written
// against it and never ran.
//
// ASSIGNMENT. rider_assignments is the source of truth for who is
// delivering what. It had correct foreign keys and 33 rows but was
// referenced by zero lines of PHP; orders.delivery_person (a NAME
// string) was doing the job instead, which cannot distinguish two
// riders with the same name.
//
// NOTE: deliberately no ini_set('display_errors', 1) here, matching
// api/profile.php. A PHP notice printed before the JSON prepends
// HTML to the body and Gson then fails to parse the ENTIRE
// response — the symptom is a working endpoint the app calls broken.
// =============================================================

session_start();
require_once '../admin/includes/config.php';
require_once __DIR__ . '/../includes/user_payload.php';
require_once __DIR__ . '/../includes/rider_earnings.php';
// Surplus orders are dispatched to riders too. They live in a different table
// with different column names, normalised to one shape here.
require_once __DIR__ . '/../includes/rider_dispatch.php';
require_once __DIR__ . '/../includes/payment_ledger.php';
// Proof photos are read on the detail action, not just written on upload,
// so this is needed for the whole file rather than inside upload_proof.
require_once __DIR__ . '/../includes/storage.php';
header('Content-Type: application/json');

if (!isset($_SESSION['user_id'])) {
    echo json_encode(['success' => false, 'error' => 'Not logged in']);
    exit;
}

$user_id = $_SESSION['user_id'];
$action  = $_GET['action'] ?? '';

$jsonBody = json_decode(file_get_contents('php://input'), true);
if (!is_array($jsonBody)) { $jsonBody = []; }

function param($key, $default = null) {
    global $jsonBody;
    if (array_key_exists($key, $jsonBody)) return $jsonBody[$key];
    if (array_key_exists($key, $_POST))    return $_POST[$key];
    if (array_key_exists($key, $_GET))     return $_GET[$key];
    return $default;
}

function fail($message, $httpCode = 200, $code = null) {
    // Key is 'error', matching auth.php's login/register/me and profile.php.
    if ($httpCode !== 200) http_response_code($httpCode);
    $body = ['success' => false, 'error' => $message];
    if ($code !== null) $body['code'] = $code;
    echo json_encode($body);
    exit;
}

/**
 * The riders row for the signed-in account, or null when unlinked.
 *
 * An account with the Rider role but no riders row is a real state —
 * the admin sets the role on the Users page and the link on the rider
 * page, and can do either first — so this is reported as a clear
 * message rather than treated as an error.
 */
function currentRider($dbh, $user_id) {
    $stmt = $dbh->prepare("SELECT * FROM riders WHERE user_id = ?");
    $stmt->execute([$user_id]);
    $rider = $stmt->fetch(PDO::FETCH_ASSOC);
    return $rider ?: null;
}

/**
 * Refuses anything that is not a rider account, before the riders lookup.
 *
 * The riders.user_id link alone is not the whole rule: an account must also
 * BE a rider account. Both are checked so a customer account can never reach
 * these endpoints even if a stray riders row were ever pointed at it.
 */
function requireRiderAccount($dbh, $user_id) {
    require_once __DIR__ . '/../includes/account_type.php';
    requireAccountType($dbh, $user_id, 'rider');
}

function requireRider($dbh, $user_id) {
    // Account type first: a customer must be refused as a customer, not told
    // their rider profile is missing.
    requireRiderAccount($dbh, $user_id);
    $rider = currentRider($dbh, $user_id);
    if (!$rider) {
        fail('This account is not set up as a rider yet. Ask an administrator to link it to a rider profile.');
    }
    return $rider;
}

// =============================================================
// STATUS MODEL
//
// One table drives every status write, because the previous free-for-all
// left orders.status and orders.current_status disagreeing on live rows
// (e.g. status='Delivered' with current_status='pending'). Each step
// writes the assignment status AND both order columns together, in one
// transaction, so they cannot drift again.
// =============================================================
$FLOW = ['assigned', 'picked_up', 'on_way', 'delivered'];

$STATUS_MAP = [
    'assigned'  => ['current' => 'ready',     'label' => 'Preparing'],
    'picked_up' => ['current' => 'on_way',    'label' => 'Out for Delivery'],
    'on_way'    => ['current' => 'on_way',    'label' => 'On Way'],
    'delivered' => ['current' => 'delivered', 'label' => 'Delivered'],
];

/**
 * The assignment row for one order, scoped to this rider.
 *
 * `source` is part of the key, not an extra filter: shop order 41 and surplus
 * order 41 both exist, so an id alone can match the wrong job and hand a rider
 * a different customer's address.
 */
function assignmentFor($dbh, $riderId, $orderId, $source = 'order') {
    $stmt = $dbh->prepare(
        "SELECT * FROM rider_assignments
          WHERE rider_id = ? AND order_id = ? AND source = ? LIMIT 1"
    );
    $stmt->execute([$riderId, $orderId, $source]);
    return $stmt->fetch(PDO::FETCH_ASSOC) ?: null;
}

/**
 * Appends one entry to orders.status_history, which holds a JSON array.
 *
 * Shop orders only — surplus_orders has no status_history column. The surplus
 * side keeps its audit trail through the customer notifications that
 * api/surplus-orders.php sends on each transition.
 */
function appendStatusHistory($dbh, $orderId, $status, $note) {
    $stmt = $dbh->prepare("SELECT status_history FROM orders WHERE orderid = ?");
    $stmt->execute([$orderId]);
    $history = json_decode((string)$stmt->fetchColumn(), true);
    if (!is_array($history)) { $history = []; }

    $history[] = [
        'status' => $status,
        'note'   => $note,
        'at'     => date('Y-m-d H:i:s'),
    ];

    $upd = $dbh->prepare("UPDATE orders SET status_history = ? WHERE orderid = ?");
    $upd->execute([json_encode($history), $orderId]);
}

// =============================================================
// ACTION: ME
// =============================================================
if ($action === 'me') {
    $rider = requireRider($dbh, $user_id);

    $counts = $dbh->prepare(
        "SELECT
            SUM(status = 'delivered' AND DATE(delivered_at) = CURDATE()) AS delivered_today,
            SUM(status <> 'delivered') AS active_count
         FROM rider_assignments WHERE rider_id = ?"
    );
    $counts->execute([$rider['id']]);
    $c = $counts->fetch(PDO::FETCH_ASSOC);

    echo json_encode([
        'success' => true,
        'rider' => [
            'id'              => (int)$rider['id'],
            'name'            => $rider['name'],
            'phone'           => $rider['phone'],
            'email'           => $rider['email'],
            'vehicle_type'    => $rider['vehicle_type'],
            'status'          => $rider['status'],
            'is_online'       => $rider['status'] === 'online',
            'avg_rating'      => (float)$rider['avg_rating'],
            'total_ratings'   => (int)$rider['total_ratings'],
            'delivered_today' => (int)($c['delivered_today'] ?? 0),
            'active_count'    => (int)($c['active_count'] ?? 0),
        ],
    ]);
    exit;
}

// =============================================================
// ACTION: DELIVERIES
// =============================================================
if ($action === 'deliveries') {
    $rider = requireRider($dbh, $user_id);

    // The assignments are read first and each order resolved afterwards,
    // rather than joined.
    //
    // A join cannot work here any more: `orders` and `surplus_orders` share no
    // column names, and a UNION would have to invent NULL columns for whichever
    // table lacks each one — at which point the query is doing the normalising
    // that loadDeliverable() does in one place, and doing it invisibly.
    //
    // The cost is one query per assignment, bounded at 100. Worth it for a
    // rider's own list; it would not be for an admin-wide view.
    $stmt = $dbh->prepare(
        "SELECT id AS assignment_id, order_id, source, status AS assignment_status,
                assigned_at, delivered_at AS assignment_delivered_at
           FROM rider_assignments
          WHERE rider_id = ?
          ORDER BY assigned_at DESC
          LIMIT 100"
    );
    $stmt->execute([$rider['id']]);

    $active = [];
    $history = [];
    foreach ($stmt->fetchAll(PDO::FETCH_ASSOC) as $a) {
        $row = loadDeliverable($dbh, $a['source'], (int)$a['order_id']);
        if (!$row) {
            // The order was deleted out from under the assignment. Skipping it
            // keeps the rider's list usable instead of failing the whole call.
            error_log("rider deliveries: assignment {$a['assignment_id']} points at a missing {$a['source']} order {$a['order_id']}");
            continue;
        }

        $d = [
            'assignment_id'   => (int)$a['assignment_id'],
            'order_id'        => (int)$row['order_id'],
            'source'          => $row['source'],
            'status'          => $a['assignment_status'],
            'order_status'    => $row['status'],
            'payment_status'  => $row['payment_status'],
            'customer_name'   => trim($row['fname'] . ' ' . $row['lname']),
            'customer_phone'  => $row['mobile'],
            'area'            => $row['area'],
            'address'         => $row['delivery_address'] ?: $row['address'],
            // Where the rider COLLECTS. Null for shop orders, which all leave
            // from the warehouse; a surplus job starts at the vendor instead,
            // and a rider sent to the warehouse for one would find nothing.
            'pickup_address'  => $row['pickup_address'],
            'dest_lat'        => $row['dest_lat'] !== null ? (float)$row['dest_lat'] : null,
            'dest_lng'        => $row['dest_lng'] !== null ? (float)$row['dest_lng'] : null,
            'total_amount'    => (float)$row['total_amount'],
            'delivery_fee'    => (float)$row['delivery_fee'],
            'assigned_at'     => $a['assigned_at'],
            'delivered_at'    => $a['assignment_delivered_at'],
            'ordertime'       => $row['ordertime'],
            'scheduled_date'  => $row['scheduled_delivery_date'],
            'scheduled_slot'  => $row['scheduled_delivery_slot'],
            'has_proof_photo' => !empty($row['delivery_photo']),
        ];
        if ($a['assignment_status'] === 'delivered') {
            $history[] = $d;
        } else {
            $active[] = $d;
        }
    }

    echo json_encode(['success' => true, 'active' => $active, 'history' => $history]);
    exit;
}

// =============================================================
// ACTION: DELIVERY DETAIL
// =============================================================
if ($action === 'delivery_detail') {
    $rider = requireRider($dbh, $user_id);
    $orderId = (int)param('order_id', 0);
    // Older rider builds send no source. Defaulting to 'order' keeps them
    // working against shop deliveries, which is all they can be assigned in a
    // version that does not know surplus exists.
    $source  = (string)param('source', 'order');
    if (!$orderId) fail('No order was specified.');
    if (!isDispatchSource($source)) fail('That is not a kind of delivery.');

    // Scoped by rider_id: a rider cannot read an order they are not on.
    $assignment = assignmentFor($dbh, $rider['id'], $orderId, $source);
    if (!$assignment) fail('That delivery is not assigned to you.');

    $o = loadDeliverable($dbh, $source, $orderId);
    if (!$o) fail('Order not found.');

    $items = deliverableItems($dbh, $source, $orderId);

    // storageExists, not is_file: on Cloud Run the photo lives in a bucket
    // and is_file() would answer false for every one of them, silently
    // blanking proof photos that are in fact there.
    $proofUrl = null;
    if (!empty($o['delivery_photo']) && storageExists('proof/' . $o['delivery_photo'])) {
        // Absolute, like avatars. A bare filename is unusable to Coil — the
        // mistake that left product images blank for so long.
        $proofUrl = storageUrl('proof/' . $o['delivery_photo']);
    }

    echo json_encode([
        'success' => true,
        'delivery' => [
            'assignment_id'  => (int)$assignment['id'],
            'order_id'       => (int)$o['order_id'],
            'source'         => $o['source'],
            'status'         => $assignment['status'],
            'order_status'   => $o['status'],
            'payment_status' => $o['payment_status'],
            'customer_name'  => trim($o['fname'] . ' ' . $o['lname']),
            'customer_phone' => $o['mobile'],
            'area'           => $o['area'],
            'address'        => $o['delivery_address'] ?: $o['address'],
            'pickup_address' => $o['pickup_address'],
            'pickup_code'    => $o['pickup_code'],
            'dest_lat'       => $o['dest_lat'] !== null ? (float)$o['dest_lat'] : null,
            'dest_lng'       => $o['dest_lng'] !== null ? (float)$o['dest_lng'] : null,
            'total_amount'   => (float)$o['total_amount'],
            'delivery_fee'   => (float)$o['delivery_fee'],
            'ordertime'      => $o['ordertime'],
            'scheduled_date' => $o['scheduled_delivery_date'],
            'scheduled_slot' => $o['scheduled_delivery_slot'],
            'proof_photo_url' => $proofUrl,
            'items'          => $items,
        ],
    ]);
    exit;
}

// =============================================================
// ACTION: UPDATE STATUS
// =============================================================
if ($action === 'update_status') {
    $rider   = requireRider($dbh, $user_id);
    $orderId = (int)param('order_id', 0);
    $next    = trim((string)param('status', ''));
    $source  = (string)param('source', 'order');

    if (!$orderId) fail('No order was specified.');
    if (!isset($STATUS_MAP[$next])) fail('That is not a valid delivery status.');
    if (!isDispatchSource($source)) fail('That is not a kind of delivery.');

    $assignment = assignmentFor($dbh, $rider['id'], $orderId, $source);
    if (!$assignment) fail('That delivery is not assigned to you.');

    $current = $assignment['status'];
    $fromIdx = array_search($current, $FLOW, true);
    $toIdx   = array_search($next, $FLOW, true);

    if ($fromIdx === false) {
        // Legacy row with a status outside the flow; let the rider recover
        // rather than being stuck on an unrecognised value.
        $fromIdx = 0;
    }
    if ($toIdx <= $fromIdx) {
        // Forward-only. Without this a delivered order could be walked back to
        // picked_up, which would clear delivered_at and confuse the customer's
        // order screen.
        fail($toIdx === $fromIdx
            ? 'This delivery is already at that stage.'
            : 'A delivery cannot go back to an earlier stage.');
    }

    $map = $STATUS_MAP[$next];

    // Cash on delivery: the rider has to say they took the money.
    //
    // 'pending_cash' was a TERMINAL state. Nothing anywhere ever moved it on,
    // so a rider collecting UGX 45,000 at the door left no record of it and the
    // order stayed permanently unpaid in the books. Cash revenue was therefore
    // never confirmed and never reconcilable.
    //
    // Gated on CASH_CONFIRMATION_REQUIRED because riders running the OLD app do
    // not send the flag: enforcing this before that build is out would leave
    // them unable to complete any cash delivery. The ledger records the
    // collection either way, so nothing is lost by waiting to enforce it.
    $requiresCashConfirm = false;
    $payableForCash = 0.0;
    if ($next === 'delivered') {
        $deliverable = loadDeliverable($dbh, $source, $orderId);
        if ($deliverable && strcasecmp((string)$deliverable['payment_status'], 'pending_cash') === 0) {
            $payableForCash = (float)$deliverable['total_amount'];
            $confirmed = in_array((string)param('cash_collected', ''), ['1', 'true', 'yes'], true);
            $enforce = env('CASH_CONFIRMATION_REQUIRED', '0') === '1';

            if (!$confirmed && $enforce) {
                // A distinct code so the app can open its confirm sheet rather
                // than showing this as a generic failure.
                fail('Confirm you have collected UGX ' . number_format($payableForCash, 0)
                     . ' in cash before marking this delivered.', 409, 'CASH_NOT_CONFIRMED');
            }
            $requiresCashConfirm = $confirmed || !$enforce;
        }
    }

    try {
        $dbh->beginTransaction();

        if ($next === 'delivered') {
            $dbh->prepare(
                "UPDATE rider_assignments SET status = ?, delivered_at = NOW(), completed_at = NOW() WHERE id = ?"
            )->execute([$next, $assignment['id']]);

            applyDeliveryStatus($dbh, $source, $orderId, $map, $next);

            // Settle the cash, inside the same transaction as the delivery.
            //
            // The `AND payment_status = 'pending_cash'` guard makes this
            // idempotent and means a retried request can never overwrite a
            // genuinely electronic payment. The amount comes from the stored
            // total, never from the rider's device — same rule api/payment.php
            // enforces for the customer.
            if ($requiresCashConfirm) {
                if ($source === 'order') {
                    $dbh->prepare(
                        "UPDATE orders
                            SET payment_status = 'paid', payment_method = 'cash',
                                cash_collected_by = ?, cash_collected_at = NOW(),
                                payment_captured_at = COALESCE(payment_captured_at, NOW())
                          WHERE orderid = ? AND payment_status = 'pending_cash'"
                    )->execute([$rider['id'], $orderId]);
                } else {
                    $dbh->prepare(
                        "UPDATE surplus_orders
                            SET payment_status = 'paid', payment_method = 'cash',
                                cash_collected_by = ?, cash_collected_at = NOW(),
                                payment_captured_at = COALESCE(payment_captured_at, NOW()),
                                updated_at = NOW()
                          WHERE id = ? AND payment_status = 'pending_cash'"
                    )->execute([$rider['id'], $orderId]);
                }
            }

            // Credited in the same transaction as the delivery itself, so a
            // rider is never marked as having delivered something without
            // also being paid for it — both commit together or neither does.
            $credit = creditRiderEarnings($dbh, $rider['id'], $orderId, $source);
            if (!$credit['ok']) {
                throw new RuntimeException($credit['error']);
            }

            // A delivered surplus order also pays the VENDOR. Same transaction
            // for the same reason: the delivery, the rider's pay and the
            // vendor's pay are one event, and a partial commit would leave a
            // vendor uncredited for goods that have demonstrably arrived.
            if ($source === 'surplus') {
                require_once __DIR__ . '/../includes/vendor_earnings.php';
                $vendorCredit = creditVendorEarnings($dbh, $orderId);
                // NOT_PAID is not a fault to abort on. The rider is at the
                // customer's door and the goods have arrived; refusing to
                // record that helps nobody, and the order shows up on the
                // reconciliation page as "delivered but not paid" for someone
                // to chase. Any OTHER failure is a real fault and still aborts.
                if (!$vendorCredit['ok'] && ($vendorCredit['code'] ?? '') !== 'NOT_PAID') {
                    throw new RuntimeException($vendorCredit['error']);
                }
            }
        } else {
            $dbh->prepare("UPDATE rider_assignments SET status = ? WHERE id = ?")
                ->execute([$next, $assignment['id']]);

            applyDeliveryStatus($dbh, $source, $orderId, $map, $next);

            // Fetch the route once, when the load is collected.
            //
            // The customer's tracking screen polls every ten seconds; asking
            // Google for the same geometry on each poll would be ~180 billable
            // calls per delivery for a line that does not change. Cached on the
            // assignment, so a reassignment re-fetches and a redelivery does not
            // inherit the previous rider's route.
            if ($next === 'picked_up' && empty($assignment['route_polyline'])) {
                $d = loadDeliverable($dbh, $source, $orderId);
                if ($d && $d['pickup_lat'] !== null && $d['pickup_lng'] !== null
                    && $d['dest_lat'] !== null && $d['dest_lng'] !== null) {
                    require_once __DIR__ . '/../includes/google_routes.php';
                    $route = roadDistanceBetween(
                        (float)$d['pickup_lat'], (float)$d['pickup_lng'],
                        (float)$d['dest_lat'], (float)$d['dest_lng'],
                        true
                    );
                    $dbh->prepare(
                        "UPDATE rider_assignments
                            SET route_polyline = ?, route_distance_km = ?,
                                route_duration_min = ?, route_fetched_at = NOW()
                          WHERE id = ?"
                    )->execute([
                        $route['polyline'] ?? null,
                        round($route['km'], 2),
                        round($route['minutes'], 1),
                        $assignment['id'],
                    ]);
                }
            }
        }

        // Shop orders only — surplus_orders has no status_history column.
        if ($source === 'order') {
            appendStatusHistory($dbh, $orderId, $map['label'], 'Updated by rider ' . $rider['name']);
        }

        $dbh->commit();

        // After the commit, so a ledger row never claims a collection that
        // rolled back.
        if ($requiresCashConfirm) {
            recordPaymentEvent($dbh, $source === 'order' ? 'order' : 'surplus', $orderId,
                'cash_collected', [
                    'from_status' => 'pending_cash',
                    'to_status'   => 'paid',
                    'method'      => 'cash',
                    'amount'      => $payableForCash,
                    'actor_type'  => 'rider',
                    'actor_id'    => (int)$rider['id'],
                ]);
        }
    } catch (Exception $e) {
        // Catches both PDOException (the write itself) and the
        // RuntimeException creditRiderEarnings() throws — either way the
        // delivery and its payout must rise and fall together.
        if ($dbh->inTransaction()) $dbh->rollBack();
        error_log('rider update_status failed: ' . $e->getMessage());
        fail('Could not update the delivery. Please try again.');
    }

    // The second of the two moments a customer is texted: their order has left
    // for their address. 'picked_up' is that transition -- $STATUS_MAP turns it
    // into the "Out for Delivery" label.
    //
    // Outside the transaction and swallowed on failure, for the same reason as
    // order placement: the delivery genuinely progressed, and failing the
    // rider's status update because a text did not send would strand the order.
    if ($next === 'picked_up') {
        try {
            require_once __DIR__ . '/../includes/brevo-sms.php';
            // Via loadDeliverable so a surplus customer is texted too. Reading
            // `orders` directly here would have found nothing for a surplus
            // job and silently sent no message.
            $row = loadDeliverable($dbh, $source, $orderId);
            if ($row && !empty($row['mobile'])) {
                sendSmsWithBrevo(
                    $row['mobile'],
                    "AfamFresh: order #{$orderId} is out for delivery and on its way to you."
                );
            }
        } catch (Throwable $e) {
            error_log("Order $orderId marked out for delivery but the SMS failed: " . $e->getMessage());
        }
    }

    // A rider completing a surplus delivery writes surplus_orders.status
    // straight to 'delivered', which means the vendor never marks it and
    // api/surplus-orders.php's own notification never fires. Without this, the
    // customer is told their order is on its way and then never told it
    // arrived. Sent here so the same message reaches them either way.
    if ($next === 'delivered' && $source === 'surplus') {
        try {
            require_once __DIR__ . '/../includes/notifications.php';
            $who = $dbh->prepare(
                "SELECT so.user_id, i.name AS product_name, v.business_name
                   FROM surplus_orders so
                   JOIN surplus_listings sl ON sl.id = so.listing_id
                   JOIN items i ON i.id = sl.product_id
                   JOIN vendors v ON v.id = sl.vendor_id
                  WHERE so.id = ?"
            );
            $who->execute([$orderId]);
            if ($row = $who->fetch(PDO::FETCH_ASSOC)) {
                addNotification(
                    (int)$row['user_id'],
                    'Order delivered',
                    'Your surplus order #' . $orderId . ' (' . $row['product_name']
                        . ' from ' . $row['business_name'] . ') has been delivered.',
                    'order',
                    null,
                    ['push', 'email']
                );
            }
        } catch (Throwable $e) {
            error_log("Surplus order $orderId delivered but the customer was not notified: " . $e->getMessage());
        }
    }

    echo json_encode([
        'success'        => true,
        'status'         => $next,
        'order_status'   => $map['label'],
        'current_status' => $map['current'],
    ]);
    exit;
}

// =============================================================
// ACTION: DUTY STATUS (online / offline)
// =============================================================
if ($action === 'duty_status') {
    $rider  = requireRider($dbh, $user_id);
    $status = trim((string)param('status', ''));

    // Validated against the enum: an unexpected value would be coerced to ''
    // by MySQL, leaving the rider in neither state.
    if (!in_array($status, ['online', 'offline'], true)) {
        fail('Status must be either online or offline.');
    }

    $dbh->prepare("UPDATE riders SET status = ? WHERE id = ?")->execute([$status, $rider['id']]);

    echo json_encode(['success' => true, 'status' => $status, 'is_online' => $status === 'online']);
    exit;
}

// =============================================================
// ACTION: LOCATION
// =============================================================
if ($action === 'location') {
    $rider = requireRider($dbh, $user_id);
    $lat = param('lat');
    $lng = param('lng');

    if (!is_numeric($lat) || !is_numeric($lng)) fail('A valid latitude and longitude are required.');
    $lat = (float)$lat;
    $lng = (float)$lng;
    if ($lat < -90 || $lat > 90 || $lng < -180 || $lng > 180) {
        fail('Those coordinates are out of range.');
    }

    $dbh->prepare(
        "UPDATE riders SET current_lat = ?, current_lng = ?, last_location_update = NOW() WHERE id = ?"
    )->execute([$lat, $lng, $rider['id']]);

    // Quality metadata, all optional so an older rider build still posts fine.
    $accuracy = is_numeric(param('accuracy')) ? (float)param('accuracy') : null;
    $speed    = is_numeric(param('speed'))    ? (float)param('speed')    : null;
    $heading  = is_numeric(param('heading'))  ? (float)param('heading')  : null;

    // Breadcrumb the active delivery, so the customer's tracking view has a
    // trail to draw.
    //
    // `source` is selected and stored: order_tracking_logs used to hold a bare
    // order_id, and shop order 41 and surplus order 41 both exist — so the two
    // trails interleaved into one stream and a customer tracking either would
    // have been shown the other rider's position.
    $active = $dbh->prepare(
        "SELECT id, order_id, source FROM rider_assignments
          WHERE rider_id = ? AND status IN ('picked_up','on_way')
          ORDER BY assigned_at DESC LIMIT 1"
    );
    $active->execute([$rider['id']]);
    $assignmentRow = $active->fetch(PDO::FETCH_ASSOC);
    $orderId = $assignmentRow['order_id'] ?? null;

    // A fix worse than 100m is noise. Stored, it makes the customer's map jump
    // a block sideways and back — indistinguishable from the rider actually
    // moving. The rider's own position on `riders` is still updated above, so
    // nothing is lost by dropping it from the trail.
    $usable = $accuracy === null || $accuracy <= 100;

    if ($orderId && $usable) {
        $dbh->prepare(
            "INSERT INTO order_tracking_logs
                (order_id, source, rider_id, assignment_id, lat, lng, accuracy_m, speed_mps, heading_deg)
             VALUES (?,?,?,?,?,?,?,?,?)"
        )->execute([
            $orderId,
            $assignmentRow['source'] ?? 'order',
            $rider['id'],
            $assignmentRow['id'] ?? null,
            $lat, $lng,
            $accuracy !== null ? (int)round($accuracy) : null,
            $speed,
            $heading !== null ? (int)round($heading) : null,
        ]);
    }

    echo json_encode([
        'success' => true,
        'tracked_order_id' => $orderId ? (int)$orderId : null,
        'source' => $assignmentRow['source'] ?? null,
        // So a rider build can tell a dropped-for-accuracy fix from a stored
        // one without guessing.
        'recorded' => (bool)($orderId && $usable),
    ]);
    exit;
}

// =============================================================
// ACTION: UPLOAD PROOF OF DELIVERY
// =============================================================
if ($action === 'upload_proof') {
    $rider   = requireRider($dbh, $user_id);
    $orderId = (int)param('order_id', 0);
    $source  = (string)param('source', 'order');
    if (!$orderId) fail('No order was specified.');
    if (!isDispatchSource($source)) fail('That is not a kind of delivery.');

    $assignment = assignmentFor($dbh, $rider['id'], $orderId, $source);
    if (!$assignment) fail('That delivery is not assigned to you.');

    if (!isset($_FILES['photo'])) fail('No photo was received.');

    require_once __DIR__ . '/../includes/image_upload.php';

    // Existing photo passed as $old so a re-take replaces rather than
    // accumulates; the helper only unlinks names it generated itself.
    //
    // Read through loadDeliverable so this works for both kinds of order.
    // surplus_orders gained delivery_photo alongside the columns `orders` has
    // carried all along, which is what makes one path serve both.
    $existing = loadDeliverable($dbh, $source, $orderId);
    if (!$existing) fail('Order not found.');
    $prev = $existing['delivery_photo'] ?? null;

    $result = saveUploadedImage(
        $_FILES['photo'],
        'proof',
        'proof',
        $prev ?: null
    );

    if (!$result['ok']) fail($result['error']);

    saveDeliveryProof($dbh, $source, $orderId, $result['filename']);

    echo json_encode([
        'success' => true,
        'proof_photo_url' => storageUrl('proof/' . $result['filename']),
    ]);
    exit;
}

// =============================================================
// ACTION: EARNINGS
//
// Summary (today / this week / all time, net of commission) plus the
// per-delivery ledger. Every row already exists in rider_earnings —
// written once, at the moment a delivery is marked delivered — so this
// only reads, never recomputes.
// =============================================================
if ($action === 'earnings') {
    $rider = requireRider($dbh, $user_id);

    $totals = $dbh->prepare(
        "SELECT
            SUM(CASE WHEN DATE(created_at) = CURDATE() THEN net_earnings ELSE 0 END) AS today,
            SUM(CASE WHEN YEARWEEK(created_at, 1) = YEARWEEK(CURDATE(), 1) THEN net_earnings ELSE 0 END) AS this_week,
            SUM(net_earnings) AS all_time,
            SUM(CASE WHEN is_paid = 0 THEN net_earnings ELSE 0 END) AS available,
            COUNT(*) AS delivery_count
         FROM rider_earnings WHERE rider_id = ?"
    );
    $totals->execute([$rider['id']]);
    $t = $totals->fetch(PDO::FETCH_ASSOC);

    // LEFT JOIN, and joined on source as well as id.
    //
    // As an INNER JOIN on order_id alone this had two faults the moment surplus
    // deliveries began being credited: a surplus earning would either vanish
    // from the ledger entirely (no matching orders row) or, worse, match the
    // same-numbered SHOP order and attribute the money to a customer who had
    // nothing to do with it. A rider's pay record is not a place for either.
    //
    // The surplus customer's name is filled in afterwards, from users.
    $rows = $dbh->prepare(
        "SELECT re.id, re.order_id, re.source, re.mileage_fee, re.commission_rate,
                re.commission_amount, re.net_earnings, re.is_estimated, re.is_paid,
                re.created_at, o.fname, o.lname
         FROM rider_earnings re
         LEFT JOIN orders o ON o.orderid = re.order_id AND re.source = 'order'
         WHERE re.rider_id = ?
         ORDER BY re.created_at DESC
         LIMIT 100"
    );
    $rows->execute([$rider['id']]);
    $earningRows = $rows->fetchAll(PDO::FETCH_ASSOC);

    // One lookup for the surplus rows, rather than a query each.
    $surplusIds = array_values(array_unique(array_map(
        fn($r) => (int)$r['order_id'],
        array_filter($earningRows, fn($r) => $r['source'] === 'surplus')
    )));
    $surplusNames = [];
    if ($surplusIds) {
        $in = implode(',', array_fill(0, count($surplusIds), '?'));
        $namesStmt = $dbh->prepare(
            "SELECT so.id, u.fname, u.lname
               FROM surplus_orders so JOIN users u ON u.id = so.user_id
              WHERE so.id IN ($in)"
        );
        $namesStmt->execute($surplusIds);
        foreach ($namesStmt->fetchAll(PDO::FETCH_ASSOC) as $n) {
            $surplusNames[(int)$n['id']] = trim($n['fname'] . ' ' . $n['lname']);
        }
    }

    $history = array_map(function ($r) use ($surplusNames) {
        $name = $r['source'] === 'surplus'
            ? ($surplusNames[(int)$r['order_id']] ?? '')
            : trim($r['fname'] . ' ' . $r['lname']);

        return [
            'id'                => (int)$r['id'],
            'order_id'          => (int)$r['order_id'],
            'source'            => $r['source'],
            'customer_name'     => $name,
            'mileage_fee'       => (float)$r['mileage_fee'],
            'commission_rate'   => (float)$r['commission_rate'],
            'commission_amount' => (float)$r['commission_amount'],
            'net_earnings'      => (float)$r['net_earnings'],
            'is_estimated'      => (bool)$r['is_estimated'],
            'is_paid'           => (bool)$r['is_paid'],
            'created_at'        => $r['created_at'],
        ];
    }, $earningRows);

    echo json_encode([
        'success' => true,
        'summary' => [
            'today'           => round((float)($t['today'] ?? 0), 2),
            'this_week'       => round((float)($t['this_week'] ?? 0), 2),
            'all_time'        => round((float)($t['all_time'] ?? 0), 2),
            'available'       => round((float)($t['available'] ?? 0), 2),
            'delivery_count'  => (int)($t['delivery_count'] ?? 0),
            'commission_rate' => RIDER_COMMISSION_RATE,
        ],
        'history' => $history,
    ]);
    exit;
}

// =============================================================
// ACTION: REQUEST PAYOUT
//
// Mirrors vendor_payout_requests. The requested amount is the unpaid
// total read server-side right now — never trusted from the client.
// =============================================================
if ($action === 'request_payout') {
    $rider = requireRider($dbh, $user_id);

    $availableStmt = $dbh->prepare(
        "SELECT COALESCE(SUM(net_earnings), 0) FROM rider_earnings WHERE rider_id = ? AND is_paid = 0"
    );
    $availableStmt->execute([$rider['id']]);
    $available = round((float)$availableStmt->fetchColumn(), 2);

    if ($available <= 0) {
        fail('You have nothing available to withdraw right now.');
    }

    $pendingStmt = $dbh->prepare(
        "SELECT id FROM rider_payout_requests WHERE rider_id = ? AND status = 'pending'"
    );
    $pendingStmt->execute([$rider['id']]);
    if ($pendingStmt->fetchColumn()) {
        fail('You already have a payout request pending review.');
    }

    $dbh->prepare(
        "INSERT INTO rider_payout_requests (rider_id, amount, status) VALUES (?, ?, 'pending')"
    )->execute([$rider['id'], $available]);

    echo json_encode(['success' => true, 'amount' => $available]);
    exit;
}

// =============================================================
// ACTION: MY PAYOUTS
// =============================================================
if ($action === 'my_payouts') {
    $rider = requireRider($dbh, $user_id);

    $stmt = $dbh->prepare(
        "SELECT id, amount, status, requested_at, processed_at, notes
         FROM rider_payout_requests WHERE rider_id = ? ORDER BY requested_at DESC LIMIT 50"
    );
    $stmt->execute([$rider['id']]);

    $payouts = array_map(function ($r) {
        return [
            'id'            => (int)$r['id'],
            'amount'        => (float)$r['amount'],
            'status'        => $r['status'],
            'requested_at'  => $r['requested_at'],
            'processed_at'  => $r['processed_at'],
            'notes'         => $r['notes'],
        ];
    }, $stmt->fetchAll(PDO::FETCH_ASSOC));

    echo json_encode(['success' => true, 'payouts' => $payouts]);
    exit;
}

fail('Invalid action');
