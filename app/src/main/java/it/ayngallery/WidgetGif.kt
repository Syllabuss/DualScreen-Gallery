package it.ayngallery

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Movie
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class WidgetGif(val frames: List<Bitmap>, val interval: Int)

/** Sample a whole loop once, then let the launcher animate. No background polling or frame broadcasts. */
@Suppress("DEPRECATION")
internal suspend fun gifWidgetFrames(app: GalleryApp, item: MediaEntity, config: PhotoWidgetConfig,
    hostWidth: Int = 240, hostHeight: Int = 240): WidgetGif? = withContext(Dispatchers.IO) {
    runCatching {
        val data = app.catalog.readMediaSnapshot(item.id)
        val fresh = data.byId[item.id]?.takeIf { data.usable(it) && !DocumentVersions.changed(item.size, item.modified, it.size, it.modified) } ?: return@runCatching null
        val uri = app.catalog.uri(fresh, data)
        // Movie cannot downsample its source: bound compressed bytes and dimensions before allocating.
        val bytes = app.contentResolver.openInputStream(uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (output.size() <= 6 * 1024 * 1024) {
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            output.toByteArray()
         } ?: return@runCatching null
        if (bytes.size > 6 * 1024 * 1024) return@runCatching null
        if (!widgetGifWithinBudget(bytes)) return@runCatching null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > 4_000_000) return@runCatching null
        val movie = Movie.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching null
        val duration = movie.duration().takeIf { it in 1..60_000 } ?: return@runCatching null
        val count = (duration / 100).coerceIn(2, 24)
        val side = minOf(photoWidgetPixels(app, android.util.SizeF(hostWidth.toFloat(), hostHeight.toFloat())),
            kotlin.math.sqrt(6.0 * 1024 * 1024 / (count * 4)).toInt()).coerceAtLeast(128)
        val ratio = movie.width().toFloat() / movie.height()
        val width = if (ratio >= 1f) side else (side * ratio).toInt().coerceAtLeast(1)
        val height = if (ratio <= 1f) side else (side / ratio).toInt().coerceAtLeast(1)
        val source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val frames = List(count) { index ->
                source.eraseColor(android.graphics.Color.TRANSPARENT)
                movie.setTime(index * duration / count)
                val canvas = Canvas(source)
                canvas.scale(width.toFloat() / movie.width(), height.toFloat() / movie.height())
                movie.draw(canvas, 0f, 0f)
                photoWidgetBitmap(source, config.selection.fill, hostWidth, hostHeight, if (config.fixedMedia != null) config.zoom else 1f,
                    if (config.fixedMedia != null) config.panX else 0f, if (config.fixedMedia != null) config.panY else 0f, maxSide = side)
            }
            WidgetGif(frames, (duration / count).coerceAtLeast(80))
        } finally { source.recycle() }
    }.onFailure {
        if (it is kotlinx.coroutines.CancellationException) throw it
        android.util.Log.w("AynGallery", "Widget GIF decode failed", it)
    }.getOrNull()
}

/** Walk real GIF blocks, including extension payloads, before asking the native decoder to allocate. */
internal fun widgetGifWithinBudget(bytes: ByteArray): Boolean = runCatching {
    require(bytes.size >= 13 && String(bytes, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a"))
    fun byte(index: Int) = bytes[index].toInt() and 255
    fun word(index: Int) = byte(index) or (byte(index + 1) shl 8)
    val pixels = word(6).toLong() * word(8)
    require(pixels in 1..4_000_000)
    var position = 13
    if (byte(10) and 128 != 0) position += 3 * (1 shl ((byte(10) and 7) + 1))
    var frames = 0
    fun skipBlocks() {
        while (true) {
            val count = byte(position++)
            if (count == 0) return
            position += count
            require(position <= bytes.size)
        }
    }
    while (position < bytes.size) when (byte(position++)) {
        0x21 -> { position++; skipBlocks() }
        0x2C -> {
            require(position + 9 <= bytes.size)
            val framePixels = word(position + 4).toLong() * word(position + 6)
            require(framePixels in 1..pixels)
            val flags = byte(position + 8)
            position += 9
            if (flags and 128 != 0) position += 3 * (1 shl ((flags and 7) + 1))
            position++ // LZW minimum code size
            skipBlocks()
            frames++
            require(frames <= 240 && frames * pixels <= 16_000_000)
        }
        0x3B -> return@runCatching frames > 1
        else -> return@runCatching false
    }
    false
}.getOrDefault(false)
