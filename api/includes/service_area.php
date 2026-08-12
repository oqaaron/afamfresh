<?php
// =============================================================
// includes/service_area.php
//
// Where AfamFresh delivers: the Greater Kampala Metropolitan Area, meaning the
// districts of Kampala, Wakiso, Mpigi and Mukono.
//
// WHY THE SERVER CHECKS THIS AND NOT JUST THE MAP
//
// The app restricts its map camera to these bounds, but delivery_lat and
// delivery_lng arrive in a request body. Anything that can be typed into a
// request can be typed with any value in it, so a client-side limit is a
// convenience for honest users and nothing more.
//
// It matters here because the coordinates feed a PRICE. A pin in Gulu computes
// a ~330km leg, hits the fee cap, and produces an order at a plausible-looking
// total that no rider can fulfil. Rejecting it at the door is cheaper than
// discovering it after a customer has paid.
//
// A rectangle is deliberately rough. The four districts are not rectangular, so
// this admits slivers of Luweero to the north and Buikwe to the east. That is
// the right direction to be wrong in: a delivery just outside the line is a
// judgement call someone can make, whereas a legitimate address wrongly
// refused is a lost order with no recourse.
// =============================================================

/** Greater Kampala bounding box. Keep in step with GkmaBounds.kt in the app. */
const GKMA_SOUTH = -0.35;
const GKMA_NORTH =  0.85;
const GKMA_WEST  = 31.95;
const GKMA_EAST  = 33.15;

/** Human list of what the box is meant to cover, for error messages. */
const GKMA_DISTRICTS = 'Kampala, Wakiso, Mpigi and Mukono';

/**
 * Is this point inside the service area?
 *
 * Null coordinates answer false — "unknown" is not "inside". Callers that
 * tolerate a missing location must check for null themselves rather than
 * relying on this to wave it through.
 */
function isInServiceArea(?float $lat, ?float $lng): bool {
    if ($lat === null || $lng === null) return false;

    // Exactly 0,0 is Null Island: far more often an uninitialised variable than
    // a real pin, and nowhere near Uganda. Caught here so it cannot ride
    // through as a valid-looking coordinate pair.
    if (abs($lat) < 0.0001 && abs($lng) < 0.0001) return false;

    return $lat >= GKMA_SOUTH && $lat <= GKMA_NORTH
        && $lng >= GKMA_WEST  && $lng <= GKMA_EAST;
}

/** The message shown when a point falls outside. Names the districts. */
function serviceAreaMessage(): string {
    return 'We only deliver within Greater Kampala — ' . GKMA_DISTRICTS
         . '. Please choose a location inside that area.';
}