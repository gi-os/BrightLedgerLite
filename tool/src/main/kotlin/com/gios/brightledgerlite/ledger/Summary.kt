package com.gios.brightledgerlite.ledger

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The two numbers the home screen leads with: spent today, spent this month.
 *
 * Pure, and takes its clock as a parameter. "This month" is a question about the phone's
 * local calendar, and a function that reads the system clock and the default zone cannot be
 * tested against a month boundary — which is the only interesting case.
 */
object Summary {

    data class Totals(
        /** Money out today, as a positive number of cents. */
        val todayCents: Long,
        /** Money out since the first of the month, as a positive number of cents. */
        val monthCents: Long,
        /** Money in since the first of the month. Shown only when non-zero. */
        val monthInCents: Long,
        /** How many of the counted rows have not posted yet. */
        val pendingCount: Int,
    )

    /**
     * Amounts arrive with SimpleFIN's sign convention — negative is money leaving — and are
     * flipped here so the screen never has to render a minus sign in front of a spend total.
     *
     * Deposits are excluded from the spend figures rather than netted against them. A
     * paycheck landing on the 1st would otherwise make the month read as though nothing had
     * been spent, which is the opposite of what the number is for.
     */
    fun of(
        rows: List<Row>,
        now: Instant,
        zone: ZoneId,
    ): Totals {
        val today = LocalDate.ofInstant(now, zone)
        val firstOfMonth = today.withDayOfMonth(1)

        var todayOut = 0L
        var monthOut = 0L
        var monthIn = 0L
        var pending = 0

        for (row in rows) {
            if (row.at <= 0L) continue
            val date = LocalDate.ofInstant(Instant.ofEpochSecond(row.at), zone)
            if (date.isBefore(firstOfMonth)) continue

            if (row.amountCents < 0) {
                val spent = -row.amountCents
                monthOut += spent
                if (date == today) todayOut += spent
            } else {
                monthIn += row.amountCents
            }
            if (row.pending) pending++
        }

        return Totals(
            todayCents = todayOut,
            monthCents = monthOut,
            monthInCents = monthIn,
            pendingCount = pending,
        )
    }

    /** The only shape the summary needs, so it does not depend on Room or on the wire types. */
    data class Row(
        val at: Long,
        val amountCents: Long,
        val pending: Boolean = false,
    )
}
