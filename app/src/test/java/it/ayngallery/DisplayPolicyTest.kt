package it.ayngallery

import org.junit.Assert.*
import org.junit.Test

class DisplayPolicyTest {
    @Test fun singleScreenAlwaysMakesTheRemainingPanelTheLibraryRegardlessOfPreviousSwap() {
        for (slot in 0..1) for (swapped in listOf(false, true)) {
            val state = DisplayState(anchorSlot = slot, swapped = swapped)
            assertEquals(slot, DisplayPolicy.librarySlot(state, singleScreen = true))
            assertEquals(if (swapped) 1 else 0, DisplayPolicy.librarySlot(state, singleScreen = false))
        }
    }

    @Test fun landscapeIsOptInAndNeverAppliesToMultipleDisplays() {
        val phone = ScreenInfo(0, "phone", "Phone", 1080, 2340, 0)
        val lower = ScreenInfo(4, "lower", "Lower", 1080, 1240, 0)
        assertFalse(DisplayPolicy.preferSingleScreenLandscape(listOf(phone), false))
        assertTrue(DisplayPolicy.preferSingleScreenLandscape(listOf(phone), true))
        assertFalse(DisplayPolicy.preferSingleScreenLandscape(listOf(phone, lower), true))
        assertFalse(DisplayPolicy.preferSingleScreenLandscape(emptyList(), true))
    }

    @Test fun thorTaskRemapKeepsThePhysicalLibraryPanelAndDoesNotFlipAgain() {
        val legacy = DisplayState(anchor = "lower", swapped = false, anchorSlot = 0)
        val primaryMain = DisplayPolicy.thorTaskState("lower", "upper", legacy)
        assertEquals(DisplayState(anchor = "lower", swapped = true, anchorSlot = 1), primaryMain)
        assertEquals(primaryMain, DisplayPolicy.thorTaskState("lower", "upper", primaryMain))
        assertEquals(primaryMain, DisplayPolicy.thorTaskState("lower", "upper", legacy.copy(anchor = "upper", anchorSlot = 1)))
        assertEquals(primaryMain, DisplayPolicy.thorTaskState("lower", "upper", primaryMain.copy(anchor = "upper", anchorSlot = 0)))
        assertFalse(DisplayPolicy.thorTaskState("lower", "upper", legacy.copy(swapped = true)).swapped)
        assertEquals(setOf("game"), DisplayPolicy.thorTaskState("lower", "upper", legacy.copy(ceded = setOf("game"))).ceded)
    }
    @Test fun thorUsesPhysicalPanelSizesInsteadOfIdsAndNeverGuessesOnOtherHardware() {
        val lower = ScreenInfo(42, "lower", "Panel", 1080, 1240, 0)
        val upper = ScreenInfo(8, "upper", "Panel", 1920, 1080, 0)
        assertEquals(lower to upper, DisplayPolicy.thorPair("AYN Thor", listOf(upper, lower)))
        assertNull(DisplayPolicy.thorPair("Other", listOf(upper, lower)))
        assertNull(DisplayPolicy.thorPair("AYN Thor", listOf(lower)))
        assertNull(DisplayPolicy.thorPair("AYN Thor", listOf(lower, upper.copy(width = 1600))))
    }

    @Test fun automaticLaunchUsesExactlyTwoDetectedScreensButNeverOverridesAnyCession() {
        val pair = setOf("upper", "lower")
        assertEquals("upper", DisplayPolicy.startupOther("lower", pair, emptySet(), DisplayState(), true))
        assertNull(DisplayPolicy.startupOther("lower", setOf("lower"), emptySet(), DisplayState(), true))
        assertNull(DisplayPolicy.startupOther("lower", pair + "remote", emptySet(), DisplayState(), true))
        assertNull(DisplayPolicy.startupOther("lower", pair, emptySet(), DisplayState(ceded = setOf("upper")), true))
        assertNull(DisplayPolicy.startupOther("lower", pair, emptySet(), DisplayState(ceded = setOf("old-upper")), true))
        assertNull(DisplayPolicy.startupOther("lower", pair, emptySet(), DisplayState(), false))
    }
    private val displays = setOf("upper", "lower")
    @Test fun savedPairRestoresOnlyRecognizedUncededDisplays() {
        assertEquals("upper", DisplayPolicy.rememberedOther("lower", displays, displays, DisplayState("lower")))
        assertNull(DisplayPolicy.rememberedOther("lower", displays, emptySet(), DisplayState()))
        assertNull(DisplayPolicy.rememberedOther("lower", displays, displays, DisplayState(ceded = setOf("upper"))))
        assertNull(DisplayPolicy.rememberedOther("lower", setOf("lower"), displays, DisplayState()))
        assertNull(DisplayPolicy.rememberedOther("new-lower", setOf("new-lower", "new-upper"), displays, DisplayState()))
    }

    @Test fun coldStartStaysOnLaunchDisplay() {
        assertEquals("lower", DisplayPolicy.ordinaryTarget("lower", displays, DisplayState()))
    }
    @Test fun ordinaryLaunchRespectsCessionAndAnchor() {
        val ceded = DisplayPolicy.singleHere("lower", displays, DisplayState())
        assertEquals(setOf("upper"), ceded.ceded)
        assertEquals("lower", DisplayPolicy.ordinaryTarget("upper", displays, ceded))
    }
    @Test fun missingAnchorNeverReclaimsCededScreen() {
        assertNull(DisplayPolicy.ordinaryTarget("upper", setOf("upper"), DisplayState("lower", setOf("upper"))))
    }
    @Test fun changedIdsFallBackToLaunchScreenOnly() {
        assertEquals("new-upper", DisplayPolicy.ordinaryTarget("new-upper", setOf("new-upper", "new-lower"),
            DisplayState("old-lower", setOf("old-upper"))))
    }
    @Test fun explicitExpansionRemovesOnlyItsCessions() {
        assertEquals(setOf("remote"), DisplayPolicy.expand("lower", "upper", displays,
            DisplayState("lower", setOf("upper", "remote"))).ceded)
    }
    @Test(expected = IllegalArgumentException::class) fun unavailableTargetCannotExpand() {
        DisplayPolicy.expand("lower", "absent", displays, DisplayState())
    }
    @Test fun swapRequiresBothStartedAndAuthorized() {
        assertFalse(DisplayPolicy.canSwap(setOf("lower"), displays, DisplayState()))
        assertFalse(DisplayPolicy.canSwap(displays, setOf("lower"), DisplayState()))
        assertFalse(DisplayPolicy.canSwap(displays, displays, DisplayState(ceded = setOf("upper"))))
        assertTrue(DisplayPolicy.canSwap(displays, displays, DisplayState()))
    }
    @Test fun cedingTheAnchorRetainsTheOtherPanel() {
        val next = DisplayPolicy.cede("upper", setOf("lower"), DisplayState("upper"))
        assertEquals("lower", next.anchor)
        assertEquals(setOf("upper"), next.ceded)
    }
}
