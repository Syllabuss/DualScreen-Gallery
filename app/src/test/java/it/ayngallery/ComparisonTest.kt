package it.ayngallery

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.app.Application

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "it")
class ComparisonTest {
    @Test fun librarySelectionNeverReplacesPins() {
        val state = ComparisonState().pin("first", listOf("first", "second"))!!
        assertEquals("first", state.target(state.panes[1], "second"))
        assertEquals("second", state.target(state.panes[0], "second"))
        assertTrue(state.enabled)
    }
    @Test fun fullFourPanesRequireExplicitReplacement() {
        var state = ComparisonState().resize(4)!!
        repeat(4) { state = state.pin("file$it", listOf("file$it"))!! }
        assertNull(state.pin("fifth", listOf("fifth")))
        val next = state.pin("fifth", listOf("fifth"), "pane_3")!!
        assertEquals("fifth", next.panes[2].mediaId)
        assertEquals(state.panes[0], next.panes[0])
        assertEquals(state.panes[3], next.panes[3])
        assertNull(next.resize(2))
        assertEquals(2, next.resize(2, discard = true)!!.panes.size)
    }
    @Test fun activePaneNavigationUsesItsOriginNotNewFolder() {
        val state = ComparisonState().pin("a", listOf("a", "b"))!!
        val next = state.navigate("unrelated", listOf("unrelated", "other"), 1)
        assertEquals("b", next.panes[1].mediaId)
        assertEquals(state.panes[0], next.panes[0])
        assertEquals("a", next.navigate(null, emptyList(), 1).panes[1].mediaId)
    }
    @Test fun zoomIsIndependentUnlessExplicitlyLinked() {
        val transform = ViewTransform(3f, 0.15f, -0.2f)
        val independent = ComparisonState().transform("pane_2", transform)
        assertEquals(ViewTransform(), independent.panes[0].view)
        assertEquals(transform, independent.panes[1].view)
        val linked = independent.copy(linked = true).transform("pane_1", ViewTransform(2f, 0.1f, 0.1f))
        assertEquals(linked.panes[0].view, linked.panes[1].view)
    }
    @Test fun persistedSessionRetainsStableReferencesAndViews() {
        val state = ComparisonState().pin("stable-id", listOf("stable-id", "another"))!!
            .transform("pane_2", ViewTransform(2f, 0.1f, -0.1f)).copy(linked = true)
        assertEquals(state, ComparisonStore.decode(ComparisonStore.encode(state).toString()))
        assertNull(ComparisonStore.decode("{broken"))
        val wrongVersion = ComparisonStore.encode(state).put("version", 99)
        assertNull(ComparisonStore.decode(wrongVersion.toString()))
    }
    @Test fun nonFiniteOrExtremeTransformsCannotPoisonRendering() {
        assertEquals(ViewTransform(), ViewTransform(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).bounded())
        assertEquals(ViewTransform(8f, 0.5f, -0.5f), ViewTransform(99f, 4f, -8f).bounded())
    }
    @Test fun shrinkingStopsAtHalfSizeAndCentresTheImage() {
        var view = ViewTransform(1f, .2f, -.2f)
        repeat(10) { view = view.fromPad(PadAction.ZOOM_OUT) }
        assertEquals(ViewTransform(.5f), view)
        assertEquals(.5f, ViewTransform(.1f).bounded().zoom)
    }
}
