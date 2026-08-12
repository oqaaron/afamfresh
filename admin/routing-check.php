<?php
// =============================================================
// admin/routing-check.php
//
// Is the Routes API actually working?
//
// A routing failure is silent by design — roadDistanceBetween() falls back to
// OSRM and then to a straight line so an order can still be quoted. That is the
// right behaviour and it is also why a broken key can go unnoticed for weeks
// while every delivery is quietly under-charged.
//
// This page routes one fixed journey and says which service answered. It is the
// quickest way to tell a working key from a missing one, a restricted one, or
// an unenabled API — each of which produces the same silence in the logs.
//
// Admin-only: the response names the failure, and an error message from Google
// can disclose the project's configuration.
// =============================================================

require_once __DIR__ . '/auth_check.php';
requireAdminLoginWeb();
require_once __DIR__ . '/includes/config.php';
require_once __DIR__ . '/../includes/google_routes.php';

// Kampala city centre to Entebbe: a real road journey of roughly 35-40km, and
// one whose straight-line distance is visibly shorter. If the reported distance
// comes back near 33km, a fallback answered.
$fromLat = 0.3476;  $fromLng = 32.5825;   // Kampala
$toLat   = 0.0512;  $toLng   = 32.4637;   // Entebbe

$keySet = trim((string)env('GOOGLE_MAPS_API_KEY', '')) !== '';

$google   = $keySet ? googleRouteBetween($fromLat, $fromLng, $toLat, $toLng) : null;
$resolved = roadDistanceBetween($fromLat, $fromLng, $toLat, $toLng);
$straight = calculateDistance($fromLat, $fromLng, $toLat, $toLng);
?>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Routing check — AfamFresh Admin</title>
    <script src="https://cdn.tailwindcss.com"></script>
    <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.5.0/css/all.min.css">
</head>
<body class="bg-gray-50 font-sans antialiased">
<div class="flex min-h-screen">
<?php include __DIR__ . '/includes/nav.php'; ?>
<div class="flex-1 overflow-auto">
    <div class="max-w-3xl mx-auto px-6 py-8">
        <h1 class="text-2xl font-bold text-green-800 mb-1">Routing check</h1>
        <p class="text-gray-600 mb-6">
            Kampala to Entebbe, priced the way a delivery would be.
        </p>

        <?php if (!$keySet): ?>
            <div class="bg-red-100 border border-red-300 text-red-800 px-4 py-3 rounded mb-4">
                <strong>GOOGLE_MAPS_API_KEY is not set.</strong>
                Every delivery quote is falling back to OSRM or a straight line,
                which under-charges. Set it in the Render environment.
            </div>
        <?php elseif ($google === null): ?>
            <div class="bg-red-100 border border-red-300 text-red-800 px-4 py-3 rounded mb-4">
                <strong>The key is set, but Google refused the request.</strong>
                The reason is in the server log — usually the Routes API not being
                enabled on the project, a key restriction that excludes this server,
                or billing not being active.
            </div>
        <?php else: ?>
            <div class="bg-green-100 border border-green-300 text-green-800 px-4 py-3 rounded mb-4">
                <strong>Routes API is answering.</strong> Delivery distances are real
                road distances.
            </div>
        <?php endif; ?>

        <div class="bg-white rounded-xl shadow divide-y">
            <div class="px-5 py-4 flex justify-between">
                <span class="text-gray-600">Distance used for pricing</span>
                <span class="font-semibold"><?= number_format($resolved['km'], 1) ?> km</span>
            </div>
            <div class="px-5 py-4 flex justify-between">
                <span class="text-gray-600">Answered by</span>
                <span class="font-semibold <?= $resolved['source'] === 'google' ? 'text-green-700' : 'text-red-700' ?>">
                    <?= htmlspecialchars($resolved['source']) ?>
                </span>
            </div>
            <?php if (!empty($resolved['minutes'])): ?>
            <div class="px-5 py-4 flex justify-between">
                <span class="text-gray-600">Driving time</span>
                <span class="font-semibold"><?= round($resolved['minutes']) ?> min</span>
            </div>
            <?php endif; ?>
            <div class="px-5 py-4 flex justify-between">
                <span class="text-gray-600">Straight line, for comparison</span>
                <span class="text-gray-500"><?= number_format($straight, 1) ?> km</span>
            </div>
        </div>

        <p class="text-sm text-gray-500 mt-4">
            The straight line is always shorter than the road. If the pricing
            distance matches it, a fallback answered and every quote is low by
            that difference.
        </p>
    </div>
</div>
</div>
</body>
</html>