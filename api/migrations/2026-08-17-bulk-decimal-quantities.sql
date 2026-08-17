-- =============================================================
-- Store Bulk quantities as decimals, and repair the rows that were truncated
-- =============================================================
-- Bulk produce is ordered in decimal kilograms. Every layer above the database
-- already knows this: CreateBulkOrderRequest.quantity is a Double,
-- api/api/Bulk-orders.php reads it with floatval() ("Can be decimal for kg"),
-- and includes/rider_dispatch.php goes out of its way NOT to round it
-- ("would show a rider 20 for a 20.5 kg load they have to fit on a bike").
--
-- The columns were INT(11) throughout, so none of that survived the write.
--
-- WHAT WENT WRONG, AND WHY IT IS RECOVERABLE
--
-- In Bulk-orders.php the total is computed from the UNtruncated value:
--     $quantity    = floatval($input['quantity']);   // 20.5
--     $total_price = $listing['discounted_price'] * $quantity;
-- and only then is $quantity handed to an INT column, where it becomes 21.
-- So total_price still reflects what was really ordered and quantity does not,
-- which means the true quantity is total_price / discounted_price.
--
-- That only holds while the listing's price is unchanged, so this repair is
-- worth running sooner rather than later. The guards below skip any row where
-- the two disagree by a whole unit or more, which is what a repriced listing
-- looks like — those are left alone for a human.
--
-- ORDER MATTERS: the columns are widened FIRST. Repairing before widening
-- would write the corrected decimal straight back into an INT and truncate it
-- a second time.
--
-- Run against Cloud SQL:
--   mysql -h 127.0.0.1 -P 9470 -u aokwi -p --get-server-public-key kitchen \
--     < migrations/2026-08-17-bulk-decimal-quantities.sql
--
-- NOT idempotent in its repair step, but safely re-runnable: the widening is a
-- no-op the second time, and the UPDATE matches nothing once the rows agree.
-- =============================================================

-- -------------------------------------------------------------
-- 1. Widen. Lossless — every existing integer value is representable.
-- -------------------------------------------------------------
ALTER TABLE `Bulk_listings`
  MODIFY `Bulk_quantity`      DECIMAL(12,3) NOT NULL COMMENT 'Available quantity, decimal for kg',
  MODIFY `remaining_quantity` DECIMAL(12,3) NOT NULL COMMENT 'Quantity still available, decimal for kg';

ALTER TABLE `Bulk_orders`
  MODIFY `quantity` DECIMAL(12,3) NOT NULL COMMENT 'Quantity ordered, decimal for kg';

-- -------------------------------------------------------------
-- 2. Before: which orders were truncated, and by how much.
--    Read this output. Anything with a drift near 1.000 is a rounded-UP row;
--    near 0.000 means it was already whole and nothing was lost.
-- -------------------------------------------------------------
SELECT so.id,
       so.quantity                                        AS stored,
       ROUND(so.total_price / sl.discounted_price, 3)     AS derived,
       ROUND(ABS(so.quantity - (so.total_price / sl.discounted_price)), 3) AS drift
  FROM `Bulk_orders` so
  JOIN `Bulk_listings` sl ON sl.id = so.listing_id
 WHERE sl.discounted_price > 0
   AND ABS(so.quantity - (so.total_price / sl.discounted_price)) > 0.001
 ORDER BY drift DESC;

-- -------------------------------------------------------------
-- 3. Repair, tightly guarded:
--      discounted_price > 0   division is meaningful
--      drift > 0.001          there is something to fix
--      drift < 1.000          it is rounding damage, not a repriced listing.
--                             A listing whose price changed after the order
--                             yields a derived quantity that is wrong by a lot;
--                             those are skipped rather than guessed at.
-- -------------------------------------------------------------
UPDATE `Bulk_orders` so
  JOIN `Bulk_listings` sl ON sl.id = so.listing_id
   SET so.quantity = ROUND(so.total_price / sl.discounted_price, 3)
 WHERE sl.discounted_price > 0
   AND ABS(so.quantity - (so.total_price / sl.discounted_price)) > 0.001
   AND ABS(so.quantity - (so.total_price / sl.discounted_price)) < 1.000;

-- -------------------------------------------------------------
-- 4. After: expect zero rows. Anything still listed was skipped by the
--    < 1.000 guard and needs a person to look at it.
-- -------------------------------------------------------------
SELECT so.id, so.quantity AS stored,
       ROUND(so.total_price / sl.discounted_price, 3) AS derived,
       'SKIPPED — drift of a whole unit or more, check whether the listing was repriced' AS note
  FROM `Bulk_orders` so
  JOIN `Bulk_listings` sl ON sl.id = so.listing_id
 WHERE sl.discounted_price > 0
   AND ABS(so.quantity - (so.total_price / sl.discounted_price)) > 0.001;

-- -------------------------------------------------------------
-- 5. Listing stock drifted too: remaining_quantity was decremented by the
--    truncated figure. VERIFY ONLY — no UPDATE.
--
--    Deliberately not repaired automatically. Unlike an order, a listing's
--    remaining_quantity is also moved by cancellations, restocks and admin
--    corrections, so "quantity minus the sum of live orders" is a plausible
--    expectation rather than a reliable one. Expect small differences; look
--    at anything large before touching it.
-- -------------------------------------------------------------
SELECT sl.id,
       sl.Bulk_quantity,
       sl.remaining_quantity                              AS stored,
       sl.Bulk_quantity - COALESCE(SUM(
           CASE WHEN so.status NOT IN ('cancelled','refunded')
                THEN so.quantity ELSE 0 END), 0)          AS expected_if_no_restocks
  FROM `Bulk_listings` sl
  LEFT JOIN `Bulk_orders` so ON so.listing_id = sl.id
 GROUP BY sl.id, sl.Bulk_quantity, sl.remaining_quantity
HAVING ABS(stored - expected_if_no_restocks) > 0.001;

-- -------------------------------------------------------------
-- 6. Column types, for the record.
-- -------------------------------------------------------------
SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE()
   AND ((TABLE_NAME = 'Bulk_listings' AND COLUMN_NAME IN ('Bulk_quantity','remaining_quantity','min_order_quantity'))
     OR (TABLE_NAME = 'Bulk_orders'   AND COLUMN_NAME = 'quantity'))
 ORDER BY TABLE_NAME, COLUMN_NAME;
