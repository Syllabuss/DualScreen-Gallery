package it.ayngallery

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.cancel
import androidx.lifecycle.viewModelScope
import org.junit.Assert.*
import org.junit.Test
import org.junit.After
import org.junit.Rule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.toArgb
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.time.Duration

/** Rendered Android/Compose fixtures, not Thor certification or playback tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestGalleryApp::class, qualifiers = "it-w800dp-h600dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class GalleryRenderTest {
    @get:Rule val compose = createEmptyComposeRule()
    @After fun release() { (RuntimeEnvironment.getApplication() as GalleryApp).onTerminate() }
    @Test fun concurrentFixtureReadersReceiveCompletePngs() {
        val app = RuntimeEnvironment.getApplication() as GalleryApp
        // Graphics stay on Robolectric's sandbox thread; publication and reads remain concurrent.
        val image = Bitmap.createBitmap(480, 320, Bitmap.Config.ARGB_8888)
        val png = java.io.ByteArrayOutputStream().use { output ->
            check(image.compress(Bitmap.CompressFormat.PNG, 100, output))
            output.toByteArray()
        }
        image.recycle()
        val provider = PreviewFixtureProvider(png).apply {
            attachInfo(app, ProviderInfo().apply { authority = "ayngallery.concurrentfixture" })
        }
        val uri = DocumentsContract.buildDocumentUriUsingTree(
            DocumentsContract.buildTreeDocumentUri("ayngallery.concurrentfixture", "root"), "parallel")
        val gate = java.util.concurrent.CountDownLatch(1)
        val workers = java.util.concurrent.Executors.newFixedThreadPool(8)
        try {
            val reads = List(8) { workers.submit<ByteArray> {
                check(gate.await(5, java.util.concurrent.TimeUnit.SECONDS))
                ParcelFileDescriptor.AutoCloseInputStream(provider.openFile(uri, "r")).use { it.readBytes() }
            } }
            gate.countDown()
            reads.forEach { read ->
                val bytes = read.get(10, java.util.concurrent.TimeUnit.SECONDS)
                assertArrayEquals(png, bytes)
                val bitmap = requireNotNull(android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                try { assertEquals(480 to 320, bitmap.width to bitmap.height) } finally { bitmap.recycle() }
            }
        } finally { workers.shutdownNow(); workers.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS) }
    }
    @Test fun displayControlsReplaceTheViewerEvenWhileTheMediaListingCannotUpdate() {
        val app = RuntimeEnvironment.getApplication() as GalleryApp
        shadowOf(app).declareComponentUnbindable(android.content.ComponentName(app, MusicService::class.java))
        runBlocking {
            app.preferences.save(GalleryPreferences(reducedMotion = true))
            withTimeout(5000) { app.catalog.snapshot.first { it.initialized } }
        }
        val launcher = Robolectric.buildActivity(LauncherActivity::class.java).create().get()
        val controller = Robolectric.buildActivity(MainPanelActivity::class.java, shadowOf(launcher).nextStartedActivity).setup().visible()
        try {
            val model = controller.get().windowModel
            model.open("unavailable-fixture")
            compose.waitForIdle()
            // Freeze only the listing producer to model a scan/query lagging behind navigation.
            model.viewModelScope.cancel()
            model.go("DISPLAY")
            compose.waitForIdle()
            compose.onNodeWithText("Usa solo questo schermo").assertIsDisplayed()
            assertNull(shadowOf(controller.get()).nextStartedActivity)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun compactSearchRestoresOnRotationAndMainSectionsKeepTheirContentHeight() {
        val app = RuntimeEnvironment.getApplication() as GalleryApp
        shadowOf(app).declareComponentUnbindable(android.content.ComponentName(app, MusicService::class.java))
        runBlocking { app.preferences.save(GalleryPreferences(reducedMotion = true)) }
        val launcher = Robolectric.buildActivity(LauncherActivity::class.java).create().get()
        val controller = Robolectric.buildActivity(MainPanelActivity::class.java, shadowOf(launcher).nextStartedActivity).setup().visible()
        var panel = controller.get()
        runBlocking { withTimeout(10_000) { app.catalog.snapshot.first { it.initialized } } }
        compose.waitForIdle()
        compose.onNodeWithTag("library-search").assertDoesNotExist()
        compose.onNodeWithText("AYNGALLERY").assertDoesNotExist()
        val height = compose.onNodeWithTag("gallery-content").fetchSemanticsNode().size.height
        for (tab in listOf("ALBUM", "VIDEO", "MUSIC", "FAVORITE", "PHOTO")) {
            panel.windowModel.go(tab)
            compose.waitUntil(10_000) { panel.windowModel.listing.value.route.tab == tab }
            compose.waitForIdle()
            assertEquals(tab, height, compose.onNodeWithTag("gallery-content").fetchSemanticsNode().size.height)
        }
        compose.onNodeWithContentDescription("Cerca e filtra").performClick()
        compose.onNodeWithTag("library-search").performTextInput("camera")
        assertEquals("camera", panel.windowModel.query.value)
        controller.recreate(); panel = controller.get(); compose.waitForIdle()
        compose.onNodeWithTag("library-search").assertTextContains("camera")
        compose.onNodeWithContentDescription("Chiudi ricerca").performClick()
        compose.onNodeWithTag("library-search").assertDoesNotExist()
        assertEquals("", panel.windowModel.query.value)
        capturePanel(panel, "compact-library-header")
        controller.pause().stop().destroy()
    }
    @Test fun dualDisplayPageCanScrollBothListsAndRememberedPairRespectsCessionAndWidgetLaunch() {
        val app = RuntimeEnvironment.getApplication() as GalleryApp
        shadowOf(app).declareComponentUnbindable(android.content.ComponentName(app, MusicService::class.java))
        org.robolectric.shadows.ShadowDisplayManager.addDisplay("w800dp-h600dp")
        app.session.refreshDisplays()
        runBlocking { app.preferences.save(GalleryPreferences(reducedMotion = true)) }
        val launcher = Robolectric.buildActivity(LauncherActivity::class.java).create().get()
        val initialIntents = listOf(shadowOf(launcher).nextStartedActivity, shadowOf(launcher).nextStartedActivity)
        assertEquals(app.session.screens.map { it.key }.toSet(), initialIntents.map { it.getStringExtra("display_key") }.toSet())
        val controller = Robolectric.buildActivity(MainPanelActivity::class.java, initialIntents.single { it.component!!.className == MainPanelActivity::class.java.name }).setup().visible()
        var panel = controller.get()
        panel.windowModel.go("DISPLAY"); compose.waitForIdle()
        compose.onNodeWithText("Usa solo questo schermo").assertIsDisplayed()
        listOf("Copia rapporto", "Scambia i contenuti dei due schermi", "Cedi questo schermo al gioco",
            "Layout Thor · album sotto, anteprima sopra", "Verifica apertura del selettore Android").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
        compose.onNodeWithTag("display-list").performScrollToNode(hasTestTag("startup-dual"))
        compose.onNodeWithTag("startup-dual").assertIsOn().performClick()
        compose.waitForIdle(); panel = controller.get()
        compose.onNodeWithTag("startup-dual").assertIsOff()
        val singleReopen = Robolectric.buildActivity(LauncherActivity::class.java).create().get()
        assertNotNull(shadowOf(singleReopen).nextStartedActivity); assertNull(shadowOf(singleReopen).nextStartedActivity)
        compose.onNodeWithTag("startup-dual").performClick()
        compose.waitForIdle(); panel = controller.get()
        compose.onNodeWithTag("startup-dual").assertIsOn()
        val companionRequest = shadowOf(app).nextStartedActivity
        assertEquals(OtherPanelActivity::class.java.name, companionRequest.component!!.className)
        assertEquals(app.session.screens.single { it.key != panel.screenKey }.key, companionRequest.getStringExtra("display_key"))
        assertNull(shadowOf(app).nextStartedActivity)
        assertEquals(app.session.screens.map { it.key }.toSet(), app.session.startupPair)
        capturePanel(panel, "dual-display-settings")
        val reopen = Robolectric.buildActivity(LauncherActivity::class.java, android.content.Intent(android.content.Intent.ACTION_MAIN)).create().get()
        val started = listOf(shadowOf(reopen).nextStartedActivity, shadowOf(reopen).nextStartedActivity)
        assertEquals(app.session.screens.map { it.key }.toSet(), started.map { it.getStringExtra("display_key") }.toSet())
        assertEquals(setOf(MainPanelActivity::class.java.name, OtherPanelActivity::class.java.name), started.map { it.component!!.className }.toSet())
        assertNull(shadowOf(reopen).nextStartedActivity)
        val widget = Robolectric.buildActivity(LauncherActivity::class.java, android.content.Intent("it.ayngallery.OPEN_PLAYER")).create().get()
        assertTrue(shadowOf(widget).nextStartedActivity.getBooleanExtra("open_player", false))
        assertNull(shadowOf(widget).nextStartedActivity)
        app.session.single(panel)
        compose.waitForIdle(); panel = controller.get()
        val afterCession = Robolectric.buildActivity(LauncherActivity::class.java).create().get()
        assertEquals(panel.screenKey, shadowOf(afterCession).nextStartedActivity.getStringExtra("display_key"))
        assertNull(shadowOf(afterCession).nextStartedActivity)
        assertEquals(1, app.session.state.ceded.size)
        controller.pause().stop().destroy()
    }
    @Test fun realComposeLibraryAndComparisonCanRender() = render("landscape")
    @Test @Config(qualifiers = "it-w480dp-h800dp-port") fun portraitComparisonKeepsReadablePanes() = render("portrait")
    @Test @Config(qualifiers = "it-w1000dp-h1000dp") fun largeWindowShowsFourUsefulPanes() = render("large")
    @Test @Config(qualifiers = "it-w360dp-h640dp-port") fun themePickerKeepsSaveReachableWithLargeText() {
        RuntimeEnvironment.setFontScale(1.4f)
        val app = RuntimeEnvironment.getApplication() as GalleryApp
        shadowOf(app).declareComponentUnbindable(android.content.ComponentName(app, MusicService::class.java))
        runBlocking { withTimeout(10_000) { app.catalog.snapshot.first { it.initialized } } }
        val launcher = Robolectric.buildActivity(LauncherActivity::class.java).create().get()
        val controller = Robolectric.buildActivity(MainPanelActivity::class.java, shadowOf(launcher).nextStartedActivity).setup().visible()
        val panel = controller.get()
        panel.windowModel.go("SETTINGS"); compose.waitForIdle()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("theme-accordion:NEON"))
        compose.onNodeWithTag("theme-accordion:NEON").performClick()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("theme:stocking_pink"))
        compose.onNodeWithTag("theme:stocking_pink").performClick()
        compose.onNodeWithTag("settings-save").assertIsDisplayed().assertIsEnabled()
        capturePanel(panel, "theme-large-text-small-window")
        compose.onNodeWithTag("settings-save").performClick()
        compose.waitUntil(5_000) { app.preferences.state.value.themeId == "stocking_pink" }
        controller.pause().stop().destroy()
    }
    @Test fun themeDraftIsDiscardedUnlessSavedAndAllPalettesRender() {
        val app = RuntimeEnvironment.getApplication() as GalleryApp
        shadowOf(app).declareComponentUnbindable(android.content.ComponentName(app, MusicService::class.java))
        runBlocking { app.preferences.save(GalleryPreferences(themeId = GalleryThemes.WHITE, reducedMotion = true)) }
        val launcher = Robolectric.buildActivity(LauncherActivity::class.java).create().get()
        val controller = Robolectric.buildActivity(MainPanelActivity::class.java, shadowOf(launcher).nextStartedActivity).setup().visible()
        var panel = controller.get()
        runBlocking { withTimeout(10_000) { app.catalog.snapshot.first { it.initialized } } }
        panel.windowModel.go("SETTINGS")
        compose.waitForIdle()
        compose.onNodeWithTag("theme:ayn_purple").performScrollTo().performClick().assertIsSelected()
        assertEquals(GalleryThemes.WHITE, app.preferences.state.value.themeId)
        assertEquals(GalleryThemes.WHITE, PreferenceStore(app).state.value.themeId)
        @Suppress("DEPRECATION")
        val oldBar = panel.window.navigationBarColor
        assertEquals(GalleryThemes.get(GalleryThemes.WHITE).background.toArgb(), oldBar)
        panel.windowModel.back(); compose.waitForIdle()
        panel.windowModel.go("SETTINGS"); compose.waitForIdle()
        compose.onNodeWithTag("theme:ayn_purple").assertIsNotSelected()
        compose.onNodeWithTag("theme:ayn_purple").performClick()
        compose.onNodeWithTag("settings-save").performClick()
        compose.waitUntil(5_000) { app.preferences.state.value.themeId == "ayn_purple" }
        compose.waitForIdle()
        assertEquals("ayn_purple", PreferenceStore(app).state.value.themeId)
        @Suppress("DEPRECATION")
        val savedBar = panel.window.navigationBarColor
        assertEquals(GalleryThemes.get("ayn_purple").background.toArgb(), savedBar)
        controller.recreate(); panel = controller.get(); compose.waitForIdle()
        assertEquals("SETTINGS", panel.windowModel.route.value.tab)
        compose.onNodeWithTag("theme:ayn_purple").assertIsSelected()
        GalleryThemes.all.forEach { theme ->
            runBlocking { app.preferences.save(app.preferences.state.value.copy(themeId = theme.id)) }
            // Re-enter so the draft follows the newly committed setting, as after reopening settings.
            panel.windowModel.back(); compose.waitForIdle()
            panel.windowModel.go("SETTINGS"); compose.waitForIdle()
            if (theme.family != "AYN") {
                val accordion = "theme-accordion:${if (theme.family == "DARK") "NEON" else theme.family}"
                compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag(accordion))
                compose.onNodeWithTag(accordion).performClick()
            }
            compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("theme:${theme.id}"))
            compose.onNodeWithTag("theme:${theme.id}").assertIsSelected()
            capturePanel(panel, "theme-${theme.id}")
        }
        controller.pause().stop().destroy()
    }
    @Test @Config(qualifiers = "it-w360dp-h640dp-port")
    fun previousSettingRequiresSaveAndRemainsReachableWithLargeText() {
        RuntimeEnvironment.setFontScale(1.4f)
        val app = RuntimeEnvironment.getApplication() as GalleryApp
        shadowOf(app).declareComponentUnbindable(android.content.ComponentName(app, MusicService::class.java))
        runBlocking { app.preferences.save(GalleryPreferences(reducedMotion = true)) }
        val launcher = Robolectric.buildActivity(LauncherActivity::class.java).create().get()
        val controller = Robolectric.buildActivity(MainPanelActivity::class.java, shadowOf(launcher).nextStartedActivity).setup().visible()
        runBlocking { withTimeout(10_000) { app.catalog.snapshot.first { it.initialized } } }
        fun show() {
            controller.get().windowModel.go("SETTINGS"); compose.waitForIdle()
            compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("always-previous"))
        }
        fun toggle() = compose.onNodeWithTag("always-previous")
        fun togglePrevious() { toggle().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }; compose.waitForIdle() }
        show(); toggle().assertIsOff(); togglePrevious(); toggle().assertIsOn()
        assertFalse(app.preferences.state.value.alwaysPrevious)
        controller.get().windowModel.back(); compose.waitForIdle()
        show(); toggle().assertIsOff(); togglePrevious(); toggle().assertIsOn()
        compose.onNodeWithTag("settings-save").assertIsDisplayed().assertIsEnabled()
        capturePanel(controller.get(), "music-previous-large-text")
        compose.onNodeWithTag("settings-save").performClick()
        compose.waitUntil(5000) { app.preferences.state.value.alwaysPrevious }
        assertTrue(PreferenceStore(app).state.value.alwaysPrevious)
        controller.recreate(); compose.waitForIdle()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("always-previous"))
        toggle().assertIsOn()
        controller.pause().stop().destroy()
    }
    private fun render(layout: String) {
        val app = RuntimeEnvironment.getApplication() as GalleryApp
        shadowOf(app).declareComponentUnbindable(android.content.ComponentName(app, MusicService::class.java))
        val provider = PreviewFixtureProvider()
        provider.attachInfo(app, ProviderInfo().apply { authority = "ayngallery.preview" })
        ShadowContentResolver.registerProviderInternal("ayngallery.preview", provider)
        val tree = DocumentsContract.buildTreeDocumentUri("ayngallery.preview", "root")
        val media = listOf("a", "b", "c", "d").map { id -> MediaEntity(id, "ayngallery.preview:$id",
            DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(), "album", "Illustration $id.png", "image/png", "IMAGE", 100, 1) }
        val song = MediaEntity("song", "ayngallery.preview:song", DocumentsContract.buildDocumentUriUsingTree(tree, "song").toString(),
            "album", "QA track.mp3", "audio/mpeg", "AUDIO", 100, 1, duration = 180_000, title = "Local artwork", artist = "QA fixture", artworkMediaId = "a")
        runBlocking {
            app.preferences.save(GalleryPreferences(reducedMotion = true))
            app.contentResolver.takePersistableUriPermission(tree, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            app.catalog.dao.saveSource(SourceEntity("source", tree.toString(), "QA fixture", "MIXED"))
            app.catalog.dao.saveAlbums(listOf(AlbumEntity("album", "ayngallery.preview:root", tree.toString(), "QA fixture", "QA fixture", null)))
            app.catalog.dao.saveAncestors(listOf(AlbumAncestor("album", "album")))
            app.catalog.dao.saveMedia(media + song)
            app.catalog.dao.saveAlbumSources(listOf(AlbumSource("album", "source", 1)))
            app.catalog.dao.saveMembership((media + song).map { MediaSource(it.id, "source", 1) })
            withTimeout(10_000) { app.catalog.snapshot.first { it.visibleMedia.size == 5 } }
            // Decode the fixture through the real loader/provider before capture;
            // virtual looper time alone does not wait for IO decoding threads.
            withContext(Dispatchers.IO) { media.forEach { item ->
                val request = coil3.request.ImageRequest.Builder(app).data(Uri.parse(item.uri)).size(480, 320)
                    .memoryCacheKey("${item.id}/${item.modified}/${item.size}/false").build()
                assertTrue(coil3.SingletonImageLoader.get(app).execute(request) is coil3.request.SuccessResult)
            } }
            withContext(Dispatchers.IO) {
                val request = coil3.request.ImageRequest.Builder(app).data(Uri.parse(media.first().uri)).size(480, 320)
                    .memoryCacheKey("${song.id}/${song.modified}/${song.size}/false").build()
                assertTrue(coil3.SingletonImageLoader.get(app).execute(request) is coil3.request.SuccessResult)
            }
        }
        val launcher = Robolectric.buildActivity(LauncherActivity::class.java).create().get()
        val controller = Robolectric.buildActivity(MainPanelActivity::class.java, shadowOf(launcher).nextStartedActivity).setup().visible()
        val activity = controller.get()
        activity.windowModel.go("PHOTO")
        fun capture(name: String) {
            compose.waitForIdle()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))
            val view = activity.window.decorView
            assertTrue(view.width > 0 && view.height > 0)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            var colors = emptySet<Int>()
            val deadline = System.nanoTime() + 5_000_000_000L
            do {
                compose.waitForIdle()
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
                view.draw(Canvas(bitmap))
                colors = (0 until bitmap.height step 20).flatMap { y ->
                    (0 until bitmap.width step 20).map { x -> bitmap.getPixel(x, y) }
                }.toSet()
                if (colors.size <= 10) Thread.sleep(20) // Wait for the actual first Compose frame, not a fake loading delay.
            } while (colors.size <= 10 && System.nanoTime() < deadline)
            val destination = File("../qa/rendered/$name.png").absoluteFile
            destination.parentFile!!.mkdirs()
            destination.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
            assertTrue("$name: rendered frame must contain actual UI (${colors.size} sampled colors)", colors.size > 10)
        }
        runBlocking { withTimeout(10_000) { activity.windowModel.listing.first { it.initialized && it.items.size == 4 } } }
        capture("library-$layout")
        app.session.updateMediaContext(media.map { it.id }); app.session.selectMedia("a")
        app.comparison.update(app.comparison.state.value.pin("b", media.map { it.id })!!)
        activity.windowModel.go("COMPARE")
        capture("comparison-two-$layout")
        var state = app.comparison.state.value.resize(4)!!
        state = state.pin("c", media.map { it.id })!!
        state = state.pin("d", media.map { it.id })!!
        app.comparison.update(state)
        capture("comparison-four-$layout")
        // Visual fixture only: the MediaSession connection is intentionally unavailable.
        app.music.state.value = PlaybackState(id = song.id, title = song.title!!, artist = song.artist!!,
            position = 45_000, duration = 180_000, queue = listOf(song.id))
        activity.windowModel.go("PLAYER")
        capture("player-$layout")
        activity.windowModel.go("SOURCES")
        capture("sources-$layout")
        runBlocking { app.preferences.save(GalleryPreferences(themeId = GalleryThemes.WHITE, reducedMotion = true)) }
        activity.windowModel.go("SETTINGS")
        capture("settings-light-$layout")
        controller.pause().stop().destroy()
    }
}

private fun capturePanel(activity: PanelActivity, name: String) {
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))
    val view = activity.window.decorView
    assertTrue(view.width > 0 && view.height > 0)
    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    view.draw(Canvas(bitmap))
    val colors = (0 until bitmap.height step 8).flatMap { y ->
        (0 until bitmap.width step 8).map { x -> bitmap.getPixel(x, y) }
    }.toSet()
    val destination = File("../qa/rendered/$name.png").absoluteFile
    destination.parentFile!!.mkdirs()
    destination.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    bitmap.recycle()
    assertTrue("$name: actual UI", colors.size > 10)
}

class PreviewFixtureProvider(private val preparedPng: ByteArray? = null) : ContentProvider() {
    override fun onCreate() = true
    @Synchronized override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r")
        val id = DocumentsContract.getDocumentId(uri)
        val file = File(context!!.cacheDir, "preview-$id.png")
        if (!file.exists() && preparedPng != null) file.writeBytes(preparedPng)
        if (!file.exists()) {
            val image = Bitmap.createBitmap(480, 320, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(image)
            canvas.drawColor(when (id) { "a" -> Color.rgb(49, 69, 109); "b" -> Color.rgb(93, 48, 91); "c" -> Color.rgb(38, 92, 88); else -> Color.rgb(115, 80, 36) })
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(228, 219, 255) }
            canvas.drawCircle(240f, 145f, 88f, paint)
            paint.color = Color.rgb(36, 30, 60); paint.textSize = 64f
            canvas.drawText(id.uppercase(), 220f, 164f, paint)
            file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val documentId = DocumentsContract.getDocumentId(uri)
        val columns = projection ?: emptyArray()
        if ("children" in uri.pathSegments) {
            val app = context!!.applicationContext as GalleryApp
            val albums = kotlinx.coroutines.runBlocking { app.catalog.dao.albums() }
            val folder = albums.firstOrNull { runCatching { DocumentsContract.getDocumentId(Uri.parse(it.documentUri)) }.getOrNull() == documentId }
                ?: albums.firstOrNull { if (documentId == "root") it.parentId == null else it.id == documentId }
            val media = kotlinx.coroutines.runBlocking { app.catalog.dao.media() }.filter {
                Uri.parse(it.uri).authority == uri.authority && !it.trashed && it.available && (folder == null || it.albumId == folder.id)
            }
            return android.database.MatrixCursor(columns).apply {
                albums.filter { it.id != folder?.id && (it.parentId == folder?.id || (documentId == "root" && it.parentId == null)) }.forEach { child ->
                    addRow(columns.map { column -> when (column) {
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID -> runCatching { DocumentsContract.getDocumentId(Uri.parse(child.documentUri)) }.getOrDefault(child.id)
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME -> child.name
                        DocumentsContract.Document.COLUMN_MIME_TYPE -> DocumentsContract.Document.MIME_TYPE_DIR
                        else -> 0
                    } })
                }
                media.forEach { item -> addRow(columns.map { column -> when (column) {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID -> DocumentsContract.getDocumentId(Uri.parse(item.uri))
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME -> item.name
                    DocumentsContract.Document.COLUMN_MIME_TYPE -> item.mime
                    DocumentsContract.Document.COLUMN_SIZE -> item.size
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED -> item.modified
                    else -> 0
                } }) }
            }
        }
        return android.database.MatrixCursor(columns).apply {
            addRow(columns.map { when (it) {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> documentId
                DocumentsContract.Document.COLUMN_DISPLAY_NAME -> "QA fixture"
                DocumentsContract.Document.COLUMN_MIME_TYPE -> DocumentsContract.Document.MIME_TYPE_DIR
                else -> 0
            } })
        }
    }
    override fun getType(uri: Uri) = "image/png"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
