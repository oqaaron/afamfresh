<?php
// =============================================================
// admin/Bulk-listings.php
//
// Review the Bulk listings vendors submit: approve, reject, or correct
// before approving.
//
// The dashboard's JS panel could only approve or reject a pending listing.
// That is fine when a listing is right and useless when it is nearly right —
// a 71% discount or a date typed as next year had to be rejected outright and
// re-entered by the vendor. Everything an admin can sensibly correct is
// editable here, and admin_notes finally gets written: the column existed and
// nothing ever set it.
//
// Deliberately server-rendered, like role-requests.php and
// vendor-catalogue.php, rather than another fetch()-driven panel. The JS one
// silently 404'd for months because its relative URLs were wrong.
// =============================================================

require_once __DIR__ . '/auth_check.php';
require_once __DIR__ . '/includes/config.php';
require_once __DIR__ . '/../includes/vendor-notification-helper.php';
require_once __DIR__ . '/../includes/csrf.php';
require_once __DIR__ . '/../includes/admin_permissions.php';
require_once __DIR__ . '/../includes/admin_audit.php';
require_once __DIR__ . '/../includes/bulk_listing_rules.php';
requireAdminPermission('Bulk.manage_listings');

$flash = '';
$flashError = '';

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    verifyCsrf();
    $id       = (int)($_POST['listing_id'] ?? 0);
    $decision = $_POST['decision'] ?? '';
    $notes    = trim($_POST['admin_notes'] ?? '');

    // Read the listing and its owner first: the notification needs the
    // vendor's user id, and the recalculation needs original_price.
    $cur = $dbh->prepare(
        "SELECT sl.*, v.user_id, i.name AS product_name
           FROM Bulk_listings sl
           JOIN vendors v ON v.id = sl.vendor_id
           LEFT JOIN items i ON i.id = sl.product_id
          WHERE sl.id = ?"
    );
    $cur->execute([$id]);
    $listing = $cur->fetch(PDO::FETCH_ASSOC);

    if (!$listing) {
        $flashError = 'No such listing.';
    } else {
        $productName = $listing['product_name'] ?: 'your Bulk listing';

        // A wholesale listing is priced outright, so none of the discount
        // machinery below applies to it: it carries discount 0 and often no
        // original_price at all. Left unbranched, the range check would refuse
        // every wholesale listing an admin tried to act on, and if it somehow
        // passed, recomputing from a 0 discount against a NULL original_price
        // would set discounted_price to 0 — the listing would go to sale free.
        $isWholesale = ($listing['listing_type'] === 'wholesale');

        // ---- Adjustments, applied whichever decision follows ----
        // ?? '' because the discount input is not rendered at all for a
        // wholesale row — reading it unguarded warns on PHP 8 and reads as 0.
        $discount = ($_POST['discount_percent'] ?? '') !== '' ? (float)$_POST['discount_percent'] : (float)$listing['discount_percent'];
        $quantity = ($_POST['Bulk_quantity'] ?? '') !== '' ? (float)$_POST['Bulk_quantity'] : (float)$listing['Bulk_quantity'];
        $expiry   = trim($_POST['expiry_date'] ?? '') ?: $listing['expiry_date'];
        // Editable directly on a wholesale listing, since there is no discount
        // to derive it from.
        $wholesalePrice = ($isWholesale && trim($_POST['wholesale_price'] ?? '') !== '')
            ? (float)$_POST['wholesale_price']
            : (float)$listing['discounted_price'];

        if ($isWholesale && $wholesalePrice <= 0) {
            $flashError = 'A wholesale listing needs a price above zero.';
        } elseif (!$isWholesale && !isValidSurplusDiscount($discount)) {
            $flashError = surplusDiscountRangeMessage() . ' — the same rule the vendor app enforces.';
        } else {
            // The server has always computed discounted_price rather than
            // trusting a submitted one; an adjustment has to recompute it or
            // the price shown to customers stops matching the discount.
            // Wholesale has no discount to recompute from, so the price stands
            // as entered.
            if ($isWholesale) {
                $discountedPrice = $wholesalePrice;
                $discount = (float)$listing['original_price'] > 0
                    ? round((1 - ($wholesalePrice / (float)$listing['original_price'])) * 100, 2)
                    : 0.00;
            } else {
                $discountedPrice = (float)$listing['original_price'] * (1 - $discount / 100);
            }

            // Shift remaining by the same delta rather than overwriting it:
            // some of the original quantity may already be sold, and setting
            // remaining = quantity would silently resurrect sold stock.
            // (float), not (int): Bulk quantities are decimal kilograms. Casting
            // here truncated an admin's correction and, worse, computed the delta
            // from a truncated original — so adjusting a 20.5 kg listing shifted
            // remaining stock by the wrong amount.
            $delta = $quantity - (float)$listing['Bulk_quantity'];
            $remaining = max(0, (float)$listing['remaining_quantity'] + $delta);

            $status = $listing['status'];
            if ($decision === 'approve') $status = 'approved';
            if ($decision === 'reject')  $status = 'rejected';
            if ($decision === 'cancel')  $status = 'cancelled';

            if ($decision === 'cancel' && $listing['status'] !== 'approved') {
                $flashError = 'Only an approved listing can be pulled from sale.';
            } elseif (($decision === 'reject' || $decision === 'cancel') && $notes === '') {
                $flashError = 'Give a reason — the vendor sees it.';
            } else {
                $upd = $dbh->prepare(
                    "UPDATE Bulk_listings
                        SET discount_percent = ?, discounted_price = ?,
                            Bulk_quantity = ?, remaining_quantity = ?,
                            expiry_date = ?, admin_notes = ?, status = ?
                      WHERE id = ?"
                );
                $upd->execute([
                    $discount, $discountedPrice, $quantity, $remaining,
                    $expiry, ($notes !== '' ? $notes : $listing['admin_notes']), $status, $id,
                ]);

                $changed = [];
                // Tolerance rather than !== on both of these: they are floats,
                // and on the wholesale path $discount is a round()ed result, so
                // an exact comparison reports a change that did not happen.
                if (abs((float)$listing['discount_percent'] - $discount) > 0.0005) $changed[] = 'discount';
                if (abs((float)$listing['Bulk_quantity'] - $quantity) > 0.0005) $changed[] = 'quantity';
                if ($listing['expiry_date'] !== $expiry)               $changed[] = 'expiry date';

                if ($decision === 'approve') {
                    $body = 'Your Bulk listing for "' . $productName . '" has been approved and is now on sale.';
                    if ($changed) {
                        // Says what was altered. A vendor who finds a
                        // different discount live than the one they submitted,
                        // with no explanation, has every reason to distrust it.
                        $body .= ' An administrator adjusted the ' . implode(' and ', $changed) . '.';
                    }
                    if ($notes !== '') $body .= ' Note: ' . $notes;
                    addNotification((int)$listing['user_id'], 'Listing approved: ' . $productName, $body, 'system', null, ['push', 'email']);
                    logAdminAction($dbh, 'Bulk_listing.approved', 'Bulk_listing', (string)$id, "Approved \"$productName\"" . ($changed ? ' (adjusted ' . implode(', ', $changed) . ')' : ''));
                    $flash = 'Approved' . ($changed ? ' with adjustments' : '') . '. The vendor has been notified.';
                } elseif ($decision === 'reject') {
                    addNotification(
                        (int)$listing['user_id'],
                        'Listing not approved: ' . $productName,
                        'Your Bulk listing for "' . $productName . '" was not approved. Reason: ' . $notes,
                        'system', null, ['push', 'email']
                    );
                    logAdminAction($dbh, 'Bulk_listing.rejected', 'Bulk_listing', (string)$id, "Rejected \"$productName\": $notes");
                    $flash = 'Rejected. The vendor has been told why.';
                } elseif ($decision === 'cancel') {
                    // Pulled from sale, not deleted: sl.status = 'approved' is
                    // the marketplace's only visible status (api/Bulk-listings.php
                    // defaults to it), so this alone removes the listing from
                    // sale immediately. Orders already placed against it are
                    // untouched — those go through Bulk-orders.php's own
                    // cancel_order action, separately, per order.
                    addNotification(
                        (int)$listing['user_id'],
                        'Listing pulled from sale: ' . $productName,
                        'Your Bulk listing for "' . $productName . '" was pulled from sale. Reason: ' . $notes,
                        'system', null, ['push', 'email']
                    );
                    logAdminAction($dbh, 'Bulk_listing.pulled', 'Bulk_listing', (string)$id, "Pulled \"$productName\" from sale: $notes");
                    $flash = 'Pulled from sale. The vendor has been told why.';
                } else {
                    $flash = 'Changes saved. The listing stays ' . $status . '.';
                }
            }
        }
    }
}

