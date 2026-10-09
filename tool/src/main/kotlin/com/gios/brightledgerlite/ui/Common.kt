package com.gios.brightledgerlite.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.thelightphone.sdk.LightQrCodeScanner
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.SharedFlow

/** Pixels one wheel click scrolls. */
const val WHEEL_STEP_PX = 180f

/** The system theme (dark or light, as LightOS has it) over a filled background. */
@Composable
fun LedgerSurface(content: @Composable () -> Unit) {
    val colors by LightThemeController.colors.collectAsState()
    LightTheme(colors = colors) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(LightThemeTokens.colors.background),
        ) {
            content()
        }
    }
}

/** A scroll state the wheel drives. */
@Composable
fun rememberWheelScroll(wheel: SharedFlow<Int>): ScrollState {
    val scroll = rememberScrollState()
    LaunchedEffect(wheel) { wheel.collect { scroll.animateScrollBy(it * WHEEL_STEP_PX) } }
    return scroll
}

/**
 * The common page: a top bar with a back arrow, a scrolling body, and an optional bottom
 * bar. Every screen but the home screen uses it.
 */
@Composable
fun Page(
    title: String,
    onBack: (() -> Unit)?,
    scroll: ScrollState,
    bottom: List<LightBarButton> = emptyList(),
    right: LightBarButton? = null,
    overlay: (@Composable () -> Unit)? = null,
    body: @Composable () -> Unit,
) {
    LedgerSurface {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = onBack?.let { LightBarButton.LightIcon(LightIcons.BACK, onClick = it) },
                center = LightTopBarCenter.Text(title),
                rightButton = right,
            )
            LightScrollView(
                scrollState = scroll,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 1f.gridUnitsAsDp()),
            ) {
                body()
                Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))
            }
            if (bottom.isNotEmpty()) LightBottomBar(bottom)
        }
        overlay?.invoke()
    }
}

@Composable
fun CenterMessage(text: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(horizontal = 2f.gridUnitsAsDp()),
        contentAlignment = Alignment.Center,
    ) {
        LightText(text = text, variant = LightTextVariant.Copy, align = TextAlign.Center)
    }
}

@Composable
fun SectionLabel(text: String) {
    LightText(
        text = text.uppercase(),
        variant = LightTextVariant.Fine,
        lighten = true,
        modifier = Modifier.padding(top = 1.2f.gridUnitsAsDp(), bottom = 0.3f.gridUnitsAsDp()),
    )
}

/**
 * A label on the left, an amount on the right, an optional dim line underneath.
 *
 * The label block takes `weight(1f)` so every amount lines up in one column down the
 * screen, which is how BrightLedger lays out its rows.
 */
@Composable
fun AmountRow(
    label: String,
    amount: String,
    sub: String? = null,
    dim: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val base = Modifier.fillMaxWidth()
    Column(
        modifier = (if (onClick != null) base.lightClickable(onClick = onClick) else base)
            .padding(vertical = 0.5f.gridUnitsAsDp()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            LightText(
                text = label,
                variant = LightTextVariant.Copy,
                lighten = dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(0.5f.gridUnitsAsDp()))
            LightText(text = amount, variant = LightTextVariant.Copy, lighten = dim, maxLines = 1)
        }
        if (sub != null) {
            LightText(
                text = sub,
                variant = LightTextVariant.Fine,
                lighten = true,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A labelled value for detail pages. */
@Composable
fun Field(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 0.4f.gridUnitsAsDp())) {
        LightText(text = label, variant = LightTextVariant.Fine, lighten = true)
        LightText(text = value, variant = LightTextVariant.Copy)
    }
}

@Composable
fun Note(text: String) {
    LightText(
        text = text,
        variant = LightTextVariant.Detail,
        lighten = true,
        modifier = Modifier.padding(vertical = 0.6f.gridUnitsAsDp()),
    )
}

/**
 * Full-screen text entry on the LP3 keyboard. Returns the submitted text through
 * [goBack]; backing out returns nothing.
 */
class TextEditorScreen(
    sealedActivity: SealedLightActivity,
    private val title: String,
    private val initial: String = "",
    private val submitLabel: String = "DONE",
) : SimpleLightScreen<String>(sealedActivity) {

    private var done = false

    private fun finish(value: String?) {
        if (done) return
        done = true
        goBack(value)
    }

    @Composable
    override fun Content() {
        val keyboardOptions = rememberKeyboardOptions()
        val state = rememberTextFieldState(initial)
        LedgerSurface {
            LightTextInputEditor(
                title = title,
                state = state,
                onSubmit = { finish(it.toString()) },
                onBack = { finish(null) },
                keyboardOptionsFlow = keyboardOptions,
                submitLabel = submitLabel,
                singleLine = false,
                initialCaps = false,
            )
        }
    }
}

/** Scans one QR code with the SDK's scanner and returns its raw text. */
class QrScanScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<String>(sealedActivity) {
    // The scanner can report the same code on consecutive frames; only the first may pop
    // the screen, or a second goBack would pop the screen underneath too.
    private var done = false

    private fun finish(value: String?) {
        if (done) return
        done = true
        goBack(value)
    }

    @Composable
    override fun Content() {
        LedgerSurface {
            LightQrCodeScanner(
                title = "Scan setup token",
                onScanned = { finish(it) },
                onBack = { finish(null) },
            )
        }
    }
}

/** A yes-or-no question on its own page. Returns true only when confirmed. */
class ConfirmScreen(
    sealedActivity: SealedLightActivity,
    private val title: String,
    private val message: String,
    private val confirmLabel: String,
) : SimpleLightScreen<Boolean>(sealedActivity) {

    private var done = false

    private fun finish(value: Boolean) {
        if (done) return
        done = true
        goBack(value)
    }

    @Composable
    override fun Content() {
        Page(
            title = title,
            onBack = { finish(false) },
            scroll = rememberScrollState(),
            bottom = listOf(
                LightBarButton.Text("CANCEL", onClick = { finish(false) }),
                LightBarButton.Text(confirmLabel, onClick = { finish(true) }),
            ),
        ) {
            Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))
            LightText(text = message, variant = LightTextVariant.Copy)
        }
    }
}
