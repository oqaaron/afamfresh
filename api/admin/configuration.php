<?php
// =============================================================
// admin/configuration.php
//
// Business-tunable numbers that, until this page existed, could only be
// changed by editing code and deploying: delivery pricing
// (delivery_pricing table), Bulk delivery settings
// (Bulk_delivery_settings table), and loyalty settings
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
require_once __DIR__ . '/../includes/csrf.php';
require_once __DIR__ . '/../includes/admin_permissions.php';
require_once __DIR__ . '/../includes/admin_audit.php';
requireAdminPermission('configuration.manage');

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
    verifyCsrf();
    $section = $_POST['section'] ?? '';

    if ($section === 'delivery_pricing') {
        $fields = [
            'service_fee' => false,
            'insurance_percent' => true,
            // Charged on BOTH shop and Bulk orders. It was referenced as
            // PROCESSING_FEE_PERCENT, a constant defined nowhere, so it was
            // permanently 1.8% and invisible here.
            'processing_percent' => true,
            'min_delivery_fee' => false,
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
                            service_fee = ?, insurance_percent = ?, processing_percent = ?,
                            min_delivery_fee = ?, free_delivery_threshold = ?,
                            free_delivery_distance_threshold = ?, medium_order_threshold = ?,
                            medium_order_rate = ?, low_order_rate = ?,
                            profit_percent_enabled = ?, profit_percent = ?
                          WHERE id = ?"
                    )->execute([
                        $values['service_fee'], $values['insurance_percent'],
                        $values['processing_percent'], $values['min_delivery_fee'],
                        $values['free_delivery_threshold'],
                        $values['free_delivery_distance_threshold'], $values['medium_order_threshold'],
                        $values['medium_order_rate'], $values['low_order_rate'],
                        $profitEnabled, $values['profit_percent'],
                        $existing,
                    ]);
                } else {
                    $dbh->prepare(
                        "INSERT INTO delivery_pricing
                            (service_fee, insurance_percent, processing_percent, min_delivery_fee,
                             free_delivery_threshold,
                             free_delivery_distance_threshold, medium_order_threshold,
                             medium_order_rate, low_order_rate, profit_percent_enabled, profit_percent)
                         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                    )->execute([
                        $values['service_fee'], $values['insurance_percent'],
                        $values['processing_percent'], $values['min_delivery_fee'],
                        $values['free_delivery_threshold'],
                        $values['free_delivery_distance_threshold'], $values['medium_order_threshold'],
                        $values['medium_order_rate'], $values['low_order_rate'],
                        $profitEnabled, $values['profit_percent'],
                    ]);
                }
                $success = 'Delivery pricing updated.';
                logAdminAction($dbh, 'configuration.updated', 'config', 'delivery_pricing', 'Delivery pricing updated');
            } catch (PDOException $e) {
                error_log('configuration.php delivery_pricing save failed: ' . $e->getMessage());
                $error = 'Could not save delivery pricing.';
            }
        }
    } elseif ($section === 'Bulk_delivery') {
        // min_order_value and min_weight_kg are order LIMITS rather than fee
        // inputs, but they live on the same row and are read by the same
        // loader (BulkDeliverySettings), so they are saved together.
        $fields = ['base_fee', 'base_included_km', 'fee_per_kg', 'rate_per_km', 'max_fee',
                   'max_weight_kg', 'free_delivery_threshold', 'min_order_value', 'min_weight_kg'];
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
            $error = 'Bulk delivery settings: please enter valid, non-negative numbers.';
        } else {
            try {
                $existing = $dbh->query("SELECT id FROM Bulk_delivery_settings ORDER BY id LIMIT 1")->fetchColumn();
                if ($existing) {
                    $dbh->prepare(
                        "UPDATE Bulk_delivery_settings SET
                            base_fee = ?, base_included_km = ?, fee_per_kg = ?,
                            rate_per_km = ?, max_fee = ?, max_weight_kg = ?,
                            free_delivery_threshold = ?, min_order_value = ?, min_weight_kg = ?
                          WHERE id = ?"
                    )->execute([
                        $values['base_fee'], $values['base_included_km'], $values['fee_per_kg'],
                        $values['rate_per_km'], $values['max_fee'], $values['max_weight_kg'],
                        $values['free_delivery_threshold'], $values['min_order_value'],
                        $values['min_weight_kg'], $existing,
                    ]);
                } else {
                    $dbh->prepare(
                        "INSERT INTO Bulk_delivery_settings
                            (base_fee, base_included_km, fee_per_kg, rate_per_km, max_fee,
                             max_weight_kg, free_delivery_threshold, min_order_value, min_weight_kg)
                         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
                    )->execute([
                        $values['base_fee'], $values['base_included_km'], $values['fee_per_kg'],
                        $values['rate_per_km'], $values['max_fee'], $values['max_weight_kg'],
                        $values['free_delivery_threshold'], $values['min_order_value'],
                        $values['min_weight_kg'],
                    ]);
                }
                $success = 'Bulk delivery settings updated.';
                logAdminAction($dbh, 'configuration.updated', 'config', 'Bulk_delivery', 'Bulk delivery settings updated');
            } catch (PDOException $e) {
                error_log('configuration.php Bulk_delivery save failed: ' . $e->getMessage());
                $error = 'Could not save Bulk delivery settings.';
            }
        }
    } elseif ($section === 'order_display') {
        // Cosmetic only: prepended when an order number is SHOWN (SMS, push
        // notifications, admin pages) — never touches orders.orderid /
        // Bulk_orders.id themselves, which stay plain integers everywhere
        // internally (foreign keys, rider_assignments, payment_events, ...).
        // See includes/order_number.php's formatOrderNumber().
        $prefix = trim((string)($_POST['order_number_prefix'] ?? ''));
        if (strlen($prefix) > 12) {
            $error = 'Order number prefix: keep it short — 12 characters or fewer.';
        } elseif ($prefix !== '' && !preg_match('/^[A-Za-z0-9\-]*$/', $prefix)) {
            $error = 'Order number prefix: letters, numbers and hyphens only.';
        } else {
            try {
                $dbh->prepare(
                    "INSERT INTO app_config (config_key, config_value) VALUES ('order_number_prefix', ?)
                     ON DUPLICATE KEY UPDATE config_value = VALUES(config_value)"
                )->execute([$prefix]);
                $success = 'Order number display updated.';
                logAdminAction($dbh, 'configuration.updated', 'config', 'order_number_prefix', "Prefix set to \"$prefix\"");
            } catch (PDOException $e) {
                error_log('configuration.php order_display save failed: ' . $e->getMessage());
                $error = 'Could not save the order number prefix.';
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
                logAdminAction($dbh, 'configuration.updated', 'config', 'loyalty', 'Loyalty settings updated');
            } catch (PDOException $e) {
                error_log('configuration.php loyalty save failed: ' . $e->getMessage());
                $error = 'Could not save loyalty settings.';
            }
        }
    }
}

