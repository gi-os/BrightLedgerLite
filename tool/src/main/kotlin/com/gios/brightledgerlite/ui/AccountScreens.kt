package com.gios.brightledgerlite.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.gios.brightledgerlite.data.LedgerRepository
import com.gios.brightledgerlite.ledger.Amounts
import com.gios.brightledgerlite.ledger.Format
import com.gios.brightledgerlite.ledger.Recurring
import com.gios.brightledgerlite.ledger.SyncPolicy
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp

/**
 * One account: its balance, then the last [SyncPolicy.SHOW_DAYS] days of activity, pending
 * first. The list stops there on purpose; older history stays on the phone for the repeats
 * detector but is not a feed to scroll through.
 */
class AccountScreen(
    sealedActivity: SealedLightActivity,
    private val accountId: String,
) : LightScreen<Unit, ScrollViewModel>(sealedActivity) {

    private val repo = LedgerRepository.get(lightContext)

    override val viewModelClass: Class<ScrollViewModel> get() = ScrollViewModel::class.java

    override fun createViewModel() = ScrollViewModel()

    @Composable
    override fun Content() {
        val ledger by repo.ledger.collectAsState()
        val scroll = rememberWheelScroll(viewModel.wheel)
        val account = ledger.account(accountId)
        val rows = remember(ledger) {
            ledger.transactionsFor(accountId, SyncPolicy.showSince(LedgerRepository.nowSeconds()))
        }

        Page(title = account?.name ?: "Account", onBack = { goBack() }, scroll = scroll) {
            if (account == null) {
                Note("This account is no longer in the ledger.")
                return@Page
            }
            LightText(
                text = Amounts.plain(account.balanceCents, account.currency),
                variant = LightTextVariant.Title,
                modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
            )
            account.availableCents?.takeIf { it != account.balanceCents }?.let {
                LightText(
                    text = Amounts.plain(it, account.currency) + " available",
                    variant = LightTextVariant.Copy,
                )
            }
            if (account.balanceDate > 0L) {
                LightText(
                    text = "Balance as of " + Format.relativeDate(account.balanceDate).lowercase(),
                    variant = LightTextVariant.Fine,
                    lighten = true,
                )
            }
            account.connName?.let { LightText(text = it, variant = LightTextVariant.Fine, lighten = true) }
            account.problem?.let { Note(it) }

            SectionLabel("Last ${SyncPolicy.SHOW_DAYS} days")
            if (rows.isEmpty()) Note("Nothing in the last ${SyncPolicy.SHOW_DAYS} days.")
            rows.forEach { txn ->
                AmountRow(
                    label = txn.description.ifBlank { "No description" },
                    amount = Amounts.signed(txn.amountCents, account.currency),
                    sub = if (txn.pending) "Pending" else Format.relativeDate(txn.at),
                    dim = txn.pending,
                    onClick = {
                        navigateTo({ TransactionScreen(it, accountId, txn.txnId) })
                    },
                )
            }
        }
    }
}

/**
 * The fields a list row has no room for. It reads its row from the live ledger rather than
 * holding a copy, so a pending charge that settles while it is open updates in place.
 */
class TransactionScreen(
    sealedActivity: SealedLightActivity,
    private val accountId: String,
    private val txnId: String,
) : LightScreen<Unit, ScrollViewModel>(sealedActivity) {

    private val repo = LedgerRepository.get(lightContext)

    override val viewModelClass: Class<ScrollViewModel> get() = ScrollViewModel::class.java

    override fun createViewModel() = ScrollViewModel()

    @Composable
    override fun Content() {
        val ledger by repo.ledger.collectAsState()
        val scroll = rememberWheelScroll(viewModel.wheel)
        val txn = ledger.transaction(accountId, txnId)
        val account = ledger.account(accountId)

        Page(title = "Transaction", onBack = { goBack() }, scroll = scroll) {
            if (txn == null) {
                Note("This transaction is no longer in the ledger. A pending charge that settled may have come back under a new reference.")
                return@Page
            }
            LightText(
                text = Amounts.signed(txn.amountCents, account?.currency),
                variant = LightTextVariant.Title,
                lighten = txn.pending,
                modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
            )
            LightText(text = txn.description.ifBlank { "No description" }, variant = LightTextVariant.Copy)
            Field("Account", account?.name ?: "Unknown")
            Field("Status", if (txn.pending) "Pending" else "Posted")
            Field("Date", Format.fullDate(txn.at))
            if (txn.posted > 0L && txn.posted != txn.at) Field("Posted", Format.fullDate(txn.posted))
            Field("Reference", txn.txnId)
        }
    }
}

/**
 * The subscriptions, and what they cost per month in total, which is the number almost
 * nobody knows. Worked out on the phone from the cached history.
 */
class RecurringScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, ScrollViewModel>(sealedActivity) {

    private val repo = LedgerRepository.get(lightContext)

    override val viewModelClass: Class<ScrollViewModel> get() = ScrollViewModel::class.java

    override fun createViewModel() = ScrollViewModel()

    @Composable
    override fun Content() {
        val ledger by repo.ledger.collectAsState()
        val scroll = rememberWheelScroll(viewModel.wheel)
        val charges = remember(ledger) { recurringOf(ledger) }
        val currency = ledger.accounts.firstOrNull()?.currency

        Page(title = "Repeats", onBack = { goBack() }, scroll = scroll) {
            if (charges.isEmpty()) {
                Note(
                    "Nothing repeating yet.\n\nA charge shows up here once it has happened " +
                        "three times at a steady interval.",
                )
                return@Page
            }
            LightText(text = "REPEATING", variant = LightTextVariant.Fine, lighten = true,
                modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()))
            LightText(
                text = Amounts.plain(charges.sumOf { Recurring.monthlyCostCents(it) }, currency),
                variant = LightTextVariant.Title,
            )
            LightText(text = "a month across ${charges.size} charges", variant = LightTextVariant.Copy)
            SectionLabel("Charges")
            charges.forEach { charge ->
                AmountRow(
                    label = charge.name,
                    amount = Amounts.plain(-charge.amountCents, currency),
                    sub = charge.cadence.label + " · " + Format.until(charge.nextAt),
                )
            }
            Note(
                "Worked out from your transaction history on this phone. Nothing is sent " +
                    "anywhere to compute it, and a charge that changes price or skips a cycle " +
                    "may drop off this list until the pattern returns.",
            )
        }
    }
}
