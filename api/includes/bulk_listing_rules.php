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

// =============================================================
// Order limits — may this quantity be ordered from this listing?
// =============================================================
// ONE implementation, called by both api/api/Bulk-orders.php (which refuses)
// and api/api/Bulk-quote.php (which warns). They were separate before the
// quote learned these rules at all, which meant a customer could be given a
// clean total and a working Pay button and only be refused after committing —
// the worst possible place to find out.
//
// Keeping it in one function is the point. Two copies of a rule that must
// agree is the same mistake as the discount range having lived in four places.

/** Trims 20.000 to "20" and 2.500 to "2.5" for a message. */
function tidyBulkQuantity($n): string {
    return rtrim(rtrim(number_format((float)$n, 3, '.', ''), '0'), '.');
}

/**
 * Why this order may not be placed, or null if it may.
 *
 * @param array $listing Needs listing_type, discounted_price, min_order_quantity,
 *                       weight_per_unit_kg, is_weight_based.
 * @param float $quantity In the listing's own unit.
 * @param array $limits   From BulkDeliverySettings(): max_weight_kg,
 *                        min_order_value, min_weight_kg.
 * @return array{code:string,error:string}|null
 */
function bulkOrderViolation(array $listing, float $quantity, array $limits): ?array {
    $weightPerUnit = (float)($listing['weight_per_unit_kg'] ?? 1.0) ?: 1.0;
    $totalWeightKg = $quantity * $weightPerUnit;
    $isWholesale   = (($listing['listing_type'] ?? '') === 'wholesale');

    // Applies to every order, wholesale or surplus: a limit on what can
    // physically be carried, not a commercial rule.
    if ($totalWeightKg > (float)$limits['max_weight_kg']) {
        return [
            'code'  => 'OVER_MAX_WEIGHT',
            'error' => 'Maximum order weight is ' . tidyBulkQuantity($limits['max_weight_kg'])
                     . 'kg. Your order weighs ' . number_format($totalWeightKg, 2) . 'kg',
        ];
    }

    if ($isWholesale) {
        // The wholesaler's own minimum is the authority. The platform floors
        // below deliberately do NOT apply: they would override a seller who
        // asked for less, which is what made a 5 kg wholesale minimum
        // unreachable behind the 20 kg surplus floor.
        $moq = (float)($listing['min_order_quantity'] ?? 0);
        if ($moq > 0 && $quantity < $moq) {
            return [
                'code'  => 'BELOW_MIN_QUANTITY',
                'error' => 'This seller\'s minimum order is ' . tidyBulkQuantity($moq)
                         . '. You asked for ' . tidyBulkQuantity($quantity) . '.',
            ];
        }
        return null;
    }

    $totalPrice = (float)($listing['discounted_price'] ?? 0) * $quantity;
    if ($totalPrice < (float)$limits['min_order_value']) {
        return [
            'code'  => 'BELOW_MIN_VALUE',
            'error' => 'Minimum order value for Bulk is UGX '
                     . number_format((float)$limits['min_order_value'], 0)
                     . '. Current total: UGX ' . number_format($totalPrice, 0),
        ];
    }

    if (!empty($listing['is_weight_based']) && $quantity < (float)$limits['min_weight_kg']) {
        return [
            'code'  => 'BELOW_MIN_WEIGHT',
            'error' => 'Minimum order for bulk/weight-based Bulk items is '
                     . tidyBulkQuantity($limits['min_weight_kg']) . ' kg',
        ];
    }

    return null;
}

/**
 * The limits that apply to this listing, for a client to show BEFORE the
 * customer picks a quantity.
 *
 * Shaped so the app never has to know which rules apply to which kind of
 * listing: whichever fields are relevant are non-zero, the rest are 0 and are
 * simply not rendered. That is what lets the app stop hardcoding
 * "UGX 250,000 / 20 kg / 1000 kg", which an admin can now change.
 */
function bulkOrderLimitsFor(array $listing, array $limits): array {
    $isWholesale = (($listing['listing_type'] ?? '') === 'wholesale');
    return [
        'is_wholesale'    => $isWholesale,
        'max_weight_kg'   => (float)$limits['max_weight_kg'],
        'min_quantity'    => $isWholesale ? (float)($listing['min_order_quantity'] ?? 0) : 0.0,
        'min_order_value' => $isWholesale ? 0.0 : (float)$limits['min_order_value'],
        'min_weight_kg'   => (!$isWholesale && !empty($listing['is_weight_based']))
                             ? (float)$limits['min_weight_kg'] : 0.0,
    ];
}
