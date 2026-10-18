package it.ayngallery

import org.junit.Test
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

/** Exercises the real Java LAME encoder; Android decoding is tested separately on a device. */
class Mp3PcmEncoderTest {
    @Test fun stereoPcmProducesPlayableMpegFramesAtTheChosenBitrate() {
        val output = ByteArrayOutputStream()
        Mp3PcmEncoder(44100, 2, 192).use { encoder ->
            for (offset in 0 until 44100 step 2048) {
                val count = minOf(2048, 44100 - offset)
                val samples = ByteBuffer.allocate(count * 4).order(ByteOrder.LITTLE_ENDIAN)
                repeat(count) { i ->
                    val sample = (sin((offset + i) * 440.0 * 2 * Math.PI / 44100) * 12000).toInt().toShort()
                    samples.putShort(sample); samples.putShort(sample)
                }
                samples.flip(); encoder.write(samples, 2, false, output)
            }
            encoder.finish(output)
        }
        val bytes = output.toByteArray()
        var position = 0; var frames = 0
        while (position + 4 <= bytes.size) {
            assertEquals(0xff, bytes[position].toInt() and 255)
            assertEquals(0xfb, bytes[position + 1].toInt() and 255)
            val header = bytes[position + 2].toInt() and 255
            assertEquals(11, header ushr 4) // MPEG-1 Layer III, 192 kb/s.
            assertEquals(0, (header ushr 2) and 3) // 44.1 kHz.
            val size = 144 * 192000 / 44100 + ((header ushr 1) and 1)
            position += size; frames++
        }
        assertEquals(bytes.size, position)
        assertTrue(frames * 1152 >= 44100)
        assertTrue(frames * 1152 < 44100 + 4096)
        val file = File("build/qa/beta27/lame-stereo.mp3")
        file.parentFile.mkdirs(); file.writeBytes(bytes)
    }

    @Test fun monoFloatAndMultichannelDownmixEncodeWithoutDependingOnDesktopAudioApis() {
        for (channels in listOf(1, 6)) {
            val output = ByteArrayOutputStream()
            Mp3PcmEncoder(48000, if (channels == 1) 1 else 2, 128).use { encoder ->
                val pcm = ByteBuffer.allocate(4800 * channels * 4).order(ByteOrder.LITTLE_ENDIAN)
                repeat(4800 * channels) { pcm.putFloat(.2f) }
                pcm.flip(); encoder.write(pcm, channels, true, output); encoder.finish(output)
            }
            assertTrue(output.size() > 1000)
        }
    }
}
