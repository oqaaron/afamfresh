<?php
// =============================================================
// includes/vendor_earnings.php — crediting a vendor for a sale.
// =============================================================
// vendor_earnings existed with a single UPDATE against it and no INSERT
// anywhere, so vendors had never earned anything. This is the missing half.
//
// Modelled on includes/rider_earnings.php, which does the same job for riders
// and is the pattern this deliberately follows: credit inside the transaction
// that marks delivery, be idempotent through a UNIQUE key, and never let a
// crediting failure roll back the delivery itself.
//
// Credited on DELIVERY, not on payment. Money only exists once goods reached
// the customer, so a cancellation or a no-show never creates an earning that
// has to be clawed back out of a withdrawal the vendor has already requested.
// =============================================================

/**
 * Credit a vendor for one delivered Bulk order.
 *
 * The commission comes from the vendor's own `commission_rate`, which is per
 * vendor and defaults to 10% — not a global constant, unlike riders.
 *
 * The vendor earns on the goods only. delivery_fee is excluded: it is the
 * rider's to be paid from, and including it would pay the vendor for a service
 * somebody else performed.
 *
 * Idempotent via UNIQUE(source, order_id, vendor_id). Calling it twice for the
 * same order — a retried request, a status set to delivered twice — does not
 * pay twice.
 *
 * @param PDO $dbh
 * @param int $BulkOrderId  Bulk_orders.id
 * @return array{ok: bool, error: ?string}
 */
function creditVendorEarnings($dbh, $BulkOrderId) {
    // The vendor and the money both come from the listing behind the order,
    // read server-side. Nothing here is taken from a request.
    $stmt = $dbh->prepare(
        "SELECT so.id, so.total_price, so.delivery_fee, so.payment_status,
                sl.vendor_id, v.commission_rate
           FROM Bulk_orders so
           JOIN Bulk_listings sl ON sl.id = so.listing_id
           JOIN vendors v ON v.id = sl.vendor_id
          WHERE so.id = ?"
    );
    $stmt->execute([$BulkOrderId]);
    $row = $stmt->fetch(PDO::FETCH_ASSOC);

    if (!$row) {
        error_log("creditVendorEarnings: Bulk order $BulkOrderId has no listing or vendor");
        return ['ok' => false, 'error' => 'That order has no vendor to credit.'];
    }

    // No money in, no money out.
    //
    // This check was missing, and could not have been added any earlier: cash
    // orders sat at 'pending_cash' forever because nothing confirmed
    // collection, so requiring 'paid' would have stranded every cash vendor
    // payment. Now that api/rider.php settles cash at the moment of delivery —
    // in the very transaction that calls this — 'paid' is reachable by both
    // routes and the check is safe.
    //
    // `code` distinguishes this from a genuine fault. api/rider.php aborts the
    // delivery transaction on a crediting error, which is right for a missing
    // vendor — but wrong here: the rider is standing at the customer's door
    // with goods that have demonstrably arrived, and refusing to record the
    // delivery helps nobody. An unpaid delivered order is instead surfaced on
    // the reconciliation page's exceptions list, where someone can act on it.
    if (strcasecmp((string)$row['payment_status'], 'paid') !== 0) {
        error_log("creditVendorEarnings: Bulk order $BulkOrderId is '"
                  . $row['payment_status'] . "', not paid — vendor not credited");
        return [
            'ok'    => false,
            'code'  => 'NOT_PAID',
            'error' => 'That order has not been paid for.',
        ];
    }

    $vendorId = (int)$row['vendor_id'];

    $existing = $dbh->prepare(
        "SELECT id FROM vendor_earnings WHERE source = 'Bulk' AND order_id = ? AND vendor_id = ?"
    );
    $existing->execute([$BulkOrderId, $vendorId]);
    if ($existing->fetchColumn()) {
        return ['ok' => true, 'error' => null];
    }

    // total_price IS the goods total, full stop — delivery_fee is a separate
    // column that is never folded into it anywhere in the checkout path
    // (api/Bulk-orders.php computes total_price as unit_price * quantity
    // before delivery_fee is even known; BulkPayableTotal() then sums the
    // two for the amount actually charged). A previous version of this
    // function subtracted delivery_fee here "defensively", which was wrong
    // under the schema as it has always behaved: it silently under-credited
    // every vendor, on every delivered Bulk order, by their delivery fee's
    // worth before commission was even applied.
    $goods = max(0.0, (float)$row['total_price']);
    if ($goods <= 0) {
        error_log("creditVendorEarnings: Bulk order $BulkOrderId has no goods value to credit");
        return ['ok' => false, 'error' => 'That order has nothing to credit.'];
    }

    $rate       = (float)($row['commission_rate'] ?? 10.00);
    $commission = round($goods * ($rate / 100), 2);
    $net        = round($goods - $commission, 2);

    try {
        $dbh->prepare(
            "INSERT INTO vendor_earnings
                (vendor_id, order_id, source, order_amount, commission_amount, net_earnings)
             VALUES (?, ?, 'Bulk', ?, ?, ?)"
        )->execute([$vendorId, $BulkOrderId, $goods, $commission, $net]);

        return ['ok' => true, 'error' => null];
    } catch (PDOException $e) {
        // 23000 means a concurrent call won the UNIQUE race. The order is
        // credited either way, so this is not a failure.
        if ($e->getCode() === '23000') {
            return ['ok' => true, 'error' => null];
        }
        error_log("creditVendorEarnings failed for Bulk order $BulkOrderId: " . $e->getMessage());
        return ['ok' => false, 'error' => 'Could not record earnings for this order.'];
    }
}