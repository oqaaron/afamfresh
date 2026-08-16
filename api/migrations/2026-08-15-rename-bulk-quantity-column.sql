-- =============================================================
-- Fix missed column rename from "Rename Surplus to Bulk" (94568c8, 2026-08-14)
-- =============================================================
-- That commit renamed Bulk_listings.surplus_quantity -> Bulk_quantity in
-- schema.sql and every PHP file, but the ALTER TABLE was never run against
-- Cloud SQL. Every INSERT into Bulk_listings since then has failed with:
--   SQLSTATE[42S22]: Column not found: 1054 Unknown column 'Bulk_quantity'
--   in 'field list'
-- (api/api/Bulk-listings.php, confirmed in production logs 2026-08-15.)
--
-- The table itself was already renamed on prod (surplus_listings ->
-- Bulk_listings), and every other table in that commit checks out — this
-- column was the one thing missed.
--
-- Run against Cloud SQL:
--   mysql -h 127.0.0.1 -P 9470 -u aokwi -p --get-server-public-key kitchen \
--     < migrations/2026-08-15-rename-bulk-quantity-column.sql
-- =============================================================

ALTER TABLE `Bulk_listings`
  CHANGE COLUMN `surplus_quantity` `Bulk_quantity`
    INT(11) NOT NULL COMMENT 'Available quantity';
