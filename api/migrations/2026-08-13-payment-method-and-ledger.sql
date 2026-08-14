-- =============================================================
-- Payment method, and a ledger of payment events
-- =============================================================
-- Two gaps this closes.
--
-- 1. NOTHING RECORDS HOW AN ORDER WAS PAID. api/orders.php reads a
--    payment_method from the request and then never uses it, and neither
--    `orders` nor `Bulk_orders` has a column for it. Cash and mobile money
--    are therefore indistinguishable after the fact, which makes reconciling
--    the rider float against the Pesapal settlement impossible.
--
-- 2. EVERY PAYMENT ATTEMPT OVERWRITES THE LAST. payment_status and
--    pesapal_tracking_id are updated in place, so a failed attempt followed by
--    a successful one leaves no trace of the first. When a customer says they
--    were charged twice, there is no record to check.
--
-- ⚠️ BEFORE RUNNING: check the row count.
--       SELECT COUNT(*) FROM orders;
--    ADD COLUMN ... AFTER is NOT an instant operation in MySQL/MariaDB -- only
--    a trailing add is -- so this copies the table. On a large `orders` that
--    means a lock for the duration. If the count is big, delete every `AFTER`
--    clause below (their only effect is column ordering in DESCRIBE) to get
--    ALGORITHM=INSTANT.
--
-- Run from Cloud Shell:
--   gcloud sql connect afamfresh --user=aokwi
--   USE kitchen;
-- then paste this file.
-- =============================================================

-- -------------------------------------------------------------
-- 1. How each order was paid
-- -------------------------------------------------------------
-- Two columns, not one, because they answer different questions.
--
--   payment_method   the AUDIT bucket: is this money sitting in a rider's
--                    pocket, or did it land in the Pesapal settlement account?
--                    Only that distinction has to be reliable, so it is a small
--                    closed set we write ourselves.
--
--   payment_channel  whatever Pesapal called it ("MPESA", "AirtelMoney",
--                    "Visa"). Free text because it is THEIR vocabulary and they
--                    can add to it; an ENUM here would start silently
--                    truncating valid payments the day they add a provider.
--
-- The default is 'unknown', not 'mobile_money', so pre-migration rows are
-- visibly un-attributed rather than quietly asserting something nobody checked.
ALTER TABLE `orders`
  ADD COLUMN `payment_method` ENUM('unknown','cash','mobile_money','card')
      NOT NULL DEFAULT 'unknown'
      COMMENT 'Audit bucket: cash is money in a rider''s hands, the rest settles to Pesapal'
      AFTER `payment_status`,
  ADD COLUMN `payment_channel` VARCHAR(50) DEFAULT NULL
      COMMENT 'Pesapal''s own name for the instrument, verbatim'
      AFTER `payment_method`,
  ADD COLUMN `cash_collected_by` INT(11) DEFAULT NULL
      COMMENT 'riders.id of whoever took the cash; NULL for electronic'
      AFTER `payment_channel`,
  ADD COLUMN `cash_collected_at` DATETIME DEFAULT NULL
      AFTER `cash_collected_by`,
  ADD KEY `idx_orders_payment` (`payment_status`, `payment_method`, `ordertime`);

ALTER TABLE `Bulk_orders`
  ADD COLUMN `payment_method` ENUM('unknown','cash','mobile_money','card')
      NOT NULL DEFAULT 'unknown' AFTER `payment_status`,
  ADD COLUMN `payment_channel` VARCHAR(50) DEFAULT NULL AFTER `payment_method`,
  ADD COLUMN `cash_collected_by` INT(11) DEFAULT NULL AFTER `payment_channel`,
  ADD COLUMN `cash_collected_at` DATETIME DEFAULT NULL AFTER `cash_collected_by`,
  ADD KEY `idx_Bulk_payment` (`payment_status`, `payment_method`, `created_at`);

