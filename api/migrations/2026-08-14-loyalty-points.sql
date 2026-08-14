-- =============================================================
-- A real loyalty-points system, replacing a display-only stub
-- =============================================================
-- Before this, `users.loyalty_points` was read correctly everywhere (profile,
-- the app) but written by exactly one place: an admin typing a raw number
-- into admin/edit-user.php, with no record of who changed it, when, or why.
-- Nothing ever earned or redeemed a point automatically.
--
-- A second table, `loyalty_points` (user_id/points/updated_at), has existed
-- alongside the column this whole time and has never been read or written
-- by any application code. It is dropped at the bottom of this file. It is
-- NOT the same thing as the `users.loyalty_points` column, which stays.
--
-- WHY A LEDGER
--
-- `users.loyalty_points` remains the fast-read cached balance -- nothing
-- that only wants "how many points does this person have right now" should
-- have to sum a ledger. But every event that MOVES that balance (an order
-- earning points, a redemption being settled, an admin correcting a
-- mistake) now also writes a row here, append-only, so a balance is always
-- explainable after the fact. Modelled directly on payment_events
-- (migrations/2026-08-13-payment-method-and-ledger.sql) -- same
-- append-only philosophy, same actor_type/actor_id shape.
--
-- ⚠️ BEFORE RUNNING: check the row count.
--       SELECT COUNT(*) FROM orders;
--    ADD COLUMN ... AFTER is NOT an instant operation in MySQL/MariaDB --
--    only a trailing add is -- so section 3 below copies the table if it's
--    large. If the count is big, delete the `AFTER small_order_surcharge`
--    clause (its only effect is column ordering in DESCRIBE) to get
--    ALGORITHM=INSTANT. Bulk_orders is much smaller and unlikely to
--    matter either way.
--
-- Run from Cloud Shell:
--   gcloud sql connect afamfresh --user=aokwi --database=kitchen
-- then paste this file's statements.
-- =============================================================

-- -------------------------------------------------------------
-- 1. The ledger
-- -------------------------------------------------------------
-- UNIQUE(source, order_id, event) is the idempotency guard the earn and
-- settle functions rely on -- MySQL lets multiple NULLs through a unique
-- index, so admin_adjust rows (no order) never collide with each other,
-- while a real order can have at most one 'earned' row and at most one
-- 'redeemed' row. Same shape rider_earnings/vendor_earnings already use for
-- the identical dual-write-path risk (a Bulk order can reach 'delivered'
-- via two different files).
CREATE TABLE IF NOT EXISTS `loyalty_ledger` (
  `id`            BIGINT(20) NOT NULL AUTO_INCREMENT,
  `user_id`       INT(11) NOT NULL,
  `source`        ENUM('order','Bulk') DEFAULT NULL
                  COMMENT 'NULL for admin_adjust rows, which have no order',
  `order_id`      INT(11) DEFAULT NULL,
  `event`         ENUM('earned','redeemed','admin_adjust') NOT NULL,
  `points_delta`  INT(11) NOT NULL COMMENT 'Signed: +earned, -redeemed, +/- admin_adjust',
  `balance_after` INT(11) NOT NULL COMMENT 'users.loyalty_points snapshot immediately after this write',
  `reason`        VARCHAR(255) DEFAULT NULL,
  `actor_type`    ENUM('customer','rider','admin','system') NOT NULL DEFAULT 'system',
  `actor_id`      INT(11) DEFAULT NULL,
  `created_at`    TIMESTAMP NOT NULL DEFAULT current_timestamp(),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_order_event` (`source`, `order_id`, `event`),
  KEY `idx_user` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -------------------------------------------------------------
-- 2. Loyalty settings -- single row, same convention as delivery_pricing
-- -------------------------------------------------------------
-- Pre-seeded, unlike delivery_pricing (which tolerates being empty and
-- falls back to hardcoded defaults) -- a redemption is real money leaving
-- the business, and that shouldn't silently rest on a guessed default.
CREATE TABLE IF NOT EXISTS `loyalty_settings` (
  `id`                         INT(11) NOT NULL AUTO_INCREMENT,
  `earn_rate_ugx_per_point`    DECIMAL(10,2) NOT NULL DEFAULT 1000.00
        COMMENT 'UGX of goods value that earns 1 point -- lower is more generous',
  `redeem_value_ugx_per_point` DECIMAL(10,2) NOT NULL DEFAULT 50.00
        COMMENT 'UGX discount per point redeemed',
  `min_redeemable_points`      INT(11) NOT NULL DEFAULT 100
        COMMENT 'Smallest redemption an order may request; below this, redeems 0',
  `max_redeem_percent`         DECIMAL(5,2) NOT NULL DEFAULT 30.00
        COMMENT 'Cap on how much of one order''s goods value points may cover',
  `updated_at`                 TIMESTAMP NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO `loyalty_settings` (`id`) VALUES (1);

-- -------------------------------------------------------------
-- 3. Where a redemption request is recorded
-- -------------------------------------------------------------
-- Recorded at order-creation time, when the discount is already known and
-- applied to the charge -- but the point DEBIT itself is deferred to
-- payment-confirmed time (settleLoyaltyRedemption in includes/loyalty.php),
-- so an abandoned or failed order never locks up points nobody actually
-- spent.
--
-- Shop orders: the discount is folded straight into total_amount at
-- creation, since nothing else derives a payout FROM total_amount.
--
-- Bulk orders are different: total_price feeds the VENDOR's payout and
-- delivery_fee feeds the RIDER's carriage pay (creditVendorEarnings() /
-- BulkRiderFeeFor()) -- reducing either to apply a loyalty discount would
-- silently underpay whichever one it came out of. So Bulk orders get a
-- SEPARATE loyalty_discount column instead, left out of both total_price and
-- delivery_fee, and BulkPayableTotal() (includes/Bulk_payment.php) is
-- updated to subtract it from what Pesapal is actually asked to charge.
ALTER TABLE `orders`
  ADD COLUMN `points_redeemed` INT(11) NOT NULL DEFAULT 0 AFTER `small_order_surcharge`;

ALTER TABLE `Bulk_orders`
  ADD COLUMN `points_redeemed` INT(11) NOT NULL DEFAULT 0 AFTER `delivery_fee`,
  ADD COLUMN `loyalty_discount` DECIMAL(10,2) NOT NULL DEFAULT 0.00 AFTER `points_redeemed`;

-- -------------------------------------------------------------
-- 4. Drop the orphaned table
-- -------------------------------------------------------------
-- ⚠️ Read this before running the DROP: expect 0, or a number you recognise
-- as genuinely unused test data. This is NOT users.loyalty_points -- that
-- column is untouched by this migration.
SELECT COUNT(*) AS orphaned_loyalty_points_rows FROM `loyalty_points`;

DROP TABLE IF EXISTS `loyalty_points`;

-- -------------------------------------------------------------
-- Sanity checks
-- -------------------------------------------------------------
SELECT COUNT(*) AS loyalty_settings_rows FROM `loyalty_settings`;

SELECT COLUMN_NAME FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME IN ('orders', 'Bulk_orders')
   AND COLUMN_NAME = 'points_redeemed';