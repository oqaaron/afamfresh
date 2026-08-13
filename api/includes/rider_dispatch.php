<?php
// =============================================================
// includes/rider_dispatch.php
//
// One shape for a deliverable, whichever table it came from.
//
// The rider system was written when `orders` was the only kind of order, so
// api/rider.php reads o.fname, o.mobile, o.dest_lat and a dozen other columns
// directly. surplus_orders has none of those names: the customer's name lives
// on `users`, the destination is delivery_lat/delivery_lng, and the goods total
// is split across total_price and delivery_fee.
//
// Rather than scatter `if ($source === 'surplus')` through four actions, both
// tables are read here and returned with identical keys. api/rider.php then
// works the same way for both, and the differences are stated once, here, where
// they can be seen together.
//
// WHY `source` EXISTS AT ALL
//
// The two id spaces overlap -- shop order 41 and surplus order 41 both exist.
// Every function here takes a source alongside the id for that reason, and
// refuses an unrecognised one rather than defaulting, because defaulting would
// silently read the wrong customer's address.
// =============================================================

require_once __DIR__ . '/rider_earnings.php';

/** The only two values `source` may take. Anything else is a caller bug. */
function dispatchSources(): array {
    return ['order', 'surplus'];
}

function isDispatchSource(?string $source): bool {
    return in_array((string)$source, dispatchSources(), true);
}

/**
 * Loads one deliverable in a shape api/rider.php can render without branching.
 *
 * Keys are the `orders` names, because that is what the rider app already
 * parses and changing the wire format would break every installed rider build
 * for the sake of tidiness.
 *
 * @return array|null null when there is no such row.
 */
function loadDeliverable(PDO $dbh, string $source, int $orderId): ?array {
    if ($source === 'order') {
        $stmt = $dbh->prepare(
            "SELECT orderid AS order_id, fname, lname, mobile, area, address,
                    delivery_address, status, current_status, payment_status,
                    total_amount, delivery_fee, ordertime,
                    dest_lat, dest_lng, delivery_photo,
                    scheduled_delivery_date, scheduled_delivery_slot
               FROM orders WHERE orderid = ?"
        );
        $stmt->execute([$orderId]);
        $row = $stmt->fetch(PDO::FETCH_ASSOC);
        if (!$row) return null;

        $row['source'] = 'order';
        // Shop orders all leave from the warehouse, so the pickup point is a
        // constant rather than a column. Returned anyway so callers that draw a
        // route do not have to know which kind of order they are holding.
        $row['pickup_address'] = defined('OFFICE_LABEL') ? OFFICE_LABEL : 'AfamFresh warehouse';
        $row['pickup_lat'] = defined('OFFICE_LAT') ? OFFICE_LAT : 0.38082497218633615;
        $row['pickup_lng'] = defined('OFFICE_LNG') ? OFFICE_LNG : 32.65071116168179;
        $row['pickup_code'] = null;
        return $row;
    }

    if ($source === 'surplus') {
        // The customer's name and phone are on `users`; the pickup point is the
        // vendor's location, because a surplus delivery starts at the vendor
        // rather than at the AfamFresh warehouse. That is the substantive
        // difference between the two kinds of job, and the rider needs it.
        $stmt = $dbh->prepare(
            "SELECT so.id AS order_id, u.fname, u.lname, u.mobile,
                    so.delivery_area AS area, so.delivery_address AS address,
                    so.delivery_address, so.status, so.status AS current_status,
                    so.payment_status,
                    (so.total_price + so.delivery_fee) AS total_amount,
                    so.delivery_fee, so.created_at AS ordertime,
                    so.delivery_lat AS dest_lat, so.delivery_lng AS dest_lng,
                    so.delivery_photo,
                    so.scheduled_delivery_date, so.scheduled_delivery_slot,
                    so.pickup_code,
                    v.location AS pickup_address, v.business_name,
                    -- The vendor's pin, not the warehouse: a surplus load is
                    -- collected from their premises. Null until they pin it.
                    v.lat AS pickup_lat, v.lng AS pickup_lng
               FROM surplus_orders so
               JOIN surplus_listings sl ON sl.id = so.listing_id
               JOIN vendors v ON v.id = sl.vendor_id
               JOIN users u ON u.id = so.user_id
              WHERE so.id = ?"
        );
        $stmt->execute([$orderId]);
        $row = $stmt->fetch(PDO::FETCH_ASSOC);
        if (!$row) return null;

        $row['source'] = 'surplus';
        return $row;
    }

    return null;
}

/**
 * The line items on a deliverable.
 *
 * A surplus order is always exactly one listing of one product -- there is no
 * cart -- so it returns a single row rather than reading an items table it
 * does not have.
 */
function deliverableItems(PDO $dbh, string $source, int $orderId): array {
    if ($source === 'order') {
        $stmt = $dbh->prepare(
            "SELECT product_name, quantity, price FROM order_items WHERE order_id = ?"
        );
        $stmt->execute([$orderId]);
        return array_map(fn($i) => [
            'name'     => $i['product_name'],
            'quantity' => (int)$i['quantity'],
            'price'    => (float)$i['price'],
        ], $stmt->fetchAll(PDO::FETCH_ASSOC));
    }

    $stmt = $dbh->prepare(
        "SELECT i.name, so.quantity, so.total_price
           FROM surplus_orders so
           JOIN surplus_listings sl ON sl.id = so.listing_id
           JOIN items i ON i.id = sl.product_id
          WHERE so.id = ?"
    );
    $stmt->execute([$orderId]);
    $row = $stmt->fetch(PDO::FETCH_ASSOC);
    if (!$row) return [];

    $quantity = (float)$row['quantity'];

    return [[
        'name' => $row['name'],
        // Bulk surplus is ordered in decimal kilograms. Casting to int here
        // would show a rider "20" for a 20.5 kg load they have to fit on a bike.
        'quantity' => $quantity,
        // UNIT price, matching what order_items holds for shop orders. The row
        // stores the order total, and handing that over as `price` would make
        // the app's price × quantity render a 250,000-shilling order as five
        // million.
        'price'    => $quantity > 0 ? round((float)$row['total_price'] / $quantity, 2) : 0.0,
    ]];
}

