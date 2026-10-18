package it.ayngallery

import android.content.*
import androidx.activity.compose.setContent
import android.content.pm.ProviderInfo
import android.graphics.*
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
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.time.Duration

/** Native UI captures with original illustrations and fictitious metadata, never device media. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestGalleryApp::class, qualifiers = "en-w960dp-h540dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class DocumentationScreenshotTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun captureDemoGallery() {
        val app = RuntimeEnvironment.getApplication() as GalleryApp
        shadowOf(app).declareComponentUnbindable(ComponentName(app, MusicService::class.java))
        val provider = PreviewFixtureProvider()
        provider.attachInfo(app, ProviderInfo().apply { authority = "gallery.demo"; exported = false })
        ShadowContentResolver.registerProviderInternal("gallery.demo", provider)
        val tree = DocumentsContract.buildTreeDocumentUri("gallery.demo", "root")
        fun uri(id: String) = DocumentsContract.buildDocumentUriUsingTree(tree, id).toString()
        val albums = listOf("Landscapes", "Illustrations", "Wallpapers").mapIndexed { index, name ->
            AlbumEntity("demo-album$index", "gallery.demo:folder$index", uri("folder$index"), name, name, null, coverMediaId = "doc${index * 3 + index}") }
        val images = (0..8).map { index ->
            val file = File(app.cacheDir, "preview-doc$index.png")
            val bitmap = Bitmap.createBitmap(960, 600, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap); val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val sky = listOf(0xff705caf.toInt(), 0xff315878.toInt(), 0xffbb7055.toInt())[index % 3]
            paint.shader = LinearGradient(0f, 0f, 0f, 600f, sky, 0xffedc4ae.toInt(), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, 960f, 600f, paint)
            paint.shader = null; paint.color = 0xffffe2be.toInt(); canvas.drawCircle(680f - index * 25f, 165f, 58f, paint)
            paint.color = listOf(0xff273355.toInt(), 0xff183e4d.toInt(), 0xff663951.toInt())[index % 3]
            canvas.drawPath(Path().apply {
                moveTo(0f, 450f); lineTo(240f, 190f + index * 8); lineTo(450f, 420f); lineTo(700f, 285f)
                lineTo(960f, 475f); lineTo(960f, 600f); lineTo(0f, 600f); close()
            }, paint)
            paint.color = 0xffb5d2cc.toInt(); paint.alpha = 110; canvas.drawRect(0f, 480f, 960f, 600f, paint)
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }; bitmap.recycle()
            MediaEntity("doc$index", "gallery.demo:doc$index", uri("doc$index"), albums[index / 3].id, "Landscape_${index + 1}.png",
                "image/png", "IMAGE", file.length(), 1791374400000L - index * 86400000L, favorite = index == 1)
        }
        val songs = listOf("Evening Light", "Slow Horizons", "Open Skies").mapIndexed { index, name ->
            MediaEntity("demo-song$index", "gallery.demo:song$index", uri("song$index"), albums.first().id,
                "$name.mp3", "audio/mpeg", "AUDIO", 4200000, 1791374400000L, duration = 180000,
                title = name, artist = "Demo Artist", musicAlbum = "Horizons", artworkMediaId = "doc1") }
        var controller: org.robolectric.android.controller.ActivityController<MainPanelActivity>? = null
        try {
            runBlocking {
                app.preferences.save(GalleryPreferences(themeId = "stocking_pink", reducedMotion = true))
                app.preferences.galleryView(true, grid = true, columns = 3); app.preferences.galleryView(false, "MUSIC", grid = false)
                app.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                app.catalog.dao.saveSource(SourceEntity("demo-source", tree.toString(), "Demo library", "MIXED"))
                app.catalog.dao.saveAlbums(albums); app.catalog.dao.saveAncestors(albums.map { AlbumAncestor(it.id, it.id) })
                app.catalog.dao.saveAlbumSources(albums.map { AlbumSource(it.id, "demo-source", 1) })
                app.catalog.dao.saveMedia(images + songs); app.catalog.dao.saveMembership((images + songs).map { MediaSource(it.id, "demo-source", 1) })
                withTimeout(10000) { app.catalog.snapshot.first { it.visibleMedia.size == 12 } }
                withContext(Dispatchers.IO) { images.forEach { image ->
                    assertTrue(coil3.SingletonImageLoader.get(app).execute(coil3.request.ImageRequest.Builder(app)
                        .data(Uri.parse(image.uri)).size(960, 600).build()) is coil3.request.SuccessResult)
                } }
            }
            val launcher = Robolectric.buildActivity(LauncherActivity::class.java).create().get()
            controller = Robolectric.buildActivity(MainPanelActivity::class.java, shadowOf(launcher).nextStartedActivity).setup().visible()
            val panel = controller.get()
            fun capture(name: String) {
                compose.waitForIdle()
                val view = panel.window.decorView
                var rendered: Bitmap? = null
                // Image decoding runs outside the Compose clock: wait for pixels, not just an idle UI.
                compose.waitUntil(8000) {
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25))
                    val frame = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                    view.draw(Canvas(frame))
                    val colors = (0 until frame.height step 20).flatMap { y -> (0 until frame.width step 20).map { x -> frame.getPixel(x, y) } }.toSet()
                    if (colors.size > (if (name == "settings") 4 else 10)) { rendered = frame; true } else { frame.recycle(); false }
                }
                val bitmap = checkNotNull(rendered) { "$name must contain a rendered frame" }
                File("../qa/rendered/documentation-$name.png").apply { parentFile!!.mkdirs() }.outputStream()
                    .use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }; bitmap.recycle()
            }
            fun pair(name: String, upper: String, lower: String) {
                val directory = File("../qa/rendered").apply { mkdirs() }
                val upperBitmap = BitmapFactory.decodeFile(File(directory, "documentation-$upper.png").path)
                val lowerBitmap = BitmapFactory.decodeFile(File(directory, "documentation-$lower.png").path)
                val width = maxOf(upperBitmap.width, lowerBitmap.width)
                val scaleUpper = width.toFloat() / upperBitmap.width
                val scaleLower = width.toFloat() / lowerBitmap.width
                val upperHeight = (upperBitmap.height * scaleUpper).toInt()
                val lowerHeight = (lowerBitmap.height * scaleLower).toInt()
                val labelHeight = 44
                val bitmap = Bitmap.createBitmap(width, upperHeight + lowerHeight + labelHeight * 2, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap).apply { drawColor(0xff10131d.toInt()) }
                val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xfff3efff.toInt(); textSize = 22f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
                label.textAlign = Paint.Align.LEFT
                canvas.drawText("UPPER DISPLAY · PREVIEW", 24f, 30f, label)
                canvas.drawBitmap(upperBitmap, null, Rect(0, labelHeight, width, labelHeight + upperHeight), Paint(Paint.FILTER_BITMAP_FLAG))
                val lowerTop = labelHeight + upperHeight
                canvas.drawText("LOWER DISPLAY · BROWSE", 24f, lowerTop + 30f, label)
                canvas.drawBitmap(lowerBitmap, null, Rect(0, lowerTop + labelHeight, width, lowerTop + labelHeight + lowerHeight), Paint(Paint.FILTER_BITMAP_FLAG))
                File(directory, "documentation-dual-$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                upperBitmap.recycle(); lowerBitmap.recycle(); bitmap.recycle()
            }
            panel.windowModel.go("ALBUM"); compose.onNodeWithTag("album:${albums.first().id}").assertExists(); capture("albums")
            panel.windowModel.go("PHOTO"); compose.waitUntil(5000) { compose.onAllNodesWithTag("media:doc0").fetchSemanticsNodes().isNotEmpty() }; capture("photos")
            app.session.updateMediaContext(images.map { it.id }); app.session.selectMedia("doc0")
            panel.windowModel.open("doc0"); compose.onNodeWithTag("fullscreen-preview").assertExists(); capture("preview")
            panel.windowModel.go("MUSIC"); compose.waitUntil(5000) { compose.onAllNodesWithTag("media:demo-song0").fetchSemanticsNodes().isNotEmpty() }; capture("music")
            app.music.state.value = PlaybackState(id = songs.first().id, title = songs.first().title!!, artist = "Demo Artist", position = 45000, duration = 180000, queue = songs.map { it.id })
            val demoPrefs = app.preferences.state.value
            val demoPlayback = app.music.state.value
            val demoData = app.catalog.snapshot.value
            panel.setContent { GalleryDesign(demoPrefs) {
                PreviewMusicPanel(app, demoPlayback, demoData, demoPrefs, fullPlayer = true)
            } }
            compose.onNodeWithTag("preview-music-panel").assertExists(); capture("player")
            panel.setContent { GalleryDesign(demoPrefs) { GalleryHome(panel) } }
            panel.windowModel.go("SETTINGS_HUB"); compose.onNodeWithTag("settings-category:INTERFACE").assertExists(); capture("settings")
            panel.setContent { GalleryDesign(demoPrefs) { SettingsPreviewScreen(app, demoData, demoPrefs, "INTERFACE") } }
            compose.onNodeWithTag("settings-preview").assertExists(); capture("settings-preview")
            pair("albums", "preview", "albums")
            pair("music", "player", "music")
            pair("settings", "settings-preview", "settings")
        } finally { controller?.pause()?.stop()?.destroy(); app.onTerminate() }
    }
}
