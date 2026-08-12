package com.techaus.afamfresh.utils

/**
 * Where AfamFresh delivers: the Greater Kampala Metropolitan Area — the
 * districts of Kampala, Wakiso, Mpigi and Mukono.
 *
 * **Keep these numbers in step with `includes/service_area.php`.** The server
 * checks the same box and refuses anything outside it, because coordinates
 * arrive in a request body and a client-side restriction is a convenience for
 * honest users rather than a control. If the two disagree, a customer can pin a
 * point the map allowed and then be told at checkout that it is out of area.
 *
 * A rectangle is deliberately rough. The four districts are not rectangular, so
 * this admits slivers of Luweero to the north and Buikwe to the east. That is
 * the right direction to be wrong in: a delivery just outside the line is a
 * judgement someone can make, whereas a legitimate address wrongly refused is a
 * lost order with no recourse.
 */
object GkmaBounds {
    const val SOUTH = -0.35
    const val NORTH = 0.85
    const val WEST = 31.95
    const val EAST = 33.15

    /** Roughly central Kampala — where the map opens before anything is picked. */
    const val CENTER_LAT = 0.3476
    const val CENTER_LNG = 32.5825

    /** Named in the message so a refusal says what the area actually is. */
    const val DISTRICTS = "Kampala, Wakiso, Mpigi and Mukono"

    const val OUT_OF_AREA_MESSAGE =
        "We only deliver within Greater Kampala — $DISTRICTS. " +
            "Please choose a location inside that area."

    /**
     * Null coordinates answer false: "unknown" is not "inside". Callers that
     * tolerate a missing location must check for that themselves.
     *
     * Exactly 0,0 is rejected too — Null Island is far more often an
     * uninitialised variable than a real pin, and it is nowhere near Uganda.
     */
    fun contains(lat: Double?, lng: Double?): Boolean {
        if (lat == null || lng == null) return false
        if (kotlin.math.abs(lat) < 0.0001 && kotlin.math.abs(lng) < 0.0001) return false
        return lat in SOUTH..NORTH && lng in WEST..EAST
    }
}