/**
 * Writes a delivery status back to whichever table owns the order.
 *
 * The shop and surplus status vocabularies are different and deliberately not
 * merged: `orders` uses human labels ("Out for Delivery") in `status` plus a
 * machine value in `current_status`, while surplus_orders uses a single
 * lowercase enum. Mapping one onto the other would mean writing a value that
 * the vendor's own screen cannot render.
 */
function applyDeliveryStatus(PDO $dbh, string $source, int $orderId, array $map, string $next): void {
    if ($source === 'order') {
        if ($next === 'delivered') {
            $dbh->prepare(
                "UPDATE orders SET status = ?, current_status = ?, delivered_at = NOW() WHERE orderid = ?"
            )->execute([$map['label'], $map['current'], $orderId]);
        } else {
            $dbh->prepare("UPDATE orders SET status = ?, current_status = ? WHERE orderid = ?")
                ->execute([$map['label'], $map['current'], $orderId]);
        }
        return;
    }

    // Surplus: only 'delivered' has a counterpart worth writing. A rider
    // picking the load up does not change what the ORDER is -- it is still
    // 'ready' until it arrives -- and inventing intermediate states here would
    // put values in the column that api/surplus-orders.php would then reject as
    // invalid.
    if ($next === 'delivered') {
        $dbh->prepare(
            "UPDATE surplus_orders
                SET status = 'delivered', delivered_at = NOW(), updated_at = NOW()
              WHERE id = ?"
        )->execute([$orderId]);
    }
}

/**
 * Records a proof-of-delivery photo against whichever table owns the order.
 *
 * Both tables carry the same three columns with the same meanings, so this is
 * a table name and nothing else — but it has to be a branch rather than a
 * parameterised table, since a table name cannot be bound.
 */
function saveDeliveryProof(PDO $dbh, string $source, int $orderId, string $filename): void {
    if ($source === 'order') {
        $dbh->prepare(
            "UPDATE orders
                SET delivery_photo = ?, delivery_confirmed = 1, delivery_confirmed_at = NOW()
              WHERE orderid = ?"
        )->execute([$filename, $orderId]);
        return;
    }

    $dbh->prepare(
        "UPDATE surplus_orders
            SET delivery_photo = ?, delivery_confirmed = 1, delivery_confirmed_at = NOW(),
                updated_at = NOW()
          WHERE id = ?"
    )->execute([$filename, $orderId]);
}

/**
 * What a rider earns for delivering a surplus order.
 *
 * Shop riders are paid from the MILEAGE component of the delivery fee, but a
 * surplus fee is charged on WEIGHT (base + per-kg, capped, waived above a
 * threshold) and carries no mileage figure to draw from.
 *
 * So the rider is paid out of the surplus delivery fee itself, less the same
 * platform commission. Two things follow from that choice, both deliberate:
 *
 *   - It can never pay out more than was collected, unlike a distance-based
 *     rate applied to a fee that was not calculated by distance.
 *   - It puts that fee to work. Before dispatch existed the platform kept the
 *     surplus delivery fee while the vendor did the driving, which was a fee
 *     charged for a service nobody performed.
 *
 * A pickup-only order has no delivery fee and should never have been
 * dispatched; it returns null rather than crediting zero, so the mistake is
 * visible in the log instead of silently paying a rider nothing.
 *
 * @return array{amount: float, estimated: bool}|null
 */
function surplusRiderFeeFor(PDO $dbh, int $orderId): ?array {
    $stmt = $dbh->prepare("SELECT delivery_fee FROM surplus_orders WHERE id = ?");
    $stmt->execute([$orderId]);
    $fee = $stmt->fetchColumn();

    if ($fee === false || (float)$fee <= 0) {
        return null;
    }

    // Not an estimate: this is the exact amount the customer was charged for
    // delivery, read from the order.
    return ['amount' => (float)$fee, 'estimated' => false];
}

/**
 * Fetches and caches a fresh assignment's route geometry, if it does not
 * already have one.
 *
 * Called at dispatch time (admin/orders.php, admin/surplus-orders.php) rather
 * than waiting for the rider to mark the delivery picked_up, which is where
 * this used to happen exclusively. That left a real gap: the rider's
 * Navigate screen and the customer's tracking map both had nothing to draw
 * for however long a delivery sat in "assigned". api/rider.php's own
 * picked_up-time fetch is left in place as a safety net — it only runs when
 * `route_polyline` is still empty, so it costs nothing on the normal path
 * where this function already filled it in, and still recovers a route for
 * an assignment created before this existed, or where Google was briefly
 * unreachable at dispatch time.
 *
 * Not repeated on reassignment: the pickup and drop-off points belong to the
 * order, not the rider carrying it, so whatever route is already cached
 * stays correct regardless of who is assigned next.
 */
function cacheAssignmentRoute(PDO $dbh, string $source, int $orderId, int $assignmentId): void {
    $d = loadDeliverable($dbh, $source, $orderId);
    if (!$d || $d['pickup_lat'] === null || $d['pickup_lng'] === null
        || $d['dest_lat'] === null || $d['dest_lng'] === null) {
        return;
    }

    require_once __DIR__ . '/google_routes.php';
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
        $assignmentId,
    ]);
}