-- =============================================================
-- Dispatching surplus orders to riders
-- =============================================================
-- The rider system was built when `orders` was the only kind of order there
-- was, so rider_assignments.order_id and rider_earnings.order_id both point at
-- it implicitly. Surplus orders live in surplus_orders, and the two id spaces
-- OVERLAP -- shop order 41 and surplus order 41 both exist today.
--
-- Without a discriminator, dispatching surplus would produce two silent
-- failures, neither of which raises an error anywhere:
--
--   1. api/rider.php joins `JOIN orders o ON o.orderid = ra.order_id`. A
--      surplus assignment would resolve to an unrelated shop order and show a
--      rider the wrong customer's name, phone and address.
--
--   2. rider_earnings has UNIQUE(order_id). Credit a rider for shop order 41
--      and a later surplus order 41 is treated as ALREADY CREDITED -- the
--      rider does the work and is never paid, and nothing logs a problem.
--
-- Both tables therefore gain a `source` column, and rider_earnings' uniqueness
-- moves from (order_id) to (source, order_id). This mirrors vendor_earnings,
-- which already carries a `source` for exactly this reason.
--
-- Run against Cloud SQL:
--   mysql -h 127.0.0.1 -P 9470 -u aokwi -p --get-server-public-key kitchen \
--     < migrations/2026-08-12-surplus-dispatch.sql
-- =============================================================

-- -------------------------------------------------------------
-- 0. The foreign keys on order_id have to go first
-- -------------------------------------------------------------
-- Both tables constrained order_id to orders.orderid:
--
--   fk_assignment_order       ON DELETE CASCADE ON UPDATE CASCADE
--   fk_rider_earnings_order   ON DELETE CASCADE
--
-- A foreign key can reference only ONE table, so neither can coexist with an
-- order_id that points at either of two. Left in place they would not merely
-- be untidy: every surplus insert would be REJECTED, because no row with that
-- id exists in `orders`. Dispatch would fail on the first assignment, and no
-- rider could ever be credited for a surplus delivery.
--
-- WHAT IS GIVEN UP, STATED PLAINLY
--
-- The CASCADE meant deleting an order swept up its assignments and earnings.
-- After this, a deleted order can leave rows behind pointing at nothing. Two
-- things make that acceptable rather than merely convenient:
--
--   - This application never hard-deletes an order. Orders are cancelled or
--     refunded and the row stays, so the cascade has in practice never fired.
--   - api/rider.php's deliveries action already skips an assignment whose
--     order cannot be resolved, and logs it, instead of failing the request.
--
-- vendor_earnings already carries (source, order_id) with no foreign key for
-- exactly this reason; this makes the rider side consistent with it.
--
-- The rider_id foreign keys are left alone. `riders` is a single table, so
-- those constraints remain meaningful and still do their job.
ALTER TABLE `rider_assignments` DROP FOREIGN KEY `fk_assignment_order`;
ALTER TABLE `rider_earnings`    DROP FOREIGN KEY `fk_rider_earnings_order`;

-- -------------------------------------------------------------
-- 1. Which table an assignment points at
-- -------------------------------------------------------------
-- Defaults to 'order' so every existing assignment keeps its current meaning.
ALTER TABLE `rider_assignments`
  ADD COLUMN `source` ENUM('order','surplus') NOT NULL DEFAULT 'order'
    COMMENT 'Which table order_id points at'
    AFTER `order_id`,
  ADD KEY `idx_source_order` (`source`, `order_id`);

-- -------------------------------------------------------------
-- 2. Same for the earnings ledger
-- -------------------------------------------------------------
ALTER TABLE `rider_earnings`
  ADD COLUMN `source` ENUM('order','surplus') NOT NULL DEFAULT 'order'
    COMMENT 'Which table order_id points at'
    AFTER `order_id`;

-- Replace UNIQUE(order_id) with UNIQUE(source, order_id).
--
-- The old index has to go or a surplus delivery can never be credited when the
-- same-numbered shop order already has been. Its NAME is not known in advance
-- -- it depends on how the table was originally created -- so it is looked up
-- rather than guessed. Guessing wrong would either error out or, worse, drop a
-- different index.
--
-- The lookup asks for: a unique index, not the primary key, covering exactly
-- one column, and that column is order_id.
SET @idx := (
  SELECT INDEX_NAME
    FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE()
     AND TABLE_NAME = 'rider_earnings'
     AND NON_UNIQUE = 0
     AND INDEX_NAME <> 'PRIMARY'
   GROUP BY INDEX_NAME
  HAVING COUNT(*) = 1 AND MAX(COLUMN_NAME) = 'order_id'
   LIMIT 1
);

SET @sql := IF(@idx IS NULL,
  'SELECT ''no single-column unique key on order_id; nothing to drop'' AS note',
  CONCAT('ALTER TABLE `rider_earnings` DROP INDEX `', @idx, '`')
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Guarded, because a partial earlier run may already have added it: this
-- migration was first written without the foreign-key drops above, so the
-- DROP INDEX failed while the statements around it succeeded.
SET @hasKey := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rider_earnings'
     AND INDEX_NAME = 'uniq_rider_earning_per_order'
);
SET @sql := IF(@hasKey > 0,
  'SELECT ''uniq_rider_earning_per_order already present'' AS note',
  'ALTER TABLE `rider_earnings` ADD UNIQUE KEY `uniq_rider_earning_per_order` (`source`, `order_id`)'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- -------------------------------------------------------------
-- 3. Where a surplus order is going
-- -------------------------------------------------------------
-- surplus_orders already has delivery_lat/delivery_lng, but nothing to record
-- that a rider has been dispatched. The assignment itself lives in
-- rider_assignments; this is only the denormalised flag the admin list and the
-- vendor's screen read, so neither has to join.
ALTER TABLE `surplus_orders`
  ADD COLUMN `rider_assigned_at` DATETIME DEFAULT NULL
    COMMENT 'Set when an admin dispatches this to a rider'
    AFTER `payment_captured_at`;

-- -------------------------------------------------------------
-- Sanity checks
-- -------------------------------------------------------------
SELECT COUNT(*) AS assignments, SUM(source = 'order') AS legacy_shop
  FROM `rider_assignments`;

SELECT COUNT(*) AS earnings_rows, SUM(source = 'order') AS legacy_shop
  FROM `rider_earnings`;

-- Should list exactly one unique key, on (source, order_id).
SHOW INDEX FROM `rider_earnings` WHERE Non_unique = 0;