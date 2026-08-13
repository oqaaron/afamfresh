<?php
// =============================================================
// admin/reconciliation.php
//
// Where the money came from, and what does not add up.
//
// Before this page the admin panel had one money figure — `SUM(total_amount)
// FROM orders` with no WHERE clause — and no way to tell cash from electronic,
// because nothing recorded how an order was paid. There was no report, no
// export and no reconciliation of any kind anywhere in admin/.
//
// The daily table is the artefact: hold it next to the Pesapal settlement
// statement and the electronic column should tie line by line. Cash will not
// appear there at all, which is exactly why the two are separated.
// =============================================================

require_once __DIR__ . '/auth_check.php';
requireAdminLoginWeb();
require_once __DIR__ . '/includes/config.php';
require_once __DIR__ . '/../includes/revenue.php';
require_once __DIR__ . '/../includes/reconciliation.php';

// Default to the last 30 days rather than all time: an all-time default makes
// the page slow and answers a question nobody actually asks.
$to   = $_GET['to']   ?? date('Y-m-d');
$from = $_GET['from'] ?? date('Y-m-d', strtotime('-29 days'));

// Validated, not trusted — these reach a date comparison in every query below.
$valid = fn($d) => (bool)preg_match('/^\d{4}-\d{2}-\d{2}$/', (string)$d);
if (!$valid($from)) $from = date('Y-m-d', strtotime('-29 days'));
if (!$valid($to))   $to   = date('Y-m-d');

$migrationMissing = false;
try {
    $revenue    = revenueSummary($dbh, $from, $to);
    $daily      = reconciliationDaily($dbh, $from, $to);
    $riderCash  = reconciliationRiderCash($dbh, $from, $to);
    $exceptions = reconciliationExceptions($dbh, $from, $to);
    $migrationMissing = ($revenue['by_method'] === null);
} catch (PDOException $e) {
    // Almost certainly the 2026-08-13 migration not having been run. Say so
    // plainly rather than showing an empty page that looks like no trade.
    error_log('reconciliation: ' . $e->getMessage());
    $migrationMissing = true;
    $revenue = $daily = $riderCash = null;
    $exceptions = ['delivered_unpaid' => [], 'paid_unattributed' => []];
}

$ugx = fn($v) => 'UGX ' . number_format((float)$v);
?>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Reconciliation — AfamFresh Admin</title>
    <script src="https://cdn.tailwindcss.com"></script>
    <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.5.0/css/all.min.css">
