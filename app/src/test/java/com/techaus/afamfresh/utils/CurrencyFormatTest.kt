package com.techaus.afamfresh.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class CurrencyFormatTest {

    @Test
    fun `formats whole shillings with thousands separators`() {
        assertEquals("UGX 12,500", formatUgx(12500.0))
        assertEquals("UGX 1,000,000", formatUgx(1_000_000.0))
    }

    @Test
    fun `small amounts have no separator`() {
        assertEquals("UGX 0", formatUgx(0.0))
        assertEquals("UGX 999", formatUgx(999.0))
        assertEquals("UGX 1,000", formatUgx(1000.0))
    }

    @Test
    fun `rounds to the nearest shilling because UGX has no subunit in practice`() {
        assertEquals("UGX 2,500", formatUgx(2499.5))
        assertEquals("UGX 2,499", formatUgx(2499.4))
        assertEquals("UGX 2,500", formatUgx(2500.49))
    }

    @Test
    fun `negative amounts keep their sign`() {
        // Refunds and adjustments should not silently render as positive.
        assertEquals("UGX -1,200", formatUgx(-1200.0))
    }

    @Test
    fun `Int and Long overloads agree with the Double one`() {
        assertEquals(formatUgx(12500.0), formatUgx(12500))
        assertEquals(formatUgx(12500.0), formatUgx(12500L))
    }

    @Test
    fun `amount-only variant omits the currency code`() {
        assertEquals("12,500", formatAmountOnly(12500.0))
    }

    /**
     * Grouping is pinned to Locale.US on purpose. If this ever starts using the
     * device locale, the same amount renders as "UGX 12.500" on a German or
     * Indonesian phone, which a Ugandan customer reads as twelve-point-five.
     */
    @Test
    fun `grouping separator is a comma regardless of default locale`() {
        val original = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("UGX 12,500", formatUgx(12500.0))
        } finally {
            java.util.Locale.setDefault(original)
        }
    }

    @Test
    fun `currency code is UGX`() {
        assertEquals("UGX", CURRENCY_CODE)
    }
}
