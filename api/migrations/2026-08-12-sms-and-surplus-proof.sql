-- =============================================================
-- SMS as a notification channel, and proof photos for surplus
-- =============================================================
-- Two unrelated gaps, both blocked on a column that does not exist yet.
--
-- 1. SMS
--    includes/brevo-sms.php works and is already used by api/rider.php for the
--    out-for-delivery message, which it sends directly. But NotificationManager
--    -- the path every other notification takes -- has no SMS channel, so
--    "order placed" could not be texted.
--
--    notification_queue.channel is an ENUM. MySQL coerces an unknown ENUM value
--    to the empty string rather than erroring, so queueing 'sms' before this
--    migration would write a row with NO channel: the worker matches on
--    'email'/'push'/'both', would match none of them, and the job would be
--    marked sent having done nothing at all. Silent, and invisible in the logs.
--
-- 2. Proof of delivery on surplus orders
--    `orders` has carried delivery_photo all along; surplus_orders never did,
--    so api/rider.php refuses the upload for a surplus job. For a delivery with
--    a 250,000-shilling minimum, no proof is the wrong default.
--
-- Run against Cloud SQL from Cloud Shell:
--   gcloud sql connect afamfresh --user=aokwi --database=kitchen
-- then paste this file's statements.
-- =============================================================

-- -------------------------------------------------------------
-- 1. SMS channel
-- -------------------------------------------------------------
-- 'both' is legacy and left in place: the worker still understands it, and
-- removing a value from an ENUM would rewrite any existing row using it.
ALTER TABLE `notification_queue`
  MODIFY COLUMN `channel` ENUM('email','push','both','sms') NOT NULL;

-- Mirrors the email_/push_ pairs already on this table, so a failed text is
-- diagnosable the same way a failed email is.
ALTER TABLE `user_notifications`
  ADD COLUMN `sms_sent_at` DATETIME DEFAULT NULL AFTER `push_error`,
  ADD COLUMN `sms_error` TEXT DEFAULT NULL AFTER `sms_sent_at`;

-- -------------------------------------------------------------
-- 2. Proof of delivery for surplus orders
-- -------------------------------------------------------------
-- Same three columns `orders` carries, with the same meanings, so
-- includes/rider_dispatch.php can return one shape for both.
ALTER TABLE `surplus_orders`
  ADD COLUMN `delivery_photo` VARCHAR(255) DEFAULT NULL
    COMMENT 'Filename under proof/ in storage; see includes/storage.php',
  ADD COLUMN `delivery_confirmed` TINYINT(1) NOT NULL DEFAULT 0,
  ADD COLUMN `delivery_confirmed_at` DATETIME DEFAULT NULL;

-- -------------------------------------------------------------
-- Sanity checks
-- -------------------------------------------------------------
-- Expect the channel enum to list four values including 'sms'.
SELECT COLUMN_TYPE FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME = 'notification_queue' AND COLUMN_NAME = 'channel';

-- Expect sms_sent_at and sms_error.
SELECT COLUMN_NAME FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME = 'user_notifications' AND COLUMN_NAME LIKE 'sms%';

-- Expect the three delivery_ columns.
SELECT COLUMN_NAME FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME = 'surplus_orders' AND COLUMN_NAME LIKE 'delivery_%';