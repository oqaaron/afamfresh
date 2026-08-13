-- =============================================================
-- Give surplus_orders the same customer-facing columns `orders` already has
-- =============================================================
-- `orders` has carried customer_rating, customer_feedback, rating_speed,
-- rating_professionalism, rating_packaging, emoji_reaction and
-- delivery_confirmed_photo since schema.sql was written — and completed_at,
-- which nothing has ever set. Nothing writes any of them: there has never
-- been a customer-facing confirm-and-rate step, only the rider's own
-- delivery_photo/delivery_confirmed pair (set by api/rider.php's
-- upload_proof action, which is the rider attesting delivery, not the
-- customer).
--
-- This is the other half: the customer's own acknowledgement. completed_at
-- is reused for exactly the purpose its name and NULL default already
-- implied — it is set the moment the customer confirms, `orders` and
-- `surplus_orders` alike.
--
-- surplus_orders never had the rating columns at all, so it gets the same
-- seven `orders` already carries, mirrored exactly.
--
-- Run against Cloud SQL from Cloud Shell:
--   gcloud sql connect afamfresh --user=aokwi --database=kitchen
-- then paste this file's statements.
-- =============================================================

ALTER TABLE `surplus_orders`
  ADD COLUMN `customer_rating` INT(11) DEFAULT NULL,
  ADD COLUMN `customer_feedback` TEXT DEFAULT NULL,
  ADD COLUMN `rating_speed` TINYINT(1) DEFAULT NULL COMMENT 'Rating for delivery speed (1-5)',
  ADD COLUMN `rating_professionalism` TINYINT(1) DEFAULT NULL COMMENT 'Rating for rider professionalism (1-5)',
  ADD COLUMN `rating_packaging` TINYINT(1) DEFAULT NULL COMMENT 'Rating for packaging quality (1-5)',
  ADD COLUMN `emoji_reaction` VARCHAR(50) DEFAULT NULL COMMENT 'Emoji reaction (thumbs_up, heart, smile, fire, rocket)',
  ADD COLUMN `delivery_confirmed_photo` TEXT DEFAULT NULL COMMENT 'Filename under proof/ in storage; the CUSTOMER''s own confirmation photo, separate from the rider''s delivery_photo',
  ADD COLUMN `completed_at` TIMESTAMP NULL DEFAULT NULL COMMENT 'Set when the customer confirms receipt, not when the rider marks it delivered';

-- -------------------------------------------------------------
-- Sanity check
-- -------------------------------------------------------------
SELECT COLUMN_NAME FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME = 'surplus_orders'
   AND COLUMN_NAME IN ('customer_rating', 'customer_feedback', 'rating_speed',
                        'rating_professionalism', 'rating_packaging',
                        'emoji_reaction', 'delivery_confirmed_photo', 'completed_at');
