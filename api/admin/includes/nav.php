<?php
// =============================================================
// admin/includes/nav.php — the admin sidebar.
//
// One copy, included by every admin page. It used to be pasted
// into each of them, which is how role-requests.php and
// rider-payouts.php ended up reachable only by typing the URL:
// both were written, neither was ever added to the nine separate
// copies of the menu.
//
// Not to be confused with leftbar.php, which was the menu for an
// older admin panel — nothing includes it and every link in it
// points at a file that no longer exists.
//
// Expects $dbh from config.php. The pending badge is the reason:
// a request queue no one can see is a request queue no one works.
// =============================================================

$navCurrent = basename($_SERVER['PHP_SELF']);

// Wrapped because the nav must render even if this query fails —
// a broken badge should not take down every admin page with it.
$navPending = 0;
try {
    if (isset($dbh)) {
        $navPending = (int)$dbh->query(
            "SELECT COUNT(*) FROM role_requests WHERE status = 'pending'"
        )->fetchColumn();
    }
} catch (Throwable $e) {
    error_log('admin nav: pending count failed: ' . $e->getMessage());
}

// Vendor-created products waiting on approval. Same reasoning as the role
// request badge: a queue nobody can see is a queue nobody works.
$navPendingProducts = 0;
try {
    if (isset($dbh)) {
        $navPendingProducts = (int)$dbh->query(
            "SELECT COUNT(*) FROM items WHERE vendor_id IS NOT NULL AND status = 'pending'"
        )->fetchColumn();
    }
} catch (Throwable $e) {
    error_log('admin nav: pending product count failed: ' . $e->getMessage());
}

// Bulk listings awaiting review. This queue is time-sensitive in a way the
// others are not: a listing sits against an expiry date, so one left unreviewed
// long enough is worthless by the time anyone looks.
$navPendingBulk = 0;
try {
    if (isset($dbh)) {
        $navPendingBulk = (int)$dbh->query(
            "SELECT COUNT(*) FROM Bulk_listings WHERE status = 'pending'"
        )->fetchColumn();
    }
} catch (Throwable $e) {
    error_log('admin nav: pending Bulk count failed: ' . $e->getMessage());
}

// Withdrawal requests waiting on a decision. Money someone is waiting for, so
// it earns a badge more than any other queue here.
$navPendingPayouts = 0;
try {
    if (isset($dbh)) {
        $navPendingPayouts = (int)$dbh->query(
            "SELECT COUNT(*) FROM vendor_payout_requests WHERE status = 'pending'"
        )->fetchColumn();
    }
} catch (Throwable $e) {
    error_log('admin nav: pending payout count failed: ' . $e->getMessage());
}

// Paid Bulk orders with nobody sent to collect them. The most urgent queue
// on this menu: the goods are perishable, the customer has already paid, and
// nothing happens until someone here assigns a rider.
$navNeedsRider = 0;
try {
    if (isset($dbh)) {
        $navNeedsRider = (int)$dbh->query(
            "SELECT COUNT(*)
               FROM Bulk_orders so
               JOIN Bulk_listings sl ON sl.id = so.listing_id
               LEFT JOIN rider_assignments ra ON ra.order_id = so.id AND ra.source = 'Bulk'
              WHERE so.payment_status IN ('paid','pending_cash')
                AND sl.pickup_only = 0
                AND so.status NOT IN ('delivered','cancelled','refunded')
                AND ra.id IS NULL"
        )->fetchColumn();
    }
} catch (Throwable $e) {
    error_log('admin nav: needs-rider count failed: ' . $e->getMessage());
}

// Vendor-requested cancellations awaiting a decision. Folded into the same
// Bulk Orders badge as needs-rider: both represent an action someone has to
// take on that page, and a request sitting unreviewed is money in limbo.
$navPendingCancellations = 0;
try {
    if (isset($dbh)) {
        $navPendingCancellations = (int)$dbh->query(
            "SELECT COUNT(*) FROM Bulk_orders WHERE status = 'cancellation_requested'"
        )->fetchColumn();
    }
} catch (Throwable $e) {
    error_log('admin nav: pending cancellation count failed: ' . $e->getMessage());
}

// Each row's 4th element is the permission required to see it — see
// includes/admin_permissions.php. A row with no matching permission for the
// current session's role is filtered out below, not just visually hidden:
// the pages themselves separately enforce the same permission, so hiding
// the link here is a convenience, not the actual access control.
require_once __DIR__ . '/../../includes/admin_permissions.php';

