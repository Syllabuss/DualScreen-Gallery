package it.ayngallery

import android.app.Application
import android.content.Context
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "it")
class PadBindingsTest {
    @Test fun screenAndOptionsSwapOnlyTheStockPair() {
        val old = padDefaults().mapValues { (context, bindings) ->
            if (context in setOf(PadContext.LIBRARY, PadContext.VIEWER, PadContext.PLAYER))
                bindings + mapOf(PadAction.SWAP to PadBinding(setOf(PadButton.X)), PadAction.SETTINGS to PadBinding(setOf(PadButton.Y)))
            else bindings
        }
        val migrated = PadSettings(profiles = mapOf("default" to PadProfile(old))).screenOptionsShortcuts()
        assertEquals(setOf(PadButton.Y), migrated.profile(null).bindings.getValue(PadContext.LIBRARY).getValue(PadAction.SWAP).buttons)
        assertEquals(setOf(PadButton.X), migrated.profile(null).bindings.getValue(PadContext.LIBRARY).getValue(PadAction.SETTINGS).buttons)
        assertEquals(migrated, migrated.screenOptionsShortcuts())
        val custom = PadSettings(profiles = mapOf("default" to PadProfile(old).assign(PadContext.LIBRARY, PadAction.SETTINGS, PadBinding(setOf(PadButton.R3)), replace = true)))
        assertEquals(custom.profile(null).bindings.getValue(PadContext.LIBRARY), custom.screenOptionsShortcuts().profile(null).bindings.getValue(PadContext.LIBRARY))
    }
    @Test fun viewShortcutMigratesStockFrameAndPreservesCustomBindings() {
        val stock = padDefaults().mapValues { (context, bindings) ->
            if (context in setOf(PadContext.LIBRARY, PadContext.VIEWER, PadContext.PLAYER))
                (bindings - PadAction.TOGGLE_VIEW) + (PadAction.FRAME to PadBinding(setOf(PadButton.R3))) +
                    (PadAction.FAVORITE to PadBinding(setOf(PadButton.L3))) else bindings
        }
        val migrated = PadSettings(profiles = mapOf("default" to PadProfile(stock))).viewShortcut().profile(null)
        for (context in listOf(PadContext.LIBRARY, PadContext.VIEWER, PadContext.PLAYER)) {
            assertEquals(setOf(PadButton.L3), migrated.bindings.getValue(context).getValue(PadAction.FRAME).buttons)
            if (context != PadContext.VIEWER) assertEquals(setOf(PadButton.R3), migrated.bindings.getValue(context).getValue(PadAction.TOGGLE_VIEW).buttons)
        }
        val custom = PadProfile().assign(PadContext.LIBRARY, PadAction.SETTINGS, PadBinding(setOf(PadButton.R3)), replace = true)
        assertEquals(custom, PadSettings(profiles = mapOf("default" to custom)).viewShortcut().profile(null))
    }
    @Test fun oldDefaultPlayerProfileGainsShoulderTabNavigation() {
        val defaults = padDefaults()
        val old = PadSettings(profiles = mapOf("default" to PadProfile(defaults +
            (PadContext.PLAYER to (defaults.getValue(PadContext.PLAYER) - setOf(PadAction.TAB_PREVIOUS, PadAction.TAB_NEXT))))))
        val restored = PadSettings.decode(old.encode())
        assertEquals(defaults, restored.profile(null).bindings)
    }
    @Test fun upperButtonsResizeGridsAndUpgradeOnlyStockBindings() {
        val original = PadProfile()
        val library = original.bindings.getValue(PadContext.LIBRARY)
        assertEquals(PadBinding(setOf(PadButton.SELECT)), library[PadAction.GRID_SMALLER])
        assertEquals(PadBinding(setOf(PadButton.START)), library[PadAction.GRID_LARGER])
        val old = original.copy(bindings = original.bindings.mapValues { (context, bindings) ->
            if (context in setOf(PadContext.LIBRARY, PadContext.PLAYER))
                (bindings - PadAction.GRID_SMALLER - PadAction.GRID_LARGER) + mapOf(
                    PadAction.MENU to PadBinding(setOf(PadButton.START)), PadAction.DISPLAY to PadBinding(setOf(PadButton.SELECT)))
            else bindings
        })
        assertEquals(original, PadSettings(profiles = mapOf("default" to old)).gridShortcuts().profile(null))
        val custom = old.assign(PadContext.LIBRARY, PadAction.DELETE, PadBinding(setOf(PadButton.START)), replace = true)
        assertEquals(custom, PadSettings(profiles = mapOf("default" to custom)).gridShortcuts().profile(null).let {
            it.copy(bindings = it.bindings + (PadContext.PLAYER to custom.bindings.getValue(PadContext.PLAYER)))
        })
        assertFalse(PadAction.GRID_SMALLER in padActions(PadContext.VIEWER))
        assertEquals(PadAction.GRID_SMALLER, PadInput(PadSettings(), original, PadContext.LIBRARY)
            .update(PadButton.SELECT, "key", true, 0).single())
    }
    @Test fun galleryShortcutsUpgradeOnlyThePreviousDefaults() {
        val previous = PadProfile().copy(bindings = padDefaults().mapValues { (context, bindings) ->
            if (context in setOf(PadContext.LIBRARY, PadContext.VIEWER, PadContext.PLAYER))
                (bindings - setOf(PadAction.FRAME, PadAction.TOGGLE_VIEW)) + mapOf(PadAction.FAVORITE to PadBinding(setOf(PadButton.X)),
                    PadAction.SWAP to PadBinding(setOf(PadButton.L3, PadButton.R3)), PadAction.SETTINGS to PadBinding(setOf(PadButton.Y))) else bindings
        })
        val upgraded = PadSettings(profiles = mapOf("default" to previous)).albumShortcuts()
        upgraded.validate()
        assertEquals(PadBinding(setOf(PadButton.X)), upgraded.profile(null).bindings.getValue(PadContext.LIBRARY)[PadAction.SWAP])
        val custom = previous.assign(PadContext.LIBRARY, PadAction.CONFIRM, PadBinding(setOf(PadButton.B)), replace = true)
            .assign(PadContext.LIBRARY, PadAction.BACK, PadBinding(setOf(PadButton.A)))
        assertEquals(PadBinding(setOf(PadButton.B)), PadSettings(profiles = mapOf("default" to custom)).albumShortcuts()
            .profile(null).bindings.getValue(PadContext.LIBRARY)[PadAction.CONFIRM])
    }
    @Test fun profilesRoundTripAndOnlySaveAppliesAndCorruptDataFallsBack() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val store = PreferenceStore(app)
        val external = PadProfile().assign(PadContext.LIBRARY, PadAction.PLAY_PAUSE, PadBinding(setOf(PadButton.R3), "Pausa musica"), replace = true)
        val pad = PadSettings(profiles = mapOf("default" to PadProfile(), "external-fixture" to external))
        assertEquals(pad, PadSettings.decode(pad.encode()))
        assertEquals(PadSettings(), store.state.value.pad)
        store.save(store.state.value.copy(pad = pad))
        assertEquals(pad, PreferenceStore(app).state.value.pad)
        assertEquals(external, pad.profile("external-fixture")); assertEquals(PadProfile(), pad.profile("another-device"))
        val bad = pad.copy(release = 0.9f)
        assertTrue(runCatching { store.save(store.state.value.copy(pad = bad)) }.isFailure)
        assertEquals(pad, PreferenceStore(app).state.value.pad)
        app.getSharedPreferences("ui_preferences_v1", Context.MODE_PRIVATE).edit().putString("pad_bindings", "invalid").commit()
        assertEquals(PadSettings(), PreferenceStore(app).state.value.pad)
    }
    @Test fun conflictReplacementIsExplicitAndMandatoryBackCannotBeRemovedFromSavedProfiles() {
        val original = PadProfile()
        val binding = PadBinding(setOf(PadButton.Y))
        assertEquals(setOf(PadAction.SWAP), original.conflicts(PadContext.LIBRARY, PadAction.DELETE, binding))
        assertTrue(runCatching { original.assign(PadContext.LIBRARY, PadAction.DELETE, binding) }.isFailure)
        val changed = original.assign(PadContext.LIBRARY, PadAction.DELETE, binding, replace = true)
        assertFalse(PadAction.SWAP in changed.bindings.getValue(PadContext.LIBRARY))
        assertEquals(binding, changed.bindings.getValue(PadContext.LIBRARY)[PadAction.DELETE])
        PadSettings(profiles = mapOf("default" to changed)).validate()
        val noBack = changed.assign(PadContext.LIBRARY, PadAction.BACK, null)
        assertTrue(runCatching { PadSettings(profiles = mapOf("default" to noBack)).validate() }.isFailure)
        assertTrue(runCatching { original.assign(PadContext.DIALOG, PadAction.DELETE, binding) }.isFailure)
        assertTrue(runCatching { original.assign(PadContext.LIBRARY, PadAction.SWAP, PadBinding(setOf(PadButton.UP, PadButton.R3))) }.isFailure)
    }
    @Test fun triggerAliasesAndRepeatAreMergedAndOneShotActionsNeverRepeat() {
        val settings = PadSettings()
        val input = PadInput(settings, PadProfile(), PadContext.LIBRARY)
        assertEquals(listOf(PadAction.MEDIA_NEXT), input.update(PadButton.R2, "key", true, 0))
        assertTrue(input.axis(PadButton.R2, "axis", 0.9f, 1).isEmpty())
        input.update(PadButton.R2, "key", false, 2)
        assertTrue(input.axis(PadButton.R2, "axis", 0.5f, 3).isEmpty())
        input.axis(PadButton.R2, "axis", 0f, 4)
        assertEquals(listOf(PadAction.MEDIA_NEXT), input.axis(PadButton.R2, "axis", 0.9f, 5))
        input.update(PadButton.DOWN, "key", true, 10)
        assertTrue(input.poll(369).isEmpty()); assertEquals(listOf(PadAction.DOWN), input.poll(370))
        input.clear(); assertTrue(input.poll(10_000).isEmpty())
    }
    @Test fun chordConsumesSinglesInEitherPressOrderAndContextSeparatesMediaAndMusic() {
        val profile = PadProfile().assign(PadContext.LIBRARY, PadAction.SWAP, PadBinding(setOf(PadButton.L3, PadButton.R3)), replace = true)
            .assign(PadContext.LIBRARY, PadAction.PLAY_PAUSE, PadBinding(setOf(PadButton.L3)), replace = true)
        for (order in listOf(listOf(PadButton.L3, PadButton.R3), listOf(PadButton.R3, PadButton.L3))) {
            val input = PadInput(PadSettings(), profile, PadContext.LIBRARY)
            assertTrue(input.update(order[0], "key", true, 0).isEmpty())
            assertEquals(listOf(PadAction.SWAP), input.update(order[1], "key", true, 1))
            assertTrue(input.poll(5000).isEmpty())
            assertTrue(input.update(order[0], "key", false, 2).isEmpty())
            assertTrue(input.update(order[1], "key", false, 3).isEmpty())
        }
        val alone = PadInput(PadSettings(), profile, PadContext.LIBRARY)
        alone.update(PadButton.L3, "key", true, 0)
        assertEquals(listOf(PadAction.PLAY_PAUSE), alone.update(PadButton.L3, "key", false, 1))
        assertEquals(listOf(PadAction.TRACK_NEXT), PadInput(PadSettings(), profile, PadContext.PLAYER).update(PadButton.R2, "key", true, 0))
        assertTrue(PadInput(PadSettings(), profile, PadContext.DIALOG).update(PadButton.R2, "key", true, 0).isEmpty())
    }
    @Test fun recordingBatchesAxesMergesAliasesAndNeverCombinesDevices() {
        val recording = PadRecording(PadSettings())
        assertFalse(recording.axes(1, mapOf(PadButton.LEFT to 0f, PadButton.RIGHT to 0f)))
        assertNull(recording.deviceId)
        assertFalse(recording.update(1, "key", mapOf(PadButton.R2 to true)))
        assertFalse(recording.axes(1, mapOf(PadButton.R2 to 0.9f)))
        assertFalse(recording.update(2, "key", mapOf(PadButton.L3 to true)))
        assertEquals(setOf(PadButton.R2), recording.candidate)
        assertFalse(recording.update(1, "key", mapOf(PadButton.R2 to false)))
        assertFalse(recording.axes(1, mapOf(PadButton.R2 to 0.5f)))
        assertTrue(recording.axes(1, mapOf(PadButton.R2 to 0f)))
        recording.clear()
        assertFalse(recording.axes(2, mapOf(PadButton.LEFT to 0.9f, PadButton.RIGHT to 0f)))
        assertFalse(recording.axes(2, mapOf(PadButton.LEFT to 0f, PadButton.RIGHT to 0.9f)))
        assertEquals(setOf(PadButton.RIGHT), recording.candidate)
        assertTrue(recording.axes(2, mapOf(PadButton.LEFT to 0f, PadButton.RIGHT to 0f)))
        assertEquals(2, recording.deviceId)
        val noDirections = PadProfile().assign(PadContext.DIALOG, PadAction.DOWN, null)
        assertTrue(runCatching { PadSettings(profiles = mapOf("default" to noDirections)).validate() }.isFailure)
    }
    @Test fun simultaneousThemeAndControllerSavesKeepBothSettings() = runBlocking {
        val store = PreferenceStore(RuntimeEnvironment.getApplication())
        val profile = PadProfile().assign(PadContext.VIEWER, PadAction.PLAY_PAUSE, PadBinding(setOf(PadButton.R3)), replace = true)
        val pad = PadSettings(profiles = mapOf("default" to profile))
        listOf(async { store.update { it.copy(pad = pad) } }, async { store.update { it.copy(themeId = "rose_milk") } }).awaitAll()
        assertEquals(pad, store.state.value.pad); assertEquals("rose_milk", store.state.value.themeId)
        assertEquals(store.state.value, PreferenceStore(RuntimeEnvironment.getApplication()).state.value)
    }
}
