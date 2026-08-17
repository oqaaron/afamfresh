-- =============================================================
-- Make the Bulk order floors admin-configurable, instead of literals in PHP
-- =============================================================
-- api/api/Bulk-orders.php refused an order on three thresholds written into
-- the code: a minimum order value of UGX 250,000, a minimum of 20 kg on
-- weight-based listings, and a 1000 kg ceiling. Changing any of them meant a
-- deploy, and the third was doubly odd -- `max_weight_kg` has been a column on
-- this table all along, so the code was ignoring a setting an admin could
-- already edit.
--
-- These were also written when every Bulk listing was surplus produce. They
-- now apply to wholesale too, where the SELLER states a minimum order on the
-- listing itself. A wholesaler asking for 5 kg had it silently overridden by
-- the 20 kg floor, with an error message about "bulk/weight-based Bulk items"
-- that named neither their listing nor their own minimum.
--
-- After this, the split is:
--   * wholesale  -> the listing's own min_order_quantity is the authority;
--                   these floors do not apply
--   * surplus    -> these floors apply, exactly as before but tunable
--   * both       -> max_weight_kg still applies; it is a physical limit on
--                   what can be carried, not a commercial rule
--
-- Defaults reproduce the hardcoded values exactly, so applying this changes
-- no behaviour on its own.
-- =============================================================

ALTER TABLE `Bulk_delivery_settings`
  ADD COLUMN `min_order_value` DECIMAL(10,2) NOT NULL DEFAULT 250000.00
    COMMENT 'Minimum value of a SURPLUS Bulk order. Not applied to wholesale.'
    AFTER `max_weight_kg`,
  ADD COLUMN `min_weight_kg` DECIMAL(10,3) NOT NULL DEFAULT 20.000
    COMMENT 'Minimum kg on a weight-based SURPLUS listing. Not applied to wholesale.'
    AFTER `min_order_value`;

-- The table is a single settings row. If one exists it keeps the values the
-- defaults just gave it; this is only here so a fresh install has a row at all
-- rather than falling back to the PHP constants forever.
-- FROM DUAL is not decoration: MySQL rejects a WHERE clause on a SELECT with
-- no FROM, so `SELECT <literals> WHERE NOT EXISTS (...)` is a syntax error
-- there. DUAL is accepted by both MySQL and MariaDB.
INSERT INTO `Bulk_delivery_settings` (base_fee, fee_per_kg, max_weight_kg,
                                      free_delivery_threshold, min_order_value, min_weight_kg)
SELECT 5000.00, 500.00, 1000, 500000.00, 250000.00, 20.000
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `Bulk_delivery_settings` LIMIT 1);

-- Expect one row, with the three limits reading 1000 / 250000.00 / 20.000
-- unless an admin has already changed them.
SELECT id, base_fee, fee_per_kg, max_weight_kg, min_order_value, min_weight_kg,
       free_delivery_threshold
  FROM `Bulk_delivery_settings`
 ORDER BY id;
