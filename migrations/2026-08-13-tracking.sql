-- =============================================================
-- Making order_tracking_logs readable, and correct
-- =============================================================
-- The table has been written to since the dispatch work and read by nothing.
-- Three things have to change before it can be read safely.
--
-- 1. A FOREIGN KEY TO `orders`. fk_tracking_order constrains order_id to
--    orders.orderid, so a SURPLUS breadcrumb is rejected outright. Every
--    surplus location post since dispatch shipped has been failing silently.
--    Nobody noticed because nothing reads the table.
--
-- 2. NO SOURCE COLUMN. api/rider.php writes the order_id from
--    rider_assignments without recording which table it came from. Shop order
--    41 and surplus order 41 both exist, so their trails would interleave in
--    one stream and a customer tracking either would be shown the other
--    rider's position.
--
-- 3. NO RIDER_ID. When a delivery is reassigned there is no way to tell whose
--    trail is whose, and no way to answer "where was rider 6 at 14:20".
--
-- Run from Cloud Shell:
--   gcloud sql connect afamfresh --user=aokwi
--   USE kitchen;
-- =============================================================

ALTER TABLE `order_tracking_logs` DROP FOREIGN KEY `fk_tracking_order`;

ALTER TABLE `order_tracking_logs`
  ADD COLUMN `source` ENUM('order','surplus') NOT NULL DEFAULT 'order'
      COMMENT 'Which table order_id points at'
      AFTER `order_id`,
  ADD COLUMN `rider_id` INT(11) DEFAULT NULL AFTER `source`,
  ADD COLUMN `assignment_id` INT(11) DEFAULT NULL
      COMMENT 'rider_assignments.id — unique across both sources, so a trail survives reassignment'
      AFTER `rider_id`,
  -- Quality metadata. Without accuracy, a 2km GPS wobble is indistinguishable
  -- from the rider actually moving 2km, and the customer's map jumps.
  ADD COLUMN `accuracy_m` SMALLINT UNSIGNED DEFAULT NULL AFTER `lng`,
  ADD COLUMN `speed_mps` DECIMAL(5,2) DEFAULT NULL AFTER `accuracy_m`,
  ADD COLUMN `heading_deg` SMALLINT DEFAULT NULL AFTER `speed_mps`,
  ADD KEY `idx_trail` (`source`, `order_id`, `recorded_at`),
  ADD KEY `idx_rider_time` (`rider_id`, `recorded_at`);

-- Every existing row is a shop order — surplus writes were being rejected — so
-- the 'order' default is correct for all of them and no backfill is needed.

-- -------------------------------------------------------------
-- The cached route for a job
-- -------------------------------------------------------------
-- One route per delivery, fetched once. Without this the customer's tracking
-- screen would re-request the same Google route on every poll: at a 10s cadence
-- over a 30-minute delivery that is 180 billable calls for geometry that does
-- not change.
ALTER TABLE `rider_assignments`
  ADD COLUMN `route_polyline` MEDIUMTEXT DEFAULT NULL
      COMMENT 'Google encoded polyline, pickup -> drop-off',
  ADD COLUMN `route_distance_km` DECIMAL(8,2) DEFAULT NULL,
  ADD COLUMN `route_duration_min` DECIMAL(8,1) DEFAULT NULL,
  ADD COLUMN `route_fetched_at` DATETIME DEFAULT NULL;

-- Sanity checks.
SELECT COUNT(*) AS trail_rows, SUM(source = 'order') AS shop FROM `order_tracking_logs`;

SELECT COLUMN_NAME FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'order_tracking_logs'
   AND COLUMN_NAME IN ('source','rider_id','assignment_id','accuracy_m');

-- Should return nothing: the foreign key is gone.
SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'order_tracking_logs'
   AND CONSTRAINT_TYPE = 'FOREIGN KEY';