<?php
// admin/reconciliation.php — Platform Financial Reconciliation Dashboard
declare(strict_types=1);

require_once __DIR__ . '/auth_check.php';
require_once __DIR__ . '/includes/config.php';
require_once __DIR__ . '/../includes/revenue.php';
require_once __DIR__ . '/../includes/reconciliation.php';
require_once __DIR__ . '/../includes/admin_permissions.php';
requireAdminPermission('reports.view_financial');

$to   = $_GET['to']   ?? date('Y-m-d');
$from = $_GET['from'] ?? date('Y-m-d', strtotime('-29 days'));

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
    error_log('Reconciliation dashboard exception: ' . $e->getMessage());
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
        <div class="flex justify-between items-center mb-6">
            <div>
                <h1 class="text-2xl font-bold text-green-800 mb-1">Financial Reconciliation</h1>
                <p class="text-gray-600 text-sm">
                    Settled payments and order revenue tracking. Compare electronic volume directly with Pesapal settlements.
                </p>
            </div>
        </div>

        <?php if ($migrationMissing): ?>
            <div class="bg-yellow-100 border border-yellow-300 text-yellow-900 px-4 py-3 rounded-lg mb-6 text-sm">
                <strong>Payment ledger synchronization required:</strong>
                Cash and digital methods cannot be fully categorized until ledger tables are active.
            </div>
        <?php endif; ?>

        <!-- Filter and Export Bar -->
        <form method="get" class="flex flex-wrap items-end gap-3 mb-6 bg-white p-4 rounded-xl shadow-sm border border-gray-100">
            <div>
                <label class="block text-xs font-semibold text-gray-600 mb-1">From</label>
                <input type="date" name="from" value="<?= htmlspecialchars($from) ?>"
                       class="border border-gray-300 rounded-lg px-3 py-1.5 text-sm focus:ring-1 focus:ring-green-600">
            </div>
            <div>
                <label class="block text-xs font-semibold text-gray-600 mb-1">To</label>
                <input type="date" name="to" value="<?= htmlspecialchars($to) ?>"
                       class="border border-gray-300 rounded-lg px-3 py-1.5 text-sm focus:ring-1 focus:ring-green-600">
            </div>
            <button class="bg-green-700 hover:bg-green-800 text-white px-4 py-2 rounded-lg text-sm font-semibold transition">
                Filter Dates
            </button>
            <?php $q = http_build_query(['from' => $from, 'to' => $to]); ?>
            <div class="ml-auto flex items-center space-x-3 text-sm">
                <a href="export-reconciliation.php?type=daily&<?= $q ?>" class="text-green-700 font-medium hover:underline">
                    <i class="fas fa-file-csv mr-1"></i> Daily CSV
                </a>
                <span class="text-gray-300">|</span>
                <a href="export-reconciliation.php?type=rider_cash&<?= $q ?>" class="text-green-700 font-medium hover:underline">
                    <i class="fas fa-motorcycle mr-1"></i> Rider Cash CSV
                </a>
                <span class="text-gray-300">|</span>
                <a href="export-reconciliation.php?type=merchants&<?= $q ?>" class="text-green-700 font-medium hover:underline">
                    <i class="fas fa-store mr-1"></i> Merchant Splits CSV
                </a>
                <span class="text-gray-300">|</span>
                <a href="export-reconciliation.php?type=orders&<?= $q ?>" class="text-green-700 font-medium hover:underline">
                    <i class="fas fa-receipt mr-1"></i> Orders CSV
                </a>
            </div>
        </form>

        <?php if ($revenue !== null): ?>
        <div class="grid grid-cols-2 md:grid-cols-4 gap-4 mb-6">
            <?php
            $cards = [
                ['Gross Placed', $revenue['total']['gross']['value'], 'border-gray-300', 'Total volume ordered'],
                ['Settled Revenue', $revenue['total']['settled']['value'], 'border-green-600', 'Funds received & verified'],
                ['Cash Outstanding', $revenue['total']['outstanding']['value'], 'border-yellow-500', 'Pending rider handovers'],
                ['Failed / Cancelled', $revenue['total']['lost']['value'], 'border-red-500', 'Voided transactions'],
            ];
            foreach ($cards as [$label, $value, $border, $sub]): ?>
                <div class="bg-white p-4 rounded-xl shadow-sm border-l-4 <?= $border ?>">
                    <p class="text-gray-500 text-xs font-semibold uppercase tracking-wide"><?= $label ?></p>
                    <p class="text-xl font-bold text-gray-800 my-1"><?= $ugx($value) ?></p>
                    <p class="text-xs text-gray-400"><?= $sub ?></p>
                </div>
            <?php endforeach; ?>
        </div>

        <?php if ($revenue['by_method'] !== null): ?>
        <div class="bg-white rounded-xl shadow-sm p-5 mb-8 border border-gray-100">
            <h2 class="font-bold text-gray-800 mb-3">Settled Volume by Payment Channel</h2>
            <div class="grid grid-cols-2 md:grid-cols-4 gap-4 text-sm">
                <?php foreach ([
                    'cash' => 'Cash on Delivery', 'mobile_money' => 'Mobile Money (MTN / Airtel)',
                    'card' => 'Debit / Credit Card', 'unknown' => 'Unattributed',
                ] as $k => $label): ?>
                    <div class="border-r last:border-0 border-gray-100 pr-2">
                        <p class="text-gray-500 text-xs"><?= $label ?></p>
                        <p class="font-bold text-lg text-gray-800 mt-1"><?= $ugx($revenue['by_method'][$k]['value']) ?></p>
                        <p class="text-xs text-gray-400"><?= number_format($revenue['by_method'][$k]['count']) ?> orders</p>
                    </div>
                <?php endforeach; ?>
            </div>
        </div>
        <?php endif; ?>
        <?php endif; ?>

        <?php if ($daily): ?>
        <div class="bg-white rounded-xl shadow-sm overflow-hidden mb-8 border border-gray-100">
            <div class="px-5 py-3 border-b bg-gray-50 flex justify-between items-center">
                <h2 class="font-bold text-gray-800">Daily Payment Breakdown</h2>
                <span class="text-xs text-gray-500">Cross-reference mobile money and card totals against Pesapal payouts</span>
            </div>
            <div class="overflow-x-auto">
            <table class="min-w-full divide-y divide-gray-200 text-sm">
                <thead class="bg-gray-50 text-xs uppercase text-gray-500">
                    <tr>
                        <th class="px-4 py-2.5 text-left font-semibold">Date</th>
                        <th class="px-4 py-2.5 text-right font-semibold">Orders</th>
                        <th class="px-4 py-2.5 text-right font-semibold">Cash (UGX)</th>
                        <th class="px-4 py-2.5 text-right font-semibold">Mobile Money (UGX)</th>
                        <th class="px-4 py-2.5 text-right font-semibold">Card (UGX)</th>
                        <th class="px-4 py-2.5 text-right font-semibold">Unattributed</th>
                        <th class="px-4 py-2.5 text-right font-semibold">Total (UGX)</th>
                    </tr>
                </thead>
                <tbody class="divide-y divide-gray-100">
                <?php foreach ($daily as $d):
                    $total = (float)$d['cash'] + (float)$d['mobile_money'] + (float)$d['card'] + (float)$d['unknown_method']; ?>
                    <tr class="hover:bg-gray-50 transition">
                        <td class="px-4 py-2 font-medium"><?= htmlspecialchars($d['day']) ?></td>
                        <td class="px-4 py-2 text-right"><?= number_format($d['orders_n']) ?></td>
                        <td class="px-4 py-2 text-right"><?= number_format($d['cash']) ?></td>
                        <td class="px-4 py-2 text-right"><?= number_format($d['mobile_money']) ?></td>
                        <td class="px-4 py-2 text-right"><?= number_format($d['card']) ?></td>
                        <td class="px-4 py-2 text-right <?= $d['unknown_method'] > 0 ? 'text-yellow-700 font-semibold' : 'text-gray-400' ?>">
                            <?= number_format($d['unknown_method']) ?>
                        </td>
                        <td class="px-4 py-2 text-right font-bold text-gray-900"><?= number_format($total) ?></td>
                    </tr>
                <?php endforeach; ?>
                </tbody>
            </table>
            </div>
        </div>
        <?php endif; ?>

        <?php if ($riderCash): ?>
        <div class="bg-white rounded-xl shadow-sm overflow-hidden mb-8 border border-gray-100">
            <div class="px-5 py-3 border-b bg-gray-50">
                <h2 class="font-bold text-gray-800">Rider Doorstep Cash Collections</h2>
                <p class="text-xs text-gray-500">Cash received by dispatch riders pending balance handover.</p>
            </div>
            <table class="min-w-full divide-y divide-gray-200 text-sm">
                <thead class="bg-gray-50 text-xs uppercase text-gray-500">
                    <tr>
                        <th class="px-4 py-2 text-left font-semibold">Rider</th>
                        <th class="px-4 py-2 text-left font-semibold">Contact</th>
                        <th class="px-4 py-2 text-right font-semibold">Trips</th>
                        <th class="px-4 py-2 text-right font-semibold">Total Handover (UGX)</th>
                    </tr>
                </thead>
                <tbody class="divide-y divide-gray-100">
                <?php foreach ($riderCash as $r): ?>
                    <tr class="hover:bg-gray-50 transition">
                        <td class="px-4 py-2 font-medium"><?= htmlspecialchars($r['name']) ?></td>
                        <td class="px-4 py-2 text-gray-500"><?= htmlspecialchars($r['phone'] ?? '—') ?></td>
                        <td class="px-4 py-2 text-right text-gray-500"><?= number_format($r['deliveries']) ?> deliveries</td>
                        <td class="px-4 py-2 text-right font-bold text-gray-800"><?= $ugx($r['collected']) ?></td>
                    </tr>
                <?php endforeach; ?>
                </tbody>
            </table>
        </div>
        <?php endif; ?>

        <!-- Audit Exception Tables -->
        <div class="grid grid-cols-1 lg:grid-cols-2 gap-6">
            <div class="bg-white rounded-xl shadow-sm overflow-hidden border border-red-100">
                <div class="px-5 py-3 border-b bg-red-50">
                    <h2 class="font-bold text-red-800">Delivered Orders Awaiting Payment</h2>
                    <p class="text-xs text-red-700">Orders marked delivered without completed payment records.</p>
                </div>
                <?php if (!$exceptions['delivered_unpaid']): ?>
                    <p class="px-5 py-4 text-sm text-gray-500">All delivered orders are fully reconciled.</p>
                <?php else: ?>
                <table class="min-w-full text-sm divide-y divide-gray-100">
                    <tbody class="divide-y divide-gray-100">
                    <?php foreach ($exceptions['delivered_unpaid'] as $x): ?>
                        <tr>
                            <td class="px-4 py-2 font-medium">#<?= (int)$x['id'] ?></td>
                            <td class="px-4 py-2 text-gray-500"><?= htmlspecialchars($x['payment_status']) ?></td>
                            <td class="px-4 py-2 text-right font-bold text-red-600"><?= number_format($x['amount']) ?> UGX</td>
                        </tr>
                    <?php endforeach; ?>
                    </tbody>
                </table>
                <?php endif; ?>
            </div>

            <div class="bg-white rounded-xl shadow-sm overflow-hidden border border-yellow-100">
                <div class="px-5 py-3 border-b bg-yellow-50">
                    <h2 class="font-bold text-yellow-900">Unattributed Paid Transactions</h2>
                    <p class="text-xs text-yellow-800">Paid orders missing payment method assignment.</p>
                </div>
                <?php if (!$exceptions['paid_unattributed']): ?>
                    <p class="px-5 py-4 text-sm text-gray-500">All settled orders have designated payment channels.</p>
                <?php else: ?>
                <table class="min-w-full text-sm divide-y divide-gray-100">
                    <tbody class="divide-y divide-gray-100">
                    <?php foreach ($exceptions['paid_unattributed'] as $x): ?>
                        <tr>
                            <td class="px-4 py-2 font-medium">#<?= (int)$x['id'] ?></td>
                            <td class="px-4 py-2 text-gray-500"><?= htmlspecialchars((string)$x['at']) ?></td>
                            <td class="px-4 py-2 text-right font-bold text-yellow-800"><?= number_format($x['amount']) ?> UGX</td>
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
