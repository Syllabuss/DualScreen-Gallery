package it.ayngallery

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class MusicSpectrumTest {
    @Test fun discReturnsContinuouslyFromItsCurrentAngleAndSlowsBeforeRest() {
        assertEquals(135f, widgetDiscRestAngle(135f, 0f))
        assertEquals(0f, widgetDiscRestAngle(135f, 1f))
        assertEquals(-90f, widgetDiscRestAngle(-90f, 0f))
        assertTrue(widgetDiscRestAngle(135f, .5f) in 0f..135f)
        val early = widgetDiscRestAngle(135f, .1f) - widgetDiscRestAngle(135f, .2f)
        val late = widgetDiscRestAngle(135f, .8f) - widgetDiscRestAngle(135f, .9f)
        assertTrue(early > late)
    }
    @Test fun pauseSettlesGraduallyAndSilenceDoesNotInventMotion() {
        assertEquals(0f, widgetBarEnvelope(0f, 0f))
        var level = 1f
        repeat(30) {
            val next = widgetBarEnvelope(level, 0f)
            assertTrue(next <= level)
            if (it == 0) assertTrue(next > 0f)
            level = next
        }
        assertEquals(0f, level)
        assertTrue(widgetBarEnvelope(0f, 1f) > .5f)
    }
    @Test fun silenceAndToneProduceDifferentMeasuredBands() {
        assertTrue(MusicSpectrum.bands(DoubleArray(512), 44100).all { it == 0f })
        val tone = DoubleArray(512) { sin(2 * PI * 1250 * it / 44100) * (.5 - .5 * cos(2 * PI * it / 511)) }
        val bars = MusicSpectrum.bands(tone, 44100)
        assertEquals(3, bars.indices.maxBy { bars[it] })
        assertTrue(bars[3] > .7f)
        assertTrue(bars.all { it in 0f..1f })
    }
    @Test fun pulseFollowsMusicEnergyAndRespondsMoreToBass() {
        assertEquals(0f, musicPulseLevel(FloatArray(6)), 0f)
        val bass = musicPulseLevel(floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f))
        val treble = musicPulseLevel(floatArrayOf(0f, 0f, 0f, 0f, 0f, 1f))
        assertTrue(bass > treble)
        assertEquals(1.06f, widgetPulseScale(1f), .0001f)
        assertEquals(.70f, widgetPulseScale(0f), .0001f)
        assertTrue(widgetPulseScale(.25f) < widgetPulseScale(.75f))
    }
}
