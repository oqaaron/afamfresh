-- =============================================================
-- Backfill riders.avg_rating and friends from existing deliveries.
-- =============================================================
-- The five rating columns on `riders` have been read in five places and
-- written by nothing since they were added, so every rider reads 0.00
-- with 0 ratings no matter how many deliveries they have completed.
-- includes/order_feedback.php now maintains them going forward; this
-- migration catches up the ratings customers have ALREADY given.
--
-- Safe to run on a live database: touches only the five derived columns
-- on `riders`, reads everything else, and is idempotent -- running it
-- twice produces the same result, because it recomputes from source
-- rows rather than accumulating.
--
-- WHY THE JOIN LOOKS LIKE THIS
--
-- `orders` has no rider_id column and Bulk_orders has none either. The
-- only thing tying a rider to a delivery across both is
-- rider_assignments, which carries the `source` discriminator added by
-- 2026-08-12-surplus-dispatch.sql. The two id spaces OVERLAP -- shop
-- order 41 and Bulk order 41 both exist -- so joining on order_id
-- without also matching source would blend one rider's shop deliveries
-- with another's Bulk deliveries and produce averages belonging to
-- nobody.
--
-- Orders with customer_rating IS NULL are excluded, not counted as zero:
-- a customer who confirmed receipt without rating expressed no opinion,
-- and scoring that as one star would punish riders for their customers'
-- indifference.
-- =============================================================

UPDATE `riders` r
LEFT JOIN (
    SELECT ra.rider_id,
           AVG(rated.customer_rating)        AS avg_overall,
           COUNT(*)                          AS n,
           AVG(rated.rating_speed)           AS avg_speed,
           AVG(rated.rating_professionalism) AS avg_prof,
           AVG(rated.rating_packaging)       AS avg_pack
      FROM `rider_assignments` ra
      JOIN (
            SELECT 'order' AS source, o.orderid AS order_id, o.customer_rating,
                   o.rating_speed, o.rating_professionalism, o.rating_packaging
              FROM `orders` o
             WHERE o.customer_rating IS NOT NULL

            UNION ALL

            SELECT 'Bulk' AS source, b.id AS order_id, b.customer_rating,
                   b.rating_speed, b.rating_professionalism, b.rating_packaging
              FROM `Bulk_orders` b
             WHERE b.customer_rating IS NOT NULL
           ) AS rated
        ON rated.order_id = ra.order_id
       AND rated.source   = ra.source
     GROUP BY ra.rider_id
) AS agg ON agg.rider_id = r.id
SET r.`avg_rating`          = COALESCE(ROUND(agg.avg_overall, 2), 0.00),
    r.`total_ratings`       = COALESCE(agg.n, 0),
    r.`avg_speed`           = COALESCE(ROUND(agg.avg_speed, 2), 0.00),
    r.`avg_professionalism` = COALESCE(ROUND(agg.avg_prof, 2), 0.00),
    r.`avg_packaging`       = COALESCE(ROUND(agg.avg_pack, 2), 0.00);

-- LEFT JOIN above is deliberate: riders with no rated deliveries match
-- nothing, COALESCE writes 0.00/0, and they are explicitly reset rather
-- than skipped. That matters if this is ever re-run after deliveries are
-- deleted -- a stale average from rows that no longer exist gets cleared
-- instead of lingering.

-- ---------------------------------------------------------------
-- Verification: eyeball that the averages match the counts.
-- ---------------------------------------------------------------
SELECT r.`id`, r.`name`, r.`avg_rating`, r.`total_ratings`,
       r.`avg_speed`, r.`avg_professionalism`, r.`avg_packaging`
  FROM `riders` r
 ORDER BY r.`total_ratings` DESC, r.`id`;
