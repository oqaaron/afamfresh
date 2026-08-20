-- =============================================================
-- Normalise items.category against the canonical `category` table.
-- =============================================================
-- Product 110 ("Pearl Chicken Drumstick Plate 500g") was filed under
-- "ChickenProducts" while every other chicken row read "Chicken Products".
-- It has since been corrected by hand in the admin console, so this
-- migration may well find nothing to change on the row that prompted it --
-- that is the expected outcome, not a sign it is unnecessary.
--
-- WHY A MIGRATION RATHER THAN A ONE-OFF UPDATE
--
-- Nothing errors on a mismatch, which is exactly what makes it worth
-- catching systematically. The Android app filters on exact category names:
--
--   * HomeScreen.kt matches category.trim().lowercase() against
--     FRESH_FOOD_CATEGORIES, which holds "chicken products" WITH the space.
--     "chickenproducts" is not in that set, so the product is absent from
--     the Fresh Food bubble.
--   * BrowseScreen.kt derives its category tiles from the products
--     themselves, so a bad value also renders a spurious extra tile with a
--     count of 1.
--
-- The row looks perfectly normal in the admin list either way. A sweep is
-- the only way to find the others.
--
-- WHY THE JOIN RATHER THAN LITERAL UPDATES
--
-- Matching on "same name ignoring case and spaces" repairs every value that
-- differs from a real category only cosmetically, without inventing a
-- mapping. A row whose category has no canonical counterpart at all (a
-- genuinely unknown name) is deliberately LEFT ALONE: silently reassigning
-- it would hide a data-entry problem rather than surface it. The SELECT at
-- the end reports those for a human to judge.
-- =============================================================

UPDATE `items` i
JOIN `category` c
  ON REPLACE(LOWER(TRIM(i.`category`)), ' ', '') = REPLACE(LOWER(TRIM(c.`categry`)), ' ', '')
SET i.`category` = c.`categry`
WHERE i.`category` <> c.`categry`;

-- Sanity check, printed into the deploy log: any product still filed under a
-- category the `category` table does not contain. Expected to be empty.
-- Anything listed here needs fixing by hand in the admin console -- it will
-- not appear under any home-screen bubble until it is.
SELECT i.`id`, i.`name`, i.`category`
FROM `items` i
LEFT JOIN `category` c ON i.`category` = c.`categry`
WHERE c.`categry` IS NULL
  AND i.`category` IS NOT NULL
  AND TRIM(i.`category`) <> '';
