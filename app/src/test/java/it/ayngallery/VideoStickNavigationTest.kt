package it.ayngallery

import org.junit.Assert.*
import org.junit.Test

class VideoStickNavigationTest {
    @Test fun touchSeeksShareTheStickSequenceAndKeepControlsOutsideTheirBands() {
        val input = VideoStickNavigation()
        assertEquals(5, input.seek(1, 0))
        assertEquals(10, input.update(.9f, 0f, 400, .6f, .35f)?.seekSeconds)
        assertEquals(10, input.seek(1, 800))
        assertEquals(-5, input.seek(-1, 900))
        assertEquals(-5, input.seek(-1, 2001))
        assertEquals(-1, videoDoubleTapDirection(.2f, .5f))
        assertEquals(1, videoDoubleTapDirection(.8f, .5f))
        assertNull(videoDoubleTapDirection(.5f, .5f))
        assertNull(videoDoubleTapDirection(.2f, .1f))
        assertNull(videoDoubleTapDirection(.8f, .8f))
        assertNull(videoDoubleTapDirection(.99f, .5f))
    }
    @Test fun holdingRepeatsEveryHalfSecondAndReversalRestartsTheSequence() {
        val input = VideoStickNavigation()
        assertEquals(5, input.update(.9f, 0f, 0, .6f, .35f)?.seekSeconds)
        assertNull(input.update(.9f, .4f, 499, .6f, .35f))
        assertEquals(listOf(10, 10, 30, 30, 60, 60), (1..6).map {
            input.update(.9f, .4f, it * 500L, .6f, .35f)?.seekSeconds
        })
        assertEquals(-5, input.update(-.9f, 0f, 3001, .6f, .35f)?.seekSeconds)
        assertNull(input.update(-.9f, 0f, 3500, .6f, .35f))
        assertEquals(-10, input.update(-.9f, 0f, 3501, .6f, .35f)?.seekSeconds)
    }
    @Test fun volumeLocksOutAccidentalHorizontalDriftUntilCentred() {
        val input = VideoStickNavigation()
        assertNull(input.update(.75f, -.75f, 0, .6f, .35f))
        assertEquals(1, input.update(.2f, -.9f, 10, .6f, .35f)?.volume)
        assertNull(input.update(.9f, -.4f, 300, .6f, .35f))
        assertEquals(-1, input.update(.6f, .9f, 600, .6f, .35f)?.volume)
        input.update(0f, 0f, 650, .6f, .35f)
        assertEquals(5, input.update(.9f, .1f, 700, .6f, .35f)?.seekSeconds)
        assertNull(input.update(.9f, .8f, 1000, .6f, .35f))
        assertNull(input.update(Float.NaN, 0f, 1100, .6f, .35f))
    }
    @Test fun rapidFlicksAccelerateAndDirectionOrIdleRestartsAtFiveSeconds() {
        val input = VideoStickNavigation()
        fun flick(x: Float, time: Long): Int? {
            input.update(0f, 0f, time - 1, .6f, .35f)
            return input.update(x, .1f, time, .6f, .35f)?.seekSeconds
        }
        assertEquals(listOf(5, 10, 10, 30, 30, 60, 60), (0..6).map { flick(.9f, it * 400L) })
        assertEquals(-5, flick(-.9f, 2800))
        assertEquals(-10, flick(-.9f, 3200))
        assertEquals(5, flick(.9f, 3600))
        assertEquals(5, flick(.9f, 5000))
    }
}
