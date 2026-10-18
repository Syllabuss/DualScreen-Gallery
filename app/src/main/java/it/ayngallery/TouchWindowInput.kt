package it.ayngallery

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/** Attached only to the useful content area of this window, below its toolbar and above navigation. */
internal fun Modifier.galleryTouch(settings: TouchSettings, context: PadContext, enabled: Boolean,
    target: String, action: (TouchAction) -> Unit): Modifier = composed {
    val callback = rememberUpdatedState(action)
    // A display swap changes the pane's role; keep shared gestures available on both panes.
    val directions = TouchDirection.entries.associateWith { direction ->
        val own = settings.threeFinger[context]?.get(direction) ?: TouchAction.NONE
        if (own != TouchAction.NONE) own else settings.threeFinger[PadContext.LIBRARY]?.get(direction)?.takeIf { it == TouchAction.SWAP } ?: TouchAction.NONE
    }
    val zones = settings.zones.filter { it.context == context && it.action != TouchAction.NONE }
    val three = directions.values.any { it != TouchAction.NONE }
    if (!enabled || context !in touchContexts || (!three && zones.isEmpty())) this
    else pointerInput(settings, context) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val start = down.position
            val began = down.uptimeMillis
            val bounds = size
            val tracker = if (three) ThreeFingerDrag(viewConfiguration.touchSlop, 32.dp.toPx()) else null
            var zone = if (size.width > 0 && size.height > 0) zones.firstOrNull { it.contains(start.x / size.width, start.y / size.height) } else null
            var event = currentEvent
            while (true) {
                if (size != bounds || event.changes.any { it.pressed && (it.position.x !in 0f..size.width.toFloat() || it.position.y !in 0f..size.height.toFloat()) }) {
                    zone = null; tracker?.cancel()
                }
                val decision = tracker?.update(event.changes.map { TouchPoint(it.id.value, it.position.x, it.position.y, it.pressed) }, event.changes.maxOf { it.uptimeMillis })
                if (event.changes.size != 1 || event.changes.any { (it.position - start).getDistance() > viewConfiguration.touchSlop ||
                        it.position.x !in 0f..size.width.toFloat() || it.position.y !in 0f..size.height.toFloat() }) zone = null
                if (decision?.consume == true) {
                    zone = null
                    event.changes.forEach { it.consume() } // Initial pass cancels the child tap/pinch before it handles this event.
                }
                if (event.changes.none { it.pressed }) {
                    val zoneAction = zone?.action?.takeIf { event.changes.maxOf { it.uptimeMillis } - began < viewConfiguration.longPressTimeoutMillis }
                    if (zoneAction != null) event.changes.forEach { it.consume() }
                    val selected = decision?.direction?.let { directions[it] } ?: zoneAction
                    if (selected != null && selected != TouchAction.NONE) callback.value(selected)
                    break
                }
                event = awaitPointerEvent(PointerEventPass.Initial)
            }
        }
    }
}

/** Album back from the left edge, before a child list can claim the drag. */
internal fun Modifier.galleryEdgeBack(enabled: Boolean, back: () -> Unit): Modifier = composed {
    val callback = rememberUpdatedState(back)
    if (!enabled) this else pointerInput(enabled) {
        awaitEachGesture {
            val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (first.position.x > 24.dp.toPx()) return@awaitEachGesture
            var claimed = false
            var cancelled = false
            var end = first.position
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.size != 1 || event.changes.any { it.isConsumed }) cancelled = true
                end = event.changes.first().position
                val delta = end - first.position
                if (!cancelled && delta.x > 64.dp.toPx() && delta.x > kotlin.math.abs(delta.y) * 2) claimed = true
                if (claimed) event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
            if (claimed && !cancelled) callback.value()
        }
    }
}
