<?php
// =============================================================
// includes/db_connect.php — builds the PDO connection, nothing else.
// =============================================================
// Extracted out of admin/includes/config.php so a CLI script (the
// migration runner) can get $dbh without inheriting that file's
// HTTP-only failure path: on a PDOException, config.php calls
// header(), echoes JSON, and does a bare `exit;` — which exits 0,
// silently telling a shell script the step succeeded even when the
// database was unreachable.
//
// This file does the opposite: it throws PDOException and lets the
// caller decide how to report that. config.php wraps this require in
// its existing try/catch (identical behaviour, unchanged for every
// HTTP endpoint). The migration runner wraps it in its own try/catch
// that writes to STDERR and exits non-zero, so `set -e` in the
// container's start.sh actually sees the failure.
//
// Usage: `$dbh = require_once __DIR__ . '/db_connect.php';`
// =============================================================

require_once __DIR__ . '/env.php';

define('DB_HOST', env('DB_HOST', 'localhost'));
define('DB_USER', env('DB_USER', 'root'));
define('DB_PASS', env('DB_PASS', ''));   // empty is normal for local XAMPP
define('DB_NAME', env('DB_NAME', 'kitchen'));

// Cloud SQL Unix Socket (Injected automatically by Cloud Run when connected)
define('DB_SOCKET', env('DB_SOCKET', '/cloudsql/afamfresh-f68c6:europe-west3:afamfresh'));

// Determine connection type: Cloud SQL UNIX Socket vs TCP Host/IP
if (file_exists(DB_SOCKET)) {
    // Connect via Cloud SQL Unix Socket.
    //
    // charset=utf8mb4 is not optional here even though it looks like
    // boilerplate: this is the branch that runs on Cloud Run, and without
    // it PDO falls back to the server's default charset. Local XAMPP and
    // Cloud SQL do not necessarily agree on that default, so omitting it
    // corrupts any non-ASCII name or address in production while local
    // testing stays clean — the worst possible failure shape.
    $dsn = sprintf('mysql:dbname=%s;unix_socket=%s;charset=utf8mb4', DB_NAME, DB_SOCKET);
} else {
    // TCP. Two very different situations use this branch: local XAMPP over
    // loopback, and a host outside Google (Render, a VPS) reaching Cloud
    // SQL's public IP across the open internet.
    $dsn = sprintf(
        'mysql:host=%s;port=%d;dbname=%s;charset=utf8mb4',
        DB_HOST, (int)env('DB_PORT', 3306), DB_NAME
    );
}

// TLS for the TCP case. Without it, the database password and every row
// returned cross the public internet in clear text — fine over loopback,
// not fine from another cloud. Cloud SQL will accept an unencrypted
// connection unless told otherwise, so this cannot be left to the server.
//
// DB_SSL_CA holds the PEM itself rather than a path, because the platforms
// this runs on inject configuration as environment variables and have no
// persistent disk to put a file on.
$pdoOptions = [];
$sslCa = trim((string)env('DB_SSL_CA', ''));
if ($sslCa !== '' && !file_exists(DB_SOCKET)) {
    // The filename carries the effective uid. Two processes share this
    // container -- Apache as www-data and the notification worker -- and
    // with a single shared path whichever wrote first owned a 0600 file
    // the other could not read or replace. That took the API down with
    // "Permission denied" on md5_file() and a database connection with no
    // usable CA.
    //
    // A path per uid rather than a relaxed mode: the file is a private key
    // trust anchor, and widening it to fix a permissions clash would be
    // solving the wrong half of the problem.
    $uid = function_exists('posix_geteuid') ? posix_geteuid() : 'shared';
    $caPath = sys_get_temp_dir() . '/cloudsql-server-ca-' . $uid . '.pem';
    if (!is_file($caPath) || !is_readable($caPath) || md5_file($caPath) !== md5($sslCa)) {
        file_put_contents($caPath, $sslCa);
        chmod($caPath, 0600);
    }
    $pdoOptions[PDO::MYSQL_ATTR_SSL_CA] = $caPath;
    // Cloud SQL's certificate is issued for the instance name, not the IP
    // being dialled, so hostname verification cannot succeed. The CA check
    // above is what establishes we are talking to the right server.
    $pdoOptions[PDO::MYSQL_ATTR_SSL_VERIFY_SERVER_CERT] = false;
}

$dbh = new PDO($dsn, DB_USER, DB_PASS, $pdoOptions);
$dbh->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);
$dbh->setAttribute(PDO::ATTR_DEFAULT_FETCH_MODE, PDO::FETCH_ASSOC);
$dbh->setAttribute(PDO::ATTR_EMULATE_PREPARES, false);

return $dbh;
