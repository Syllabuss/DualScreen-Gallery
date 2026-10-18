package it.ayngallery

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameSeekStepsTest {
    @Test fun heldDirectionAcceleratesAndReleaseOrReversalRestartsAtOneSecond() {
        val seek = FrameSeekSteps()
        assertEquals(1000L, seek.step(1, 0))
        for (time in 500L..2500L step 500L) assertEquals(1000L, seek.step(1, time))
        for (time in 3000L..7500L step 500L) assertEquals(10000L, seek.step(1, time))
        assertEquals(30000L, seek.step(1, 8000))
        assertEquals(-1000L, seek.step(-1, 8500))
        assertEquals(-1000L, seek.step(-1, 10000))
    }
}
