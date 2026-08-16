-- =============================================================
-- Role-based admin accounts, and an audit trail
-- =============================================================
-- Every admin account has been identical until now -- one `admin` table,
-- one login flow, `$_SESSION['admin_logged_in']` the only check anywhere.
-- This adds a role column (the pre-existing account(s) default to
-- 'super_admin', so nobody's access narrows on deploy), a soft-deactivate
-- flag instead of ever hard-deleting an admin, and an append-only log of
-- who did what -- modelled directly on payment_events, the codebase's own
-- established pattern for "an actor, a before/after-shaped record".
--
-- New roles are added later purely in code (includes/admin_permissions.php's
-- ADMIN_ROLES array) -- this migration never needs to run again just to add
-- a role.
--
-- Run from Cloud Shell:
--   gcloud sql connect afamfresh --user=aokwi --database=kitchen
-- then paste this file. Applies itself automatically via
-- scripts/run-migrations.php on the next deploy either way.
-- =============================================================

ALTER TABLE `admin`
  ADD COLUMN `role` VARCHAR(50) NOT NULL DEFAULT 'super_admin'
    COMMENT 'Key into includes/admin_permissions.php''s ADMIN_ROLES',
  ADD COLUMN `created_by` INT(11) DEFAULT NULL
    COMMENT 'admin.id who onboarded this account; NULL for the pre-existing admin(s)',
  ADD COLUMN `is_active` TINYINT(1) NOT NULL DEFAULT 1
    COMMENT 'Deactivated, not deleted -- admin_action_log keeps a name to attach their past actions to';

CREATE TABLE IF NOT EXISTS `admin_action_log` (
  `id`          BIGINT(20) NOT NULL AUTO_INCREMENT,
  `admin_id`    INT(11) DEFAULT NULL,
  `admin_name`  VARCHAR(100) NOT NULL COMMENT 'Denormalized snapshot -- survives the admin later being deactivated/renamed',
  `action`      VARCHAR(100) NOT NULL COMMENT 'e.g. order.status_changed, product.price_updated, admin.created',
  `target_type` VARCHAR(50) DEFAULT NULL COMMENT 'e.g. order, product, admin, vendor, config',
  `target_id`   VARCHAR(50) DEFAULT NULL COMMENT 'varchar, not int -- some targets (a config key) are not numeric',
  `description` TEXT DEFAULT NULL COMMENT 'Human-readable summary for the audit viewer',
  `ip_address`  VARCHAR(45) DEFAULT NULL,
  `created_at`  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_admin` (`admin_id`, `created_at`),
  KEY `idx_action` (`action`, `created_at`),
  KEY `idx_target` (`target_type`, `target_id`),
  CONSTRAINT `fk_audit_admin` FOREIGN KEY (`admin_id`) REFERENCES `admin` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -------------------------------------------------------------
-- Sanity checks
-- -------------------------------------------------------------
SELECT id, UserName, role, is_active, created_by FROM `admin`;

SELECT COLUMN_NAME, IS_NULLABLE, COLUMN_TYPE FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'admin_action_log'
 ORDER BY ORDINAL_POSITION;
