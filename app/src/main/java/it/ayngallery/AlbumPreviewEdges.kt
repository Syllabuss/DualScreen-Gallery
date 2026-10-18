package it.ayngallery

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import kotlin.math.max

internal fun albumEdgeOpacity(x: Float, y: Float, mode: String): Float {
    fun fade(distance: Float, span: Float) = (1f - distance / span).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
    val top = if ((mode == "TOP" || mode == "ALL" || mode == "VERTICAL")) fade(y, .22f) * .75f else 0f
    val bottom = if ((mode == "BOTTOM" || mode == "ALL" || mode == "VERTICAL")) fade(1f - y, .22f) * .75f else 0f
    val sides = if ((mode == "SIDES" || mode == "ALL")) max(fade(x, .15f), fade(1f - x, .15f)) * .65f else 0f
    return max(max(top, bottom), sides)
}

// At most six edge modes. No media is retained.
private val edgeMasks = mutableMapOf<String, Bitmap>()

/** One alpha mask avoids doubling the darkness where edge gradients meet. */
@Composable
internal fun AlbumPreviewEdges(mode: String, studio: Boolean) {
    val mask = remember(mode) { edgeMasks.getOrPut(mode) {
        val width = 256; val height = 144
        val pixels = IntArray(width * height) { index ->
            val x = index % width; val y = index / width
            val nx = x.toFloat() / (width - 1); val ny = y.toFloat() / (height - 1)
            val shadow = albumEdgeOpacity(nx, ny, mode)
            (shadow * 255).toInt() shl 24
        }
        Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
    }
    val paint = remember { Paint(Paint.FILTER_BITMAP_FLAG) }
    val accent = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxSize()) {
        drawIntoCanvas { it.nativeCanvas.drawBitmap(mask, null, RectF(0f, 0f, size.width, size.height), paint) }
        if (studio) {
            val band = size.minDimension * .09f
            val spacing = 18.dp.toPx()
            fun opacity(distance: Float) = (1f - distance / band).coerceIn(0f, 1f).let { it * it * .5f }
            fun colors(span: Float, fixed: Float) = arrayOf(
                0f to accent.copy(alpha = .5f), band / span to accent.copy(alpha = fixed),
                (1f - band / span) to accent.copy(alpha = fixed), 1f to accent.copy(alpha = .5f))
            var x = spacing / 2
            while (x < size.width) {
                val alpha = opacity(minOf(x, size.width - x))
                drawLine(Brush.verticalGradient(*colors(size.height, alpha)), Offset(x, 0f), Offset(x, size.height), .7.dp.toPx())
                x += spacing
            }
            var y = spacing / 2
            while (y < size.height) {
                val alpha = opacity(minOf(y, size.height - y))
                drawLine(Brush.horizontalGradient(*colors(size.width, alpha)), Offset(0f, y), Offset(size.width, y), .7.dp.toPx())
                y += spacing
            }
        }
    }
}
