package it.ayngallery

import android.content.*
import android.content.pm.ProviderInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowContentResolver
import android.app.Activity
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import org.robolectric.shadows.util.DataSource
import java.io.File

/** Production Compose UI with a synthetic metadata/poster decoder; no claim of codec playback. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestGalleryApp::class, qualifiers = "it-w360dp-h640dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class VideoEditorRenderTest {
    // Decoder IO completes on worker threads; resume Compose effects on the UI looper.
    // The Compose test rule owns looper draining; do not advance it inside wait predicates.
    @OptIn(ExperimentalTestApi::class)
    @get:Rule val compose = createEmptyComposeRule(effectContext = Dispatchers.Main)
    private val app get() = RuntimeEnvironment.getApplication() as GalleryApp
    private var activity: ActivityController<MainPanelActivity>? = null
    private lateinit var provider: OperationDocuments
    private lateinit var bytes: ByteArray
    private lateinit var media: MediaEntity
    private lateinit var metadata: DataSource
    @After fun close() { activity?.pause()?.stop()?.destroy(); app.onTerminate() }
    private fun pump(done: () -> Boolean) { compose.waitUntil(10000) { done() } }
    @Test fun largeTextDraftRangeCropAndRecreationUseNativeDestinationAndCancelKeepsOriginal() {
        app.resources.configuration.fontScale = 1.4f
        shadowOf(app).declareComponentUnbindable(ComponentName(app, MusicService::class.java))
        prepareFixture()
        val node = provider.nodes.getValue("image")
        val launcher = Robolectric.buildActivity(LauncherActivity::class.java).create().get()
        activity = Robolectric.buildActivity(MainPanelActivity::class.java, shadowOf(launcher).nextStartedActivity).setup().visible()
        activity!!.get().windowModel.open(media.id); compose.waitForIdle()
        pump { compose.onAllNodesWithContentDescription("Opzioni del media").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Opzioni del media").performClick()
        compose.onNodeWithText("Modifica video…").performScrollTo().performClick()
        pump { compose.onAllNodesWithTag("video-crop-canvas").fetchSemanticsNodes().isNotEmpty() }
        capture("video-editor-large-text")
        compose.onNodeWithTag("video-start").performScrollTo().performTextReplacement("2,125")
        compose.onNodeWithTag("video-end").performScrollTo().performTextReplacement("4")
        compose.onNodeWithTag("video-apply-range").assertDoesNotExist()
        compose.onNodeWithTag("video-segment-summary").performScrollTo().assertTextContains("0:01,875", substring = true)
        compose.onNodeWithTag("video-preview-seek").performScrollTo().assertExists()
        compose.onNodeWithTag("video-preview-position").performScrollTo().assertTextContains("0:04,000", substring = true)
        compose.onNodeWithText("1:1").performScrollTo().performClick()
        compose.onNodeWithTag("video-export-name").performScrollTo().performTextReplacement("QA video")
        poster(); activity!!.recreate(); compose.waitForIdle()
        pump { compose.onAllNodesWithTag("video-crop-canvas").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("video-start").performScrollTo().performTextReplacement("bad")
        compose.onNodeWithTag("video-export-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("video-apply-range").assertDoesNotExist()
        compose.onNodeWithTag("video-editor-error").performScrollTo().assertExists()
        compose.onNodeWithTag("video-start").performScrollTo().performTextReplacement("2.125")
        compose.onNodeWithTag("video-apply-range").assertDoesNotExist()
        compose.onNodeWithTag("video-export-confirm").assertIsEnabled().performClick()
        val picker = shadowOf(activity!!.get()).nextStartedActivityForResult
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, picker.intent.action)
        assertNotNull(picker.intent.getParcelableExtra<Uri>(DocumentsContract.EXTRA_INITIAL_URI))
        assertTrue(runBlocking { app.catalog.dao.operations().isEmpty() })
        shadowOf(activity!!.get()).receiveResult(picker.intent, Activity.RESULT_CANCELED, null)
        compose.waitForIdle()
        compose.onNodeWithTag("video-editor-cancel").performClick()
        assertArrayEquals(bytes, node.file!!.readBytes()); assertTrue(provider.deletions.isEmpty())
        assertTrue(runBlocking { app.catalog.dao.media(media.id)!!.favorite }); assertTrue(runBlocking { app.catalog.dao.operations().isEmpty() })
    }
    @Test fun pairedEditorShowsOnlyControlsBelowAndTracksExactScrubFramesAbove() {
        prepareFixture()
        org.robolectric.shadows.ShadowDisplayManager.addDisplay("w800dp-h600dp")
        app.session.refreshDisplays()
        val launcher = Robolectric.buildActivity(LauncherActivity::class.java).create().get()
        val intents = listOf(shadowOf(launcher).nextStartedActivity, shadowOf(launcher).nextStartedActivity)
        val intent = intents.single { it.component!!.className == MainPanelActivity::class.java.name }
        activity = Robolectric.buildActivity(MainPanelActivity::class.java, intent).setup().visible().windowFocusChanged(true)
        val upper = activity!!.get()
        val lower = Robolectric.buildActivity(OtherPanelActivity::class.java, Intent(intent).apply { removeExtra("ticket") }).setup().visible()
        try {
            val lowerKey = app.session.screens.single { it.key != upper.screenKey }.key
            org.robolectric.util.ReflectionHelpers.setField(lower.get(), "screenKey", lowerKey)
            org.robolectric.util.ReflectionHelpers.getField<MutableSet<String>>(app.session, "allowed").add(lowerKey)
            app.session.swap()
            app.session.openMediaEditor(upper, ExternalRequest(ExternalAction.OPEN, listOf(ExternalTarget.from(media))).encode())
            pump { compose.onAllNodesWithTag("video-time-range").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag("video-edit-partner-canvas").fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithTag("media-editor-page").assertCountEquals(1)
            compose.onNodeWithTag("video-crop-canvas").assertDoesNotExist()
            compose.onNodeWithTag("gallery-header").assertDoesNotExist()
            compose.onNodeWithTag("video-start").performScrollTo().performTextReplacement("2.125")
            compose.onNodeWithTag("video-end").performScrollTo().performTextReplacement("4")
            compose.onNodeWithTag("video-preview-seek").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(3000f) }
            pump { app.session.mediaEditor?.previewPosition == 3000L }
            assertEquals(lower.get().slot, app.session.mediaEditor!!.ownerSlot)
            val recipe = VideoRecipe.decode(app.session.mediaEditor!!.recipe!!)
            assertEquals(2125L, recipe.start); assertEquals(4000L, recipe.end)
            // Distinct decoder fixture at the scrub position verifies that the upper canvas follows it.
            pump {
                val view = upper.window.decorView
                val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(image)); val color = image.getPixel(image.width / 2, image.height / 2); image.recycle()
                Color.blue(color) > Color.red(color)
            }
            capture("video-editor-partner")
            compose.onNodeWithTag("editor-preview-play").performClick()
            pump { app.video.player != null }
            assertEquals(upper.slot, app.video.owner)
            val clipping = app.video.player!!.currentMediaItem!!.clippingConfiguration
            assertEquals(2125L, clipping.startPositionMs); assertEquals(4000L, clipping.endPositionMs)
            compose.onNodeWithTag("video-editor-cancel").performClick()
            pump { app.session.mediaEditor == null && app.video.player == null }
            assertArrayEquals(bytes, provider.nodes.getValue("image").file!!.readBytes())
            assertTrue(provider.deletions.isEmpty()); assertTrue(runBlocking { app.catalog.dao.operations().isEmpty() })
        } finally { lower.pause().stop().destroy() }
    }
    private fun prepareFixture() {
        shadowOf(app).declareComponentUnbindable(ComponentName(app, MusicService::class.java))
        provider = OperationDocuments()
        provider.attachInfo(app, ProviderInfo().apply { authority = "ayngallery.videoui"; exported = true; grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission })
        ShadowContentResolver.registerProviderInternal("ayngallery.videoui", provider)
        bytes = File("src/test/resources/media/video-segments.mp4").readBytes()
        val node = provider.nodes.getValue("image"); node.file!!.writeBytes(bytes)
        provider.nodes["image"] = node.copy(name = "video.mp4", mime = "video/mp4")
        val tree = DocumentsContract.buildTreeDocumentUri("ayngallery.videoui", "root")
        media = runBlocking {
            app.preferences.save(GalleryPreferences(reducedMotion = true))
            app.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            val source = SourceEntity("source", tree.toString(), "QA video", "VISUAL")
            app.catalog.dao.saveSource(source); SourceScanner(app.catalog).scan(source)
            app.catalog.dao.media().single().also { app.catalog.favoriteAll(listOf(it.id), true) }
        }
        metadata = DataSource.toDataSource(app, Uri.parse(media.uri))
        mapOf(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO to "yes", MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO to "yes",
            MediaMetadataRetriever.METADATA_KEY_DURATION to "6000", MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH to "160",
            MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT to "120").forEach { (key, value) -> ShadowMediaMetadataRetriever.addMetadata(metadata, key, value) }
        poster()
        runBlocking { withTimeout(5000) { app.catalog.snapshot.first { it.byId[media.id]?.favorite == true } } }
    }
    private fun poster() {
            val image = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
            image.eraseColor(Color.rgb(166, 102, 158))
            ShadowMediaMetadataRetriever.addScaledFrame(metadata, 0, 160, 120, image)
            ShadowMediaMetadataRetriever.addScaledFrame(metadata, 2_125_000, 160, 120, image)
            ShadowMediaMetadataRetriever.addScaledFrame(metadata, 4_000_000, 160, 120, image)
            ShadowMediaMetadataRetriever.addScaledFrame(metadata, 3_999_000, 160, 120, image)
            val next = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
            next.eraseColor(Color.rgb(64, 160, 200))
            ShadowMediaMetadataRetriever.addScaledFrame(metadata, 3_000_000, 160, 120, next)
        }

    private fun capture(name: String) {
        compose.waitForIdle(); val view = activity!!.get().window.decorView
        val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888); view.draw(Canvas(image))
        val file = File("../qa/rendered/$name.png").absoluteFile
        file.parentFile!!.mkdirs(); file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }; image.recycle()
    }
}
