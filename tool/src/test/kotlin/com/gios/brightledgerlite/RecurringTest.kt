package com.gios.brightledgerlite

import com.gios.brightledgerlite.ledger.Recurring
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

class RecurringTest {

    private val day = 86_400L
    private val start = 1_700_000_000L

    private fun rows(vararg row: Triple<Long, Long, String>) =
        row.map { Recurring.Row(it.first, it.second, it.third) }

    /** Monthly, with the store-number decoration a real bank adds to each charge. */
    @Test
    fun `finds a monthly subscription through descriptor noise`() {
        val found = Recurring.detect(
            rows(
                Triple(start, -1099L, "POS DEBIT 0412 SPOTIFY USA 8774"),
                Triple(start + 30 * day, -1099L, "POS DEBIT 0512 SPOTIFY USA 2211"),
                Triple(start + 61 * day, -1099L, "POS DEBIT 0612 SPOTIFY USA 9034"),
            ),
        )
        assertEquals(1, found.size)
        assertEquals(Recurring.Cadence.Monthly, found[0].cadence)
        assertEquals(-1099L, found[0].amountCents)
        assertEquals(3, found[0].occurrences)
        assertTrue(found[0].name.contains("Spotify", ignoreCase = true))
    }

    @Test
    fun `projects the next charge from the observed interval`() {
        val found = Recurring.detect(
            rows(
                Triple(start, -1599L, "NETFLIX"),
                Triple(start + 30 * day, -1599L, "NETFLIX"),
                Triple(start + 60 * day, -1599L, "NETFLIX"),
            ),
        ).single()
        assertEquals(start + 60 * day, found.lastAt)
        assertEquals(start + 90 * day, found.nextAt)
    }

    @Test
    fun `recognises weekly and yearly cadences`() {
        val weekly = Recurring.detect(
            rows(
                Triple(start, -2500L, "CLEANER"),
                Triple(start + 7 * day, -2500L, "CLEANER"),
                Triple(start + 14 * day, -2500L, "CLEANER"),
                Triple(start + 21 * day, -2500L, "CLEANER"),
            ),
        ).single()
        assertEquals(Recurring.Cadence.Weekly, weekly.cadence)

        val yearly = Recurring.detect(
            rows(
                Triple(start, -9900L, "DOMAIN RENEWAL"),
                Triple(start + 365 * day, -9900L, "DOMAIN RENEWAL"),
                Triple(start + 730 * day, -9900L, "DOMAIN RENEWAL"),
            ),
        ).single()
        assertEquals(Recurring.Cadence.Yearly, yearly.cadence)
    }

    /**
     * The check that keeps the list honest. Three visits to the same coffee shop at similar
     * prices are not a subscription, and without the interval-consistency test they would
     * be the bulk of what this screen showed.
     */
    @Test
    fun `irregular visits to the same merchant are not a subscription`() {
        val found = Recurring.detect(
            rows(
                Triple(start, -450L, "BLUE BOTTLE"),
                Triple(start + 2 * day, -455L, "BLUE BOTTLE"),
                Triple(start + 19 * day, -450L, "BLUE BOTTLE"),
            ),
        )
        assertTrue(found.isEmpty())
    }

    @Test
    fun `two charges are not enough`() {
        val found = Recurring.detect(
            rows(
                Triple(start, -1099L, "SPOTIFY"),
                Triple(start + 30 * day, -1099L, "SPOTIFY"),
            ),
        )
        assertTrue(found.isEmpty())
    }

    @Test
    fun `deposits are never recurring charges`() {
        val found = Recurring.detect(
            rows(
                Triple(start, 250_000L, "ACME PAYROLL"),
                Triple(start + 14 * day, 250_000L, "ACME PAYROLL"),
                Triple(start + 28 * day, 250_000L, "ACME PAYROLL"),
            ),
        )
        assertTrue(found.isEmpty())
    }

    /** A small price rise should not split one subscription into two. */
    @Test
    fun `tolerates a modest price change`() {
        val found = Recurring.detect(
            rows(
                Triple(start, -1099L, "SPOTIFY"),
                Triple(start + 30 * day, -1099L, "SPOTIFY"),
                Triple(start + 60 * day, -1199L, "SPOTIFY"),
            ),
        )
        assertEquals(1, found.size)
    }

    /** One merchant can hold two separate plans, and averaging them helps nobody. */
    @Test
    fun `splits one merchant into separate charges when the amounts differ`() {
        val found = Recurring.detect(
            rows(
                Triple(start, -999L, "AMAZON"),
                Triple(start + 30 * day, -999L, "AMAZON"),
                Triple(start + 60 * day, -999L, "AMAZON"),
                Triple(start, -13900L, "AMAZON"),
                Triple(start + 30 * day, -13900L, "AMAZON"),
                Triple(start + 60 * day, -13900L, "AMAZON"),
            ),
        )
        assertEquals(2, found.size)
        // Ordered by what it costs a month, so the expensive plan leads.
        assertEquals(-13900L, found[0].amountCents)
    }

    /**
     * The label is what appears on screen, so the bank's scaffolding has to come off it too
     * — an earlier version showed this charge as "Pos Debit 0412 Spotify Usa 8774".
     */
    @Test
    fun `the display name drops the prefix and the store numbers`() {
        val found = Recurring.detect(
            rows(
                Triple(start, -1099L, "POS DEBIT 0412 SPOTIFY USA 8774"),
                Triple(start + 30 * day, -1099L, "POS DEBIT 0512 SPOTIFY USA 2211"),
                Triple(start + 61 * day, -1099L, "POS DEBIT 0612 SPOTIFY USA 9034"),
            ),
        ).single()
        assertEquals("Spotify", found.name)
    }

    @Test
    fun `a square terminal prefix is not the merchant`() {
        assertEquals(
            Recurring.normalize("SQ *BLUE BOTTLE 1234"),
            Recurring.normalize("BLUE BOTTLE COFFEE"),
        )
    }

    @Test
    fun `normalize strips the parts banks vary per charge`() {
        assertEquals(
            Recurring.normalize("POS DEBIT 0412 SPOTIFY USA 8774"),
            Recurring.normalize("POS DEBIT 1130 SPOTIFY USA 1002"),
        )
        assertEquals(
            Recurring.normalize("SQ *BLUE BOTTLE 1234"),
            Recurring.normalize("SQ *BLUE BOTTLE 9999"),
        )
        assertEquals("NETFLIX", Recurring.normalize("NETFLIX.COM 866-579-7172"))
        assertEquals("AMAZON", Recurring.normalize("AMAZON.COM*2J40K1PL3"))
    }

    /** A monthly and a yearly charge are compared on what they cost per month. */
    @Test
    fun `monthly cost normalizes across cadences`() {
        val yearly = Recurring.Charge(
            name = "Domain",
            amountCents = -12_000L,
            cadence = Recurring.Cadence.Yearly,
            lastAt = start,
            nextAt = start + 365 * day,
            occurrences = 3,
        )
        assertEquals(986L, Recurring.monthlyCostCents(yearly))
    }
}
