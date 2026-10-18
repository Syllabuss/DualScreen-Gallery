package it.ayngallery

import org.junit.Assert.*
import org.junit.Test

/** Original normalization regressions, exercised against the single production kernel. */
class ControllerInputTest {
    private fun input() = PadInput(PadSettings(), PadProfile(), PadContext.LIBRARY)
    @Test fun keyAndAxisDoNotDuplicateOneTrigger() {
        val input = input()
        assertEquals(listOf(PadAction.MEDIA_NEXT), input.update(PadButton.R2, "key", true, 0))
        assertTrue(input.axis(PadButton.R2, "axis", 0.9f, 1).isEmpty())
        assertTrue(input.update(PadButton.R2, "key", false, 2).isEmpty())
        assertTrue(input.axis(PadButton.R2, "axis", 0.7f, 3).isEmpty())
        input.axis(PadButton.R2, "axis", 0f, 4)
        assertEquals(listOf(PadAction.MEDIA_NEXT), input.update(PadButton.R2, "key", true, 5))
    }
    @Test fun axisHasDeadzoneAndReleaseHysteresis() {
        val input = input()
        assertTrue(input.axis(PadButton.RIGHT, "stick", 0.5f, 0).isEmpty())
        assertEquals(listOf(PadAction.RIGHT), input.axis(PadButton.RIGHT, "stick", 0.7f, 1))
        assertTrue(input.axis(PadButton.RIGHT, "stick", 0.5f, 2).isEmpty())
        input.axis(PadButton.RIGHT, "stick", 0.2f, 3)
        assertEquals(listOf(PadAction.RIGHT), input.axis(PadButton.RIGHT, "stick", 0.8f, 4))
    }
    @Test fun navigationRepeatsButTriggersDoNot() {
        val input = input()
        input.update(PadButton.DOWN, "key", true, 0)
        input.update(PadButton.R2, "key", true, 0)
        assertTrue(input.poll(359).isEmpty())
        assertEquals(listOf(PadAction.DOWN), input.poll(360))
        assertTrue(input.poll(400).isEmpty())
        assertEquals(listOf(PadAction.DOWN), input.poll(460))
    }
    @Test fun focusLossClearsHeldInputsAndRepeats() {
        val input = input()
        input.update(PadButton.UP, "key", true, 0)
        input.clear()
        assertTrue(input.poll(1000).isEmpty())
        assertEquals(listOf(PadAction.UP), input.update(PadButton.UP, "key", true, 1001))
    }
}
