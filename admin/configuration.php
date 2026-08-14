<?php
// =============================================================
// admin/configuration.php
//
// Business-tunable numbers that, until this page existed, could only be
// changed by editing code and deploying: delivery pricing
// (delivery_pricing table), surplus delivery settings
// (surplus_delivery_settings table), and loyalty settings
// (loyalty_settings table). All three tables already existed — nothing in
// admin/ ever read or wrote to the first two.
//
// Three independent forms, one per section, each POSTing to itself with its
// own `section` field — a mistake in one save must not block or clobber a
// valid save in another, since an admin adjusting the loyalty rate has no
// reason to also be re-submitting delivery pricing at the same moment.
// =============================================================

session_start();
require_once 'includes/config.php';

if (!isset($_SESSION['admin_logged_in']) || $_SESSION['admin_logged_in'] !== true) {
    header('Location: login.php');
    exit;
}

$error = '';
$success = '';

/** Numeric, non-negative, and — for percent-shaped fields — capped at 100. */
function validNumber($value, bool $isPercent = false): bool {
    if (!is_numeric($value)) return false;
    $f = (float)$value;
    if ($f < 0) return false;
    if ($isPercent && $f > 100) return false;
    return true;
}

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $section = $_POST['section'] ?? '';

    if ($section === 'delivery_pricing') {
        $fields = [
            'service_fee' => false,
            'insurance_percent' => true,
            'free_delivery_threshold' => false,
            'free_delivery_distance_threshold' => false,
            'medium_order_threshold' => false,
            'medium_order_rate' => false,
            'low_order_rate' => false,
            'profit_percent' => true,
        ];
        $values = [];
        $ok = true;
        foreach ($fields as $name => $isPercent) {
            $v = $_POST[$name] ?? '';
            if (!validNumber($v, $isPercent)) {
                $ok = false;
                break;
            }
            $values[$name] = (float)$v;
        }
        $profitEnabled = isset($_POST['profit_percent_enabled']) ? 1 : 0;

        if (!$ok) {
            $error = 'Delivery pricing: please enter valid, non-negative numbers (percentages up to 100).';
        } else {
            try {
                $existing = $dbh->query("SELECT id FROM delivery_pricing ORDER BY id LIMIT 1")->fetchColumn();
                if ($existing) {
                    $dbh->prepare(
                        "UPDATE delivery_pricing SET
                            service_fee = ?, insurance_percent = ?, free_delivery_threshold = ?,
                            free_delivery_distance_threshold = ?, medium_order_threshold = ?,
                            medium_order_rate = ?, low_order_rate = ?,
                            profit_percent_enabled = ?, profit_percent = ?
                          WHERE id = ?"
                    )->execute([
                        $values['service_fee'], $values['insurance_percent'], $values['free_delivery_threshold'],
                        $values['free_delivery_distance_threshold'], $values['medium_order_threshold'],
                        $values['medium_order_rate'], $values['low_order_rate'],
                        $profitEnabled, $values['profit_percent'],
                        $existing,
                    ]);
                } else {
                    $dbh->prepare(
                        "INSERT INTO delivery_pricing
                            (service_fee, insurance_percent, free_delivery_threshold,
                             free_delivery_distance_threshold, medium_order_threshold,
                             medium_order_rate, low_order_rate, profit_percent_enabled, profit_percent)
                         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
                    )->execute([
                        $values['service_fee'], $values['insurance_percent'], $values['free_delivery_threshold'],
                        $values['free_delivery_distance_threshold'], $values['medium_order_threshold'],
                        $values['medium_order_rate'], $values['low_order_rate'],
                        $profitEnabled, $values['profit_percent'],
                    ]);
                }
                $success = 'Delivery pricing updated.';
            } catch (PDOException $e) {
                error_log('configuration.php delivery_pricing save failed: ' . $e->getMessage());
                $error = 'Could not save delivery pricing.';
            }
        }
    } elseif ($section === 'surplus_delivery') {
        $fields = ['base_fee', 'fee_per_kg', 'max_weight_kg', 'free_delivery_threshold'];
        $values = [];
        $ok = true;
        foreach ($fields as $name) {
            $v = $_POST[$name] ?? '';
            if (!validNumber($v)) {
                $ok = false;
                break;
            }
            $values[$name] = (float)$v;
        }

        if (!$ok) {
            $error = 'Surplus delivery settings: please enter valid, non-negative numbers.';
        } else {
            try {
                $existing = $dbh->query("SELECT id FROM surplus_delivery_settings ORDER BY id LIMIT 1")->fetchColumn();
                if ($existing) {
                    $dbh->prepare(
                        "UPDATE surplus_delivery_settings SET
                            base_fee = ?, fee_per_kg = ?, max_weight_kg = ?, free_delivery_threshold = ?
                          WHERE id = ?"
                    )->execute([
                        $values['base_fee'], $values['fee_per_kg'], $values['max_weight_kg'],
                        $values['free_delivery_threshold'], $existing,
                    ]);
                } else {
                    $dbh->prepare(
                        "INSERT INTO surplus_delivery_settings (base_fee, fee_per_kg, max_weight_kg, free_delivery_threshold)
                         VALUES (?, ?, ?, ?)"
                    )->execute([
                        $values['base_fee'], $values['fee_per_kg'], $values['max_weight_kg'],
                        $values['free_delivery_threshold'],
                    ]);
                }
                $success = 'Surplus delivery settings updated.';
            } catch (PDOException $e) {
                error_log('configuration.php surplus_delivery save failed: ' . $e->getMessage());
                $error = 'Could not save surplus delivery settings.';
            }
        }
    } elseif ($section === 'loyalty') {
        $earnRate = $_POST['earn_rate_ugx_per_point'] ?? '';
        $redeemValue = $_POST['redeem_value_ugx_per_point'] ?? '';
        $minPoints = $_POST['min_redeemable_points'] ?? '';
        $maxPercent = $_POST['max_redeem_percent'] ?? '';

        if (!validNumber($earnRate) || (float)$earnRate <= 0) {
            $error = 'Loyalty settings: the earn rate must be a positive number.';
        } elseif (!validNumber($redeemValue) || (float)$redeemValue <= 0) {
            $error = 'Loyalty settings: the redeem value must be a positive number.';
        } elseif (!ctype_digit((string)$minPoints)) {
            $error = 'Loyalty settings: minimum redeemable points must be a whole number.';
        } elseif (!validNumber($maxPercent, true)) {
            $error = 'Loyalty settings: the redemption cap must be a percentage between 0 and 100.';
        } else {
            try {
                $existing = $dbh->query("SELECT id FROM loyalty_settings ORDER BY id LIMIT 1")->fetchColumn();
                if ($existing) {
                    $dbh->prepare(
                        "UPDATE loyalty_settings SET
                            earn_rate_ugx_per_point = ?, redeem_value_ugx_per_point = ?,
                            min_redeemable_points = ?, max_redeem_percent = ?
                          WHERE id = ?"
                    )->execute([
                        (float)$earnRate, (float)$redeemValue, (int)$minPoints, (float)$maxPercent, $existing,
                    ]);
                } else {
                    $dbh->prepare(
                        "INSERT INTO loyalty_settings
                            (earn_rate_ugx_per_point, redeem_value_ugx_per_point, min_redeemable_points, max_redeem_percent)
                         VALUES (?, ?, ?, ?)"
                    )->execute([(float)$earnRate, (float)$redeemValue, (int)$minPoints, (float)$maxPercent]);
                }
                $success = 'Loyalty settings updated.';
            } catch (PDOException $e) {
                error_log('configuration.php loyalty save failed: ' . $e->getMessage());
                $error = 'Could not save loyalty settings.';
            }
        }
    }
}

