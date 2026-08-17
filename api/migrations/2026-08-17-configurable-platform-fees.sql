-- =============================================================
-- Put the remaining hardcoded fee components under admin control
-- =============================================================
-- The configuration page already edits the service fee and the insurance rate,
-- and both fee calculators read them. Three components did NOT come from
-- anywhere an admin can reach:
--
--   processing fee   PROCESSING_FEE_PERCENT, referenced by
--                    includes/delivery-fee.php and includes/Bulk_delivery_fee.php
--                    as `defined(...) ? ... : 1.8`. It is defined NOWHERE in the
--                    codebase, so every order on both channels has always been
--                    charged the 1.8 fallback. This is the significant one: it
--                    is a percentage of order value, charged on every shop and
--                    Bulk order, and it was invisible on the settings page while
--                    admin/dashboard.php reported the revenue it produced.
--
--   min delivery fee MIN_DELIVERY_FEE, same pattern, falls back to 0. Harmless
--                    as a no-op floor, but equally unreachable.
--
--   Bulk per-km      Bulk_RATE_PER_KM (900) and Bulk_MAX_FEE (120000), PHP
--   and Bulk cap     constants in includes/Bulk_delivery_fee.php. Their own
--                    comment says: "move them into the table when an admin needs
--                    to tune them without a deploy." That is now.
--
-- Every default below reproduces the constant it replaces exactly, so applying
-- this changes no price by itself. The PHP keeps its `?? default` fallbacks, so
-- a row that is somehow missing a column still prices orders the same way.
--
-- Note processing_percent lands on delivery_pricing rather than
-- Bulk_delivery_settings even though both channels use it: it is the same
-- business cost wherever it appears, exactly like service_fee and
-- insurance_percent, which already live there and are already shared. Splitting
-- it would let the two channels drift apart silently.
-- =============================================================

ALTER TABLE `delivery_pricing`
  ADD COLUMN `processing_percent` DECIMAL(5,2) NOT NULL DEFAULT 1.80
    COMMENT 'Processing fee as % of order value. Applied to BOTH shop and Bulk.'
    AFTER `insurance_percent`,
  ADD COLUMN `min_delivery_fee` DECIMAL(10,2) NOT NULL DEFAULT 0.00
    COMMENT 'Floor under the computed shop delivery fee. 0 = no floor.'
    AFTER `processing_percent`;

ALTER TABLE `Bulk_delivery_settings`
  ADD COLUMN `rate_per_km` DECIMAL(10,2) NOT NULL DEFAULT 900.00
    COMMENT 'Bulk carriage rate per km, vendor -> customer.'
    AFTER `fee_per_kg`,
  ADD COLUMN `max_fee` DECIMAL(10,2) NOT NULL DEFAULT 120000.00
    COMMENT 'Cap on the whole Bulk delivery fee, applied after every component.'
    AFTER `rate_per_km`;

-- Both tables are single settings rows. FROM DUAL because MySQL rejects a
-- WHERE clause on a SELECT with no FROM.
INSERT INTO `delivery_pricing` (service_fee, insurance_percent, processing_percent,
                                min_delivery_fee, free_delivery_threshold,
                                free_delivery_distance_threshold, medium_order_threshold,
                                medium_order_rate, low_order_rate,
                                profit_percent_enabled, profit_percent)
SELECT 1000.00, 0.90, 1.80, 0.00, 100000.00, 10, 50000.00, 375.00, 700.00, 1, 16.00
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `delivery_pricing` LIMIT 1);

-- Expect processing_percent 1.80 and min_delivery_fee 0.00 unless already tuned.
SELECT id, service_fee, insurance_percent, processing_percent, min_delivery_fee
  FROM `delivery_pricing`
 ORDER BY id;

-- Expect rate_per_km 900.00 and max_fee 120000.00.
SELECT id, base_fee, fee_per_kg, rate_per_km, max_fee, max_weight_kg,
       min_order_value, min_weight_kg
  FROM `Bulk_delivery_settings`
 ORDER BY id;
