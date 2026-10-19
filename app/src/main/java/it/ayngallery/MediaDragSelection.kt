package it.ayngallery

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun mediaDragSelection(enabled: Boolean, ids: List<String>, hit: (Offset) -> String?,
    viewportHeight: () -> Int, scroll: suspend (Float) -> Unit, select: (String) -> Unit): Modifier {
    val hitNow by rememberUpdatedState(hit)
    val selectNow by rememberUpdatedState(select)
    val scrollNow by rememberUpdatedState(scroll)
    val heightNow by rememberUpdatedState(viewportHeight)
    val indices = remember(ids) { ids.withIndex().associate { it.value to it.index } }
    val edge = with(LocalDensity.current) { 56.dp.toPx() }
    val step = with(LocalDensity.current) { 18.dp.toPx() }
    return Modifier.pointerInput(enabled, ids, edge, step) {
        if (!enabled) return@pointerInput
        coroutineScope {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val initial = hitNow(down.position) ?: return@awaitEachGesture
                val held = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                held.consume()
                selectNow(initial)
                var point = held.position
                val visited = mutableSetOf(initial)
                var moved = false
                var previous = indices[initial] ?: return@awaitEachGesture
                fun selectAtPoint() {
                    val target = hitNow(point)?.let(indices::get) ?: return
                    for (index in minOf(previous, target)..maxOf(previous, target)) {
                        if (visited.add(ids[index])) selectNow(ids[index])
                    }
                    previous = target
                }
                val scrolling = launch {
                    while (true) {
                        delay(16)
                        if (moved) {
                            val height = heightNow()
                            val speed = when {
                                point.y < edge -> -((edge - point.y) / edge).coerceIn(0f, 1f)
                                point.y > height - edge -> ((point.y - height + edge) / edge).coerceIn(0f, 1f)
                                else -> 0f
                            }
                            scrollNow(speed * step)
                            selectAtPoint()
                        }
                    }
                }
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) { change.consume(); break }
                        point = change.position
                        if (!moved && (point - held.position).getDistance() > viewConfiguration.touchSlop) moved = true
                        if (moved) { change.consume(); selectAtPoint() }
                    }
                } finally { scrolling.cancel() }
            }
        }
    }
}
