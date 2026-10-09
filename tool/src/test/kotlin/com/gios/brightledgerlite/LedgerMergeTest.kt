package com.gios.brightledgerlite

import com.gios.brightledgerlite.ledger.Ledger
import com.gios.brightledgerlite.simplefin.SfAccount
import com.gios.brightledgerlite.simplefin.SfAccountSet
import com.gios.brightledgerlite.simplefin.SfConnection
import com.gios.brightledgerlite.simplefin.SfError
import com.gios.brightledgerlite.simplefin.SfTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LedgerMergeTest {

    private val day = 86_400L
    private val now = 1_760_000_000L
    private val window = now - 14 * day
    private val keep = now - 400 * day

    private fun txn(id: String, at: Long, cents: Long, pending: Boolean = false, desc: String = "X") =
        SfTransaction(id = id, posted = if (pending) 0L else at, amountCents = cents, description = desc,
            transactedAt = at, pending = pending)

    private fun account(id: String, vararg txns: SfTransaction, conn: String? = "c1", balance: Long = 1000) =
        SfAccount(id, "Acct $id", conn, "USD", balance, null, now, txns.toList())

    private fun set(vararg accounts: SfAccount, errors: List<SfError> = emptyList()) =
        SfAccountSet(errors, listOf(SfConnection("c1", "My Bank", "org")), accounts.toList())

    private fun merge(old: Ledger, data: SfAccountSet) = Ledger.merge(old, data, window, now, keep)

    @Test
    fun `first sync stores accounts and transactions`() {
        val ledger = merge(Ledger(), set(account("a", txn("t1", now - day, -500))))
        assertEquals(1, ledger.accounts.size)
        assertEquals("My Bank", ledger.accounts[0].connName)
        assertEquals(1, ledger.transactions.size)
        assertEquals(now, ledger.lastSuccess)
    }

    /** The protocol lets two accounts reuse a transaction id; neither may overwrite the other. */
    @Test
    fun `the same transaction id in two accounts is two rows`() {
        val ledger = merge(Ledger(), set(account("a", txn("1", now - day, -100)), account("b", txn("1", now - day, -200))))
        assertEquals(2, ledger.transactions.size)
        assertEquals(-100L, ledger.transaction("a", "1")?.amountCents)
        assertEquals(-200L, ledger.transaction("b", "1")?.amountCents)
    }

    @Test
    fun `a re-fetched row replaces the cached copy`() {
        val first = merge(Ledger(), set(account("a", txn("t1", now - day, -500, pending = true))))
        val second = merge(first, set(account("a", txn("t1", now - day, -650))))
        val row = second.transactions.single()
        assertEquals(-650L, row.amountCents)
        assertTrue(!row.pending)
    }

    /** A released hold vanishes from the server and must vanish here too. */
    @Test
    fun `pending rows the server stopped returning are swept inside the window`() {
        val first = merge(Ledger(), set(account("a", txn("hold", now - 2 * day, -9000, pending = true), txn("ok", now - 2 * day, -100))))
        val second = merge(first, set(account("a", txn("ok", now - 2 * day, -100))))
        assertNull(second.transaction("a", "hold"))
        assertNotNull(second.transaction("a", "ok"))
    }

    @Test
    fun `settled history older than the window is kept`() {
        val old = Ledger(
            accounts = listOf(Ledger.Account("a", "A", balanceCents = 0)),
            transactions = listOf(Ledger.Txn("a", "old", now - 100 * day, now - 100 * day, -300, "OLD")),
        )
        val merged = merge(old, set(account("a", txn("new", now - day, -1))))
        assertNotNull(merged.transaction("a", "old"))
        assertNotNull(merged.transaction("a", "new"))
    }

    @Test
    fun `history past the keep limit is dropped`() {
        val old = Ledger(transactions = listOf(Ledger.Txn("a", "ancient", now - 500 * day, now - 500 * day, -1, "A")))
        assertTrue(merge(old, set(account("a"))).transactions.isEmpty())
    }

    /** A bank that is down for a day should not make its balance disappear. */
    @Test
    fun `an account missing from a response is kept`() {
        val first = merge(Ledger(), set(account("a"), account("b")))
        val second = merge(first, set(account("a", balance = 5)))
        assertEquals(2, second.accounts.size)
        assertEquals(5L, second.account("a")?.balanceCents)
    }

    @Test
    fun `pending rows of an account that was not fetched are left alone`() {
        val first = merge(Ledger(), set(account("a"), account("b", txn("p", now - day, -10, pending = true))))
        val second = merge(first, set(account("a")))
        assertNotNull(second.transaction("b", "p"))
    }

    @Test
    fun `server errors attach to the accounts they name`() {
        val data = set(
            account("a"), account("b", conn = "c2"),
            errors = listOf(SfError("con.auth", "Reconnect your bank", connId = "c2")),
        )
        val ledger = merge(Ledger(), data)
        assertNull(ledger.account("a")?.problem)
        assertEquals("Reconnect your bank", ledger.account("b")?.problem)
    }

    @Test
    fun `an account list shows pending first, then newest, and stops at the cutoff`() {
        val ledger = merge(
            Ledger(),
            set(account("a",
                txn("old", now - 90 * day, -1),
                txn("mid", now - 10 * day, -2),
                txn("new", now - day, -3),
                txn("pend", now - 5 * day, -4, pending = true),
            )),
        )
        val rows = ledger.transactionsFor("a", now - 60 * day).map { it.txnId }
        assertEquals(listOf("pend", "new", "mid"), rows)
    }
}
