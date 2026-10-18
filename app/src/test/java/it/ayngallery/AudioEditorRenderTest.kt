package it.ayngallery

import android.content.*
import android.content.pm.ProviderInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Looper
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
import java.time.Duration

/** Actual Compose UI; metadata is synthetic, no audio playback/export codec proof. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestGalleryApp::class, qualifiers = "it-w360dp-h640dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class AudioEditorRenderTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = RuntimeEnvironment.getApplication() as GalleryApp
    private var activity: ActivityController<MainPanelActivity>? = null
    @After fun close() { activity?.pause()?.stop()?.destroy(); app.onTerminate() }
    private fun pump(done: () -> Boolean) { compose.waitUntil(10000) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25)); done() } }
    @Test fun largeTextDraftSurvivesRecreationWithoutListeningAndUsesNativeDestinationConfirmation() {
        app.resources.configuration.fontScale = 1.4f
        shadowOf(app).declareComponentUnbindable(ComponentName(app, MusicService::class.java))
        val provider = OperationDocuments()
        provider.attachInfo(app, ProviderInfo().apply { authority = "ayngallery.audioui"; exported = true; grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission })
        ShadowContentResolver.registerProviderInternal("ayngallery.audioui", provider)
        val bytes = File("src/test/resources/media/audio-segments.m4a").readBytes()
        val node = provider.nodes.getValue("image"); node.file!!.writeBytes(bytes)
        provider.nodes["image"] = node.copy(name = "song.m4a", mime = "audio/mp4")
        val tree = DocumentsContract.buildTreeDocumentUri("ayngallery.audioui", "root")
        val media = runBlocking {
            app.preferences.save(GalleryPreferences(reducedMotion = true))
            app.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            val source = SourceEntity("source", tree.toString(), "QA audio", "MUSIC")
            app.catalog.dao.saveSource(source); SourceScanner(app.catalog).scan(source)
            app.catalog.dao.media().single().also { app.catalog.favoriteAll(listOf(it.id), true) }
        }
        val metadata = DataSource.toDataSource(app, Uri.parse(media.uri))
        ShadowMediaMetadataRetriever.addMetadata(metadata, MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO, "yes")
        ShadowMediaMetadataRetriever.addMetadata(metadata, MediaMetadataRetriever.METADATA_KEY_DURATION, "6000")
        runBlocking { withTimeout(5000) { app.catalog.snapshot.first { it.byId[media.id]?.favorite == true } } }
        val launcher = Robolectric.buildActivity(LauncherActivity::class.java).create().get()
        activity = Robolectric.buildActivity(MainPanelActivity::class.java, shadowOf(launcher).nextStartedActivity).setup().visible()
        activity!!.get().windowModel.open(media.id); compose.waitForIdle()
        compose.onNodeWithContentDescription("Opzioni del media").performClick()
        compose.onNodeWithText("Modifica audio…").performScrollTo().performClick()
        pump { compose.onAllNodesWithTag("audio-time-range").fetchSemanticsNodes().isNotEmpty() }
        capture("audio-editor-large-text")
        compose.onNodeWithTag("audio-start").performScrollTo().performTextReplacement("2,125")
        compose.onNodeWithTag("audio-end").performScrollTo().performTextReplacement("3.875")
        compose.onNodeWithTag("audio-export-confirm").assertIsEnabled()
        compose.onNodeWithTag("audio-segment-preview").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("audio-apply-range").assertDoesNotExist()
        compose.onNodeWithTag("audio-segment-summary").performScrollTo().assertTextContains("0:01,750", substring = true)
        compose.onNodeWithTag("audio-preview-seek").performScrollTo().assertExists()
        compose.onNodeWithTag("audio-preview-position").performScrollTo().assertTextContains("0:03,875", substring = true)
        compose.onNodeWithTag("audio-bitrate:256").performScrollTo().performClick()
        compose.onNodeWithTag("audio-export-name").performScrollTo().performTextReplacement("QA audio")
        activity!!.recreate(); compose.waitForIdle()
        pump { compose.onAllNodesWithTag("audio-time-range").fetchSemanticsNodes().isNotEmpty() }
        assertNull(app.music.previewToken.value)
        compose.onNodeWithTag("audio-bitrate:256").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("audio-start").performScrollTo().performTextReplacement("bad")
        compose.onNodeWithTag("audio-export-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("audio-apply-range").assertDoesNotExist()
        compose.onNodeWithTag("audio-editor-error").performScrollTo().assertExists()
        compose.onNodeWithTag("audio-start").performScrollTo().performTextReplacement("2.125")
        compose.onNodeWithTag("audio-apply-range").assertDoesNotExist()
        compose.onNodeWithTag("audio-export-confirm").assertIsEnabled().performClick()
        val picker = shadowOf(activity!!.get()).nextStartedActivityForResult
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, picker.intent.action)
        assertNotNull(picker.intent.getParcelableExtra<Uri>(DocumentsContract.EXTRA_INITIAL_URI))
        assertTrue(runBlocking { app.catalog.dao.operations().isEmpty() })
        shadowOf(activity!!.get()).receiveResult(picker.intent, Activity.RESULT_CANCELED, null)
        compose.waitForIdle()
        compose.onNodeWithTag("audio-editor-cancel").performClick()
        assertArrayEquals(bytes, node.file!!.readBytes()); assertTrue(provider.deletions.isEmpty())
        assertTrue(runBlocking { app.catalog.dao.media(media.id)!!.favorite }); assertTrue(runBlocking { app.catalog.dao.operations().isEmpty() })
    }
    private fun capture(name: String) {
        compose.waitForIdle(); val view = activity!!.get().window.decorView
        val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888); view.draw(Canvas(image))
        val file = File("../qa/rendered/$name.png").absoluteFile
        file.parentFile!!.mkdirs(); file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }; image.recycle()
    }
}