$deliveryPricing = $dbh->query("SELECT * FROM delivery_pricing ORDER BY id LIMIT 1")->fetch(PDO::FETCH_ASSOC) ?: [];
$BulkSettings = $dbh->query("SELECT * FROM Bulk_delivery_settings ORDER BY id LIMIT 1")->fetch(PDO::FETCH_ASSOC) ?: [];
$loyaltySettings = $dbh->query("SELECT * FROM loyalty_settings ORDER BY id LIMIT 1")->fetch(PDO::FETCH_ASSOC) ?: [];
$orderNumberPrefixStmt = $dbh->prepare("SELECT config_value FROM app_config WHERE config_key = 'order_number_prefix'");
$orderNumberPrefixStmt->execute();
$orderNumberPrefix = (string)($orderNumberPrefixStmt->fetchColumn() ?: '');

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

            <!-- ===== Order number display ===== -->
            <div class="bg-white p-8 rounded-xl shadow mb-6">
                <h2 class="text-lg font-bold text-gray-800 mb-1">Order number display</h2>
                <p class="text-gray-400 text-xs mb-4">
                    Cosmetic prefix shown on order numbers (SMS, push notifications, admin
                    pages). Always includes the order's placement date, plus a count that
                    starts fresh at 1 each day — e.g. prefix "AF" turns the 1st shop order
                    placed on 15 Aug 2026 into AF-150826-001. The real internal order id
                    (used for payments, rider assignment, tracking — everything
                    operational) is completely unaffected; this is a display label only.
                </p>
                <form method="POST">
                    <?= csrfField() ?>
                    <input type="hidden" name="section" value="order_display">
                    <div class="max-w-xs">
                        <label class="block text-gray-700 font-bold mb-2">Prefix (optional)</label>
                        <input type="text" maxlength="12" name="order_number_prefix"
                               value="<?= htmlspecialchars($orderNumberPrefix) ?>"
                               placeholder="e.g. AF" class="w-full px-4 py-2 border rounded">
                        <p class="text-gray-400 text-xs mt-1">Letters, numbers and hyphens only. Leave blank to show just the date and id.</p>
                    </div>
                    <button type="submit" class="mt-4 bg-green-600 hover:bg-green-700 text-white px-6 py-2 rounded">Save order number display</button>
                </form>
            </div>

            <!-- ===== Delivery pricing ===== -->
            <div class="bg-white p-8 rounded-xl shadow mb-6">
                <h2 class="text-lg font-bold text-gray-800 mb-1">Delivery pricing</h2>
                <p class="text-gray-400 text-xs mb-4">Shop-order delivery fee: service fee, insurance, distance rate tiers.</p>
                <form method="POST">
                    <?= csrfField() ?>
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
                            <label class="block text-gray-700 font-bold mb-2">Processing fee (% of order value)</label>
                            <input type="number" step="0.01" min="0" max="100" name="processing_percent" value="<?= field($deliveryPricing, 'processing_percent', '1.8') ?>" class="w-full px-4 py-2 border rounded">
                            <p class="text-gray-400 text-xs mt-1">Charged on <strong>both</strong> shop and Bulk orders. Was fixed at 1.8% in code with no setting anywhere.</p>
                        </div>
                        <div>
                            <label class="block text-gray-700 font-bold mb-2">Minimum delivery fee (UGX)</label>
                            <input type="number" step="0.01" min="0" name="min_delivery_fee" value="<?= field($deliveryPricing, 'min_delivery_fee', '0') ?>" class="w-full px-4 py-2 border rounded">
                            <p class="text-gray-400 text-xs mt-1">Floor under the computed shop delivery fee. 0 means no floor.</p>
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

            <!-- ===== Bulk delivery settings ===== -->
            <div class="bg-white p-8 rounded-xl shadow mb-6">
                <h2 class="text-lg font-bold text-gray-800 mb-1">Bulk delivery settings</h2>
                <p class="text-gray-400 text-xs mb-4">Weight-based delivery fee for bulk Bulk orders.</p>
                <form method="POST">
                    <?= csrfField() ?>
                    <input type="hidden" name="section" value="Bulk_delivery">
                    <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
                        <div>
                            <label class="block text-gray-700 font-bold mb-2">Base fee (UGX)</label>
                            <input type="number" step="0.01" min="0" name="base_fee" value="<?= field($BulkSettings, 'base_fee', '5000') ?>" class="w-full px-4 py-2 border rounded">
                            <p class="text-gray-400 text-xs mt-1">Flat charge per delivery. Covers the first few km — see below.</p>
                        </div>
                        <div>
                            <label class="block text-gray-700 font-bold mb-2">Km included in the base fee</label>
                            <input type="number" step="0.001" min="0" name="base_included_km" value="<?= field($BulkSettings, 'base_included_km', '3') ?>" class="w-full px-4 py-2 border rounded">
                            <p class="text-gray-400 text-xs mt-1">Only distance <strong>beyond</strong> this is charged per km. 0 charges every km on top of the base fee.</p>
                        </div>
                        <div>
                            <label class="block text-gray-700 font-bold mb-2">Fee per kg (UGX)</label>
                            <input type="number" step="0.01" min="0" name="fee_per_kg" value="<?= field($BulkSettings, 'fee_per_kg', '500') ?>" class="w-full px-4 py-2 border rounded">
                        </div>
                        <div>
                            <label class="block text-gray-700 font-bold mb-2">Rate per km (UGX)</label>
                            <input type="number" step="0.01" min="0" name="rate_per_km" value="<?= field($BulkSettings, 'rate_per_km', '900') ?>" class="w-full px-4 py-2 border rounded">
                            <p class="text-gray-400 text-xs mt-1">Vendor to customer, by road. Was fixed at 900 in code.</p>
                        </div>
                        <div>
                            <label class="block text-gray-700 font-bold mb-2">Maximum delivery fee (UGX)</label>
                            <input type="number" step="0.01" min="0" name="max_fee" value="<?= field($BulkSettings, 'max_fee', '120000') ?>" class="w-full px-4 py-2 border rounded">
                            <p class="text-gray-400 text-xs mt-1">Caps the whole Bulk fee after every component. Was fixed at 120,000 in code.</p>
                        </div>
                        <div>
                            <label class="block text-gray-700 font-bold mb-2">Max weight per order (kg)</label>
                            <input type="number" step="0.01" min="0" name="max_weight_kg" value="<?= field($BulkSettings, 'max_weight_kg', '1000') ?>" class="w-full px-4 py-2 border rounded">
                        </div>
                        <div>
                            <label class="block text-gray-700 font-bold mb-2">Free delivery threshold (UGX)</label>
                            <input type="number" step="0.01" min="0" name="free_delivery_threshold" value="<?= field($BulkSettings, 'free_delivery_threshold', '500000') ?>" class="w-full px-4 py-2 border rounded">
                        </div>
                    </div>

                    <h3 class="text-sm font-bold text-gray-700 mt-6 mb-1">Order limits</h3>
                    <p class="text-gray-400 text-xs mb-3">
                        These apply to <strong>surplus</strong> listings only. A wholesale listing
                        carries the seller's own minimum order, set on the listing, and that is
                        what is enforced for it — otherwise a wholesaler asking for 5&nbsp;kg would
                        be overridden by the floor below. The maximum weight applies to every
                        order, since it is a limit on what can physically be carried.
                    </p>
                    <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
                        <div>
                            <label class="block text-gray-700 font-bold mb-2">Minimum order value (UGX)</label>
                            <input type="number" step="0.01" min="0" name="min_order_value" value="<?= field($BulkSettings, 'min_order_value', '250000') ?>" class="w-full px-4 py-2 border rounded">
                            <p class="text-gray-400 text-xs mt-1">Surplus orders below this are refused. Was fixed at 250,000 in code.</p>
                        </div>
                        <div>
                            <label class="block text-gray-700 font-bold mb-2">Minimum weight per order (kg)</label>
                            <input type="number" step="0.001" min="0" name="min_weight_kg" value="<?= field($BulkSettings, 'min_weight_kg', '20') ?>" class="w-full px-4 py-2 border rounded">
                            <p class="text-gray-400 text-xs mt-1">Applies to weight-based surplus listings only. Was fixed at 20 kg in code.</p>
                        </div>
                    </div>

                    <button type="submit" class="mt-4 bg-green-600 hover:bg-green-700 text-white px-6 py-2 rounded">Save Bulk delivery settings</button>
                </form>
            </div>

            <!-- ===== Loyalty settings ===== -->
            <div class="bg-white p-8 rounded-xl shadow mb-6">
                <h2 class="text-lg font-bold text-gray-800 mb-1">Loyalty settings</h2>
                <p class="text-gray-400 text-xs mb-4">How fast customers earn points, and what those points are worth at checkout.</p>
                <form method="POST">
                    <?= csrfField() ?>
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