-- =============================================================
-- Vendor coordinates, for Bulk delivery pricing
-- =============================================================
-- A Bulk load is collected from the VENDOR and driven to the customer, but
-- vendors carry only a free-text `location` ("Nakawa market", "near the
-- stage"). There is nothing to measure a distance from, so the Bulk
-- delivery fee has been weight-only: base + per-kg, blind to how far the goods
-- actually travel.
--
-- That prices the wrong thing. A tonne of matooke moved 3km and the same tonne
-- moved 40km cost the same to the customer and pay the rider the same, while
-- costing the business wildly different amounts.
--
-- These two columns let a vendor pin their premises once, after which
-- includes/Bulk_delivery_fee.php can charge for the real journey.
--
-- NULLABLE on purpose. Every existing vendor has no pin, and their listings
-- must keep selling in the meantime -- the fee calculation falls back to the
-- office coordinates and flags the quote as estimated, rather than refusing
-- the order.
--
-- Run against Cloud SQL from Cloud Shell:
--   gcloud sql connect afamfresh --user=aokwi
--   USE kitchen;
-- then paste the statements below.
-- =============================================================

-- DECIMAL(10,7) matches orders.dest_lat/dest_lng: seven decimal places is
-- roughly 1cm, far past what a hand-placed pin is worth, and avoids the
-- rounding drift a FLOAT would introduce into distance sums.
ALTER TABLE `vendors`
  ADD COLUMN `lat` DECIMAL(10,7) DEFAULT NULL
    COMMENT 'Pickup latitude, set by the vendor pinning their premises'
    AFTER `location`,
  ADD COLUMN `lng` DECIMAL(10,7) DEFAULT NULL
    COMMENT 'Pickup longitude'
    AFTER `lat`;

-- What the customer was actually charged, itemised, so a fee can be explained
-- months later without recomputing it against pricing that has since changed.
-- Bulk_orders.delivery_fee_breakdown already exists and holds JSON; this is
-- the distance that fed it, kept as a column because it is worth querying.
ALTER TABLE `Bulk_orders`
  ADD COLUMN `delivery_distance_km` DECIMAL(8,2) DEFAULT NULL
    COMMENT 'Vendor to customer, km. NULL when neither end could be located.'
    AFTER `delivery_fee_breakdown`;

-- Sanity checks.
SELECT COUNT(*) AS vendors, SUM(lat IS NOT NULL) AS pinned FROM `vendors`;

SELECT COLUMN_NAME FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'vendors'
   AND COLUMN_NAME IN ('lat','lng');