$statusFilter = $_GET['status'] ?? 'pending';
if (!in_array($statusFilter, ['pending', 'approved', 'rejected', 'cancelled', 'all'], true)) {
    $statusFilter = 'pending';
}

$sql = "SELECT sl.*, v.business_name, i.name AS product_name
          FROM Bulk_listings sl
          LEFT JOIN vendors v ON v.id = sl.vendor_id
          LEFT JOIN items i ON i.id = sl.product_id";
$params = [];
if ($statusFilter !== 'all') {
    $sql .= " WHERE sl.status = ?";
    $params[] = $statusFilter;
}
$sql .= " ORDER BY sl.id DESC";
$stmt = $dbh->prepare($sql);
$stmt->execute($params);
$listings = $stmt->fetchAll(PDO::FETCH_ASSOC);

$pendingCount = (int)$dbh->query("SELECT COUNT(*) FROM Bulk_listings WHERE status = 'pending'")->fetchColumn();
?>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Bulk Listings — AfamFresh Admin</title>
    <script src="https://cdn.tailwindcss.com"></script>
    <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.5.0/css/all.min.css">
</head>
<body class="bg-gray-50 font-sans antialiased">
<div class="flex min-h-screen">
<?php include __DIR__ . '/includes/nav.php'; ?>
<div class="flex-1 overflow-auto">
    <div class="max-w-7xl mx-auto px-6 py-8">
        <h1 class="text-2xl font-bold text-green-800 mb-1">Bulk Listings</h1>
        <p class="text-gray-600 mb-6">
            Approving a listing puts it on sale. You can correct the discount, quantity
            or expiry first — the customer price is recalculated from the discount.
        </p>

        <?php if ($flash): ?>
            <div class="bg-green-100 border border-green-300 text-green-800 px-4 py-3 rounded mb-4"><?= htmlspecialchars($flash) ?></div>
        <?php endif; ?>
        <?php if ($flashError): ?>
            <div class="bg-red-100 border border-red-300 text-red-700 px-4 py-3 rounded mb-4"><?= htmlspecialchars($flashError) ?></div>
        <?php endif; ?>

        <div class="mb-5 space-x-2">
            <?php foreach (['pending', 'approved', 'rejected', 'cancelled', 'all'] as $f): ?>
                <a href="?status=<?= $f ?>"
                   class="px-3 py-1 rounded-full text-sm <?= $statusFilter === $f ? 'bg-green-700 text-white' : 'bg-gray-200 text-gray-700' ?>">
                    <?= ucfirst($f) ?><?= $f === 'pending' && $pendingCount > 0 ? " ($pendingCount)" : '' ?>
                </a>
            <?php endforeach; ?>
        </div>

        <?php if (!$listings): ?>
            <div class="bg-white rounded-xl shadow p-8 text-center text-gray-500">
                Nothing <?= htmlspecialchars($statusFilter) ?> right now.
            </div>
        <?php else: ?>
            <div class="space-y-4">
            <?php foreach ($listings as $l): ?>
                <?php
                $badge = ['pending' => 'bg-yellow-100 text-yellow-800',
                          'approved' => 'bg-green-100 text-green-800',
                          'rejected' => 'bg-red-100 text-red-800',
                          'cancelled' => 'bg-gray-200 text-gray-700'][$l['status']] ?? 'bg-gray-100';
                ?>
                <form method="post" class="bg-white rounded-xl shadow p-5">
                    <?= csrfField() ?>
                    <input type="hidden" name="listing_id" value="<?= (int)$l['id'] ?>">

                    <div class="flex justify-between items-start mb-4">
                        <div>
                            <div class="font-semibold text-gray-900">
                                #<?= (int)$l['id'] ?> · <?= htmlspecialchars($l['product_name'] ?? 'Unknown product') ?>
                            </div>
                            <div class="text-sm text-gray-500">
                                <?= htmlspecialchars($l['business_name'] ?? 'Unknown vendor') ?>
                                · normal price UGX <?= number_format((float)$l['original_price']) ?>
                                · <?= htmlspecialchars($l['listing_type']) ?>
                                · condition <?= htmlspecialchars($l['condition_rating']) ?>
                                <?= (int)$l['pickup_only'] === 1 ? ' · pickup only' : '' ?>
                            </div>
                            <?php if (!empty($l['description'])): ?>
                                <div class="text-sm text-gray-600 mt-1"><?= htmlspecialchars($l['description']) ?></div>
                            <?php endif; ?>
                        </div>
                        <span class="px-2 py-1 rounded-full text-xs font-semibold <?= $badge ?>"><?= htmlspecialchars($l['status']) ?></span>
                    </div>

                    <?php $rowIsWholesale = ($l['listing_type'] === 'wholesale'); ?>
                    <div class="grid grid-cols-1 md:grid-cols-4 gap-3 mb-3">
                        <div>
                            <?php if ($rowIsWholesale): ?>
                                <!-- Wholesale is priced outright. Offering the discount field here
                                     would invite an admin to set a discount the server has no
                                     original_price to apply it to. -->
                                <label class="block text-xs font-medium text-gray-600">Wholesale price (UGX)</label>
                                <input type="number" step="0.01" min="0" name="wholesale_price"
                                       value="<?= htmlspecialchars($l['discounted_price']) ?>"
                                       class="mt-1 w-full border border-gray-300 rounded-lg px-3 py-2 text-sm">
                                <div class="text-xs text-gray-500 mt-1">
                                    <?php if ((float)$l['original_price'] > 0): ?>
                                        vs UGX <?= number_format((float)$l['original_price']) ?> retail
                                    <?php else: ?>
                                        no retail reference given
                                    <?php endif; ?>
                                </div>
                            <?php else: ?>
                                <!-- Bounds come from the same constants the server validates against
                                     (includes/bulk_listing_rules.php). Hardcoding them here once meant
                                     the form refused a correction the server would have accepted, with
                                     no error shown because the browser blocked the submit. -->
                                <label class="block text-xs font-medium text-gray-600">Discount % (<?= rtrim(rtrim(number_format(SURPLUS_DISCOUNT_MIN, 2, '.', ''), '0'), '.') ?>–<?= rtrim(rtrim(number_format(SURPLUS_DISCOUNT_MAX, 2, '.', ''), '0'), '.') ?>)</label>
                                <input type="number" step="0.01" min="<?= SURPLUS_DISCOUNT_MIN ?>" max="<?= SURPLUS_DISCOUNT_MAX ?>" name="discount_percent"
                                       value="<?= htmlspecialchars($l['discount_percent']) ?>"
                                       class="mt-1 w-full border border-gray-300 rounded-lg px-3 py-2 text-sm">
                                <div class="text-xs text-gray-500 mt-1">
                                    Customer pays UGX <?= number_format((float)$l['discounted_price']) ?> now
                                </div>
                            <?php endif; ?>
                        </div>
                        <div>
                            <label class="block text-xs font-medium text-gray-600">Quantity</label>
                            <input type="number" min="0" name="Bulk_quantity"
                                   value="<?= rtrim(rtrim(number_format((float)$l['Bulk_quantity'], 3, '.', ''), '0'), '.') ?>"
                                   class="mt-1 w-full border border-gray-300 rounded-lg px-3 py-2 text-sm">
                            <div class="text-xs text-gray-500 mt-1"><?= rtrim(rtrim(number_format((float)$l['remaining_quantity'], 3, '.', ''), '0'), '.') ?> remaining</div>
                        </div>
                        <div>
                            <label class="block text-xs font-medium text-gray-600">Expires</label>
                            <input type="text" name="expiry_date"
                                   value="<?= htmlspecialchars($l['expiry_date']) ?>"
                                   class="mt-1 w-full border border-gray-300 rounded-lg px-3 py-2 text-sm">
                            <div class="text-xs text-gray-500 mt-1">YYYY-MM-DD HH:MM:SS</div>
                        </div>
                        <div>
                            <label class="block text-xs font-medium text-gray-600">Note to vendor</label>
                            <input type="text" name="admin_notes"
                                   value="<?= htmlspecialchars($l['admin_notes'] ?? '') ?>"
                                   placeholder="Required when rejecting or pulling"
                                   class="mt-1 w-full border border-gray-300 rounded-lg px-3 py-2 text-sm">
                        </div>
                    </div>

                    <div class="space-x-2">
                        <button name="decision" value="approve"
                                class="bg-green-700 hover:bg-green-800 text-white px-4 py-2 rounded-lg text-sm font-semibold">
                            <?= $l['status'] === 'approved' ? 'Save &amp; keep approved' : 'Approve' ?>
                        </button>
                        <button name="decision" value="save"
                                class="bg-gray-200 hover:bg-gray-300 text-gray-800 px-4 py-2 rounded-lg text-sm font-semibold">
                            Save changes only
                        </button>
                        <button name="decision" value="reject"
                                class="bg-red-600 hover:bg-red-700 text-white px-4 py-2 rounded-lg text-sm font-semibold">
                            Reject
                        </button>
                        <?php if ($l['status'] === 'approved'): ?>
                            <button name="decision" value="cancel"
                                    onclick="return confirm('Pull this listing from sale? It will stop showing to customers immediately. Already-placed orders are not affected.')"
                                    class="bg-gray-700 hover:bg-gray-800 text-white px-4 py-2 rounded-lg text-sm font-semibold">
                                Pull from sale
                            </button>
                        <?php endif; ?>
                    </div>
                </form>
            <?php endforeach; ?>
            </div>
        <?php endif; ?>
    </div>
</div>
</div>
</body>
</html>