-- =============================================================
-- The Bulk base fee buys a first few kilometres; only the excess is charged
-- =============================================================
-- calculateBulkDeliveryFee() charged base_fee AND the full road distance at
-- rate_per_km, so the first kilometre was paid for twice: once inside the flat
-- base fee, and again per km. A 2 km delivery cost 5,000 base + 1,800
-- distance, which is a fee that does not describe anything real.
--
-- The base fee is now what it sounds like: a flat charge that includes the
-- first `base_included_km` kilometres. Beyond that, the standard per-km rate
-- applies to the EXCESS only.
--
--   distance_fee = MAX(0, km - base_included_km) x rate_per_km
--
-- Default 3.000 km, as requested. Set it to 0 to restore the previous
-- behaviour of charging every kilometre on top of the base fee.
--
-- This CHANGES PRICES, unlike the two migrations before it: every delivery
-- under 3 km loses its distance component entirely, and every longer one drops
-- by 3 x rate_per_km (2,700 at the default rate). That is the intent -- the
-- old figure double-charged the short leg -- but it is a real reduction in
-- delivery revenue and should be expected rather than discovered.
-- =============================================================

ALTER TABLE `Bulk_delivery_settings`
  ADD COLUMN `base_included_km` DECIMAL(6,3) NOT NULL DEFAULT 3.000
    COMMENT 'Km covered by base_fee. Only distance beyond this is charged per km.'
    AFTER `base_fee`;

-- Expect base_included_km 3.000 alongside the existing base fee and rate.
SELECT id, base_fee, base_included_km, rate_per_km, max_fee,
       free_delivery_threshold
  FROM `Bulk_delivery_settings`
 ORDER BY id;
