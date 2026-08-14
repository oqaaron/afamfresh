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
 * Matches the shop's own rule in includes/rider_earnings.php: paid from
 * carriage only (base + weight + distance) — never the service fee,
 * insurance, or processing fee, which cover the platform's own costs and are
 * not something the rider performs. A surplus order has no single "mileage"
 * figure the way a shop order does, so this sums the carriage components out
 * of the stored `delivery_fee_breakdown` JSON instead.
 *
 * `delivery_fee_breakdown` has two shapes, both handled:
 *
 *   - Current: {base_fee, weight_fee, distance_fee, service_fee,
 *     insurance_fee, processing_fee, total_fee, ...} — carriage is the first
 *     three, summed.
 *   - Legacy ("type":"weight_based"): {base_fee, weight_charge, total_fee}
 *     from before service/insurance/processing existed. total_fee here IS
 *     carriage in full — there is nothing else in it to strip out.
 *
 * Falls back to the full delivery_fee only if the breakdown is missing or
 * unparseable, which should not happen for any order placed through the
 * normal checkout path — logged when it does, so a bad fallback is visible
 * rather than silently paying from the wrong base.
 *
 * A pickup-only order has no delivery fee and should never have been
 * dispatched; it returns null rather than crediting zero, so the mistake is
 * visible in the log instead of silently paying a rider nothing.
 *
 * @return array{amount: float, estimated: bool}|null
 */
function surplusRiderFeeFor(PDO $dbh, int $orderId): ?array {
    $stmt = $dbh->prepare(
        "SELECT delivery_fee, delivery_fee_breakdown FROM surplus_orders WHERE id = ?"
    );
    $stmt->execute([$orderId]);
    $row = $stmt->fetch(PDO::FETCH_ASSOC);

    if (!$row || $row['delivery_fee'] === null || (float)$row['delivery_fee'] <= 0) {
        return null;
    }

    $fullFee = (float)$row['delivery_fee'];
    $breakdown = $row['delivery_fee_breakdown'] ? json_decode($row['delivery_fee_breakdown'], true) : null;

    if (!is_array($breakdown)) {
        error_log("surplusRiderFeeFor: order $orderId has no parseable delivery_fee_breakdown, "
                 . "falling back to the full delivery_fee");
        return ['amount' => $fullFee, 'estimated' => true];
    }

    // Legacy shape: no service_fee key means nothing was ever added beyond
    // carriage, so the total is already the right number.
    if (!array_key_exists('service_fee', $breakdown)) {
        return ['amount' => $fullFee, 'estimated' => false];
    }

    $carriage = (float)($breakdown['base_fee'] ?? 0)
              + (float)($breakdown['weight_fee'] ?? 0)
              + (float)($breakdown['distance_fee'] ?? 0);

    return ['amount' => $carriage, 'estimated' => false];
}

/**
 * Fetches and caches an assignment's route geometry, if it does not already
 * have one.
 *
 * SELF-GUARDING: callers do not need to check `route_polyline` first, and
 * should not — every call site is expected to call this unconditionally.
 * That matters for reassignment: the pickup and drop-off points belong to
 * the order, not the rider carrying it, so a route already cached is still
 * correct and this is a one-query no-op. But an assignment that has NEVER
 * had a route cached — one created before this function existed, or one
 * where an earlier Google call failed — genuinely needs this to run on
 * reassignment too, or it stays routeless forever. An external `empty()`
 * check at the call site (the original version of this function) cannot
 * tell those two cases apart without a query of its own, so the check
 * belongs here, once, instead of duplicated at every caller.
 *
 * Called at dispatch time and on reassignment (admin/orders.php,
 * admin/surplus-orders.php) rather than waiting for the rider to mark the
 * delivery picked_up, which is where this used to happen exclusively. That
 * left a real gap: the rider's Navigate screen and the customer's tracking
 * map both had nothing to draw for however long a delivery sat in
 * "assigned". api/rider.php's own picked_up-time call is left in place as a
 * safety net for the same reason — Google can still fail here.
 */
