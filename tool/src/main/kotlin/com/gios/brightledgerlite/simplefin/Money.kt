package com.gios.brightledgerlite.simplefin

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Money, as whole cents in a Long.
 *
 * SimpleFIN sends amounts as *numeric strings* — "-33293.43", "100.23" — and the spec is
 * explicit that they are strings rather than numbers. Parsing them into a Double and
 * summing a month of them is how a spend total ends up reading 1847.9999999998, so nothing
 * in this app ever holds an amount as a floating point number. BigDecimal does the parse,
 * cents do the arithmetic, and formatting happens once at the edge.
 *
 * Positive is money arriving. That is SimpleFIN's convention, not a choice made here, and
 * it is why the summary code negates before it calls anything "spent".
 */
object Money {

    /**
     * Null on anything that is not a decimal number, rather than zero.
     *
     * Zero would be indistinguishable from a real zero-amount transaction, and a server
     * sending junk in one row should drop that row rather than silently record it as free.
     */
    fun parseCents(raw: String?): Long? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        return runCatching {
            BigDecimal(text).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()
        }.getOrNull()
    }

    /** "-1,234.56" style, no currency symbol. The symbol is the caller's business. */
    fun format(cents: Long): String {
        val negative = cents < 0
        val abs = if (negative) -cents else cents
        val whole = abs / 100
        val part = abs % 100
        val grouped = groupThousands(whole)
        val sign = if (negative) "-" else ""
        return sign + grouped + "." + part.toString().padStart(2, '0')
    }

    /**
     * The same, with the sign carried by a leading plus for credits.
     *
     * On a greyscale panel there is no red and green to lean on, so the sign character is
     * the only thing distinguishing money in from money out and it is always drawn.
     */
    fun formatSigned(cents: Long): String =
        if (cents > 0) "+" + format(cents) else format(cents)

    /** Symbol for the common cases, the ISO code itself for everything else. */
    fun symbol(currency: String?): String = when (currency?.uppercase()) {
        "USD" -> "$"
        "EUR" -> "€"
        "GBP" -> "£"
        "JPY" -> "¥"
        "CAD" -> "CA$"
        "AUD" -> "AU$"
        null, "" -> "$"
        else -> if (currency.startsWith("http")) "" else currency.uppercase() + " "
    }

    private fun groupThousands(value: Long): String {
        val digits = value.toString()
        if (digits.length <= 3) return digits
        val out = StringBuilder()
        var count = 0
        for (index in digits.lastIndex downTo 0) {
            out.append(digits[index])
            count++
            if (count % 3 == 0 && index > 0) out.append(',')
        }
        return out.reverse().toString()
    }
}
