-- =============================================================
-- Vendor-owned products
-- =============================================================
-- Lets a vendor create a product of their own, which an admin approves before
-- it can be listed as surplus. Until now `items` was purely the admin
-- catalogue, and surplus_listings.product_id has a hard foreign key to it, so
-- a vendor could only list surplus of something an admin had already created.
--
-- Two columns, both nullable/defaulted so the 72 existing rows are untouched
-- and stay visible:
--
--   vendor_id  NULL      = admin catalogue product, as every current row is
--              <id>      = created by that vendor
--
--   status     'approved' = the default, so existing rows keep working. New
--                           vendor products are inserted as 'pending'
--                           explicitly.
--
-- The default deliberately favours the existing data over the new feature.
-- Defaulting to 'pending' would have hidden the whole catalogue the moment
-- this ran.
--
-- Run against Cloud SQL:
--   mysql -h 127.0.0.1 -P 9470 -u aokwi -p --get-server-public-key kitchen \
--     < migrations/2026-08-11-vendor-owned-products.sql
-- =============================================================

ALTER TABLE `items`
  ADD COLUMN `vendor_id` INT(11) DEFAULT NULL
    COMMENT 'Owning vendor; NULL means an admin catalogue product'
    AFTER `id`,
  ADD COLUMN `status` ENUM('pending','approved','rejected') NOT NULL DEFAULT 'approved'
    COMMENT 'Vendor-created products start pending; admin approval makes them listable'
    AFTER `vendor_id`,
  ADD COLUMN `rejection_reason` VARCHAR(255) DEFAULT NULL
    COMMENT 'Shown to the vendor so a rejection is actionable',
  ADD KEY `idx_vendor_status` (`vendor_id`, `status`);

-- ON DELETE CASCADE would take the product with the vendor, and
-- surplus_listings cascades from products -- so removing one vendor could
-- silently delete listings and order history referencing them. SET NULL keeps
-- the product as an ownerless catalogue row instead.
ALTER TABLE `items`
  ADD CONSTRAINT `items_vendor_fk`
  FOREIGN KEY (`vendor_id`) REFERENCES `vendors` (`id`) ON DELETE SET NULL;

-- Sanity check: every existing row should be an approved, admin-owned product.
-- Expect 72 rows, all vendor_id NULL and status 'approved'.
SELECT COUNT(*) AS total,
       SUM(vendor_id IS NULL) AS admin_owned,
       SUM(status = 'approved') AS approved
  FROM `items`;
