package com.techaus.afamfresh.utils

/**
 * Decodes Google's encoded polyline format.
 *
 * The Routes API returns a route's geometry as a compact ASCII string rather
 * than a list of coordinates — a few hundred bytes instead of tens of
 * kilobytes. This turns it back into points.
 *
 * WHY THIS IS HAND-WRITTEN
 *
 * `maps-android-utils` has a `PolyUtil.decode()` that does exactly this. It is
 * not a dependency, and adding one for thirty lines of arithmetic means new R8
 * surface in three release builds — and this project has already lost time to a
 * transitive R8 conflict that only appeared in release (see the osmdroid note
 * in build.gradle.kts). At sixteen minutes a release build, that trade is not
 * close.
 *
 * WHY IT RETURNS PAIRS, NOT LatLng
 *
 * `LatLng` is a Maps SDK type. Keeping this free of it means the file lives in
 * `src/main`, is unit-testable on the JVM without an emulator, and does not
 * drag the Maps SDK into anything that merely wants to measure a route.
 *
 * The algorithm is Google's published one: each coordinate is stored as a delta
 * from the previous, multiplied by 1e5, zig-zag encoded so negatives stay
 * small, then split into 5-bit chunks with a continuation bit and offset by 63
 * to land in printable ASCII.
 */
object PolylineDecoder {

    /**
     * @return the points in order, as (latitude, longitude). Empty for a null,
     *         blank or malformed input — a route that cannot be drawn is not an
     *         error worth crashing a tracking screen over.
     */
    fun decode(encoded: String?): List<Pair<Double, Double>> {
        if (encoded.isNullOrEmpty()) return emptyList()

        val points = ArrayList<Pair<Double, Double>>()
        var index = 0
        var lat = 0
        var lng = 0

        try {
            while (index < encoded.length) {
                // Latitude delta, then longitude delta, same scheme for both.
                var shift = 0
                var result = 0
                var b: Int
                do {
                    b = encoded[index++].code - 63
                    result = result or ((b and 0x1f) shl shift)
                    shift += 5
                } while (b >= 0x20)
                // Zig-zag: the low bit is the sign.
                lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1

                shift = 0
                result = 0
                do {
                    b = encoded[index++].code - 63
                    result = result or ((b and 0x1f) shl shift)
                    shift += 5
                } while (b >= 0x20)
                lng += if (result and 1 != 0) (result shr 1).inv() else result shr 1

                points.add(Pair(lat / 1e5, lng / 1e5))
            }
        } catch (e: StringIndexOutOfBoundsException) {
            // A truncated string decodes fine up to the break. Returning what
            // was read draws most of the route; throwing would draw none of it.
            return points
        }

        return points
    }
}