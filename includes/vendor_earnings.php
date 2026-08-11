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
 * Credit a vendor for one delivered surplus order.
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
 * @param int $surplusOrderId  surplus_orders.id
 * @return array{ok: bool, error: ?string}
 */
function creditVendorEarnings($dbh, $surplusOrderId) {
    // The vendor and the money both come from the listing behind the order,
    // read server-side. Nothing here is taken from a request.
    $stmt = $dbh->prepare(
        "SELECT so.id, so.total_price, so.delivery_fee,
                sl.vendor_id, v.commission_rate
           FROM surplus_orders so
           JOIN surplus_listings sl ON sl.id = so.listing_id
           JOIN vendors v ON v.id = sl.vendor_id
          WHERE so.id = ?"
    );
    $stmt->execute([$surplusOrderId]);
    $row = $stmt->fetch(PDO::FETCH_ASSOC);

    if (!$row) {
        error_log("creditVendorEarnings: surplus order $surplusOrderId has no listing or vendor");
        return ['ok' => false, 'error' => 'That order has no vendor to credit.'];
    }

    $vendorId = (int)$row['vendor_id'];

    $existing = $dbh->prepare(
        "SELECT id FROM vendor_earnings WHERE source = 'surplus' AND order_id = ? AND vendor_id = ?"
    );
    $existing->execute([$surplusOrderId, $vendorId]);
    if ($existing->fetchColumn()) {
        return ['ok' => true, 'error' => null];
    }

    // total_price is the goods total after discount; the delivery fee is a
    // separate column, so it is not in here to begin with. Subtracted anyway
    // in case a caller ever folds it in — a defensive max() rather than a
    // silent negative.
    $goods = max(0.0, (float)$row['total_price'] - (float)$row['delivery_fee']);
    if ($goods <= 0) {
        error_log("creditVendorEarnings: surplus order $surplusOrderId has no goods value to credit");
        return ['ok' => false, 'error' => 'That order has nothing to credit.'];
    }

    $rate       = (float)($row['commission_rate'] ?? 10.00);
    $commission = round($goods * ($rate / 100), 2);
    $net        = round($goods - $commission, 2);

    try {
        $dbh->prepare(
            "INSERT INTO vendor_earnings
                (vendor_id, order_id, source, order_amount, commission_amount, net_earnings)
             VALUES (?, ?, 'surplus', ?, ?, ?)"
        )->execute([$vendorId, $surplusOrderId, $goods, $commission, $net]);

        return ['ok' => true, 'error' => null];
    } catch (PDOException $e) {
        // 23000 means a concurrent call won the UNIQUE race. The order is
        // credited either way, so this is not a failure.
        if ($e->getCode() === '23000') {
            return ['ok' => true, 'error' => null];
        }
        error_log("creditVendorEarnings failed for surplus order $surplusOrderId: " . $e->getMessage());
        return ['ok' => false, 'error' => 'Could not record earnings for this order.'];
    }
}