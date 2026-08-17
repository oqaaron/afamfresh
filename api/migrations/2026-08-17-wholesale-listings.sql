-- =============================================================
-- Let Bulk_listings carry a wholesale offer, not just a surplus discount
-- =============================================================
-- The table was built for near-expiry produce: expiry_date is mandatory,
-- original_price is mandatory, and a listing is defined by a discount off
-- that price. A wholesaler sells at a price, not at a markdown from one, and
-- their stock has no expiry to speak of — so there was no way to express a
-- wholesale offer at all.
--
-- Every change here RELAXES a constraint or adds a column with a default, so
-- it is safe to run ahead of the code that uses it: existing surplus listings
-- keep their expiry, their original price and their discount, and every
-- INSERT that api/api/Bulk-listings.php makes today still satisfies the table.
-- Run this first, deploy the PHP second.
--
-- Note what is deliberately NOT changed:
--   * listing_type stays NULL-able with its existing DEFAULT. Only the new
--     'wholesale' value is added. Making it NOT NULL would be a second,
--     unrelated change that could fail on any legacy row holding NULL.
--   * discounted_price keeps its name. It is the price customers pay and is
--     read by Bulk-orders.php, Bulk-quote.php and creditVendorEarnings();
--     renaming it to something wholesale-flavoured would break three call
--     sites to gain nothing.
--
-- DO NOT APPLY THIS BY HAND. scripts/run-migrations.php applies every pending
-- file on the next container start and records it in the schema_migrations
-- ledger. To apply: commit, push, deploy.
--
-- Hand-applying leaves the ledger unaware, so the runner retries the file,
-- fails on `Duplicate column name`, exits 2, and the container never starts --
-- the deploy dies with the previous release still live. This header used to
-- carry a manual mysql command, which is exactly what caused that on
-- 2026-08-17. See DEPLOY.md.
--
-- NOT idempotent: a second run stops at
--   ERROR 1060 (42S21): Duplicate column name 'min_order_quantity'
-- having already re-applied the (harmless, identical) MODIFYs above it.
-- Nothing is damaged, but to check whether it has run rather than find out
-- the hard way:
--   SELECT COUNT(*) FROM information_schema.COLUMNS
--    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'Bulk_listings'
--      AND COLUMN_NAME = 'min_order_quantity';   -- 1 = already applied
-- =============================================================

ALTER TABLE `Bulk_listings`
  -- Additive: the three existing values are untouched and keep their meaning.
  MODIFY `listing_type` ENUM('goodie_bag','final_days','bulk','wholesale')
    DEFAULT 'goodie_bag',

  -- Wholesale stock is not near-expiry. GET must also stop filtering these
  -- out — see the (expiry_date IS NULL OR expiry_date > NOW()) change in
  -- api/api/Bulk-listings.php.
  MODIFY `expiry_date` DATETIME NULL DEFAULT NULL
    COMMENT 'When the produce expires. NULL for wholesale, which does not expire',

  -- Optional RRP for a "save X% vs retail" line. A wholesaler quoting only
  -- their own price has no retail figure to give.
  MODIFY `original_price` DECIMAL(10,2) NULL DEFAULT NULL
    COMMENT 'Retail reference price. NULL when the seller quotes wholesale only',

  -- Was NOT NULL with no default and a "(30-70%)" comment. The floor moved to
  -- 10 (see includes/bulk_listing_rules.php) and a wholesale listing carries
  -- 0, computed for display only when an RRP is given.
  MODIFY `discount_percent` DECIMAL(5,2) NOT NULL DEFAULT 0.00
    COMMENT 'Surplus discount, within SURPLUS_DISCOUNT_MIN/MAX. 0 for wholesale',

  -- DECIMAL even though Bulk_quantity/remaining_quantity are still INT: this
  -- column is new, so it can be the right type from the start rather than
  -- inheriting a bug. Those two are widened separately — see the decimal
  -- quantity work, which repairs live rows and needs its own migration.
  ADD COLUMN `min_order_quantity` DECIMAL(12,3) NOT NULL DEFAULT 1.000
    COMMENT 'Wholesale MOQ in the listing unit. Ignored for surplus listings'
    AFTER `remaining_quantity`;

-- -------------------------------------------------------------
-- Sanity checks
-- -------------------------------------------------------------

-- Expect: listing_type includes 'wholesale'; expiry_date and original_price
-- are YES for nullable; min_order_quantity present as decimal(12,3).
SELECT COLUMN_NAME, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_TYPE
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME = 'Bulk_listings'
   AND COLUMN_NAME IN ('listing_type','expiry_date','original_price',
                       'discount_percent','min_order_quantity')
 ORDER BY ORDINAL_POSITION;

-- Expect: no existing listing lost its expiry or its price, and none is
-- wholesale yet (nothing can create one until the PHP ships).
SELECT COUNT(*) AS listings,
       SUM(expiry_date IS NULL)      AS null_expiry,
       SUM(original_price IS NULL)   AS null_original_price,
       SUM(listing_type = 'wholesale') AS wholesale_rows,
       MIN(discount_percent)         AS lowest_discount
  FROM `Bulk_listings`;
