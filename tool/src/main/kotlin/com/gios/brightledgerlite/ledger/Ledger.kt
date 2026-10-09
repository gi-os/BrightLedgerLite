package com.gios.brightledgerlite.ledger

import com.gios.brightledgerlite.simplefin.SfAccountSet
import kotlinx.serialization.Serializable

/**
 * The cached ledger: accounts, their transactions, and when it was last refreshed.
 *
 * BrightLedger kept this in Room. The Lite tool keeps it as one JSON file, because the
 * whole thing is a few hundred kilobytes at most and holding it as a value means the sync
 * rules below are plain functions with tests, not DAO queries that only run on a device.
 *
 * Amounts are whole cents throughout (see [com.gios.brightledgerlite.simplefin.Money]).
 * Timestamps are epoch seconds, as SimpleFIN sends them.
 */
@Serializable
data class Ledger(
    val accounts: List<Account> = emptyList(),
    val transactions: List<Txn> = emptyList(),
    /** Epoch seconds of the last successful sync; 0 = never. */
    val lastSuccess: Long = 0L,
) {

    @Serializable
    data class Account(
        val id: String,
        val name: String,
        /** The bank login the account belongs to, as the server names it. */
        val connName: String? = null,
        val currency: String = "USD",
        val balanceCents: Long,
        val availableCents: Long? = null,
        val balanceDate: Long = 0L,
        /** A server-side error attached to this account on the last sync, shown verbatim. */
        val problem: String? = null,
    )

    @Serializable
    data class Txn(
        val accountId: String,
        val txnId: String,
        /** When it happened, falling back to when it posted (see SfTransaction.effectiveAt). */
        val at: Long,
        val posted: Long,
        val amountCents: Long,
        val description: String,
        val pending: Boolean = false,
    )

    fun account(id: String): Account? = accounts.firstOrNull { it.id == id }

    /**
     * One account's rows since [sinceSeconds], pending first, then newest first.
     *
     * Pending first because the amount on a pending row is the one most likely to change,
     * and the thing you bought ten minutes ago is the row you opened the screen to find.
     */
    fun transactionsFor(accountId: String, sinceSeconds: Long): List<Txn> =
        transactions
            .filter { it.accountId == accountId && (it.pending || it.at >= sinceSeconds) }
            .sortedWith(compareByDescending<Txn> { it.pending }.thenByDescending { it.at })

    fun transaction(accountId: String, txnId: String): Txn? =
        transactions.firstOrNull { it.accountId == accountId && it.txnId == txnId }

    /** Accounts largest balance first, matching BrightLedger's home screen. */
    fun sortedAccounts(): List<Account> = accounts.sortedByDescending { it.balanceCents }

    companion object {

        /**
         * Folds one `/accounts` response into the cache.
         *
         * - Accounts are upserted by id. An account missing from a response is kept, since a
         *   bank that is down for a day should not make its balance disappear.
         * - Transactions are upserted by `(accountId, txnId)`. The protocol only promises a
         *   transaction id is unique within an account and allows two accounts to reuse one,
         *   so keying on the id alone would let one account's rows overwrite another's.
         * - Pending rows inside the fetched window that the server no longer returns are
         *   swept. A released hold, or a tip adjustment that reposts under a new id, would
         *   otherwise sit in the list forever and inflate the month's spend. Rows older than
         *   the window are left alone, because this response says nothing about them.
         * - Anything older than [keepSinceSeconds] is dropped so the file stays bounded.
         */
        fun merge(
            old: Ledger,
            data: SfAccountSet,
            windowStart: Long,
            nowSeconds: Long,
            keepSinceSeconds: Long,
        ): Ledger {
            val connectionNames = data.connections.associate { it.connId to it.name }

            // An error is attached to the account it names, or to every account on the
            // connection it names, so one broken bank shows on its own rows rather than as a
            // banner over accounts that are fine.
            val problems = HashMap<String, String>()
            for (error in data.errors) {
                when {
                    error.accountId != null -> problems[error.accountId] = error.msg
                    error.connId != null -> data.accounts
                        .filter { it.connId == error.connId }
                        .forEach { problems[it.id] = error.msg }
                }
            }

            val accounts = LinkedHashMap<String, Account>()
            old.accounts.forEach { accounts[it.id] = it }
            for (account in data.accounts) {
                accounts[account.id] = Account(
                    id = account.id,
                    name = account.name,
                    connName = account.connId?.let(connectionNames::get),
                    currency = account.currency,
                    balanceCents = account.balanceCents,
                    availableCents = account.availableBalanceCents,
                    balanceDate = account.balanceDate,
                    problem = problems[account.id],
                )
            }

            val fetchedAccounts = data.accounts.mapTo(HashSet()) { it.id }
            val seen = HashSet<Pair<String, String>>()
            val fresh = ArrayList<Txn>()
            for (account in data.accounts) {
                for (txn in account.transactions) {
                    if (seen.add(account.id to txn.id)) {
                        fresh += Txn(
                            accountId = account.id,
                            txnId = txn.id,
                            at = txn.effectiveAt,
                            posted = txn.posted,
                            amountCents = txn.amountCents,
                            description = txn.description,
                            pending = txn.pending,
                        )
                    }
                }
            }

            val kept = old.transactions.filter { txn ->
                val key = txn.accountId to txn.txnId
                when {
                    key in seen -> false // replaced by the fresh copy
                    txn.pending && txn.accountId in fetchedAccounts && txn.at >= windowStart -> false
                    // A pending row with no usable date cannot be placed outside the window,
                    // so it is treated as inside it.
                    txn.pending && txn.accountId in fetchedAccounts && txn.at <= 0L -> false
                    else -> true
                }
            }

            val transactions = (kept + fresh).filter { it.pending || it.at >= keepSinceSeconds }
            return Ledger(
                accounts = accounts.values.toList(),
                transactions = transactions,
                lastSuccess = nowSeconds,
            )
        }
    }
}
