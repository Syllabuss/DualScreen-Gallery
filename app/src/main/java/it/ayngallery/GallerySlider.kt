package it.ayngallery

import android.view.KeyEvent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** Keep the native slider and route controller adjustments before directional focus navigation. */
@Composable
internal fun GallerySlider(value: Float, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, valueRange: ClosedFloatingPointRange<Float> = 0f..1f, steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null, colors: SliderColors = SliderDefaults.colors(),
    interactionSource: androidx.compose.foundation.interaction.MutableInteractionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) {
    val context = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    val owner = remember(context) { generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }.filterIsInstance<PanelActivity>().firstOrNull() }
    val latest by rememberUpdatedState { action: PadAction ->
        if (!enabled || action !in setOf(PadAction.LEFT, PadAction.RIGHT, PadAction.CONFIRM)) false
        else {
            if (action != PadAction.CONFIRM) {
                val span = valueRange.endInclusive - valueRange.start
                val step = if (steps > 0) span / (steps + 1) else if (span > 1f) maxOf(1f, span / 100f) else span / 100f
                onValueChange((value + if (action == PadAction.RIGHT) step else -step).coerceIn(valueRange))
                onValueChangeFinished?.invoke()
            }
            true
        }
    }
    val adjust = remember { { action: PadAction -> latest(action) } }
    DisposableEffect(owner) { onDispose { if (owner?.sliderAction === adjust) owner.sliderAction = null } }
    Slider(value, onValueChange, modifier.border(if (focused) 2.dp else 0.dp,
        if (focused) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(24.dp)).padding(horizontal = 5.dp).onFocusChanged {
        focused = it.isFocused
        if (it.isFocused) owner?.sliderAction = adjust else if (owner?.sliderAction === adjust) owner.sliderAction = null
    }.onPreviewKeyEvent { event ->
        val action = when (event.nativeKeyEvent.keyCode) { KeyEvent.KEYCODE_DPAD_LEFT -> PadAction.LEFT; KeyEvent.KEYCODE_DPAD_RIGHT -> PadAction.RIGHT; else -> null }
        action != null && if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) latest(action) else true
    }, enabled, valueRange, steps, onValueChangeFinished, colors, interactionSource)
}
