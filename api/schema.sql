-- AfamFresh schema. Generated from the local MariaDB by
-- scripts/export-schema.sh -- edit that, not this file.
--
-- Structure for all tables; rows only for catalogue/pricing/UI-copy
-- tables. No customer, order, rider or admin data is included.
--
-- Normalised on export for Cloud SQL:
--   DEFAULT (curdate()) -> DEFAULT (curdate())
--                      (MariaDB allows a bare expression default;
--                       MySQL 8.0 rejects it unless parenthesised.
--                       current_timestamp() is special-cased on
--                       TIMESTAMP columns, so it is left alone.)
--   MyISAM -> InnoDB   (Cloud SQL does not support MyISAM)
--   latin1 -> utf8mb4  (13 legacy tables; verified lossless --
--                       only 8 values were non-ASCII, all cp1252
--                       typography that maps cleanly to Unicode)
SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS=0;
-- ============ STRUCTURE ============
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;
DROP TABLE IF EXISTS `admin`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `admin` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `UserName` varchar(100) NOT NULL,
  `Password` varchar(100) NOT NULL,
  `updationDate` timestamp NOT NULL DEFAULT current_timestamp(),
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=8 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `app_config`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `app_config` (
  `config_key` varchar(50) NOT NULL,
  `config_value` text NOT NULL,
  `updated_at` timestamp NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  PRIMARY KEY (`config_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `banner`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `banner` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `bnimg` varchar(200) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=6 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `capacity_logs`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `capacity_logs` (
  `log_id` int(11) NOT NULL AUTO_INCREMENT,
  `hub_id` int(11) NOT NULL,
  `temperature_zone` varchar(50) DEFAULT NULL,
  `slot_id` varchar(50) DEFAULT NULL,
  `change_type` enum('booking_created','booking_confirmed','booking_cancelled','booking_completed','manual_adjustment') NOT NULL,
  `quantity_change` decimal(10,2) NOT NULL COMMENT 'Negative for booking, positive for release',
  `quantity_before` decimal(10,2) DEFAULT NULL,
  `quantity_after` decimal(10,2) DEFAULT NULL,
  `available_capacity_before` decimal(10,2) DEFAULT NULL,
  `available_capacity_after` decimal(10,2) DEFAULT NULL,
  `booking_id` int(11) DEFAULT NULL,
  `reason` text DEFAULT NULL COMMENT 'For manual adjustments',
  `timestamp` datetime DEFAULT current_timestamp(),
  PRIMARY KEY (`log_id`),
  KEY `idx_hub_id` (`hub_id`),
  KEY `idx_timestamp` (`timestamp`),
  KEY `idx_change_type` (`change_type`),
  KEY `idx_booking_id` (`booking_id`),
  CONSTRAINT `fk_capacity_logs_booking` FOREIGN KEY (`booking_id`) REFERENCES `storage_bookings` (`booking_id`) ON DELETE SET NULL,
  CONSTRAINT `fk_capacity_logs_hub` FOREIGN KEY (`hub_id`) REFERENCES `storage_hubs` (`hub_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `category`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `category` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `categry` varchar(100) NOT NULL,
  `cateimg` varchar(500) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=24 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `customer_addresses`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `customer_addresses` (
  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) unsigned DEFAULT NULL,
  `raw_address` varchar(255) NOT NULL,
  `area` varchar(100) NOT NULL,
  `latitude` decimal(10,8) NOT NULL,
  `longitude` decimal(11,8) NOT NULL,
  `resolved_via` enum('api','cache','fallback') NOT NULL,
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_area` (`area`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `customer_meal_subscriptions`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `customer_meal_subscriptions` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `user_id` int(11) NOT NULL,
  `meal_plan_id` int(11) NOT NULL,
  `start_date` date NOT NULL,
  `end_date` date DEFAULT NULL,
  `delivery_days` varchar(50) DEFAULT NULL COMMENT 'Monday,Wednesday,Friday',
  `delivery_time` varchar(50) DEFAULT '12:00',
  `status` enum('active','paused','cancelled') DEFAULT 'active',
  `next_delivery_date` date DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  PRIMARY KEY (`id`),
  KEY `user_id` (`user_id`),
  KEY `meal_plan_id` (`meal_plan_id`),
  CONSTRAINT `customer_meal_subscriptions_ibfk_1` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
  CONSTRAINT `customer_meal_subscriptions_ibfk_2` FOREIGN KEY (`meal_plan_id`) REFERENCES `meal_plans` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `delivery_pricing`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `delivery_pricing` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `profit_percent_enabled` tinyint(1) DEFAULT 1 COMMENT '1 = enabled, 0 = disabled',
  `profit_percent` decimal(5,2) DEFAULT 16.00 COMMENT 'Profit percentage to apply to delivery fee calculation',
  `service_fee` decimal(10,2) DEFAULT 1000.00,
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  `updated_at` timestamp NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  `free_delivery_threshold` decimal(10,2) DEFAULT 100000.00 COMMENT 'Order amount for free delivery (all areas)',
  `free_delivery_distance_threshold` int(11) DEFAULT 10 COMMENT 'Distance in km for free delivery on medium orders',
  `medium_order_threshold` decimal(10,2) DEFAULT 50000.00 COMMENT 'Order amount threshold for medium pricing tier',
  `medium_order_rate` decimal(10,2) DEFAULT 375.00 COMMENT 'Rate per km for medium orders above distance threshold',
  `low_order_rate` decimal(10,2) DEFAULT 700.00 COMMENT 'Rate per km for low orders',
  `insurance_percent` decimal(5,2) DEFAULT 0.90 COMMENT 'Insurance percentage applied to all orders',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `delivery_slots`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `delivery_slots` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `slot_label` varchar(50) NOT NULL,
  `slot_start` time NOT NULL,
  `slot_end` time NOT NULL,
  `max_orders` int(11) DEFAULT 10,
  `is_active` tinyint(1) DEFAULT 1,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `feedback`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `feedback` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `fmsg` text NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `geocode_cache`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `geocode_cache` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `address` varchar(255) DEFAULT NULL,
  `lat` decimal(10,7) DEFAULT NULL,
  `lng` decimal(10,7) DEFAULT NULL,
  `source` varchar(50) DEFAULT 'nominatim',
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  `updated_at` timestamp NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  PRIMARY KEY (`id`),
  UNIQUE KEY `address` (`address`),
  KEY `idx_address` (`address`),
  KEY `idx_updated_at` (`updated_at`)
) ENGINE=InnoDB AUTO_INCREMENT=14 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `gift_hamper_items`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `gift_hamper_items` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `hamper_id` int(11) NOT NULL,
  `item_id` int(11) NOT NULL,
  `quantity` int(11) NOT NULL DEFAULT 1,
  PRIMARY KEY (`id`),
  KEY `hamper_id` (`hamper_id`),
  KEY `item_id` (`item_id`),
  CONSTRAINT `fk_hamper_items_hamper` FOREIGN KEY (`hamper_id`) REFERENCES `gift_hampers` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_hamper_items_item` FOREIGN KEY (`item_id`) REFERENCES `items` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=61 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `gift_hampers`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `gift_hampers` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `name` varchar(200) NOT NULL,
  `description` text NOT NULL,
  `price` decimal(10,2) NOT NULL,
  `image` varchar(500) NOT NULL,
  `is_active` tinyint(1) DEFAULT 1,
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `items`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `items` (
  `id` int(100) NOT NULL AUTO_INCREMENT,
  `name` varchar(100) NOT NULL,
  `category` varchar(100) NOT NULL,
  `description` text NOT NULL,
  `price` varchar(50) NOT NULL,
  `quantity` varchar(11) NOT NULL,
  `quantitytype` varchar(50) NOT NULL,
  `image` varchar(200) NOT NULL,
  `discount` decimal(5,2) DEFAULT 0.00,
  `homepage` varchar(10) NOT NULL DEFAULT 'NO',
  `offer` varchar(50) NOT NULL DEFAULT 'NO',
  `freshness_date` date DEFAULT NULL,
  `stock_qty` int(11) NOT NULL DEFAULT 999,
  `weekly_deal` enum('YES','NO') NOT NULL DEFAULT 'NO',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=99 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `loyalty_points`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `loyalty_points` (
  `user_id` int(11) NOT NULL,
  `points` int(11) NOT NULL DEFAULT 0,
  `updated_at` timestamp NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `meal_plan_items`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `meal_plan_items` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `meal_plan_id` int(11) NOT NULL,
  `item_id` int(11) NOT NULL,
  `quantity` int(11) DEFAULT 1,
  `day_number` int(11) DEFAULT NULL COMMENT 'Day of week (1-7) for daily plans',
  `meal_type` enum('breakfast','lunch','dinner','snack') DEFAULT 'lunch',
  PRIMARY KEY (`id`),
  KEY `meal_plan_id` (`meal_plan_id`),
  KEY `item_id` (`item_id`),
  CONSTRAINT `meal_plan_items_ibfk_1` FOREIGN KEY (`meal_plan_id`) REFERENCES `meal_plans` (`id`) ON DELETE CASCADE,
  CONSTRAINT `meal_plan_items_ibfk_2` FOREIGN KEY (`item_id`) REFERENCES `items` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `meal_plans`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `meal_plans` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `name` varchar(255) NOT NULL,
  `slug` varchar(255) DEFAULT NULL,
  `description` text DEFAULT NULL,
  `short_description` varchar(255) DEFAULT NULL,
  `price` decimal(10,2) NOT NULL,
  `discounted_price` decimal(10,2) DEFAULT NULL,
  `image` varchar(255) DEFAULT NULL,
  `duration` enum('weekly','biweekly','monthly') DEFAULT 'weekly',
  `meals_per_day` int(11) DEFAULT 3,
  `servings_per_meal` int(11) DEFAULT 1,
  `calories_range` varchar(50) DEFAULT NULL,
  `dietary_tags` varchar(255) DEFAULT NULL,
  `is_active` tinyint(1) DEFAULT 1,
  `is_featured` tinyint(1) DEFAULT 0,
  `display_order` int(11) DEFAULT 0,
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  `updated_at` timestamp NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  PRIMARY KEY (`id`),
  UNIQUE KEY `slug` (`slug`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `message`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `message` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `cname` varchar(100) NOT NULL,
  `cmobile` varchar(50) NOT NULL,
  `cmsg` text NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `nominatim_cache`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `nominatim_cache` (
  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `search_query` varchar(255) NOT NULL,
  `latitude` decimal(10,8) NOT NULL,
  `longitude` decimal(11,8) NOT NULL,
  `display_name` text DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  `updated_at` timestamp NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  PRIMARY KEY (`id`),
  UNIQUE KEY `search_query` (`search_query`),
  KEY `idx_search_query` (`search_query`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `notification`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `notification` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `title` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `description` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `type` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT 'system' COMMENT 'Notification type: system, order, vendor, user, etc.',
  `user_id` int(11) DEFAULT NULL COMMENT 'Target specific user (null for all admins)',
  `link` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'Link to related page/action',
  `is_read` tinyint(1) DEFAULT 0 COMMENT 'Whether notification has been read',
  `created_at` timestamp NOT NULL DEFAULT current_timestamp() COMMENT 'When notification was created',
  `priority` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT 'normal' COMMENT 'Priority level: low, normal, high, urgent',
  PRIMARY KEY (`id`),
  KEY `idx_type` (`type`),
  KEY `idx_is_read` (`is_read`),
  KEY `idx_created_at` (`created_at`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB AUTO_INCREMENT=61 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `notification_queue`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `notification_queue` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `notification_id` int(11) NOT NULL,
  `channel` enum('email','push','both') NOT NULL,
  `payload` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL CHECK (json_valid(`payload`)),
  `status` enum('pending','processing','sent','failed') DEFAULT 'pending',
  `retries` tinyint(4) DEFAULT 0,
  `max_retries` tinyint(4) DEFAULT 3,
  `next_attempt_at` datetime DEFAULT NULL,
  `created_at` datetime DEFAULT current_timestamp(),
  `updated_at` datetime DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  PRIMARY KEY (`id`),
  KEY `idx_status_attempt` (`status`,`next_attempt_at`),
  KEY `notification_id` (`notification_id`),
  CONSTRAINT `notification_queue_ibfk_1` FOREIGN KEY (`notification_id`) REFERENCES `user_notifications` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `order_items`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `order_items` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `order_id` int(11) NOT NULL,
  `product_id` int(11) NOT NULL,
  `product_name` varchar(500) NOT NULL,
  `quantity` int(11) NOT NULL DEFAULT 1,
  `price` decimal(10,2) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `order_id` (`order_id`)
) ENGINE=InnoDB AUTO_INCREMENT=489 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `order_surplus_items`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `order_surplus_items` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `order_id` int(11) NOT NULL,
  `surplus_listing_id` int(11) NOT NULL,
  `product_name` varchar(255) DEFAULT NULL,
  `quantity` decimal(10,2) NOT NULL,
  `unit_price` decimal(10,2) NOT NULL,
  `total_price` decimal(10,2) NOT NULL,
  `weight_kg` decimal(10,2) NOT NULL,
  `pickup_only` tinyint(1) DEFAULT 0,
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  PRIMARY KEY (`id`),
  KEY `idx_order` (`order_id`),
  CONSTRAINT `order_surplus_items_ibfk_1` FOREIGN KEY (`order_id`) REFERENCES `orders` (`orderid`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `order_tracking_logs`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `order_tracking_logs` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `order_id` int(11) NOT NULL,
  `lat` decimal(10,8) NOT NULL,
  `lng` decimal(11,8) NOT NULL,
  `recorded_at` timestamp NOT NULL DEFAULT current_timestamp(),
  PRIMARY KEY (`id`),
  KEY `idx_order_id` (`order_id`),
  KEY `idx_recorded` (`recorded_at`),
  CONSTRAINT `fk_tracking_order` FOREIGN KEY (`order_id`) REFERENCES `orders` (`orderid`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=334 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `orders`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `orders` (
  `orderid` int(11) NOT NULL AUTO_INCREMENT,
  `user_id` int(11) DEFAULT NULL,
  `fname` varchar(100) NOT NULL,
  `lname` varchar(100) NOT NULL,
  `mobile` varchar(25) NOT NULL,
  `area` varchar(100) NOT NULL,
  `address` varchar(500) NOT NULL,
  `status` varchar(50) DEFAULT 'Received',
  `current_status` varchar(50) DEFAULT 'pending',
  `location` varchar(255) DEFAULT NULL,
  `delivery_person` varchar(100) DEFAULT NULL,
  `estimated_delivery` datetime DEFAULT NULL,
  `status_history` mediumtext DEFAULT NULL,
  `scheduled_delivery_date` date DEFAULT NULL,
  `scheduled_delivery_slot` varchar(50) DEFAULT NULL,
  `ordertime` timestamp NOT NULL DEFAULT current_timestamp(),
  `delivery_assigned_at` datetime DEFAULT NULL,
  `reminder_sent` tinyint(1) DEFAULT 0,
  `cancelled_at` datetime DEFAULT NULL,
  `delivered_at` datetime DEFAULT NULL,
  `delivery_confirmed` tinyint(1) DEFAULT 0,
  `delivery_confirmed_at` datetime DEFAULT NULL,
  `completed_at` timestamp NULL DEFAULT NULL,
  `customer_rating` int(11) DEFAULT NULL,
  `customer_feedback` text DEFAULT NULL,
  `delivery_photo` varchar(255) DEFAULT NULL,
  `payment_reminder_sent` tinyint(1) DEFAULT 0,
  `delivery_lat` decimal(10,8) DEFAULT NULL,
  `delivery_lng` decimal(11,8) DEFAULT NULL,
  `delivery_address` text DEFAULT NULL,
  `dest_lat` decimal(10,8) DEFAULT NULL,
  `dest_lng` decimal(11,8) DEFAULT NULL,
  `delivery_fee` decimal(10,2) DEFAULT 0.00,
  `mileage_fee` decimal(10,2) DEFAULT NULL COMMENT 'Distance-based component of delivery_fee, persisted at order creation so rider earnings can be computed later without recomputing the quote.',
  `service_fee` decimal(10,2) DEFAULT 0.00,
  `insurance_fee` decimal(10,2) DEFAULT 0.00,
  `processing_fee` decimal(10,2) DEFAULT 0.00,
  `small_order_surcharge` decimal(10,2) DEFAULT 0.00,
  `rating_speed` tinyint(1) DEFAULT NULL COMMENT 'Rating for delivery speed (1-5)',
  `rating_professionalism` tinyint(1) DEFAULT NULL COMMENT 'Rating for rider professionalism (1-5)',
  `rating_packaging` tinyint(1) DEFAULT NULL COMMENT 'Rating for packaging quality (1-5)',
  `emoji_reaction` varchar(50) DEFAULT NULL COMMENT 'Emoji reaction (thumbs_up, heart, smile, fire, rocket)',
  `delivery_confirmed_photo` text DEFAULT NULL COMMENT 'Additional confirmation photo from customer',
  `total_amount` decimal(12,2) DEFAULT NULL,
  `payment_authorization_id` varchar(255) DEFAULT NULL,
  `pesapal_tracking_id` varchar(255) DEFAULT NULL,
  `payment_status` varchar(50) DEFAULT 'pending',
  `payment_authorized_at` datetime DEFAULT NULL,
  `payment_captured_at` datetime DEFAULT NULL,
  PRIMARY KEY (`orderid`),
  KEY `idx_orders_status` (`status`),
  KEY `idx_orders_ordertime` (`ordertime`),
  KEY `idx_orders_sched_date` (`scheduled_delivery_date`)
) ENGINE=InnoDB AUTO_INCREMENT=500935 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `orderslist`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `orderslist` (
  `id` int(50) NOT NULL AUTO_INCREMENT,
  `orderid` varchar(50) NOT NULL,
  `itemname` varchar(500) NOT NULL,
  `itemquantity` varchar(100) NOT NULL,
  `itemquantitytype` varchar(50) NOT NULL,
  `Mquantity` varchar(50) NOT NULL,
  `itemprice` varchar(30) NOT NULL,
  `itemtotal` varchar(50) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=201 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `product_ratings`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `product_ratings` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `item_id` int(11) NOT NULL,
  `user_id` int(11) NOT NULL,
  `rating` tinyint(4) NOT NULL CHECK (`rating` between 1 and 5),
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_user_item` (`user_id`,`item_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `rider_assignments`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `rider_assignments` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `order_id` int(11) NOT NULL,
  `rider_id` int(11) NOT NULL,
  `assigned_at` datetime DEFAULT current_timestamp(),
  `delivered_at` datetime DEFAULT NULL,
  `status` varchar(50) DEFAULT 'assigned',
  `completed_at` timestamp NULL DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_order_id` (`order_id`),
  KEY `idx_rider_id` (`rider_id`),
  KEY `idx_status` (`status`),
  CONSTRAINT `fk_assignment_order` FOREIGN KEY (`order_id`) REFERENCES `orders` (`orderid`) ON DELETE CASCADE ON UPDATE CASCADE,
  CONSTRAINT `fk_assignment_rider` FOREIGN KEY (`rider_id`) REFERENCES `riders` (`id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=48 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `rider_earnings`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `rider_earnings` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `rider_id` int(11) NOT NULL,
  `order_id` int(11) NOT NULL,
  `mileage_fee` decimal(10,2) NOT NULL COMMENT 'Distance-based fee the rider earns from, before commission.',
  `commission_rate` decimal(5,2) NOT NULL COMMENT 'Percentage retained by AfamFresh, e.g. 10.00.',
  `commission_amount` decimal(10,2) NOT NULL,
  `net_earnings` decimal(10,2) NOT NULL COMMENT 'mileage_fee - commission_amount: what the rider actually earns.',
  `is_estimated` tinyint(1) DEFAULT 0 COMMENT '1 when mileage_fee was recomputed from stored coordinates rather than the fee actually charged at order time (pre-2026-08 orders had no persisted breakdown).',
  `is_paid` tinyint(1) DEFAULT 0,
  `paid_at` datetime DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uniq_order` (`order_id`),
  KEY `idx_rider_id` (`rider_id`),
  KEY `idx_is_paid` (`is_paid`),
  CONSTRAINT `fk_rider_earnings_order` FOREIGN KEY (`order_id`) REFERENCES `orders` (`orderid`) ON DELETE CASCADE,
  CONSTRAINT `fk_rider_earnings_rider` FOREIGN KEY (`rider_id`) REFERENCES `riders` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `rider_payout_requests`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `rider_payout_requests` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `rider_id` int(11) NOT NULL,
  `amount` decimal(10,2) NOT NULL,
  `status` enum('pending','approved','rejected','paid') DEFAULT 'pending',
  `requested_at` timestamp NOT NULL DEFAULT current_timestamp(),
  `processed_at` timestamp NULL DEFAULT NULL,
  `notes` text DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_rider_id` (`rider_id`),
  CONSTRAINT `fk_rider_payout_rider` FOREIGN KEY (`rider_id`) REFERENCES `riders` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `riders`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `riders` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `user_id` int(11) DEFAULT NULL,
  `name` varchar(100) NOT NULL,
  `phone` varchar(20) NOT NULL,
  `email` varchar(100) DEFAULT NULL,
  `password` varchar(255) NOT NULL,
  `vehicle_type` varchar(50) DEFAULT NULL,
  `current_lat` decimal(10,8) DEFAULT NULL,
  `current_lng` decimal(11,8) DEFAULT NULL,
  `status` enum('online','offline') DEFAULT 'offline',
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  `last_location_update` datetime DEFAULT NULL,
  `avg_rating` decimal(3,2) DEFAULT 0.00 COMMENT 'Average overall rating',
  `total_ratings` int(11) DEFAULT 0 COMMENT 'Total number of ratings received',
  `avg_speed` decimal(3,2) DEFAULT 0.00 COMMENT 'Average speed rating',
  `avg_professionalism` decimal(3,2) DEFAULT 0.00 COMMENT 'Average professionalism rating',
  `avg_packaging` decimal(3,2) DEFAULT 0.00 COMMENT 'Average packaging rating',
  PRIMARY KEY (`id`),
  UNIQUE KEY `idx_phone` (`phone`),
  UNIQUE KEY `idx_email` (`email`),
  UNIQUE KEY `user_id` (`user_id`),
  KEY `idx_rider_ratings` (`avg_rating`,`total_ratings`)
) ENGINE=InnoDB AUTO_INCREMENT=9 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `role_requests`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `role_requests` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `user_id` int(11) NOT NULL,
  `requested_role` enum('vendor','rider','wholesaler') NOT NULL,
  `status` enum('pending','approved','rejected') DEFAULT 'pending',
  `admin_notes` text DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  `processed_at` timestamp NULL DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `user_id` (`user_id`,`requested_role`),
  CONSTRAINT `role_requests_ibfk_1` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=8 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `scheduler_log`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `scheduler_log` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `task` varchar(100) NOT NULL,
  `details` text DEFAULT NULL,
  `affected_rows` int(11) DEFAULT 0,
  `run_at` timestamp NOT NULL DEFAULT current_timestamp(),
  PRIMARY KEY (`id`),
  KEY `idx_run_at` (`run_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `shipping`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `shipping` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `cost` int(20) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `site_settings`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `site_settings` (
  `setting_key` varchar(100) NOT NULL,
  `setting_value` text NOT NULL,
  `updated_at` timestamp NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  `promo_bg_color` varchar(20) DEFAULT '#0f3d1f' COMMENT 'Banner background color (hex)',
  `promo_bg_gradient_start` varchar(20) DEFAULT '#0f3d1f' COMMENT 'Gradient start color (hex)',
  `promo_bg_gradient_end` varchar(20) DEFAULT '#2d8a44' COMMENT 'Gradient end color (hex)',
  `promo_accent_color` varchar(20) DEFAULT '#FFC107' COMMENT 'Accent color for highlights (hex)',
  `promo_text_color` varchar(20) DEFAULT '#ffffff' COMMENT 'Main text color (hex)',
  `promo_cta_text` varchar(100) DEFAULT 'Shop the Deal' COMMENT 'CTA button text',
  `promo_cta_link` varchar(255) DEFAULT 'products.php' COMMENT 'CTA button link',
  `promo_enabled` tinyint(1) DEFAULT 1 COMMENT '1 = enabled, 0 = disabled',
  `promo_countdown_enabled` tinyint(1) DEFAULT 1 COMMENT '1 = show countdown, 0 = hide',
  `promo_countdown_date` date DEFAULT NULL COMMENT 'Countdown end date (YYYY-MM-DD)',
  `promo_custom_image` varchar(255) DEFAULT NULL COMMENT 'Custom banner image path',
  PRIMARY KEY (`setting_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `storage_bookings`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `storage_bookings` (
  `booking_id` int(11) NOT NULL AUTO_INCREMENT,
  `vendor_id` int(11) NOT NULL,
  `hub_id` int(11) NOT NULL,
  `produce_type` varchar(100) NOT NULL,
  `produce_category` enum('leafy_greens','root_vegetables','fruits','herbs','tomatoes','peppers','other') DEFAULT 'other',
  `quantity_kg` decimal(10,2) NOT NULL,
  `temperature_zone` varchar(50) DEFAULT NULL,
  `container_type` enum('boxes','kg','baskets') DEFAULT 'kg',
  `number_of_containers` int(11) DEFAULT 1,
  `start_date` datetime NOT NULL,
  `end_date` datetime DEFAULT NULL,
  `duration_days` int(11) NOT NULL,
  `slot_id` varchar(50) DEFAULT NULL,
  `booking_status` enum('pending','confirmed','active','completed','cancelled','expired') DEFAULT 'pending',
  `qr_code` varchar(100) DEFAULT NULL,
  `total_cost` decimal(12,2) DEFAULT NULL,
  `payment_status` enum('pending','paid','refunded','failed') DEFAULT 'pending',
  `payment_method` enum('MTN_MobileMoney','Airtel_MobileMoney','Cash','Card') DEFAULT NULL,
  `payment_transaction_id` varchar(100) DEFAULT NULL,
  `payment_paid_at` datetime DEFAULT NULL,
  `check_in_time` datetime DEFAULT NULL COMMENT 'When vendor actually dropped off produce',
  `check_out_time` datetime DEFAULT NULL COMMENT 'When vendor picked up produce',
  `notes` text DEFAULT NULL,
  `cancellation_reason` text DEFAULT NULL,
  `cancelled_by` varchar(50) DEFAULT NULL COMMENT 'vendor, admin, system',
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  `updated_at` timestamp NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  PRIMARY KEY (`booking_id`),
  UNIQUE KEY `qr_code` (`qr_code`),
  KEY `idx_vendor_id` (`vendor_id`),
  KEY `idx_hub_id` (`hub_id`),
  KEY `idx_booking_status` (`booking_status`),
  KEY `idx_payment_status` (`payment_status`),
  KEY `idx_created_at` (`created_at`),
  KEY `idx_start_date` (`start_date`),
  KEY `idx_end_date` (`end_date`),
  KEY `idx_qr_code` (`qr_code`),
  CONSTRAINT `fk_storage_bookings_hub` FOREIGN KEY (`hub_id`) REFERENCES `storage_hubs` (`hub_id`) ON DELETE CASCADE,
  CONSTRAINT `fk_storage_bookings_vendor` FOREIGN KEY (`vendor_id`) REFERENCES `vendors` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `storage_hubs`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `storage_hubs` (
  `hub_id` int(11) NOT NULL AUTO_INCREMENT,
  `hub_name` varchar(100) NOT NULL,
  `location` varchar(255) NOT NULL,
  `address` text DEFAULT NULL,
  `total_capacity_kg` int(11) NOT NULL,
  `available_capacity_kg` int(11) NOT NULL,
  `latitude` decimal(10,7) DEFAULT NULL,
  `longitude` decimal(10,7) DEFAULT NULL,
  `temperature_zones` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '{"leafy_greens": "2-4°C", "root_vegetables": "8-10°C"}' CHECK (json_valid(`temperature_zones`)),
  `operating_hours` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '{"open": "06:00", "close": "20:00", "days": ["mon","tue","wed","thu","fri","sat"]}' CHECK (json_valid(`operating_hours`)),
  `contact_phone` varchar(25) DEFAULT NULL,
  `contact_email` varchar(255) DEFAULT NULL,
  `status` enum('active','maintenance','closed') DEFAULT 'active',
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  `updated_at` timestamp NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  PRIMARY KEY (`hub_id`),
  KEY `idx_status` (`status`),
  KEY `idx_location` (`location`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `storage_pricing`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `storage_pricing` (
  `pricing_id` int(11) NOT NULL AUTO_INCREMENT,
  `produce_category` enum('leafy_greens','root_vegetables','fruits','herbs','tomatoes','peppers','other') NOT NULL,
  `duration_tier` enum('short_term','medium','long') NOT NULL COMMENT 'short: 1-3 days, medium: 4-7 days, long: 8+ days',
  `price_per_kg_per_day` decimal(10,2) NOT NULL,
  `minimum_charge` decimal(10,2) DEFAULT 0.00 COMMENT 'Minimum charge per booking',
  `currency` varchar(3) DEFAULT 'UGX',
  `is_active` tinyint(1) DEFAULT 1,
  `effective_from` date DEFAULT (curdate()),
  `effective_to` date DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  `updated_at` timestamp NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  PRIMARY KEY (`pricing_id`),
  UNIQUE KEY `unique_category_tier_active` (`produce_category`,`duration_tier`,`is_active`),
  KEY `idx_produce_category` (`produce_category`),
  KEY `idx_duration_tier` (`duration_tier`),
  KEY `idx_is_active` (`is_active`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `surplus_analytics`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `surplus_analytics` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `vendor_id` int(11) NOT NULL,
  `date` date NOT NULL,
  `total_listings` int(11) DEFAULT 0,
  `total_sold` int(11) DEFAULT 0,
  `total_expired` int(11) DEFAULT 0,
  `total_revenue` decimal(15,2) DEFAULT 0.00,
  `total_discount_given` decimal(15,2) DEFAULT 0.00,
  `food_waste_saved_kg` decimal(10,2) DEFAULT 0.00,
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  PRIMARY KEY (`id`),
  UNIQUE KEY `vendor_id` (`vendor_id`,`date`),
  KEY `idx_date` (`date`),
  CONSTRAINT `surplus_analytics_ibfk_1` FOREIGN KEY (`vendor_id`) REFERENCES `vendors` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `surplus_delivery_settings`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `surplus_delivery_settings` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `base_fee` decimal(10,2) DEFAULT 5000.00,
  `fee_per_kg` decimal(10,2) DEFAULT 500.00,
  `max_weight_kg` int(11) DEFAULT 1000,
  `free_delivery_threshold` decimal(10,2) DEFAULT 500000.00,
  `updated_at` timestamp NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `surplus_discount_rules`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `surplus_discount_rules` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `name` varchar(255) NOT NULL,
  `min_discount_percent` decimal(5,2) NOT NULL COMMENT 'Minimum discount (e.g., 30%)',
  `max_discount_percent` decimal(5,2) NOT NULL COMMENT 'Maximum discount (e.g., 70%)',
  `days_before_expiry` int(11) NOT NULL COMMENT 'Apply discount if expiry within X days',
  `condition_multiplier` decimal(3,2) DEFAULT 1.00 COMMENT 'Multiplier based on condition (excellent=1.0, good=0.9, fair=0.8)',
  `is_active` tinyint(1) DEFAULT 1,
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  `updated_at` timestamp NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  PRIMARY KEY (`id`),
  KEY `idx_is_active` (`is_active`)
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `surplus_listings`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `surplus_listings` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `vendor_id` int(11) NOT NULL,
  `product_id` int(11) NOT NULL,
  `original_price` decimal(10,2) NOT NULL COMMENT 'Original price before discount',
  `discount_percent` decimal(5,2) NOT NULL COMMENT 'Discount percentage (30-70%)',
  `discounted_price` decimal(10,2) NOT NULL COMMENT 'Price after discount',
  `surplus_quantity` int(11) NOT NULL COMMENT 'Available quantity',
  `remaining_quantity` int(11) NOT NULL COMMENT 'Quantity still available',
  `expiry_date` datetime NOT NULL COMMENT 'When the surplus produce expires',
  `listing_type` enum('goodie_bag','final_days','bulk') DEFAULT 'goodie_bag',
  `status` enum('pending','approved','rejected','cancelled') DEFAULT 'pending',
  `description` text DEFAULT NULL COMMENT 'Additional details about the surplus',
  `condition_rating` enum('excellent','good','fair') DEFAULT 'good' COMMENT 'Quality condition',
  `pickup_only` tinyint(1) DEFAULT 0 COMMENT 'If true, only pickup allowed (no delivery)',
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  `updated_at` timestamp NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  `weight_per_unit_kg` decimal(10,2) DEFAULT 1.00,
  `is_weight_based` tinyint(1) DEFAULT 1,
  `admin_notes` text DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `product_id` (`product_id`),
  KEY `idx_vendor_id` (`vendor_id`),
  KEY `idx_status` (`status`),
  KEY `idx_expiry_date` (`expiry_date`),
  KEY `idx_listing_type` (`listing_type`),
  KEY `idx_discount_percent` (`discount_percent`),
  KEY `idx_created_at` (`created_at`),
  CONSTRAINT `surplus_listings_ibfk_1` FOREIGN KEY (`vendor_id`) REFERENCES `vendors` (`id`) ON DELETE CASCADE,
  CONSTRAINT `surplus_listings_ibfk_2` FOREIGN KEY (`product_id`) REFERENCES `items` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `surplus_matching_preferences`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `surplus_matching_preferences` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `user_id` int(11) NOT NULL,
  `preferred_categories` varchar(255) DEFAULT NULL COMMENT 'Comma-separated category IDs',
  `max_discount_percent` decimal(5,2) DEFAULT 70.00,
  `min_quality_rating` enum('excellent','good','fair') DEFAULT 'good',
  `pickup_only` tinyint(1) DEFAULT 0,
  `notification_enabled` tinyint(1) DEFAULT 1,
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  `updated_at` timestamp NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  PRIMARY KEY (`id`),
  UNIQUE KEY `user_id` (`user_id`),
  CONSTRAINT `surplus_matching_preferences_ibfk_1` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `surplus_notifications`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `surplus_notifications` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `user_id` int(11) NOT NULL,
  `listing_id` int(11) NOT NULL,
  `message` text NOT NULL,
  `is_read` tinyint(1) DEFAULT 0,
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  PRIMARY KEY (`id`),
  KEY `listing_id` (`listing_id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_is_read` (`is_read`),
  KEY `idx_created_at` (`created_at`),
  CONSTRAINT `surplus_notifications_ibfk_1` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
  CONSTRAINT `surplus_notifications_ibfk_2` FOREIGN KEY (`listing_id`) REFERENCES `surplus_listings` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `surplus_orders`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `surplus_orders` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `listing_id` int(11) NOT NULL,
  `user_id` int(11) NOT NULL,
  `quantity` int(11) NOT NULL COMMENT 'Quantity ordered',
  `total_price` decimal(10,2) NOT NULL COMMENT 'Total price after discount',
  `status` enum('pending','confirmed','processing','ready','delivered','cancelled','refunded') DEFAULT 'pending',
  `delivery_address` text DEFAULT NULL,
  `delivery_area` varchar(100) DEFAULT NULL,
  `delivery_lat` decimal(10,7) DEFAULT NULL,
  `delivery_lng` decimal(10,7) DEFAULT NULL,
  `delivery_fee` decimal(10,2) DEFAULT 0.00,
  `pickup_code` varchar(10) DEFAULT NULL COMMENT 'Code for pickup orders',
  `scheduled_delivery_date` date DEFAULT NULL,
  `scheduled_delivery_slot` varchar(50) DEFAULT NULL,
  `order_notes` text DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  `updated_at` timestamp NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  `confirmed_at` datetime DEFAULT NULL,
  `delivered_at` datetime DEFAULT NULL,
  `total_weight_kg` decimal(10,2) DEFAULT 0.00,
  `delivery_fee_breakdown` text DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_listing_id` (`listing_id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_status` (`status`),
  KEY `idx_created_at` (`created_at`),
  CONSTRAINT `surplus_orders_ibfk_1` FOREIGN KEY (`listing_id`) REFERENCES `surplus_listings` (`id`) ON DELETE CASCADE,
  CONSTRAINT `surplus_orders_ibfk_2` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `user_notifications`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `user_notifications` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `user_id` int(11) NOT NULL,
  `title` varchar(200) NOT NULL,
  `message` text NOT NULL,
  `type` varchar(50) DEFAULT 'system',
  `is_read` tinyint(1) DEFAULT 0,
  `link` varchar(500) DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  `email_sent_at` datetime DEFAULT NULL,
  `email_error` text DEFAULT NULL,
  `push_sent_at` datetime DEFAULT NULL,
  `push_error` text DEFAULT NULL,
  `retry_count` tinyint(4) DEFAULT 0,
  `max_retries` tinyint(4) DEFAULT 3,
  `last_attempt_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `user_id` (`user_id`),
  KEY `is_read` (`is_read`),
  KEY `created_at` (`created_at`),
  KEY `idx_user_read` (`user_id`,`is_read`),
  KEY `idx_created` (`created_at`)
) ENGINE=InnoDB AUTO_INCREMENT=193 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `user_roles`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `user_roles` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `user_id` int(11) NOT NULL,
  `role` enum('user','vendor','rider','wholesaler') NOT NULL,
  `status` enum('active','pending','suspended') DEFAULT 'pending',
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  PRIMARY KEY (`id`),
  UNIQUE KEY `user_role` (`user_id`,`role`),
  KEY `user_id` (`user_id`),
  KEY `role` (`role`),
  KEY `status` (`status`)
) ENGINE=InnoDB AUTO_INCREMENT=38 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `users`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `users` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `fname` varchar(100) NOT NULL,
  `lname` varchar(50) NOT NULL,
  `email` varchar(100) DEFAULT NULL,
  `password` varchar(255) DEFAULT NULL,
  `google_id` varchar(255) DEFAULT NULL,
  `google_picture` varchar(500) DEFAULT NULL,
  `profile_picture` varchar(255) DEFAULT NULL,
  `reset_token` varchar(64) DEFAULT NULL,
  `reset_token_expiry` datetime DEFAULT NULL,
  `reset_expires` timestamp NULL DEFAULT NULL,
  `area` varchar(100) NOT NULL,
  `address` varchar(200) NOT NULL,
  `last_login` datetime DEFAULT NULL,
  `last_login_date` date DEFAULT NULL,
  `last_login_day` varchar(20) DEFAULT NULL,
  `login_count` int(11) DEFAULT 0,
  `loyalty_points` int(11) DEFAULT 0 COMMENT 'Loyalty points earned by customer',
  `current_role` enum('user','vendor','rider','wholesaler') DEFAULT 'user',
  `account_type` enum('customer','rider','vendor','wholesaler') NOT NULL DEFAULT 'customer' COMMENT 'What this account is FOR. Fixed at registration; one account, one purpose.',
  `mobile` varchar(30) DEFAULT NULL,
  `last_lat` float DEFAULT NULL,
  `last_lng` float DEFAULT NULL,
  `last_location_update` timestamp NULL DEFAULT NULL,
  `fcm_token` varchar(255) DEFAULT NULL,
  `notification_preferences` text DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `idx_mobile` (`mobile`),
  UNIQUE KEY `email` (`email`),
  UNIQUE KEY `google_id` (`google_id`),
  UNIQUE KEY `idx_email` (`email`),
  KEY `idx_current_role` (`current_role`),
  KEY `idx_account_type` (`account_type`)
) ENGINE=InnoDB AUTO_INCREMENT=113 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `vendor_commission_requests`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `vendor_commission_requests` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `vendor_id` int(11) NOT NULL,
  `requested_rate` decimal(5,2) NOT NULL,
  `status` enum('pending','approved','rejected') DEFAULT 'pending',
  `requested_at` timestamp NOT NULL DEFAULT current_timestamp(),
  `processed_at` timestamp NULL DEFAULT NULL,
  `notes` text DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `vendor_id` (`vendor_id`),
  CONSTRAINT `vendor_commission_requests_ibfk_1` FOREIGN KEY (`vendor_id`) REFERENCES `vendors` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `vendor_earnings`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `vendor_earnings` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `vendor_id` int(11) NOT NULL,
  `order_id` int(11) NOT NULL,
  `order_amount` decimal(10,2) NOT NULL,
  `commission_amount` decimal(10,2) NOT NULL,
  `net_earnings` decimal(10,2) NOT NULL,
  `is_paid` tinyint(1) DEFAULT 0,
  `paid_at` datetime DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  PRIMARY KEY (`id`),
  KEY `idx_vendor_id` (`vendor_id`),
  KEY `idx_is_paid` (`is_paid`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `vendor_notifications`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `vendor_notifications` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `vendor_id` int(11) NOT NULL,
  `title` varchar(255) NOT NULL,
  `message` text NOT NULL,
  `type` enum('order','payment','system','surplus') DEFAULT 'system',
  `is_read` tinyint(1) DEFAULT 0,
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  PRIMARY KEY (`id`),
  KEY `idx_vendor_id` (`vendor_id`),
  KEY `idx_is_read` (`is_read`),
  KEY `idx_type` (`type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `vendor_payout_requests`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `vendor_payout_requests` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `vendor_id` int(11) NOT NULL,
  `amount` decimal(10,2) NOT NULL,
  `status` enum('pending','approved','rejected','paid') DEFAULT 'pending',
  `requested_at` timestamp NOT NULL DEFAULT current_timestamp(),
  `processed_at` timestamp NULL DEFAULT NULL,
  `notes` text DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `vendor_id` (`vendor_id`),
  CONSTRAINT `vendor_payout_requests_ibfk_1` FOREIGN KEY (`vendor_id`) REFERENCES `vendors` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `vendor_products`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `vendor_products` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `vendor_id` int(11) NOT NULL,
  `product_id` int(11) NOT NULL,
  `stock_quantity` int(11) DEFAULT 0,
  `price` decimal(10,2) DEFAULT NULL COMMENT 'Vendor-specific price (if different from base price)',
  `is_active` tinyint(1) DEFAULT 1,
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  `updated_at` timestamp NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  PRIMARY KEY (`id`),
  UNIQUE KEY `vendor_id` (`vendor_id`,`product_id`),
  KEY `idx_vendor_id` (`vendor_id`),
  KEY `idx_product_id` (`product_id`),
  KEY `idx_is_active` (`is_active`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `vendor_reviews`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `vendor_reviews` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `vendor_id` int(11) NOT NULL,
  `user_id` int(11) NOT NULL,
  `order_id` int(11) NOT NULL,
  `rating` int(11) NOT NULL COMMENT '1-5 stars',
  `comment` text DEFAULT NULL,
  `is_approved` tinyint(1) DEFAULT 0,
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  PRIMARY KEY (`id`),
  UNIQUE KEY `order_id` (`order_id`),
  KEY `idx_vendor_id` (`vendor_id`),
  KEY `idx_is_approved` (`is_approved`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `vendors`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `vendors` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `user_id` int(11) NOT NULL,
  `business_name` varchar(255) NOT NULL,
  `business_type` enum('farmer','market_vendor','wholesaler','store','Fish Products') DEFAULT 'market_vendor',
  `phone` varchar(25) NOT NULL,
  `email` varchar(255) DEFAULT NULL,
  `location` varchar(255) DEFAULT NULL,
  `market_stall` varchar(100) DEFAULT NULL COMMENT 'e.g., Owino Market Stall 45',
  `logo` varchar(255) DEFAULT NULL,
  `is_verified` tinyint(1) DEFAULT 0,
  `verification_date` datetime DEFAULT NULL,
  `rating` decimal(3,2) DEFAULT 0.00 COMMENT 'Average rating from customers (0.00-5.00)',
  `total_reviews` int(11) DEFAULT 0,
  `total_sales` decimal(15,2) DEFAULT 0.00,
  `commission_rate` decimal(5,2) DEFAULT 10.00 COMMENT 'Platform commission percentage',
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  `updated_at` timestamp NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  `low_stock_threshold` int(11) DEFAULT 5,
  `last_stock_notification_sent` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `user_id` (`user_id`),
  KEY `idx_business_type` (`business_type`),
  KEY `idx_is_verified` (`is_verified`),
  KEY `idx_location` (`location`),
  CONSTRAINT `vendors_ibfk_1` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=9 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;
/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;
-- ============ REFERENCE DATA ============
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;
/*!40000 ALTER TABLE `items` DISABLE KEYS */;
INSERT INTO `items` (`id`, `name`, `category`, `description`, `price`, `quantity`, `quantitytype`, `image`, `discount`, `homepage`, `offer`, `freshness_date`, `stock_qty`, `weekly_deal`) VALUES (4,'Apple','Fruits','Apples are loaded with vitamin C. Almost half of an apple\'s vitamin C content is just under the skin, so it\'s a good idea to eat apples with their skins. Flores said that this is also where apples\' fiber is found. Apples contain insoluble fiber, which provides bulk in the intestinal tract. The bulk holds water that cleanses and moves food quickly through the digestive system.','1300','1','Unit','product_b3d183ca1a948b75.png',0.00,'NO','YES',NULL,999,'NO'),(7,'Orange','Fruits','The orange is the fruit of the citrus species Citrus × sinensis in the family Rutaceae. It is also called sweet orange, to distinguish it from the related Citrus × aurantium, referred to as bitter orange. The sweet orange reproduces asexually; varieties of sweet orange arise through mutations.','10000','1','Kg','orange.png',0.00,'YES','NO',NULL,999,'NO'),(9,'Pineapple','Fruits','The pineapple is a tropical plant with an edible multiple fruit consisting of coalesced berries, also called pineapples, and the most economically significant plant in the family Bromeliaceae.','5500','1','Unit','pineapple.png',0.00,'NO','NO',NULL,999,'NO'),(11,'Garlic','Vegetables','Garlic is a species in the onion genus, Allium. Its close relatives include the onion, shallot, leek, chive, and Chinese onion. Garlic is native to Central Asia and northeastern Iran, and has long been a common seasoning worldwide, with a history of several thousand years of human consumption and use.','36000','1','Kg','garlic.png',0.00,'YES','NO',NULL,999,'NO'),(23,'Ranchers Finest Beef BBQ Sausages.','Meats','Ranchers Finest Beef BBQ Sausages 1kg.','33500','1','Kg','1784293798.webp',0.00,'YES','NO',NULL,999,'NO'),(24,'Ranchers Finest Prime Fresh Minced Beef 500g','Meats','Ranchers Finest Prime Fresh Minced Beef 500g','15000','500','Grams','RanchersPrime.png',0.00,'YES','NO',NULL,999,'NO'),(25,'Avocado -pc','Fruits','Avocado','2000','1','Unit','Avocado.jpg',0.00,'YES','NO',NULL,999,'NO'),(26,'Sweet Banana - Cluster','Fruits','Sweet Banana ','6000','1','Unit','banana.png',0.00,'YES','NO',NULL,999,'NO'),(28,'Yellow Lemons Imported 500g','Fruits','  Yellow Lemons Imported 500g                          ','8000','500','Grams','yellow-lemons.png',0.00,'NO','NO',NULL,999,'NO'),(30,'Apples Red 500g','Fruits','Apples Red 500g','9500','500','Grams','redapple.png',0.00,'NO','NO',NULL,999,'NO'),(31,'Tangerines per Kg','Fruits','Tangerines','29450','1','Kg','tangerines.png',0.00,'NO','NO',NULL,999,'NO'),(32,'Apples Green 500g','Fruits','Apples Green 500g','9250','500','Grams','applesgreen.jpg',0.00,'YES','NO',NULL,999,'NO'),(34,'Bogoya','Fruits','they are naturally big, fatty, and crunchy   ','11000','1','Cluster','bogoya.png',0.00,'NO','NO',NULL,999,'NO'),(35,'White Grapes Seedless pkt ','Fruits','White grapes taste fresh and sweet and sour. These grapes are nice and crunchy and seedless. Delicious to eat out of hand','17000','1','Packet','grapes.png',0.00,'NO','NO',NULL,999,'NO'),(36,'Ranchers Finest English Style Chicken Sausages 1kg','Meats','English style chicken sausages ideal for breakfast, lunch and snack','30000','1','Kg','engchick.png',0.00,'YES','NO',NULL,999,'NO'),(37,'Ranchers Finest Breakfast Chicken Sausages 700g','Meats','Ranchers Finest Breakfast Chicken Sausages','29000','700','Grams','chicbreak.png',0.00,'YES','NO',NULL,999,'NO'),(38,'Ranchers Finest Chicken Hot Dog 1kg','Meats','Ranchers Finest Chicken Hot Dog','27000','1','Kg','hotdog.png',0.00,'NO','NO',NULL,999,'NO'),(39,'Ranchers Finest English Style Pork Sausages 1kg','Meats','Ranchers Finest English Style Pork Sausages\r\n','30000','1','Kg','pork-eng_sausages-1kg.png',0.00,'NO','NO',NULL,999,'NO'),(40,'Ranchers Finest Pork Hot Dogs 1kg','Meats','Ranchers Finest Pork Hot Dogs','27000','1','Kg','hotdogs_pork-1kg.png',0.00,'NO','NO',NULL,999,'NO'),(41,'Ranchers Finest Beef Hot Dogs 1kg','Meats','Ranchers Finest Beef Hot Dogs','25000','1','Kg','hotdogs_beef-1kg_finest.png',0.00,'NO','NO',NULL,999,'NO'),(42,'Ranchers Finest Breakfast Chicken Sausages 700g','Meats','Ranchers Finest Breakfast Chicken Sausages','29000','700','Grams','breakfast-chicken-sausages.png',0.00,'NO','NO',NULL,999,'NO'),(43,'Ranchers Finest Pork Breakfast Sausages 700g','Meats','Ranchers Finest Pork Breakfast Sausages','29500','700','Grams','01-Breakfast-Pork-Sausages_700g_.png',0.00,'NO','NO',NULL,999,'NO'),(44,'Ranchers Finest Beef Breakfast Sausages 700g','Meats','Ranchers Finest Beef Breakfast Sausages','28500','700','Grams','01-Breakfast-Beef-Sausages_700g.png',0.00,'NO','NO',NULL,999,'NO'),(45,'Ranchers Finest Chicken Viennas 700g','Meats','Ranchers Finest Chicken Viennas ','17500','700','Grams','poultry-viennas-700g_.png',0.00,'NO','NO',NULL,999,'NO'),(46,'Ranchers Finest Prime Beef Cubes 500g','Meats','Beef Cubes Category Sliced cold meat','20000','500','Grams','prime-beef.jpg',0.00,'NO','NO',NULL,999,'NO'),(47,'Ranchers Finest Fresh Prime Beef Plain Hamburger 500g','Meats','Ranchers Finest Fresh Prime Beef Plain Hamburger','20500','500','Grams','burger-beef.png',0.00,'NO','NO',NULL,999,'NO'),(48,'Ranchers Finest Prime Beef Steak 1st choice-per kg 500g','Meats','Ranchers Finest Prime Beef Steak 1st choice','20500','500','Grams','prime-beef-steak.png',0.00,'NO','NO',NULL,999,'NO'),(49,'Ranchers Finest Prime Roast Beef (Topside) Per Kg 1kg','Meats','Ranchers Finest Prime Roast Beef (Topside)','39500','1','Kg','prime-roast-beef.jpg',0.00,'NO','NO',NULL,999,'NO'),(50,'Ranchers Finest Prime Beef Veal Stew 500g','Meats','Ranchers Finest Prime Beef Veal Stew','20000','500','Grams','prime-beef-veal-stew.png',0.00,'NO','NO',NULL,999,'NO'),(51,'Ranchers Finest Prime Beef StriploinSirloin Steak-Kg','Meats','Ranchers Finest Prime Beef Striploin Sirloin Steak 500g','26250','500','Grams','ranchers-finest-prime-beef-striploinsirloin-steak-kg.png',0.00,'YES','NO',NULL,999,'NO'),(53,'Ranchers Finest Prime Beef Rib Eye Steak-kg 500g','Meats','Ranchers Finest Prime Beef Rib Eye Steak','21350','500','Grams','ranchers-finest-prime-beef-rib-eye-steak-kg-500g.png',0.00,'NO','NO',NULL,999,'NO'),(54,'Fresh Dairy Full Cream UHT Milk 500ml ','Dairy Products','Fresh Dairy Full Cream UHT Milk                    ','4800','500','ml','freshdairyfullcream.png',0.00,'NO','NO',NULL,999,'NO'),(55,'Alpro  Soya Milk Sugar Free 1L','Dairy Products','Alpro Soya Milk Sugar Free','45000','1','Packet','alpro--soya-milk-sugar-free-1l.png',0.00,'NO','NO',NULL,999,'NO'),(56,'Alpro Almond Milk Original 1L','Dairy Products','Alpro almond milk is a 100% plant-based, dairy-free, and vegan drink made from lightly roasted Mediterranean almonds. It is naturally low in fat, lactose-free, and gluten-free, often fortified with calcium and vitamins B2, B12, and D2. Popular variants include \"Original\" (lightly sweetened) and \"Unsweetened/No Sugars,\" both offering a low-calorie alternative to dairy.','45500','1','Packet','alprooriginal.jpg',0.00,'NO','NO',NULL,999,'NO'),(57,'Alpro Soya Milk Original 1Ltr','Dairy Products','Alpro Soya Milk Original ','41000','1','Packet','lprooriginal.jpg',0.00,'NO','NO',NULL,999,'NO'),(58,'Basil','Vegetables','Basil Pre Packed','7500','1','Packet','basil-scaled-1.webp',0.00,'NO','NO',NULL,999,'NO'),(59,'Parsley Curved Leaves Pkt','Vegetables','Parsley Curved Leaves','7500','1','Packet','parsley-curved-leaves.webp',0.00,'NO','NO',NULL,999,'NO'),(60,'Mint leaves ','Vegetables','packet of Mint leaves','7500','1','Packet','mint-leaves.webp',0.00,'NO','NO',NULL,999,'NO'),(61,'Orange Yolk egg Half Tray','Chicken Products','Orange Yolk egg Half Tray - 1*15','18000','1','Packet','orange-yolk-egg-half-tray',0.00,'NO','NO',NULL,999,'NO'),(62,'Orange Yolk eggs - 6pc','Chicken Products','Orange Yolk eggs - 6pc','9000','1','Packet','orange-yolk-eggs---6pc.jpg',0.00,'NO','NO',NULL,999,'NO'),(63,'Everyday Mayonaise With Eggs 500ml','Condiments','Everyday Mayonaise With Eggs 500ml','34800','500','ml','everyday-mayonaise.webp',0.00,'YES','NO',NULL,999,'NO'),(64,'Boni  Bio Oatmeal 500g','Dry Ratio','Boni Bio Oatmeal 500g\r\nThis product contains: Gluten, Lupin, Milk, Nuts.','18800','500','Grams','boni-selection-flocons-avoine-bio-500-gr.jpg',0.00,'NO','NO',NULL,999,'NO'),(65,'Boni Muesli Repen Bars- Chocolate 8x23g','Dry Ratio','This product contains:\r\nGluten\r\nSoy\r\nNuts','33450','184','Grams','boni-muesli-repen-bars--chocolate-8x23g.jpg',0.00,'NO','NO',NULL,999,'NO'),(66,'Kakira Sugar 10kgs','Groceries','Kakira Sugar 10kgs bag.','45000','10','Kg','694189f8eabcf_1765902840.jpg',0.00,'NO','NO',NULL,999,'NO'),(67,'Ground Minced Beef 500g','Meats','AfamFresh assorted meat mince.                      ','13000','500','Grams','pngegg-(6).png',0.00,'NO','NO',NULL,999,'NO'),(68,'Kakira Sugar - 1kg','Groceries','Kakira Sugar - 1kg','5800','1','Kg','69418dcd06d68_1765903821.jpg',0.00,'NO','NO',NULL,999,'NO'),(69,'Rice Super Premium 1kg','Dry Ratio','Rice Super Premium','6000','1','Kg','6941461d5d1e3_1765885469.jpg',0.00,'NO','NO',NULL,999,'NO'),(70,'Rice Super Premium 10kg','Dry Ratio','Rice Super Premium 10kg','62000','10','Kg','6941461a00c23_1765885466.jpg',0.00,'NO','NO',NULL,999,'NO'),(71,'Masavu beans well sorted 5kg','Dry Ratio','Masavu beans well sorted','32000','5','Kg','69f83c33671e9_1777876019.jpg',0.00,'YES','NO',NULL,999,'NO'),(72,'Masavu beans well sorted 1kg','Dry Ratio','The most delicious beans on the market. ','6000','1','Kg','69f83c33671e9_1777876019.jpg',0.00,'NO','NO',NULL,999,'NO'),(73,'White Onions.','Vegetables','White onions are medium to large globular bulbs with a bright white, flaky skin and translucent, firm flesh. Their texture is crisp and juicy, composed of many thin white rings. Known for their crunch and tenderness, they offer a pungent yet mildly sweet flavor with a mellow aftertaste, making them versatile for various culinary uses without an abrasive finish.','2000','150','Grams','1219.png',0.00,'NO','NO',NULL,999,'NO'),(74,'Fresh Lemons local 1kg','Fruits','Pure fresh local Ugandan indigenous lemons.                     ','6500','1','Kg','screenshot_20260530-194107.jpg',0.00,'NO','NO',NULL,999,'NO'),(75,'Fresh Mushrooms Loose - Pkt','Vegetables','Freshly grown mushrooms.','4500','1','Packet','screenshot_20260530-194537.jpg',10.00,'YES','NO',NULL,999,'NO'),(76,'Red Pepper - 250g','Vegetables','Red Pepper             ','5200','250','Grams','screenshot_20260530-195303.jpg',10.00,'YES','NO',NULL,999,'NO'),(79,'Green Pepper 150g','Vegetables','Green Peppers                         ','1100','150','Grams','screenshot_20260530-200221.jpg',0.00,'NO','NO',NULL,999,'NO'),(80,'Supreme All Purpose wheat flour','Dry Ratio','Supreme All Purpose wheat flour – 2kg','8650','2','Kg','images-(4).jpeg',0.00,'NO','NO',NULL,999,'NO'),(81,'Supreme Premium Maize flour ','Dry Ratio',' Supreme Premium Maize flour                            ','5200','2','Kg','4.jpg',0.00,'NO','NO',NULL,999,'NO'),(83,'Chicken Braai Pack','Chicken Products',' Chicken Braai Pack                         ','17000','1','Packet','screenshot_20260530-203304.jpg',0.00,'NO','NO',NULL,999,'NO'),(84,'Whole Chicken 1Kg','Chicken Products','Whole Chicken 1Kg','17500','1','Kg','screenshot_20260530-203620.jpg',0.00,'NO','NO',NULL,999,'NO'),(85,'Family Chicken Pack 2Kgs','Chicken Products','Family Chicken Pack 2Kgs','30800','2','Kg','screenshot_20260530-203004.jpg',0.00,'NO','NO',NULL,999,'NO'),(86,'Tomatoes - 1 Kg','Vegetables','Tomatoes                           ','6000','1','Kg','cherry-tomato-food-salad-tomato-png-image-4e1d3cbc9f5e6169de3651a99a0d6d45.png',10.00,'YES','NO',NULL,999,'NO'),(87,'Splash Fruit Cocktail 250ml','Beverages','Splash Fruit Cocktail 250ml','1800','1','Packet','splash-cocktail-250ml.jpg',0.00,'YES','NO',NULL,999,'NO'),(88,'Rwenzori Water 500ML','Beverages',' Rwenzori Water 500ML                           ','900','1','Unit','images.jpg',0.00,'YES','NO',NULL,999,'NO'),(89,'Kinyara Sugar 1 Kg','Groceries','Kinyara Sugar                   ','4000','1','Kg','1.jpg',0.00,'NO','NO',NULL,999,'NO'),(90,'Rwenzori Water 18.9 L','Groceries','Rwenzori Mineral water                           ','11800','1','Unit','rwenzori-pure-natural-mineral-water-18.7ltrs-ugx-25000.jpg',0.00,'NO','NO',NULL,999,'NO'),(91,'MUKWANO GOLDEN TEA 250g','Groceries',' Savor the rich taste of Uganda’s finest—click below to order your Golden Tea today!                        ','5350','250','Grams','tea-58-of-60-1024x1024.jpg',0.00,'NO','NO',NULL,999,'NO'),(92,'MUKWANO GOLDEN TEA 500g','Groceries','Savor the rich taste of Uganda’s finest—click below to order your Golden Tea today!                 ','8500','500','Grams','tea-58-of-60-1024x1024.jpg',0.00,'NO','NO',NULL,999,'NO'),(93,'RWENZORI GOLD LOOSE TEA 250g','Groceries','  Savor the rich taste of Uganda’s finest—click below to order your Rwenzori Gold Tea today!                          ','4300','250','Grams','tea-42-of-60.jpg',0.00,'NO','NO',NULL,999,'NO'),(94,'RWENZORI GOLD LOOSE TEA 100g','Groceries','Savor the rich taste of Uganda’s finest—click below to order your Rwenzori Gold Tea today!        ','2500','100','Grams','tea-42-of-60.jpg',0.00,'NO','NO',NULL,999,'NO'),(95,'RWENZORI TEA BAGS','Groceries',' RWENZORI TEA BAGS                           ','5800','1','Packet','mukwano-tea-05.png',0.00,'NO','NO',NULL,999,'NO'),(96,'MAHMOOD RICE 5Kg','Dry Ratio','MAHMOOD RICE','52500','5','Kg','images-(5).jpeg',0.00,'NO','NO',NULL,999,'NO'),(97,'Eggs -Tray','Chicken Products','A Tray Of Eggs','13500','1','Unit','1784292985.webp',0.00,'NO','NO',NULL,999,'NO');
/*!40000 ALTER TABLE `items` ENABLE KEYS */;
/*!40000 ALTER TABLE `category` DISABLE KEYS */;
INSERT INTO `category` (`id`, `categry`, `cateimg`) VALUES (9,'Fruits','fruits.png'),(10,'Juice','juice.png'),(11,'Vegetables','vegetables.png'),(12,'Oils','oils.png'),(14,'Meats','sausage-rump-steak-ribs-beef-beef-meat-ac42ee37fc5937203d4ff0c15f4e85b6.png'),(15,'Dairy Products','pngegg-(1).png'),(16,'Chicken Products','poultryproducts.png'),(17,'Dry Ratio','vecteezy_ingredients-for-baking-flour-wheat-bread-and-cooking_58268998.png'),(18,'Groceries','pngegg-(5).png'),(19,'Grains','pngegg-(4).png'),(20,'Fish Products','pngegg-(3).png'),(21,'Meal Plans','—pngtree—3d-illustration-of-diet-plan_14570073.png'),(22,'Condiments','pngegg-(2).png'),(23,'Beverages','beverages_collection.png');
/*!40000 ALTER TABLE `category` ENABLE KEYS */;
/*!40000 ALTER TABLE `site_settings` DISABLE KEYS */;
INSERT INTO `site_settings` (`setting_key`, `setting_value`, `updated_at`, `promo_bg_color`, `promo_bg_gradient_start`, `promo_bg_gradient_end`, `promo_accent_color`, `promo_text_color`, `promo_cta_text`, `promo_cta_link`, `promo_enabled`, `promo_countdown_enabled`, `promo_countdown_date`, `promo_custom_image`) VALUES ('hero_autoplay_enabled','1','2026-05-29 06:28:53','#0f3d1f','#0f3d1f','#2d8a44','#FFC107','#ffffff','Shop the Deal','products.php',1,1,NULL,NULL),('hero_autoplay_interval','4000','2026-05-29 06:28:53','#0f3d1f','#0f3d1f','#2d8a44','#FFC107','#ffffff','Shop the Deal','products.php',1,1,NULL,NULL),('hero_badge','Now Delivering in Kampala','2026-05-29 06:28:53','#0f3d1f','#0f3d1f','#2d8a44','#FFC107','#ffffff','Shop the Deal','products.php',1,1,NULL,NULL),('hero_cta_primary_link','products.php','2026-05-29 06:28:53','#0f3d1f','#0f3d1f','#2d8a44','#FFC107','#ffffff','Shop the Deal','products.php',1,1,NULL,NULL),('hero_cta_primary_text','🛒 Shop Now →','2026-05-29 06:28:53','#0f3d1f','#0f3d1f','#2d8a44','#FFC107','#ffffff','Shop the Deal','products.php',1,1,NULL,NULL),('hero_cta_secondary_link','#delivery-zones','2026-05-29 06:28:53','#0f3d1f','#0f3d1f','#2d8a44','#FFC107','#ffffff','Shop the Deal','products.php',1,1,NULL,NULL),('hero_cta_secondary_text','📍 Check My Zone','2026-05-29 06:28:53','#0f3d1f','#0f3d1f','#2d8a44','#FFC107','#ffffff','Shop the Deal','products.php',1,1,NULL,NULL),('hero_enabled','1','2026-05-29 06:28:53','#0f3d1f','#0f3d1f','#2d8a44','#FFC107','#ffffff','Shop the Deal','products.php',1,1,NULL,NULL),('hero_stat1_label','Products Available','2026-05-29 06:28:53','#0f3d1f','#0f3d1f','#2d8a44','#FFC107','#ffffff','Shop the Deal','products.php',1,1,NULL,NULL),('hero_stat1_num','500+','2026-05-29 06:28:53','#0f3d1f','#0f3d1f','#2d8a44','#FFC107','#ffffff','Shop the Deal','products.php',1,1,NULL,NULL),('hero_stat2_label','Avg Delivery Time','2026-05-29 06:28:53','#0f3d1f','#0f3d1f','#2d8a44','#FFC107','#ffffff','Shop the Deal','products.php',1,1,NULL,NULL),('hero_stat2_num','2hrs','2026-05-29 06:28:53','#0f3d1f','#0f3d1f','#2d8a44','#FFC107','#ffffff','Shop the Deal','products.php',1,1,NULL,NULL),('hero_stat3_label','Customer Rating','2026-05-29 06:28:53','#0f3d1f','#0f3d1f','#2d8a44','#FFC107','#ffffff','Shop the Deal','products.php',1,1,NULL,NULL),('hero_stat3_num','4.9★','2026-05-29 06:28:53','#0f3d1f','#0f3d1f','#2d8a44','#FFC107','#ffffff','Shop the Deal','products.php',1,1,NULL,NULL),('hero_subtitle','Order fresh vegetables, fruits, meat, and cereals from AfamFresh. Delivered same-day across Kampala and her Surroundings.','2026-05-29 06:28:53','#0f3d1f','#0f3d1f','#2d8a44','#FFC107','#ffffff','Shop the Deal','products.php',1,1,NULL,NULL),('hero_title','Farm Fresh Right to Your Doorstep','2026-05-29 06:28:53','#0f3d1f','#0f3d1f','#2d8a44','#FFC107','#ffffff','Shop the Deal','products.php',1,1,NULL,NULL),('promo_accent_color','#FFC107','2026-05-29 05:42:33','#0f3d1f','#0f3d1f','#2d8a44','#FFC107','#ffffff','Shop the Deal','products.php',1,1,NULL,NULL),('promo_bg_gradient_end','#2d8a44','2026-05-29 05:42:33','#0f3d1f','#0f3d1f','#2d8a44','#FFC107','#ffffff','Shop the Deal','products.php',1,1,NULL,NULL),('promo_bg_gradient_start','#0f3d1f','2026-05-29 05:42:33','#0f3d1f','#0f3d1f','#2d8a44','#FFC107','#ffffff','Shop the Deal','products.php',1,1,NULL,NULL),('promo_countdown_date','','2026-05-29 05:42:33','#0f3d1f','#0f3d1f','#2d8a44','#FFC107','#ffffff','Shop the Deal','products.php',1,1,NULL,NULL),('promo_countdown_enabled','1','2026-05-29 05:42:33','#0f3d1f','#0f3d1f','#2d8a44','#FFC107','#ffffff','Shop the Deal','products.php',1,1,NULL,NULL),('promo_cta_link','products.php','2026-05-29 05:42:33','#0f3d1f','#0f3d1f','#2d8a44','#FFC107','#ffffff','Shop the Deal','products.php',1,1,NULL,NULL),('promo_cta_text','Shop the Deal','2026-05-29 05:42:33','#0f3d1f','#0f3d1f','#2d8a44','#FFC107','#ffffff','Shop the Deal','products.php',1,1,NULL,NULL),('promo_custom_image','','2026-05-29 05:42:33','#0f3d1f','#0f3d1f','#2d8a44','#FFC107','#ffffff','Shop the Deal','products.php',1,1,NULL,NULL),('promo_enabled','1','2026-05-29 05:42:33','#0f3d1f','#0f3d1f','#2d8a44','#FFC107','#ffffff','Shop the Deal','products.php',1,1,NULL,NULL),('promo_feature1','✓ New Products Added Daily','2026-05-23 10:10:42','#0f3d1f','#0f3d1f','#2d8a44','#FFC107','#ffffff','Shop the Deal','products.php',1,1,NULL,NULL),('promo_feature2','✓ Quickest Delivery','2026-05-23 10:10:42','#0f3d1f','#0f3d1f','#2d8a44','#FFC107','#ffffff','Shop the Deal','products.php',1,1,NULL,NULL),('promo_sub','Free Delivery on Orders Over 75,000 UGX','2026-05-23 10:10:42','#0f3d1f','#0f3d1f','#2d8a44','#FFC107','#ffffff','Shop the Deal','products.php',1,1,NULL,NULL),('promo_tag','Limited Time Offer','2026-05-23 10:10:42','#0f3d1f','#0f3d1f','#2d8a44','#FFC107','#ffffff','Shop the Deal','products.php',1,1,NULL,NULL),('promo_text_color','#ffffff','2026-05-29 05:42:33','#0f3d1f','#0f3d1f','#2d8a44','#FFC107','#ffffff','Shop the Deal','products.php',1,1,NULL,NULL),('promo_title','Fresh Deals - Upto 10% Off This Week','2026-06-04 11:58:17','#0f3d1f','#0f3d1f','#2d8a44','#FFC107','#ffffff','Shop the Deal','products.php',1,1,NULL,NULL);
/*!40000 ALTER TABLE `site_settings` ENABLE KEYS */;
/*!40000 ALTER TABLE `app_config` DISABLE KEYS */;
INSERT INTO `app_config` (`config_key`, `config_value`, `updated_at`) VALUES ('current_version','1.0','2026-06-28 15:52:56'),('is_maintenance_mode','0','2026-06-28 14:27:28'),('maintenance_message','We are updating our stock. Back soon!','2026-06-28 13:54:51'),('min_version_required','1.0','2026-06-28 15:53:04'),('surplus_approval_required','1','2026-06-28 13:54:51');
/*!40000 ALTER TABLE `app_config` ENABLE KEYS */;
/*!40000 ALTER TABLE `delivery_pricing` DISABLE KEYS */;
INSERT INTO `delivery_pricing` (`id`, `profit_percent_enabled`, `profit_percent`, `service_fee`, `created_at`, `updated_at`, `free_delivery_threshold`, `free_delivery_distance_threshold`, `medium_order_threshold`, `medium_order_rate`, `low_order_rate`, `insurance_percent`) VALUES (1,1,2.80,1800.00,'2026-05-26 04:39:31','2026-06-07 23:38:00',100000.00,5,75000.00,380.00,700.00,0.90);
/*!40000 ALTER TABLE `delivery_pricing` ENABLE KEYS */;
/*!40000 ALTER TABLE `delivery_slots` DISABLE KEYS */;
INSERT INTO `delivery_slots` (`id`, `slot_label`, `slot_start`, `slot_end`, `max_orders`, `is_active`) VALUES (1,'Morning (8AM-12PM)','08:00:00','12:00:00',100,1),(2,'Afternoon (12PM-4PM)','12:00:00','16:00:00',100,1),(3,'Evening (4PM-8PM)','16:00:00','20:00:00',100,1);
/*!40000 ALTER TABLE `delivery_slots` ENABLE KEYS */;
/*!40000 ALTER TABLE `shipping` DISABLE KEYS */;
INSERT INTO `shipping` (`id`, `cost`) VALUES (1,10);
/*!40000 ALTER TABLE `shipping` ENABLE KEYS */;
/*!40000 ALTER TABLE `banner` DISABLE KEYS */;
INSERT INTO `banner` (`id`, `bnimg`) VALUES (1,'banner1.jpg'),(2,'banner2.jpg'),(3,'banner3.jpg'),(4,'banner4.jpg'),(5,'banner5.jpg');
/*!40000 ALTER TABLE `banner` ENABLE KEYS */;
/*!40000 ALTER TABLE `gift_hampers` DISABLE KEYS */;
INSERT INTO `gift_hampers` (`id`, `name`, `description`, `price`, `image`, `is_active`, `created_at`) VALUES (1,'FreshLife Hamper \"Fresh ingredients. Better meals. Delivered Fresh on time.\"','A carefully curated selection of premium fresh fruits, farm-picked vegetables, and essential quick-make dry foods designed to keep kitchens stocked, healthy, and efficient. The FreshLife Hamper is ideal for office kitchens and individuals who value fresh, reliable ingredients delivered right when needed.                   ',100000.00,'pngegg.png',1,'2026-05-23 04:19:45'),(2,'The Goodies Drop.','From wholesome everyday staples to exciting new finds, The Goodies Drop is your shortcut to a fully stocked kitchen — without the hassle of hunting through aisles.\r\nWhy you\'ll love it:\r\n? Fresh, quality picks selected just for you\r\n? New surprise items every month\r\n? Fast, reliable delivery to your doorstep\r\n? Exclusive member pricing — more value, less spend',165000.00,'5a3761242a8f1b8ae5ac2466c53d8af9.jpg',1,'2026-05-30 18:17:58');
/*!40000 ALTER TABLE `gift_hampers` ENABLE KEYS */;
/*!40000 ALTER TABLE `gift_hamper_items` DISABLE KEYS */;
INSERT INTO `gift_hamper_items` (`id`, `hamper_id`, `item_id`, `quantity`) VALUES (21,1,24,1),(22,1,45,1),(23,1,71,1),(24,1,26,1),(25,1,69,2),(26,1,68,1),(50,2,26,1),(51,2,40,1),(52,2,50,1),(53,2,68,1),(54,2,69,1),(55,2,72,1),(56,2,79,1),(57,2,75,1),(58,2,84,1),(59,2,80,1),(60,2,81,1);
/*!40000 ALTER TABLE `gift_hamper_items` ENABLE KEYS */;
/*!40000 ALTER TABLE `surplus_discount_rules` DISABLE KEYS */;
INSERT INTO `surplus_discount_rules` (`id`, `name`, `min_discount_percent`, `max_discount_percent`, `days_before_expiry`, `condition_multiplier`, `is_active`, `created_at`, `updated_at`) VALUES (1,'Standard Rule',30.00,50.00,3,1.00,1,'2026-05-27 17:12:17','2026-05-27 17:12:17'),(2,'Urgent Rule',50.00,70.00,1,1.00,1,'2026-05-27 17:12:17','2026-05-27 17:12:17'),(3,'Bulk Discount',40.00,60.00,5,0.95,1,'2026-05-27 17:12:17','2026-05-27 17:12:17');
/*!40000 ALTER TABLE `surplus_discount_rules` ENABLE KEYS */;
/*!40000 ALTER TABLE `surplus_delivery_settings` DISABLE KEYS */;
INSERT INTO `surplus_delivery_settings` (`id`, `base_fee`, `fee_per_kg`, `max_weight_kg`, `free_delivery_threshold`, `updated_at`) VALUES (1,5000.00,500.00,1000,500000.00,'2026-06-15 12:23:05'),(2,5000.00,500.00,1000,500000.00,'2026-06-15 13:29:59');
/*!40000 ALTER TABLE `surplus_delivery_settings` ENABLE KEYS */;
/*!40000 ALTER TABLE `storage_pricing` DISABLE KEYS */;
/*!40000 ALTER TABLE `storage_pricing` ENABLE KEYS */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;
/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;
SET FOREIGN_KEY_CHECKS=1;
