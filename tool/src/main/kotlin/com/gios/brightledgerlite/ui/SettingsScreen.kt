package com.gios.brightledgerlite.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewModelScope
import com.gios.brightledgerlite.data.BackgroundSync
import com.gios.brightledgerlite.data.LedgerRepository
import com.gios.brightledgerlite.ledger.Format
import com.gios.brightledgerlite.ledger.Ledger
import com.gios.brightledgerlite.ledger.SyncPolicy
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val repo: LedgerRepository,
    private val lightContext: SealedLightContext,
) : WheelViewModel<Unit>() {

    val ledger: StateFlow<Ledger> = repo.ledger

    private val _host = MutableStateFlow<String?>(null)
    val host: StateFlow<String?> = _host.asStateFlow()

    private val _background = MutableStateFlow(false)
    val background: StateFlow<Boolean> = _background.asStateFlow()

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        viewModelScope.launch {
            _host.value = repo.host()
            _background.value = repo.backgroundSync()
        }
    }

    fun toggleBackground() {
        viewModelScope.launch {
            val enabled = !_background.value
            repo.setBackgroundSync(enabled)
            if (enabled) BackgroundSync.schedule(lightContext) else BackgroundSync.cancel(lightContext)
            _background.value = enabled
        }
    }

    fun disconnect(onDone: () -> Unit) {
        viewModelScope.launch {
            repo.disconnect(lightContext)
            onDone()
        }
    }
}

class SettingsScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, SettingsViewModel>(sealedActivity) {

    private val repo = LedgerRepository.get(lightContext)

    override val viewModelClass: Class<SettingsViewModel> get() = SettingsViewModel::class.java

    override fun createViewModel() = SettingsViewModel(repo, lightContext)

    private fun confirmDisconnect() {
        navigateTo({
            ConfirmScreen(
                it,
                title = "Disconnect",
                message = "Forget the SimpleFIN credential and erase every cached balance and " +
                    "transaction from this phone?\n\nThis does not revoke access. Do that on " +
                    "the Bridge (or whichever server issued the token), or the credential stays " +
                    "valid there.",
                confirmLabel = "ERASE",
            )
        }) { confirmed ->
            if (confirmed) viewModel.disconnect { goBack() }
        }
    }

    @Composable
    override fun Content() {
        val ledger by viewModel.ledger.collectAsState()
        val host by viewModel.host.collectAsState()
        val background by viewModel.background.collectAsState()
        val scroll = rememberWheelScroll(viewModel.wheel)

        Page(
            title = "Settings",
            onBack = { goBack() },
            scroll = scroll,
            bottom = listOf(LightBarButton.Text("DISCONNECT", onClick = ::confirmDisconnect)),
        ) {
            SectionLabel("Server")
            Field("Connected to", host ?: "Unknown")
            Field("Last sync", Format.since(ledger.lastSuccess))
            Field("Accounts", ledger.accounts.size.toString())

            SectionLabel("Background")
            AmountRow(
                label = "Daily sync",
                amount = if (background) "ON" else "OFF",
                sub = "Refresh about once every ${SyncPolicy.BACKGROUND_HOURS} hours while the " +
                    "tool is closed, so the figures are current when you open it. LightOS " +
                    "picks the exact time.",
                onClick = viewModel::toggleBackground,
            )

            SectionLabel("About")
            Note(
                "Balances and transactions over the SimpleFIN protocol. There is no server in " +
                    "the middle: the phone talks straight to ${host ?: "your SimpleFIN server"}, " +
                    "using a read-only credential that exists only on this phone, encrypted with " +
                    "a key held in the Android Keystore.",
            )
            Note(
                "Opening the tool refreshes only when the figures are more than " +
                    "${SyncPolicy.AUTO_REFRESH_AFTER / 60} minutes old, because SimpleFIN servers " +
                    "limit how many requests a credential can make in a day.",
            )
        }
    }
}
