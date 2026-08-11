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

// Surplus listings awaiting review. This queue is time-sensitive in a way the
// others are not: a listing sits against an expiry date, so one left unreviewed
// long enough is worthless by the time anyone looks.
$navPendingSurplus = 0;
try {
    if (isset($dbh)) {
        $navPendingSurplus = (int)$dbh->query(
            "SELECT COUNT(*) FROM surplus_listings WHERE status = 'pending'"
        )->fetchColumn();
    }
} catch (Throwable $e) {
    error_log('admin nav: pending surplus count failed: ' . $e->getMessage());
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

$navItems = [
    ['dashboard.php',        'Dashboard',          'fa-chart-pie'],
    ['products.php',         'Products',           'fa-box'],
    ['orders.php',           'Orders',             'fa-shopping-cart'],
    ['users.php',            'Users',              'fa-users'],
    ['riders.php',           'Riders',             'fa-motorcycle'],
    ['rider-payouts.php',    'Rider Payouts',      'fa-money-bill-wave'],
    ['role-requests.php',    'Role Requests',      'fa-user-check'],
    ['admin-dashboard.php',  'Vendor Verification','fa-store'],
    ['vendor-catalogue.php', 'Vendor Products',    'fa-seedling'],
    ['surplus-listings.php', 'Surplus Listings',   'fa-tags'],
    ['vendor-payouts.php',   'Vendor Payouts',     'fa-wallet'],
];
?>
<div class="w-64 bg-green-800 text-white flex flex-col">
    <div class="p-6 text-xl font-bold border-b border-green-700">AfamFresh</div>
    <nav class="flex-1 p-4 space-y-2">
        <?php foreach ($navItems as [$href, $label, $icon]): ?>
            <a href="<?= $href ?>"
               class="flex items-center justify-between py-2 px-4 rounded <?= $navCurrent === $href ? 'bg-green-700' : 'hover:bg-green-700' ?>">
                <span><i class="fas <?= $icon ?> mr-2"></i> <?= $label ?></span>
                <?php if ($href === 'role-requests.php' && $navPending > 0): ?>
                    <span class="bg-yellow-400 text-green-900 text-xs font-bold px-2 py-0.5 rounded-full"><?= $navPending ?></span>
                <?php endif; ?>
                <?php if ($href === 'vendor-catalogue.php' && $navPendingProducts > 0): ?>
                    <span class="bg-yellow-400 text-green-900 text-xs font-bold px-2 py-0.5 rounded-full"><?= $navPendingProducts ?></span>
                <?php endif; ?>
                <?php if ($href === 'surplus-listings.php' && $navPendingSurplus > 0): ?>
                    <span class="bg-yellow-400 text-green-900 text-xs font-bold px-2 py-0.5 rounded-full"><?= $navPendingSurplus ?></span>
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
