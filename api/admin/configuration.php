<?php
// admin/configuration.php — Central platform configuration & financial settings
declare(strict_types=1);

session_start();
require_once 'includes/config.php';
require_once __DIR__ . '/../includes/csrf.php';
require_once __DIR__ . '/../includes/admin_permissions.php';
require_once __DIR__ . '/../includes/admin_audit.php';
requireAdminPermission('configuration.manage');

$error = '';
$success = '';

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

    if ($section === 'commissions') {
        $keys = [
            'default_rider_commission_rate'         => 'Rider commission',
            'default_vendor_commission_rate'        => 'Vendor commission',
            'default_market_vendor_commission_rate' => 'Market produce commission',
            'default_fastfood_commission_rate'      => 'Fast food commission',
            'default_wholesale_commission_rate'     => 'Wholesale commission',
        ];

        $stmt = $dbh->prepare("
            INSERT INTO app_config (config_key, config_value) 
            VALUES (?, ?)
            ON DUPLICATE KEY UPDATE config_value = VALUES(config_value)
        ");

        $ok = true;
        foreach ($keys as $k => $label) {
            $val = $_POST[$k] ?? '';
            if (!validNumber($val, true)) {
                $error = "{$label} must be a valid percentage between 0 and 100.";
                $ok = false;
                break;
            }
        }

        if ($ok) {
            try {
                $dbh->beginTransaction();
                foreach ($keys as $k => $label) {
                    $stmt->execute([$k, (string)(float)$_POST[$k]]);
                }
                $dbh->commit();
                $success = 'Platform commission rates updated successfully.';
                logAdminAction($dbh, 'configuration.updated', 'config', 'commissions', 'Updated default commission tiers');
            } catch (PDOException $e) {
                $dbh->rollBack();
                error_log('configuration.php commissions save failed: ' . $e->getMessage());
                $error = 'Could not save commission settings.';
            }
        }
    } elseif ($section === 'delivery_pricing') {
        $fields = [
            'service_fee' => false,
            'insurance_percent' => true,
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

$appConfig = [];
$cfgStmt = $dbh->query("SELECT config_key, config_value FROM app_config");
while ($row = $cfgStmt->fetch(PDO::FETCH_ASSOC)) {
    $appConfig[$row['config_key']] = $row['config_value'];
}
$orderNumberPrefix = (string)($appConfig['order_number_prefix'] ?? '');

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
<body class="bg-gray-100 font-sans antialiased">
<div class="flex min-h-screen">
    <?php include __DIR__ . "/includes/nav.php"; ?>

    <div class="flex-1 overflow-y-auto p-8">
        <div class="max-w-4xl mx-auto">
            <h1 class="text-3xl font-bold text-green-800 mb-2">Platform Configuration</h1>
            <p class="text-gray-600 text-sm mb-6">
                Business-tunable parameters and rates. Changes take effect immediately without requiring code redeployment.
            </p>

            <?php if ($error): ?>
                <div class="bg-red-100 border border-red-400 text-red-700 px-4 py-3 rounded mb-4"><?= htmlspecialchars($error) ?></div>
            <?php endif; ?>
            <?php if ($success): ?>
                <div class="bg-green-100 border border-green-400 text-green-700 px-4 py-3 rounded mb-4"><?= htmlspecialchars($success) ?></div>
            <?php endif; ?>

            <!-- ===== Platform Commission Rates ===== -->
            <div class="bg-white p-6 rounded-xl shadow mb-6">
                <h2 class="text-xl font-bold text-gray-800 mb-1">💼 Default Platform Commissions (%)</h2>
                <p class="text-gray-500 text-xs mb-4">
                    Base cut retained by the platform on order fulfillment. Can be overridden per individual seller in the Merchant Registry.
                </p>
                <form method="POST">
                    <?= csrfField() ?>
                    <input type="hidden" name="section" value="commissions">
                    <div class="grid grid-cols-1 md:grid-cols-5 gap-4">
                        <div>
                            <label class="block text-gray-700 text-xs font-bold mb-1">Riders (%)</label>
                            <input type="number" step="0.1" min="0" max="100" name="default_rider_commission_rate" value="<?= field($appConfig, 'default_rider_commission_rate', '15.0') ?>" class="w-full px-3 py-2 border rounded font-semibold text-green-800 text-sm">
                        </div>
                        <div>
                            <label class="block text-gray-700 text-xs font-bold mb-1">Standard Vendors (%)</label>
                            <input type="number" step="0.1" min="0" max="100" name="default_vendor_commission_rate" value="<?= field($appConfig, 'default_vendor_commission_rate', '10.0') ?>" class="w-full px-3 py-2 border rounded font-semibold text-green-800 text-sm">
                        </div>
                        <div>
                            <label class="block text-gray-700 text-xs font-bold mb-1">Market Produce (%)</label>
                            <input type="number" step="0.1" min="0" max="100" name="default_market_vendor_commission_rate" value="<?= field($appConfig, 'default_market_vendor_commission_rate', '6.0') ?>" class="w-full px-3 py-2 border rounded font-semibold text-green-800 text-sm">
                        </div>
                        <div>
                            <label class="block text-gray-700 text-xs font-bold mb-1">Restaurants (%)</label>
                            <input type="number" step="0.1" min="0" max="100" name="default_fastfood_commission_rate" value="<?= field($appConfig, 'default_fastfood_commission_rate', '18.0') ?>" class="w-full px-3 py-2 border rounded font-semibold text-green-800 text-sm">
                        </div>
                        <div>
                            <label class="block text-gray-700 text-xs font-bold mb-1">Wholesale (%)</label>
                            <input type="number" step="0.1" min="0" max="100" name="default_wholesale_commission_rate" value="<?= field($appConfig, 'default_wholesale_commission_rate', '3.5') ?>" class="w-full px-3 py-2 border rounded font-semibold text-green-800 text-sm">
                        </div>
                    </div>
                    <button type="submit" class="mt-4 bg-green-700 hover:bg-green-800 text-white font-semibold px-6 py-2 rounded text-sm transition">Save Commission Rates</button>
                </form>
            </div>

            <!-- ===== Order number display ===== -->
            <div class="bg-white p-6 rounded-xl shadow mb-6">
                <h2 class="text-xl font-bold text-gray-800 mb-1">Order number display</h2>
                <p class="text-gray-500 text-xs mb-4">
                    Cosmetic prefix shown on order numbers (SMS, push notifications, admin pages).
                </p>
                <form method="POST">
                    <?= csrfField() ?>
                    <input type="hidden" name="section" value="order_display">
                    <div class="max-w-xs">
                        <label class="block text-gray-700 font-bold text-xs mb-2">Prefix (optional)</label>
                        <input type="text" maxlength="12" name="order_number_prefix"
                               value="<?= htmlspecialchars($orderNumberPrefix) ?>"
                               placeholder="e.g. AF" class="w-full px-4 py-2 border rounded text-sm">
                        <p class="text-gray-400 text-xs mt-1">Letters, numbers, and hyphens only.</p>
                    </div>
                    <button type="submit" class="mt-4 bg-green-600 hover:bg-green-700 text-white font-semibold px-6 py-2 rounded text-sm transition">Save Order Prefix</button>
                </form>
            </div>

            <!-- ===== Delivery pricing ===== -->
            <div class="bg-white p-6 rounded-xl shadow mb-6">
                <h2 class="text-xl font-bold text-gray-800 mb-1">Delivery pricing</h2>
                <p class="text-gray-500 text-xs mb-4">Shop-order delivery fee: service fee, insurance, distance rate tiers.</p>
                <form method="POST">
                    <?= csrfField() ?>
                    <input type="hidden" name="section" value="delivery_pricing">
                    <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
                        <div>
                            <label class="block text-gray-700 font-bold text-xs mb-1">Service fee (UGX)</label>
                            <input type="number" step="0.01" min="0" name="service_fee" value="<?= field($deliveryPricing, 'service_fee', '1000') ?>" class="w-full px-3 py-2 border rounded text-sm">
                        </div>
                        <div>
                            <label class="block text-gray-700 font-bold text-xs mb-1">Insurance (% of order)</label>
                            <input type="number" step="0.01" min="0" max="100" name="insurance_percent" value="<?= field($deliveryPricing, 'insurance_percent', '0.9') ?>" class="w-full px-3 py-2 border rounded text-sm">
                        </div>
                        <div>
                            <label class="block text-gray-700 font-bold text-xs mb-1">Processing fee (% of order value)</label>
                            <input type="number" step="0.01" min="0" max="100" name="processing_percent" value="<?= field($deliveryPricing, 'processing_percent', '1.8') ?>" class="w-full px-3 py-2 border rounded text-sm">
                        </div>
                        <div>
                            <label class="block text-gray-700 font-bold text-xs mb-1">Minimum delivery fee (UGX)</label>
                            <input type="number" step="0.01" min="0" name="min_delivery_fee" value="<?= field($deliveryPricing, 'min_delivery_fee', '0') ?>" class="w-full px-3 py-2 border rounded text-sm">
                        </div>
                        <div>
                            <label class="block text-gray-700 font-bold text-xs mb-1">Free delivery threshold (UGX)</label>
                            <input type="number" step="0.01" min="0" name="free_delivery_threshold" value="<?= field($deliveryPricing, 'free_delivery_threshold', '100000') ?>" class="w-full px-3 py-2 border rounded text-sm">
                        </div>
                        <div>
                            <label class="block text-gray-700 font-bold text-xs mb-1">Free delivery distance limit (km)</label>
                            <input type="number" step="0.01" min="0" name="free_delivery_distance_threshold" value="<?= field($deliveryPricing, 'free_delivery_distance_threshold', '10') ?>" class="w-full px-3 py-2 border rounded text-sm">
                        </div>
                        <div>
                            <label class="block text-gray-700 font-bold text-xs mb-1">Medium-order threshold (UGX)</label>
                            <input type="number" step="0.01" min="0" name="medium_order_threshold" value="<?= field($deliveryPricing, 'medium_order_threshold', '50000') ?>" class="w-full px-3 py-2 border rounded text-sm">
                        </div>
                        <div>
                            <label class="block text-gray-700 font-bold text-xs mb-1">Medium/long-distance rate (UGX/km)</label>
                            <input type="number" step="0.01" min="0" name="medium_order_rate" value="<?= field($deliveryPricing, 'medium_order_rate', '375') ?>" class="w-full px-3 py-2 border rounded text-sm">
                        </div>
                        <div>
                            <label class="block text-gray-700 font-bold text-xs mb-1">Short-distance rate (UGX/km)</label>
                            <input type="number" step="0.01" min="0" name="low_order_rate" value="<?= field($deliveryPricing, 'low_order_rate', '700') ?>" class="w-full px-3 py-2 border rounded text-sm">
                        </div>
                        <div>
                            <label class="block text-gray-700 font-bold text-xs mb-1">Profit margin (%, small orders only)</label>
                            <input type="number" step="0.01" min="0" max="100" name="profit_percent" value="<?= field($deliveryPricing, 'profit_percent', '8') ?>" class="w-full px-3 py-2 border rounded text-sm">
                        </div>
                        <div class="flex items-center gap-2 md:col-span-2">
                            <input type="checkbox" id="profit_percent_enabled" name="profit_percent_enabled" value="1" <?= !empty($deliveryPricing['profit_percent_enabled']) ? 'checked' : '' ?>>
                            <label for="profit_percent_enabled" class="text-gray-700 text-sm">Apply the profit margin to small orders</label>
                        </div>
                    </div>
                    <button type="submit" class="mt-4 bg-green-600 hover:bg-green-700 text-white font-semibold px-6 py-2 rounded text-sm transition">Save Delivery Pricing</button>
                </form>
            </div>

            <!-- ===== Bulk delivery settings ===== -->
            <div class="bg-white p-6 rounded-xl shadow mb-6">
                <h2 class="text-xl font-bold text-gray-800 mb-1">Bulk delivery settings</h2>
                <p class="text-gray-500 text-xs mb-4">Weight-based delivery fee for bulk Bulk orders.</p>
                <form method="POST">
                    <?= csrfField() ?>
                    <input type="hidden" name="section" value="Bulk_delivery">
                    <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
                        <div>
                            <label class="block text-gray-700 font-bold text-xs mb-1">Base fee (UGX)</label>
                            <input type="number" step="0.01" min="0" name="base_fee" value="<?= field($BulkSettings, 'base_fee', '5000') ?>" class="w-full px-3 py-2 border rounded text-sm">
                        </div>
                        <div>
                            <label class="block text-gray-700 font-bold text-xs mb-1">Km included in base fee</label>
                            <input type="number" step="0.001" min="0" name="base_included_km" value="<?= field($BulkSettings, 'base_included_km', '3') ?>" class="w-full px-3 py-2 border rounded text-sm">
                        </div>
                        <div>
                            <label class="block text-gray-700 font-bold text-xs mb-1">Fee per kg (UGX)</label>
                            <input type="number" step="0.01" min="0" name="fee_per_kg" value="<?= field($BulkSettings, 'fee_per_kg', '500') ?>" class="w-full px-3 py-2 border rounded text-sm">
                        </div>
                        <div>
                            <label class="block text-gray-700 font-bold text-xs mb-1">Rate per km (UGX)</label>
                            <input type="number" step="0.01" min="0" name="rate_per_km" value="<?= field($BulkSettings, 'rate_per_km', '900') ?>" class="w-full px-3 py-2 border rounded text-sm">
                        </div>
                        <div>
                            <label class="block text-gray-700 font-bold text-xs mb-1">Maximum delivery fee (UGX)</label>
                            <input type="number" step="0.01" min="0" name="max_fee" value="<?= field($BulkSettings, 'max_fee', '120000') ?>" class="w-full px-3 py-2 border rounded text-sm">
                        </div>
                        <div>
                            <label class="block text-gray-700 font-bold text-xs mb-1">Max weight per order (kg)</label>
                            <input type="number" step="0.01" min="0" name="max_weight_kg" value="<?= field($BulkSettings, 'max_weight_kg', '1000') ?>" class="w-full px-3 py-2 border rounded text-sm">
                        </div>
                        <div>
                            <label class="block text-gray-700 font-bold text-xs mb-1">Free delivery threshold (UGX)</label>
                            <input type="number" step="0.01" min="0" name="free_delivery_threshold" value="<?= field($BulkSettings, 'free_delivery_threshold', '500000') ?>" class="w-full px-3 py-2 border rounded text-sm">
                        </div>
                        <div>
                            <label class="block text-gray-700 font-bold text-xs mb-1">Minimum order value (UGX)</label>
                            <input type="number" step="0.01" min="0" name="min_order_value" value="<?= field($BulkSettings, 'min_order_value', '250000') ?>" class="w-full px-3 py-2 border rounded text-sm">
                        </div>
                        <div>
                            <label class="block text-gray-700 font-bold text-xs mb-1">Minimum weight per order (kg)</label>
                            <input type="number" step="0.001" min="0" name="min_weight_kg" value="<?= field($BulkSettings, 'min_weight_kg', '20') ?>" class="w-full px-3 py-2 border rounded text-sm">
                        </div>
                    </div>
                    <button type="submit" class="mt-4 bg-green-600 hover:bg-green-700 text-white font-semibold px-6 py-2 rounded text-sm transition">Save Bulk Delivery Settings</button>
                </form>
            </div>

            <!-- ===== Loyalty settings ===== -->
            <div class="bg-white p-6 rounded-xl shadow mb-6">
                <h2 class="text-xl font-bold text-gray-800 mb-1">Loyalty settings</h2>
                <p class="text-gray-500 text-xs mb-4">How fast customers earn points, and what those points are worth at checkout.</p>
                <form method="POST">
                    <?= csrfField() ?>
                    <input type="hidden" name="section" value="loyalty">
                    <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
                        <div>
                            <label class="block text-gray-700 font-bold text-xs mb-1">Earn rate (UGX of goods per 1 point)</label>
                            <input type="number" step="0.01" min="0.01" name="earn_rate_ugx_per_point" value="<?= field($loyaltySettings, 'earn_rate_ugx_per_point', '1000') ?>" class="w-full px-3 py-2 border rounded text-sm">
                        </div>
                        <div>
                            <label class="block text-gray-700 font-bold text-xs mb-1">Redeem value (UGX discount per point)</label>
                            <input type="number" step="0.01" min="0.01" name="redeem_value_ugx_per_point" value="<?= field($loyaltySettings, 'redeem_value_ugx_per_point', '50') ?>" class="w-full px-3 py-2 border rounded text-sm">
                        </div>
                        <div>
                            <label class="block text-gray-700 font-bold text-xs mb-1">Minimum redeemable points</label>
                            <input type="number" step="1" min="0" name="min_redeemable_points" value="<?= field($loyaltySettings, 'min_redeemable_points', '100') ?>" class="w-full px-3 py-2 border rounded text-sm">
                        </div>
                        <div>
                            <label class="block text-gray-700 font-bold text-xs mb-1">Max redemption (% of order value)</label>
                            <input type="number" step="0.01" min="0" max="100" name="max_redeem_percent" value="<?= field($loyaltySettings, 'max_redeem_percent', '30') ?>" class="w-full px-3 py-2 border rounded text-sm">
                        </div>
                    </div>
                    <button type="submit" class="mt-4 bg-green-600 hover:bg-green-700 text-white font-semibold px-6 py-2 rounded text-sm transition">Save Loyalty Settings</button>
                </form>
            </div>
        </div>
    </div>
</div>
</body>
</html>
