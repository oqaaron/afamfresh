<?php
// =============================================================
// Service root — liveness probe.
// =============================================================
// Two reasons this file exists.
//
// The platform probes GET / to decide the container is up. With no index
// file that is a 403 plus an autoindex error in the log every few seconds,
// which buries anything real.
//
// And the health check was pointed at /api/products.php?action=list, so every
// probe ran a full catalogue query against Cloud SQL — a few times a minute,
// permanently, for a question that does not need the database. Worse, it was
// not even a health signal: config.php answers a connection failure with
// HTTP 200, so that endpoint reported healthy for the entire time the
// database was unreachable.
//
// Deliberately does NOT touch the database. This answers "is the container
// serving?" only. Whether the database is reachable belongs in the log and in
// monitoring — tying it to the health check would make Render restart the
// container over a fault restarting cannot fix, turning a degraded service
// into a crash loop.
// =============================================================

header('Content-Type: application/json');
header('Cache-Control: no-store');

echo json_encode([
    'service' => 'afamfresh-api',
    'status'  => 'ok',
    'time'    => gmdate('c'),
], JSON_UNESCAPED_SLASHES);
