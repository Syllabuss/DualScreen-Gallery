package it.ayngallery

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** Reads the existing player's decoded PCM without changing or retaining it. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class MusicSpectrum(private val enabled: () -> Boolean, private val publish: (FloatArray) -> Unit) : TeeAudioProcessor.AudioBufferSink {
    private var rate = 44_100
    private var channels = 2
    private var encoding = C.ENCODING_PCM_16BIT
    private var last = 0L
    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        rate = sampleRateHz; channels = channelCount; this.encoding = encoding; last = 0
    }
    override fun handleBuffer(buffer: ByteBuffer) {
        if (!enabled() || encoding != C.ENCODING_PCM_16BIT || channels <= 0) return
        val now = SystemClock.elapsedRealtime()
        if (now - last < 50) return
        last = now
        val input = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val count = min(512, input.remaining() / (2 * channels))
        if (count < 64) return
        val samples = DoubleArray(count) { index ->
            var sample = 0.0
            repeat(channels) { sample += input.short / 32768.0 }
            sample / channels * (.5 - .5 * cos(2 * PI * index / (count - 1)))
        }
        publish(bands(samples, rate))
    }
    companion object {
        /** Six logarithmically spaced bands; silence remains silence. */
        fun bands(samples: DoubleArray, rate: Int): FloatArray = doubleArrayOf(80.0, 200.0, 500.0, 1250.0, 3150.0, 8000.0).map { frequency ->
            val coefficient = 2 * cos(2 * PI * frequency.coerceAtMost(rate * .45) / rate)
            var previous = 0.0; var before = 0.0
            for (sample in samples) { val value = sample + coefficient * previous - before; before = previous; previous = value }
            val amplitude = sqrt(max(0.0, previous * previous + before * before - coefficient * previous * before)) / samples.size.coerceAtLeast(1)
            ((20 * log10(amplitude.coerceAtLeast(.000001)) + 60) / 60).coerceIn(0.0, 1.0).toFloat()
        }.toFloatArray()
    }
}
