package com.techaus.afamfresh.utils

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Renders a Bulk quantity for display.
 *
 * Bulk produce is sold in decimal kilograms, so these values are Doubles — but
 * most of them are whole, and "100.0 kg left" reads like a rounding artefact.
 * This drops the fractional part when there isn't one and trims trailing zeros
 * when there is, so 100.0 shows as "100" and 20.500 as "20.5".
 *
 * The columns are DECIMAL(12,3), so three places is the most that can be
 * stored and anything beyond that is float noise rather than data.
 */
fun formatQuantity(value: Double): String {
    if (!value.isFinite()) return "0"
    val rounded = (value * 1000.0).roundToLong() / 1000.0
    if (abs(rounded - rounded.roundToLong()) < 0.0005) return rounded.roundToLong().toString()
    return rounded.toString().trimEnd('0').trimEnd('.')
}

/** Quantity with its unit, e.g. "20.5 kg". Blank unit is tolerated. */
fun formatQuantity(value: Double, unit: String?): String =
    "${formatQuantity(value)} ${unit.orEmpty()}".trim()

/**
 * Filters typed text down to a decimal number, for quantity fields.
 *
 * These fields used `filter { it.isDigit() }`, which silently swallowed the
 * decimal point — so a vendor with 20.5 kg to sell could type "205" and nothing
 * else. Keeps digits and at most one separator, and normalises a comma to a
 * dot so a keypad that emits "," on a locale keyboard still parses.
 */
fun decimalInput(raw: String, maxDecimals: Int = 3): String {
    val normalised = raw.replace(',', '.')
    val sb = StringBuilder()
    var seenDot = false
    var decimals = 0
    for (c in normalised) {
        when {
            c.isDigit() && (!seenDot || decimals < maxDecimals) -> {
                sb.append(c)
                if (seenDot) decimals++
            }
            c == '.' && !seenDot && sb.isNotEmpty() -> {
                sb.append(c)
                seenDot = true
            }
        }
    }
    return sb.toString()
}
