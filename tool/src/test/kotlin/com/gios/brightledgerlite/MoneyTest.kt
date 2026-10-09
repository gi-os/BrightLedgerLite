package com.gios.brightledgerlite

import com.gios.brightledgerlite.simplefin.Money
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test

class MoneyTest {

    @Test
    fun `parses the spec's own example amounts`() {
        assertEquals(10023L, Money.parseCents("100.23"))
        assertEquals(-3329343L, Money.parseCents("-33293.43"))
        assertEquals(7523L, Money.parseCents("75.23"))
    }

    @Test
    fun `accepts amounts a server may reasonably send`() {
        assertEquals(500L, Money.parseCents("5"))
        assertEquals(500L, Money.parseCents("5.00"))
        assertEquals(-4L, Money.parseCents("-0.04"))
        assertEquals(10023L, Money.parseCents("  100.23  "))
        assertEquals(123456789L, Money.parseCents("1234567.89"))
    }

    /**
     * The reason this type exists. A float cannot hold 0.1 and summing a month of them
     * drifts; the whole pipeline is integer cents from the parse onwards.
     */
    @Test
    fun `a month of small amounts sums exactly`() {
        val rows = List(1000) { Money.parseCents("0.10")!! }
        assertEquals(10000L, rows.sum())
        assertEquals("100.00", Money.format(rows.sum()))
    }

    @Test
    fun `rounds a third decimal rather than truncating it`() {
        assertEquals(1006L, Money.parseCents("10.055"))
        assertEquals(1005L, Money.parseCents("10.054"))
    }

    @Test
    fun `junk is null, not zero`() {
        assertNull(Money.parseCents(null))
        assertNull(Money.parseCents(""))
        assertNull(Money.parseCents("   "))
        assertNull(Money.parseCents("N/A"))
        assertNull(Money.parseCents("1,234.56"))
        assertNull(Money.parseCents("$5.00"))
    }

    @Test
    fun `formats with thousands separators`() {
        assertEquals("0.00", Money.format(0))
        assertEquals("0.07", Money.format(7))
        assertEquals("9.99", Money.format(999))
        assertEquals("1,000.00", Money.format(100_000))
        assertEquals("1,234,567.89", Money.format(123_456_789))
        assertEquals("-1,234.56", Money.format(-123_456))
    }

    @Test
    fun `signed formatting marks credits, because grey cannot`() {
        assertEquals("+42.00", Money.formatSigned(4200))
        assertEquals("-42.00", Money.formatSigned(-4200))
        assertEquals("0.00", Money.formatSigned(0))
    }

    @Test
    fun `currency falls back to the code for anything unusual`() {
        assertEquals("$", Money.symbol("USD"))
        assertEquals("€", Money.symbol("EUR"))
        assertEquals("$", Money.symbol(null))
        assertEquals("ZMW ", Money.symbol("ZMW"))
        // A custom currency is a URL in this protocol, and prefixing an amount with one
        // would be nonsense.
        assertEquals("", Money.symbol("https://example.com/flight-miles"))
    }
}
