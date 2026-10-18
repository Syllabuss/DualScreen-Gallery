package it.ayngallery

import java.io.InputStream

/** Reads block structure only, on demand: never decodes or indexes the animation. */
internal object GifFrameCount {
    fun read(source: InputStream, checkCancelled: () -> Unit = {}): Int? = try {
        val input = source.buffered()
        var bytes = 0
        fun byte(): Int {
            if (bytes % 8192 == 0) checkCancelled()
            check(++bytes <= 64 * 1024 * 1024) { localized(R.string.text_gif_too_large_803f38, "GIF troppo grande") }
            return input.read().also { check(it >= 0) { localized(R.string.text_incomplete_gif_597b31, "GIF incompleta") } }
        }
        fun skip(n: Int) { repeat(n) { byte() } }
        fun blocks() { while (true) { val n = byte(); if (n == 0) break; skip(n) } }
        val signature = CharArray(6) { byte().toChar() }.concatToString()
        check(signature == "GIF87a" || signature == "GIF89a")
        skip(4); val packed = byte(); skip(2)
        if (packed and 128 != 0) skip(3 * (1 shl ((packed and 7) + 1)))
        var frames = 0
        while (true) {
            when (byte()) {
                0x3b -> break
                0x21 -> { byte(); blocks() }
                0x2c -> {
                    skip(8); val image = byte()
                    if (image and 128 != 0) skip(3 * (1 shl ((image and 7) + 1)))
                    byte(); blocks(); frames++
                    check(frames <= 200000)
                }
                else -> error(localized(R.string.text_invalid_gif_block_19455d, "Blocco GIF non valido"))
            }
        }
        frames.takeIf { it > 0 }
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (_: IllegalStateException) { null }
}