$navItemsAll = [
    ['dashboard.php',        'Dashboard',          'fa-chart-pie',        'reports.view'],
    ['configuration.php',    'Configuration',      'fa-cog',              'configuration.manage'],
    ['products.php',         'Products',           'fa-box',              'products.manage_operational'],
    ['orders.php',           'Orders',             'fa-shopping-cart',    'orders.manage'],
    ['users.php',            'Users',              'fa-users',            'users.manage'],
    ['riders.php',           'Riders',             'fa-motorcycle',       'users.manage'],
    ['rider-payouts.php',    'Rider Payouts',      'fa-money-bill-wave',  'payouts.manage'],
    ['role-requests.php',    'Role Requests',      'fa-user-check',       'users.manage'],
    ['admin-dashboard.php',  'Vendor Verification','fa-store',            'vendors.manage'],
    ['vendor-catalogue.php', 'Vendor Products',    'fa-seedling',         'vendors.manage'],
    ['Bulk-listings.php',    'Bulk Listings',      'fa-tags',             'Bulk.manage_listings'],
    ['Bulk-orders.php',      'Bulk Orders',        'fa-truck-fast',       'Bulk.dispatch'],
    ['vendor-payouts.php',   'Vendor Payouts',     'fa-wallet',           'payouts.manage'],
    ['reconciliation.php',   'Reconciliation',     'fa-scale-balanced',   'reports.view'],
    ['routing-check.php',    'Routing check',      'fa-route',            'reports.view'],
    ['manage-admins.php',    'Manage Admins',      'fa-user-shield',      'admins.manage'],
    ['audit-log.php',        'Audit Log',          'fa-clipboard-list',   'admins.manage'],
];

// products.php is listed under products.manage_operational (browsing/editing
// non-price fields is fine for a dispatcher) even though add/delete are
// products.manage_pricing-gated pages reached from within it — those two
// links simply won't do anything useful for a dispatcher, since
// add-product.php/delete-product.php each enforce their own permission
// independently. Bulk-orders.php is listed under Bulk.dispatch for the same
// reason: reaching the page is fine, its refund-adjacent actions are gated
// per-action inside the page itself.
$navItems = array_values(array_filter(
    $navItemsAll,
    fn($item) => adminHasPermission($item[3])
));
?>
<div class="w-64 bg-green-800 text-white flex flex-col">
    <div class="p-6 text-xl font-bold border-b border-green-700">AfamFresh</div>
    <nav class="flex-1 p-4 space-y-2">
        <?php foreach ($navItems as [$href, $label, $icon, $permission]): ?>
            <a href="<?= $href ?>"
               class="flex items-center justify-between py-2 px-4 rounded <?= $navCurrent === $href ? 'bg-green-700' : 'hover:bg-green-700' ?>">
                <span><i class="fas <?= $icon ?> mr-2"></i> <?= $label ?></span>
                <?php if ($href === 'role-requests.php' && $navPending > 0): ?>
                    <span class="bg-yellow-400 text-green-900 text-xs font-bold px-2 py-0.5 rounded-full"><?= $navPending ?></span>
                <?php endif; ?>
                <?php if ($href === 'vendor-catalogue.php' && $navPendingProducts > 0): ?>
                    <span class="bg-yellow-400 text-green-900 text-xs font-bold px-2 py-0.5 rounded-full"><?= $navPendingProducts ?></span>
                <?php endif; ?>
                <?php if ($href === 'Bulk-listings.php' && $navPendingBulk > 0): ?>
                    <span class="bg-yellow-400 text-green-900 text-xs font-bold px-2 py-0.5 rounded-full"><?= $navPendingBulk ?></span>
                <?php endif; ?>
                <?php if ($href === 'Bulk-orders.php' && ($navNeedsRider + $navPendingCancellations) > 0): ?>
                    <span class="bg-red-400 text-white text-xs font-bold px-2 py-0.5 rounded-full"><?= $navNeedsRider + $navPendingCancellations ?></span>
                <?php endif; ?>
                <?php if ($href === 'vendor-payouts.php' && $navPendingPayouts > 0): ?>
                    <span class="bg-yellow-400 text-green-900 text-xs font-bold px-2 py-0.5 rounded-full"><?= $navPendingPayouts ?></span>
                <?php endif; ?>
            </a>
        <?php endforeach; ?>
        <a href="logout.php" class="block py-2 px-4 rounded hover:bg-red-600 mt-8">
            <i class="fas fa-sign-out-alt mr-2"></i> Logout
        </a>
    </nav>
</div>
