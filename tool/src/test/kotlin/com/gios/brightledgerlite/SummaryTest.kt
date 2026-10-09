package com.gios.brightledgerlite

import com.gios.brightledgerlite.ledger.Summary
import kotlin.test.assertEquals
import kotlin.test.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class SummaryTest {

    private val nyc = ZoneId.of("America/New_York")

    private fun at(year: Int, month: Int, day: Int, hour: Int = 12): Long =
        ZonedDateTime.of(year, month, day, hour, 0, 0, 0, nyc).toEpochSecond()

    private val now = Instant.ofEpochSecond(at(2026, 3, 17, 14))

    @Test
    fun `separates today from the rest of the month`() {
        val totals = Summary.of(
            rows = listOf(
                Summary.Row(at(2026, 3, 17, 9), -1250),
                Summary.Row(at(2026, 3, 17, 13), -800),
                Summary.Row(at(2026, 3, 16), -2000),
                Summary.Row(at(2026, 3, 1), -500),
            ),
            now = now,
            zone = nyc,
        )
        assertEquals(2050L, totals.todayCents)
        assertEquals(4550L, totals.monthCents)
    }

    /** The boundary case, and the reason the clock is a parameter rather than a call. */
    @Test
    fun `last month is excluded entirely`() {
        val totals = Summary.of(
            rows = listOf(
                Summary.Row(at(2026, 2, 28), -9999),
                Summary.Row(at(2026, 3, 1, 0), -100),
            ),
            now = now,
            zone = nyc,
        )
        assertEquals(100L, totals.monthCents)
    }

    /**
     * A paycheck must not cancel out the month's spending. Netting them would make the
     * first week of every month read as though nothing had been spent.
     */
    @Test
    fun `deposits are counted separately, not netted`() {
        val totals = Summary.of(
            rows = listOf(
                Summary.Row(at(2026, 3, 2), 300_000),
                Summary.Row(at(2026, 3, 5), -4_500),
            ),
            now = now,
            zone = nyc,
        )
        assertEquals(4_500L, totals.monthCents)
        assertEquals(300_000L, totals.monthInCents)
    }

    @Test
    fun `counts pending rows but still includes them in the totals`() {
        val totals = Summary.of(
            rows = listOf(
                Summary.Row(at(2026, 3, 17, 10), -1000, pending = true),
                Summary.Row(at(2026, 3, 17, 11), -500),
            ),
            now = now,
            zone = nyc,
        )
        assertEquals(1500L, totals.todayCents)
        assertEquals(1, totals.pendingCount)
    }

    /** A row with no usable timestamp is skipped rather than counted against epoch. */
    @Test
    fun `rows with no date are ignored`() {
        val totals = Summary.of(
            rows = listOf(Summary.Row(0L, -5000)),
            now = now,
            zone = nyc,
        )
        assertEquals(0L, totals.monthCents)
    }

    /**
     * A charge made late on the last evening of the month belongs to that month in the
     * phone's own timezone, not in UTC.
     */
    @Test
    fun `month boundaries follow the local zone`() {
        val lateOnTheFirst = at(2026, 3, 1, 20)
        val totals = Summary.of(
            rows = listOf(Summary.Row(lateOnTheFirst, -1000)),
            now = now,
            zone = nyc,
        )
        assertEquals(1000L, totals.monthCents)
    }

    @Test
    fun `an empty ledger is all zeroes`() {
        val totals = Summary.of(emptyList(), now, nyc)
        assertEquals(0L, totals.todayCents)
        assertEquals(0L, totals.monthCents)
        assertEquals(0L, totals.monthInCents)
        assertEquals(0, totals.pendingCount)
    }
}
