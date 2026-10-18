package it.ayngallery

import android.content.*
import android.content.pm.ProviderInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.provider.DocumentsContract
import android.view.KeyEvent
import android.view.View
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestGalleryApp::class, qualifiers = "it-w800dp-h600dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class TouchRenderTest {
    @Test fun toolsShouldersCycleAllFourSectionsWithoutLeavingTools() {
        start()
        panel.windowModel.go("TRASH"); compose.waitForIdle()
        app.session.recoveryTab = "TRASH"
        fun next() { panel.executeControllerAction(PadAction.TAB_NEXT); compose.waitForIdle() }
        next(); assertEquals("DUPLICATES", app.session.recoveryTab)
        next(); assertEquals("FTP", app.session.recoveryTab); assertFalse(app.explorer.browsing.value)
        next(); assertEquals("FTP", app.session.recoveryTab); assertTrue(app.explorer.browsing.value)
        next(); assertEquals("TRASH", app.session.recoveryTab)
        panel.executeControllerAction(PadAction.TAB_PREVIOUS); compose.waitForIdle()
        assertEquals("FTP", app.session.recoveryTab); assertTrue(app.explorer.browsing.value)
        assertEquals("TRASH", panel.windowModel.route.value.tab)
    }

    @Test fun mixedFavoritesControllerCrossesLocalAndChannelTilesAndReverseReselectsFirst() {
        start()
        val channel = TvCatalogEntry("https://example.com/controller.m3u8", "Channel", null, null, null)
        runBlocking {
            app.preferences.update { it.copy(tvEnabled = true, tvInMediaFavorites = true) }
            app.catalog.favorite("a"); app.tv.store.favorite(channel, true)
        }
        pump { app.catalog.snapshot.value.byId["a"]?.favorite == true }
        panel.windowModel.go("FAVORITE"); compose.waitForIdle()
        val data = app.catalog.snapshot.value
        compose.waitUntil(5000) { compose.onAllNodesWithTag("favorite:tv:${tvKey(channel)}").fetchSemanticsNodes().isNotEmpty() && panel.mediaGridFocus != null }
        compose.onNodeWithTag("media:a").performSemanticsAction(SemanticsActions.RequestFocus) { it() }; compose.waitForIdle()
        panel.clearPadFocus?.invoke()
        val initialRows = tvFavoriteRows(listOf(data.byId.getValue("a")), app.tv.store.channels, "", panel.windowModel.sort.value, panel.windowModel.descending.value, false, "ALL")
        val towardChannel = if (initialRows.first() is TvFavoriteRow.Local) PadAction.RIGHT else PadAction.LEFT
        panel.executeControllerAction(towardChannel); compose.waitForIdle()
        assertTrue("selected=${app.tv.selected} local=${app.session.selectedMediaId} handler=${panel.mediaGridFocus != null} header=${panel.settingsHeaderHasFocus}", app.tv.favoritePreview); assertEquals(channel, app.tv.selected)
        panel.executeControllerAction(if (towardChannel == PadAction.RIGHT) PadAction.LEFT else PadAction.RIGHT); compose.waitForIdle()
        assertFalse(app.tv.favoritePreview); assertEquals("a", app.session.selectedMediaId)
        panel.windowModel.reverse(); compose.waitForIdle()
        val rows = tvFavoriteRows(listOf(data.byId.getValue("a")), app.tv.store.channels, "", panel.windowModel.sort.value, panel.windowModel.descending.value, false, "ALL")
        assertEquals(rows.first() is TvFavoriteRow.Channel, app.tv.favoritePreview)
    }

    @Test fun firstTvEntryRequiresAnExplicitAcknowledgementAndRemembersIt() {
        start(acknowledgeTv = false)
        runBlocking { app.preferences.update { it.copy(tvEnabled = true, tvInNavbar = true) } }
        panel.windowModel.go("TV"); compose.waitForIdle()
        compose.onNodeWithTag("tv-authorized-use").assertExists()
        compose.onNodeWithTag("tv-authorized-continue").assertIsNotEnabled()
        assertFalse(app.tv.store.acknowledged)
        panel.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B))
        panel.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_B)); compose.waitForIdle()
        assertEquals("VIDEO", panel.windowModel.route.value.tab)
        assertFalse(app.tv.store.acknowledged)
        panel.windowModel.go("TV"); compose.waitForIdle()
        compose.onNodeWithTag("tv-authorized-check").performClick()
        compose.onNodeWithTag("tv-authorized-continue").assertIsEnabled().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("tv-authorized-use").assertDoesNotExist()
        compose.onNodeWithTag("tv-personal-sources-notice").assertExists()
        app.tv.store.acknowledgeSources(); app.tv.noticeVisible = false
        compose.waitForIdle()
        assertTrue(TvStore(app).acknowledged)
        panel.windowModel.go("PHOTO"); panel.windowModel.go("TV"); compose.waitForIdle()
        compose.onNodeWithTag("tv-authorized-use").assertDoesNotExist()
    }

    @Test fun tvRootBackStaysInTheNavbarButReturnsToVideoWhenItIsContextual() {
        start()
        runBlocking { app.preferences.update { it.copy(tvEnabled = true, tvInNavbar = true) } }
        panel.windowModel.go("TV"); compose.waitForIdle()
        panel.onBackPressedDispatcher.onBackPressed(); compose.waitForIdle()
        assertEquals("TV", panel.windowModel.route.value.tab)
        app.tv.browsing = true; compose.waitForIdle()
        panel.onBackPressedDispatcher.onBackPressed(); compose.waitForIdle()
        assertFalse(app.tv.browsing); assertEquals("TV", panel.windowModel.route.value.tab)
        runBlocking { app.preferences.update { it.copy(tvInNavbar = false) } }; compose.waitForIdle()
        panel.onBackPressedDispatcher.onBackPressed(); compose.waitForIdle()
        assertEquals("VIDEO", panel.windowModel.route.value.tab)
    }

    @Test fun tvCurrentChannelTogglesPauseAndBackClosesThePausedPlayerBeforeNavigating() {
        start(kind = "VIDEO")
        val entry = TvCatalogEntry("https://example.com/paused.m3u8", "Current channel", null, null, null)
        runBlocking { app.preferences.update { it.copy(tvEnabled = true, tvInNavbar = false) }; app.tv.store.set(entry, true) }
        app.video.play(app.catalog.snapshot.value.byId.getValue("a"), panel.slot, false, resume = false)
        val live = org.robolectric.util.ReflectionHelpers.getField<MutableState<TvCatalogEntry?>>(app.video, "liveChannel\$delegate")
        live.value = entry
        panel.windowModel.go("TV"); compose.waitForIdle()
        val player = checkNotNull(app.video.player)
        // The local fixture has no live network decoder; establish the running-channel state.
        org.robolectric.util.ReflectionHelpers.getField<MutableState<String?>>(app.video, "error\$delegate").value = null
        player.play()
        assertTrue(player.playWhenReady)
        app.tv.play(entry, panel.slot); assertFalse(player.playWhenReady)
        app.tv.play(entry, panel.slot); assertTrue(player.playWhenReady)
        app.tv.play(entry, panel.slot); assertFalse(player.playWhenReady)
        panel.onBackPressedDispatcher.onBackPressed(); compose.waitForIdle()
        assertNull(app.video.player); assertNull(app.video.liveChannel)
        assertEquals("TV", panel.windowModel.route.value.tab)
        panel.onBackPressedDispatcher.onBackPressed(); compose.waitForIdle()
        assertEquals("VIDEO", panel.windowModel.route.value.tab)
    }

    @Test fun tvReorderContinuesFollowingTheFingerDuringAutomaticScrolling() {
        start()
        runBlocking {
            app.preferences.update { it.copy(tvEnabled = true) }
            (1..40).forEach { app.tv.store.set(TvCatalogEntry("https://example.com/$it.m3u8", "Channel $it", null, null, null), true) }
            app.tv.store.grid(false)
        }
        panel.windowModel.go("TV"); compose.waitForIdle()
        val before = app.tv.store.channels.toList()
        val root = compose.onNodeWithTag("tv-channel-layout").fetchSemanticsNode().boundsInRoot
        val origin = compose.onNodeWithTag("tv-channel:${before[0].id}").fetchSemanticsNode().boundsInRoot.center - root.topLeft
        val visible = compose.onAllNodes(SemanticsMatcher("channel") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("tv-channel:") == true }).fetchSemanticsNodes().size
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("tv-channel-layout").performTouchInput { down(origin) }
        compose.mainClock.advanceTimeBy(650)
        compose.onNodeWithTag("tv-channel-layout").performTouchInput { advanceEventTime(650); moveTo(origin); moveTo(Offset(origin.x, root.height - 28f), delayMillis = 200) }
        repeat(30) { compose.mainClock.advanceTimeBy(40); shadowOf(Looper.getMainLooper()).idle() }
        compose.onNodeWithTag("tv-channel-layout").performTouchInput { up() }
        compose.mainClock.autoAdvance = true; compose.waitForIdle()
        compose.waitUntil(5000) { app.tv.store.channels.map { it.id } != before.map { it.id } }
        assertTrue(app.tv.store.channels.indexOfFirst { it.id == before[0].id } >= visible)
    }

    @Test fun tvOverlayFollowsTheCurrentPlayerAndSleepReleasesItsLiveConnection() {
        start(kind = "VIDEO")
        runBlocking { app.preferences.update { it.copy(tvEnabled = true) } }
        fun live(channel: TvCatalogEntry, id: String) {
            app.video.play(app.catalog.snapshot.value.byId.getValue(id), panel.slot, false)
            val state = org.robolectric.util.ReflectionHelpers.getField<MutableState<TvCatalogEntry?>>(app.video, "liveChannel\$delegate")
            state.value = channel
        }
        val first = TvCatalogEntry("https://example.com/a.m3u8", "First", null, null, null)
        val second = first.copy(streamUrl = "https://example.com/b.m3u8", name = "Second")
        live(first, "a")
        val prefs = app.preferences.state.value
        panel.setContent { GalleryDesign(prefs) { TvVideoPreview(panel) } }
        compose.waitForIdle()
        fun find(view: View): GalleryVideoView? {
            if (view is GalleryVideoView) return view
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        compose.runOnIdle { checkNotNull(find(panel.window.decorView)).showController() }
        compose.waitForIdle(); compose.onNodeWithTag("tv-close-player").assertExists()
        compose.runOnIdle { live(second, "b") }; compose.waitForIdle()
        compose.onNodeWithTag("tv-close-player").assertDoesNotExist()
        compose.runOnIdle { checkNotNull(find(panel.window.decorView)).showController() }
        compose.waitForIdle(); compose.onNodeWithTag("tv-close-player").assertExists()
        compose.runOnIdle { app.tv.pauseForSleep() }; compose.waitForIdle()
        assertNull(app.video.player); assertEquals(second, app.tv.sleepResume)
    }

    @Test fun tvStarRequiresConfirmationAndOutsideNavigationAbandonsAllChannelRequests() {
        start()
        val channel = TvCatalogEntry("https://example.com/a.m3u8", "News", null, null, null)
        runBlocking { app.preferences.update { it.copy(tvEnabled = true) }; app.tv.store.favorite(channel, true) }
        panel.windowModel.go("TV"); compose.waitForIdle()
        app.tv.requestFavorite(channel); compose.waitForIdle()
        compose.onNodeWithTag("tv-favorite-dialog").assertExists()
        assertEquals(1, app.tv.store.channels.size)
        panel.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B))
        panel.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_B)); compose.waitForIdle()
        compose.onNodeWithTag("tv-favorite-dialog").assertDoesNotExist()
        app.tv.requestFavorite(channel); compose.waitForIdle()
        panel.windowModel.go("PHOTO"); compose.waitForIdle()
        assertNull(app.tv.pendingFavorite)
        app.tv.pendingPlay = channel to panel.slot
        panel.windowModel.go("VIDEO"); compose.waitForIdle()
        assertNull(app.tv.pendingPlay)
        panel.windowModel.go("TV"); compose.waitForIdle()
        app.tv.requestFavorite(channel); compose.waitForIdle()
        val confirmModal = checkNotNull(panel.controllerModal)
        confirmModal.key(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A))
        confirmModal.key(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A)); compose.waitForIdle()
        compose.waitUntil(5000) { app.tv.store.channels.singleOrNull()?.favorite == false }
        assertEquals(1, app.tv.store.channels.size)
    }

    @Test fun tvUnsavedChannelOptionsAddImmediatelyWithoutConfirmation() {
        start()
        val channel = TvCatalogEntry("https://example.com/a.m3u8", "Public channel", null, null, null)
        runBlocking { app.preferences.update { it.copy(tvEnabled = true) } }
        panel.windowModel.go("TV"); compose.waitForIdle()
        app.tv.selected = channel
        panel.padAction!!.invoke(PadAction.SETTINGS); compose.waitForIdle()
        compose.waitUntil(5000) { app.tv.store.channels.singleOrNull()?.favorite == true }
        compose.onNodeWithTag("tv-favorite-dialog").assertDoesNotExist()
        assertNull(app.tv.pendingFavorite)
    }

    @Test fun tvConfirmationClosesAndShoulderNavigationStillChangesTheCategory() {
        start()
        runBlocking { app.preferences.update { it.copy(tvEnabled = true, tvInNavbar = true) } }
        panel.windowModel.go("TV"); compose.waitForIdle()
        val entry = TvCatalogEntry("https://example.com/a.m3u8", "Public", null, null, null)
        runBlocking { app.tv.store.favorite(entry, true) }
        app.tv.requestFavorite(entry); compose.waitForIdle()
        val modal = checkNotNull(panel.controllerModal)
        modal.key(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_R1))
        modal.key(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_R1)); compose.waitForIdle()
        assertNull(app.tv.pendingFavorite)
        assertEquals("FAVORITE", panel.windowModel.route.value.tab)
    }

    @Test fun tvGridFitsFiveChannelsAndBrowseViewCanBeChangedWithR3() {
        start()
        runBlocking {
            app.preferences.update { it.copy(tvEnabled = true) }
            (1..10).forEach { app.tv.store.set(TvCatalogEntry("https://example.com/$it.m3u8", "Channel $it", null, null, null), true) }
            app.tv.store.grid(true)
        }
        panel.windowModel.go("TV"); compose.waitForIdle()
        val rows = app.tv.store.channels
        val first = compose.onNodeWithTag("tv-channel:${rows[0].id}").fetchSemanticsNode().boundsInRoot
        val fifth = compose.onNodeWithTag("tv-channel:${rows[4].id}").fetchSemanticsNode().boundsInRoot
        assertEquals(first.top, fifth.top, 1f)
        assertTrue(fifth.left > first.left)
        app.tv.browsing = true; compose.waitForIdle()
        compose.onNodeWithTag("tv-view").assertExists()
        panel.padAction!!.invoke(PadAction.TOGGLE_VIEW); compose.waitForIdle()
        assertFalse(app.tv.store.grid)
    }

    @Test fun returningToTvKeepsTheTouchedChannelInsteadOfTheFirstFocusableRow() {
        start()
        runBlocking {
            app.preferences.update { it.copy(tvEnabled = true) }
            (1..4).forEach { app.tv.store.set(TvCatalogEntry("https://example.com/return-$it.m3u8", "Return $it", null, null, null), true) }
            app.tv.store.grid(false)
        }
        panel.windowModel.go("TV"); compose.waitForIdle()
        val target = app.tv.store.channels[2].entry
        compose.onNodeWithTag("tv-channel:${tvKey(target)}").performClick()
        compose.waitForIdle()
        assertEquals(target, app.tv.selected)
        panel.windowModel.go("PHOTO"); compose.waitForIdle()
        panel.windowModel.go("TV"); compose.waitForIdle()
        assertEquals(target, app.tv.selected)
        compose.onNodeWithTag("tv-channel:${tvKey(target)}").assertIsDisplayed()
    }

    @Test fun tvChannelSelectionFollowsExternalChannelChangesAndBrowsingUsesFourColumns() {
        start()
        runBlocking {
            app.preferences.update { it.copy(tvEnabled = true) }
            (1..30).forEach { app.tv.store.set(TvCatalogEntry("https://example.com/follow-$it.m3u8", "Follow $it", null, null, null), true) }
            app.tv.store.grid(false)
        }
        panel.windowModel.go("TV"); compose.waitForIdle()
        val rows = app.tv.store.channels
        panel.enterPadMode?.invoke()
        app.tv.select(rows.last().entry); compose.waitForIdle()
        compose.onNodeWithTag("tv-channel:${rows.last().id}").assertIsDisplayed().assertIsFocused()
        app.tv.browsing = true
        runBlocking { app.tv.store.grid(true) }
        panel.setContent { TvChannelCollection(panel, rows.map { it.entry }, false, play = {}) }
        app.tv.select(rows.first().entry); compose.waitForIdle()
        val first = compose.onNodeWithTag("tv-channel:${rows[0].id}").fetchSemanticsNode().boundsInRoot
        val fourth = compose.onNodeWithTag("tv-channel:${rows[3].id}").fetchSemanticsNode().boundsInRoot
        val fifth = compose.onNodeWithTag("tv-channel:${rows[4].id}").fetchSemanticsNode().boundsInRoot
        assertEquals(first.top, fourth.top, 1f)
        assertTrue(fifth.top > first.top)
    }

    @Test fun tvListKeepsLogosIndependentFromCustomBackgroundsAndPlacesThemBeforeTheStar() {
        start()
        val rows = (1..4).map { i -> TvCatalogEntry("https://example.com/logos-$i.m3u8", "Channel $i", null,
            if (i <= 2) "https://example.com/logo-$i.png" else null, null) }
        runBlocking {
            app.preferences.update { it.copy(tvEnabled = true) }
            app.tv.store.grid(false)
            rows.forEachIndexed { index, entry -> app.tv.store.customize(entry, null, if (index in 1..2) "test-cover.png" else null) }
        }
        panel.windowModel.go("TV"); compose.waitForIdle()
        rows.forEachIndexed { i, entry ->
            val id = tvKey(entry)
            if (i <= 1) compose.onNodeWithTag("tv-list-logo:$id", useUnmergedTree = true).assertExists() else compose.onNodeWithTag("tv-list-logo:$id", useUnmergedTree = true).assertDoesNotExist()
            if (i in 1..2) compose.onNodeWithTag("tv-list-thumb:$id", useUnmergedTree = true).assertExists() else compose.onNodeWithTag("tv-list-thumb:$id", useUnmergedTree = true).assertDoesNotExist()
            if (i < 3) {
                val art = compose.onNodeWithTag("tv-list-artwork:$id", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                val star = compose.onNodeWithTag("tv-star:$id", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                assertTrue(art.width > 0f && art.height > 0f)
                assertTrue(art.right <= star.left)
            } else compose.onNodeWithTag("tv-list-artwork:$id", useUnmergedTree = true).assertDoesNotExist()
        }
    }

    @Test fun tvCachedOriginalLogoRemainsVisibleAboveACustomBackgroundInListAndGrid() {
        start()
        val entry = TvCatalogEntry("https://example.com/cached-logo.m3u8", "Cached logo", null, null, null)
        runBlocking { app.tvCacheReady.await() }
        val originals = File(app.filesDir, "tv-user-artwork").apply { mkdirs() }
        val thumbs = File(app.cacheDir, "tv-thumbnails").apply { mkdirs() }
        fun png(file: File, color: Int) {
            val image = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
        }
        val custom = "aaaaaaaa-1111.png"
        png(File(originals, custom), android.graphics.Color.RED)
        png(File(thumbs, tvKey(entry) + ".png"), android.graphics.Color.GREEN)
        runBlocking {
            app.preferences.update { it.copy(tvEnabled = true) }
            app.tv.store.customize(entry, null, custom)
            app.tv.store.grid(false)
        }
        panel.windowModel.go("TV"); compose.waitForIdle()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("tv-list-logo:${tvKey(entry)}", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        fun logoIsDrawn(): Boolean {
            shadowOf(Looper.getMainLooper()).idle()
            val view = panel.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            return try {
                view.draw(Canvas(bitmap))
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                pixels.any { it == android.graphics.Color.GREEN }
            } finally { bitmap.recycle() }
        }
        compose.waitUntil(5000) { logoIsDrawn() }
        runBlocking { app.tv.store.grid(true) }; compose.waitForIdle()
        compose.waitUntil(5000) { logoIsDrawn() }
    }

    @Test fun tvCacheClearRemovesOnlyTemporaryLogosAndKeepsCustomArtwork() {
        val logos = File(app.cacheDir, "tv-thumbnails").apply { mkdirs() }
        runBlocking { app.tvCacheReady.await() }
        val logo = File(logos, tvDigest("channel") + ".png").apply { writeBytes(ByteArray(20)) }
        val originals = File(app.filesDir, "tv-user-artwork").apply { mkdirs() }
        val original = File(originals, java.util.UUID.randomUUID().toString() + ".png").apply { writeBytes(ByteArray(20)) }
        var done = false
        app.catalog.scope.launch { app.cache.clear(allowGame = true); done = true }
        compose.waitUntil(5000) { shadowOf(Looper.getMainLooper()).idle(); done }
        assertFalse(logo.exists()); assertTrue(original.exists())
    }

    @Test fun mixedFavoritesClearTvPreviewWhenTheSelectedFilterHasNoChannels() {
        start()
        val channel = TvCatalogEntry("https://example.com/live.m3u8", "News", null, null, null)
        runBlocking {
            app.preferences.update { it.copy(tvEnabled = true, tvInMediaFavorites = true) }
            app.tv.store.favorite(channel, true)
        }
        panel.windowModel.go("FAVORITE"); compose.waitForIdle()
        compose.waitUntil(5000) { app.tv.favoritePreview }
        panel.windowModel.filter("IMAGE"); compose.waitForIdle()
        compose.waitUntil(5000) { !app.tv.favoritePreview }
        panel.windowModel.filter("ALL"); compose.waitForIdle()
        compose.waitUntil(5000) { app.tv.favoritePreview }
        panel.windowModel.search("no matching channel"); compose.waitForIdle()
        compose.waitUntil(5000) { !app.tv.favoritePreview }
    }

    @Test fun cancellingTvDraftRemovesOnlyItsUnsavedArtwork() {
        start()
        val channel = TvCatalogEntry("https://example.com/live.m3u8", "News", null, null, null)
        val directory = File(app.filesDir, "tv-user-artwork").apply { mkdirs() }
        val draft = File(directory, java.util.UUID.randomUUID().toString() + ".png").apply { writeText("draft") }
        runBlocking { app.tv.store.set(channel, true) }
        app.tv.beginDraft(channel); app.tv.draftArtwork = draft.name; app.tv.closeDraft()
        compose.waitUntil(5000) { !draft.exists() }
        val saved = File(directory, java.util.UUID.randomUUID().toString() + ".png").apply { writeText("saved") }
        runBlocking { app.tv.store.customize(channel, null, saved.name) }
        app.tv.beginDraft(channel); app.tv.closeDraft()
        runBlocking { app.tv.store.discardArtwork(setOf(saved.name)) }
        assertTrue(saved.exists())
    }

    @Test fun tvEntryIsOptInAndUsesTheExistingEmptyGalleryWithoutStartingPlayback() {
        start()
        panel.windowModel.go("VIDEO"); compose.waitForIdle()
        compose.onNodeWithTag("video-open-tv").assertDoesNotExist()
        runBlocking { app.preferences.update { it.copy(tvEnabled = true) } }
        compose.waitForIdle()
        compose.onNodeWithTag("video-open-tv").performClick()
        compose.waitForIdle()
        assertEquals("TV", panel.windowModel.route.value.tab)
        compose.onNodeWithTag("tv-add").assertDoesNotExist()
        compose.onNodeWithTag("empty-add-folder").assertIsDisplayed()
        assertEquals("sources", app.tv.group)
        assertNull(app.video.player)
        assertNull(app.music.state.value.id)
        capture("tv-empty-channels")
        panel.onBackPressedDispatcher.onBackPressed(); compose.waitForIdle()
        assertEquals("VIDEO", panel.windowModel.route.value.tab)
    }

    @Test fun tvRowFocusAndRemappedSettingsNeverInvokeLocalFileActionsOrPlayback() {
        start()
        val channel = TvCatalogEntry("https://example.com/live.m3u8", "Demo channel", "demo", null, "News")
        runBlocking {
            app.preferences.update { it.copy(tvEnabled = true) }
            app.tv.store.set(channel, true)
        }
        panel.windowModel.go("TV"); compose.waitForIdle()
        compose.onNodeWithTag("tv-channel:${tvKey(channel)}").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        compose.waitForIdle()
        assertEquals(channel, app.tv.selected)
        assertNull(app.video.player)
        panel.padAction!!.invoke(PadAction.SETTINGS); compose.waitForIdle()
        compose.onNodeWithText("Modifica canale").assertExists()
        assertNull(app.session.mediaOptions)
        assertTrue(panel.windowModel.selectedAlbums.value.isEmpty())
        compose.onNodeWithText("Annulla").performClick(); compose.waitForIdle()
        val previewPreferences = app.preferences.state.value
        panel.setContent { GalleryDesign(previewPreferences) { TvScreen(panel, preview = true) } }
        compose.waitForIdle()
        compose.onNodeWithText("Guarda ora").assertExists()
        assertNull(app.video.player)
        capture("tv-channel-preview")
    }

    @Test fun tvBrowserUsesOneSearchRowAndRestoresKeyboardLayoutWhenLeaving() {
        start()
        runBlocking { app.preferences.update { it.copy(tvEnabled = true) } }
        val originalMode = panel.window.attributes.softInputMode
        panel.windowModel.go("TV"); compose.waitForIdle()
        app.tv.group = "sources"
        app.tv.browsing = true
        compose.waitForIdle()
        compose.onNodeWithTag("tv-group-selector").assertDoesNotExist()
        compose.onNodeWithTag("tv-source-add").assertExists()
        compose.onNodeWithTag("tv-search").assertExists().performTextInput("rakuten")
        compose.onNodeWithTag("tv-add").assertDoesNotExist()
        compose.onNodeWithText("Sfoglia canali").assertDoesNotExist()
        assertEquals(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING,
            panel.window.attributes.softInputMode and android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
        panel.windowModel.go("VIDEO"); compose.waitForIdle()
        compose.waitUntil(5000) { panel.window.attributes.softInputMode == originalMode }
        assertEquals(originalMode, panel.window.attributes.softInputMode)
        assertNull(app.video.player)
    }

    @Test fun tvChannelStepsFollowTheSavedListAndWrapWithoutPlayingOnSelection() {
        start()
        val first = TvCatalogEntry("https://example.com/a.m3u8", "A", null, null, null)
        val second = TvCatalogEntry("https://example.com/b.m3u8", "B", null, null, null)
        runBlocking { app.tv.store.set(first, true); app.tv.store.set(second, true) }
        val rows = app.tv.store.channels.sortedByDescending { it.savedAt }.map { it.entry }
        assertEquals(rows[1], app.tv.adjacentChannel(rows[0], 1))
        assertEquals(rows[1], app.tv.adjacentChannel(rows[0], -1))
        assertEquals(rows[0], app.tv.adjacentChannel(rows[1], 1))
        assertNull(app.tv.adjacentChannel(first.copy(streamUrl = "https://example.com/unknown.m3u8"), 1))
        assertNull(app.video.player)
    }

    @Test fun tvSavedChannelsNavigateByControllerAndKeepFocusAcrossGridChanges() {
        start()
        runBlocking {
            app.preferences.update { it.copy(tvEnabled = true) }
            (1..4).forEach { app.tv.store.set(TvCatalogEntry("https://example.com/$it.m3u8", "Channel $it", null, null, null), true) }
        }
        panel.windowModel.go("TV"); compose.waitForIdle()
        val rows = app.tv.store.channels.map { it.entry }
        app.tv.selected = rows.first()
        panel.moveFocus!!.invoke(PadAction.DOWN); compose.waitForIdle()
        compose.waitUntil(5000) { app.tv.selected == rows[1] }
        compose.onNodeWithTag("tv-view").performClick(); compose.waitForIdle()
        assertTrue(app.tv.store.grid)
        panel.moveFocus!!.invoke(PadAction.RIGHT); compose.waitForIdle()
        compose.waitUntil(5000) { app.tv.selected == rows[2] }
        panel.padAction!!.invoke(PadAction.TOGGLE_VIEW); compose.waitForIdle()
        assertFalse(app.tv.store.grid)
        assertNull(app.video.player)
        panel.padAction!!.invoke(PadAction.SETTINGS); compose.waitForIdle()
        compose.onNodeWithTag("tv-channel-dialog").assertExists()
        compose.onNodeWithText("Scegli miniatura").performClick(); compose.waitForIdle()
        assertEquals("TV", panel.windowModel.route.value.tab)
        val picker = shadowOf(panel).nextStartedActivityForResult
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, picker.intent.action)
        shadowOf(panel).receiveResult(picker.intent, android.app.Activity.RESULT_CANCELED, null)
        compose.waitForIdle()
        compose.onNodeWithTag("tv-channel-dialog").assertExists()
    }

    @Test fun tvChannelLongPressReordersWithoutStartingPlayback() {
        start()
        runBlocking {
            app.preferences.update { it.copy(tvEnabled = true) }
            (1..4).forEach { app.tv.store.set(TvCatalogEntry("https://example.com/$it.m3u8", "Channel $it", null, null, null), true) }
        }
        panel.windowModel.go("TV"); compose.waitForIdle()
        val before = app.tv.store.channels.toList()
        val root = compose.onNodeWithTag("tv-channel-layout").fetchSemanticsNode().boundsInRoot
        val origin = compose.onNodeWithTag("tv-channel:${tvKey(before[0].entry)}").fetchSemanticsNode().boundsInRoot.center - root.topLeft
        val destination = compose.onNodeWithTag("tv-channel:${tvKey(before[2].entry)}").fetchSemanticsNode().boundsInRoot.center - root.topLeft
        compose.onNodeWithTag("tv-channel-layout").performTouchInput { down(origin) }
        compose.mainClock.advanceTimeBy(650)
        compose.onNodeWithTag("tv-drag-preview").assertExists()
        compose.onNodeWithTag("tv-channel-layout").performTouchInput { advanceEventTime(650); moveTo(origin); moveTo(destination, delayMillis = 200) }
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag("tv-drag-preview").assertExists()
        compose.onNodeWithTag("tv-channel-layout").performTouchInput { up() }
        compose.waitForIdle()
        compose.waitUntil(5000) { app.tv.store.channels.map { it.id } != before.map { it.id } }
        assertEquals(listOf(before[1].id, before[2].id, before[0].id, before[3].id), app.tv.store.channels.map { it.id })
        assertNull(app.video.player)
    }

    @Test fun tvArtworkPickerImportsAndReturnsToTheSameDraftAfterExternalNavigation() {
        val bitmap = Bitmap.createBitmap(128, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
        val encoded = java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        start(imageBytes = encoded)
        val channel = TvCatalogEntry("https://example.com/live.m3u8", "Original", null, null, null)
        runBlocking { app.preferences.update { it.copy(tvEnabled = true) }; app.tv.store.set(channel, true) }
        panel.windowModel.go("TV"); compose.waitForIdle()
        app.tv.selected = channel; app.tv.editSelected(); compose.waitForIdle()
        compose.onNodeWithTag("tv-channel-name").performTextReplacement("My channel")
        compose.onNodeWithText("Scegli miniatura").performClick(); compose.waitForIdle()
        val picker = shadowOf(panel).nextStartedActivityForResult
        assertTrue(app.tv.artworkPickerPending)
        panel.windowModel.go("PHOTO"); compose.waitForIdle()
        val image = app.catalog.snapshot.value.byId.getValue("a")
        shadowOf(panel).receiveResult(picker.intent, android.app.Activity.RESULT_OK, Intent().setData(app.catalog.uri(image)))
        compose.waitForIdle()
        compose.waitUntil(5000) { shadowOf(Looper.getMainLooper()).idle(); !app.tv.artworkPickerPending }
        compose.waitForIdle()
        assertEquals("TV", panel.windowModel.route.value.tab)
        assertEquals("My channel", app.tv.draftName)
        assertNotNull(app.tv.draftArtwork)
        compose.onNodeWithTag("tv-channel-dialog").assertExists()
        compose.onNodeWithTag("tv-edit-artwork").assertExists()
        compose.onNodeWithTag("tv-choose-artwork").assertDoesNotExist()
        val cancel = compose.onNodeWithText("Annulla").fetchSemanticsNode().boundsInRoot
        val save = compose.onNodeWithText("Salva").fetchSemanticsNode().boundsInRoot
        assertTrue(cancel.left < save.left)
        compose.onNodeWithText("Salva").performClick(); compose.waitForIdle()
        compose.waitUntil(5000) { app.tv.store.channels.single().artwork != null }
        assertEquals("My channel", app.tv.store.channels.single().name)
        assertNotNull(runBlocking { app.tv.image(channel) })
    }

    @Test fun tvRequestsConfirmationBeforeReplacingAnActiveLocalVideo() {
        start(kind = "VIDEO")
        runBlocking { app.preferences.update { it.copy(tvEnabled = true) } }
        val video = app.catalog.snapshot.value.byId.getValue("a")
        app.video.play(video, panel.slot, false)
        val originalPlayer = app.video.player
        app.video.playing.value = true
        val channel = TvCatalogEntry("https://example.com/live.m3u8", "Channel", null, null, null)
        app.tv.play(channel, panel.slot)
        assertEquals(channel, app.tv.pendingPlay?.first)
        assertSame(originalPlayer, app.video.player)
        assertNull(app.video.liveLoadingChannel)
    }

    @Test fun tvNavbarOptionRemovesTheContextualEntryAndRetainsTheOtherCategories() {
        start()
        runBlocking { app.preferences.update { it.copy(tvEnabled = true, tvInNavbar = true) } }
        panel.windowModel.go("VIDEO"); compose.waitForIdle()
        compose.onNodeWithTag("video-open-tv").assertDoesNotExist()
        compose.onNodeWithTag("navbar:TV").assertExists().performClick()
        compose.waitForIdle()
        assertEquals("TV", panel.windowModel.route.value.tab)
        runBlocking { app.preferences.update { it.copy(tvEnabled = false) } }
        compose.waitForIdle()
        compose.onNodeWithTag("navbar:TV").assertDoesNotExist()
        assertEquals("ALBUM", panel.windowModel.route.value.tab)
        assertNull(app.video.player)
    }

    @Test fun sharedSwapGestureSurvivesFourPaneRoleChanges() {
        val base = TouchSettings()
        val settings = base.copy(threeFinger = base.threeFinger + (PadContext.LIBRARY to (base.threeFinger.getValue(PadContext.LIBRARY) + (TouchDirection.UP to TouchAction.SWAP))))
        var swaps by mutableIntStateOf(0)
        start()
        panel.setContent {
            val context = if (swaps % 2 == 0) PadContext.LIBRARY else PadContext.VIEWER
            Box(androidx.compose.ui.Modifier.size(600.dp).galleryTouch(settings, context, true, "target:$swaps") { if (it == TouchAction.SWAP) swaps++ }.testTag("swap-area"))
        }
        repeat(4) { count ->
            compose.onNodeWithTag("swap-area").performTouchInput {
                for (i in 0..2) down(i, center + Offset((i - 1) * 30f, 60f))
                for (i in 0..2) updatePointerTo(i, center + Offset((i - 1) * 30f, -80f))
                move(100); up(0); up(1); up(2)
            }
            compose.waitForIdle(); assertEquals(count + 1, swaps)
        }
    }

    @Test fun imageOptionsOpenImagePlaybackSettings() { displayOptionsRoute("IMAGE", "IMAGE") }
    @Test fun videoOptionsOpenVideoPlaybackSettings() { displayOptionsRoute("VIDEO", "VIDEO") }
    @Test fun musicOptionsOpenMusicPlaybackSettings() { displayOptionsRoute("AUDIO", "MUSIC") }
    private fun displayOptionsRoute(kind: String, category: String) {
        start(kind = kind)
        app.session.mediaOptions = app.catalog.snapshot.value.byId.getValue("a"); compose.waitForIdle()
        compose.onNodeWithTag("media-display-options").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals("PLAYBACK", app.session.settingsSection)
        assertEquals(category, app.session.playbackSettingsCategory)
        assertEquals("SETTINGS", panel.windowModel.route.value.tab)
        assertNull(app.session.mediaOptions)
        compose.onNodeWithTag("playback-options:$category").assertExists()
    }
    @Test fun albumCoverActionIsAvailableFromTheAlbumSection() {
        start()
        app.session.mediaOptions = app.catalog.snapshot.value.byId.getValue("a"); compose.waitForIdle()
        compose.onNodeWithText("Imposta come copertina").assertDoesNotExist()
        app.session.mediaOptions = null
        panel.windowModel.go("ALBUM"); compose.waitForIdle()
        app.session.mediaOptions = app.catalog.snapshot.value.byId.getValue("a"); compose.waitForIdle()
        compose.onNodeWithText("Imposta come copertina").assertExists()
    }
    @Test fun gifCounterDependsOnTheCountButTheFrameSelectorRemainsAvailable() {
        start()
        app.session.settingsSection = "PLAYBACK"
        app.session.playbackSettingsCategory = "IMAGE"
        panel.windowModel.go("SETTINGS"); compose.waitForIdle()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("Numero di fotogrammi GIF"))
        compose.onNodeWithText("Numero di fotogrammi GIF").performClick(); compose.waitForIdle()
        compose.onNodeWithText("Contatore del fotogramma corrente").assertDoesNotExist()
        compose.onNodeWithText("Selettore dei fotogrammi").assertExists().performClick()
        compose.onNodeWithText("Selettore dei fotogrammi").assertIsOn()
        compose.onNodeWithText("Numero di fotogrammi GIF").performClick(); compose.waitForIdle()
        compose.onNodeWithText("Contatore del fotogramma corrente").assertExists()
        compose.onNodeWithText("Selettore dei fotogrammi").assertIsOn()
        assertTrue(app.preferences.state.value.gifFrameCount)
    }
    @Test fun nearestFilterChangesImmediatelyWithoutResettingPreviewTransform() {
        start()
        runBlocking { app.preferences.update { it.copy(photoNavigator = true) } }
        panel.windowModel.open("a"); compose.waitForIdle()
        compose.runOnIdle { panel.padTransform!!.invoke(PadAction.ZOOM_IN, "a") }
        compose.waitForIdle()
        compose.onNodeWithTag("photo-navigator").assertExists()
        compose.onNodeWithContentDescription("Opzioni del media").performClick()
        compose.onNodeWithTag("preview-nearest-filter").performScrollTo().performClick()
        compose.waitUntil(5000) { shadowOf(Looper.getMainLooper()).idle(); app.preferences.state.value.previewNearest }
        compose.onNodeWithTag("photo-navigator").assertExists()
        assertEquals(androidx.compose.ui.graphics.FilterQuality.None, previewFilter(app.preferences.state.value))
    }
    @Test fun rightStickStartsZoomBeforeTheNavigatorExistsAndNeutralStickKeepsNavigationAvailable() {
        start()
        val data = app.catalog.snapshot.value
        val prefs = app.preferences.state.value.copy(photoNavigator = true)
        panel.setContent { GalleryDesign(prefs) { MediaViewer(panel, data.byId.getValue("a"), data, prefs, onActions = {}) } }
        compose.waitForIdle()
        compose.onNodeWithTag("photo-navigator").assertDoesNotExist()
        compose.runOnIdle { assertFalse(panel.imageSticks!!.invoke(.8f, 0f, 0f, 0f, SystemClock.uptimeMillis())) }
        compose.runOnIdle { assertTrue(panel.imageSticks!!.invoke(0f, 0f, 0f, -1f, SystemClock.uptimeMillis() + 30)) }
        compose.waitForIdle()
        compose.onNodeWithTag("photo-navigator").assertExists()
    }
    @Test fun changingAlbumSizeCompletesSelection() {
        start()
        runBlocking { app.catalog.dao.saveAlbumSources(listOf(AlbumSource("album", "source", 1))) }
        panel.windowModel.go("ALBUM"); compose.waitForIdle()
        panel.windowModel.selectAlbums(setOf("album")); panel.windowModel.albumMenu(true)
        compose.waitForIdle()
        compose.onNodeWithText("2×").performClick()
        compose.waitForIdle()
        assertEquals(4, app.preferences.albumSizes.value["album"])
        assertTrue(panel.windowModel.selectedAlbums.value.isEmpty())
        assertFalse(panel.windowModel.albumMenu.value)
    }
    @Test fun comparisonGifStaysAnimatedAndTapShowsItsCloseControl() {
        start(kind = "GIF", imageBytes = javaClass.getResourceAsStream("/gif/three-frames.gif")!!.use { it.readBytes() })
        runBlocking { app.preferences.update { it.copy(comparisonEnabled = true, previewNearest = true) } }
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true)
        val service = Robolectric.buildService(FloatingImageService::class.java).create()
        try {
            service.get().onStartCommand(Intent(app, FloatingImageService::class.java).putExtra("media_id", "a"), 0, 1)
            fun windows() = org.robolectric.util.ReflectionHelpers.getField<Map<String, Any>>(service.get(), "windows")
            compose.waitUntil(8000) { shadowOf(Looper.getMainLooper()).idle(); windows().values.firstOrNull()?.let { org.robolectric.util.ReflectionHelpers.getField<Boolean>(it, "attached") } == true }
            val window = windows().getValue("a")
            val image = org.robolectric.util.ReflectionHelpers.getField<android.widget.ImageView>(window, "image")
            assertTrue("GIF comparison must retain its animated drawable", image.drawable is android.graphics.drawable.Animatable)
            assertTrue(image.drawable is NearestComparisonDrawable)
            val filtered = image.drawable as NearestComparisonDrawable
            assertSame("Animated frame invalidations must reach the ImageView", filtered, filtered.source.callback)
            runBlocking { app.preferences.update { it.copy(previewNearest = false) } }
            compose.waitForIdle()
            assertFalse(image.drawable is NearestComparisonDrawable)
            assertTrue(image.drawable is android.graphics.drawable.Animatable)
            val root = org.robolectric.util.ReflectionHelpers.getField<android.widget.FrameLayout>(window, "view")
            val layout = org.robolectric.util.ReflectionHelpers.getField<android.view.WindowManager.LayoutParams>(window, "layout")
            val originalWidth = layout.width
            fun dragBy(dx: Float) {
                val now = SystemClock.uptimeMillis()
                listOf(android.view.MotionEvent.ACTION_DOWN to 20f, android.view.MotionEvent.ACTION_MOVE to (20f + dx), android.view.MotionEvent.ACTION_UP to (20f + dx)).forEachIndexed { index, (action, x) ->
                    val event = android.view.MotionEvent.obtain(now, now + index * 100, action, x, 20f, 0)
                    root.dispatchTouchEvent(event); event.recycle()
                }
            }
            val screen = android.graphics.Point()
            @Suppress("DEPRECATION")
            panel.windowManager.defaultDisplay.getRealSize(screen)
            val target = (screen.x - originalWidth - 16).coerceAtLeast(0)
            dragBy((target - layout.x).toFloat())
            assertEquals("Comparison must move across the whole display", target, layout.x)
            dragBy((-layout.width - layout.x).toFloat())
            assertEquals(View.GONE, image.visibility)
            assertTrue(layout.width < originalWidth)
            assertEquals("Left tab must touch the display edge", 0, layout.x)
            assertEquals(View.LAYOUT_DIRECTION_RTL, root.getChildAt(3).layoutDirection)
            root.performClick()
            assertEquals(View.VISIBLE, image.visibility)
            assertEquals(originalWidth, layout.width)
            val close = root.getChildAt(1)
            assertEquals(View.GONE, close.visibility)
            root.performClick(); assertEquals(View.VISIBLE, close.visibility)
            val resize = root.getChildAt(2)
            assertEquals(View.VISIBLE, resize.visibility)
            val right = layout.x + layout.width
            val oldWidth = layout.width
            val now = SystemClock.uptimeMillis()
            listOf(android.view.MotionEvent.ACTION_DOWN to 10f, android.view.MotionEvent.ACTION_MOVE to 50f,
                android.view.MotionEvent.ACTION_UP to 50f).forEachIndexed { index, (action, x) ->
                val event = android.view.MotionEvent.obtain(now, now + index * 100, action, x, 10f, 0)
                resize.dispatchTouchEvent(event); event.recycle()
            }
            assertTrue(layout.width < oldWidth)
            assertEquals("Corner resize keeps the opposite edge anchored", right, layout.x + layout.width)
            root.performClick(); assertEquals(View.GONE, close.visibility)
            assertEquals(View.GONE, resize.visibility)
            root.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
            assertEquals("Comparison controls hide after inactivity", View.GONE, close.visibility)
            assertEquals(View.GONE, resize.visibility)
            root.performClick(); close.performClick()
            assertTrue(windows().isEmpty())
        } finally { service.destroy(); org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(false) }
    }
    @Test fun floatingComparisonsHaveTwoWindowsAndCloseWhenTheirAlbumIsHidden() {
        start(total = 3)
        runBlocking { app.preferences.update { it.copy(comparisonEnabled = true) } }
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true)
        val service = Robolectric.buildService(FloatingImageService::class.java).create()
        fun windows() = org.robolectric.util.ReflectionHelpers.getField<Map<String, Any>>(service.get(), "windows")
        fun open(id: String) { service.get().onStartCommand(Intent(app, FloatingImageService::class.java).putExtra("media_id", id), 0, 1) }
        try {
            open("a"); open("b"); open("zz0")
            assertEquals(setOf("a", "b"), windows().keys)
            compose.waitUntil(8000) { shadowOf(Looper.getMainLooper()).idle(); windows().size == 2 && windows().values.all { org.robolectric.util.ReflectionHelpers.getField<Boolean>(it, "attached") } }
            open("a"); assertEquals(setOf("b"), windows().keys)
            runBlocking {
                app.catalog.dao.saveAlbums(listOf(app.catalog.dao.albums().single().copy(hidden = true)))
            }
            compose.waitUntil(5000) { shadowOf(Looper.getMainLooper()).idle(); windows().isEmpty() }
        } finally { service.destroy(); org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(false) }
    }
    @Test fun comparisonNeverRequestsOverlayPermissionForAVideo() {
        start(kind = "VIDEO")
        runBlocking { app.preferences.update { it.copy(comparisonEnabled = true) } }
        FloatingImageService.open(panel, "a")
        assertNull(app.session.floatingImageRequestId)
        assertThrows(IllegalArgumentException::class.java) { app.session.togglePreviewPin("a") }
    }
    @Test fun openingAnotherImageRequiresRemovingThePreviewPin() {
        start()
        runBlocking { app.preferences.update { it.copy(previewPinEnabled = true) } }
        app.session.togglePreviewPin("a")
        compose.waitForIdle()
        compose.onNodeWithTag("media:b").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("preview-unpin-confirm").assertIsDisplayed()
        assertEquals("a", app.session.pinnedPreviewId)
        compose.onNodeWithTag("preview-unpin-cancel").performClick()
        compose.waitForIdle()
        assertEquals("a", app.session.pinnedPreviewId)
        compose.onNodeWithTag("media:b").performClick()
        compose.onNodeWithTag("preview-unpin-confirm").performClick()
        compose.waitForIdle()
        assertNull(app.session.pinnedPreviewId)
        assertEquals("b", app.session.selectedMediaId)
    }
    @Test fun legacyFtpPreferenceDoesNotAddANavbarShortcut() {
        start()
        runBlocking { app.preferences.update { it.copy(ftpEnabled = true) } }
        compose.waitForIdle()
        compose.onAllNodesWithTag("navbar:FTP").assertCountEquals(0)
        runBlocking { app.preferences.update { it.copy(ftpEnabled = false) } }
        compose.waitForIdle()
        compose.onAllNodesWithTag("navbar:FTP").assertCountEquals(0)
        assertEquals("PHOTO", panel.windowModel.route.value.tab)
    }
    @Test fun customColorDialogUsesSideBySideControlsAndCopiesAValidHexValue() {
        start()
        var draft by mutableStateOf(app.preferences.state.value.copy(themeId = GalleryThemes.CUSTOM,
            customTheme = CustomTheme.from(GalleryThemes.get(app.preferences.state.value))))
        panel.setContent { GalleryDesign(draft) { CustomThemeEditor(draft) { draft = it } } }
        compose.onNodeWithTag("custom-color:0").performClick()
        compose.onNodeWithTag("custom-hue-wheel").assertIsDisplayed()
        val wheel = compose.onNodeWithTag("custom-hue-wheel").fetchSemanticsNode().boundsInRoot
        val saturation = compose.onNodeWithTag("custom-saturation").fetchSemanticsNode().boundsInRoot
        assertTrue("The wheel belongs to the left of the adjustments", wheel.right < saturation.left)
        compose.onNodeWithTag("custom-hex").performTextReplacement("#12")
        compose.onNodeWithText("Fine").assertIsNotEnabled()
        compose.onNodeWithTag("custom-copy-hex").assertIsNotEnabled()
        compose.onNodeWithTag("custom-hex").performTextReplacement("#12abef")
        compose.onNodeWithTag("custom-copy-hex").assertIsEnabled().performClick()
        assertEquals("#12ABEF", app.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
        compose.onNodeWithText("Fine").performClick()
        assertEquals("#12ABEF", draft.customTheme!!.background)
    }
    @Test fun keyboardFocusOnVisibleLastRowRevealsTheTrailingGap() {
        start(total = 30)
        app.preferences.galleryView(false, "PHOTO", grid = true, columns = 3)
        compose.waitForIdle()
        val last = panel.windowModel.listing.value.items.last().id
        val grid = compose.onNodeWithTag("library-grid")
        grid.performSemanticsAction(SemanticsActions.ScrollToIndex) { it(29) }
        compose.waitForIdle()
        grid.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -28f) }
        compose.waitForIdle()
        val gridBottom = grid.fetchSemanticsNode().boundsInRoot.bottom
        val before = compose.onNodeWithTag("media:$last").fetchSemanticsNode().boundsInRoot.bottom
        assertTrue("Fixture must reproduce a visible tile with hidden trailing padding", gridBottom - before < 10f)
        compose.runOnIdle { app.session.selectMedia(last); panel.enterPadMode?.invoke() }
        compose.waitForIdle()
        val after = compose.onNodeWithTag("media:$last").fetchSemanticsNode().boundsInRoot.bottom
        assertTrue("Native focus scrolling should leave a small margin above the navbar", gridBottom - after in 8f..14f)
        compose.onNodeWithTag("media:$last").assertIsDisplayed()
        assertEquals(last, app.session.selectedMediaId)
        capture("controller-grid-footer")
    }
    @Test fun keyboardFooterRevealDoesNotMoveAGridThatAlreadyFits() {
        start()
        app.preferences.galleryView(false, "PHOTO", grid = true, columns = 3)
        compose.waitForIdle()
        val before = compose.onNodeWithTag("media:b").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { app.session.selectMedia("b"); panel.enterPadMode?.invoke() }
        compose.waitForIdle()
        assertEquals(before, compose.onNodeWithTag("media:b").fetchSemanticsNode().boundsInRoot)
    }

    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = RuntimeEnvironment.getApplication() as GalleryApp
    private var activity: ActivityController<MainPanelActivity>? = null
    private val panel get() = activity!!.get()
    @After fun close() { activity?.pause()?.stop()?.destroy(); app.onTerminate() }
    private fun start(touch: TouchSettings = TouchSettings(), kind: String = "IMAGE", total: Int = 2, imageBytes: ByteArray? = null, acknowledgeTv: Boolean = true) {
        if (acknowledgeTv) { app.tv.store.acknowledge(); app.tv.store.acknowledgeSources() }
        val provider = PreviewFixtureProvider(imageBytes)
        provider.attachInfo(app, ProviderInfo().apply { authority = "ayngallery.touchui"; exported = false })
        ShadowContentResolver.registerProviderInternal("ayngallery.touchui", provider)
        val tree = DocumentsContract.buildTreeDocumentUri("ayngallery.touchui", "root")
        app.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val mime = when (kind) { "VIDEO" -> "video/mp4"; "AUDIO" -> "audio/mpeg"; else -> "image/png" }
        val media = (listOf("a", "b") + (0 until total - 2).map { "zz$it" }).mapIndexed { index, id ->
            MediaEntity(id, "ayngallery.touchui:$id", DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(), "album", "$id.png", mime, kind, 100, (total - index).toLong()) }
        runBlocking {
            app.preferences.save(GalleryPreferences(themeId = "ayn_purple", reducedMotion = true, touch = touch, viewerZoom = true))
            if (kind == "VIDEO") app.preferences.videoFolderView(false)
            app.catalog.dao.saveSource(SourceEntity("source", tree.toString(), "QA", "MIXED"))
            app.catalog.dao.saveAlbums(listOf(AlbumEntity("album", "ayngallery.touchui:root", DocumentsContract.buildDocumentUriUsingTree(tree, "root").toString(), "QA", "QA", null)))
            app.catalog.dao.saveMedia(media); app.catalog.dao.saveMembership(media.map { MediaSource(it.id, "source", 1) })
            withTimeout(5000) { app.catalog.snapshot.first { it.initialized && it.byId.size == total } }
        }
        shadowOf(app).declareComponentUnbindable(ComponentName(app, MusicService::class.java))
        val launcher = Robolectric.buildActivity(LauncherActivity::class.java).create().get()
        activity = Robolectric.buildActivity(MainPanelActivity::class.java, shadowOf(launcher).nextStartedActivity).setup().visible().windowFocusChanged(true)
        panel.windowModel.go(when (kind) { "VIDEO" -> "VIDEO"; "AUDIO" -> "MUSIC"; else -> "PHOTO" })
        compose.waitUntil(8000) { compose.onAllNodesWithTag("media:a").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }
    @Test fun firstMusicTapOnlySelectsAndSecondTapActivatesTheSelectedSong() {
        start(kind = "AUDIO")
        compose.onNodeWithTag("media:b").performClick()
        compose.waitForIdle()
        assertEquals("b", app.session.selectedMediaId)
        assertEquals("MUSIC", panel.windowModel.route.value.tab)
        assertNull(app.music.state.value.id)
        compose.onNodeWithTag("media:b").performClick()
        compose.waitForIdle()
        assertEquals("PLAYER", panel.windowModel.route.value.tab)
    }
    @Test fun hiddenAlbumZoomAppliesAtRootAndInsideAlbumAndMovesFolderActionLeft() {
        start()
        runBlocking {
            app.catalog.dao.saveAlbumSources(listOf(AlbumSource("album", "source", 1)))
            withTimeout(5000) { app.catalog.snapshot.first { it.topAlbums(app.preferences.state.value).isNotEmpty() } }
            app.preferences.update { it.copy(hiddenTools = it.hiddenTools + "ALBUM:ZOOM") }
        }
        app.preferences.galleryView(true, grid = true)
        panel.windowModel.go("ALBUM")
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription("Ingrandisci album").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Rimpicciolisci album").assertCountEquals(0)
        assertTrue(compose.onNodeWithTag("album-add-folder").fetchSemanticsNode().boundsInRoot.left < 100f)
        panel.windowModel.go("ALBUM", album = "album")
        compose.waitUntil(5000) { compose.onAllNodesWithTag("album-add-folder").fetchSemanticsNodes().size == 1 }
        compose.onAllNodesWithContentDescription("Ingrandisci album").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Rimpicciolisci album").assertCountEquals(0)
        assertTrue(compose.onNodeWithTag("album-add-folder").fetchSemanticsNode().boundsInRoot.left < 100f)
    }
    @Test fun albumShortcutExplainsFolderRootsBeforeOpeningThePicker() {
        start()
        runBlocking {
            app.catalog.dao.saveAlbumSources(listOf(AlbumSource("album", "source", 1)))
            withTimeout(5000) { app.catalog.snapshot.first { it.visibleAlbums().isNotEmpty() } }
        }
        panel.windowModel.go("ALBUM")
        compose.waitForIdle()
        compose.onNodeWithTag("album-add-folder").performClick()
        compose.onNodeWithText("Scegli una cartella da sfogliare. Potrai aggiungerne altre quando vuoi.").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(100); compose.waitForIdle()
        compose.onNodeWithTag("folder-guidance-continue").assertIsFocused()
        compose.onNodeWithTag("folder-guidance-continue").performClick()
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, shadowOf(panel).nextStartedActivityForResult.intent.action)
        assertEquals("VISUAL", panel.windowModel.pendingSourceRole)
    }
    @Test fun emptyMusicAndFavoritePreviewsOfferOnlyRelevantActions() {
        start()
        var chosen = 0
        val prefs = app.preferences.state.value
        panel.setContent { GalleryDesign(prefs) { PreviewMusicPanel(app, PlaybackState(), CatalogSnapshot(), prefs, true, onChooseFolder = { chosen++ }) } }
        compose.onNodeWithText("Non disponi di alcun media per questa categoria.").assertIsDisplayed()
        compose.onNodeWithText("Scegli una cartella").performClick()
        assertEquals(1, chosen)
        panel.setContent { GalleryDesign(prefs) { EmptyCategoryPreview("FAVORITE", prefs) { chosen++ } } }
        compose.onNodeWithText("Non hai ancora aggiunto media ai preferiti.").assertIsDisplayed()
        compose.onNodeWithText("Scegli una cartella").assertDoesNotExist()
        assertEquals(1, chosen)
    }
    @Test fun photoAndVideoHaveRootButtonsAndPhotoFiltersStayHiddenUntilSearch() {
        start()
        compose.onNodeWithTag("media-format-filter").assertDoesNotExist()
        compose.onNodeWithContentDescription("Cerca e filtra").performClick()
        compose.onNodeWithTag("media-format-filter").assertExists()
        compose.onNodeWithTag("photo-add-folder").performClick()
        compose.onNodeWithTag("folder-guidance-continue").performClick()
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, shadowOf(panel).nextStartedActivityForResult.intent.action)
        panel.windowModel.go("VIDEO"); compose.waitForIdle()
        compose.onNodeWithTag("video-add-folder").assertDoesNotExist()
        compose.onNodeWithTag("empty-add-folder").performClick()
        compose.onNodeWithTag("folder-guidance-continue").performClick()
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, shadowOf(panel).nextStartedActivityForResult.intent.action)
        assertEquals("VISUAL", panel.windowModel.pendingSourceRole)
    }
    @Test fun albumRootHasNoExtraCreateTile() {
        start()
        panel.windowModel.go("ALBUM"); compose.waitForIdle()
        compose.onNodeWithTag("new-album-tile").assertDoesNotExist()
        compose.onNodeWithTag("album-add-folder").assertDoesNotExist()
        compose.onNodeWithTag("empty-add-folder").assertExists()
    }
    @Test fun albumActionsCannotDeleteAlbumsEvenWhenDirectDeleteIsEnabled() {
        start()
        runBlocking { app.preferences.update { it.copy(directDeleteEnabled = true) } }
        panel.windowModel.go("ALBUM")
        panel.windowModel.selectAlbums(setOf("album")); panel.windowModel.albumMenu(true)
        compose.waitForIdle()
        compose.onNodeWithText("Nascondi").assertExists()
        compose.onNodeWithText("Elimina", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Sposta nel cestino", substring = true).assertDoesNotExist()
    }
    @Test fun sortControlsAreTouchOnlyUntilExplicitlyEnabled() {
        start()
        compose.onNodeWithTag("library-order").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("library-order").assertIsNotFocused()
        compose.onNodeWithTag("library-reverse").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("library-reverse").assertIsNotFocused()
        runBlocking { app.preferences.update { it.copy(controllerSorting = true) } }
        compose.waitForIdle()
        compose.onNodeWithTag("library-order").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("library-order").assertIsFocused()
    }
    @Test fun changingLibrarySectionClearsAlbumMultiSelection() {
        start()
        panel.windowModel.go("ALBUM")
        panel.windowModel.selectAlbums(setOf("album"))
        assertEquals(setOf("album"), panel.windowModel.selectedAlbums.value)
        panel.windowModel.go("VIDEO")
        assertTrue(panel.windowModel.selectedAlbums.value.isEmpty())
        assertFalse(panel.windowModel.albumMenu.value)
    }
    @Test fun mediaOptionsSetTheCurrentImageAsItsAlbumCover() {
        start()
        val data = app.catalog.snapshot.value
        val prefs = app.preferences.state.value
        panel.setContent { GalleryDesign(prefs) {
            MediaMenu(data.byId.getValue("a"), data, app, { operation -> runBlocking { operation() } }, {}, {}, {}, { _, _ -> }, {}, insideAlbum = true)
        } }
        compose.onNodeWithText("Imposta come copertina").performClick()
        assertEquals("a", runBlocking { app.catalog.readSnapshot() }.albumsById.getValue("album").coverMediaId)
    }
    @Test fun emptyPreviewUsesTheBrandedEmptyStateAndFolderAction() {
        start()
        val prefs = app.preferences.state.value
        val data = app.catalog.snapshot.value
        var requestedRole: SourceRole? = null
        panel.setContent { GalleryDesign(prefs) { MediaViewer(panel, null, data, prefs, onActions = {}, onChooseFolder = { requestedRole = it }) } }
        compose.onNodeWithTag("empty-gallery").assertExists()
        compose.onNodeWithText("Non disponi di alcun media per questa categoria.").assertIsDisplayed()
        compose.onNodeWithText("Scegli una cartella").assertIsDisplayed()
        compose.onNodeWithTag("empty-gallery-card").performClick()
        assertEquals(SourceRole.VISUAL, requestedRole)
    }
    @Test fun playbackExamplesShowEveryCategoryWithoutStartingAPlayer() {
        start()
        val preferences = app.preferences.state.value.copy(gifFrameSelector = true)
        val music = app.music.state.value
        panel.setContent { GalleryDesign(preferences) { PlaybackSettingsPreview(app.session.playbackSettingsCategory, preferences) } }
        for (category in listOf("IMAGE", "VIDEO", "MUSIC")) {
            app.session.playbackSettingsCategory = category
            compose.waitForIdle(); compose.mainClock.advanceTimeBy(400)
            compose.onNodeWithTag("playback-example:$category").assertIsDisplayed()
            compose.onNodeWithTag("playback-example-label").assertIsDisplayed()
            if (category == "IMAGE") {
                compose.onNodeWithTag("example-navigator").assertIsDisplayed()
                compose.onNodeWithTag("example-frame-count").assertIsDisplayed()
                compose.onNodeWithTag("example-filmstrip").assertIsDisplayed()
            }
            assertNull(app.video.player); assertEquals(music, app.music.state.value)
        }
    }
    @Test fun themePreviewShowsRealAlbumsAndNonInteractiveNavbar() {
        start()
        runBlocking { app.catalog.dao.saveAlbumSources(listOf(AlbumSource("album", "source", 1))) }
        val data = runBlocking { app.catalog.readSnapshot() }
        val prefs = app.preferences.state.value
        panel.setContent { SettingsPreviewScreen(app, data, prefs, "THEME") }
        compose.onNodeWithTag("settings-preview").assertExists()
        compose.onNodeWithText("Preview example").assertIsDisplayed()
        compose.onNodeWithTag("settings-preview-album:album").assertIsDisplayed().assert(hasClickAction().not())
        compose.onNodeWithTag("navbar:ALBUM").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("settings-preview-tool:PHOTO:FILTER:${R.drawable.ph_funnel}").assertDoesNotExist()
        compose.onNodeWithTag("settings-preview-swap").assertIsDisplayed()
        panel.setContent { SettingsPreviewScreen(app, data, prefs.copy(hiddenTools = prefs.hiddenTools + "ALBUM:SWAP"), "INTERFACE") }
        compose.onNodeWithTag("settings-preview-swap").assertDoesNotExist()
    }
    @Test fun sortDirectionButtonReversesEachLibraryWithoutOpeningTheSortMenu() = checkReverse("PHOTO", "IMAGE")
    @Test fun albumSortDirectionKeepsFocus() = checkReverse("ALBUM", "IMAGE")
    @Test fun videoSortDirectionKeepsFocus() = checkReverse("VIDEO", "VIDEO")
    @Test fun musicSortDirectionKeepsFocus() = checkReverse("MUSIC", "AUDIO")
    @Test fun favoriteSortDirectionKeepsFocus() = checkReverse("FAVORITE", "IMAGE")
    private fun checkReverse(tab: String, kind: String) {
        start(kind = kind)
        runBlocking {
            if (tab == "FAVORITE") {
                app.catalog.favorite("a")
                withTimeout(5000) { app.catalog.snapshot.first { it.byId["a"]?.favorite == true } }
            }
            app.preferences.update { it.copy(controllerSorting = true) }
        }
        panel.windowModel.go(tab, album = "album".takeIf { tab == "ALBUM" })
        compose.waitUntil(5000) { compose.onAllNodesWithTag("library-reverse").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("library-reverse").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        val before = panel.windowModel.descending.value
        compose.onNodeWithTag("library-reverse").performClick()
        compose.waitForIdle()
        assertEquals(!before, panel.windowModel.descending.value)
        compose.onNodeWithTag("library-reverse").assertIsFocused()
    }
    @Test fun reverseKeepsArrowFocusAndSelectsFirstOrderedMedia() {
        start(kind = "AUDIO")
        panel.windowModel.mediaGrid(false)
        runBlocking { app.preferences.update { it.copy(controllerSorting = true) } }
        compose.waitForIdle()
        compose.onNodeWithTag("library-reverse").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("library-reverse").assertIsFocused()
        repeat(2) {
            compose.onNodeWithTag("library-reverse").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("library-reverse").assertIsFocused()
            assertEquals(panel.windowModel.listing.value.items.first().id, app.session.selectedMediaId)
        }
        panel.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))
        panel.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN))
        compose.waitForIdle()
        compose.onNodeWithTag("library-reverse").assertIsNotFocused()
    }
    @Test fun themeBackgroundPickerBrowsesAlbumsThenFiveColumnImagesWithoutSearch() {
        start()
        runBlocking { app.catalog.dao.saveAlbumSources(listOf(AlbumSource("album", "source", 1))) }
        val data = runBlocking { app.catalog.readSnapshot() }
        val prefs = app.preferences.state.value
        var chosen: List<String>? = null
        panel.setContent { GalleryDesign(prefs) {
            LocalMediaPicker(app, data, prefs, "background", "Background", "MEDIA", emptySet(), true,
                onSave = { chosen = it }, done = {}, dismiss = {}, browseAlbums = true)
        } }
        compose.onNodeWithTag("collection-media-search").assertDoesNotExist()
        compose.onNodeWithTag("picker-album:album").performClick()
        pump { compose.onAllNodesWithTag("collection-choose:a").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("collection-choose:a").assertIsDisplayed().performClick()
        compose.onNodeWithTag("collection-add-save").performClick()
        pump { chosen == listOf("a") }
    }
    @Test fun controllerOffersResumeChoiceForAnAlreadyStartedVideo() {
        start(kind = "VIDEO")
        runBlocking { app.catalog.dao.saveMedia(listOf(app.catalog.snapshot.value.byId.getValue("a").copy(videoPosition = 65000, videoPositionAt = System.currentTimeMillis()))) }
        pump { app.catalog.snapshot.value.byId.getValue("a").videoPosition == 65000L }
        viewer()
        assertTrue(panel.videoControllerAction!!.invoke(PadAction.CONFIRM))
        compose.waitForIdle()
        assertNull("A must ask before resuming the saved video", app.video.player)
        compose.onNodeWithTag("video-resume").assertExists()
    }
    @Test fun aPreviouslyPlayedVideoPromptsBeforeStartingAndCanDismissWithoutPlayback() {
        start(kind = "VIDEO")
        runBlocking { app.catalog.dao.saveMedia(listOf(app.catalog.snapshot.value.byId.getValue("a").copy(videoPosition = 65000, videoPositionAt = System.currentTimeMillis()))) }
        pump { app.catalog.snapshot.value.byId.getValue("a").videoPosition == 65000L }
        viewer()
        compose.onNodeWithTag("video-play").performClick()
        compose.onNodeWithTag("video-resume").assertExists()
        compose.onNodeWithTag("video-restart").assertExists()
        compose.onNodeWithTag("video-resume-cancel").assertExists()
        compose.onNodeWithText("Controller:", substring = true).assertDoesNotExist()
        val buttons = listOf("video-resume", "video-restart", "video-resume-cancel").map {
            compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot
        }
        assertEquals(buttons[0].left, buttons[1].left, 1f)
        assertEquals(buttons[0].width, buttons[2].width, 1f)
        assertTrue(buttons[0].bottom < buttons[1].top && buttons[1].bottom < buttons[2].top)
        compose.onNodeWithText("1:05", substring = true).assertExists()
        capture("video-resume-actions")
        assertNull(app.video.player)
        panel.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B))
        panel.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_B))
        compose.waitForIdle(); compose.onNodeWithTag("video-resume").assertDoesNotExist()
        assertNull(app.video.player)
    }
    @Test fun outgoingPhotoGridDoesNotScrollToAnotherSectionsSelection() {
        start(total = 30)
        val data = app.catalog.snapshot.value
        val prefs = app.preferences.state.value
        var activeScene by mutableStateOf(true)
        panel.setContent { GalleryDesign(prefs) {
            LibraryGrid(panel, GalleryRoute(tab = "PHOTO"), data, data.media, prefs, false, emptySet(), {}, {}, activeScene = activeScene)
        } }
        compose.onNodeWithTag("media:a").assertIsDisplayed()
        val before = compose.onNodeWithTag("media:a").fetchSemanticsNode().boundsInRoot
        activeScene = false
        app.session.selectMedia("zz27")
        compose.waitForIdle()
        compose.onNodeWithTag("media:a").assertIsDisplayed()
        assertEquals(before, compose.onNodeWithTag("media:a").fetchSemanticsNode().boundsInRoot)
    }
    @Test fun photoToAlbumFadeKeepsTheOutgoingGridInPlace() {
        start(total = 30)
        runBlocking {
            app.catalog.dao.saveAlbumSources(listOf(AlbumSource("album", "source", 1)))
            withTimeout(5000) { app.catalog.snapshot.first { it.topAlbums(app.preferences.state.value).isNotEmpty() } }
            app.preferences.update { it.copy(reducedMotion = false) }
        }
        compose.waitForIdle()
        val before = compose.onNodeWithTag("media:a").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { panel.windowModel.go("ALBUM") }
            compose.mainClock.advanceTimeBy(48)
            compose.runOnIdle { app.session.selectMedia("zz27") }
            compose.mainClock.advanceTimeBy(32)
            capture("photo-to-album-fade")
            compose.onNodeWithTag("media:a").assertIsDisplayed()
            assertEquals(before, compose.onNodeWithTag("media:a").fetchSemanticsNode().boundsInRoot)
        } finally {
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
        }
    }
    @Test fun initialPreviewIgnoresRememberedPhotoUnlessPersistenceIsEnabled() {
        start()
        val data = app.catalog.snapshot.value
        app.session.selectMedia("b")
        assertEquals("a", initialPreviewId(data, "b", persist = false, category = "PHOTO"))
        assertEquals("b", initialPreviewId(data, "b", persist = true, category = "PHOTO"))
    }
    @Test fun emptyVideoPanelShowsFolderGuidanceBeforeOpeningAndroidPicker() {
        start()
        panel.windowModel.go("VIDEO")
        compose.waitUntil(8000) { compose.onAllNodesWithTag("empty-add-folder").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("video-add-folder").assertDoesNotExist()
        compose.onNodeWithTag("empty-add-folder").performClick()
        compose.onNodeWithTag("folder-guidance-continue").assertIsDisplayed()
        compose.onNodeWithTag("folder-guidance-continue").performClick()
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, shadowOf(panel).nextStartedActivityForResult.intent.action)
        assertEquals("VISUAL", panel.windowModel.pendingSourceRole)
    }
    @Test fun selectedAlbumChoosesItsCoverFromContainedPhotos() {
        start()
        runBlocking { app.catalog.dao.saveAlbumSources(listOf(AlbumSource("album", "source", 1))) }
        panel.windowModel.go("ALBUM"); compose.waitForIdle()
        panel.windowModel.selectAlbums(setOf("album")); compose.waitForIdle()
        compose.onNodeWithContentDescription("Seleziona tutti").assertIsDisplayed()
        compose.onNodeWithContentDescription("Deseleziona tutti").assertIsDisplayed()
        compose.onNodeWithContentDescription("Opzioni album selezionati").performClick()
        compose.onNodeWithText("Seleziona tutti").assertDoesNotExist()
        compose.onNodeWithText("Imposta come copertina").performClick()
        pump { compose.onAllNodesWithTag("collection-choose:b").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("collection-choose:b").performClick()
        compose.onNodeWithTag("collection-add-save").performClick()
        pump { app.catalog.snapshot.value.albumsById["album"]?.coverMediaId == "b" }
        assertTrue(panel.windowModel.selectedAlbums.value.isEmpty())
    }
    @Test fun yOpensActionsForTheFocusedAlbumOrMedia() {
        start()
        panel.windowModel.go("ALBUM"); compose.waitForIdle()
        app.session.previewAlbum("album")
        panel.padAction?.invoke(PadAction.SETTINGS)
        compose.waitForIdle()
        assertTrue(panel.windowModel.albumMenu.value)
        compose.onNodeWithText("Imposta come copertina").assertIsDisplayed()
        panel.windowModel.albumMenu(false); panel.windowModel.selectAlbums(emptySet())
        panel.windowModel.go("PHOTO"); compose.waitForIdle()
        compose.onNodeWithTag("media:b").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.waitForIdle()
        panel.padAction?.invoke(PadAction.SETTINGS)
        compose.waitForIdle()
        assertEquals("PHOTO", panel.windowModel.route.value.tab)
        compose.onNodeWithTag("preview-actions").assertExists()
        compose.onNodeWithText("b.png").assertIsDisplayed()
    }
    @Test fun downAtTheLastMusicRowClampsToTheFinalSong() {
        start(kind = "AUDIO", total = 5)
        panel.windowModel.mediaGrid(true); panel.windowModel.mediaColumns(3)
        compose.waitForIdle()
        app.session.selectMedia("zz0"); compose.waitForIdle()
        panel.moveFocus?.invoke(PadAction.DOWN); compose.waitForIdle()
        assertEquals("zz2", app.session.selectedMediaId)
        panel.moveFocus?.invoke(PadAction.DOWN); compose.waitForIdle()
        assertEquals("zz2", app.session.selectedMediaId)
    }
    @Test fun trashSelectionShowsPreviewAndBothConfirmationsPreserveFilesUntilAccepted() {
        start()
        val data = app.catalog.snapshot.value
        val now = System.currentTimeMillis()
        val records = data.media.map { media ->
            val id = java.util.UUID.randomUUID().toString()
            TrashEntry(media.id, id, "$id.bin", media.uri, media.albumId, media.name, media.mime, media.size,
                "0".repeat(64), now, now + java.util.concurrent.TimeUnit.DAYS.toMillis(7))
        }
        runBlocking { records.forEach { app.catalog.dao.saveTrash(it) } }
        pump { app.files.trash.value.size == 2 }
        panel.setContent { GalleryDesign(GalleryPreferences()) { TrashScreen(app, data, {}) } }
        compose.onNodeWithTag("trash-item:a").performClick()
        compose.onNodeWithTag("trash-item:b").performClick()
        compose.onNodeWithText("2 elementi selezionati").assertIsDisplayed()
        assertEquals("b", app.session.trashPreviewId)
        compose.onNodeWithTag("trash-delete-selected").performClick()
        compose.onNodeWithText("Eliminare definitivamente 2 file?").assertIsDisplayed()
        compose.onNodeWithText("Annulla").performClick()
        compose.onNodeWithTag("trash-restore-selected").performClick()
        compose.onNodeWithText("Ripristinare 2 file?").assertIsDisplayed()
        compose.onNodeWithText("Annulla").performClick()
        assertTrue(app.files.operations.value.isEmpty())
        assertEquals(2, app.files.trash.value.size)
        assertEquals(7L, trashDaysRemaining(records.first(), now))
        assertEquals(0L, trashDaysRemaining(records.first(), now + java.util.concurrent.TimeUnit.DAYS.toMillis(8)))
        panel.setContent { GalleryDesign(GalleryPreferences()) { TrashPreviewScreen(app) } }
        compose.onNodeWithTag("trash-preview:b").assertIsDisplayed()
    }
    @Test fun categoryStripKeepsOneActiveAndUpdatesPreviewNavbar() {
        start()
        compose.onNodeWithContentDescription("Impostazioni").performClick()
        compose.onNodeWithTag("settings-category:INTERFACE").performClick()
        listOf("PHOTO", "ALBUM", "VIDEO", "MUSIC").forEach { key -> compose.onNodeWithTag("category-toggle:$key").performClick() }
        compose.onNodeWithTag("category-toggle:FAVORITE").performClick()
        assertEquals(setOf("PHOTO", "ALBUM", "VIDEO", "MUSIC"), app.session.settingsDraft!!.disabledCategories)
        compose.onNodeWithTag("category-toggle:FAVORITE").assertIsOn()
        compose.onNodeWithTag("settings-save").performClick()
        pump { app.preferences.state.value.disabledCategories.size == 4 }
        panel.onBackPressedDispatcher.onBackPressed(); compose.waitForIdle()
        panel.onBackPressedDispatcher.onBackPressed(); compose.waitForIdle()
        pump { panel.windowModel.route.value.tab == "FAVORITE" }
        compose.onNodeWithTag("navbar:PHOTO").assertDoesNotExist()
        compose.onNodeWithTag("navbar:FAVORITE").assertIsDisplayed()
    }
    @Test fun playbackCoverAndIdleChoicesApplyOnlyAfterSaving() {
        start()
        compose.onNodeWithContentDescription("Impostazioni").performClick()
        compose.onNodeWithTag("settings-category:PLAYBACK").performClick()
        compose.onNodeWithTag("playback-tab:MUSIC").performClick()
        compose.onNodeWithTag("music-cover-choice").performScrollTo()
        compose.onNodeWithTag("music-cover-choice").performClick()
        compose.onNodeWithTag("music-cover-choice:2").performClick()
        assertEquals(0, app.preferences.musicCoverEffect.value)
        compose.onNodeWithTag("playback-tab:VIDEO").assertIsDisplayed()
        compose.onNodeWithTag("playback-tab:VIDEO").performClick()
        compose.onNodeWithTag("video-auto-dim").performScrollTo()
        compose.onNodeWithTag("video-auto-dim").performClick()
        compose.onNodeWithTag("video-auto-dim:5").performClick()
        assertEquals(0, app.preferences.state.value.videoDimSeconds)
        compose.onNodeWithTag("settings-save").performClick()
        pump { app.preferences.state.value.musicCoverEffect == 2 && app.preferences.state.value.videoDimSeconds == 5 }
        assertEquals(2, app.preferences.musicCoverEffect.value)
    }
    @Test fun settingsNavbarCanReorderWithLongPressDragWithoutSavingImmediately() {
        start()
        compose.onNodeWithContentDescription("Impostazioni").performClick()
        compose.onNodeWithTag("settings-category:INTERFACE").performClick()
        compose.waitForIdle()
        val source = compose.onNodeWithTag("category-toggle:PHOTO").fetchSemanticsNode().boundsInRoot
        val target = compose.onNodeWithTag("category-toggle:ALBUM").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("category-toggle:PHOTO").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveTo(target.center - source.topLeft)
            advanceEventTime(100)
            up()
        }
        compose.waitForIdle()
        assertEquals("ALBUM", app.session.settingsDraft!!.navbarOrder.first())
        assertEquals(NavbarOrder.DEFAULT, app.preferences.state.value.navbarOrder)
    }
    @Test fun enteringAlbumSelectsItsMediaAndLeavingRestoresCoverEvenWithLegacyPersistence() {
        start()
        panel.windowModel.go("ALBUM")
        panel.windowModel.go("ALBUM", album = "album")
        pump { app.session.selectedMediaId == "a" && app.session.visualSelectedThisSession && app.session.mediaContext == listOf("a", "b") }
        app.session.selectMedia("b")
        panel.windowModel.back()
        compose.waitForIdle()
        assertEquals("album", app.session.previewAlbumId)
        assertFalse(app.session.visualSelectedThisSession)
        val snapshot = app.catalog.snapshot.value
        assertNull(sessionVisualPreview(snapshot, app.session.selectedVisualMediaId, app.session.selectedMediaId,
            app.session.visualSelectedThisSession, false, null, null))
        assertNotNull(snapshot.albumsById["album"]?.let(snapshot::cover))
        runBlocking { app.preferences.update { it.copy(persistSelectedMedia = true) } }
        app.session.selectMedia("b")
        panel.windowModel.go("ALBUM", album = "album")
        compose.waitForIdle()
        assertEquals("b", app.session.selectedMediaId)
        panel.windowModel.back()
        compose.waitForIdle()
        assertFalse(app.session.visualSelectedThisSession)
        assertEquals("b", app.session.selectedMediaId)
    }
    @Test fun videoControllerTransportContinuesOntoTheNextMediaAndBackLeavesItsPreviewSelected() {
        start(kind = "VIDEO")
        viewer()
        val control = panel.videoControllerAction!!
        assertTrue(control(PadAction.CONFIRM))
        assertEquals("a", app.video.mediaId)
        assertTrue(app.video.player!!.playWhenReady)
        panel.navigateMedia!!.invoke(1)
        assertEquals("b", app.session.selectedMediaId)
        assertEquals("b", app.video.mediaId)
        assertTrue(app.video.player!!.playWhenReady)
        assertTrue(control(PadAction.CONFIRM))
        assertFalse(app.video.player!!.playWhenReady)
        assertTrue(control(PadAction.CONFIRM))
        assertTrue(app.video.player!!.playWhenReady)
        assertFalse(control(PadAction.BACK))
        assertTrue(app.video.player!!.playWhenReady)
        assertTrue(control(PadAction.CONFIRM))
        assertFalse(app.video.player!!.playWhenReady)
        assertTrue(panel.videoControllerAction!!.invoke(PadAction.BACK))
        assertNull(app.video.player)
        assertEquals("b", app.session.selectedMediaId)
    }
    @Test fun favoritesPreselectTheMostRecentlyAddedInsteadOfThePreviouslyViewedPhoto() {
        start(total = 3)
        runBlocking { app.catalog.dao.favorite("a", true, 100); app.catalog.dao.favorite("b", true, 300) }
        pump { app.catalog.snapshot.value.byId["b"]?.favoriteAddedAt == 300L }
        panel.windowModel.go("FAVORITE")
        try { pump { app.session.selectedMediaId == "b" } } catch (e: Throwable) { throw AssertionError("selection=${app.session.selectedMediaId}; route=${panel.windowModel.route.value}; listing=${panel.windowModel.listing.value}", e) }
        assertEquals("FAVORITE_ADDED", panel.windowModel.sort.value)
        assertTrue(panel.windowModel.descending.value)
    }
    @Test fun launcherIconIsADraftUntilSaveAndBothChoicesShareOneRow() {
        start()
        assertTrue(app.preferences.settingsGrid.value)
        compose.onNodeWithContentDescription("Impostazioni").performClick()
        compose.onNodeWithTag("settings-category:INTERFACE").performClick()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("launcher-icon:DARK"))
        val light = compose.onNodeWithTag("launcher-icon:LIGHT").fetchSemanticsNode().boundsInRoot
        val dark = compose.onNodeWithTag("launcher-icon:DARK").fetchSemanticsNode().boundsInRoot
        assertEquals(light.center.y, dark.center.y, .1f)
        compose.onNodeWithTag("launcher-icon:DARK").performClick()
        assertEquals("LIGHT", LauncherIcon.current(app))
        compose.onNodeWithTag("settings-save").assertIsEnabled().performClick()
        pump { LauncherIcon.current(app) == "DARK" }
        compose.onNodeWithTag("settings-save").assertIsNotEnabled()
    }
    @Test fun settingsGridUsesThreeEqualCellsAndPersistsChoice() {
        start()
        compose.onNodeWithContentDescription("Impostazioni").performClick()
        if (!app.preferences.settingsGrid.value) compose.onNodeWithTag("settings-view-toggle").performClick()
        compose.waitForIdle()
        val interfaceBounds = compose.onNodeWithTag("settings-category:INTERFACE").fetchSemanticsNode().boundsInRoot
        val playbackBounds = compose.onNodeWithTag("settings-category:PLAYBACK").fetchSemanticsNode().boundsInRoot
        val soundsBounds = compose.onNodeWithTag("settings-category:SOUNDS").fetchSemanticsNode().boundsInRoot
        assertEquals(interfaceBounds.top, playbackBounds.top)
        assertEquals(interfaceBounds.top, soundsBounds.top)
        assertEquals(interfaceBounds.height, playbackBounds.height)
        assertEquals(interfaceBounds.height, soundsBounds.height)
        assertTrue(PreferenceStore(app).settingsGrid.value)
        compose.onNodeWithTag("settings-view-toggle").performClick()
        assertFalse(PreferenceStore(app).settingsGrid.value)
    }
    @Test fun navigatorTapMovesZoomAndHidesWithOverlay() {
        start(touch = TouchSettings(doubleTap = TouchAction.NONE))
        runBlocking { app.preferences.update { it.copy(photoNavigator = true) } }
        viewer()
        repeat(5) { compose.onNodeWithText("+").performClick() }
        fun position() = compose.onNodeWithTag("media-surface:viewer").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        val before = position()
        compose.onNodeWithTag("photo-navigator").performTouchInput { click(Offset(width * .8f, height * .5f)) }
        compose.waitForIdle()
        assertNotEquals(before, position())
        compose.onNodeWithTag("photo-navigator").assertIsDisplayed()
        compose.onNodeWithTag("photo-navigator").performTouchInput { down(Offset(width * .2f, height * .5f)) }
        compose.waitForIdle()
        val pressed = position()
        compose.onNodeWithTag("photo-navigator").performTouchInput { moveTo(Offset(width * .7f, height * .5f)) }
        compose.waitForIdle()
        assertNotEquals("Dragging must update before lifting the finger", pressed, position())
        val dragged = position()
        compose.onNodeWithTag("photo-navigator").performTouchInput { up() }
        compose.waitForIdle()
        assertEquals("Release must not jump to another position", dragged, position())
        compose.onNodeWithTag("media-surface:viewer").performTouchInput { click(center) }
        compose.waitForIdle()
        compose.onNodeWithTag("photo-navigator").assertDoesNotExist()
        compose.onNodeWithTag("media-surface:viewer").performTouchInput { click(center) }
        compose.waitForIdle()
        compose.onNodeWithTag("photo-navigator").assertIsDisplayed()
    }
    @Test fun emptyInterfaceCategoryKeepsToolsEditableAndExplainsMissingPreview() {
        start()
        app.session.settingsToolsSection = "VIDEO"
        val data = app.catalog.snapshot.value
        val prefs = app.preferences.state.value
        assertFalse(interfaceCategoryHasMedia(data, "VIDEO"))
        panel.setContent { GalleryDesign(prefs) {
            SectionToolsSettings("VIDEO", prefs) {}
        } }
        compose.onNodeWithTag("tool:VIDEO:VIEW").assertIsEnabled()
        panel.setContent { SettingsPreviewScreen(app, data, prefs, "INTERFACE") }
        compose.onNodeWithTag("settings-preview-unavailable").assertIsDisplayed()
        compose.onNodeWithText("Non disponi di alcun media per questa categoria.").assertIsDisplayed()
    }
    @Test fun customThemeRestoresControllerFocusAfterReplacingPaletteList() {
        start()
        compose.onNodeWithContentDescription("Impostazioni").performClick()
        compose.onNodeWithTag("settings-category:INTERFACE").performClick()
        compose.onNodeWithTag("settings-category:THEME").performClick()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("custom-theme-open"))
        compose.onNodeWithTag("custom-theme-open").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("custom-theme-open").performClick()
        compose.waitForIdle()
        panel.ensurePadFocus!!.invoke()
        compose.waitForIdle()
        assertTrue(compose.onAllNodes(isFocused()).fetchSemanticsNodes().isNotEmpty())
        fun press(code: Int) {
            val time = SystemClock.uptimeMillis()
            panel.dispatchKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, code, 0).apply { source = android.view.InputDevice.SOURCE_GAMEPAD })
            panel.dispatchKeyEvent(KeyEvent(time, time + 20, KeyEvent.ACTION_UP, code, 0).apply { source = android.view.InputDevice.SOURCE_GAMEPAD })
            compose.waitForIdle()
        }
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_BUTTON_A)
        compose.onNodeWithTag("custom-hue-wheel").assertIsDisplayed()
    }
    @Test fun discardDialogControllerStartsOnKeepAndCanSelectDiscard() {
        start()
        var discarded = false
        val preferences = app.preferences.state.value
        panel.setContent { GalleryDesign(preferences) { SettingsDiscardDialog({}, { discarded = true }) } }
        compose.waitForIdle()
        compose.onNodeWithTag("discard-keep").assertIsFocused()
        fun press(code: Int) {
            val time = SystemClock.uptimeMillis()
            panel.controllerModal!!.key(KeyEvent(time, time, KeyEvent.ACTION_DOWN, code, 0).apply { source = android.view.InputDevice.SOURCE_GAMEPAD })
            panel.controllerModal!!.key(KeyEvent(time, time + 20, KeyEvent.ACTION_UP, code, 0).apply { source = android.view.InputDevice.SOURCE_GAMEPAD })
            compose.waitForIdle()
        }
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithTag("discard-confirm").assertIsFocused()
        press(KeyEvent.KEYCODE_BUTTON_A)
        assertTrue(discarded)
    }
    @Test fun navbarRejectsControllerFocusButStillAcceptsTouch() {
        start()
        compose.onNodeWithTag("navbar:VIDEO").performSemanticsAction(SemanticsActions.RequestFocus) { assertFalse(it()) }
        compose.onNodeWithTag("navbar:VIDEO").assertIsNotFocused()
        compose.onNodeWithTag("navbar:VIDEO").performTouchInput { click(center) }
        assertEquals("VIDEO", panel.windowModel.route.value.tab)
    }
    @Test fun favoritesKeepTileBoundsAfterLoadingAndFocusChanges() {
        start()
        runBlocking { app.catalog.dao.saveMedia(app.catalog.snapshot.value.byId.values.map { it.copy(favorite = true) }) }
        pump { app.catalog.snapshot.value.byId.values.all { it.favorite } }
        panel.windowModel.go("FAVORITE")
        compose.waitForIdle()
        pump { compose.onAllNodesWithTag("media:a").fetchSemanticsNodes().isNotEmpty() && compose.onAllNodesWithTag("media:b").fetchSemanticsNodes().isNotEmpty() }
        fun bounds() = listOf("a", "b").map { compose.onNodeWithTag("media:$it").fetchSemanticsNode().boundsInRoot }
        val original = bounds()
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        assertEquals(original, bounds())
        compose.onNodeWithTag("media:b").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.waitForIdle()
        assertEquals(original, bounds())
    }
    @Test fun previewFavoriteTouchKeepsOverlayVisible() {
        start(); viewer()
        compose.onNodeWithTag("preview-favorite").performTouchInput { click(center) }
        pump { app.catalog.snapshot.value.byId["a"]?.favorite == true }
        compose.onNodeWithTag("preview-favorite").assertIsDisplayed()
        compose.onNodeWithContentDescription("Opzioni del media").assertIsDisplayed()
    }
    @Test fun musicShoulderButtonsChangeNavbarTabs() {
        start(kind = "AUDIO")
        assertEquals(PadContext.PLAYER, panel.padContext)
        fun press(code: Int) {
            val time = SystemClock.uptimeMillis()
            panel.dispatchKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, code, 0).apply { source = android.view.InputDevice.SOURCE_GAMEPAD })
            panel.dispatchKeyEvent(KeyEvent(time, time + 20, KeyEvent.ACTION_UP, code, 0).apply { source = android.view.InputDevice.SOURCE_GAMEPAD })
            compose.waitForIdle()
        }
        press(KeyEvent.KEYCODE_BUTTON_R1)
        assertEquals("FAVORITE", panel.windowModel.route.value.tab)
        press(KeyEvent.KEYCODE_BUTTON_L1)
        assertEquals("MUSIC", panel.windowModel.route.value.tab)
    }
    @Test fun settingsPreviewUsesGalleryNavbarOnlyForVisualSections() {
        start()
        var section by mutableStateOf("THEME")
        val snapshot = app.catalog.snapshot.value
        val preferences = app.preferences.state.value
        panel.setContent { SettingsPreviewScreen(app, snapshot, preferences, section) }
        compose.waitForIdle()
        compose.onNodeWithTag("settings-preview").assertIsDisplayed()
        compose.onNodeWithText("Preview example").assertIsDisplayed()
        compose.onNodeWithTag("navbar:PHOTO").assertIsDisplayed().assertIsNotEnabled()
        section = "INPUT"
        compose.waitForIdle()
        compose.onNodeWithTag("controller-quick-guide").assertIsDisplayed()
        compose.onNodeWithTag("navbar:PHOTO").assertDoesNotExist()
    }
    @Test fun favoritesUseAMosaicAndKeepMediaSelectionActions() {
        start()
        runBlocking {
            app.catalog.dao.saveMedia(listOf(app.catalog.snapshot.value.byId.getValue("a").copy(kind = "AUDIO", mime = "audio/mpeg", favorite = true),
                app.catalog.snapshot.value.byId.getValue("b").copy(favorite = true)))
        }
        pump { app.catalog.snapshot.value.byId.getValue("a").favorite }
        panel.windowModel.go("FAVORITE")
        compose.waitUntil(8000) { compose.onAllNodesWithTag("favorites-mosaic").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("media:a").assertIsDisplayed()
        compose.onNodeWithTag("media:b").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Opzioni media selezionati").assertIsDisplayed()
    }
    @Test fun colorWheelUsesTheLatestCallbackAfterRecompositionAndSelectsFullHueAndSaturation() {
        start()
        var selected = 0f
        var selectedSaturation = 0f
        var callbackVersion by mutableStateOf(1)
        var observedVersion = 0
        panel.setContent {
            val version = callbackVersion
            GalleryDesign(GalleryPreferences()) {
                HueWheel(0f, 1f, { hue, saturation -> selected = hue; selectedSaturation = saturation; observedVersion = version }, androidx.compose.ui.Modifier.size(236.dp))
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("custom-hue-wheel").performTouchInput { click(Offset(width * .85f, height * .5f)) }
        assertEquals(1, observedVersion)
        callbackVersion = 2; compose.waitForIdle()
        compose.onNodeWithTag("custom-hue-wheel").performTouchInput { click(Offset(width * .5f, height * .85f)) }
        assertEquals(2, observedVersion)
        assertEquals(90f, selected, 2f)
        assertTrue(selectedSaturation in .5f..1f)
        compose.onNodeWithTag("custom-hue-wheel").performTouchInput { click(center) }
        assertEquals(0f, selectedSaturation, .02f)
    }
    @Test fun albumFocusRestoresItsCoverInsteadOfKeepingTheLastChosenPhoto() {
        start()
        app.session.selectMedia("b")
        panel.windowModel.go("ALBUM")
        compose.waitForIdle()
        app.session.previewAlbum("album")
        compose.waitForIdle()
        assertEquals("album", app.session.previewAlbumId)
        assertNull(app.session.selectedVisualMediaId)
        app.session.selectMedia("a")
        assertEquals("a", app.session.selectedVisualMediaId)
    }
    @Test fun restoredPhotoDoesNotCountAsAnExplicitSelectionForAlbumBrowsing() {
        start()
        app.session.selectMedia("b")
        assertTrue(app.session.visualSelectedThisSession)
        val restored = GallerySession(app)
        assertNull(restored.selectedVisualMediaId)
        assertFalse(restored.visualSelectedThisSession)
        restored.previewAlbum("album")
        assertFalse(restored.visualSelectedThisSession)
        restored.selectMedia("b")
        assertTrue(restored.visualSelectedThisSession)
        restored.selectMedia(null)
        assertFalse(restored.visualSelectedThisSession)
    }
    @Test fun previewRotationCyclesAndHidesWithOverlaysWithoutChangingTheDocument() {
        start(); viewer()
        val original = runBlocking { app.catalog.dao.media("a")!! }
        for (angle in listOf("90°", "180°", "270°", "0°")) {
            compose.onNodeWithTag("preview-rotate").performClick()
            compose.waitForIdle()
            assertEquals(angle, compose.onNodeWithTag("preview-rotate").fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        }
        assertEquals(original, runBlocking { app.catalog.dao.media("a")!! })
        compose.onNodeWithTag("media-surface:viewer").performTouchInput { click(center) }
        pump { compose.onAllNodesWithTag("preview-rotate").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("media-surface:viewer").performTouchInput { click(center) }
        pump { compose.onAllNodesWithTag("preview-rotate").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun openedAlbumMenuSwitchesBetweenLockAndPinConfirmedUnlock() {
        start()
        runBlocking { app.catalog.dao.saveAlbumSources(listOf(AlbumSource("album", "source", 1))) }
        panel.windowModel.go("ALBUM")
        pump { compose.onAllNodesWithTag("album:album").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("album:album").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("open-album-menu").performClick()
        compose.onNodeWithText("Blocca").performClick()
        repeat(2) { for (digit in "1234") compose.onNodeWithTag("pin:$digit").performClick() }
        pump { "album" in app.catalog.protection.state.value.protected && panel.windowModel.route.value.album == null }
        pump { app.catalog.snapshot.value.albumLocked("album") }
        compose.onNodeWithTag("album:album").performClick()
        pump { compose.onAllNodesWithTag("album-pin-dialog").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Annulla").performClick()
        compose.waitForIdle()
        assertNull(panel.windowModel.route.value.album)
        compose.onNodeWithTag("album:album").performClick()
        for (digit in "1234") compose.onNodeWithTag("pin:$digit").performClick()
        pump { panel.windowModel.route.value.album == "album" }
        compose.onNodeWithTag("open-album-menu").performClick()
        compose.onNodeWithText("Sblocca").performClick()
        for (digit in "1234") compose.onNodeWithTag("pin:$digit").performClick()
        pump { "album" !in app.catalog.protection.state.value.protected }
        assertEquals("album", panel.windowModel.route.value.album)
    }
    @Test fun emptySectionsHideGridToolsAndPadResizesPopulatedGrid() {
        start()
        val photoColumns = panel.windowModel.mediaColumns("PHOTO")
        val before = photoColumns.value
        panel.padAction!!.invoke(PadAction.GRID_SMALLER)
        pump { photoColumns.value == before + 1 }
        panel.padAction!!.invoke(PadAction.GRID_LARGER)
        pump { photoColumns.value == before }
        panel.windowModel.go("MUSIC")
        pump { compose.onAllNodesWithContentDescription("Vista elenco").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithContentDescription("Ingrandisci album").assertDoesNotExist()
        compose.onNodeWithContentDescription("Rimpicciolisci album").assertDoesNotExist()
        panel.padAction!!.invoke(PadAction.GRID_SMALLER)
        assertEquals(before, photoColumns.value)
    }
    private fun settings() {
        panel.windowModel.go("SETTINGS"); compose.waitForIdle()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-touch"))
        compose.onNodeWithTag("settings-touch").performClick(); compose.waitForIdle()
        assertEquals("TOUCH", panel.windowModel.route.value.tab)
    }
    @Test fun settingsUnifyViewerAndWarnBeforeDiscardingTouchDraft() {
        start()
        compose.onNodeWithContentDescription("Impostazioni").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("settings-category:VIEWER").assertDoesNotExist()
        compose.onNodeWithTag("settings-category:INTERFACE").performClick(); compose.waitForIdle()
        compose.onAllNodes(hasText("Attivo:", substring = true)).assertCountEquals(0)
        compose.onNodeWithText("Numero di fotogrammi GIF").assertDoesNotExist()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("Navigatore quando la foto è ingrandita"))
        compose.onNodeWithText("Navigatore quando la foto è ingrandita").assertIsDisplayed()
        panel.onBackPressedDispatcher.onBackPressed(); compose.waitForIdle()
        compose.onNodeWithTag("settings-hub").performScrollToNode(hasTestTag("settings-category:INPUT"))
        compose.onNodeWithTag("settings-category:INPUT").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("input-tab:TOUCH").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("touch-double").performClick()
        compose.onNodeWithTag("touch-double:NONE").performClick()
        compose.onNodeWithTag("input-tab:PAD").performClick(); compose.waitForIdle()
        compose.onNodeWithText("Modifiche non salvate").assertIsDisplayed()
        compose.onNodeWithText("Continua a modificare").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("touch-double").assertTextContains(TouchAction.NONE.title)
        compose.onNodeWithTag("touch-save").assertIsEnabled().performClick()
        pump { app.preferences.state.value.touch.doubleTap == TouchAction.NONE }
    }
    @Test fun settingsHubStagesLabelsAndRestoresNavigationUntilSave() {
        start()
        panel.windowModel.search("a"); compose.waitForIdle()
        fun interfacePage() {
            compose.onNodeWithContentDescription("Impostazioni").performClick(); compose.waitForIdle()
            compose.onNodeWithTag("settings-category:INTERFACE").performClick(); compose.waitForIdle()
        }
        assertFalse(app.preferences.state.value.navbarLabels)
        interfacePage()
        compose.onNodeWithTag("navbar-labels").performClick(); compose.waitForIdle()
        assertTrue(app.session.settingsDraft!!.navbarLabels)
        assertFalse(app.preferences.state.value.navbarLabels)
        panel.onBackPressedDispatcher.onBackPressed(); compose.waitForIdle()
        compose.onNodeWithText("Modifiche non salvate").assertIsDisplayed()
        compose.onNodeWithText("Esci senza salvare").performClick(); compose.waitForIdle()
        panel.onBackPressedDispatcher.onBackPressed(); compose.waitForIdle()
        assertEquals("PHOTO", panel.windowModel.route.value.tab)
        assertEquals("a", panel.windowModel.query.value)
        assertNull(app.session.settingsOwner)
        interfacePage()
        compose.onNodeWithTag("navbar-labels").performClick()
        compose.onNodeWithTag("settings-save").performClick()
        pump { app.preferences.state.value.navbarLabels }
        panel.onBackPressedDispatcher.onBackPressed(); compose.waitForIdle()
        panel.onBackPressedDispatcher.onBackPressed(); compose.waitForIdle()
        compose.onNodeWithText("Foto").assertExists()
        assertTrue(PreferenceStore(app).state.value.navbarLabels)
    }
    @Test fun interfaceOffersOneSectionAtATimeAndRemovesIneffectiveCompactGrid() {
        start(kind = "VIDEO")
        compose.onNodeWithContentDescription("Impostazioni").performClick()
        compose.onNodeWithTag("settings-category:INTERFACE").performClick()
        compose.onNodeWithText("Griglia compatta").assertDoesNotExist()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("tools-section:VIDEO"))
        compose.onNodeWithTag("tools-section:VIDEO").performClick()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("tool:VIDEO:ZOOM"))
        compose.onNodeWithTag("tool:VIDEO:ZOOM").performClick()
        compose.waitForIdle()
        assertFalse(InterfaceTools.shown(app.session.settingsDraft!!, "VIDEO", "ZOOM"))
        assertTrue(InterfaceTools.shown(app.session.settingsDraft!!, "PHOTO", "ZOOM"))
        assertTrue(InterfaceTools.shown(app.preferences.state.value, "VIDEO", "ZOOM"))
    }
    @Test fun gifLimitsOnlyAppearForVisibleAnimationsAndAdvancedStartsClosed() {
        start()
        compose.onNodeWithContentDescription("Impostazioni").performClick()
        compose.onNodeWithTag("settings-category:PLAYBACK").performClick()
        compose.onNodeWithTag("gif-all-visible").assertDoesNotExist()
        compose.onNodeWithText("Più GIF visibili").performScrollTo()
        compose.onNodeWithText("Più GIF visibili").assertIsDisplayed().performClick(); compose.waitForIdle()
        compose.onNodeWithText("Più GIF visibili").assertIsSelected()
        compose.onNodeWithTag("gif-all-visible").performScrollTo()
        compose.onNodeWithTag("gif-limit").performScrollTo()
        compose.onNodeWithTag("gif-limit").assertExists()
        compose.onNodeWithTag("gif-all-visible").performScrollTo()
        compose.onNodeWithTag("gif-all-visible").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("gif-limit").assertDoesNotExist()
        compose.onNodeWithTag("settings-advanced").assertDoesNotExist()
        compose.onAllNodes(hasText("Cambio foto nella cornice:", substring = true)).assertCountEquals(0)
        compose.onNodeWithText("Copertine musicali").assertDoesNotExist()
    }
    @Test fun infoIncludesLibraryLinksAndPermissionsWithoutAdvancedCopies() {
        start()
        compose.onNodeWithContentDescription("Impostazioni").performClick()
        compose.onNodeWithTag("settings-category:LIBRARY").assertDoesNotExist()
        compose.onNodeWithTag("settings-category:PERMISSIONS").assertDoesNotExist()
        compose.onNodeWithTag("settings-hub").performScrollToNode(hasTestTag("settings-category:INFO"))
        compose.onNodeWithTag("settings-category:INFO").performClick()
        compose.onNodeWithTag("info-support").assertIsDisplayed()
        compose.onNodeWithTag("app-info").performScrollToNode(hasTestTag("settings-tool:SOURCES"))
        compose.onNodeWithTag("settings-tool:SOURCES").assertIsDisplayed()
        compose.onNodeWithTag("settings-tool:COPY_VISUAL").assertDoesNotExist()
        compose.onNodeWithTag("settings-tool:DUPLICATES").assertDoesNotExist()
        compose.onNodeWithTag("settings-advanced").assertDoesNotExist()
        compose.onNodeWithTag("app-info").performScrollToNode(hasTestTag("info-licenses"))
        compose.onNodeWithTag("info-licenses").assertIsDisplayed()
    }
    @Test fun previewPresentationSettingsLiveInInterfaceRatherThanPlayback() {
        start()
        compose.onNodeWithContentDescription("Impostazioni").performClick()
        compose.onNodeWithTag("settings-category:INTERFACE").performClick()
        compose.onNodeWithTag("image-preview-name").assertDoesNotExist()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("grid-preview-motion"))
        compose.onNodeWithTag("grid-preview-motion").assertIsDisplayed()
        app.session.settingsSection = "PLAYBACK"; panel.windowModel.go("SETTINGS"); compose.waitForIdle()
        compose.onNodeWithTag("image-preview-name").performScrollTo()
        compose.onNodeWithTag("image-preview-name").assertIsDisplayed()
    }
    @Test fun deviceAdvancedShowsTransferControlsInline() {
        start()
        compose.onNodeWithContentDescription("Impostazioni").performClick()
        compose.onNodeWithTag("settings-hub").performScrollToNode(hasTestTag("settings-category:DEVICE"))
        compose.onNodeWithTag("settings-category:DEVICE").performClick()
        compose.onNodeWithTag("settings-advanced").assertDoesNotExist()
        compose.onNodeWithTag("settings-tool:TRANSFER").assertDoesNotExist()
        compose.onNodeWithTag("settings-tools").performScrollToNode(hasTestTag("export-settings"))
        compose.onNodeWithTag("export-settings").assertIsDisplayed()
    }
    @Test fun savingInterfaceSoundsDoesNotGetRevertedByAnotherSettingsPage() {
        start()
        compose.onNodeWithContentDescription("Impostazioni").performClick()
        compose.onNodeWithTag("settings-hub").performScrollToNode(hasTestTag("settings-category:SOUNDS"))
        compose.onNodeWithTag("settings-category:SOUNDS").performClick()
        compose.onNodeWithTag("ui-sounds").performClick()
        compose.onNodeWithTag("settings-save").performClick()
        pump { !app.preferences.state.value.uiSounds }
        compose.waitForIdle()
        panel.onBackPressedDispatcher.onBackPressed(); compose.waitForIdle()
        compose.onNodeWithTag("settings-hub").performScrollToNode(hasTestTag("settings-category:INTERFACE"))
        compose.onNodeWithTag("settings-category:INTERFACE").performClick()
        compose.onNodeWithTag("settings-save").assertIsNotEnabled()
        assertFalse(app.session.settingsDraft!!.uiSounds)
    }
    private fun viewer() {
        if (app.catalog.snapshot.value.byId["a"]?.kind == "VIDEO") {
            // Open the idle preview explicitly; tapping an already selected video now starts it.
            app.session.selectMedia("a"); panel.windowModel.open("a")
        } else compose.onNodeWithTag("media:a").performClick()
        pump { compose.onAllNodesWithTag("media-surface:viewer").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }
    @Test fun imageDragShowsNeighborBeforeReleaseAndUpdatesSelectionOnlyAfterward() {
        start(); viewer()
        compose.onNodeWithTag("media-surface:viewer").performTouchInput {
            down(Offset(width * .8f, height * .4f))
            moveTo(Offset(width * .5f, height * .4f), 200)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("preview-neighbor:b").assertExists()
        assertEquals("a", app.session.selectedMediaId)
        compose.onNodeWithTag("media-surface:viewer").performTouchInput { up() }
        pump { app.session.selectedMediaId == "b" }
        compose.onNodeWithTag("preview-neighbor:b").assertDoesNotExist()
    }
    @Test fun realGifFrameStripAndNavigatorHideWithTheOverlayAndKeepTheSelectedFramePaused() {
        val bytes = javaClass.getResourceAsStream("/gif/three-frames.gif")!!.use { it.readBytes() }
        start(kind = "GIF", imageBytes = bytes)
        val data = app.catalog.snapshot.value
        val prefs = app.preferences.state.value.copy(gifFrameSelector = true, gifFrameCounter = true)
        var visible by mutableStateOf(true)
        panel.setContent { GalleryDesign(prefs) {
            MediaSurface(panel, data.byId.getValue("a"), data, prefs, ViewTransform(2f), {}, overlayVisible = visible, tap = {})
        } }
        compose.waitForIdle()
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("gif-frame:2").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("gif-frame:2").performScrollTo().performClick()
        compose.waitUntil(5000) {
            compose.onAllNodes(hasTestTag("gif-frame-count") and hasText("3 / 3 fotogrammi")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("gif-frame:2").assertIsSelected()
        compose.onNodeWithTag("photo-navigator").assertExists()
        compose.runOnIdle { visible = false }; compose.waitForIdle()
        compose.onNodeWithTag("gif-frame-strip").assertDoesNotExist()
        compose.onNodeWithTag("gif-frame-count").assertDoesNotExist()
        compose.onNodeWithTag("photo-navigator").assertDoesNotExist()
        compose.onNodeWithTag("media-surface:viewer").performTouchInput { click(center) }
        compose.runOnIdle { visible = true }; compose.waitForIdle()
        compose.onNodeWithTag("gif-frame:2").assertIsSelected()
        compose.onNodeWithTag("gif-frame-count").assertTextEquals("3 / 3 fotogrammi")
        compose.onNodeWithContentDescription("Riproduci").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("gif-frame:2").assertIsNotSelected()
    }
    @Test fun gifFrameCounterFadesWithTheRestOfThePhotoOverlay() {
        start(kind = "GIF")
        val data = app.catalog.snapshot.value
        val prefs = app.preferences.state.value
        var visible by mutableStateOf(true)
        panel.setContent { GalleryDesign(prefs) {
            MediaSurface(panel, data.byId.getValue("a"), data, prefs, ViewTransform(), {}, overlayVisible = visible)
        } }
        compose.waitForIdle()
        compose.onNodeWithTag("gif-frame-count").assertIsDisplayed()
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        compose.onNodeWithTag("gif-frame-count").assertDoesNotExist()
        compose.runOnIdle { visible = true }
        compose.waitForIdle()
        compose.onNodeWithTag("gif-frame-count").assertIsDisplayed()
    }
    @Test fun changingPhotosDoesNotRestartTheFiveSecondOverlayDeadline() {
        start()
        val data = app.catalog.snapshot.value
        val preferences = app.preferences.state.value.copy(imageOverlayAutoHide = true)
        var id by mutableStateOf("a")
        app.session.imageOverlayVisible = true; app.session.interactImageOverlay()
        panel.setContent { GalleryDesign(preferences) {
            key(id) { MediaViewer(panel, data.byId.getValue(id), data, preferences, {}) }
        } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(4_000)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
        compose.waitForIdle()
        assertTrue(app.session.imageOverlayVisible)
        compose.runOnIdle { id = "b" }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2_000)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        compose.waitForIdle()
        assertFalse("Changing the photo must retain the original overlay deadline", app.session.imageOverlayVisible)
    }
    @Test fun albumCoverOverlayStaysVisibleAfterTappingTheCover() {
        start()
        app.session.previewAlbum("album")
        val data = app.catalog.snapshot.value
        val preferences = app.preferences.state.value
        panel.setContent { GalleryDesign(preferences) {
            MediaViewer(panel, data.byId.getValue("a"), data, preferences, {}, albumCover = true)
        } }
        compose.onNodeWithTag("preview-album-cover").performClick()
        compose.onNodeWithText("QA").assertIsDisplayed()
        compose.onNodeWithText("2 media").assertIsDisplayed()
        compose.onNodeWithTag("preview-album-cover").performClick()
        compose.onNodeWithText("QA").assertIsDisplayed()
    }
    @Test fun swipingPhotoShowsSlidingNeighborBeforeSelectionChanges() {
        start(touch = TouchSettings(doubleTap = TouchAction.NONE))
        runBlocking { app.preferences.update { it.copy(reducedMotion = false) } }
        viewer()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("media-surface:viewer").performTouchInput { swipe(Offset(width * .8f, height * .4f), Offset(width * .2f, height * .4f), 250) }
        compose.mainClock.advanceTimeBy(100)
        compose.waitForIdle()
        assertEquals("a", app.session.selectedMediaId)
        compose.onNodeWithTag("preview-neighbor:b").assertExists()
        compose.mainClock.advanceTimeBy(400)
        compose.mainClock.autoAdvance = true
        pump { app.session.selectedMediaId == "b" }
    }
    @Test fun removedNeighborDuringTransitionReturnsPhotoAndAllowsNextNavigation() {
        start(touch = TouchSettings(doubleTap = TouchAction.NONE))
        runBlocking { app.preferences.update { it.copy(reducedMotion = false) } }
        viewer()
        val next = app.catalog.snapshot.value.byId.getValue("b")
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("media-surface:viewer").performTouchInput { swipe(Offset(width * .8f, height * .4f), Offset(width * .2f, height * .4f), 250) }
        compose.mainClock.advanceTimeBy(100)
        runBlocking { app.catalog.dao.saveMedia(listOf(next.copy(trashed = true))) }
        pump { app.catalog.snapshot.value.byId["b"]?.trashed == true }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        assertEquals("a", app.session.selectedMediaId)
        compose.onNodeWithTag("preview-neighbor:b").assertDoesNotExist()
        runBlocking { app.catalog.dao.saveMedia(listOf(next)) }
        pump { app.catalog.snapshot.value.byId["b"]?.trashed == false }
        compose.onNodeWithTag("media-surface:viewer").performTouchInput { swipe(Offset(width * .8f, height * .4f), Offset(width * .2f, height * .4f), 250) }
        compose.mainClock.advanceTimeBy(600)
        compose.mainClock.autoAdvance = true
        pump { app.session.selectedMediaId == "b" }
    }
    @Test fun bothPhotoEdgesOnlyToggleOverlayWithoutChangingMedia() {
        start(); viewer()
        compose.onNodeWithTag("media-surface:viewer").performTouchInput { click(Offset(width * .95f, height / 2f)) }
        pump { !app.session.imageOverlayVisible }
        assertEquals("a", app.session.selectedMediaId)
        compose.onNodeWithTag("media-surface:viewer").performTouchInput { click(Offset(width * .05f, height / 2f)) }
        pump { app.session.imageOverlayVisible }
        assertEquals("a", app.session.selectedMediaId)
    }
    @Test fun consecutiveNavigationUsesLiveSelectionBeforeRecomposition() {
        start(total = 4); viewer()
        val advance = panel.navigateMedia!!
        advance(1); advance(1); advance(1)
        assertEquals("zz1", app.session.selectedMediaId)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1000)
        assertEquals("zz1", app.session.selectedMediaId)
    }
    @Test fun consecutiveAnimatedSwipesStayOnTheirCommittedPhoto() {
        start(total = 4)
        runBlocking { app.preferences.update { it.copy(reducedMotion = false) } }
        viewer()
        for (expected in listOf("b", "zz0", "zz1")) {
            compose.onNodeWithTag("media-surface:viewer").performTouchInput {
                swipe(Offset(width * .8f, height * .4f), Offset(width * .2f, height * .4f), 200)
            }
            pump { app.session.selectedMediaId == expected && panel.windowModel.route.value.viewer == expected }
            compose.mainClock.advanceTimeBy(400)
            compose.waitForIdle()
            assertEquals(expected, app.session.selectedMediaId)
        }
    }
    @Test fun animatedImageSwipeCommitsAndCanReturnToThePreviousPhoto() {
        start()
        runBlocking { app.preferences.update { it.copy(reducedMotion = false) } }
        viewer(); swipePreviewAndBack()
    }
    @Test fun swipeNeighborsNeverExposeHiddenOrLockedAlbums() {
        start()
        val data = app.catalog.snapshot.value
        assertEquals("b", previewNeighbor("a", 1, listOf("a", "b"), data)?.id)
        assertNull(previewNeighbor("a", 1, listOf("a", "b"), data.copy(albums = data.albums.map { it.copy(hidden = true) })))
        assertNull(previewNeighbor("a", 1, listOf("a", "b"), data.copy(protectedAlbumIds = setOf("album"))))
        assertNull(previewNeighbor("a", 1, listOf("a"), data))
    }
    @Test fun shortImageDragReturnsToCurrentPhotoAndPinchNeverTurnsPage() {
        start(); viewer()
        compose.onNodeWithTag("media-surface:viewer").performTouchInput {
            swipe(Offset(width * .5f, height * .4f), Offset(width * .5f - 35f, height * .4f), 300)
        }
        compose.waitForIdle(); assertEquals("a", app.session.selectedMediaId)
        compose.onNodeWithTag("media-surface:viewer").performTouchInput {
            down(0, Offset(width * .3f, height * .4f)); down(1, Offset(width * .7f, height * .4f))
            moveTo(0, Offset(width * .2f, height * .4f)); moveTo(1, Offset(width * .8f, height * .4f))
            up(0); up(1)
        }
        compose.waitForIdle(); assertEquals("a", app.session.selectedMediaId)
    }
    @Test fun idleVideoPreviewSwipesInBothDirectionsWithoutStartingPlayback() {
        start(kind = "VIDEO"); viewer()
        swipePreviewAndBack()
        assertNull(app.video.player)
        compose.onNodeWithTag("video-play").assertIsDisplayed()
    }
    @Test fun musicPreviewSwipesInBothDirectionsWithoutStartingPlayback() {
        start(kind = "AUDIO")
        app.session.updateMediaContext(listOf("a", "b")); app.session.selectMedia("a")
        panel.windowModel.open("a"); compose.waitForIdle()
        swipePreviewAndBack()
        compose.onNodeWithText("Riproduci brano").assertIsDisplayed()
    }
    private fun swipePreviewAndBack() {
        compose.onNodeWithTag("media-surface:viewer").performTouchInput {
            swipe(Offset(width * .8f, height * .4f), Offset(width * .2f, height * .4f), 500)
        }
        pump { app.session.selectedMediaId == "b" && panel.windowModel.route.value.viewer == "b" }
        compose.onNodeWithTag("media-surface:viewer").performTouchInput {
            swipe(Offset(width * .2f, height * .4f), Offset(width * .8f, height * .4f), 500)
        }
        pump { app.session.selectedMediaId == "a" && panel.windowModel.route.value.viewer == "a" }
    }
    @Test fun remoteSelectionScrollsTheGridToCurrentMediaAndBack() {
        start(total = 30)
        compose.onNodeWithTag("media:zz27").assertDoesNotExist()
        app.session.selectMedia("zz27")
        pump { compose.onAllNodesWithTag("media:zz27").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("media:zz27").assertIsDisplayed()
        app.session.selectMedia("a")
        pump { compose.onAllNodesWithTag("media:a").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("media:a").assertIsDisplayed()
    }
    @Test fun chronologicalHeadersDoNotOffsetRemoteSelectionOrBackScroll() {
        start(total = 30)
        runBlocking { app.preferences.update { it.copy(timelineModes = mapOf("PHOTO" to "DAY")) } }
        compose.waitForIdle()
        compose.onNodeWithTag("date:1970-01-01").assertExists()
        app.session.selectMedia("zz27")
        pump { compose.onAllNodesWithTag("media:zz27").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("media:zz27").assertIsDisplayed()
        app.session.selectMedia("a")
        pump { compose.onAllNodesWithTag("media:a").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("media:a").assertIsDisplayed()
    }
    @Test fun survivingThorPanelDoesNotRelaunchTheCoveredDisplayOnFocusTouchOrPad() {
        start()
        org.robolectric.shadows.ShadowBuild.setModel("AYN Thor")
        org.robolectric.shadows.ShadowDisplayManager.addDisplay("w1920dp-h1080dp")
        app.session.refreshDisplays()
        val screens = org.robolectric.util.ReflectionHelpers.getField<androidx.compose.runtime.MutableState<List<ScreenInfo>>>(app.session, "screens\$delegate")
        screens.value = app.session.screens.map {
            it.copy(width = if (it.key == panel.screenKey) 1240 else 1920, height = 1080)
        }
        assertNotNull(app.session.thorPair)
        assertTrue(panel.hasWindowFocus())
        while (shadowOf(panel).nextStartedActivity != null) { }
        val before = app.session.state
        panel.onWindowFocusChanged(false); panel.onWindowFocusChanged(true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        assertNull(shadowOf(panel).nextStartedActivity)
        panel.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))
        panel.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN))
        compose.onNodeWithTag("media:a").performTouchInput { click() }
        compose.waitForIdle()
        assertNull(shadowOf(panel).nextStartedActivity)
        assertEquals(before, app.session.state)
        assertTrue(panel.isStartedFlow.value)
        assertFalse(panel.isFinishing)
    }
    @Test fun choosingMusicWithPartnerPreviewKeepsTheLibraryGrid() {
        start(kind = "AUDIO")
        org.robolectric.shadows.ShadowDisplayManager.addDisplay("w800dp-h600dp")
        app.session.refreshDisplays()
        val partner = Robolectric.buildActivity(OtherPanelActivity::class.java, Intent(panel.intent).apply { removeExtra("ticket") }).setup().visible()
        try {
            val second = app.session.screens.first { it.key != panel.screenKey }.key
            org.robolectric.util.ReflectionHelpers.setField(partner.get(), "screenKey", second)
            org.robolectric.util.ReflectionHelpers.getField<MutableSet<String>>(app.session, "allowed").add(second)
            assertTrue(app.session.remotePreviewVisible(panel))
            compose.onNodeWithTag("media:a").performClick()
            pump { app.session.selectedMediaId == "a" }
            assertEquals("MUSIC", panel.windowModel.route.value.tab)
            compose.onNodeWithTag("media:a").assertIsDisplayed()
        } finally { partner.pause().stop().destroy() }
    }
    @Test fun activeMusicCannotCoverSettingsOnThePreviewPanel() {
        start(kind = "AUDIO")
        org.robolectric.shadows.ShadowDisplayManager.addDisplay("w800dp-h600dp")
        app.session.refreshDisplays()
        val partner = Robolectric.buildActivity(OtherPanelActivity::class.java, Intent(panel.intent).apply { removeExtra("ticket") }).setup().visible()
        try {
            val second = app.session.screens.first { it.key != panel.screenKey }.key
            org.robolectric.util.ReflectionHelpers.setField(partner.get(), "screenKey", second)
            org.robolectric.util.ReflectionHelpers.getField<MutableSet<String>>(app.session, "allowed").add(second)
            runBlocking { app.preferences.update { it.copy(reducedMotion = false) } }
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            compose.runOnIdle {
                app.music.state.value = PlaybackState(id = "a", title = "Active song", playing = true)
                app.session.openSettings(partner.get())
            }
            repeat(4) { compose.mainClock.advanceTimeByFrame() }
            val openingBounds = compose.onNodeWithTag("settings-partner-info").fetchSemanticsNode().boundsInRoot
            compose.mainClock.advanceTimeBy(500)
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            val finalBounds = compose.onNodeWithTag("settings-partner-info").fetchSemanticsNode().boundsInRoot
            assertEquals(openingBounds.height, finalBounds.height, .1f)
            assertEquals(openingBounds.width, finalBounds.width, .1f)
            assertEquals(partner.get().slot, app.session.settingsOwner)
            compose.onNodeWithTag("settings-category:INTERFACE").assertIsDisplayed()
            compose.onAllNodesWithTag("preview-music-panel").assertCountEquals(0)
            compose.onNodeWithTag("settings-partner-info").assertIsDisplayed()
            compose.onNodeWithTag("settings-category:INTERFACE").performClick()
        compose.onNodeWithTag("settings-category:THEME").performClick()
            compose.onNodeWithTag("settings-preview").assertIsDisplayed()
        } finally { partner.pause().stop().destroy() }
    }
    @Test fun customThemeStartsFromSelectedPaletteAndSavesFromHeader() {
        start()
        compose.onNodeWithContentDescription("Impostazioni").performClick()
        compose.onNodeWithTag("settings-category:INTERFACE").performClick()
        compose.onNodeWithTag("settings-category:THEME").performClick()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("custom-theme-open"))
        compose.onNodeWithTag("custom-theme-open").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Torna al tema").assertDoesNotExist()
        compose.onNodeWithText("Tocca per scegliere un nuovo colore").assertDoesNotExist()
        val colors = (0..5).map { compose.onNodeWithTag("custom-color:$it").fetchSemanticsNode().boundsInRoot }
        assertEquals(colors[0].center.y, colors[2].center.y, .1f)
        assertEquals(colors[3].center.y, colors[5].center.y, .1f)
        assertTrue(colors[3].top > colors[0].bottom)
        val base = CustomTheme.from(GalleryThemes.get("ayn_purple"))
        assertEquals(base, app.session.settingsDraft!!.customTheme)
        assertEquals("ayn_purple", app.preferences.state.value.themeId)
        compose.onNodeWithTag("custom-color:2").performScrollTo().performClick()
        compose.onNodeWithTag("custom-hue-wheel").assertIsDisplayed()
        compose.onNodeWithTag("custom-saturation").performSemanticsAction(SemanticsActions.SetProgress) { it(.2f) }
        compose.onNodeWithText("Fine").performClick()
        compose.onNodeWithTag("settings-save").assertIsEnabled().performClick()
        pump { app.preferences.state.value.themeId == GalleryThemes.CUSTOM }
        assertNotEquals(base.accent, PreferenceStore(app).state.value.customTheme!!.accent)
        assertEquals(base.background, app.preferences.state.value.customTheme!!.background)
    }

    @Test fun videoFilenameSharesTheFavoriteOverlayVisibility() {
        start(kind = "VIDEO")
        panel.windowModel.open("a")
        pump { compose.onAllNodesWithTag("preview-video-name").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("preview-video-name").assertTextEquals("a.png")
        compose.onNodeWithTag("media-surface:viewer").performTouchInput { click(Offset(width * .3f, height * .5f)) }
        compose.onNodeWithTag("preview-video-name").assertDoesNotExist()
        compose.onNodeWithTag("preview-favorite").assertDoesNotExist()
    }

    @Test fun categoryArrowsAndDefaultSectionRemainDraftUntilSave() {
        start(); compose.onNodeWithContentDescription("Impostazioni").performClick(); compose.onNodeWithTag("settings-category:INTERFACE").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("category-move:ALBUM:1"))
        compose.onNodeWithTag("category-move:ALBUM:1").performClick()
        assertEquals(NavbarOrder.DEFAULT, app.preferences.state.value.navbarOrder)
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("default-section"))
        compose.onNodeWithTag("default-section").performClick()
        compose.onNodeWithTag("default-section:MUSIC").performClick()
        compose.onNodeWithTag("settings-save").performClick()
        pump { app.preferences.state.value.defaultSection == "MUSIC" }
        assertEquals(listOf("PHOTO", "VIDEO", "ALBUM", "MUSIC", "FAVORITE"), app.preferences.state.value.navbarOrder)
    }
    @Test fun navbarReorderPreviewDoesNotNavigateAndPersistsOnlyOnSave() {
        start()
        compose.onNodeWithTag("navbar:PHOTO").performTouchInput { longClick() }
        compose.onNodeWithText("ANTEPRIMA").assertExists()
        compose.onNodeWithContentDescription("Sposta a destra").performClick()
        assertEquals(NavbarOrder.DEFAULT, app.preferences.state.value.navbarOrder)
        compose.onNodeWithTag("navbar-preview:VIDEO").performClick()
        assertEquals("PHOTO", panel.windowModel.route.value.tab)
        compose.onNodeWithText("Annulla").performClick()
        compose.onNodeWithTag("navbar:PHOTO").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Sposta a destra").performClick()
        compose.onNodeWithText("Salva").performClick()
        pump { app.preferences.state.value.navbarOrder.first() == "ALBUM" }
        assertEquals(app.preferences.state.value.navbarOrder, PreferenceStore(app).state.value.navbarOrder)
    }

    private fun pump(check: () -> Boolean) { compose.waitUntil(8000) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25)); check() }; compose.waitForIdle() }
    private fun select(tag: String, action: TouchAction) {
        compose.onNodeWithTag(tag).performClick()
        compose.onNodeWithTag("$tag:${action.name}").performScrollTo().performClick()
        compose.waitForIdle()
    }
    @Test fun galleryLongPressAndEdgeBackReplaceSelectionButtonAndAlbumArrow() {
        start()
        compose.onNodeWithText("a.png").assertDoesNotExist()
        compose.onNodeWithText("Seleziona").assertDoesNotExist()
        compose.onNodeWithTag("media:a").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Opzioni media selezionati").performClick()
        compose.onNodeWithText("Seleziona tutti").assertIsDisplayed()
        compose.onNodeWithText("Sposta").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Deseleziona tutti").performScrollTo()
        compose.onNodeWithText("Deseleziona tutti").performClick()
        compose.onNodeWithContentDescription("Cerca e filtra").assertExists()
        panel.windowModel.go("ALBUM", album = "album"); compose.waitForIdle()
        compose.onNodeWithContentDescription("Indietro").assertDoesNotExist()
        compose.onNodeWithTag("gallery-content").performTouchInput { swipe(Offset(2f, height / 2f), Offset(width / 2f, height / 2f), 500) }
        compose.waitForIdle()
        assertNull(panel.windowModel.route.value.album)
    }
    @Test fun photoTapHidesAndRestoresTheFloatingPlayerWithItsControls() {
        start()
        val data = app.catalog.snapshot.value
        panel.setContent { GalleryDesign(GalleryPreferences(reducedMotion = true)) {
            var visible by remember { mutableStateOf(true) }
            Box {
                MediaViewer(panel, data.byId.getValue("a"), data, GalleryPreferences(reducedMotion = true),
                    onActions = {}, onControlsVisibility = { visible = it })
                PreviewMusicPanel(app, PlaybackState(id = "music", title = "Playing song", playing = true), data,
                    GalleryPreferences(reducedMotion = true), fullPlayer = false, controlsVisible = visible)
            }
        } }
        compose.waitForIdle()
        compose.onNodeWithTag("preview-mini-player").assertIsDisplayed()
        compose.onNodeWithTag("media-surface:viewer").performTouchInput { click(center) }
        pump { compose.onAllNodesWithTag("preview-mini-player").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("preview-mini-player").assertDoesNotExist()
        compose.onNodeWithContentDescription("Opzioni del media").assertDoesNotExist()
        compose.onNodeWithTag("media-surface:viewer").performTouchInput { click(center) }
        pump { compose.onAllNodesWithTag("preview-mini-player").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("preview-mini-player").assertIsDisplayed()
        compose.onNodeWithContentDescription("Opzioni del media").assertIsDisplayed()
    }
    @Test fun hiddenImageOverlaySurvivesMovingToTheNextPhoto() {
        start(); viewer()
        compose.onNodeWithTag("media-surface:viewer").performTouchInput { click(center) }
        pump { !app.session.imageOverlayVisible }
        compose.onNodeWithTag("media-surface:viewer").performTouchInput { swipe(Offset(width * .8f, height / 2f), Offset(width * .2f, height / 2f), 250) }
        pump { panel.windowModel.route.value.viewer == "b" }
        compose.onNodeWithTag("preview-favorite").assertDoesNotExist()
        compose.onNodeWithTag("media-surface:viewer").performTouchInput { click(center) }
        pump { app.session.imageOverlayVisible }
        compose.onNodeWithTag("preview-favorite").assertIsDisplayed()
    }
    @Test fun imageOverlayStaysVisibleByDefaultAfterFiveSeconds() {
        start(); viewer()
        compose.mainClock.advanceTimeBy(6_000)
        compose.waitForIdle()
        assertTrue(app.session.imageOverlayVisible)
        compose.onNodeWithTag("preview-favorite").assertIsDisplayed()
    }
    @Test fun idleImageOverlayHidesAfterFiveSeconds() {
        start(); runBlocking { app.preferences.update { it.copy(imageOverlayAutoHide = true) } }; viewer()
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(4_000)
        assertTrue(app.session.imageOverlayVisible)
        compose.mainClock.advanceTimeBy(1_100)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertFalse(app.session.imageOverlayVisible)
        compose.onNodeWithTag("preview-favorite").assertDoesNotExist()
    }
    @Test fun previewDefaultsToFullScreenAndTapFadesGearWhileSwipeMoves() {
        start()
        runBlocking { app.preferences.update { it.copy(viewerZoom = false) } }; compose.waitForIdle()
        viewer()
        compose.onNodeWithTag("gallery-header").assertDoesNotExist()
        compose.onNodeWithContentDescription("Media precedente").assertDoesNotExist()
        compose.onNodeWithContentDescription("Media successivo").assertDoesNotExist()
        compose.onNodeWithText("100% · reset").assertDoesNotExist()
        compose.onNodeWithContentDescription("Opzioni del media").assertIsDisplayed()
        compose.onNodeWithContentDescription("Aggiungi ai preferiti").performClick()
        pump { app.catalog.snapshot.value.byId["a"]?.favorite == true }
        compose.onNodeWithContentDescription("Rimuovi dai preferiti").assertIsDisplayed()
        compose.onNodeWithTag("media-surface:viewer").performTouchInput { click(center) }
        pump { compose.onAllNodesWithContentDescription("Opzioni del media").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("preview-favorite").assertDoesNotExist()
        compose.onNodeWithTag("media-surface:viewer").performTouchInput { click(center) }
        pump { compose.onAllNodesWithContentDescription("Opzioni del media").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Rimuovi dai preferiti").performClick()
        pump { app.catalog.snapshot.value.byId["a"]?.favorite == false }
        compose.onNodeWithTag("media-surface:viewer").performTouchInput { swipe(Offset(width * .8f, height / 2f), Offset(width * .2f, height / 2f), 250) }
        pump { panel.windowModel.route.value.viewer == "b" }
    }
    @Test fun navigatorIsOptInAndAppearsOnlyWhileZoomed() {
        start()
        runBlocking { app.preferences.update { it.copy(photoNavigator = true) } }; compose.waitForIdle()
        viewer()
        compose.onNodeWithTag("photo-navigator").assertDoesNotExist()
        compose.onNodeWithTag("media-surface:viewer").performTouchInput { doubleClick(center) }
        pump { !app.session.imageOverlayVisible }
        compose.onNodeWithTag("photo-navigator").assertDoesNotExist()
        compose.onNodeWithTag("media-surface:viewer").performTouchInput { advanceEventTime(400); click(center) }
        pump { compose.onAllNodesWithTag("photo-navigator").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("photo-navigator").assertIsDisplayed()
        compose.onNodeWithText("200% · reset").performClick()
        compose.onNodeWithTag("photo-navigator").assertDoesNotExist()
    }
    @Test fun doubleTapRestoresShrunkImageBeforeTheUsualZoomIn() {
        start(); viewer()
        repeat(5) { compose.onNodeWithText("−").performClick() }
        compose.onNodeWithText("50% · reset").assertExists()
        compose.onNodeWithTag("media-surface:viewer").performTouchInput { doubleClick(center) }
        pump { !app.session.imageOverlayVisible }
        compose.onNodeWithTag("media-surface:viewer").performTouchInput { advanceEventTime(400); click(center) }
        pump { compose.onAllNodesWithText("100% · reset").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("media-surface:viewer").performTouchInput { advanceEventTime(400); doubleClick(center) }
        pump { !app.session.imageOverlayVisible }
        compose.onNodeWithTag("media-surface:viewer").performTouchInput { advanceEventTime(400); click(center) }
        pump { compose.onAllNodesWithText("200% · reset").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun zoomedImageKeepsPanAfterFingerLiftAndRecomposition() {
        start(); viewer()
        compose.onNodeWithText("+").performClick()
        compose.onNodeWithText("125% · reset").assertExists()
        fun position() = compose.onNodeWithTag("media-surface:viewer").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        val centered = position()
        compose.onNodeWithTag("media-surface:viewer").performTouchInput { swipe(center, center + Offset(120f, 0f), 500) }
        compose.waitForIdle()
        val moved = position()
        assertNotEquals("Finger lift must preserve the last valid pan position", centered, moved)
        runBlocking { app.preferences.update { it.copy(viewerArrows = true) } }; compose.waitForIdle()
        assertEquals(moved, position())
        compose.mainClock.advanceTimeBy(1000); compose.waitForIdle()
        assertEquals(moved, position())
    }
    @Test fun dormantComparisonIsAbsentFromMenusAndOldControllerShortcut() {
        start(); viewer()
        compose.onNodeWithContentDescription("Opzioni del media").performClick()
        compose.onNodeWithText("Fissa e confronta").assertDoesNotExist()
        compose.onNodeWithTag("preview-dismiss-area").performTouchInput { click(Offset(10f, 10f)) }; compose.waitForIdle()
        compose.onNodeWithTag("preview-side-sheet").assertDoesNotExist()
        panel.performTouchAction(TouchAction.PIN); compose.waitForIdle()
        assertEquals("a", panel.windowModel.route.value.viewer)
        assertFalse(app.comparison.state.value.enabled)
    }
    @Test @Config(qualifiers = "it-w360dp-h640dp-port") fun draftRotationAndSaveRemainReachableWithoutTouchZonesAt140Percent() {
        RuntimeEnvironment.setFontScale(1.4f); start(); settings()
        compose.onNodeWithTag("touch-save").assertIsDisplayed().assertIsNotEnabled()
        select("touch-double", TouchAction.NONE)
        assertEquals(TouchAction.TOGGLE_ZOOM, app.preferences.state.value.touch.doubleTap)
        activity!!.recreate(); compose.waitForIdle()
        compose.onNodeWithTag("touch-double").assertTextEquals("Nessuna azione")
        compose.onNodeWithTag("touch-advanced").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("touch-add-zone").assertDoesNotExist()
        compose.onNodeWithTag("touch-zone-x").assertDoesNotExist()
        compose.onNodeWithText("Zone di tocco").assertDoesNotExist()
        assertTrue(app.preferences.state.value.touch.zones.isEmpty())
        runBlocking { app.preferences.update { it.copy(compact = true) } }
        compose.waitForIdle()
        capture("touch-controls-large-text")
        compose.onNodeWithTag("touch-save").assertIsDisplayed().assertIsEnabled().performClick()
        pump { app.preferences.state.value.touch.doubleTap == TouchAction.NONE }
        val saved = app.preferences.state.value
        assertEquals(TouchAction.NONE, saved.touch.doubleTap); assertTrue(saved.compact)
        assertTrue(saved.touch.zones.isEmpty())
        assertEquals(saved, PreferenceStore(app).state.value)
        compose.onNodeWithTag("touch-settings-list").performScrollToNode(hasTestTag("touch-defaults"))
        compose.onNodeWithTag("touch-defaults").performScrollTo().performClick()
        pump { compose.onAllNodes(hasTestTag("touch-save") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        panel.onBackPressedDispatcher.onBackPressed(); compose.waitForIdle()
        compose.onNodeWithText("Esci senza salvare").performClick(); compose.waitForIdle()
        assertEquals(saved, app.preferences.state.value)
        assertEquals("SETTINGS", panel.windowModel.route.value.tab)
    }
    @Test fun anotherPanelsTouchChangeRequiresReloadInsteadOfOverwritingIt() {
        start(); settings(); select("touch-double", TouchAction.NONE)
        val other = app.preferences.state.value.touch.copy(longPress = TouchAction.FAVORITE)
        runBlocking { app.preferences.update { it.copy(touch = other) } }
        compose.waitForIdle(); compose.onNodeWithTag("touch-save").performClick()
        pump { compose.onAllNodesWithTag("touch-error").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("touch-error").assertTextContains("altro pannello", substring = true)
        assertEquals(other, app.preferences.state.value.touch)
        compose.onNodeWithTag("touch-advanced").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("touch-settings-list").performScrollToNode(hasTestTag("touch-reload"))
        compose.onNodeWithTag("touch-reload").performScrollTo().performClick()
        compose.onNodeWithTag("touch-save").assertIsNotEnabled()
    }
    @Test fun nativeThreeFingerDragDoubleTapPinchPanSwipeAndControllerCoexist() {
        val base = TouchSettings()
        val touch = base.copy(threeFinger = base.threeFinger + (PadContext.VIEWER to (base.threeFinger.getValue(PadContext.VIEWER) + (TouchDirection.RIGHT to TouchAction.FAVORITE))))
        start(touch); viewer()
        compose.onNodeWithTag("media-surface:viewer").performTouchInput { doubleClick(center) }
        pump { !app.session.imageOverlayVisible }
        compose.onNodeWithTag("media-surface:viewer").performTouchInput { advanceEventTime(400); click(center) }
        pump { compose.onAllNodesWithText("200% · reset").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("200% · reset").assertExists().performClick()
        dragThree(100f)
        pump { app.catalog.snapshot.value.byId["a"]?.favorite == true }
        compose.onNodeWithText("100% · reset").assertExists()
        dragThree(100f, reverse = true)
        assertTrue(app.catalog.snapshot.value.byId.getValue("a").favorite)
        compose.onNodeWithTag("media-surface:viewer").performTouchInput {
            down(0, center + Offset(-60f, 0f)); down(1, center + Offset(60f, 0f))
            updatePointerTo(0, center + Offset(-120f, 0f)); updatePointerTo(1, center + Offset(120f, 0f)); move(80)
            down(2, center + Offset(0f, 30f))
            updatePointerTo(0, center + Offset(-20f, 0f)); updatePointerTo(1, center + Offset(220f, 0f)); updatePointerTo(2, center + Offset(100f, 30f)); move(80)
            up(0); up(1); up(2)
        }
        compose.onNodeWithText("100% · reset").assertDoesNotExist()
        assertTrue(app.catalog.snapshot.value.byId.getValue("a").favorite)
        compose.onNodeWithTag("media-surface:viewer").performTouchInput { swipe(center, center + Offset(100f, 0f), 200) }
        assertEquals("a", panel.windowModel.route.value.viewer)
        compose.onNode(hasText("% · reset", substring = true)).performClick()
        compose.onNodeWithTag("media-surface:viewer").performTouchInput { swipe(center, center + Offset(-200f, 0f), 200) }
        pump { panel.windowModel.route.value.viewer == "b" }
        val time = SystemClock.uptimeMillis()
        panel.dispatchKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_R2, 0))
        panel.dispatchKeyEvent(KeyEvent(time, time + 20, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_R2, 0))
        pump { panel.windowModel.route.value.viewer == "a" }
        assertFalse(app.session.state.swapped)
        assertNull(shadowOf(panel).nextStartedActivity)
    }
    @Test fun shortTapZoneDoesNotConsumePinchAndLongPressUsesConfiguredAction() {
        val touch = TouchSettings(longPress = TouchAction.FAVORITE,
            zones = listOf(TouchZone("center-zone", PadContext.VIEWER, .25f, .25f, .5f, .5f, TouchAction.FAVORITE)))
        start(touch); viewer()
        compose.onNodeWithTag("gallery-content").performTouchInput { click(center) }
        pump { app.catalog.snapshot.value.byId["a"]?.favorite == true }
        compose.onNodeWithTag("media-surface:viewer").performTouchInput {
            down(0, center + Offset(-30f, 0f)); down(1, center + Offset(30f, 0f))
            updatePointerTo(0, center + Offset(-80f, 0f)); updatePointerTo(1, center + Offset(80f, 0f)); move(100); up(0); up(1)
        }
        compose.onNodeWithText("100% · reset").assertDoesNotExist()
        assertTrue(app.catalog.snapshot.value.byId.getValue("a").favorite)
        compose.onNodeWithTag("media-surface:viewer").performTouchInput { longClick(center, 650) }
        pump { app.catalog.snapshot.value.byId["a"]?.favorite == false }
    }
    private fun dragThree(dx: Float, reverse: Boolean = false) {
        compose.onNodeWithTag("media-surface:viewer").performTouchInput {
            down(0, center + Offset(-30f, -30f)); down(1, center + Offset(0f, 0f)); down(2, center + Offset(30f, 30f))
            for (i in 0..2) updatePointerTo(i, center + Offset((i - 1) * 30f + dx, (i - 1) * 30f))
            move(100)
            if (reverse) { for (i in 0..2) updatePointerTo(i, center + Offset((i - 1) * 30f, (i - 1) * 30f)); move(100) }
            up(0); up(1); up(2)
        }
    }
    private fun capture(name: String) {
        compose.waitForIdle(); shadowOf(Looper.getMainLooper()).idle()
        val view = panel.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap)); val file = File("../qa/rendered/$name.png").absoluteFile
            file.parentFile!!.mkdirs(); file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
