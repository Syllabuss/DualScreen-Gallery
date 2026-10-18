package it.ayngallery

import org.junit.Assert.*
import org.junit.Test

class ExplorerNavigationTest {
    @Test fun goingBackRestoresTheFocusedItemAndRefreshDoesNotLoseIt() {
        val root = ExplorerLocation("saf:test", "root")
        val child = root.copy(directoryId = "child")
        val entry = ExplorerEntry(root, "child", "Child", true, null, null, true)
        var pane = ExplorerPaneState().open(root)
        pane = pane.listed(pane.request, root, listOf(entry)).open(child).previous()
        assertEquals("child", pane.focusedId)
        pane = pane.listed(pane.request, root, listOf(entry)).open(root)
        assertEquals("child", pane.listed(pane.request, root, listOf(entry)).focusedId)
    }
    private val local = ExplorerLocation("local", "root")
    private val remote = ExplorerLocation("remote", "share")

    @Test fun twoHistoriesAndSelectionsFollowIdentitiesAcrossRepeatedSwaps() {
        var state = ExplorerNavigation().update(ExplorerSide.LOCAL) { it.open(local) }
            .update(ExplorerSide.REMOTE) { it.open(remote) }
        val entry = ExplorerEntry(local, "same-id", "photo.png", false, 100, 1, true)
        state = state.update(ExplorerSide.LOCAL) { it.listed(it.request, local, listOf(entry)).toggle(entry) }
        val frozen = state.local.frozenSelection()
        val original = state
        repeat(20) { state = state.swap() }
        assertEquals(original, state)
        assertEquals(ExplorerSide.REMOTE, state.swap().lower())
        state = state.update(ExplorerSide.REMOTE) { it.open(remote.copy(directoryId = "child")) }
        assertEquals(original.local, state.local)
        assertEquals(listOf(entry), frozen)
        assertEquals(listOf(entry), state.local.frozenSelection())
        assertEquals(remote, state.remote.previous().location)
        assertEquals("child", state.remote.previous().next().location?.directoryId)
    }

    @Test fun obsoleteListingsAndMixedProvidersCannotReplaceCurrentDirectory() {
        val first = ExplorerPaneState().open(local)
        val current = first.open(local.copy(directoryId = "child"))
        assertSame(current, current.listed(first.request, local, emptyList()))
        assertTrue(runCatching { current.listed(current.request, current.location!!,
            listOf(ExplorerEntry(remote, "id", "other", true, null, null, true))) }.isFailure)
        val returned = current.previous()
        assertSame(returned, returned.listed(first.request, local, emptyList()))
    }

    @Test fun newNavigationDropsForwardHistoryAndFrozenSelectionSurvivesFolderChanges() {
        val entry = ExplorerEntry(local, "id", "photo.png", false, null, null, true)
        val pane = ExplorerPaneState().open(local).let { it.listed(it.request, local, listOf(entry)) }.toggle(entry)
        val child = pane.open(local.copy(directoryId = "child"))
        val other = child.previous().open(local.copy(directoryId = "other"))
        assertTrue(other.forward.isEmpty())
        assertEquals(listOf(entry), other.frozenSelection())
        assertEquals(local, other.previous().location)
    }
}