$deliveryPricing = $dbh->query("SELECT * FROM delivery_pricing ORDER BY id LIMIT 1")->fetch(PDO::FETCH_ASSOC) ?: [];
$surplusSettings = $dbh->query("SELECT * FROM surplus_delivery_settings ORDER BY id LIMIT 1")->fetch(PDO::FETCH_ASSOC) ?: [];
$loyaltySettings = $dbh->query("SELECT * FROM loyalty_settings ORDER BY id LIMIT 1")->fetch(PDO::FETCH_ASSOC) ?: [];

function field($arr, $key, $default = '') {
    return htmlspecialchars((string)($arr[$key] ?? $default));
}
?>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>AfamFresh – Configuration</title>
    <script src="https://cdn.tailwindcss.com"></script>
    <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.5.0/css/all.min.css">
</head>
<body class="bg-gray-100">
<div class="flex h-screen">
    <?php include __DIR__ . "/includes/nav.php"; ?>

    <div class="flex-1 overflow-y-auto p-6">
        <div class="max-w-3xl">
            <h1 class="text-2xl font-bold text-green-800 mb-2">Configuration</h1>
            <p class="text-gray-500 text-sm mb-6">
                Business-tunable numbers. Changes here take effect on the very next order or quote — no deploy needed.
            </p>

            <?php if ($error): ?>
                <div class="bg-red-100 border border-red-400 text-red-700 px-4 py-3 rounded mb-4"><?= htmlspecialchars($error) ?></div>
            <?php endif; ?>
            <?php if ($success): ?>
                <div class="bg-green-100 border border-green-400 text-green-700 px-4 py-3 rounded mb-4"><?= htmlspecialchars($success) ?></div>
            <?php endif; ?>

            <!-- ===== Delivery pricing ===== -->
            <div class="bg-white p-8 rounded-xl shadow mb-6">
                <h2 class="text-lg font-bold text-gray-800 mb-1">Delivery pricing</h2>
                <p class="text-gray-400 text-xs mb-4">Shop-order delivery fee: service fee, insurance, distance rate tiers.</p>
                <form method="POST">
                    <input type="hidden" name="section" value="delivery_pricing">
                    <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
                        <div>
                            <label class="block text-gray-700 font-bold mb-2">Service fee (UGX)</label>
                            <input type="number" step="0.01" min="0" name="service_fee" value="<?= field($deliveryPricing, 'service_fee', '1000') ?>" class="w-full px-4 py-2 border rounded">
                        </div>
                        <div>
                            <label class="block text-gray-700 font-bold mb-2">Insurance (% of order)</label>
                            <input type="number" step="0.01" min="0" max="100" name="insurance_percent" value="<?= field($deliveryPricing, 'insurance_percent', '0.9') ?>" class="w-full px-4 py-2 border rounded">
                        </div>
                        <div>
                            <label class="block text-gray-700 font-bold mb-2">Free delivery threshold (UGX)</label>
                            <input type="number" step="0.01" min="0" name="free_delivery_threshold" value="<?= field($deliveryPricing, 'free_delivery_threshold', '100000') ?>" class="w-full px-4 py-2 border rounded">
                        </div>
                        <div>
                            <label class="block text-gray-700 font-bold mb-2">Free delivery distance limit (km)</label>
                            <input type="number" step="0.01" min="0" name="free_delivery_distance_threshold" value="<?= field($deliveryPricing, 'free_delivery_distance_threshold', '10') ?>" class="w-full px-4 py-2 border rounded">
                        </div>
                        <div>
                            <label class="block text-gray-700 font-bold mb-2">Medium-order threshold (UGX)</label>
                            <input type="number" step="0.01" min="0" name="medium_order_threshold" value="<?= field($deliveryPricing, 'medium_order_threshold', '50000') ?>" class="w-full px-4 py-2 border rounded">
                        </div>
                        <div>
                            <label class="block text-gray-700 font-bold mb-2">Medium/long-distance rate (UGX/km)</label>
                            <input type="number" step="0.01" min="0" name="medium_order_rate" value="<?= field($deliveryPricing, 'medium_order_rate', '375') ?>" class="w-full px-4 py-2 border rounded">
                        </div>
                        <div>
                            <label class="block text-gray-700 font-bold mb-2">Short-distance rate (UGX/km)</label>
                            <input type="number" step="0.01" min="0" name="low_order_rate" value="<?= field($deliveryPricing, 'low_order_rate', '700') ?>" class="w-full px-4 py-2 border rounded">
                        </div>
                        <div>
                            <label class="block text-gray-700 font-bold mb-2">Profit margin (%, small orders only)</label>
                            <input type="number" step="0.01" min="0" max="100" name="profit_percent" value="<?= field($deliveryPricing, 'profit_percent', '8') ?>" class="w-full px-4 py-2 border rounded">
                        </div>
                        <div class="flex items-center gap-2 md:col-span-2">
                            <input type="checkbox" id="profit_percent_enabled" name="profit_percent_enabled" value="1" <?= !empty($deliveryPricing['profit_percent_enabled']) ? 'checked' : '' ?>>
                            <label for="profit_percent_enabled" class="text-gray-700">Apply the profit margin to small orders</label>
                        </div>
                    </div>
                    <button type="submit" class="mt-4 bg-green-600 hover:bg-green-700 text-white px-6 py-2 rounded">Save delivery pricing</button>
                </form>
            </div>

            <!-- ===== Surplus delivery settings ===== -->
            <div class="bg-white p-8 rounded-xl shadow mb-6">
                <h2 class="text-lg font-bold text-gray-800 mb-1">Surplus delivery settings</h2>
                <p class="text-gray-400 text-xs mb-4">Weight-based delivery fee for bulk surplus orders.</p>
                <form method="POST">
                    <input type="hidden" name="section" value="surplus_delivery">
                    <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
                        <div>
                            <label class="block text-gray-700 font-bold mb-2">Base fee (UGX)</label>
                            <input type="number" step="0.01" min="0" name="base_fee" value="<?= field($surplusSettings, 'base_fee', '5000') ?>" class="w-full px-4 py-2 border rounded">
                        </div>
                        <div>
                            <label class="block text-gray-700 font-bold mb-2">Fee per kg (UGX)</label>
                            <input type="number" step="0.01" min="0" name="fee_per_kg" value="<?= field($surplusSettings, 'fee_per_kg', '500') ?>" class="w-full px-4 py-2 border rounded">
                        </div>
                        <div>
                            <label class="block text-gray-700 font-bold mb-2">Max weight per order (kg)</label>
                            <input type="number" step="0.01" min="0" name="max_weight_kg" value="<?= field($surplusSettings, 'max_weight_kg', '1000') ?>" class="w-full px-4 py-2 border rounded">
                        </div>
                        <div>
                            <label class="block text-gray-700 font-bold mb-2">Free delivery threshold (UGX)</label>
                            <input type="number" step="0.01" min="0" name="free_delivery_threshold" value="<?= field($surplusSettings, 'free_delivery_threshold', '500000') ?>" class="w-full px-4 py-2 border rounded">
                        </div>
                    </div>
                    <button type="submit" class="mt-4 bg-green-600 hover:bg-green-700 text-white px-6 py-2 rounded">Save surplus delivery settings</button>
                </form>
            </div>

            <!-- ===== Loyalty settings ===== -->
            <div class="bg-white p-8 rounded-xl shadow mb-6">
                <h2 class="text-lg font-bold text-gray-800 mb-1">Loyalty settings</h2>
                <p class="text-gray-400 text-xs mb-4">How fast customers earn points, and what those points are worth at checkout.</p>
                <form method="POST">
                    <input type="hidden" name="section" value="loyalty">
                    <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
                        <div>
                            <label class="block text-gray-700 font-bold mb-2">Earn rate (UGX of goods per 1 point)</label>
                            <input type="number" step="0.01" min="0.01" name="earn_rate_ugx_per_point" value="<?= field($loyaltySettings, 'earn_rate_ugx_per_point', '1000') ?>" class="w-full px-4 py-2 border rounded">
                            <p class="text-gray-400 text-xs mt-1">Lower = more generous. 1000 means 1 point per UGX 1,000 spent on goods.</p>
                        </div>
                        <div>
                            <label class="block text-gray-700 font-bold mb-2">Redeem value (UGX discount per point)</label>
                            <input type="number" step="0.01" min="0.01" name="redeem_value_ugx_per_point" value="<?= field($loyaltySettings, 'redeem_value_ugx_per_point', '50') ?>" class="w-full px-4 py-2 border rounded">
                        </div>
                        <div>
                            <label class="block text-gray-700 font-bold mb-2">Minimum redeemable points</label>
                            <input type="number" step="1" min="0" name="min_redeemable_points" value="<?= field($loyaltySettings, 'min_redeemable_points', '100') ?>" class="w-full px-4 py-2 border rounded">
                            <p class="text-gray-400 text-xs mt-1">A customer must redeem at least this many at once, or none.</p>
                        </div>
                        <div>
                            <label class="block text-gray-700 font-bold mb-2">Max redemption (% of order's goods value)</label>
                            <input type="number" step="0.01" min="0" max="100" name="max_redeem_percent" value="<?= field($loyaltySettings, 'max_redeem_percent', '30') ?>" class="w-full px-4 py-2 border rounded">
                            <p class="text-gray-400 text-xs mt-1">Caps how much of one order points may cover, so an order can never be fully zeroed out by points.</p>
                        </div>
                    </div>
                    <button type="submit" class="mt-4 bg-green-600 hover:bg-green-700 text-white px-6 py-2 rounded">Save loyalty settings</button>
                </form>
            </div>
        </div>
    </div>
</div>
</body>
</html>