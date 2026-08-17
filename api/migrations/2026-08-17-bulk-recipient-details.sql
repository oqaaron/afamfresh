-- =============================================================
-- Let a Bulk order name who actually receives it
-- =============================================================
-- Bulk_orders stored a delivery address and nothing about the person at it.
-- includes/rider_dispatch.php therefore handed the rider `u.fname, u.lname,
-- u.mobile` from `users` -- the ACCOUNT HOLDER.
--
-- That is wrong for the customer this product is built around. AfamFresh sells
-- to the diaspora ordering for family in Uganda: the buyer is in London and
-- the produce goes to their mother in Nakawa. The rider was being given a
-- foreign phone number and the buyer's name, with no way to reach the person
-- standing at the door.
--
-- The shop side has never had this problem: `orders` carries its own fname,
-- lname and mobile per order, precisely so an order can be delivered to
-- someone other than the account. This gives Bulk the same thing.
--
-- NULLABLE, and the code falls back to the account holder when they are null.
-- Every existing order keeps behaving exactly as it does now, and an older app
-- that does not send these fields still places valid orders.
-- =============================================================

ALTER TABLE `Bulk_orders`
  ADD COLUMN `recipient_name` VARCHAR(150) NULL DEFAULT NULL
    COMMENT 'Who receives this. NULL means the account holder.'
    AFTER `delivery_area`,
  ADD COLUMN `recipient_phone` VARCHAR(25) NULL DEFAULT NULL
    COMMENT 'Phone the rider calls on arrival. NULL means the account holder.'
    AFTER `recipient_name`;

-- Expect both columns present and NULL on every existing row.
SELECT COUNT(*) AS orders,
       SUM(recipient_name IS NULL)  AS null_name,
       SUM(recipient_phone IS NULL) AS null_phone
  FROM `Bulk_orders`;
