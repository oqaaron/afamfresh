-- =============================================================
-- One-time seed for scripts/run-migrations.php
-- =============================================================
-- Run this by hand, once, against the live database, BEFORE the
-- first deploy that includes run-migrations.php.
--
-- WHY THIS IS NEEDED
--
-- The 9 files in migrations/ as of 2026-08-14 have already been
-- applied to this database by hand (pasted into a `mysql` session one
-- at a time, over several days). run-migrations.php tracks applied
-- migrations in a schema_migrations table so it only ever runs a
-- given file once -- but most of those 9 files use bare
-- `ALTER TABLE ... ADD COLUMN` with no guard, which errors
-- ("duplicate column") if run again against a database that already
-- has the column.
--
-- So the very first time run-migrations.php runs against this
-- database, schema_migrations needs to already say "these 9 are
-- done" -- otherwise it would try to re-run them and fail on the
-- first one. This file does exactly that: creates the ledger table
-- (identical DDL to what run-migrations.php itself would create) and
-- backfills it with the 9 filenames, timestamped now rather than
-- guessing when each was actually run -- the exact time doesn't
-- matter, only that they're marked done.
--
-- This file deliberately lives in scripts/, not migrations/ --
-- run-migrations.php globs migrations/*.sql, and this must run
-- BEFORE that automated path exists, not be swept into it as just
-- another migration.
--
-- Run from Cloud Shell:
--   gcloud sql connect afamfresh --user=aokwi --database=kitchen
-- then paste this file's statements. Run it exactly once.
-- =============================================================

CREATE TABLE IF NOT EXISTS schema_migrations (
    id INT NOT NULL AUTO_INCREMENT,
    filename VARCHAR(255) NOT NULL,
    applied_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uniq_filename (filename)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- INSERT IGNORE, not INSERT: safe to paste this file twice by
-- accident -- the UNIQUE KEY on filename makes a repeat a no-op
-- instead of an error.
INSERT IGNORE INTO schema_migrations (filename) VALUES
    ('2026-08-11-vendor-owned-products.sql'),
    ('2026-08-12-sms-and-surplus-proof.sql'),
    ('2026-08-12-surplus-dispatch.sql'),
    ('2026-08-12-vendor-coordinates.sql'),
    ('2026-08-12-vendor-payouts.sql'),
    ('2026-08-13-customer-receipt-confirmation.sql'),
    ('2026-08-13-payment-method-and-ledger.sql'),
    ('2026-08-13-tracking.sql'),
    ('2026-08-14-loyalty-points.sql');

-- Sanity check: expect 9 rows.
SELECT COUNT(*) AS seeded_migrations FROM schema_migrations;
