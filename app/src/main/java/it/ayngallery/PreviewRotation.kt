package it.ayngallery

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.min

internal object PreviewRotation {
    fun fit(width: Float, height: Float, contentWidth: Float, contentHeight: Float, degrees: Float): Float {
        val radians = Math.toRadians(degrees.toDouble())
        val c = abs(cos(radians)).toFloat(); val s = abs(sin(radians)).toFloat()
        return min(width / (contentWidth * c + contentHeight * s).coerceAtLeast(1f), height / (contentWidth * s + contentHeight * c).coerceAtLeast(1f))
    }
}
