package com.gios.brightledgerlite.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewModelScope
import com.gios.brightledgerlite.data.LedgerRepository
import com.gios.brightledgerlite.simplefin.AccessUrl
import com.thelightphone.sdk.LightConnectivity
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextField
import com.thelightphone.sdk.ui.LightTextVariant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SetupState(
    val token: String = "",
    val claiming: Boolean = false,
    val error: String? = null,
)

class SetupViewModel(
    private val repo: LedgerRepository,
    private val connectivity: LightConnectivity,
) : WheelViewModel<Boolean>() {

    private val _state = MutableStateFlow(SetupState())
    val state: StateFlow<SetupState> = _state.asStateFlow()

    fun setToken(value: String) = _state.update { it.copy(token = value.trim(), error = null) }

    /**
     * Claims the token. Has its own busy flag, separate from syncing: BrightLedger once
     * shared one, and a scan that landed during a background sync returned without a
     * request or a word, which looked exactly like a broken camera.
     */
    fun claim(onConnected: () -> Unit) {
        val token = _state.value.token
        if (_state.value.claiming) return
        if (AccessUrl.decodeSetupToken(token) == null) {
            _state.update {
                it.copy(
                    error = if (token.isBlank()) {
                        "Scan or type a SimpleFIN setup token first."
                    } else {
                        "That does not look like a SimpleFIN setup token. It should be one long " +
                            "line of letters and numbers from the Bridge."
                    },
                )
            }
            return
        }
        if (!runCatching { connectivity.currentStatus.isConnected }.getOrDefault(true)) {
            _state.update { it.copy(error = "No connection. The token is untouched; try again when online.") }
            return
        }
        _state.update { it.copy(claiming = true, error = null) }
        viewModelScope.launch {
            when (val result = repo.claim(token)) {
                is LedgerRepository.ClaimOutcome.Connected -> {
                    _state.update { it.copy(claiming = false, token = "") }
                    onConnected()
                }
                is LedgerRepository.ClaimOutcome.Refused ->
                    _state.update { it.copy(claiming = false, error = result.message) }
            }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }
}

/**
 * Connecting a bank: a SimpleFIN setup token, scanned or typed, is claimed once for a
 * read-only Access URL, which is sealed on the phone. The token is then useless.
 */
class SetupScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Boolean, SetupViewModel>(sealedActivity) {

    private val repo = LedgerRepository.get(lightContext)

    override val viewModelClass: Class<SetupViewModel> get() = SetupViewModel::class.java

    override fun createViewModel() = SetupViewModel(repo, lightContext.connectivity)

    private fun connected() = goBack(true)

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        val scroll = rememberWheelScroll(viewModel.wheel)
        Page(
            title = "Connect",
            onBack = { goBack(false) },
XX, onClick = {
                    if (!state.claiming) {
                        navigateTo({ QrScanScreen(it) }) { raw ->
                            viewModel.setToken(raw)
                            viewModel.claim(::connected)
                        }
                    }
                }),
                LightBarButton.Text(if (state.claiming) "CONNECTING" else "CONNECT", onClick = {
                    viewModel.claim(::connected)
                }),
            ),
        ) {
            Note("1. On a computer, go to bridge.simplefin.org and connect your bank.")
            Note("2. It gives you a setup token, a long line of text.")
            Note("3. Turn the token into a QR code and scan it, or type it below.")
            LightTextField(
                label = "Setup token",
                value = if (state.token.length > 24) state.token.take(24) + "…" else state.token,
                placeholder = "aHR0cHM6Ly9icmlkZ2Uu…",
                onClick = {
                    if (!state.claiming) {
                        navigateTo({ TextEditorScreen(it, "Setup token", state.token) }) { viewModel.setToken(it) }
                    }
                },
            )
            if (state.claiming) {
                LightText(text = "Claiming the token and fetching your accounts…", variant = LightTextVariant.Detail)
            }
            Note(
                "The token is used once, to get a read-only access URL. That URL is encrypted " +
                    "on this phone and only ever sent back to the server that issued it. " +
                    "This tool has no server of its own.",
            )
        }
    }
}
