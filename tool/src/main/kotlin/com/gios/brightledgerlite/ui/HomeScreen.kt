package com.gios.brightledgerlite.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.gios.brightledgerlite.data.LedgerRepository
import com.gios.brightledgerlite.data.LedgerRepository.Outcome
import com.gios.brightledgerlite.data.LedgerRepository.Trigger
import com.gios.brightledgerlite.ledger.Amounts
import com.gios.brightledgerlite.ledger.Format
import com.gios.brightledgerlite.ledger.Ledger
import com.gios.brightledgerlite.ledger.Recurring
import com.gios.brightledgerlite.ledger.Summary
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightConnectivity
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HomeViewModel(
    private val repo: LedgerRepository,
    private val connectivity: LightConnectivity,
) : WheelViewModel<Unit>() {

    val ledger: StateFlow<Ledger> = repo.ledger
    val syncing: StateFlow<Boolean> = repo.syncing

    /** Null until the stored settings have been read. */
    private val _connected = MutableStateFlow<Boolean?>(null)
    val connected: StateFlow<Boolean?> = _connected.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        viewModelScope.launch {
            repo.load()
            val isConnected = repo.isConnected()
            _connected.value = isConnected
            _lastError.value = repo.lastError()
            if (isConnected && online()) run(Trigger.Open)
        }
    }

    fun syncNow() {
        if (syncing.value) return
        viewModelScope.launch {
            if (!online()) {
                _message.value = "No connection. The figures shown are from the last sync."
                return@launch
            }
            if (run(Trigger.Manual) is Outcome.Skipped) {
                _message.value = "Synced less than a minute ago. SimpleFIN servers limit how " +
                    "often they can be asked, so give it a moment."
            }
        }
    }

    private fun online(): Boolean =
        runCatching { connectivity.currentStatus.isConnected }.getOrDefault(true)

    private suspend fun run(trigger: Trigger): Outcome {
        val outcome = repo.sync(trigger)
        _lastError.value = repo.lastError()
        _message.value = when (outcome) {
            is Outcome.Ok -> outcome.warnings.firstOrNull()
            Outcome.NeedsReconnect ->
                "The server says this access was revoked. Disconnect in Settings and connect again with a new token."
            Outcome.PaymentRequired ->
                "Your SimpleFIN server is refusing requests until its subscription is renewed. " +
                    "That is between you and that server."
            // A failed open-time refresh is a line under the totals, not an interruption.
            is Outcome.Failed -> if (trigger == Trigger.Manual) outcome.message else null
            else -> null
        }
        return outcome
    }

    fun dismissMessage() {
        _message.value = null
    }
}

/**
 * Spent today, then this month, then a staleness line, then the accounts.
 *
 * That order is BrightLedger's: "can I buy this" is asked more often than "what did I buy",
 * and the answer to the common question should not need a scroll.
 */