</head>
<body class="bg-gray-50 font-sans antialiased">
<div class="flex min-h-screen">
<?php include __DIR__ . '/includes/nav.php'; ?>
<div class="flex-1 overflow-auto">
    <div class="max-w-7xl mx-auto px-6 py-8">
        <h1 class="text-2xl font-bold text-green-800 mb-1">Reconciliation</h1>
        <p class="text-gray-600 mb-6">
            Settled money only — orders whose payment actually completed.
            Everything else is in the exceptions at the bottom.
        </p>

        <?php if ($migrationMissing): ?>
            <div class="bg-yellow-100 border border-yellow-300 text-yellow-900 px-4 py-3 rounded mb-6">
                <strong>The payment-method migration has not been run.</strong>
                Cash and electronic cannot be separated until it is —
                <code>migrations/2026-08-13-payment-method-and-ledger.sql</code>.
            </div>
        <?php endif; ?>

        <form method="get" class="flex flex-wrap items-end gap-3 mb-6">
            <div>
                <label class="block text-xs text-gray-500 mb-1">From</label>
                <input type="date" name="from" value="<?= htmlspecialchars($from) ?>"
                       class="border border-gray-300 rounded-lg px-3 py-2 text-sm">
            </div>
            <div>
                <label class="block text-xs text-gray-500 mb-1">To</label>
                <input type="date" name="to" value="<?= htmlspecialchars($to) ?>"
                       class="border border-gray-300 rounded-lg px-3 py-2 text-sm">
            </div>
            <button class="bg-green-700 hover:bg-green-800 text-white px-4 py-2 rounded-lg text-sm font-semibold">
                Apply
            </button>
            <?php $q = http_build_query(['from' => $from, 'to' => $to]); ?>
            <a href="export-reconciliation.php?type=daily&<?= $q ?>"
               class="ml-auto text-sm text-green-700 hover:underline">
                <i class="fas fa-download mr-1"></i> Daily CSV
            </a>
            <a href="export-reconciliation.php?type=rider_cash&<?= $q ?>"
               class="text-sm text-green-700 hover:underline">Rider cash CSV</a>
            <a href="export-reconciliation.php?type=orders&<?= $q ?>"
               class="text-sm text-green-700 hover:underline">All settled orders CSV</a>
        </form>

        <?php if ($revenue !== null): ?>
        <div class="grid grid-cols-2 md:grid-cols-4 gap-4 mb-6">
            <?php
            $cards = [
                ['Gross placed', $revenue['total']['gross']['value'], 'border-gray-300', 'ordered, paid or not'],
                ['Settled', $revenue['total']['settled']['value'], 'border-green-600', 'money received'],
                ['Cash outstanding', $revenue['total']['outstanding']['value'], 'border-yellow-500', 'not yet collected'],
                ['Failed or cancelled', $revenue['total']['lost']['value'], 'border-red-500', 'never completed'],
            ];
            foreach ($cards as [$label, $value, $border, $sub]): ?>
                <div class="bg-white p-4 rounded-xl shadow border-l-4 <?= $border ?>">
                    <p class="text-gray-500 text-xs uppercase tracking-wide"><?= $label ?></p>
                    <p class="text-xl font-bold text-gray-800"><?= $ugx($value) ?></p>
                    <p class="text-xs text-gray-400"><?= $sub ?></p>
                </div>
            <?php endforeach; ?>
        </div>

        <?php if ($revenue['by_method'] !== null): ?>
        <div class="bg-white rounded-xl shadow p-5 mb-8">
            <h2 class="font-semibold text-gray-800 mb-3">Settled, by how it was paid</h2>
            <div class="grid grid-cols-2 md:grid-cols-4 gap-4 text-sm">
                <?php foreach ([
                    'cash' => 'Cash', 'mobile_money' => 'Mobile money',
                    'card' => 'Card', 'unknown' => 'Unattributed',
                ] as $k => $label): ?>
                    <div>
                        <p class="text-gray-500"><?= $label ?></p>
                        <p class="font-semibold text-lg"><?= $ugx($revenue['by_method'][$k]['value']) ?></p>
                        <p class="text-xs text-gray-400"><?= number_format($revenue['by_method'][$k]['count']) ?> orders</p>
                    </div>
                <?php endforeach; ?>
            </div>
        </div>
        <?php endif; ?>
        <?php endif; ?>

        <?php if ($daily): ?>
        <div class="bg-white rounded-xl shadow overflow-hidden mb-8">
            <div class="px-5 py-3 border-b">
                <h2 class="font-semibold text-gray-800">Day by day</h2>
                <p class="text-xs text-gray-500">
                    The electronic columns should tie to the Pesapal settlement statement.
                    Cash will not appear there.
                </p>
            </div>
            <div class="overflow-x-auto">
            <table class="min-w-full divide-y divide-gray-200 text-sm">
                <thead class="bg-gray-50 text-xs uppercase text-gray-500">
                    <tr>
                        <th class="px-4 py-2 text-left">Date</th>
                        <th class="px-4 py-2 text-right">Orders</th>
                        <th class="px-4 py-2 text-right">Cash</th>
                        <th class="px-4 py-2 text-right">Mobile money</th>
                        <th class="px-4 py-2 text-right">Card</th>
                        <th class="px-4 py-2 text-right">Unattributed</th>
                        <th class="px-4 py-2 text-right">Total</th>
                    </tr>
                </thead>
                <tbody class="divide-y divide-gray-100">
                <?php foreach ($daily as $d):
                    $total = $d['cash'] + $d['mobile_money'] + $d['card'] + $d['unknown_method']; ?>
                    <tr>
                        <td class="px-4 py-2"><?= htmlspecialchars($d['day']) ?></td>
                        <td class="px-4 py-2 text-right"><?= number_format($d['orders_n']) ?></td>
                        <td class="px-4 py-2 text-right"><?= number_format($d['cash']) ?></td>
                        <td class="px-4 py-2 text-right"><?= number_format($d['mobile_money']) ?></td>
                        <td class="px-4 py-2 text-right"><?= number_format($d['card']) ?></td>
                        <td class="px-4 py-2 text-right <?= $d['unknown_method'] > 0 ? 'text-yellow-700' : 'text-gray-400' ?>">
                            <?= number_format($d['unknown_method']) ?>
                        </td>
                        <td class="px-4 py-2 text-right font-semibold"><?= number_format($total) ?></td>
                    </tr>
                <?php endforeach; ?>
                </tbody>
            </table>
            </div>
        </div>
        <?php endif; ?>

        <?php if ($riderCash): ?>
        <div class="bg-white rounded-xl shadow overflow-hidden mb-8">
            <div class="px-5 py-3 border-b">
                <h2 class="font-semibold text-gray-800">Cash collected, by rider</h2>
                <p class="text-xs text-gray-500">What each rider confirmed taking at the door.</p>
            </div>
            <table class="min-w-full divide-y divide-gray-200 text-sm">
                <tbody class="divide-y divide-gray-100">
                <?php foreach ($riderCash as $r): ?>
                    <tr>
                        <td class="px-4 py-2"><?= htmlspecialchars($r['name']) ?></td>
                        <td class="px-4 py-2 text-gray-500"><?= htmlspecialchars($r['phone'] ?? '') ?></td>
                        <td class="px-4 py-2 text-right text-gray-500"><?= number_format($r['deliveries']) ?> deliveries</td>
                        <td class="px-4 py-2 text-right font-semibold"><?= $ugx($r['collected']) ?></td>
                    </tr>
                <?php endforeach; ?>
                </tbody>
            </table>
        </div>
        <?php endif; ?>

        <div class="grid grid-cols-1 lg:grid-cols-2 gap-6">
            <div class="bg-white rounded-xl shadow overflow-hidden">
                <div class="px-5 py-3 border-b bg-red-50">
                    <h2 class="font-semibold text-red-800">Delivered but not paid</h2>
                    <p class="text-xs text-red-700">Goods handed over with no money recorded.</p>
                </div>
                <?php if (!$exceptions['delivered_unpaid']): ?>
                    <p class="px-5 py-4 text-sm text-gray-500">Nothing — every delivered order is paid.</p>
                <?php else: ?>
                <table class="min-w-full text-sm divide-y divide-gray-100">
                    <tbody class="divide-y divide-gray-100">
                    <?php foreach ($exceptions['delivered_unpaid'] as $x): ?>
                        <tr>
                            <td class="px-4 py-2">
                                <span class="text-gray-400"><?= $x['source'] === 'surplus' ? 'Surplus' : 'Shop' ?></span>
                                #<?= (int)$x['id'] ?>
                            </td>
                            <td class="px-4 py-2 text-gray-500"><?= htmlspecialchars($x['payment_status']) ?></td>
                            <td class="px-4 py-2 text-right font-semibold"><?= number_format($x['amount']) ?></td>
                        </tr>
                    <?php endforeach; ?>
                    </tbody>
                </table>
                <?php endif; ?>
            </div>

            <div class="bg-white rounded-xl shadow overflow-hidden">
                <div class="px-5 py-3 border-b bg-yellow-50">
                    <h2 class="font-semibold text-yellow-900">Paid but unattributed</h2>
                    <p class="text-xs text-yellow-800">
                        Money received that cannot be assigned to cash or electronic.
                        Mostly pre-migration; a new one means a payment path is not
                        recording its method.
                    </p>
                </div>
                <?php if (!$exceptions['paid_unattributed']): ?>
                    <p class="px-5 py-4 text-sm text-gray-500">Nothing — every settled order has a method.</p>
                <?php else: ?>
                <table class="min-w-full text-sm divide-y divide-gray-100">
                    <tbody class="divide-y divide-gray-100">
                    <?php foreach ($exceptions['paid_unattributed'] as $x): ?>
                        <tr>
                            <td class="px-4 py-2">
                                <span class="text-gray-400"><?= $x['source'] === 'surplus' ? 'Surplus' : 'Shop' ?></span>
                                #<?= (int)$x['id'] ?>
                            </td>
                            <td class="px-4 py-2 text-gray-500"><?= htmlspecialchars((string)$x['at']) ?></td>
                            <td class="px-4 py-2 text-right font-semibold"><?= number_format($x['amount']) ?></td>
                        </tr>
                    <?php endforeach; ?>
                    </tbody>
                </table>
                <?php endif; ?>
            </div>
        </div>
    </div>
</div>
</div>
</body>
</html>