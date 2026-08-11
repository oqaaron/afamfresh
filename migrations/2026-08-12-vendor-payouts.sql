-- =============================================================
-- Vendor earnings and payouts
-- =============================================================
-- vendor_earnings and vendor_payout_requests already exist and are correctly
-- shaped. What was missing is that nothing ever wrote to them: a single UPDATE
-- against vendor_earnings and no INSERT anywhere, so vendors have never earned
-- anything. This migration only adds what the crediting logic needs.
--
-- Two changes.
--
-- 1. vendor_earnings.source
--    The table was built around `orders` (the existing query joins o.orderid),
--    but vendor sales happen through surplus_orders. order_id alone is
--    therefore ambiguous -- id 7 could be either table. `source` says which.
--
-- 2. A unique key across (source, order_id, vendor_id)
--    This is money. Without it, re-running the credit -- a retried request, a
--    double-tapped button, a status set to delivered twice -- pays the vendor
--    again. The unique key makes the second attempt an error the code can
--    catch rather than a silent duplicate payment.
--
-- Run against Cloud SQL:
--   mysql -h 127.0.0.1 -P 9470 -u aokwi -p --get-server-public-key kitchen \
--     < migrations/2026-08-12-vendor-payouts.sql
-- =============================================================

-- Defaults to 'order' so any pre-existing row keeps its original meaning.
-- New credits pass 'surplus' explicitly.
ALTER TABLE `vendor_earnings`
  ADD COLUMN `source` ENUM('order','surplus') NOT NULL DEFAULT 'order'
    COMMENT 'Which table order_id points at'
    AFTER `order_id`;

ALTER TABLE `vendor_earnings`
  ADD UNIQUE KEY `uniq_earning_per_order` (`source`, `order_id`, `vendor_id`);

-- Payment state for surplus orders. `orders` has had these three all along;
-- surplus_orders carried no record of whether money ever changed hands.
ALTER TABLE `surplus_orders`
  ADD COLUMN `payment_status` VARCHAR(50) NOT NULL DEFAULT 'pending'
    COMMENT 'pending | paid | failed | cancelled — mirrors orders.payment_status',
  ADD COLUMN `payment_captured_at` DATETIME DEFAULT NULL,
  ADD COLUMN `pesapal_tracking_id` VARCHAR(255) DEFAULT NULL,
  ADD KEY `idx_payment_status` (`payment_status`);

-- Sanity checks.
SELECT COUNT(*) AS earnings_rows, SUM(source = 'order') AS legacy_order_rows
  FROM `vendor_earnings`;
SELECT COUNT(*) AS surplus_orders, SUM(payment_status = 'pending') AS unpaid
  FROM `surplus_orders`;