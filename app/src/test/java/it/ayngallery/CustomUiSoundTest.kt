package it.ayngallery

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class CustomUiSoundTest {
    private fun wav(frames: Int, channels: Int = 1): ByteArray {
        val data = frames * channels * 2
        return ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(channels.toShort()); putInt(8000); putInt(8000 * channels * 2)
            putShort((channels * 2).toShort()); putShort(16); put("data".toByteArray()); putInt(data)
            repeat(frames * channels) { putShort(if (it % 2 == 0) 12000 else -12000) }
        }.array()
    }
    @Test fun normalizesStereoAndAcceptsExactlySixSeconds() {
        val sound = CustomUiSound.decode(wav(48000, 2))
        assertEquals(6000L, sound.duration)
        assertEquals(264644, sound.wav.size)
        assertEquals(256, sound.samples.size)
        assertEquals(sound.duration, CustomUiSound.decode(sound.wav).duration)
    }
    @Test fun rejectsLongTruncatedAndInvalidFiles() {
        listOf(wav(48001), wav(100).dropLast(1).toByteArray(), ByteArray(44)).forEach {
            try { CustomUiSound.decode(it); fail("Invalid audio accepted") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun readsAndPreservesTheOriginalBytes() {
        val input = wav(8000); val original = input.copyOf()
        assertEquals(1000L, CustomUiSound.read(input.inputStream()).duration)
        assertArrayEquals(original, input)
    }
}
