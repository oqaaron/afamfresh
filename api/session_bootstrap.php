<?php
/**
 * Session bootstrap — loaded by auto_prepend_file BEFORE any endpoint
 * runs, so the DB-backed session handler is registered before any
 * session_start() call.
 *
 * WHY THIS FILE EXISTS
 *
 * Every endpoint (auth.php, payment.php, orders.php, ...) opens with
 * session_start() at the top. admin/includes/config.php used to register
 * the DB-backed handler, but by the time config.php is required the
 * session is already active, and PHP refuses with:
 *
 *   Warning: session_set_save_handler(): Session save handler cannot be
 *   changed when a session is active
 *
 * and silently keeps the filesystem handler — which Render wipes on every
 * deploy, so PHPSESSID points at nothing and the Android app, iOS app,
 * and web portal all get "Not logged in" after a restart.
 *
 * WHAT IT MUST NEVER DO
 *
 * This file runs on EVERY request from all three clients. Any failure
 * here is a failure everywhere. So: it never throws, never exits, never
 * assumes a class exists. If anything goes wrong it logs and returns,
 * leaving PHP on its default filesystem handler. Sessions work exactly
 * as before; they just don't survive a restart. Degraded, not down.
 */

// Absolute safety: never emit output, never throw to the caller.
ini_set('display_errors', '0');

if (session_status() !== PHP_SESSION_NONE) {
    // A session is already active — presumably because something loaded
    // before this file, or another ini layer beat us here. Refusing to
    // register now is the correct behaviour: PHP would warn and ignore
    // us anyway. Leave the current handler in place.
    return;
}

// Read DB credentials from the environment. Render sets these as env
// vars; the fallback chain mirrors includes/db_connect.php.
$host = getenv('DB_HOST') ?: '';
$name = getenv('DB_NAME') ?: '';
$user = getenv('DB_USER') ?: '';
$pass = getenv('DB_PASS') ?: '';
$port = getenv('DB_PORT') ?: '3306';
$ca   = trim((string)(getenv('DB_SSL_CA') ?: ''));

if ($host === '' || $name === '' || $user === '') {
    error_log('[session-bootstrap] DB env vars missing; leaving filesystem sessions in place');
    return;
}

try {
    $dsn = "mysql:host={$host};port={$port};dbname={$name};charset=utf8mb4";
    if ($ca !== '' && file_exists($ca)) {
        $dsn .= ";sslmode=verify-full";
    }
    $pdo = new PDO($dsn, $user, $pass, [
        PDO::ATTR_ERRMODE            => PDO::ERRMODE_EXCEPTION,
        PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
        PDO::ATTR_TIMEOUT            => 3,
    ]);
} catch (Throwable $e) {
    error_log('[session-bootstrap] DB connect failed: ' . $e->getMessage() . '; leaving filesystem sessions in place');
    return;
}

// Verify the table exists before installing the handler. If it doesn't,
// the handler would silently return empty sessions on every read, which
// is worse than falling back to the filesystem: at least the filesystem
// works within a single container lifetime.
try {
    $pdo->query("SELECT 1 FROM php_sessions LIMIT 1");
} catch (Throwable $e) {
    error_log('[session-bootstrap] php_sessions table unavailable: ' . $e->getMessage() . '; leaving filesystem sessions in place');
    return;
}

/**
 * Minimal session handler. Every method catches its own exceptions and
 * returns a value PHP understands — it must not let a DB error escape,
 * because that would leave the request without a session entirely.
 */
class AfamDbSessionHandler implements SessionHandlerInterface
{
    private PDO $dbh;
    public function __construct(PDO $dbh) { $this->dbh = $dbh; }
    public function open($path, $name): bool { return true; }
    public function close(): bool { return true; }

    public function read($id): string|false
    {
        try {
            $stmt = $this->dbh->prepare(
                "SELECT data FROM php_sessions WHERE id = ? AND last_access > ?"
            );
            $stmt->execute([$id, time() - 604800]);
            $data = $stmt->fetchColumn();
            return $data === false ? '' : (string)$data;
        } catch (Throwable $e) {
            error_log('[session] read failed: ' . $e->getMessage());
            return '';
        }
    }

    public function write($id, $data): bool
    {
        try {
            $stmt = $this->dbh->prepare(
                "INSERT INTO php_sessions (id, data, last_access)
                 VALUES (?, ?, ?)
                 ON DUPLICATE KEY UPDATE
                    data = VALUES(data),
                    last_access = VALUES(last_access)"
            );
            return $stmt->execute([$id, $data, time()]);
        } catch (Throwable $e) {
            error_log('[session] write failed: ' . $e->getMessage());
            return false;
        }
    }

    public function destroy($id): bool
    {
        try {
            $stmt = $this->dbh->prepare("DELETE FROM php_sessions WHERE id = ?");
            return $stmt->execute([$id]);
        } catch (Throwable $e) {
            error_log('[session] destroy failed: ' . $e->getMessage());
            return false;
        }
    }

    public function gc($max_lifetime): int|false
    {
        try {
            $stmt = $this->dbh->prepare(
                "DELETE FROM php_sessions WHERE last_access < ?"
            );
            $stmt->execute([time() - max(86400, (int)$max_lifetime)]);
            return $stmt->rowCount();
        } catch (Throwable $e) {
            error_log('[session] gc failed: ' . $e->getMessage());
            return 0;
        }
    }
}

try {
    session_set_save_handler(new AfamDbSessionHandler($pdo), true);
} catch (Throwable $e) {
    error_log('[session-bootstrap] session_set_save_handler failed: ' . $e->getMessage());
    // Return without rethrowing — fall back to filesystem sessions.
}