package com.gios.brightledgerlite

import com.gios.brightledgerlite.simplefin.SfParser
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * The parser, against payloads taken from the SimpleFIN v2.0.0 specification.
 *
 * The sandbox these were written in cannot reach bridge.simplefin.org, so the fixtures are
 * the spec's own examples rather than recorded traffic. That is a real limitation and worth
 * naming: it proves the parser matches the document, not that it matches the Bridge.
 */
class SfParserTest {

    /** Verbatim from the spec's `GET /accounts` sample response. */
    private val specSample = """
        {
          "errlist": [],
          "connections": [
            {
              "conn_id": "10829309823094234",
              "name": "My Bank - Jeff",
              "org_id": "INST-982394823948230-2340923094",
              "org_url": "https://mybank.com",
              "sfin_url": "https://sfin.mybank.com"
            }
          ],
          "accounts": [
            {
              "id": "2930002",
              "name": "Savings",
              "conn_id": "10829309823094234",
              "currency": "USD",
              "balance": "100.23",
              "available-balance": "75.23",
              "balance-date": 978366153,
              "transactions": [
                {
                  "id": "12394832938403",
                  "posted": 793090572,
                  "amount": "-33293.43",
                  "description": "Uncle Frank's Bait Shop"
                }
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `parses the spec sample`() {
        val set = SfParser.parseAccountSet(specSample)
        assertTrue(set.errors.isEmpty())
        assertEquals(1, set.connections.size)
        assertEquals("My Bank - Jeff", set.connections[0].name)

        assertEquals(1, set.accounts.size)
        val account = set.accounts[0]
        assertEquals("2930002", account.id)
        assertEquals("Savings", account.name)
        assertEquals("USD", account.currency)
        assertEquals(10023L, account.balanceCents)
        assertEquals(7523L, account.availableBalanceCents)
        assertEquals(978366153L, account.balanceDate)

        val txn = account.transactions.single()
        assertEquals("12394832938403", txn.id)
        assertEquals(-3329343L, txn.amountCents)
        assertEquals("Uncle Frank's Bait Shop", txn.description)
        assertFalse(txn.pending)
    }

    @Test
    fun `reads structured errors and flags the ones needing a reconnect`() {
        val body = """
            {
              "errlist": [
                {"code": "con.auth", "msg": "Authentication required",
                 "conn_id": "CON-1"},
                {"code": "act.missingdata", "msg": "Try again later",
                 "account_id": "ACT-9"}
              ],
              "connections": [],
              "accounts": []
            }
        """.trimIndent()
        val set = SfParser.parseAccountSet(body)
        assertEquals(2, set.errors.size)
        assertEquals("con", set.errors[0].prefix)
        assertTrue(set.errors[0].needsReauth)
        assertEquals("CON-1", set.errors[0].connId)
        assertEquals("act", set.errors[1].prefix)
        assertFalse(set.errors[1].needsReauth)
        assertEquals("ACT-9", set.errors[1].accountId)
    }

    /** v2 deprecated the bare-string `errors` list but did not remove it. */
    @Test
    fun `still reads the deprecated v1 errors array`() {
        val body = """
            {"errors": ["Something went wrong"], "connections": [], "accounts": []}
        """.trimIndent()
        val set = SfParser.parseAccountSet(body)
        assertEquals(1, set.errors.size)
        assertEquals("Something went wrong", set.errors[0].msg)
        assertEquals("gen", set.errors[0].prefix)
    }

    /**
     * A pending row may carry `posted = 0`. Ordering on `posted` would sink the charge you
     * made ten minutes ago to the bottom of the feed, so `effectiveAt` prefers
     * `transacted_at`.
     */
    @Test
    fun `a pending transaction with no posted date still sorts by when it happened`() {
        val body = """
            {
              "errlist": [], "connections": [],
              "accounts": [{
                "id": "a1", "name": "Card", "currency": "USD",
                "balance": "-50.00", "balance-date": 1,
                "transactions": [
                  {"id": "t1", "posted": 0, "transacted_at": 1735689600,
                   "amount": "-12.50", "description": "COFFEE", "pending": true}
                ]
              }]
            }
        """.trimIndent()
        val txn = SfParser.parseAccountSet(body).accounts.single().transactions.single()
        assertTrue(txn.pending)
        assertEquals(0L, txn.posted)
        assertEquals(1735689600L, txn.effectiveAt)
    }

    @Test
    fun `a malformed row is dropped without taking the response with it`() {
        val body = """
            {
              "errlist": [], "connections": [],
              "accounts": [
                {"id": "good", "name": "Checking", "currency": "USD",
                 "balance": "10.00", "balance-date": 1,
                 "transactions": [
                   {"id": "ok", "posted": 100, "amount": "-1.00", "description": "A"},
                   {"posted": 100, "amount": "-2.00", "description": "no id"},
                   {"id": "bad-amount", "posted": 100, "amount": "N/A", "description": "B"}
                 ]},
                {"name": "no id at all", "currency": "USD", "balance": "1.00"},
                {"id": "no-balance", "name": "Broken", "currency": "USD"}
              ]
            }
        """.trimIndent()
        val set = SfParser.parseAccountSet(body)
        assertEquals(1, set.accounts.size)
        assertEquals("good", set.accounts[0].id)
        assertEquals(1, set.accounts[0].transactions.size)
        assertEquals("ok", set.accounts[0].transactions[0].id)
    }

    /**
     * `optString` hands back the four-character string "null" for a JSON null, which would
     * otherwise be drawn on screen as an account called null.
     */
    @Test
    fun `a json null does not become the text null`() {
        val body = """
            {
              "errlist": [], "connections": [],
              "accounts": [{
                "id": "a1", "name": null, "conn_id": null, "currency": null,
                "balance": "5.00", "balance-date": 10, "transactions": []
              }]
            }
        """.trimIndent()
        val account = SfParser.parseAccountSet(body).accounts.single()
        assertEquals("a1", account.name)
        assertNull(account.connId)
        assertEquals("USD", account.currency)
    }

    @Test
    fun `available balance is optional`() {
        val body = """
            {"errlist": [], "connections": [], "accounts": [
              {"id": "a", "name": "A", "currency": "USD", "balance": "1.00",
               "balance-date": 1, "transactions": []}
            ]}
        """.trimIndent()
        assertNull(SfParser.parseAccountSet(body).accounts.single().availableBalanceCents)
    }

    @Test
    fun `an empty account set is not an error`() {
        val set = SfParser.parseAccountSet("""{"errlist": [], "connections": [], "accounts": []}""")
        assertTrue(set.accounts.isEmpty())
        assertTrue(set.errors.isEmpty())
    }
}
