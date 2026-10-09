package com.gios.brightledgerlite

import com.gios.brightledgerlite.ledger.SyncPolicy
import com.gios.brightledgerlite.simplefin.SfParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncPolicyTest {

    private val day = 86_400L
    private val now = 1_760_000_000L

    @Test
    fun `a first sync asks for about a year`() {
        assertEquals(now - 400 * day, SyncPolicy.windowStart(0L, now))
    }

    @Test
    fun `later syncs overlap the last one by two weeks`() {
        assertEquals(now - day - 14 * day, SyncPolicy.windowStart(now - day, now))
    }

    @Test
    fun `a long gap never asks for more than the first sync did`() {
        assertEquals(now - 400 * day, SyncPolicy.windowStart(now - 900 * day, now))
    }

    @Test
    fun `opening the tool does not refresh fresh data`() {
        assertFalse(SyncPolicy.shouldAutoRefresh(lastSuccess = now - 10 * 60, lastAttempt = now - 10 * 60, now = now))
        assertTrue(SyncPolicy.shouldAutoRefresh(lastSuccess = now - 31 * 60, lastAttempt = now - 31 * 60, now = now))
        assertTrue(SyncPolicy.shouldAutoRefresh(lastSuccess = 0L, lastAttempt = 0L, now = now))
    }

    /** A server that keeps failing is not hammered on every glance. */
    @Test
    fun `a recent failed attempt holds off the automatic retry`() {
        assertFalse(SyncPolicy.shouldAutoRefresh(lastSuccess = now - day, lastAttempt = now - 60, now = now))
        assertTrue(SyncPolicy.shouldAutoRefresh(lastSuccess = now - day, lastAttempt = now - 6 * 60, now = now))
    }

    @Test
    fun `sync presses inside a minute are refused`() {
        assertFalse(SyncPolicy.canManualRefresh(lastAttempt = now - 30, now = now))
        assertTrue(SyncPolicy.canManualRefresh(lastAttempt = now - 61, now = now))
        // A clock set backwards must not lock the button.
        assertTrue(SyncPolicy.canManualRefresh(lastAttempt = now + 3600, now = now))
    }

    /** Servers vary; a timestamp or a flag sent as a string still reads. */
    @Test
    fun `the parser tolerates numbers and flags sent as strings`() {
        val body = """
            {"errlist": [], "connections": [], "accounts": [
              {"id": 42, "name": "A", "currency": "USD", "balance": "1.00", "balance-date": "1700000000",
               "transactions": [
                 {"id": "t", "posted": "1700000000", "amount": "-2.50", "description": "X", "pending": "1"}
               ]}
            ]}
        """.trimIndent()
        val account = SfParser.parseAccountSet(body).accounts.single()
        assertEquals("42", account.id)
        assertEquals(1_700_000_000L, account.balanceDate)
        val txn = account.transactions.single()
        assertEquals(1_700_000_000L, txn.posted)
        assertEquals(-250L, txn.amountCents)
        assertTrue(txn.pending)
    }
}
