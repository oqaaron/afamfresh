-- =============================================================
-- Gate paid Bulk-order cancellations behind admin approval
-- =============================================================
-- Cancelling a PAID Bulk order today just flips a status column and tells
-- the customer "you will be refunded" -- nothing anywhere actually calls
-- Pesapal to reverse the charge. Worse, the vendor-facing endpoint
-- (api/Bulk-orders.php PUT) lets a vendor set status straight to
-- 'refunded' unilaterally, with no admin involved and no stock returned.
--
-- Fix: a vendor cancelling their own PAID order now only REQUESTS it --
-- status moves to 'cancellation_requested', nothing else changes -- until
-- an admin approves (which actually cancels, restocks, and requests a real
-- Pesapal refund) or denies (which restores the order exactly where it was).
-- An admin cancelling directly still executes immediately; the admin
-- clicking it IS the approval. See includes/Bulk_payment.php's
-- cancelBulkOrder() / requestBulkOrderCancellation().
--
-- Run from Cloud Shell:
--   gcloud sql connect afamfresh --user=aokwi --database=kitchen
-- then paste this file. Applies itself automatically via
-- scripts/run-migrations.php on the next deploy either way.
-- =============================================================

ALTER TABLE `Bulk_orders`
  MODIFY `status` ENUM('pending','confirmed','processing','ready','delivered',
                        'cancelled','refunded','cancellation_requested')
      DEFAULT 'pending',
  ADD COLUMN `cancellation_reason` TEXT DEFAULT NULL,
  ADD COLUMN `status_before_cancellation` VARCHAR(50) DEFAULT NULL
      COMMENT 'so a denied request restores exactly where the order was',
  ADD COLUMN `cancellation_requested_by` INT(11) DEFAULT NULL
      COMMENT 'vendors.user_id who asked, for audit',
  ADD COLUMN `cancellation_requested_at` DATETIME DEFAULT NULL;

-- payment_events.actor_type didn't include 'vendor' -- recordPaymentEvent()
-- silently downgrades any value outside its whitelist to 'system', which
-- would have quietly erased "the vendor requested this" from the ledger for
-- every cancellation-request/refund event this feature writes.
ALTER TABLE `payment_events`
  MODIFY `actor_type` ENUM('customer','rider','vendor','admin','system','pesapal')
      NOT NULL DEFAULT 'system';

-- -------------------------------------------------------------
-- Sanity check
-- -------------------------------------------------------------
SELECT COLUMN_NAME, IS_NULLABLE, COLUMN_TYPE FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME = 'Bulk_orders'
   AND COLUMN_NAME IN ('status', 'cancellation_reason', 'status_before_cancellation',
                        'cancellation_requested_by', 'cancellation_requested_at')
 ORDER BY ORDINAL_POSITION;

SELECT COLUMN_TYPE FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payment_events' AND COLUMN_NAME = 'actor_type';