-- -------------------------------------------------------------
-- 2. Backfill only what is certain
-- -------------------------------------------------------------
-- These two inferences are safe because each has exactly one possible cause:
--
--   pending_cash            only the cash branch of api/payment.php ever
--                           writes that value.
--   has a tracking id       only a Pesapal submitOrder() ever writes one.
--
-- Everything else stays 'unknown'. An un-attributed row is a question someone
-- can answer; a wrongly-attributed one is a wrong number nobody will question.
UPDATE `orders`
   SET `payment_method` = 'cash'
 WHERE `payment_status` = 'pending_cash';

UPDATE `orders`
   SET `payment_method` = 'mobile_money'
 WHERE `payment_method` = 'unknown'
   AND `pesapal_tracking_id` IS NOT NULL
   AND `pesapal_tracking_id` <> '';

UPDATE `Bulk_orders`
   SET `payment_method` = 'cash'
 WHERE `payment_status` = 'pending_cash';

UPDATE `Bulk_orders`
   SET `payment_method` = 'mobile_money'
 WHERE `payment_method` = 'unknown'
   AND `pesapal_tracking_id` IS NOT NULL
   AND `pesapal_tracking_id` <> '';

-- -------------------------------------------------------------
-- 3. The ledger
-- -------------------------------------------------------------
-- Append-only. Nothing in the application ever UPDATEs or DELETEs a row here;
-- a correction is a new row. That is the whole point -- `orders` and
-- `Bulk_orders` already hold current state, and current state is exactly
-- what was being lost every time an attempt overwrote its predecessor.
--
-- No foreign key on order_id, for the same reason vendor_earnings has none:
-- order_id points at one of two tables and a foreign key can reference only
-- one. `source` says which.
CREATE TABLE IF NOT EXISTS `payment_events` (
  `id`            BIGINT(20) NOT NULL AUTO_INCREMENT,
  `source`        ENUM('order','Bulk') NOT NULL,
  `order_id`      INT(11) NOT NULL,
  `event`         VARCHAR(40) NOT NULL
                  COMMENT 'initiated | ipn | verified | cash_selected | cash_collected | reversed | admin_adjust',
  `from_status`   VARCHAR(50) DEFAULT NULL,
  `to_status`     VARCHAR(50) DEFAULT NULL,
  `method`        ENUM('unknown','cash','mobile_money','card') NOT NULL DEFAULT 'unknown',
  `channel`       VARCHAR(50) DEFAULT NULL,
  `amount`        DECIMAL(12,2) DEFAULT NULL
                  COMMENT 'The payable total AS THE SERVER COMPUTED IT, never a client-supplied figure',
  `currency`      VARCHAR(8) NOT NULL DEFAULT 'UGX',
  `tracking_id`   VARCHAR(255) DEFAULT NULL COMMENT 'Pesapal order_tracking_id for this attempt',
  `merchant_ref`  VARCHAR(255) DEFAULT NULL,
  `actor_type`    ENUM('customer','rider','admin','system','pesapal') NOT NULL DEFAULT 'system',
  `actor_id`      INT(11) DEFAULT NULL,
  `raw`           TEXT DEFAULT NULL COMMENT 'Provider payload, truncated; for disputes only',
  `created_at`    TIMESTAMP NOT NULL DEFAULT current_timestamp(),
  PRIMARY KEY (`id`),
  KEY `idx_order` (`source`, `order_id`, `created_at`),
  KEY `idx_tracking` (`tracking_id`),
  KEY `idx_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -------------------------------------------------------------
-- Sanity checks
-- -------------------------------------------------------------
-- Read these. 'unknown' rows with a 'paid' status are the ones that will show
-- up on the reconciliation page's exceptions list: real money whose origin
-- cannot be attributed. That is a true statement about the data, not a bug.
SELECT payment_method, payment_status, COUNT(*) AS n,
       COALESCE(SUM(total_amount),0) AS value
  FROM orders GROUP BY payment_method, payment_status ORDER BY value DESC;

SELECT payment_method, payment_status, COUNT(*) AS n,
       COALESCE(SUM(total_price + delivery_fee),0) AS value
  FROM Bulk_orders GROUP BY payment_method, payment_status ORDER BY value DESC;