@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, HomeViewModel>(sealedActivity) {

    private val repo = LedgerRepository.get(lightContext)

    override val viewModelClass: Class<HomeViewModel> get() = HomeViewModel::class.java

    override fun createViewModel() = HomeViewModel(repo, lightContext.connectivity)

    private fun openSetup() = navigateTo({ SetupScreen(it) })

    private fun openSettings() = navigateTo({ SettingsScreen(it) })

    private fun openRecurring() = navigateTo({ RecurringScreen(it) })

    private fun openAccount(id: String) = navigateTo({ AccountScreen(it, id) })

    @Composable
    override fun Content() {
        val connected by viewModel.connected.collectAsState()
        val ledger by viewModel.ledger.collectAsState()
        val syncing by viewModel.syncing.collectAsState()
        val lastError by viewModel.lastError.collectAsState()
        val message by viewModel.message.collectAsState()
        val scroll = rememberWheelScroll(viewModel.wheel)

        val totals = remember(ledger) {
            Summary.of(
                ledger.transactions.map { Summary.Row(it.at, it.amountCents, it.pending) },
                Instant.now(),
                ZoneId.systemDefault(),
            )
        }
        val repeats = remember(ledger) { recurringOf(ledger) }
        val currency = ledger.accounts.firstOrNull()?.currency

        LedgerSurface {
            Column(modifier = Modifier.fillMaxSize()) {
                LightTopBar(
                    center = LightTopBarCenter.Text("Ledger"),
                    rightButton = if (connected == true) {
                        LightBarButton.LightIcon(LightIcons.SETTINGS, onClick = ::openSettings)
                    } else {
                        null
                    },
                )
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when (connected) {
                        null -> Unit
                        false -> CenterMessage(
                            "Bank balances over SimpleFIN, straight from this phone to the server " +
                                "you choose. Connect with a SimpleFIN setup token to start.",
                        )
                        true -> LightScrollView(
                            scrollState = scroll,
                            modifier = Modifier.fillMaxSize().padding(horizontal = 1f.gridUnitsAsDp()),
                        ) {
                            Spacer(modifier = Modifier.height(0.5f.gridUnitsAsDp()))
                            LightText(text = "SPENT TODAY", variant = LightTextVariant.Fine, lighten = true)
                            BigAmount(text = Amounts.plain(totals.todayCents, currency))
                            LightText(
                                text = Amounts.plain(totals.monthCents, currency) + " this month",
                                variant = LightTextVariant.Copy,
                            )
                            if (totals.monthInCents > 0) {
                                LightText(
                                    text = "+" + Amounts.plain(totals.monthInCents, currency) + " in this month",
                                    variant = LightTextVariant.Detail,
                                    lighten = true,
                                )
                            }
                            if (totals.pendingCount > 0) {
                                LightText(
                                    text = totals.pendingCount.toString() + " pending",
                                    variant = LightTextVariant.Detail,
                                    lighten = true,
                                )
                            }
                            // Never hidden. A confident number with no age attached is a small lie,
                            // and this phone is offline a lot.
                            LightText(
                                text = when {
                                    syncing -> "Syncing…"
                                    ledger.lastSuccess <= 0L -> "Not synced yet"
                                    else -> "Updated " + Format.since(ledger.lastSuccess)
                                },
                                variant = LightTextVariant.Fine,
                                lighten = true,
                                modifier = Modifier.padding(top = 0.4f.gridUnitsAsDp()),
                            )
                            if (!syncing && lastError != null) {
                                LightText(text = lastError.orEmpty(), variant = LightTextVariant.Fine, lighten = true)
                            }

                            SectionLabel("Accounts")
                            val accounts = ledger.sortedAccounts()
                            if (accounts.isEmpty()) {
                                Note(if (syncing) "Loading…" else "No accounts yet.")
                            }
                            accounts.forEach { account ->
                                AmountRow(
                                    label = account.name,
                                    amount = Amounts.plain(account.balanceCents, account.currency),
                                    sub = account.problem ?: account.connName,
                                    onClick = { openAccount(account.id) },
                                )
                            }

                            if (repeats.isNotEmpty()) {
                                SectionLabel("Repeats")
                                AmountRow(
                                    label = "${repeats.size} repeating charges",
                                    amount = Amounts.plain(repeats.sumOf { Recurring.monthlyCostCents(it) }, currency) + "/mo",
                                    onClick = ::openRecurring,
                                )
                            }
                        }
                    }
                }
                when (connected) {
                    false -> LightBottomBar(listOf(LightBarButton.Text("CONNECT", onClick = ::openSetup)))
                    true -> LightBottomBar(
                        listOf(
                            LightBarButton.Text(if (syncing) "SYNCING" else "SYNC", onClick = viewModel::syncNow),
                            LightBarButton.Text("REPEATS", onClick = ::openRecurring),
                        ),
                    )
                    null -> Unit
                }
            }
            message?.let { LightFullscreenModal(message = it, onClose = viewModel::dismissMessage) }
        }
    }
}

/** Repeating charges from settled history, biggest monthly commitment first. */
fun recurringOf(ledger: Ledger): List<Recurring.Charge> =
    Recurring.detect(
        ledger.transactions
            .filterNot { it.pending }
            .map { Recurring.Row(it.at, it.amountCents, it.description) },
    )
