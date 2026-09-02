-- Add fast_food_restaurant to vendors.business_type enum
-- Allows vendors to be categorized as fast food restaurants alongside
-- farmers, market vendors, and wholesalers. Used by the customer app
-- to display merchant categories.

ALTER TABLE `vendors` MODIFY `business_type` enum('farmer','market_vendor','wholesaler','fast_food_restaurant') DEFAULT 'market_vendor';

-- Add merchant_category field to Bulk_listings to store the categorized
-- merchant type for easy filtering on the client side.
-- This is denormalized from the vendor.business_type at listing creation
-- so the category is immutable per listing (historical accuracy).
ALTER TABLE `Bulk_listings` ADD COLUMN `merchant_category` varchar(50) DEFAULT NULL AFTER `listing_type`;

-- Index for efficient filtering by merchant category on the customer side.
ALTER TABLE `Bulk_listings` ADD INDEX `idx_merchant_category` (`merchant_category`);
