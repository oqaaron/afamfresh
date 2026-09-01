package com.techaus.afamfresh.utils

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

fun computeDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val earthRadius = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val sinHalfLat = sin(dLat / 2.0)
    val sinHalfLon = sin(dLon / 2.0)
    val a = (sinHalfLat * sinHalfLat) +
        (cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sinHalfLon * sinHalfLon)
    val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
    return earthRadius * c
}
