package it.ayngallery

import androidx.compose.foundation.gestures.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** One-finger swipe at 1×; pan/pinch otherwise. A parent claiming three fingers cancels this sequence. */
internal suspend fun PointerInputScope.mediaTouchGestures(zoomed: () -> Boolean, start: () -> Unit,
    transform: (Offset, Offset, Float) -> Unit, swipe: (Int) -> Unit, drag: ((Float) -> Unit)? = null, release: ((Int) -> Unit)? = null) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        start()
        val beganZoomed = zoomed()
        var multi = false
        var moved = false
        var cancelled = false
        var total = Offset.Zero
        var accumulatedZoom = 1f
        var finished: Boolean
        do {
            val event = awaitPointerEvent()
            if (event.changes.any { it.isConsumed }) { cancelled = true; break }
            if (event.changes.count { it.pressed || it.previousPressed } > 1) multi = true
            val factor = event.calculateZoom()
            val pan = event.calculatePan()
            total += pan; accumulatedZoom *= factor
            if (!moved) moved = total.getDistance() > viewConfiguration.touchSlop ||
                abs(1 - accumulatedZoom) * event.calculateCentroidSize(useCurrent = false) > viewConfiguration.touchSlop
            if (moved) {
                // The final UP has no centroid (NaN). Keep the last valid position instead of resetting it.
                if ((beganZoomed || multi) && event.changes.any { it.pressed }) {
                    val centroid = event.calculateCentroid(useCurrent = false)
                    if (centroid.x.isFinite() && centroid.y.isFinite()) transform(centroid, pan, factor)
                }
                if (!multi && !beganZoomed && abs(total.x) > abs(total.y) * 2f) drag?.invoke(total.x)
                else drag?.invoke(0f)
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
            finished = event.changes.none { it.pressed }
        } while (!finished)
        val direction = if (!cancelled && !multi && !beganZoomed && moved && abs(total.x) >= 64.dp.toPx() && abs(total.x) > abs(total.y) * 2f)
            if (total.x < 0) 1 else -1 else 0
        if (release != null) release(direction) else if (direction != 0) swipe(direction)
    }
}
