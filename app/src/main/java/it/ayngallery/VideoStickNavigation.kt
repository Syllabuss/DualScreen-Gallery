package it.ayngallery

import kotlin.math.abs

internal data class VideoStickStep(val seekSeconds: Int = 0, val volume: Int = 0)

/** Lock one axis until centre; holding a direction repeats at half-second intervals. */
internal class VideoStickNavigation {
    private var axis = 0
    private var heldDirection = 0
    private var lastDirection = 0
    private var seekCount = 0
    private var lastSeek = -2000L
    private var lastVolume = -1000L

    fun update(x: Float, y: Float, now: Long, press: Float, release: Float): VideoStickStep? {
        if (!x.isFinite() || !y.isFinite()) return null
        val ax = abs(x); val ay = abs(y)
        if (maxOf(ax, ay) <= release) { axis = 0; heldDirection = 0; return null }
        val threshold = maxOf(.65f, press)
        if (axis == 0) {
            axis = when {
                ax >= threshold && ax >= ay * 1.5f -> 1
                ay >= threshold && ay >= ax * 1.5f -> 2
                else -> return null
            }
            if (axis == 2) { lastDirection = 0; seekCount = 0 }
        }
        if (axis == 2) {
            if (ay < threshold || now - lastVolume < 250) return null
            lastVolume = now
            return VideoStickStep(volume = if (y < 0) 1 else -1)
        }
        if (ax <= release) heldDirection = 0
        if (ax < threshold) return null
        val direction = if (x > 0) 1 else -1
        if (direction == heldDirection && now - lastSeek < 500) return null
        heldDirection = direction
        return VideoStickStep(seekSeconds = seek(direction, now))
    }

    fun seek(direction: Int, now: Long): Int {
        require(direction == -1 || direction == 1)
        seekCount = if (direction == lastDirection && now - lastSeek in 0..1000) (seekCount + 1).coerceAtMost(5) else 0
        lastDirection = direction; lastSeek = now
        return direction * listOf(5, 10, 10, 30, 30, 60)[seekCount]
    }
}
