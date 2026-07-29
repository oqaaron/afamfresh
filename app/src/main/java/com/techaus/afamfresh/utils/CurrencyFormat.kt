package com.techaus.afamfresh.utils

import java.util.Locale
import kotlin.math.roundToLong

/**
 * Single source of truth for displaying money in this app.
 *
 * The business operates in Uganda, so every price is shown in Ugandan Shillings.
 * Nothing outside this file should build a currency string by hand — if the
 * format ever needs to change, it changes here and nowhere else.
 *
 * Two deliberate decisions:
 *
 *  - **No decimals.** UGX has a subunit (the cent) but it is not used in
 *    practice; prices are quoted and paid in whole shillings. Amounts are
 *    rounded to the nearest shilling for display.
 *
 *  - **Locale.US grouping, not the device locale.** This fixes the thousands
 *    separator as a comma ("UGX 12,500"). Formatting with the device locale
 *    would render the same amount as "UGX 12.500" on a phone set to German or
 *    Indonesian, which reads as twelve-point-five to a Ugandan customer. The
 *    separator is a property of how we quote prices, not of the user's phone.
 *
 * Note that this affects **display only**. Amounts are still held as Double all
 * the way through the cart, checkout and API layers, so rounding here never
 * changes what is actually charged.
 */

/** ISO 4217 code for the Ugandan Shilling. */
const val CURRENCY_CODE = "UGX"

/**
 * Formats an amount as e.g. `UGX 12,500`.
 *
 * Rounds to the nearest whole shilling (half away from zero).
 */
fun formatUgx(amount: Double): String =
    "$CURRENCY_CODE ${groupDigits(amount.roundToLong())}"

/** Overload for amounts already known to be whole shillings. */
fun formatUgx(amount: Long): String =
    "$CURRENCY_CODE ${groupDigits(amount)}"

/** Overload for amounts already known to be whole shillings. */
fun formatUgx(amount: Int): String =
    "$CURRENCY_CODE ${groupDigits(amount.toLong())}"

/**
 * Groups digits without the currency code — for the rare place that needs the
 * bare number, e.g. prefilling an editable price field.
 */
fun formatAmountOnly(amount: Double): String = groupDigits(amount.roundToLong())

/**
 * `String.format` is used rather than a shared `NumberFormat` instance because
 * `NumberFormat` is not thread-safe, and these helpers are called from both
 * composition and background callbacks.
 */
private fun groupDigits(value: Long): String = String.format(Locale.US, "%,d", value)