function cacheAssignmentRoute(PDO $dbh, string $source, int $orderId, int $assignmentId): void {
    $existing = $dbh->prepare("SELECT route_polyline FROM rider_assignments WHERE id = ?");
    $existing->execute([$assignmentId]);
    if (!empty($existing->fetchColumn())) {
        return;
    }

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

/** Off-route distance that counts as a genuine deviation, not GPS noise. */
const REROUTE_DISTANCE_METERS = 200;

/** Minimum time between reroute fetches for the same assignment. */
const REROUTE_COOLDOWN_SECONDS = 60;

/**
 * Refetches the cached route from the rider's CURRENT position to the
 * destination when they have drifted meaningfully off the last one — the
 * "recalculating..." behaviour every turn-by-turn app has, which this one
 * never did: cacheAssignmentRoute() fetches pickup->dropoff exactly once, at
 * dispatch, and nothing before this ever revisited it.
 *
 * Deliberately conservative on cost. The distance-to-route check itself is
 * free (pure PHP against an already-decoded polyline) and can run on every
 * poll; the Google call behind it only fires when BOTH:
 *   - the rider is genuinely off-route (REROUTE_DISTANCE_METERS — well past
 *     the accuracy-usable and marker-ratchet tolerances used elsewhere, so
 *     this never fires on ordinary GPS noise or road width), AND
 *   - the last reroute was over REROUTE_COOLDOWN_SECONDS ago, so the
 *     customer's poll and the rider's poll hitting this within the same
 *     window cannot double the call rate, and a rider who stays off-route
 *     gets re-centered at most once a minute rather than once a poll.
 *
 * Self-stabilizing beyond that: a successful reroute's new route starts
 * AT the rider's current position, so the very next check reads ~0m
 * off-route and does not refire unless they drift again.
 *
 * @return array{polyline:string,distance_km:float,duration_min:float}|null
 *         The fresh route if one was fetched — callers should use it
 *         immediately rather than serving the now-stale value they already
 *         had for one more poll cycle. Null when nothing needed to change,
 *         or when a refetch was attempted but no provider could give
 *         geometry (in which case the OLD route is left in place; a
 *         stale-but-roughly-right line beats no line).
 */
function rerouteIfNeeded(
    PDO $dbh, int $assignmentId, ?string $currentPolyline, ?string $routeFetchedAt,
    float $riderLat, float $riderLng, float $destLat, float $destLng
): ?array {
    if (!$currentPolyline) return null;

    if ($routeFetchedAt !== null) {
        $ageSeconds = time() - strtotime($routeFetchedAt);
        if ($ageSeconds < REROUTE_COOLDOWN_SECONDS) return null;
    }

    require_once __DIR__ . '/google_routes.php';

    $points = decodeEncodedPolyline($currentPolyline);
    if (count($points) < 2) return null;

    $offRouteMeters = distanceFromRouteMeters($points, $riderLat, $riderLng);
    if ($offRouteMeters <= REROUTE_DISTANCE_METERS) return null;

    $fresh = roadDistanceBetween($riderLat, $riderLng, $destLat, $destLng, true);
    if (empty($fresh['polyline'])) {
        error_log("reroute: assignment $assignmentId is off-route but no provider returned geometry, keeping previous route");
        return null;
    }

    $dbh->prepare(
        "UPDATE rider_assignments
            SET route_polyline = ?, route_distance_km = ?,
                route_duration_min = ?, route_fetched_at = NOW()
          WHERE id = ?"
    )->execute([
        $fresh['polyline'],
        round($fresh['km'], 2),
        round($fresh['minutes'], 1),
        $assignmentId,
    ]);

    error_log(sprintf(
        'reroute: assignment %d was %dm off-route, refetched via %s',
        $assignmentId, (int)round($offRouteMeters), $fresh['source']
    ));

    return [
        'polyline' => $fresh['polyline'],
        'distance_km' => round($fresh['km'], 2),
        'duration_min' => round($fresh['minutes'], 1),
    ];
}