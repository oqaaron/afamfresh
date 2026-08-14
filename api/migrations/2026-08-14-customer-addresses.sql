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
-- current shape, this migration replaces the table outright.
--
-- REWRITTEN as DROP + CREATE rather than a chain of ALTERs: the first
-- attempt at this migration used ALTER TABLE and hit two problems --
-- (1) `user_id BIGINT UNSIGNED` doesn't match `users.id INT(11)`, which
-- MySQL/MariaDB refuses as an incompatible FK type (error 3780), and
-- (2) DDL auto-commits per statement, so the failure on the last ALTER
-- (adding the FK) left every earlier ALTER in this file already applied --
-- retrying the same file from the top then failed immediately, because
-- `raw_address` no longer existed to rename. A DROP + CREATE is safe to
-- retry from any of those partial states, or from the original shape, or
-- from nothing at all.
--
-- The target shape matches app/src/main/java/com/techaus/afamfresh/models/
-- Address.kt field-for-field (see its @SerializedName values) and the
-- endpoint contract already documented in that app's AddressRepository.kt.
--
-- ⚠️ BEFORE RUNNING: check this table is genuinely empty/unused.
--       SELECT COUNT(*) FROM customer_addresses;
--    If it's unexpectedly large, stop and look before proceeding -- this
--    migration assumes there is no real customer data here to lose.
--
-- Run from Cloud Shell:
--   gcloud sql connect afamfresh --user=aokwi --database=kitchen
-- then paste this file's statements. It also applies itself automatically
-- via scripts/run-migrations.php on the next deploy -- this manual run is
-- only needed if you want to verify it ahead of that.
-- =============================================================

SELECT COUNT(*) AS rows_before_migration FROM `customer_addresses`;

DROP TABLE IF EXISTS `customer_addresses`;

CREATE TABLE `customer_addresses` (
  `id` BIGINT(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  -- INT(11), not BIGINT -- matches users.id exactly, required for the FK below.
  `user_id` INT(11) NOT NULL,
  `label` VARCHAR(50) NOT NULL DEFAULT '',
  `recipient_name` VARCHAR(100) NOT NULL DEFAULT '',
  `phone` VARCHAR(20) NOT NULL DEFAULT '',
  `address_line` VARCHAR(255) NOT NULL,
  `area` VARCHAR(100) NOT NULL,
  -- Nullable: a typed-in address has no coordinates, only a map-pinned one
  -- does. Matches Address.kt's lat/lng being nullable.
  `latitude` DECIMAL(10,8) NULL,
  `longitude` DECIMAL(11,8) NULL,
  `is_default` TINYINT(1) NOT NULL DEFAULT 0,
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`),
  CONSTRAINT `fk_customer_addresses_user`
    FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -------------------------------------------------------------
-- Sanity checks
-- -------------------------------------------------------------
SELECT COLUMN_NAME, IS_NULLABLE, COLUMN_TYPE FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME = 'customer_addresses'
 ORDER BY ORDINAL_POSITION;
