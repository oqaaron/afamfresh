-- =============================================================
-- Repurposing customer_addresses into a real saved-address book
-- =============================================================
-- customer_addresses has existed since the original schema but nothing in
-- the backend has ever read or written it (confirmed: zero PHP references
-- anywhere in api/). Android instead keeps saved addresses in
-- SharedPreferences, so they never sync across a customer's devices and are
-- lost on reinstall.
--
-- The existing table shape doesn't fit a saved-address book anyway --
-- raw_address/resolved_via ENUM('api','cache','fallback') looks built for a
-- geocoding cache or audit log, not something a customer manages (no label,
-- recipient name, phone, or default flag). Since nothing depends on the
-- current shape, this migration repurposes the table in place rather than
-- creating a new one.
--
-- The target shape matches app/src/main/java/com/techaus/afamfresh/models/
-- Address.kt field-for-field (see its @SerializedName values) and the
-- endpoint contract already documented in that app's AddressRepository.kt.
--
-- ⚠️ BEFORE RUNNING: check this table is genuinely empty/unused.
--       SELECT COUNT(*) FROM customer_addresses;
--       SELECT COUNT(*) FROM customer_addresses WHERE user_id IS NULL;
--    If either is unexpectedly large, stop and look before proceeding --
--    this migration assumes there is no real customer data here to lose.
--
-- Run from Cloud Shell:
--   gcloud sql connect afamfresh --user=aokwi --database=kitchen
-- then paste this file's statements. It also applies itself automatically
-- via scripts/run-migrations.php on the next deploy -- this manual run is
-- only needed if you want to verify it ahead of that.
-- =============================================================

SELECT COUNT(*) AS rows_before_migration FROM `customer_addresses`;

-- -------------------------------------------------------------
-- 1. A row with no owner cannot exist in an address book -- drop it rather
--    than leave user_id nullable. Expected to affect 0 rows.
-- -------------------------------------------------------------
DELETE FROM `customer_addresses` WHERE `user_id` IS NULL;

-- -------------------------------------------------------------
-- 2. Rename/retype for the address-book shape
-- -------------------------------------------------------------
-- CHANGE COLUMN, not RENAME COLUMN -- works across the MySQL/MariaDB version
-- range this project has run on, not just the newest ones.
ALTER TABLE `customer_addresses`
  CHANGE COLUMN `raw_address` `address_line` VARCHAR(255) NOT NULL;

ALTER TABLE `customer_addresses`
  DROP COLUMN `resolved_via`;

-- -------------------------------------------------------------
-- 3. The columns a saved address actually needs
-- -------------------------------------------------------------
ALTER TABLE `customer_addresses`
  ADD COLUMN `label` VARCHAR(50) NOT NULL DEFAULT '' AFTER `user_id`,
  ADD COLUMN `recipient_name` VARCHAR(100) NOT NULL DEFAULT '' AFTER `label`,
  ADD COLUMN `phone` VARCHAR(20) NOT NULL DEFAULT '' AFTER `recipient_name`,
  ADD COLUMN `is_default` TINYINT(1) NOT NULL DEFAULT 0 AFTER `longitude`,
  ADD COLUMN `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
    ON UPDATE CURRENT_TIMESTAMP AFTER `created_at`;

-- A typed-in address has no coordinates -- only a map-pinned one does.
-- Matches Address.kt's lat/lng being nullable.
ALTER TABLE `customer_addresses`
  MODIFY COLUMN `latitude` DECIMAL(10,8) NULL,
  MODIFY COLUMN `longitude` DECIMAL(11,8) NULL;

-- -------------------------------------------------------------
-- 4. Every address now genuinely belongs to someone, and the database
--    enforces it -- same convention as customer_meal_subscriptions.
-- -------------------------------------------------------------
ALTER TABLE `customer_addresses`
  MODIFY COLUMN `user_id` BIGINT(20) UNSIGNED NOT NULL;

ALTER TABLE `customer_addresses`
  ADD CONSTRAINT `fk_customer_addresses_user`
    FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE;

-- -------------------------------------------------------------
-- Sanity checks
-- -------------------------------------------------------------
SELECT COLUMN_NAME, IS_NULLABLE, COLUMN_TYPE FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME = 'customer_addresses'
 ORDER BY ORDINAL_POSITION;
