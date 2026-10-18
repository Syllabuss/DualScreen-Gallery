package it.ayngallery

import kotlin.math.abs

internal enum class ConsoleAxis { BRIGHTNESS, VOLUME }
internal data class ConsoleStep(val axis: ConsoleAxis, val direction: Int)

/** Lock the dominant axis until release so volume adjustments cannot change brightness. */
internal class ConsoleStickControls {
    private var axis: ConsoleAxis? = null
    private var direction = 0
    private var next = 0L
    val active get() = axis != null
    fun clear() { axis = null; direction = 0; next = 0 }
    fun update(x: Float, y: Float, now: Long): ConsoleStep? {
        if (!x.isFinite() || !y.isFinite() || maxOf(abs(x), abs(y)) < .3f) { clear(); return null }
        if (axis == null) {
            axis = when {
                abs(x) >= .65f && abs(x) > abs(y) * 1.25f -> ConsoleAxis.VOLUME
                abs(y) >= .65f && abs(y) > abs(x) * 1.25f -> ConsoleAxis.BRIGHTNESS
                else -> return null
            }
        }
        val value = if (axis == ConsoleAxis.VOLUME) x else -y
        if (abs(value) < .3f) return null
        val sign = if (value > 0) 1 else -1
        if (sign != direction) { direction = sign; next = 0 }
        if (now < next) return null
        next = now + 220
        return ConsoleStep(axis!!, sign)
    }
}
