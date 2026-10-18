package it.ayngallery

import org.junit.Assert.*
import org.junit.Test

class ConsoleStickControlsTest {
    @Test fun neutralAndAmbiguousDiagonalsDoNotChangeSettings() {
        val stick = ConsoleStickControls()
        assertNull(stick.update(.2f, .1f, 1000))
        assertNull(stick.update(.9f, .9f, 1010))
        assertFalse(stick.active)
    }
    @Test fun volumeRepeatsAtABoundedRateAndReversalIsImmediate() {
        val stick = ConsoleStickControls()
        assertEquals(ConsoleStep(ConsoleAxis.VOLUME, 1), stick.update(1f, .1f, 1000))
        assertNull(stick.update(1f, .1f, 1100))
        assertEquals(ConsoleStep(ConsoleAxis.VOLUME, 1), stick.update(1f, .1f, 1220))
        assertEquals(ConsoleStep(ConsoleAxis.VOLUME, -1), stick.update(-1f, .1f, 1240))
    }
    @Test fun brightnessLocksItsAxisUntilTheStickIsReleased() {
        val stick = ConsoleStickControls()
        assertEquals(ConsoleStep(ConsoleAxis.BRIGHTNESS, 1), stick.update(.1f, -1f, 1000))
        assertNull(stick.update(1f, -.1f, 1250))
        assertTrue(stick.active)
        assertNull(stick.update(0f, 0f, 1260))
        assertFalse(stick.active)
        assertEquals(ConsoleStep(ConsoleAxis.VOLUME, -1), stick.update(-1f, 0f, 1300))
        assertNull(stick.update(Float.NaN, 0f, 1500))
        assertFalse(stick.active)
    }
}
