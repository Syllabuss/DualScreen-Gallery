package it.ayngallery

import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import android.content.*
import android.content.pm.ProviderInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.provider.DocumentsContract
import android.view.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.media3.common.Player
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowViewRootImpl
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.time.Duration

/** Production Compose/windows and key/axis dispatch. Focus notifications are simulated, not Thor routing. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestGalleryApp::class, qualifiers = "it-w800dp-h600dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class PadRenderTest {
    @Test fun albumVerticalNavigationRetracesItsColumnAfterReachingTheFooter() {
        start()
        runBlocking {
            val base = app.catalog.dao.albums().single()
            val folders = (0..10).map { base.copy(id = "nav-$it", documentKey = "nav-$it", name = "%02d".format(it)) }
            app.catalog.dao.saveAlbums(folders)
            app.catalog.dao.saveAlbumSources(folders.map { AlbumSource(it.id, "source", 1) })
        }
        app.preferences.reorderAlbums((0..10).map { "nav-$it" } + "album")
        panel.windowModel.albumGrid(true); panel.windowModel.albumColumns(3); panel.windowModel.go("ALBUM")
        compose.waitForIdle()
        compose.onNodeWithTag("albums-grid").performSemanticsAction(SemanticsActions.ScrollToIndex) { it(0) }
        compose.onNodeWithTag("album:nav-2").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.waitForIdle()
        for (expected in listOf("nav-5", "nav-8", "nav-10")) {
            key(panel, KeyEvent.KEYCODE_DPAD_DOWN)
            assertEquals(expected, app.session.previewAlbumId)
        }
        for (expected in listOf("nav-8", "nav-5", "nav-2")) {
            key(panel, KeyEvent.KEYCODE_DPAD_UP)
            assertEquals(expected, app.session.previewAlbumId)
            assertEquals(androidx.compose.ui.unit.IntOffset(0, -1), app.session.gridPreviewDirection)
        }
        key(panel, KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals("nav-1", app.session.previewAlbumId)
        key(panel, KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("nav-4", app.session.previewAlbumId)
        key(panel, KeyEvent.KEYCODE_DPAD_UP)
        assertEquals("nav-1", app.session.previewAlbumId)
        for (columns in listOf(2, 4, 5, 8)) {
            panel.windowModel.albumColumns(columns); compose.waitForIdle()
            val index = columns + 1
            compose.onNodeWithTag("albums-grid").performSemanticsAction(SemanticsActions.ScrollToIndex) { it(index / columns / 2) }
            compose.onNodeWithTag("album:nav-$index").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            compose.waitForIdle()
            key(panel, KeyEvent.KEYCODE_DPAD_UP)
            assertEquals("Column must be retained at density $columns", "nav-1", app.session.previewAlbumId)
            key(panel, KeyEvent.KEYCODE_DPAD_DOWN)
            assertEquals("nav-$index", app.session.previewAlbumId)
        }
    }
    @Test fun aLargeAlbumHasTwoSmallAlbumsBesideItWithoutOverlap() {
        start()
        runBlocking {
            val base = app.catalog.dao.albums().single()
            val folders = (0..2).map { base.copy(id = "size-$it", documentKey = "size-$it", name = "Size $it") }
            app.catalog.dao.saveAlbums(folders)
            app.catalog.dao.saveAlbumSources(folders.map { AlbumSource(it.id, "source", 1) })
        }
        app.preferences.reorderAlbums(listOf("size-0", "size-1", "size-2", "album"))
        app.preferences.albumSize(setOf("size-0"), 4)
        panel.windowModel.albumGrid(true); panel.windowModel.albumColumns(3); panel.windowModel.go("ALBUM")
        compose.waitUntil(5000) { compose.onAllNodesWithTag("album:size-2").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        val large = compose.onNodeWithTag("album:size-0").fetchSemanticsNode().boundsInRoot
        val first = compose.onNodeWithTag("album:size-1").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("album:size-2").fetchSemanticsNode().boundsInRoot
        assertTrue(large.height > first.height * 1.8f)
        assertTrue(first.left >= large.right)
        assertEquals(first.left, second.left, 1f)
        assertTrue(second.top >= first.bottom)
        assertTrue(second.bottom <= large.bottom + 2f)
    }
    @Test fun confirmOnFocusedPhotoTogglesItsOverlayWithoutChangingMedia() {
        start(); val preview = focusPreview()
        compose.onNodeWithTag("media:a").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.waitForIdle()
        val before = app.session.selectedMediaId
        app.session.imageOverlayVisible = true
        key(preview, KeyEvent.KEYCODE_BUTTON_A)
        assertFalse(app.session.imageOverlayVisible)
        assertEquals(before, app.session.selectedMediaId)
        key(preview, KeyEvent.KEYCODE_BUTTON_A)
        assertTrue(app.session.imageOverlayVisible)
    }
    @Test fun repeatingFocusAfterTouchKeepsTheGridTransitionDirection() {
        start()
        app.session.selectMedia("a")
        app.session.selectMedia("b")
        app.session.gridPreviewFocus(listOf("a", "b"), "a", "b", 3)
        val direction = androidx.compose.ui.unit.IntOffset(1, 0)
        assertEquals(direction, app.session.gridPreviewDirection)
        app.session.selectMedia("b")
        app.session.gridPreviewFocus(listOf("a", "b"), "b", "b", 3)
        assertEquals(direction, app.session.gridPreviewDirection)
        app.session.selectMedia("a")
        assertEquals(androidx.compose.ui.unit.IntOffset.Zero, app.session.gridPreviewDirection)
    }

    @Test fun shoulderButtonsCycleSettingsPreviewWithoutLeavingThemeOrInterface() {
        start(); val preview = focusPreview()
        app.session.openSettings(preview)
        val owner = app.session.settingsActivity()!!
        app.session.settingsSection = "THEME"
        owner.windowModel.go("SETTINGS"); compose.waitForIdle()
        assertEquals("ALBUM", app.session.settingsToolsSection)
        key(preview, KeyEvent.KEYCODE_BUTTON_R1)
        assertEquals("VIDEO", app.session.settingsToolsSection)
        assertEquals("SETTINGS", owner.windowModel.route.value.tab)
        app.session.settingsSection = "INTERFACE"
        key(preview, KeyEvent.KEYCODE_BUTTON_L1)
        assertEquals("ALBUM", app.session.settingsToolsSection)
        assertEquals("INTERFACE", app.session.settingsSection)
    }

    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = RuntimeEnvironment.getApplication() as GalleryApp
    private var activity: ActivityController<MainPanelActivity>? = null
    private var service: ServiceController<MusicService>? = null
    private val panel get() = activity!!.get()
    private var partner: ActivityController<OtherPanelActivity>? = null
    @After fun close() { partner?.pause()?.stop()?.destroy(); activity?.pause()?.stop()?.destroy(); app.music.close(); service?.destroy(); app.onTerminate() }

    private fun focusPreview(): OtherPanelActivity {
        org.robolectric.shadows.ShadowDisplayManager.addDisplay("w800dp-h600dp")
        app.session.refreshDisplays()
        partner = Robolectric.buildActivity(OtherPanelActivity::class.java,
            Intent(panel.intent).apply { removeExtra("ticket") }).setup().visible()
        val preview = partner!!.get()
        val second = app.session.screens.first { it.key != panel.screenKey }.key
        ReflectionHelpers.setField(preview, "screenKey", second)
        ReflectionHelpers.getField<MutableSet<String>>(app.session, "allowed").add(second)
        activity!!.windowFocusChanged(false); partner!!.windowFocusChanged(true)
        compose.waitForIdle()
        assertSame(panel, app.session.controllerTarget(preview))
        return preview
    }

    @Test fun previewFocusRoutesButtonsAndAxesToNavigationWithoutPreviewCommands() {
        start()
        val preview = focusPreview()
        val navigation = mutableListOf<PadAction>()
        val previewActions = mutableListOf<PadAction>()
        panel.moveFocus = { navigation.add(it) }; panel.padAction = { navigation.add(it) }
        preview.moveFocus = { previewActions.add(it) }; preview.padAction = { previewActions.add(it) }
        key(preview, KeyEvent.KEYCODE_DPAD_RIGHT)
        key(preview, KeyEvent.KEYCODE_BUTTON_SELECT)
        motion(preview, mapOf(MotionEvent.AXIS_X to -1f))
        motion(preview, mapOf(MotionEvent.AXIS_X to 0f))
        assertTrue(navigation.contains(PadAction.RIGHT))
        assertTrue(navigation.contains(PadAction.LEFT))
        assertTrue(navigation.size >= 3)
        assertTrue(previewActions.isEmpty())
        panel.windowModel.go("ALBUM", album = "album"); compose.waitForIdle()
        key(preview, KeyEvent.KEYCODE_BUTTON_B)
        assertNull(panel.windowModel.route.value.album)
        assertFalse(panel.isFinishing)
    }

    @Test fun controllerNavigatesAndChangesPlaybackOptions() {
        start()
        app.session.openSettings(panel)
        app.session.settingsSection = "PLAYBACK"
        panel.windowModel.go("SETTINGS")
        compose.waitForIdle()
        compose.onNodeWithTag("playback-tab:IMAGE").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        key(panel, KeyEvent.KEYCODE_DPAD_RIGHT); key(panel, KeyEvent.KEYCODE_DPAD_RIGHT)
        key(panel, KeyEvent.KEYCODE_BUTTON_A)
        assertEquals("MUSIC", app.session.playbackSettingsCategory)
        repeat(12) {
            val focused = runCatching { compose.onNodeWithTag("always-previous").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Focused] }.getOrDefault(false)
            if (!focused) key(panel, KeyEvent.KEYCODE_DPAD_DOWN)
        }
        compose.onNodeWithTag("always-previous").assertIsFocused()
        key(panel, KeyEvent.KEYCODE_BUTTON_A)
        assertTrue(app.session.settingsDraft!!.alwaysPrevious)
    }
    @Test fun controllerReachesTheSoundSwitchInsideControls() {
        start()
        app.session.openSettings(panel)
        app.session.settingsSection = "SOUNDS"
        panel.windowModel.go("SETTINGS_SOUNDS")
        compose.waitForIdle()
        key(panel, KeyEvent.KEYCODE_DPAD_DOWN)
        key(panel, KeyEvent.KEYCODE_DPAD_UP)
        compose.onNodeWithTag("ui-sounds").assertIsFocused()
        key(panel, KeyEvent.KEYCODE_BUTTON_A)
        compose.onNodeWithTag("ui-sounds").assertIsOff()
    }
    @Test fun controllerChangesThemeInsideAppearance() {
        start()
        app.session.openSettings(panel)
        app.session.settingsSection = "THEME"
        panel.windowModel.go("SETTINGS")
        compose.waitForIdle()
        compose.onNodeWithTag("theme:ayn_rainbow").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        compose.waitForIdle()
        compose.onNodeWithTag("theme:ayn_rainbow").assertIsFocused()
        key(panel, KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithTag("theme:ayn_white").assertIsFocused()
        key(panel, KeyEvent.KEYCODE_BUTTON_A)
        assertEquals("ayn_white", app.session.settingsDraft!!.themeId)
    }
    @Test fun controllerNavigatesAndChangesASettingInsideInterface() {
        start()
        app.session.openSettings(panel)
        app.session.settingsSection = "INTERFACE"
        panel.windowModel.go("SETTINGS")
        compose.waitForIdle()
        repeat(10) { key(panel, KeyEvent.KEYCODE_DPAD_UP) }
        repeat(12) {
            val focused = runCatching { compose.onNodeWithText("Mostra nomi dei file").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Focused] }.getOrDefault(false)
            if (!focused) key(panel, KeyEvent.KEYCODE_DPAD_DOWN)
        }
        compose.onNodeWithText("Mostra nomi dei file").assertIsFocused()
        key(panel, KeyEvent.KEYCODE_BUTTON_A)
        assertTrue(app.session.settingsDraft!!.showMediaNames)
        compose.onNodeWithText("Mostra album musicali in Album").assertDoesNotExist()
    }
    @Test fun controllerCanActivateSettingsHeaderLayoutWithoutLosingFocus() {
        start()
        app.session.openSettings(panel)
        compose.waitForIdle()
        key(panel, KeyEvent.KEYCODE_DPAD_UP)
        fun focused(tag: String) = runCatching { compose.onNodeWithTag(tag).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Focused] }.getOrDefault(false)
        compose.onNodeWithTag("settings-view-toggle").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("settings-view-toggle").assertIsFocused()
        val previous = app.preferences.settingsGrid.value
        key(panel, KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(!previous, app.preferences.settingsGrid.value)
        compose.onNodeWithTag("settings-view-toggle").assertIsFocused()
        key(panel, KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithTag("settings-trash-shortcut").assertIsFocused()
        key(panel, KeyEvent.KEYCODE_BUTTON_A)
        assertEquals("TRASH", panel.windowModel.route.value.tab)
        assertEquals("TRASH", app.session.recoveryTab)
    }
    @Test fun firstAOnlyHighlightsInterfaceAndTheSecondAOpensIt() {
        start(); app.session.openSettings(panel); compose.waitForIdle()
        key(panel, KeyEvent.KEYCODE_BUTTON_A)
        assertEquals("SETTINGS_HUB", panel.windowModel.route.value.tab)
        compose.onNodeWithTag("settings-category:INTERFACE").assertIsFocused()
        key(panel, KeyEvent.KEYCODE_BUTTON_A)
        assertEquals("SETTINGS", panel.windowModel.route.value.tab)
        assertEquals("INTERFACE", app.session.settingsSection)
    }
    @Test fun settingsControllerAcquiresCategoryFocusMovesAndOpensTheReachedPage() {
        start()
        app.session.openSettings(panel)
        compose.waitForIdle()
        key(panel, KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithTag("settings-category:INTERFACE").assertIsFocused()
        key(panel, KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithTag("settings-category:PLAYBACK").assertIsFocused()
        key(panel, KeyEvent.KEYCODE_BUTTON_A)
        assertEquals("PLAYBACK", app.session.settingsSection)
        assertEquals("SETTINGS", panel.windowModel.route.value.tab)
    }
    @Test fun settingsLastRowHandsControllerToPartnerInfoWithoutOpeningAnotherCategory() {
        start()
        val preview = focusPreview()
        app.session.openSettings(preview)
        compose.waitForIdle()
        key(preview, KeyEvent.KEYCODE_BUTTON_A) // First input only arms category focus.
        compose.onNodeWithTag("settings-category:DEVICE").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.waitForIdle()
        key(preview, KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue(app.session.settingsInfoFocused)
        key(preview, KeyEvent.KEYCODE_BUTTON_A)
        assertEquals("INFO", app.session.settingsSection)
        assertEquals("SETTINGS_INFO", preview.windowModel.route.value.tab)
    }
    @Test fun settingsOwnerReceivesControllerEvenFromAnUnpairedEntry() {
        start()
        val preview = focusPreview()
        app.session.openSettings(preview)
        panel.intent.removeExtra("paired_gallery")
        assertSame(preview, app.session.controllerTarget(panel))
        assertSame(preview, app.session.controllerTarget(preview))
    }
    @Test fun controllerTargetFollowsSwapAndNeverRestartsCoveredNavigation() {
        start()
        val preview = focusPreview()
        app.session.swap(); compose.waitForIdle()
        assertSame(preview, app.session.controllerTarget(panel))
        assertSame(preview, app.session.controllerTarget(preview))
        app.session.swap(); compose.waitForIdle()
        app.session.lifecycle(panel, false)
        assertNull(app.session.controllerTarget(preview))
        val commands = mutableListOf<PadAction>()
        panel.moveFocus = { commands.add(it) }
        key(preview, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue(commands.isEmpty())
        assertFalse(panel.isFinishing)
    }

    @Test fun favoriteSelectionSurvivesSwappingTheTwoPanels() {
        start()
        focusPreview()
        runBlocking { app.catalog.dao.favorite("a", true, 100); app.catalog.dao.favorite("b", true, 300) }
        waitFor { app.catalog.snapshot.value.byId["b"]?.favoriteAddedAt == 300L }
        panel.windowModel.go("FAVORITE")
        compose.waitForIdle()
        compose.waitUntil(8000) { shadowOf(Looper.getMainLooper()).idle(); app.session.selectedMediaId == "b" }
        app.session.selectMedia("a")
        compose.waitForIdle()
        assertEquals("a", app.session.selectedMediaId)
        assertTrue(app.session.canSwap())
        app.session.swap()
        compose.waitForIdle()
        assertEquals("Swapping must preserve the current favorite", "a", app.session.selectedMediaId)
    }
    @Test fun pairedFrameHidesNavigationAndSettingsAndDimsOnlyItsControls() {
        start()
        focusPreview()
        panel.windowModel.configureFrame(FrameConfig(scope = "SELECTED", albums = setOf("album")))
        panel.windowModel.startFrame()
        panel.windowModel.frameConfiguring(false)
        panel.windowModel.framePlaying(true)
        compose.waitForIdle()
        compose.onNodeWithTag("photo-frame").assertExists()
        compose.onNodeWithTag("frame-setup").assertExists()
        compose.onAllNodesWithContentDescription("Impostazioni").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Album", useUnmergedTree = true).assertCountEquals(0)
        compose.onNode(hasTestTag("frame-dim-toggle") and hasAnyAncestor(hasTestTag("gallery-header"))).performClick()
        compose.onNodeWithTag("frame-dim-navigation").assertExists()
        assertTrue(panel.windowModel.framePlaying.value)
        compose.onNodeWithTag("frame-dim-navigation").performClick()
        assertFalse(panel.windowModel.frameDimmed.value)
        compose.mainClock.advanceTimeBy(10_050)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10_050))
        compose.waitForIdle()
        assertTrue(panel.windowModel.frameDimmed.value)
        compose.onNodeWithTag("frame-dim-navigation").assertExists()
        panel.windowModel.back()
        compose.waitForIdle()
        assertFalse(panel.windowModel.frameDimmed.value)
        compose.onNodeWithTag("photo-frame").assertDoesNotExist()
        compose.onNodeWithTag("frame-dim-navigation").assertDoesNotExist()
    }
    @Test fun frameExitKeepsTheOutgoingPhotoUntilTheFadeCompletes() {
        start()
        focusPreview()
        runBlocking { app.preferences.update { it.copy(reducedMotion = false) } }
        panel.windowModel.configureFrame(FrameConfig(scope = "SELECTED", albums = setOf("album")))
        panel.windowModel.startFrame()
        panel.windowModel.frameConfiguring(false)
        compose.waitForIdle()
        compose.onNodeWithTag("photo-frame").assertExists()
        compose.mainClock.autoAdvance = false
        panel.windowModel.back()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("photo-frame").assertExists()
        compose.mainClock.advanceTimeBy(500)
        compose.mainClock.autoAdvance = true
        compose.waitUntil(8000) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithTag("photo-frame").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("frame-setup").assertDoesNotExist()
    }
    private fun videoPair(music: Boolean = false): OtherPanelActivity {
        start(music = music)
        app.preferences.videoFolderView(false)
        val preview = focusPreview()
        runBlocking { app.catalog.dao.saveMedia(listOf("a", "b").map {
            app.catalog.snapshot.value.byId.getValue(it).copy(kind = "VIDEO", mime = "video/mp4")
        }) }
        waitFor { app.catalog.snapshot.value.byId["b"]?.kind == "VIDEO" }
        // A PNG is an already-ended one-frame stream, not a playing video.
        val videoBytes = File("src/test/resources/media/video-segments.mp4").readBytes()
        listOf("a", "b").forEach { File(app.cacheDir, "preview-$it.png").writeBytes(videoBytes) }
        panel.windowModel.go("VIDEO")
        compose.waitForIdle()
        compose.waitUntil(8000) { shadowOf(Looper.getMainLooper()).idle(); app.session.selectedMediaId == "a" }
        waitFor { panel.windowModel.listing.value.let { it.initialized && it.route.tab == "VIDEO" && it.items.size == 2 && it.sort == panel.windowModel.sort.value } }
        compose.waitForIdle()
        app.video.play(app.catalog.snapshot.value.byId.getValue("a"), preview.slot, false)
        app.video.dimNavigation = true // Existing tests explicitly exercise manual shading.
        compose.waitForIdle()
        return preview
    }
    @Test fun videoStickChangesOnlyPlayerVolumeAndKeepsItAcrossVideos() {
        val preview = videoPair()
        val audio = app.getSystemService(android.media.AudioManager::class.java)
        val systemVolume = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        app.video.setVolume(.5f)
        assertTrue(app.video.leftStick(0f, -1f, 1000))
        assertEquals(.55f, app.video.player!!.volume, .001f)
        assertEquals(systemVolume, audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC))
        app.video.play(app.catalog.snapshot.value.byId.getValue("b"), preview.slot, false)
        assertEquals(.55f, app.video.player!!.volume, .001f)
    }
    @Test fun manualMoonWorksWithoutPlaybackAndTouchOrControllerWakesIt() {
        start(); val initial = panel.windowModel.route.value
        assertNull(app.video.player)
        panel.performTouchAction(TouchAction.DIM); compose.waitForIdle()
        assertTrue(panel.manuallyDimmed)
        compose.onNodeWithTag("video-dim-navigation").assertIsDisplayed().performClick()
        assertFalse(panel.manuallyDimmed); assertEquals(initial, panel.windowModel.route.value)
        panel.performTouchAction(TouchAction.DIM); compose.waitForIdle()
        key(panel, KeyEvent.KEYCODE_BUTTON_R1)
        assertFalse(panel.manuallyDimmed)
    }
    @Test fun statusBarIsHiddenUnlessEnabledForThePrimaryDisplay() {
        start(); val other = focusPreview()
        fun hidden(activity: PanelActivity) = activity.window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN != 0
        panel.immersivePreview(false); other.immersivePreview(false)
        assertTrue(hidden(panel)); assertTrue(hidden(other))
        runBlocking { app.preferences.update { it.copy(showUpperStatusBar = true) } }
        compose.waitForIdle()
        panel.immersivePreview(false); other.immersivePreview(false)
        val primary = if (app.session.current(panel)?.id == 0) panel else other
        val secondary = if (primary === panel) other else panel
        assertFalse(hidden(primary)); assertTrue(hidden(secondary))
    }
    @Test fun albumPinPlaysOneCueForEachOfItsFourDigitsThenTheResult() {
        start()
        runBlocking { app.catalog.protection.configure("1234") }
        val pool = shadowOf(ReflectionHelpers.getField<android.media.SoundPool>(app.sounds, "pool"))
        val sounds = listOf(R.raw.ui_pin_digit_1, R.raw.ui_pin_digit_2, R.raw.ui_pin_digit_3, R.raw.ui_pin_digit_4)
        (sounds + R.raw.ui_unlock).forEach { pool.notifyResourceLoaded(it, true) }
        var accepted = false
        panel.setContent { GalleryDesign(GalleryPreferences(reducedMotion = true)) {
            AlbumPinDialog(app.catalog.protection, "UNLOCK", dismiss = {}, accepted = { accepted = true })
        } }
        for ((index, digit) in listOf("1", "2", "3", "4").withIndex()) {
            compose.onNodeWithTag("pin:$digit").performClick()
            assertTrue("Digit $digit has its own cue", pool.wasResourcePlayed(sounds[index]))
        }
        compose.mainClock.advanceTimeBy(300)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        waitFor { accepted }
        assertTrue(pool.wasResourcePlayed(R.raw.ui_unlock))
    }
    @Test fun homeReturnKeepsSettingsDraftAndTheVideoInPip() {
        val preview = videoPair()
        shadowOf(app.packageManager).setSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE, true)
        val running = app.video.player!!
        app.video.galleryBackgrounded()
        val native = Robolectric.buildActivity(VideoPipActivity::class.java).create().start().resume()
        app.session.openSettings(preview)
        val owner = app.session.settingsActivity()!!
        app.session.settingsSection = "PLAYBACK"; owner.windowModel.go("SETTINGS")
        val draft = app.session.settingsDraft!!.copy(alwaysPrevious = true)
        app.session.settingsDraft = draft
        compose.waitForIdle()
        app.video.galleryReturned(panel)
        assertTrue(app.video.floating); assertSame(running, app.video.player)
        assertEquals(owner.slot, app.session.settingsOwner)
        assertEquals("SETTINGS", owner.windowModel.route.value.tab)
        assertEquals("PLAYBACK", app.session.settingsSection)
        assertSame(draft, app.session.settingsDraft)
        val launch = Robolectric.buildActivity(LauncherActivity::class.java, Intent(Intent.ACTION_MAIN)).create()
        assertTrue(launch.get().isFinishing)
        assertSame(draft, app.session.settingsDraft)
        assertTrue(app.video.floating); assertTrue(running.playWhenReady)
        launch.destroy(); native.pause().stop().destroy()
    }
    @Test fun replacementCanBeDisabledAndChangingSelectionCancelsIt() {
        val preview = videoPair()
        val running = app.video.player!!
        app.session.selectMedia("b")
        app.video.requestPlay(app.catalog.snapshot.value.byId.getValue("b"), preview.slot, false)
        compose.waitForIdle()
        assertEquals("b", app.video.replacementRequest!!.id)
        app.session.selectMedia("a")
        compose.waitForIdle()
        assertNull(app.video.replacementRequest); assertTrue(running.playWhenReady)
        app.session.selectMedia("b")
        app.video.requestPlay(app.catalog.snapshot.value.byId.getValue("b"), preview.slot, false)
        compose.waitForIdle()
        compose.onNodeWithTag("video-replace-dont-ask").performClick()
        compose.onNodeWithTag("video-replace-confirm").performClick()
        waitFor { app.video.mediaId == "b" }
        assertFalse(app.preferences.state.value.confirmVideoReplacement)
        assertFalse(PreferenceStore(app).state.value.confirmVideoReplacement)
        app.session.selectMedia("a")
        app.video.requestPlay(app.catalog.snapshot.value.byId.getValue("a"), preview.slot, false)
        compose.waitForIdle()
        assertNull(app.video.replacementRequest); assertEquals("a", app.video.mediaId)
    }
    @Test fun manualPipRemainsFloatingOnVideoReturnAndOnlyItsOwnSelectionShowsThePlaceholder() {
        val preview = videoPair()
        shadowOf(app.packageManager).setSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE, true)
        val running = app.video.player!!
        app.video.openPip(preview)
        val native = Robolectric.buildActivity(VideoPipActivity::class.java).create().start().resume()
        panel.windowModel.go("PHOTO"); panel.windowModel.go("VIDEO")
        app.video.returnFromTools(force = true); compose.waitForIdle()
        val translucent = android.util.TypedValue()
        native.get().theme.resolveAttribute(android.R.attr.windowIsTranslucent, translucent, true)
        assertEquals(0, translucent.data)
        val content = native.get().findViewById<android.view.ViewGroup>(android.R.id.content)
        assertEquals(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT, (content.getChildAt(0) as androidx.media3.ui.PlayerView).resizeMode)
        assertTrue(app.video.manualPip); assertTrue(app.video.floating)
        assertSame(running, app.video.player)
        app.session.selectMedia("b"); compose.waitForIdle()
        compose.onNodeWithTag("video-pip-placeholder").assertDoesNotExist()
        app.session.selectMedia("a"); compose.waitForIdle()
        compose.onNodeWithTag("video-pip-placeholder").assertExists()
        app.video.dismissPip(native.get())
        native.pause().stop().destroy()
    }

    @Test fun automaticPipStillReturnsWhenTheVideoSectionIsReopened() {
        val preview = videoPair()
        shadowOf(app.packageManager).setSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE, true)
        app.video.suspendForTools()
        assertFalse(app.video.manualPip)
        val native = Robolectric.buildActivity(VideoPipActivity::class.java).create().start().resume()
        assertTrue(app.video.floating)
        app.video.returnFromTools(force = true)
        app.video.pipClosed(native.get()); app.video.resumePipViewer(preview)
        compose.waitForIdle()
        assertFalse(app.video.floating)
        native.pause().stop().destroy()
    }

    @Test fun homePipWaitsForTheOriginalPreviewWhenLibraryReturnsFirst() {
        val preview = videoPair()
        shadowOf(app.packageManager).setSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE, true)
        val running = app.video.player!!
        app.video.galleryBackgrounded()
        assertTrue(app.video.returningFromHome)
        val native = Robolectric.buildActivity(VideoPipActivity::class.java).create().start().resume()
        app.session.lifecycle(preview, false); preview.isStartedFlow.value = false
        app.video.pipClosed(native.get()) // Detached native window; the companion has not restarted yet.
        app.video.galleryReturned(panel) // The browsing panel resumes first.
        assertTrue(app.video.floating)
        app.session.lifecycle(preview, true); preview.isStartedFlow.value = true
        app.video.resumePipViewer(preview)
        compose.waitForIdle()
        assertSame(running, app.video.player)
        assertFalse(app.video.floating)
        assertEquals(preview.slot, app.video.owner)
        native.pause().stop().destroy()
    }
    @Test fun closingTheFloatingWindowKeepsItsPausedTimelineAndCurrentSettingsPage() {
        val preview = videoPair()
        shadowOf(app.packageManager).setSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE, true)
        val running = app.video.player!!
        running.pause(); running.seekTo(250)
        app.video.openPip(preview)
        val native = Robolectric.buildActivity(VideoPipActivity::class.java).create().start().resume()
        app.session.openSettings(preview); compose.waitForIdle()
        app.video.dismissPip(native.get()); compose.waitForIdle()
        assertSame(running, app.video.player)
        assertFalse(app.video.floating); assertFalse(running.playWhenReady)
        assertEquals(250L, running.currentPosition)
        assertNotNull(app.session.settingsOwner)
        assertEquals("SETTINGS_HUB", app.session.settingsModel()!!.route.value.tab)
        native.pause().stop().destroy()
    }
    @Test fun anotherVideoIsSelectedByTheFirstTapAndStartedByTheSecond() {
        videoPair()
        app.video.stop(app.video.owner); compose.waitForIdle()
        compose.onNodeWithTag("media:b").performClick(); compose.waitForIdle()
        assertEquals("b", app.session.selectedMediaId); assertNull(app.video.player)
        compose.onNodeWithTag("media:b").performClick(); compose.waitForIdle()
        assertNotNull(app.video.player); assertEquals("b", app.video.mediaId)
    }
    @Test fun floatingVideoSurvivesGallerySettingsAndPanelStopsAndRestoresTheSamePlayer() {
        val preview = videoPair()
        shadowOf(app.packageManager).setSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE, true)
        val running = app.video.player!!
        // The real fixture is six seconds long; seeking past its end races STATE_ENDED.
        running.seekTo(1500)
        compose.waitForIdle()
        assertTrue(running.playWhenReady)
        app.video.openPip(preview)
        assertTrue(app.video.pipOpening)
        // The gallery retains its surface until the native window has attached it.
        app.video.stop(preview.slot)
        assertSame(running, app.video.player)
        val native = org.robolectric.Robolectric.buildActivity(VideoPipActivity::class.java).create().start().resume()
        compose.waitForIdle()
        assertTrue(app.video.floating)
        assertEquals(VideoPlayback.PIP_OWNER, app.video.owner)
        assertSame(running, app.video.player)
        compose.onNodeWithTag("video-pip-placeholder").assertExists()
        app.video.stop(preview.slot, "a", "viewer")
        app.video.stop(panel.slot)
        app.session.openSettings(panel)
        compose.waitForIdle()
        assertSame(running, app.video.player)
        assertTrue(running.playWhenReady)
        app.session.closeSettings()
        panel.windowModel.go("PHOTO")
        app.session.selectMedia("b")
        compose.waitForIdle()
        assertTrue(app.video.floating)
        assertSame(running, app.video.player)
        running.pause()
        val position = running.currentPosition
        app.video.beginPipReturn()
        app.video.stop(preview.slot)
        assertSame(running, app.video.player)
        assertTrue(app.video.floating)
        app.session.gridPreviewDirection = androidx.compose.ui.unit.IntOffset(1, 0)
        app.video.pipClosed(native.get())
        app.video.resumePipViewer(preview)
        compose.waitForIdle()
        assertEquals("PHOTO", panel.windowModel.route.value.tab)
        assertEquals("b", app.session.selectedMediaId)
        assertEquals(androidx.compose.ui.unit.IntOffset.Zero, app.session.gridPreviewDirection)
        assertFalse(app.video.floating)
        compose.onNodeWithTag("video-pip-placeholder").assertDoesNotExist()
        assertEquals(preview.slot, app.video.owner)
        assertSame(running, app.video.player)
        assertTrue("The paused timeline must not restart on return", kotlin.math.abs(position - running.currentPosition) < 100)
        assertFalse(running.playWhenReady)
        app.video.stop(VideoPlayback.PIP_OWNER, "a", "pip")
        assertSame(running, app.video.player)
        native.pause().stop().destroy()
    }
    @Test fun explicitPipReturnClosesAnAlreadyResumedTemporaryWindow() {
        val preview = videoPair()
        shadowOf(app.packageManager).setSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE, true)
        val running = app.video.player!!
        app.video.openPip(preview)
        val native = Robolectric.buildActivity(VideoPipActivity::class.java).create().start().resume()
        compose.waitForIdle()
        app.video.returnToGallery(preview)
        assertTrue(native.get().isFinishing)
        app.video.pipClosed(native.get())
        app.video.resumePipViewer(preview)
        compose.waitForIdle()
        assertSame(running, app.video.player)
        repeat(15) {
            val tabs = visibleNavbarTabs(app.preferences.state.value)
            val index = tabs.indexOfFirst { it.key == panel.windowModel.route.value.tab }
            val expected = tabs[(index + 1) % tabs.size].key
            key(preview, KeyEvent.KEYCODE_BUTTON_R1)
            assertEquals(expected, panel.windowModel.route.value.tab)
        }
        native.pause().stop().destroy()
    }
    @Test fun pipResizeKeepsItsFullscreenReturnBoundsAndHomeReturnDoesNotSlideTheTask() {
        val preview = videoPair()
        shadowOf(app.packageManager).setSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE, true)
        app.video.openPip(preview)
        val launch = shadowOf(preview).nextStartedActivity
        assertTrue(launch.flags and Intent.FLAG_ACTIVITY_NO_ANIMATION != 0)
        val native = org.robolectric.Robolectric.buildActivity(VideoPipActivity::class.java, launch).create().start().resume()
        val activity = native.get()
        val view = ReflectionHelpers.getField<androidx.media3.ui.PlayerView>(activity, "view")
        val overlay = android.util.TypedValue()
        assertTrue(activity.theme.resolveAttribute(android.R.attr.windowIsTranslucent, overlay, true))
        assertEquals(0, overlay.data)
        fun bounds(): android.graphics.Rect = ReflectionHelpers.callInstanceMethod<android.app.PictureInPictureParams>(activity, "parameters").sourceRectHint!!
        ReflectionHelpers.setField(activity, "entered", false)
        view.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 1920, 1080)
        val fullscreen = android.graphics.Rect(bounds())
        assertTrue(fullscreen.width() > 480)
        ReflectionHelpers.setField(activity, "entered", true)
        view.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 480, 360)
        assertEquals(fullscreen, bounds())
        app.session.lifecycle(panel, false)
        app.session.lifecycle(preview, false)
        ReflectionHelpers.setField(shadowOf(activity), "isInPictureInPictureMode", false)
        native.pause().resume()
        val restore = shadowOf(activity).nextStartedActivity
        assertNotNull("Returning from Home must restore gallery panels", restore)
        assertTrue(restore.flags and Intent.FLAG_ACTIVITY_NO_ANIMATION != 0)
        assertTrue(restore.component!!.className in setOf(LauncherActivity::class.java.name, MainPanelActivity::class.java.name, OtherPanelActivity::class.java.name))
        native.pause().stop().destroy()
    }
    @Test fun liveSubtitleTrackAvailabilityUpdatesTheNativeBottomButton() {
        val preview = videoPair()
        fun findView(view: View): GalleryVideoView? {
            if (view is GalleryVideoView) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) findView(view.getChildAt(i))?.let { return it }
            return null
        }
        val tracks = ReflectionHelpers.getField<androidx.compose.runtime.MutableState<Boolean>>(app.video, "subtitleTracksAvailable\$delegate")
        compose.runOnIdle { tracks.value = true }
        compose.waitForIdle()
        val native = findView(preview.window.decorView)!!
        val gear = native.findViewById<View>(androidx.media3.ui.R.id.exo_settings)
        val row = gear.parent as android.widget.LinearLayout
        val cc = (0 until row.childCount).map(row::getChildAt).single { it.contentDescription == localized(R.string.video_subtitles, "Sottotitoli") }
        assertEquals(View.VISIBLE, cc.visibility)
        compose.onNodeWithTag("preview-subtitles").assertDoesNotExist()
        compose.onNodeWithTag("video-exit-playback").assertDoesNotExist()
        compose.runOnIdle { tracks.value = false }
        compose.waitForIdle()
        assertEquals(View.GONE, cc.visibility)
    }
    @Test fun settingsPreserveThePausedVideoAndItsBrowsingContext() {
        val preview = videoPair()
        val running = app.video.player!!
        val id = app.video.mediaId
        val context = app.video.context
        app.session.openSettings(panel)
        compose.waitForIdle()
        assertSame(running, app.video.player)
        assertFalse(running.playWhenReady)
        assertFalse(app.video.dimNavigation)
        app.session.closeSettings()
        compose.waitForIdle()
        assertSame(running, app.video.player)
        assertEquals(id, app.video.mediaId)
        assertEquals(context, app.video.context)
        assertEquals(preview.slot, app.video.owner)
        compose.onNodeWithTag("media-surface:viewer").assertExists()
    }
    @Test fun musicAndFtpTemporarilyPauseTheVideoWithoutReleasingItsPlayer() {
        videoPair()
        runBlocking { app.preferences.update { it.copy(videoKeepPlaying = false) } }
        val running = app.video.player!!
        val id = app.video.mediaId
        running.seekTo(12000)
        val position = running.currentPosition
        panel.windowModel.go("MUSIC"); compose.waitForIdle()
        assertSame(running, app.video.player)
        assertFalse(running.playWhenReady)
        assertTrue(app.video.suspendedForTools)
        compose.onNodeWithTag("media:song2").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.waitForIdle()
        assertSame(running, app.video.player)
        panel.windowModel.go("PHOTO"); compose.waitForIdle()
        assertSame(running, app.video.player); assertEquals(id, app.video.mediaId)
        assertTrue("Pause may settle within a frame, but must not restart or seek", kotlin.math.abs(position - running.currentPosition) <= 40)
        panel.windowModel.go("FTP"); compose.waitForIdle()
        assertSame(running, app.video.player)
        panel.windowModel.go("VIDEO"); compose.waitForIdle()
        assertSame(running, app.video.player)
        assertTrue("Returning must preserve the paused frame", kotlin.math.abs(position - running.currentPosition) <= 40)
    }
    @Test fun videoFlowReplacesButterflyAndKeepPlayingRemainsControlledBySettings() {
        videoPair()
        runBlocking { app.preferences.update { it.copy(videoKeepPlaying = true) } }
        compose.onAllNodesWithTag("video-automatic-window").assertCountEquals(0)
        app.video.cycleFlow() // Playback mode is now in the native bottom settings menu.
        assertEquals(1, app.video.flowMode)
        assertTrue(app.video.keepsPlaying)
        runBlocking { app.preferences.update { it.copy(videoKeepPlaying = false) } }
        assertFalse(app.video.keepsPlaying)
    }
    @Test fun keepPlayingAutomaticallyUsesPipAndReturnsWithoutChangingTheBrowsingTab() {
        val preview = videoPair()
        shadowOf(app.packageManager).setSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE, true)
        runBlocking { app.preferences.update { it.copy(videoKeepPlaying = true) } }
        val running = app.video.player!!
        panel.windowModel.go("MUSIC"); compose.waitForIdle()
        assertTrue(app.video.pipOpening); assertTrue(running.playWhenReady)
        val native = Robolectric.buildActivity(VideoPipActivity::class.java).create().start().resume()
        compose.waitForIdle()
        assertTrue(app.video.floating); assertSame(running, app.video.player)
        panel.windowModel.go("PHOTO"); compose.waitForIdle()
        app.video.beginPipReturn(); app.video.pipClosed(native.get()); app.video.resumePipViewer(preview)
        compose.waitForIdle()
        assertEquals("PHOTO", panel.windowModel.route.value.tab)
        assertSame(running, app.video.player); assertFalse(app.video.floating)
        assertTrue(running.playWhenReady)
        native.pause().stop().destroy()
    }
    @Test fun favoriteToTvUsesTheCurrentNavbarAfterTvIsEnabled() {
        start()
        panel.windowModel.go("FAVORITE"); compose.waitForIdle()
        runBlocking { app.preferences.update { it.copy(tvEnabled = true, tvInNavbar = true) } }
        compose.waitForIdle()
        panel.padAction!!.invoke(PadAction.TAB_PREVIOUS); compose.waitForIdle()
        assertEquals("TV", panel.windowModel.route.value.tab)
        panel.windowModel.go("MUSIC"); compose.waitForIdle()
        panel.padAction!!.invoke(PadAction.TAB_NEXT); compose.waitForIdle()
        assertEquals("TV", panel.windowModel.route.value.tab)
        panel.windowModel.go("FAVORITE"); compose.waitForIdle()
        panel.windowModel.go("TV"); compose.waitForIdle()
        assertEquals("TV", panel.windowModel.route.value.tab)
        runBlocking { app.preferences.update { it.copy(tvInNavbar = false) } }
        compose.waitForIdle()
        panel.padAction!!.invoke(PadAction.TAB_NEXT); compose.waitForIdle()
        assertEquals("MUSIC", panel.windowModel.route.value.tab)
    }
    @Test fun favoriteToTvKeepsTheLibraryRouteWhileVideoEntersPip() {
        val preview = videoPair()
        runBlocking { app.preferences.update { it.copy(tvEnabled = true, tvInNavbar = true, tvInMediaFavorites = true, videoKeepPlaying = true) } }
        shadowOf(app.packageManager).setSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE, true)
        panel.windowModel.go("FAVORITE"); compose.waitForIdle()
        key(preview, KeyEvent.KEYCODE_BUTTON_L1)
        compose.waitForIdle()
        assertEquals("TV", panel.windowModel.route.value.tab)
        assertTrue(app.video.pipOpening)
        val native = Robolectric.buildActivity(VideoPipActivity::class.java).create().start().resume()
        compose.waitForIdle()
        assertEquals("TV", panel.windowModel.route.value.tab)
        native.pause().stop().destroy()
    }
    @Test fun currentVideoCoverUsesTheExactPausedPositionAndNeverAnotherVideo() {
        videoPair()
        val current = app.catalog.snapshot.value.byId.getValue("a")
        app.video.player!!.pause()
        app.video.player!!.seekTo(1234)
        app.session.mediaOptions = current; compose.waitForIdle()
        compose.onNodeWithTag("preview-actions").performScrollToNode(hasTestTag("video-cover-current-frame"))
        compose.onNodeWithTag("video-cover-current-frame").performClick()
        waitFor { app.catalog.videoCoverFrame("a") == 1234L && app.session.mediaOptions == null }
        assertNull(app.session.mediaOptions)
        app.session.mediaOptions = app.catalog.snapshot.value.byId.getValue("b"); compose.waitForIdle()
        compose.onNodeWithTag("video-cover-current-frame").assertDoesNotExist()
        app.session.mediaOptions = current; compose.waitForIdle()
        compose.onNodeWithTag("video-cover-current-frame").assertExists()
        app.session.mediaOptions = null
        app.video.stop(app.video.owner)
        app.session.mediaOptions = current; compose.waitForIdle()
        compose.onNodeWithTag("video-cover-current-frame").assertDoesNotExist()
    }
    @Test fun favoriteSongFocusDoesNotReplaceThePlayingVideo() {
        videoPair()
        val running = app.video.player!!
        runBlocking { app.catalog.dao.saveMedia(app.catalog.snapshot.value.media.filter { it.kind == "AUDIO" }.map { it.copy(favorite = true) }) }
        waitFor { app.catalog.snapshot.value.byId["song2"]?.favorite == true }
        panel.windowModel.go("FAVORITE")
        waitFor { compose.onAllNodesWithTag("media:song2").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("media:song2").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.waitForIdle()
        assertEquals("song2", app.session.selectedMediaId)
        assertSame(running, app.video.player)
        assertEquals("a", app.video.mediaId)
        compose.onNodeWithTag("media-surface:viewer").assertExists()
        compose.onNodeWithTag("preview-video-name").assertDoesNotExist() // Hidden with the native controls until tapped.
    }
    @Test fun albumPinBackErasesOneDigitBeforeDismissing() {
        start(); val preview = focusPreview()
        app.session.albumUnlockRequest = "album"; compose.waitForIdle()
        compose.onNodeWithTag("pin:1").performClick(); compose.waitForIdle()
        key(preview, KeyEvent.KEYCODE_BUTTON_B)
        compose.onNodeWithTag("album-pin-dialog").assertExists()
        key(preview, KeyEvent.KEYCODE_BUTTON_B)
        compose.onNodeWithTag("album-pin-dialog").assertDoesNotExist()
    }
    @Test fun changingTabsClosesContextOptionsAndFtpIgnoresGlobalSettingsShortcut() {
        start(); val preview = focusPreview()
        panel.windowModel.go("PHOTO"); compose.waitForIdle()
        app.session.mediaOptions = app.catalog.snapshot.value.byId.getValue("a"); compose.waitForIdle()
        key(preview, KeyEvent.KEYCODE_BUTTON_R1)
        assertNull(app.session.mediaOptions)
        assertEquals("ALBUM", panel.windowModel.route.value.tab)
        panel.windowModel.go("FTP"); compose.waitForIdle()
        key(preview, KeyEvent.KEYCODE_BUTTON_X)
        assertNull(app.session.settingsOwner)
        assertNull(app.session.mediaOptions)
        assertEquals("FTP", panel.windowModel.route.value.tab)
    }
    @Test fun pinAndUnpinOnTheSamePhotoKeepZoomAndPosition() {
        start(); focusPreview()
        runBlocking { app.preferences.update { it.copy(previewPinEnabled = true, viewerZoom = true) } }
        panel.windowModel.go("PHOTO"); compose.waitForIdle()
        compose.onNodeWithTag("media:a").performSemanticsAction(SemanticsActions.RequestFocus) { it() }; compose.waitForIdle()
        compose.onNodeWithText("+").performClick(); compose.waitForIdle()
        fun transform() = compose.onNodeWithTag("media-surface:viewer").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription]
        val before = transform()
        compose.onNodeWithTag("preview-pin").performClick(); compose.waitForIdle()
        assertEquals(before, transform())
        compose.onNodeWithTag("preview-pin").performClick(); compose.waitForIdle()
        assertEquals(before, transform())
    }
    @Test fun videoFolderDimensionsAreIndependentOfAlbumsAndPersist() {
        start()
        app.preferences.albumSize(setOf("album"), 4)
        panel.windowModel.go("VIDEO"); panel.windowModel.videoFolders(true)
        panel.windowModel.albumColumns(6)
        app.preferences.videoAlbumSize(setOf("album"), 2)
        assertEquals(4, app.preferences.albumSizes.value["album"])
        assertEquals(2, app.preferences.videoAlbumSizes.value["album"])
        assertEquals(6, app.preferences.videoAlbumColumns.value)
        assertEquals(3, app.preferences.albumColumns.value)
        val restored = PreferenceStore(app)
        assertEquals(6, restored.videoAlbumColumns.value)
        assertEquals(2, restored.videoAlbumSizes.value["album"])
    }
    @Test fun pinnedImageConfirmationAcceptsAAndCancelsWithBFromThePreviewController() {
        start(); val preview = focusPreview()
        runBlocking { app.preferences.update { it.copy(previewPinEnabled = true) } }
        app.session.togglePreviewPin("a")
        panel.windowModel.go("PHOTO"); compose.waitForIdle()
        compose.onNodeWithTag("media:b").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.waitForIdle()
        key(preview, KeyEvent.KEYCODE_BUTTON_A)
        compose.onNodeWithTag("preview-unpin-confirm").assertIsDisplayed()
        key(preview, KeyEvent.KEYCODE_BUTTON_B)
        assertEquals("a", app.session.pinnedPreviewId); assertNull(app.session.pinActivationRequestId)
        assertEquals("The library selection must survive cancelling the pin dialog", "b", app.session.selectedMediaId)
        compose.onNodeWithTag("media:b").assertIsFocused()
        key(preview, KeyEvent.KEYCODE_BUTTON_A)
        assertEquals("A must reopen confirmation for the focused media", "b", app.session.pinActivationRequestId)
        compose.onNodeWithTag("preview-unpin-confirm").assertIsDisplayed()
        key(preview, KeyEvent.KEYCODE_BUTTON_A)
        compose.waitForIdle()
        assertNull(app.session.pinnedPreviewId); assertEquals("b", app.session.selectedMediaId)
    }
    @Test fun previewFollowsFocusUnlessPinnedAndReturnsFromMusicToThePinnedImage() {
        start(); val previewPanel = focusPreview()
        runBlocking { app.preferences.update { it.copy(previewPinEnabled = true, imagePreviewName = true) } }
        fun focus(id: String) {
            compose.onNodeWithTag("media:$id").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            compose.waitForIdle()
        }
        fun preview(id: String) {
            val name = app.catalog.snapshot.value.byId.getValue(id).name
            compose.onAllNodes(hasText(name) and hasAnyAncestor(hasTestTag("fullscreen-preview"))).assertCountEquals(1)
        }
        focus("a"); preview("a")
        focus("b"); preview("b")
        app.session.togglePreviewPin("b"); compose.waitForIdle()
        focus("a"); preview("b")
        assertEquals("a", app.session.selectedMediaId)
        key(previewPanel, KeyEvent.KEYCODE_BUTTON_A)
        compose.onNodeWithTag("preview-unpin-confirm").assertIsDisplayed()
        panel.windowModel.go("MUSIC"); compose.waitForIdle()
        assertNull(app.session.pinActivationRequestId)
        assertEquals("b", app.session.pinnedPreviewId)
        compose.onNodeWithTag("preview-pin").assertDoesNotExist()
        panel.windowModel.go("PHOTO"); compose.waitForIdle(); preview("b")
        compose.onNodeWithTag("preview-pin").performTouchInput { click(center) }
        compose.waitForIdle(); preview("a")
        assertEquals("Removing a pin must preserve current browsing focus", "a", app.session.selectedMediaId)
    }
    @Test fun navigationMoonHidesDuringSearchAndIdleTimerRespectsInteraction() {
        videoPair()
        compose.runOnIdle { app.video.wakeNavigation(); app.video.playing.value = true }
        compose.onNodeWithTag("navigation-video-moon").assertIsDisplayed()
        compose.onNodeWithContentDescription("Cerca e filtra").performClick()
        compose.onNodeWithTag("navigation-video-moon").assertDoesNotExist()
        compose.onNodeWithContentDescription("Chiudi ricerca").performClick()
        compose.onNodeWithTag("navigation-video-moon").assertIsDisplayed().performClick()
        compose.onNodeWithTag("video-dim-navigation").assertIsDisplayed().performClick()
        compose.onNodeWithTag("navigation-video-moon").assertIsDisplayed()
        runBlocking { app.preferences.update { it.copy(videoDimSeconds = 5) } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(4000)
        assertFalse(app.video.dimNavigation)
        compose.runOnIdle { app.video.wakeNavigation() }
        compose.mainClock.advanceTimeBy(48)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(4000)
        assertFalse(app.video.dimNavigation)
        compose.mainClock.advanceTimeBy(1100)
        compose.waitForIdle()
        assertTrue(app.video.dimNavigation)
        compose.mainClock.autoAdvance = true
    }
    @Test fun videoStickChangesVolumeWithoutChangingTheSelectedVideoAndLeavesDpadAxesAvailable() {
        videoPair()
        val manager = app.getSystemService(android.media.AudioManager::class.java)
        manager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 3, 0)
        val selected = app.session.selectedMediaId
        assertTrue(panel.videoStickAction!!.invoke(0f, -1f, 10000))
        assertEquals(3, manager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC))
        assertTrue(panel.videoStickAction!!.invoke(0f, 1f, 10250))
        assertEquals(3, manager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC))
        assertEquals(selected, app.session.selectedMediaId)
        val event = axisEvent(mapOf(MotionEvent.AXIS_X to 1f, MotionEvent.AXIS_Y to -1f, MotionEvent.AXIS_HAT_X to -1f))
        try {
            val axes = padAxes(event, leftStickHandled = true)
            assertEquals(-1f, axes[PadButton.RIGHT] ?: 0f)
            assertEquals(0f, axes[PadButton.UP] ?: 0f, 0f)
            assertEquals(1f, axes[PadButton.LEFT] ?: 0f)
        } finally { event.recycle() }
    }
    @Test fun browsingAnotherVideoKeepsThePlayerAndAnExplicitTapReplacesIt() {
        videoPair()
        val pool = shadowOf(ReflectionHelpers.getField<android.media.SoundPool>(app.sounds, "pool"))
        pool.notifyResourceLoaded(R.raw.ui_video_replace, true)
        val running = app.video.player!!
        compose.onNodeWithTag("video-dim-navigation").performClick()
        compose.onNodeWithTag("media:b").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.waitForIdle()
        assertEquals("b", app.session.selectedMediaId)
        assertEquals("a", app.video.mediaId)
        assertSame(running, app.video.player)
        compose.onNodeWithTag("media-surface:viewer").assertExists()
        compose.onNodeWithTag("preview-video-name").assertDoesNotExist() // Hidden with the native controls until tapped.
        compose.onNodeWithTag("media:b").performClick()
        compose.onNodeWithTag("video-replace-confirm").assertExists()
        assertTrue(pool.wasResourcePlayed(R.raw.ui_video_replace))
        assertSame(running, app.video.player); assertFalse(running.playWhenReady)
        key(app.session.previewActivity()!!, KeyEvent.KEYCODE_BUTTON_B)
        assertNull(app.video.replacementRequest); assertSame(running, app.video.player)
        assertTrue(running.playWhenReady)
        compose.onNodeWithTag("media:b").performClick()
        compose.onNodeWithTag("video-replace-confirm").performClick()
        waitFor { app.video.mediaId == "b" }
        assertNotSame(running, app.video.player)
    }
    @Test fun selectingAnotherMediaAbandonsThePendingVideoResume() {
        val preview = videoPair()
        app.video.stop(app.video.owner)
        val item = app.catalog.snapshot.value.byId.getValue("a").copy(videoPosition = 65000, videoPositionAt = System.currentTimeMillis())
        app.video.requestPlay(item, preview.slot, false)
        compose.waitForIdle()
        compose.onNodeWithTag("video-resume").assertExists()
        compose.onNodeWithTag("media:b").performClick()
        compose.waitForIdle()
        assertEquals("b", app.session.selectedMediaId)
        assertNull(app.video.resumeRequest)
        assertNull(app.video.player)
        compose.onNodeWithTag("video-resume").assertDoesNotExist()
        compose.onNodeWithTag("video-play").assertExists()
        app.video.confirmResume(true)
        assertNull(app.video.player)
    }
    @Test fun rootAlbumBackDismissesTheOpenMediaAndShowsItsCoverAgain() {
        start()
        panel.windowModel.go("ALBUM")
        app.session.previewAlbum("album")
        app.session.selectMedia("b")
        compose.waitForIdle()
        assertTrue(app.session.visualSelectedThisSession)
        key(panel, KeyEvent.KEYCODE_BUTTON_B)
        assertFalse(app.session.visualSelectedThisSession)
        assertNull(app.session.selectedVisualMediaId)
        assertEquals("ALBUM", panel.windowModel.route.value.tab)
        assertNull(panel.windowModel.route.value.album)
    }
    @Test fun videoFoldersOpenIntoTheVideoListingAndExcludePhotoOnlyFolders() {
        val preview = videoPair()
        app.video.stop(preview.slot)
        panel.windowModel.go("VIDEO")
        panel.windowModel.videoFolders(true)
        compose.waitForIdle()
        waitFor { compose.onAllNodesWithTag("album:album").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("album:album").assertExists().performClick()
        compose.waitForIdle()
        assertEquals("VIDEO", panel.windowModel.route.value.tab)
        assertEquals("album", panel.windowModel.route.value.album)
        assertTrue(panel.windowModel.listing.value.items.all { it.kind == "VIDEO" })
    }
    @Test fun confirmOnVideoFolderOpensFolderInsteadOfPlayingItsPreview() {
        val preview = videoPair()
        app.video.stop(preview.slot)
        panel.windowModel.go("VIDEO")
        panel.windowModel.videoFolders(true)
        compose.waitForIdle()
        waitFor { compose.onAllNodesWithTag("album:album").fetchSemanticsNodes().isNotEmpty() }
        // A video remains selected as the folder cover: it must not intercept folder confirmation.
        app.session.selectMedia("a")
        compose.waitForIdle()
        assertFalse(panel.videoControllerAction?.invoke(PadAction.CONFIRM) == true)
        compose.onNodeWithTag("album:album").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        key(preview, KeyEvent.KEYCODE_BUTTON_A)
        compose.waitForIdle()
        assertEquals("album", panel.windowModel.route.value.album)
        assertNull(app.video.player)
        assertNull(app.video.resumeRequest)
    }

    @Test fun pairedResumeDialogUsesXtoRestartAndBtoCancel() {
        val preview = videoPair()
        app.video.stop(app.video.owner)
        val item = app.catalog.snapshot.value.byId.getValue("a").copy(videoPosition = 65000, videoPositionAt = System.currentTimeMillis())
        app.video.requestPlay(item, preview.slot, false)
        compose.waitForIdle()
        compose.onNodeWithTag("video-restart").assertExists()
        key(preview, KeyEvent.KEYCODE_BUTTON_B)
        assertNull(app.video.resumeRequest); assertNull(app.video.player)
        app.video.requestPlay(item, preview.slot, false)
        compose.waitForIdle()
        key(preview, KeyEvent.KEYCODE_BUTTON_X)
        assertNull(app.video.resumeRequest); assertNotNull(app.video.player)
        assertTrue("Restart must begin near zero rather than the saved 65-second position", app.video.player!!.currentPosition in 0..999)
    }
    @Test fun resumeIsAbandonedByDirectionsSectionsAndAlbumBrowsing() {
        val preview = videoPair(music = true)
        app.video.stop(app.video.owner)
        val item = app.catalog.snapshot.value.byId.getValue("a").copy(videoPosition = 65000, videoPositionAt = System.currentTimeMillis())
        fun request() {
            panel.windowModel.go("VIDEO")
            app.session.selectMedia("a")
            compose.waitForIdle()
            app.video.requestPlay(item, preview.slot, false)
            compose.waitForIdle()
            compose.onNodeWithTag("video-resume").assertExists()
        }
        for (button in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1)) {
            request()
            key(preview, button)
            assertNull("Navigation must discard the pending request for key $button", app.video.resumeRequest)
            assertNull(app.video.player)
        }
        request()
        compose.onNodeWithTag("navbar:ALBUM").performClick()
        assertNull(app.video.resumeRequest)
        compose.onNodeWithTag("album:album").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        key(preview, KeyEvent.KEYCODE_BUTTON_A)
        assertEquals("album", panel.windowModel.route.value.album)
        assertNull(app.video.player)
        request()
        compose.onNodeWithTag("navbar:MUSIC").performClick()
        compose.onNodeWithTag("media:song2").performClick()
        key(preview, KeyEvent.KEYCODE_BUTTON_A)
        waitFor { app.music.state.value.id == "song2" }
        assertNull(app.video.resumeRequest)
        assertNull(app.video.player)
    }
    @Test fun singlePanelResumeAllowsStickAndShoulderNavigationOutOfTheDialog() {
        start()
        runBlocking { app.catalog.dao.saveMedia(app.catalog.snapshot.value.media.filter { it.id in setOf("a", "b") }.map { it.copy(kind = "VIDEO", videoPosition = 65000, videoPositionAt = System.currentTimeMillis()) }) }
        waitFor { app.catalog.snapshot.value.byId["a"]?.kind == "VIDEO" }
        panel.windowModel.go("VIDEO")
        app.session.selectMedia("a")
        compose.waitForIdle()
        app.video.requestPlay(app.catalog.snapshot.value.byId.getValue("a"), panel.slot, false)
        compose.waitForIdle()
        motion(dialogView(), mapOf(MotionEvent.AXIS_X to 1f))
        assertNull(app.video.resumeRequest)
        assertNull(app.video.player)
        activity!!.windowFocusChanged(true)
        app.session.selectMedia("a")
        app.video.requestPlay(app.catalog.snapshot.value.byId.getValue("a"), panel.slot, false)
        compose.waitForIdle()
        key(dialogView(), KeyEvent.KEYCODE_BUTTON_R1)
        assertEquals("MUSIC", panel.windowModel.route.value.tab)
        assertNull(app.video.resumeRequest)
        assertNull(app.video.player)
    }
    @Test fun videoPausesAcrossMusicAndShoulderNavigationRestoresItsPreview() {
        val preview = videoPair()
        val running = app.video.player!!
        panel.windowModel.go("MUSIC")
        compose.waitForIdle()
        assertSame(running, app.video.player)
        assertFalse(running.playWhenReady)
        compose.onNodeWithTag("preview-video-name").assertDoesNotExist()
        key(preview, KeyEvent.KEYCODE_BUTTON_L1)
        assertEquals("VIDEO", panel.windowModel.route.value.tab)
        assertSame(running, app.video.player)
        compose.onNodeWithTag("media-surface:viewer").assertExists()
        compose.onNodeWithTag("preview-video-name").assertDoesNotExist() // Hidden with the native controls until tapped.
        key(preview, KeyEvent.KEYCODE_BUTTON_R1)
        assertEquals("MUSIC", panel.windowModel.route.value.tab)
        assertSame(running, app.video.player)
        assertFalse(running.playWhenReady)
        compose.onNodeWithTag("video-dim-toggle").assertDoesNotExist()
    }
    @Test fun backNavigatesWhileVideoPlaysButClosesAPausedVideoWithoutLeavingTheFolder() {
        val preview = videoPair()
        val running = app.video.player!!
        panel.windowModel.go("ALBUM", album = "album")
        compose.waitForIdle()
        key(preview, KeyEvent.KEYCODE_BUTTON_B)
        assertNull(panel.windowModel.route.value.album)
        assertSame(running, app.video.player)
        assertTrue(running.playWhenReady)
        panel.windowModel.go("ALBUM", album = "album")
        compose.waitForIdle()
        compose.onNodeWithTag("media:a").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.waitForIdle()
        key(preview, KeyEvent.KEYCODE_BUTTON_A)
        assertSame(running, app.video.player)
        assertFalse(running.playWhenReady)
        compose.onNodeWithTag("media:b").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.waitForIdle()
        key(preview, KeyEvent.KEYCODE_BUTTON_B)
        assertNull(app.video.player)
        assertEquals("album", panel.windowModel.route.value.album)
        assertEquals("b", app.session.selectedMediaId)
        compose.onNodeWithTag("preview-video-name").assertTextEquals("b.png")
        key(preview, KeyEvent.KEYCODE_BUTTON_B)
        assertNull(panel.windowModel.route.value.album)
        // A looping GIF converted to MP4 must not silence navigation indefinitely.
        app.video.play(app.catalog.snapshot.value.byId.getValue("a").copy(name = "animation.GIF.mp4"), preview.slot, true)
        assertFalse(app.video.playing.value)
    }
    @Test fun lockPressDoesNotRequestPinAndDraggingPaintsOpenFirst() {
        start()
        focusPreview()
        runBlocking {
            app.catalog.dao.saveAlbumSources(listOf(AlbumSource("album", "source", 1)))
            assertTrue(app.catalog.protection.protect(setOf("album"), "1234"))
        }
        waitFor { app.catalog.snapshot.value.albumLocked("album") }
        panel.windowModel.go("ALBUM")
        app.session.previewAlbum("album")
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("album-preview-lock").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(160)
        assertNull(app.session.albumUnlockRequest)
        compose.onNode(hasTestTag("album-lock-closed") and hasAnyAncestor(hasTestTag("album-preview-lock"))).assertExists()
        compose.onNodeWithTag("album-preview-lock").performTouchInput { moveTo(center + androidx.compose.ui.geometry.Offset(-70f, 0f)) }
        compose.mainClock.advanceTimeByFrame()
        compose.onNode(hasTestTag("album-lock-open") and hasAnyAncestor(hasTestTag("album-preview-lock"))).assertExists()
        assertNull(app.session.albumUnlockRequest)
        compose.onNodeWithTag("album-preview-lock").performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals("album", app.session.albumUnlockRequest)
        assertNull(panel.windowModel.route.value.album)
        compose.onNodeWithTag("album-pin-dialog").assertExists()
    }
    @Test fun draggingThePreviewLockRequestsPinOnTheBrowsingPanelWithoutUnlockingFiles() {
        start()
        focusPreview()
        runBlocking {
            app.catalog.dao.saveAlbumSources(listOf(AlbumSource("album", "source", 1)))
            assertTrue(app.catalog.protection.protect(setOf("album"), "1234"))
        }
        waitFor { app.catalog.snapshot.value.albumLocked("album") }
        panel.windowModel.go("ALBUM")
        app.session.previewAlbum("album")
        compose.waitForIdle()
        compose.onNodeWithTag("album-preview-lock").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertNull(panel.windowModel.route.value.album)
        assertEquals("album", app.session.albumUnlockRequest)
        compose.onNodeWithTag("album-pin-dialog").assertExists()
        assertTrue(app.catalog.snapshot.value.albumLocked("album"))
        compose.onNodeWithText("Annulla").performClick()
        compose.waitForIdle()
        assertNull(app.session.albumUnlockRequest)
        assertNull(panel.windowModel.route.value.album)
        compose.onNodeWithTag("album-preview-lock").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        listOf("1", "2", "3", "4").forEach { compose.onNodeWithTag("pin:$it").performClick() }
        waitFor { panel.windowModel.route.value.album == "album" }
        assertNull(app.session.albumUnlockRequest)
    }
    @Test fun swappingVideoPreservesThePlayerPositionAndPauseState() {
        val preview = videoPair()
        val running = app.video.player!!
        running.pause()
        running.seekTo(1500)
        compose.waitForIdle()
        val position = running.currentPosition
        compose.waitForIdle()
        assertSame(running, app.video.player)
        app.session.swap()
        compose.waitForIdle()
        assertEquals(panel.slot, app.video.owner)
        assertSame(running, app.video.player)
        assertEquals(position, running.currentPosition)
        assertFalse(running.playWhenReady)
        app.session.swap()
        compose.waitForIdle()
        assertEquals(preview.slot, app.video.owner)
        assertSame(running, app.video.player)
        assertEquals(position, running.currentPosition)
    }
    private fun start(profile: PadProfile = PadProfile(), music: Boolean = false) {
        app.tv.store.acknowledge(); app.tv.store.acknowledgeSources()
        val provider = PreviewFixtureProvider()
        provider.attachInfo(app, ProviderInfo().apply { authority = "ayngallery.padui"; exported = false })
        ShadowContentResolver.registerProviderInternal("ayngallery.padui", provider)
        val tree = DocumentsContract.buildTreeDocumentUri("ayngallery.padui", "root")
        app.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val media = listOf("a" to "IMAGE", "b" to "IMAGE", "song1" to "AUDIO", "song2" to "AUDIO").mapIndexed { index, (id, kind) ->
            MediaEntity(id, "ayngallery.padui:$id", DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(), "album", "$id.${if (kind == "IMAGE") "png" else "wav"}",
                if (kind == "IMAGE") "image/png" else "audio/wav", kind, 100, (4 - index).toLong())
        }
        runBlocking {
            app.preferences.save(GalleryPreferences(reducedMotion = true, pad = PadSettings(profiles = mapOf("default" to profile))))
            app.catalog.dao.saveSource(SourceEntity("source", tree.toString(), "QA local", "MIXED"))
            app.catalog.dao.saveAlbums(listOf(AlbumEntity("album", "root", DocumentsContract.buildDocumentUriUsingTree(tree, "root").toString(), "QA local", "QA local", null)))
            app.catalog.dao.saveMedia(media); app.catalog.dao.saveMembership(media.map { MediaSource(it.id, "source", 1) })
            withTimeout(5000) { app.catalog.snapshot.first { it.initialized && it.byId.size == 4 } }
        }
        if (music) {
            app.getSharedPreferences("music", Context.MODE_PRIVATE).edit().putString("queue", "[\"song1\",\"song2\"]").putInt("index", 0).commit()
            shadowOf(app).grantPermissions("it.ayngallery.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
            org.robolectric.shadows.ShadowProcess.setUid(app.applicationInfo.uid)
            service = Robolectric.buildService(MusicService::class.java).create()
            val binding = Intent(app, MusicService::class.java).setAction(MediaSessionService.SERVICE_INTERFACE)
            shadowOf(app).setComponentNameAndServiceForBindService(ComponentName(app, MusicService::class.java), service!!.get().onBind(binding))
            app.music.observe()
            waitFor { app.music.state.value.id == "song1" && app.music.state.value.queue.size == 2 }
        } else shadowOf(app).declareComponentUnbindable(ComponentName(app, MusicService::class.java))
        val launcher = Robolectric.buildActivity(LauncherActivity::class.java).create().get()
        activity = Robolectric.buildActivity(MainPanelActivity::class.java, shadowOf(launcher).nextStartedActivity).setup().visible().windowFocusChanged(true)
        panel.windowModel.go("PHOTO")
        compose.waitUntil(5000) { compose.onAllNodesWithTag("media:a").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        key(panel, KeyEvent.KEYCODE_DPAD_DOWN)
    }
    @Test fun controllerEntryAfterScrollingPastTheFirstAlbumDoesNotRequestADetachedTarget() {
        start()
        panel.windowModel.albumGrid(false)
        runBlocking {
            val original = app.catalog.dao.albums().single()
            val tree = DocumentsContract.buildTreeDocumentUri("ayngallery.padui", "root")
            val folders = (0..24).map { original.copy(id = "folder-$it", documentKey = "folder-$it", name = "%02d".format(it)) }
            app.catalog.dao.saveAlbums(folders)
            app.catalog.dao.saveAlbumSources((0..24).map { AlbumSource("folder-$it", "source", 1) })
            val media = folders.mapIndexed { index, folder -> MediaEntity("media-$index", "ayngallery.padui:$index", DocumentsContract.buildDocumentUriUsingTree(tree, "image-$index").toString(), folder.id, "image-$index.png", "image/png", "IMAGE", 1, 1) }
            app.catalog.dao.saveMedia(media); app.catalog.dao.saveMembership(media.map { MediaSource(it.id, "source", 1) })
        }
        panel.windowModel.go("ALBUM"); compose.waitForIdle()
        compose.onNodeWithTag("albums-list").performScrollToNode(hasTestTag("album:folder-20"))
        compose.waitForIdle()
        panel.enterPadMode?.invoke(); compose.waitForIdle()
        compose.onNodeWithTag("album:folder-20").assertIsDisplayed()
        compose.onNodeWithTag("album:folder-20").performClick(); compose.waitForIdle()
        assertEquals("folder-20", panel.windowModel.route.value.album)
        key(panel, KeyEvent.KEYCODE_BUTTON_B); compose.waitForIdle()
        // Returning recreates the lazy list; wait for its layout/focus restoration, not only recomposition.
        compose.waitUntil(5000) {
            val album = compose.onNodeWithTag("album:folder-20")
            album.isDisplayed() && album.fetchSemanticsNode().config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Focused) == true
        }
        compose.onNodeWithTag("album:folder-20").assertIsDisplayed().assertIsFocused()
        panel.windowModel.albumGrid(true); compose.waitForIdle()
        compose.onNodeWithTag("albums-grid").assertIsDisplayed()
    }
    @Test fun settingsShortcutOpensSettingsAndFollowsRemapping() {
        start()
        panel.windowModel.go("COLLECTION"); app.session.selectMedia(null); compose.waitForIdle()
        key(panel, KeyEvent.KEYCODE_BUTTON_X)
        assertEquals("SETTINGS_HUB", panel.windowModel.route.value.tab)
        key(panel, KeyEvent.KEYCODE_BUTTON_B)
        val profile = PadProfile().assign(PadContext.LIBRARY, PadAction.SETTINGS, PadBinding(setOf(PadButton.R3)), replace = true)
        runBlocking { app.preferences.update { it.copy(pad = it.pad.copy(profiles = mapOf("default" to profile))) } }
        panel.windowModel.go("COLLECTION"); app.session.selectMedia(null); compose.waitForIdle()
        key(panel, KeyEvent.KEYCODE_BUTTON_THUMBR)
        assertEquals("SETTINGS_HUB", panel.windowModel.route.value.tab)
    }
    @Test fun leavingSettingsRestoresLibraryShouldersAfterThePreviewContextChanged() {
        start(); val preview = focusPreview()
        app.session.openSettings(preview); compose.waitForIdle()
        app.session.selectMedia("b"); compose.waitForIdle()
        app.session.closeSettings(); compose.waitForIdle()
        assertEquals(PadContext.LIBRARY, panel.padContext)
        key(preview, KeyEvent.KEYCODE_BUTTON_R1)
        assertEquals("ALBUM", panel.windowModel.route.value.tab)
    }
    @Test fun playbackShouldersChangeCategoryAndHubReturnsToTheOpenedSection() {
        start(); val preview = focusPreview()
        app.session.openSettings(preview); compose.waitForIdle()
        val owner = app.session.settingsActivity()!!
        compose.onNodeWithTag("settings-category:INTERFACE").assertIsNotFocused()
        compose.onNodeWithTag("settings-category:PLAYBACK").performClick(); compose.waitForIdle()
        app.session.playbackSettingsCategory = "IMAGE"
        key(preview, KeyEvent.KEYCODE_BUTTON_R1)
        assertEquals("VIDEO", app.session.playbackSettingsCategory)
        key(preview, KeyEvent.KEYCODE_BUTTON_L1)
        assertEquals("IMAGE", app.session.playbackSettingsCategory)
        key(preview, KeyEvent.KEYCODE_BUTTON_B)
        assertEquals("SETTINGS_HUB", owner.windowModel.route.value.tab)
        compose.onNodeWithTag("settings-category:PLAYBACK").assertIsFocused()
    }
    @Test fun focusedSliderAdjustsWithControllerRatherThanLosingFocus() {
        start()
        var value by androidx.compose.runtime.mutableFloatStateOf(.55f)
        val prefs = app.preferences.state.value
        panel.setContent { GalleryDesign(prefs) {
            GallerySlider(value, { value = it }, Modifier.testTag("controller-slider"))
        } }
        compose.onNodeWithTag("controller-slider").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        key(panel, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue(value > .55f)
        compose.onNodeWithTag("controller-slider").assertIsFocused()
        key(panel, KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(.55f, value, .001f)
    }
    @Test fun controlsShouldersSwitchTabsAndXOpensTheGuide() {
        start(); val preview = focusPreview()
        app.session.openSettings(preview)
        val owner = app.session.settingsActivity()!!
        app.session.settingsSection = "INPUT"; owner.windowModel.go("SETTINGS_INPUT")
        compose.waitForIdle()
        compose.onNodeWithTag("pad-settings-list").assertExists()
        key(preview, KeyEvent.KEYCODE_BUTTON_R1)
        compose.onNodeWithTag("touch-settings-list").assertExists()
        key(preview, KeyEvent.KEYCODE_BUTTON_L1)
        compose.onNodeWithTag("pad-settings-list").assertExists()
        key(preview, KeyEvent.KEYCODE_BUTTON_X)
        compose.onNodeWithText("Comandi ·", substring = true).assertExists()
        key(preview, KeyEvent.KEYCODE_BUTTON_B)
        compose.onNodeWithText("Comandi ·", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("pad-settings-list").assertExists()
    }
    @Test fun controlsDownCanReachTheActualBottomOfBothTabs() {
        start(); val preview = focusPreview()
        app.session.openSettings(preview)
        val owner = app.session.settingsActivity()!!
        app.session.settingsSection = "INPUT"; owner.windowModel.go("SETTINGS_INPUT")
        compose.waitForIdle()
        compose.onNodeWithTag("pad-settings-list").performScrollToNode(hasTestTag("pad-record:GRID_LARGER"))
        compose.onNodeWithTag("pad-record:GRID_LARGER").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        key(preview, KeyEvent.KEYCODE_DPAD_DOWN)
        compose.mainClock.advanceTimeBy(800); compose.waitForIdle()
        assertFalse(owner.settingsScrollEnd!!.invoke())
        key(preview, KeyEvent.KEYCODE_BUTTON_R1)
        compose.onNodeWithTag("touch-defaults").performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        key(preview, KeyEvent.KEYCODE_DPAD_DOWN)
        compose.mainClock.advanceTimeBy(800); compose.waitForIdle()
        assertFalse(owner.settingsScrollEnd!!.invoke())
    }
    @Test fun playbackImageAndVideoNotesCanReachTheActualBottom() {
        start(); val preview = focusPreview()
        runBlocking { app.preferences.update { it.copy(gifMode = "VISIBLE", gifLimit = 4) } }
        app.session.openSettings(preview)
        val owner = app.session.settingsActivity()!!
        app.session.settingsSection = "PLAYBACK"; owner.windowModel.go("SETTINGS")
        for ((category, tag) in listOf("IMAGE" to "gif-limit", "VIDEO" to "video-auto-dim")) {
            app.session.playbackSettingsCategory = category
            compose.waitForIdle()
            compose.onNodeWithTag(tag).performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            key(preview, KeyEvent.KEYCODE_DPAD_DOWN)
            compose.mainClock.advanceTimeBy(800); compose.waitForIdle()
            val axis = compose.onNodeWithTag("settings-list").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange]
            assertEquals(category, axis.maxValue(), axis.value(), 1f)
        }
    }
    @Test fun playbackDownRevealsNotesBelowTheLastMusicControl() {
        start(); val preview = focusPreview()
        app.session.openSettings(preview)
        val owner = app.session.settingsActivity()!!
        app.session.settingsSection = "PLAYBACK"; owner.windowModel.go("SETTINGS")
        app.session.playbackSettingsCategory = "MUSIC"
        compose.waitForIdle()
        compose.onNodeWithTag("always-previous").performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.waitForIdle()
        key(preview, KeyEvent.KEYCODE_DPAD_DOWN)
        compose.mainClock.advanceTimeBy(800); compose.waitForIdle()
        val axis = compose.onNodeWithTag("settings-list").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange]
        assertEquals("Down reveals the entire trailing note", axis.maxValue(), axis.value(), 1f)
        compose.onNodeWithTag("music-previous-help").assertIsDisplayed()
        key(preview, KeyEvent.KEYCODE_DPAD_UP)
        compose.onNodeWithTag("settings-list").assertExists()
    }
    @Test fun infoControllerMovesBetweenLicensesAndFolderLinksOnBothDisplays() {
        start(music = true); val preview = focusPreview()
        panel.windowModel.go("MUSIC"); compose.waitForIdle()
        compose.onNodeWithTag("media:song2").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        app.session.openSettings(preview)
        val owner = app.session.settingsActivity()!!
        app.session.settingsSection = "INFO"; owner.windowModel.go("SETTINGS_INFO")
        compose.waitForIdle()
        compose.onNodeWithTag("info-licenses").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        key(preview, KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithTag("settings-tool:SOURCES").assertIsFocused()
        assertTrue(app.session.infoDetailsActive)
        compose.onNodeWithTag("info-licenses").assertIsNotFocused()
        for (targetKey in listOf("COLLECTION", "HIDDEN", "SETTINGS_PIN")) {
            key(preview, KeyEvent.KEYCODE_DPAD_DOWN)
            compose.onNodeWithTag("settings-tool:$targetKey").assertIsFocused()
        }
        repeat(2) { key(preview, KeyEvent.KEYCODE_DPAD_DOWN) }
        compose.onNodeWithTag("info-cache-clear").assertIsFocused()
        key(preview, KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithTag("info-app-reset").assertIsFocused()
        key(preview, KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithTag("info-credits").assertIsFocused()
        compose.onAllNodesWithTag("app-info").filter(hasAnyDescendant(hasTestTag("info-credits"))).onFirst().performScrollToNode(hasTestTag("settings-tool:SOURCES"))
        compose.onNodeWithTag("settings-tool:SOURCES").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        key(preview, KeyEvent.KEYCODE_DPAD_UP)
        compose.onNodeWithTag("info-licenses").assertIsFocused()
        assertFalse(app.session.infoDetailsActive)
        compose.onNodeWithTag("settings-tool:SOURCES").assertIsNotFocused()
    }
    @Test fun tutorialUsesOnlyThePrimaryPanelAndItsControllerTarget() {
        start(); val preview = focusPreview()
        app.preferences.beginTutorial()
        compose.waitForIdle()
        compose.onAllNodesWithTag("welcome-tutorial").assertCountEquals(1)
        assertNotNull(panel.controllerModal); assertNull(preview.controllerModal)
        assertSame(panel, app.session.controllerTarget(preview))
        compose.onNodeWithTag("tutorial-welcome-screen").assertIsDisplayed()
        val before = app.session.libraryModel()!!.route.value
        compose.onNodeWithTag("tutorial-welcome-screen").performTouchInput { click(bottomCenter - androidx.compose.ui.geometry.Offset(80f, 10f)) }
        compose.waitForIdle()
        assertEquals(before, app.session.libraryModel()!!.route.value)
        key(preview, KeyEvent.KEYCODE_BUTTON_B)
        compose.onNodeWithTag("welcome-tutorial").assertIsDisplayed()
    }
    @Test fun appResetUsesTheDetailsPanelAndBackDismissesWithoutLeavingInfo() {
        start(); val preview = focusPreview()
        app.session.openSettings(preview)
        val owner = app.session.settingsActivity()!!
        app.session.settingsSection = "INFO"; owner.windowModel.go("SETTINGS_INFO")
        compose.waitForIdle()
        compose.onNodeWithTag("info-app-reset").performScrollTo().assertIsDisplayed()
        val cache = compose.onNodeWithTag("info-cache-clear").fetchSemanticsNode().boundsInRoot
        val reset = compose.onNodeWithTag("info-app-reset").fetchSemanticsNode().boundsInRoot
        assertEquals(cache.top, reset.top, .1f); assertEquals(cache.height, reset.height, .1f)
        val manager = shadowOf(app.getSystemService(android.app.ActivityManager::class.java))
        compose.onNodeWithTag("info-app-reset").performClick()
        compose.onNodeWithTag("app-reset-confirm").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("app-reset-countdown").assertTextEquals("5 s")
        assertFalse(manager.isApplicationUserDataCleared)
        assertSame(panel, app.session.controllerTarget(preview))
        key(preview, KeyEvent.KEYCODE_BUTTON_B)
        assertEquals("SETTINGS_INFO", owner.windowModel.route.value.tab)
        assertFalse(app.session.resetApplicationRequested); assertFalse(manager.isApplicationUserDataCleared)
        compose.onNodeWithTag("info-app-reset").performClick()
        compose.onNodeWithTag("app-reset-confirm").assertIsNotEnabled()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(5_100)
        compose.waitForIdle()
        compose.onNodeWithTag("app-reset-countdown").assertDoesNotExist()
        val cancelButton = compose.onNodeWithTag("app-reset-cancel").fetchSemanticsNode().boundsInRoot
        val confirmButton = compose.onNodeWithTag("app-reset-confirm").fetchSemanticsNode().boundsInRoot
        assertTrue(cancelButton.right <= confirmButton.left)
        compose.onNodeWithTag("app-reset-confirm").assertIsEnabled().performClick()
        assertTrue(manager.isApplicationUserDataCleared)
    }
    @Test fun albumDragMovesSelectedBlockAndKeepsSavedOrder() {
        start()
        runBlocking {
            val original = app.catalog.dao.albums().single()
            app.catalog.dao.saveAlbums(listOf(original.copy(id = "second", documentKey = "second", name = "Second"), original.copy(id = "third", documentKey = "third", name = "Third")))
            app.catalog.dao.saveAlbumSources(listOf("album", "second", "third").map { AlbumSource(it, "source", 1) })
        }
        app.preferences.reorderAlbums(listOf("album", "second", "third"))
        panel.windowModel.albumGrid(true); panel.windowModel.go("ALBUM"); compose.waitForIdle()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("album:third").fetchSemanticsNodes().isNotEmpty() }
        panel.windowModel.selectAlbums(setOf("album", "second")); compose.waitForIdle()
        val first = compose.onNodeWithTag("album:album").fetchSemanticsNode().boundsInRoot
        val last = compose.onNodeWithTag("album:third").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("album:album").performTouchInput {
            down(center); advanceEventTime(650)
            moveTo(last.center - first.topLeft, delayMillis = 100)
            advanceEventTime(50)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("albums-grid").performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(listOf("third", "album", "second"), app.preferences.albumOrder.value.take(3))
        assertEquals(app.preferences.albumOrder.value, PreferenceStore(app).albumOrder.value)
        compose.onNodeWithTag("library-refresh-spinner").assertDoesNotExist()
        panel.windowModel.selectAlbums(emptySet()); compose.waitForIdle()
        val bottom = compose.onNodeWithTag("album:second").fetchSemanticsNode().boundsInRoot
        val top = compose.onNodeWithTag("album:third").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("album:second").performTouchInput {
            down(center); advanceEventTime(650)
            // The left half explicitly inserts before the target in the stable grid.
            moveTo(androidx.compose.ui.geometry.Offset(top.left + 12f, top.center.y) - bottom.topLeft, delayMillis = 150)
            up()
        }
        compose.waitForIdle()
        compose.onNodeWithTag("library-refresh-spinner").assertDoesNotExist()
        assertEquals("second", app.preferences.albumOrder.value.first())
        panel.windowModel.selectAlbums(emptySet())
        compose.onNodeWithTag("album:third").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        key(panel, KeyEvent.KEYCODE_BUTTON_A)
        assertEquals("third", panel.windowModel.route.value.album)
    }
    @Test fun albumGridLongPressOpensOptionsWithoutLeavingSelectionBehind() {
        start()
        runBlocking { app.catalog.dao.saveAlbumSources(listOf(AlbumSource("album", "source", 1))) }
        panel.windowModel.go("ALBUM"); compose.waitForIdle()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("album:album").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("album:album").performTouchInput { longClick() }
        compose.waitForIdle()
        assertEquals("album", app.session.albumActionTarget)
        assertTrue(panel.windowModel.selectedAlbums.value.isEmpty())
        key(panel, KeyEvent.KEYCODE_BUTTON_B)
        assertTrue(panel.windowModel.selectedAlbums.value.isEmpty())
        assertEquals("ALBUM", panel.windowModel.route.value.tab)
    }
    @Test fun albumCheckboxTouchChangesSelectionExactlyOnce() {
        start()
        runBlocking { app.catalog.dao.saveAlbumSources(listOf(AlbumSource("album", "source", 1))) }
        panel.windowModel.go("ALBUM"); panel.windowModel.albumGrid(false)
        panel.windowModel.selectAlbums(setOf("album")); compose.waitForIdle()
        compose.onNode(isToggleable().and(hasAnyAncestor(hasTestTag("album:album"))), useUnmergedTree = true).performTouchInput { click() }
        compose.waitForIdle()
        assertTrue(panel.windowModel.selectedAlbums.value.isEmpty())
        assertNull(panel.windowModel.route.value.album)
    }
    @Test fun backAtRootNeverFinishesAndAlbumContentsReceiveControllerFocus() {
        start()
        key(panel, KeyEvent.KEYCODE_BUTTON_B)
        assertFalse(panel.isFinishing)
        panel.windowModel.go("ALBUM", album = "album"); compose.waitForIdle()
        panel.enterPadMode?.invoke(); compose.waitForIdle()
        compose.onNodeWithTag("media:a").assertIsFocused()
        key(panel, KeyEvent.KEYCODE_DPAD_RIGHT); compose.waitForIdle()
        compose.onNodeWithTag("media:b").assertIsFocused()
        compose.onNodeWithContentDescription("Vista elenco").performClick(); compose.waitForIdle()
        compose.onNodeWithContentDescription("Vista griglia").assertIsDisplayed()
        key(panel, KeyEvent.KEYCODE_BUTTON_B); compose.waitForIdle()
        assertNull(panel.windowModel.route.value.album)
        key(panel, KeyEvent.KEYCODE_BUTTON_B)
        assertFalse(panel.isFinishing)
    }
    @Test fun bindingRecordingShowsConflictAndRequiresExplicitSave() = recording("landscape")
    @Test @Config(qualifiers = "it-w360dp-h640dp-port") fun bindingRecordingFitsSmallPanelAt140Percent() {
        RuntimeEnvironment.setFontScale(1.4f); recording("large-text")
    }
    private fun recording(layout: String) {
        start()
        panel.windowModel.go("SETTINGS"); compose.waitForIdle()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-controls"))
        compose.onNodeWithTag("settings-controls").performClick(); compose.waitForIdle()
        assertEquals("CONTROLS", panel.windowModel.route.value.tab)
        compose.onNodeWithTag("pad-advanced").assertDoesNotExist()
        compose.onNodeWithTag("pad-settings-list").performScrollToNode(hasTestTag("pad-record:CONFIRM"))
        compose.onNodeWithTag("pad-record:CONFIRM").performClick(); compose.waitForIdle()
        var dialog = dialogView()
        key(dialog, KeyEvent.KEYCODE_BUTTON_X)
        compose.onNodeWithTag("pad-candidate").assertTextEquals("X ricevuto")
        compose.onNodeWithTag("pad-conflict").assertIsDisplayed()
        compose.onNodeWithTag("pad-use").assertIsNotEnabled()
        capture(dialog, "pad-conflict-$layout")
        compose.onNodeWithTag("pad-label-edit").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("pad-physical-label").performTextReplacement("X fisico")
        compose.onNodeWithTag("pad-label-use").performClick(); compose.waitForIdle()
        dialog = dialogView()
        compose.onNode(isToggleable()).performClick()
        compose.onNodeWithTag("pad-use").assertIsEnabled()
        // Confirm through the controller, using the dialog context, not a touch click.
        key(dialog, KeyEvent.KEYCODE_BUTTON_A)
        compose.waitUntil(5000) { compose.onAllNodesWithTag("pad-use").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("pad-binding:CONFIRM").assertTextEquals("X fisico")
        assertNotEquals("X fisico", app.preferences.state.value.pad.profile(null).bindings.getValue(PadContext.LIBRARY)[PadAction.CONFIRM]?.label)
        activity!!.recreate(); activity!!.windowFocusChanged(true); compose.waitForIdle()
        compose.onNodeWithTag("pad-settings-list").performScrollToNode(hasTestTag("pad-binding:CONFIRM"))
        compose.onNodeWithTag("pad-binding:CONFIRM").assertTextEquals("X fisico")
        compose.onNodeWithTag("pad-save").assertIsDisplayed().assertIsEnabled()
        capture(panel.window.decorView, "pad-settings-$layout")
        compose.onNodeWithTag("pad-save").performClick()
        waitFor { app.preferences.state.value.pad.profile(null).bindings.getValue(PadContext.LIBRARY)[PadAction.CONFIRM]?.label == "X fisico" }
        assertEquals(app.preferences.state.value.pad, PreferenceStore(app).state.value.pad)
    }
    @Test fun deleteShortcutIsFrozenAndModalKeyAxesStayLocal() {
        val profile = PadProfile().assign(PadContext.LIBRARY, PadAction.DELETE, PadBinding(setOf(PadButton.X)), replace = true)
            .assign(PadContext.DIALOG, PadAction.CONFIRM, PadBinding(setOf(PadButton.R1)))
            .assign(PadContext.DIALOG, PadAction.BACK, PadBinding(setOf(PadButton.L1)))
        start(profile)
        compose.onNodeWithTag("media:a").performSemanticsAction(SemanticsActions.RequestFocus) { it() }; compose.waitForIdle()
        assertEquals("a", app.session.selectedMediaId)
        key(panel, KeyEvent.KEYCODE_BUTTON_X); compose.waitForIdle()
        val dialog = dialogView()
        compose.onNodeWithTag("confirm-file-action").assertIsDisplayed()
        app.session.selectMedia("b"); compose.waitForIdle()
        compose.onNodeWithTag("file-action-target").assertTextEquals("a.png")
        val before = app.catalog.snapshot.value.byId
        key(dialog, KeyEvent.KEYCODE_BUTTON_X)
        key(dialog, KeyEvent.KEYCODE_BUTTON_R2)
        motion(dialog, mapOf(MotionEvent.AXIS_RTRIGGER to .9f)); motion(dialog, emptyMap())
        assertEquals("b", app.session.selectedMediaId)
        assertEquals(before, app.catalog.snapshot.value.byId)
        assertTrue(runBlocking { app.catalog.dao.operations().isEmpty() })
        // Move the modal's actual focus with its left stick, then confirm its Annulla button with R1.
        compose.onNodeWithText("Annulla").performSemanticsAction(SemanticsActions.RequestFocus) { it() }; compose.waitForIdle()
        motion(dialog, mapOf(MotionEvent.AXIS_X to 1f)); motion(dialog, emptyMap())
        compose.onNodeWithTag("confirm-file-action").assertIsFocused()
        compose.onNodeWithText("Annulla").performSemanticsAction(SemanticsActions.RequestFocus) { it() }; compose.waitForIdle()
        key(dialog, KeyEvent.KEYCODE_BUTTON_R1)
        compose.waitUntil(5000) { compose.onAllNodesWithTag("confirm-file-action").fetchSemanticsNodes().isEmpty() }
        assertTrue(runBlocking { app.catalog.dao.operations().isEmpty() })
        activity!!.windowFocusChanged(true)
        key(panel, KeyEvent.KEYCODE_BUTTON_X); compose.waitForIdle()
        key(dialogView(), KeyEvent.KEYCODE_BUTTON_L1)
        compose.waitUntil(5000) { compose.onAllNodesWithTag("confirm-file-action").fetchSemanticsNodes().isEmpty() }
    }
    @Test fun selectingAnotherSongKeepsThePlayingPreviewUntilAStartsItAndPauseAllowsBrowsing() {
        start(music = true)
        val audioBytes = File("src/test/resources/media/video-segments.mp4").readBytes()
        listOf("song1", "song2").forEach { File(app.cacheDir, "preview-$it.png").writeBytes(audioBytes) }
        val preview = focusPreview()
        panel.windowModel.go("MUSIC"); compose.waitForIdle()
        app.music.play("song1", listOf("song1", "song2"))
        waitFor { app.music.state.value.id == "song1" && app.music.state.value.playRequested }
        compose.onNodeWithTag("media:song2").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.waitForIdle()
        assertEquals("song2", app.session.selectedMediaId)
        assertEquals("song1", app.music.state.value.id)
        compose.onNodeWithTag("preview-music-title").assertTextEquals("song1.wav")
        activity!!.windowFocusChanged(true)
        key(panel, KeyEvent.KEYCODE_BUTTON_A)
        waitFor { app.music.state.value.id == "song2" && app.music.state.value.playRequested }
        compose.onNodeWithTag("preview-music-title").assertTextEquals("song2.wav")
        app.music.pause()
        waitFor { !app.music.state.value.playRequested }
        compose.onNodeWithTag("media:song1").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.waitForIdle()
        assertEquals("song2", app.music.state.value.id)
        compose.onNodeWithTag("preview-music-title").assertTextEquals("song1.wav")
    }
    @Test fun musicShoulderChangesFollowTheNewTrackInThePreviewAndSelection() {
        start(music = true)
        val preview = focusPreview()
        panel.windowModel.go("MUSIC"); compose.waitForIdle()
        app.session.selectMedia("song1"); compose.waitForIdle()
        compose.onNodeWithTag("preview-music-title").assertTextEquals("song1.wav")
        key(preview, KeyEvent.KEYCODE_BUTTON_R2)
        waitFor { app.music.state.value.id == "song2" && app.session.selectedMediaId == "song2" }
        compose.onNodeWithTag("preview-music-title").assertTextEquals("song2.wav")
        key(preview, KeyEvent.KEYCODE_BUTTON_L2)
        waitFor { app.music.state.value.id == "song1" && app.session.selectedMediaId == "song1" }
        compose.onNodeWithTag("preview-music-title").assertTextEquals("song1.wav")
    }
    @Test fun mediaAndTrackShortcutsUseDifferentTargetsAndDeduplicateTrigger() {
        start(PadProfile().assign(PadContext.VIEWER, PadAction.TRACK_NEXT, PadBinding(setOf(PadButton.R3)), replace = true), music = true)
        app.session.updateMediaContext(listOf("a", "b")); app.session.selectMedia("a")
        panel.windowModel.go("PLAYER"); panel.windowModel.open("a"); compose.waitForIdle()
        assertEquals(PadContext.VIEWER, panel.padContext)
        panel.dispatchKeyEvent(event(KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.ACTION_DOWN))
        motion(panel, mapOf(MotionEvent.AXIS_RTRIGGER to .9f))
        panel.dispatchKeyEvent(event(KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.ACTION_UP))
        motion(panel, emptyMap()); compose.waitForIdle()
        assertEquals("b", panel.windowModel.route.value.viewer)
        assertEquals("song1", app.music.state.value.id)
        key(panel, KeyEvent.KEYCODE_BUTTON_THUMBR)
        waitFor { app.music.state.value.id == "song2" }
        assertEquals("b", panel.windowModel.route.value.viewer)
        assertFalse(app.music.state.value.playing)
        assertFalse(ReflectionHelpers.getField<Player>(service!!.get(), "player").playWhenReady)
        key(panel, KeyEvent.KEYCODE_BUTTON_B); compose.waitForIdle()
        assertNull(panel.windowModel.route.value.viewer); assertEquals(PadContext.PLAYER, panel.padContext)
        key(panel, KeyEvent.KEYCODE_BUTTON_L2); waitFor { app.music.state.value.id == "song1" }
        panel.windowModel.go("MUSIC"); compose.waitForIdle()
        compose.onNodeWithTag("media:song2").performSemanticsAction(SemanticsActions.RequestFocus) { it() }; compose.waitForIdle()
        assertEquals("song2", app.session.selectedMediaId)
        assertEquals("song1", app.music.state.value.id)
        assertFalse(ReflectionHelpers.getField<Player>(service!!.get(), "player").playWhenReady)
    }
    @Test fun controllerTransformsOnlyActiveComparisonPaneAndRestoresGridFocus() {
        start()
        runBlocking { app.preferences.update { it.copy(viewerZoom = true) } }; compose.waitForIdle()
        compose.onNodeWithTag("media:b").performSemanticsAction(SemanticsActions.RequestFocus) { it() }; compose.waitForIdle()
        key(panel, KeyEvent.KEYCODE_BUTTON_A); compose.waitForIdle()
        assertEquals("b", panel.windowModel.route.value.viewer)
        key(panel, KeyEvent.KEYCODE_BUTTON_R1); compose.waitForIdle()
        compose.onNodeWithText("125% · reset").assertExists()
        key(panel, KeyEvent.KEYCODE_BUTTON_B); compose.waitForIdle()
        compose.onNodeWithTag("media:b").assertIsFocused()
        val original = ComparisonState(panes = listOf(ComparisonPane("pane_1", "a", true, listOf("a", "b")), ComparisonPane("pane_2", "b", true, listOf("a", "b"))), enabled = true)
        app.comparison.update(original); panel.windowModel.go("COMPARE"); compose.waitForIdle()
        key(panel, KeyEvent.KEYCODE_BUTTON_R1); compose.waitForIdle()
        assertEquals(1.25f, app.comparison.state.value.panes[0].view.zoom)
        assertEquals(ViewTransform(), app.comparison.state.value.panes[1].view)
        motion(panel, mapOf(MotionEvent.AXIS_Z to 1f)); motion(panel, emptyMap()); compose.waitForIdle()
        assertTrue(app.comparison.state.value.panes[0].view.x < 0)
        assertEquals(ViewTransform(), app.comparison.state.value.panes[1].view)
        activity!!.windowFocusChanged(false)
        val frozen = app.comparison.state.value
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800)); compose.waitForIdle()
        assertEquals(frozen, app.comparison.state.value)
        activity!!.windowFocusChanged(true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800)); compose.waitForIdle()
        assertEquals(frozen, app.comparison.state.value)
    }
    @Test fun zoomedPreviewUsesSticksWithoutMovingTheLibraryAndStopsOnFocusLoss() {
        start()
        runBlocking { app.preferences.update { it.copy(viewerZoom = true) } }; compose.waitForIdle()
        key(panel, KeyEvent.KEYCODE_BUTTON_A)
        compose.onNodeWithText("+").performClick()
        fun transform() = compose.onNodeWithTag("media-surface:viewer").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription]
        val selected = app.session.selectedMediaId
        val initial = transform()
        motion(panel, mapOf(MotionEvent.AXIS_X to 1f))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(120)); compose.waitForIdle()
        motion(panel, emptyMap())
        assertNotEquals(initial, transform())
        assertEquals(selected, app.session.selectedMediaId)
        val panned = transform()
        motion(panel, mapOf(MotionEvent.AXIS_RZ to -1f))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(120)); compose.waitForIdle()
        motion(panel, emptyMap())
        assertNotEquals(panned, transform())
        activity!!.windowFocusChanged(false)
        val frozen = transform()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300)); compose.waitForIdle()
        assertEquals(frozen, transform())
        activity!!.windowFocusChanged(true)
        compose.onNode(hasText("% · reset", substring = true)).performClick()
        motion(panel, mapOf(MotionEvent.AXIS_X to -1f)); motion(panel, emptyMap())
        assertEquals("a", app.session.selectedMediaId)
    }
    private fun event(key: Int, action: Int) = KeyEvent(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), action, key, 0, 0, 40, 0, 0, InputDevice.SOURCE_GAMEPAD)
    private fun key(target: android.app.Activity, code: Int) { target.dispatchKeyEvent(event(code, KeyEvent.ACTION_DOWN)); target.dispatchKeyEvent(event(code, KeyEvent.ACTION_UP)); compose.waitForIdle() }
    private fun key(target: View, code: Int) { target.dispatchKeyEvent(event(code, KeyEvent.ACTION_DOWN)); target.dispatchKeyEvent(event(code, KeyEvent.ACTION_UP)); compose.waitForIdle() }
    private fun motion(target: android.app.Activity, axes: Map<Int, Float>) { val e = axisEvent(axes); try { target.dispatchGenericMotionEvent(e) } finally { e.recycle() }; compose.waitForIdle() }
    private fun motion(target: View, axes: Map<Int, Float>) { val e = axisEvent(axes); try { target.dispatchGenericMotionEvent(e) } finally { e.recycle() }; compose.waitForIdle() }
    private fun axisEvent(axes: Map<Int, Float>): MotionEvent {
        val properties = MotionEvent.PointerProperties().apply { id = 0 }
        val coords = MotionEvent.PointerCoords().apply { axes.forEach { (axis, value) -> setAxisValue(axis, value) } }
        return MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, 1, arrayOf(properties), arrayOf(coords), 0, 0, 1f, 1f, 40, 0, InputDevice.SOURCE_JOYSTICK, 0)
    }
    private fun dialogView(): View {
        val view = ShadowDialog.getShownDialogs().last { it.isShowing }.window!!.decorView
        val root: Any = ReflectionHelpers.callInstanceMethod(view, "getViewRootImpl")
        Shadow.extract<ShadowViewRootImpl>(root).callWindowFocusChanged(true)
        activity!!.windowFocusChanged(false)
        compose.waitForIdle()
        assertTrue(view.hasWindowFocus())
        return view
    }
    private fun waitFor(check: () -> Boolean) {
        val deadline = System.nanoTime() + 8_000_000_000L
        while (!check() && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(15) }
        assertTrue("Condition timed out", check()); compose.waitForIdle()
    }
    private fun capture(view: View, name: String) {
        compose.waitForIdle(); shadowOf(Looper.getMainLooper()).idle()
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val file = File("../qa/rendered/$name.png").absoluteFile
        file.parentFile!!.mkdirs(); file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }; bitmap.recycle()
    }
}
