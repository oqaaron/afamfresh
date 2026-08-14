#!/usr/bin/env php
<?php
// =============================================================
// scripts/run-migrations.php — applies pending migrations/*.sql
// files, exactly once each, tracked in a schema_migrations table.
// =============================================================
// Run automatically by start.sh on every container start, before
// Apache begins serving traffic. Can also be run by hand:
//   php scripts/run-migrations.php
//
// WHY A LEDGER, NOT IDEMPOTENT SQL
//
// Most existing migration files use bare `ALTER TABLE ... ADD COLUMN`
// with no guard -- safe to run once, but they error ("duplicate
// column") on a second run. Rewriting all of them to be
// self-idempotent would be significant, ongoing busywork. Instead
// this script tracks which files have already been applied in a
// schema_migrations table, so a file only ever runs once regardless
// of its own contents. New migrations don't need defensive guards --
// they just need to be correct.
//
// FIRST RUN ON THE LIVE DATABASE
//
// The 9 migration files that predate this script have already been
// hand-applied to the live database. Before this script runs against
// that database for the first time, scripts/seed-schema-migrations.sql
// must be run once by hand (see that file) to mark those 9 as already
// applied -- otherwise this script would try to re-run them and fail
// on the first unguarded ADD COLUMN. From that point on, this script
// owns everything: a new migration file just needs to exist in
// migrations/ and it applies itself on the next deploy.
// =============================================================

// Never HTML, always readable in a container's plain-text log.
error_reporting(E_ALL);
ini_set('display_errors', '1');

function log_line(string $msg): void {
    fwrite(STDOUT, '[migrations] ' . $msg . "\n");
}

function fail(string $msg): never {
    fwrite(STDERR, '[migrations] ERROR: ' . $msg . "\n");
    exit(2);
}

try {
    $dbh = require_once __DIR__ . '/../includes/db_connect.php';
} catch (PDOException $e) {
    fwrite(STDERR, '[migrations] ERROR: could not connect to the database: ' . $e->getMessage() . "\n");
    exit(1);
}

$dbh->exec("
    CREATE TABLE IF NOT EXISTS schema_migrations (
        id INT NOT NULL AUTO_INCREMENT,
        filename VARCHAR(255) NOT NULL,
        applied_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (id),
        UNIQUE KEY uniq_filename (filename)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
");

// Guards against two containers migrating at once during a deploy's
// brief old/new overlap. Render's `starter` plan is single-instance
// today, but this is cheap insurance against that ever changing.
$lockName = 'afamfresh_schema_migrations';
$got = $dbh->query("SELECT GET_LOCK(" . $dbh->quote($lockName) . ", 30)")->fetchColumn();
if ((int)$got !== 1) {
    fail("could not acquire migration lock '$lockName' within 30s -- another instance may be migrating");
}

try {
    $applied = $dbh->query("SELECT filename FROM schema_migrations")
        ->fetchAll(PDO::FETCH_COLUMN);
    $appliedSet = array_flip($applied);

    $files = glob(__DIR__ . '/../migrations/*.sql');
    sort($files); // date-prefixed filenames sort chronologically

    $pending = array_filter($files, fn($f) => !isset($appliedSet[basename($f)]));

    if (empty($pending)) {
        log_line('up to date (' . count($applied) . ' migration(s) already applied)');
        exit(0);
    }

    foreach ($pending as $path) {
        $filename = basename($path);
        log_line("applying $filename ...");

        $sql = file_get_contents($path);
        if ($sql === false) {
            fail("could not read $filename");
        }

        foreach (splitSqlStatements($sql) as $statement) {
            try {
                $dbh->exec($statement);
            } catch (PDOException $e) {
                fail("$filename failed on statement:\n" . $statement . "\n\n" . $e->getMessage()
                    . "\n(not marked applied -- fix and redeploy to retry)");
            }
        }

        $dbh->prepare("INSERT INTO schema_migrations (filename) VALUES (?)")->execute([$filename]);
        log_line("applied $filename");
    }

    log_line('done: ' . count($pending) . ' migration(s) applied');
} finally {
    $dbh->exec("SELECT RELEASE_LOCK(" . $dbh->quote($lockName) . ")");
}

/**
 * Splits a .sql file's contents into individual statements.
 *
 * A plain explode(';') breaks the moment a string literal or a
 * COMMENT '...' clause contains a semicolon, which several of these
 * migration files' column comments come close to. This walks the
 * file character by character, tracking whether the cursor is inside
 * a single-quoted, double-quoted, or backtick-quoted span, or a `--`
 * line comment, and only treats `;` as a terminator outside of all of
 * those. None of this project's migrations use DELIMITER blocks
 * (stored procedures/triggers), so that case is not handled.
 *
 * @return string[] non-empty, trimmed statements, comments stripped
 */
function splitSqlStatements(string $sql): array {
    $statements = [];
    $current = '';
    $inString = null; // one of ', ", ` or null
    $inLineComment = false;
    $len = strlen($sql);

    for ($i = 0; $i < $len; $i++) {
        $ch = $sql[$i];

        if ($inLineComment) {
            if ($ch === "\n") {
                $inLineComment = false;
                $current .= $ch;
            }
            continue;
        }

        if ($inString !== null) {
            $current .= $ch;
            if ($ch === $inString) {
                // A doubled quote (e.g. '' inside a '...' string, used at
                // migrations/2026-08-14-loyalty-points.sql:82) is an escaped
                // quote, not the end of the string.
                if ($i + 1 < $len && $sql[$i + 1] === $inString) {
                    $current .= $sql[++$i];
                } else {
                    $inString = null;
                }
            }
            continue;
        }

        if ($ch === '-' && $i + 1 < $len && $sql[$i + 1] === '-') {
            $inLineComment = true;
            continue;
        }

        if ($ch === "'" || $ch === '"' || $ch === '`') {
            $inString = $ch;
            $current .= $ch;
            continue;
        }

        if ($ch === ';') {
            $trimmed = trim($current);
            if ($trimmed !== '') {
                $statements[] = $trimmed;
            }
            $current = '';
            continue;
        }

        $current .= $ch;
    }

    $trimmed = trim($current);
    if ($trimmed !== '') {
        $statements[] = $trimmed;
    }

    return $statements;
}
