package com.techaus.afamfresh.utils

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Bulk quantities became Doubles when the columns were widened to DECIMAL(12,3).
 * Rendering them raw gives "100.0 kg left", which reads like a rounding
 * artefact, and the old digit-only input filter made a decimal impossible to
 * type at all.
 */
class QuantityFormatTest {

    @Test
    fun `whole numbers lose the decimal point`() {
        assertEquals("100", formatQuantity(100.0))
        assertEquals("0", formatQuantity(0.0))
        assertEquals("5000", formatQuantity(5000.0))
    }

    @Test
    fun `fractions keep only the digits that matter`() {
        assertEquals("20.5", formatQuantity(20.5))
        assertEquals("250.5", formatQuantity(250.500))
        assertEquals("33.333", formatQuantity(33.333))
        assertEquals("7.25", formatQuantity(7.25))
    }

    @Test
    fun `float noise below the column's precision is not shown`() {
        // DECIMAL(12,3) cannot store more than three places, so anything
        // beyond that is arithmetic noise rather than data.
        assertEquals("20.5", formatQuantity(20.5000001))
        assertEquals("100", formatQuantity(99.9999999))
    }

    @Test
    fun `unit is appended, and a missing one does not leave a trailing space`() {
        assertEquals("20.5 kg", formatQuantity(20.5, "kg"))
        assertEquals("100", formatQuantity(100.0, null))
        assertEquals("100", formatQuantity(100.0, ""))
    }

    @Test
    fun `decimal input accepts what a vendor needs to type`() {
        assertEquals("20.5", decimalInput("20.5"))
        assertEquals("20.5", decimalInput("20.5kg"))
        // A locale keypad emitting a comma still parses.
        assertEquals("20.5", decimalInput("20,5"))
        assertEquals("250.500", decimalInput("250.500"))
    }

    @Test
    fun `decimal input refuses what would not parse`() {
        // One separator only.
        assertEquals("20.55", decimalInput("20.5.5"))
        // Cannot lead with a separator.
        assertEquals("5", decimalInput(".5"))
        // Capped at the column's three places.
        assertEquals("1.234", decimalInput("1.23456"))
        assertEquals("", decimalInput("abc"))
    }

    @Test
    fun `the digit-only filter this replaced would have mangled a decimal`() {
        val oldFilter = { s: String -> s.filter { it.isDigit() } }
        assertEquals("205", oldFilter("20.5"))      // 20.5 kg became 205 kg
        assertEquals("20.5", decimalInput("20.5"))
    }
}
