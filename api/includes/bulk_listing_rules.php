<?php
// =============================================================
// includes/bulk_listing_rules.php — the discount bounds for surplus listings.
// =============================================================
// The 30-70 range used to be written out as four literals: the create
// validation in api/Bulk-listings.php, the correction validation in
// admin/Bulk-listings.php, and that page's min="" / max="" form attributes.
// Moving the floor meant finding all four, and missing the HTML pair left an
// admin unable to submit a correction the server would have accepted --
// browser-side validation with no server error to explain it.
//
// The app has its own copy in CreateBulkListingRequest.DISCOUNT_RANGE
// (SurplusModels.kt). That one cannot read this file, so the two have to be
// changed together; the app checks locally only to save a round trip, and the
// server remains the authority.
//
// These bounds are for SURPLUS listings. A wholesale listing carries a real
// price rather than a discount off one, so it is not subject to them.
// =============================================================

/** Lowest discount a surplus listing may carry, inclusive. */
const SURPLUS_DISCOUNT_MIN = 10.0;

/** Highest discount a surplus listing may carry, inclusive. */
const SURPLUS_DISCOUNT_MAX = 70.0;

/** The message every rejection uses, so the bounds are never restated by hand. */
function surplusDiscountRangeMessage(): string {
    return 'Discount must be between ' . rtrim(rtrim(number_format(SURPLUS_DISCOUNT_MIN, 2, '.', ''), '0'), '.')
        . '% and ' . rtrim(rtrim(number_format(SURPLUS_DISCOUNT_MAX, 2, '.', ''), '0'), '.') . '%';
}

/** Whether $percent is an acceptable surplus discount. */
function isValidSurplusDiscount($percent): bool {
    return (float)$percent >= SURPLUS_DISCOUNT_MIN && (float)$percent <= SURPLUS_DISCOUNT_MAX;
}
