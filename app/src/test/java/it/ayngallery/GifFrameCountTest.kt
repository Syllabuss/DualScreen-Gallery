package it.ayngallery

import org.junit.Test
import org.junit.Assert.*
import java.io.ByteArrayInputStream

class GifFrameCountTest {
    private fun gif(frames: Int): ByteArray {
        val header = "GIF89a".toByteArray() + byteArrayOf(1, 0, 1, 0, 0, 0, 0)
        val frame = byteArrayOf(0x2c, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x44, 1, 0)
        return header + (0 until frames).flatMap { frame.toList() }.toByteArray() + byteArrayOf(0x3b)
    }
    @Test fun countsBlocksWithoutTreatingPixelBytesAsFrameMarkers() {
        assertEquals(3, GifFrameCount.read(ByteArrayInputStream(gif(3))))
    }
    @Test fun cancelledProviderReadIsNotReportedAsMalformedGif() {
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            GifFrameCount.read(ByteArrayInputStream(gif(2))) { throw kotlinx.coroutines.CancellationException() }
        }
    }
    @Test fun rejectsTruncatedAndInvalidInput() {
        assertNull(GifFrameCount.read(ByteArrayInputStream(gif(2).dropLast(2).toByteArray())))
        assertNull(GifFrameCount.read(ByteArrayInputStream("not a gif".toByteArray())))
    }
}
