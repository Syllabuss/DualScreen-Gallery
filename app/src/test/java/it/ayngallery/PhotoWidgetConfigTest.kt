package it.ayngallery

import android.app.Activity
import android.appwidget.*
import android.content.*
import android.content.pm.ProviderInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.provider.DocumentsContract
import androidx.compose.ui.graphics.toArgb
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestGalleryApp::class, qualifiers = "it-w360dp-h640dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class PhotoWidgetConfigTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = RuntimeEnvironment.getApplication() as GalleryApp
    @After fun close() { app.onTerminate() }
    private fun setup(): Int {
        val provider = FrameFixtureProvider()
        provider.attachInfo(app, ProviderInfo().apply { authority = "ayngallery.photoconfig"; exported = true; grantUriPermissions = true })
        ShadowContentResolver.registerProviderInternal("ayngallery.photoconfig", provider)
        val tree = DocumentsContract.buildTreeDocumentUri("ayngallery.photoconfig", "root")
        app.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runBlocking {
            app.preferences.save(GalleryPreferences(themeId = "rose_milk", reducedMotion = true))
            app.catalog.dao.saveSource(SourceEntity("source", tree.toString(), "Fixture", "VISUAL"))
            app.catalog.dao.saveAlbums(listOf(AlbumEntity("folder", "ayngallery.photoconfig:root", DocumentsContract.buildDocumentUriUsingTree(tree, "root").toString(), "Immagini personali", "Immagini personali", null)))
            app.catalog.dao.saveAncestors(listOf(AlbumAncestor("folder", "folder")))
            app.catalog.dao.saveAlbumSources(listOf(AlbumSource("folder", "source", 1)))
            val images = listOf("a", "b").map { MediaEntity(it, "fixture:$it", DocumentsContract.buildDocumentUriUsingTree(tree, it).toString(), "folder", "$it.png", "image/png", "IMAGE", 100, 1) }
            app.catalog.dao.saveMedia(images); app.catalog.dao.saveMembership(images.map { MediaSource(it.id, "source", 1) })
            withTimeout(5000) { app.catalog.snapshot.first { it.initialized && it.media.size == 2 } }
        }
        val host = shadowOf(AppWidgetManager.getInstance(app)); val id = host.createWidget(PhotoWidgetProvider::class.java, R.layout.photo_widget)
        host.putWidgetInfo(id, AppWidgetProviderInfo().apply { this.provider = ComponentName(app, PhotoWidgetProvider::class.java); initialLayout = R.layout.photo_widget })
        return id
    }
    @Test fun cropDialogSupportsControllerPanAndSavesOnlyTheWidgetFraming() {
        val id = setup()
        app.photoWidgets.settings.save(id, PhotoWidgetConfig(fixedMedia = "a", seconds = null))
        val controller = Robolectric.buildActivity(PhotoWidgetConfigActivity::class.java,
            Intent(app, PhotoWidgetConfigActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)).setup().visible().windowFocusChanged(true)
        try {
            compose.waitForIdle()
            compose.onNodeWithTag("photo-config-list").performScrollToNode(hasTestTag("photo-config-crop"))
            compose.onNodeWithTag("photo-config-crop").performClick()
            compose.onNodeWithTag("photo-widget-crop-preview").assertIsDisplayed()
            val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView
            val nativeRoot: Any = org.robolectric.util.ReflectionHelpers.callInstanceMethod(dialog, "getViewRootImpl")
            org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowViewRootImpl>(nativeRoot).callWindowFocusChanged(true)
            controller.windowFocusChanged(false)
            compose.waitForIdle()
            capture(dialog, "photo-widget-crop-0.81")
            val event = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_LEFT)
            assertTrue(org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView.dispatchKeyEvent(event))
            compose.waitForIdle()
            assertEquals(0f, app.photoWidgets.settings.get(id).panX, 0f)
            val props = android.view.MotionEvent.PointerProperties().apply { this.id = 0 }
            val coords = android.view.MotionEvent.PointerCoords().apply { setAxisValue(android.view.MotionEvent.AXIS_Z, .5f) }
            val motion = android.view.MotionEvent.obtain(android.os.SystemClock.uptimeMillis(), android.os.SystemClock.uptimeMillis(),
                android.view.MotionEvent.ACTION_MOVE, 1, arrayOf(props), arrayOf(coords), 0, 0, 1f, 1f, 40, 0, android.view.InputDevice.SOURCE_JOYSTICK, 0)
            try { assertTrue(dialog.dispatchGenericMotionEvent(motion)) } finally { motion.recycle() }
            compose.waitForIdle()

            assertTrue(org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView.dispatchKeyEvent(
                android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BUTTON_A)))
            compose.waitForIdle()
            compose.onNodeWithTag("photo-widget-crop-preview").assertDoesNotExist()
            controller.windowFocusChanged(true)
            compose.onNodeWithTag("photo-config-save").performClick()
            compose.waitUntil(5000) { shadowOf(Looper.getMainLooper()).idle(); controller.get().isFinishing }
            assertEquals(-.0375f, app.photoWidgets.settings.get(id).panX, .001f)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun albumFlipperUsesSecondsAndRechecksAccessBeforeDecodingEachFrame() {
        val id = setup()
        val config = PhotoWidgetConfig(FrameConfig(scope = "SELECTED", albums = setOf("folder")), seconds = 5, fade = true)
        app.photoWidgets.settings.save(id, config)
        assertEquals(config, PhotoWidgetConfig.decode(config.encode()))
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val frame = photoWidgetViews(app, id, config, GalleryPreferences(), app.catalog.snapshot.value.media.first(), bitmap, 2, null)
            .apply(app, android.widget.FrameLayout(app))
        val flipper = frame.findViewById<android.widget.AdapterViewFlipper>(R.id.photo_slideshow)
        assertEquals(5000, flipper.flipInterval); assertTrue(flipper.isAutoStart)
        assertEquals(350L, flipper.inAnimation.duration)
        val service = Robolectric.buildService(PhotoWidgetAlbumService::class.java).create()
        try {
            val factory = service.get().onGetViewFactory(Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
            factory.onCreate(); factory.onDataSetChanged()
            assertTrue(factory.count > 0)
            val picture = factory.getViewAt(0).apply(app, android.widget.FrameLayout(app)).findViewById<android.widget.ImageView>(R.id.photo_frame)
            assertNotNull(picture.drawable)
            val work = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val hiding = work.async { app.catalog.hideAlbums(listOf("folder"), true) }
                compose.waitUntil(5000) { shadowOf(Looper.getMainLooper()).idle(); hiding.isCompleted }
                runBlocking { hiding.await() }
            } finally { work.cancel() }
            val excluded = factory.getViewAt(0).apply(app, android.widget.FrameLayout(app)).findViewById<android.widget.ImageView>(R.id.photo_frame)
            assertNull(excluded.drawable)
            factory.onDestroy()
        } finally { service.destroy() }
    }
    @Test fun largeTextDraftRotationCancelSaveAndPerWidgetScopesUseTheActualConfigurationActivity() {
        RuntimeEnvironment.setFontScale(1.4f); val id = setup()
        val intent = Intent(app, PhotoWidgetConfigActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        var controller = Robolectric.buildActivity(PhotoWidgetConfigActivity::class.java, intent).setup().visible().windowFocusChanged(true)
        compose.waitForIdle()
        compose.onNodeWithTag("photo-config-mode:ALBUM").assertExists()
        compose.onNodeWithTag("photo-config-save").assertIsNotEnabled()
        compose.onNodeWithTag("photo-config-list").performScrollToNode(hasTestTag("photo-config-album:folder"))
        compose.onNodeWithTag("photo-config-album:folder").performClick().assertIsSelected()
        compose.onNodeWithTag("photo-config-list").performScrollToNode(hasTestTag("photo-config-interval:60"))
        compose.onNodeWithTag("photo-config-interval:60").performClick()
        assertEquals(PhotoWidgetConfig(), app.photoWidgets.settings.get(id))
        controller.recreate().windowFocusChanged(true); compose.waitForIdle()
        compose.onNodeWithTag("photo-config-list").performScrollToNode(hasTestTag("photo-config-interval:60"))
        compose.onNodeWithTag("photo-config-interval:60").assertIsSelected()
        compose.onNodeWithTag("photo-config-save").assertIsDisplayed().assertIsEnabled()
        capture(controller.get().window.decorView, "photo-widget-config-large-text")
        assertEquals(GalleryThemes.get("rose_milk").background.toArgb(), controller.get().window.navigationBarColor)
        compose.onNodeWithContentDescription("Annulla").performClick()
        compose.onNodeWithText("Modifiche non salvate").assertIsDisplayed()
        compose.onNodeWithText("Continua a modificare").performClick()
        assertFalse(controller.get().isFinishing)
        compose.onNodeWithContentDescription("Annulla").performClick()
        compose.onNodeWithText("Esci senza salvare").performClick()
        assertEquals(Activity.RESULT_CANCELED, shadowOf(controller.get()).resultCode); assertEquals(PhotoWidgetConfig(), app.photoWidgets.settings.get(id))
        controller.pause().stop().destroy()
        controller = Robolectric.buildActivity(PhotoWidgetConfigActivity::class.java, intent).setup().visible().windowFocusChanged(true)
        compose.waitForIdle(); compose.onNodeWithTag("photo-config-mode:PHOTO").performClick()
        compose.onNodeWithTag("photo-config-save").assertIsNotEnabled()
        compose.onNodeWithTag("photo-config-grid").performScrollToNode(hasTestTag("photo-config-media:b"))
        compose.onNodeWithTag("photo-config-media:b").performClick()
        compose.onNodeWithTag("photo-config-save").performClick()
        compose.waitUntil(7000) { shadowOf(Looper.getMainLooper()).idle(); controller.get().isFinishing }
        assertEquals(Activity.RESULT_OK, shadowOf(controller.get()).resultCode)
        assertEquals("b", app.photoWidgets.settings.get(id).fixedMedia)
        assertEquals("b", app.photoWidgets.settings.cursor(id).current)
        assertEquals(PhotoWidgetConfig(), app.photoWidgets.settings.get(id + 100))
        assertNull(shadowOf(controller.get()).nextStartedActivity)
        controller.pause().stop().destroy()
    }
    @Test fun unavailableOldAlbumCannotBeSavedUntilAnAccessibleSelectionIsChosen() {
        val id = setup()
        val old = PhotoWidgetConfig(FrameConfig(scope = "SELECTED", albums = setOf("old-unavailable-album")))
        // Configure through the same lock as live widget updates; raw preference writes
        // can race the first shape update triggered when the fixture widget is created.
        val configured = app.catalog.scope.async { app.photoWidgets.configure(id, old) }
        compose.waitUntil(10000) { shadowOf(Looper.getMainLooper()).idle(); configured.isCompleted }
        runBlocking { configured.await() }
        val controller = Robolectric.buildActivity(PhotoWidgetConfigActivity::class.java,
            Intent(app, PhotoWidgetConfigActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)).setup().visible().windowFocusChanged(true)
        compose.waitForIdle()
        compose.onNodeWithTag("photo-config-save").assertIsNotEnabled()
        compose.onNodeWithTag("photo-config-mode:COLLECTION").assertDoesNotExist()
        compose.onNodeWithTag("photo-config-save").assertIsNotEnabled()
        compose.onNodeWithTag("photo-config-mode:ALBUM").performClick()
        compose.onNodeWithTag("photo-config-list").performScrollToNode(hasTestTag("photo-config-album:folder"))
        compose.onNodeWithTag("photo-config-album:folder").performClick()
        compose.onNodeWithTag("photo-config-save").assertIsEnabled()
        compose.onNodeWithContentDescription("Annulla").performClick()
        compose.onNodeWithText("Modifiche non salvate").assertIsDisplayed()
        compose.onNodeWithText("Continua a modificare").performClick()
        assertFalse(controller.get().isFinishing)
        compose.onNodeWithContentDescription("Annulla").performClick()
        compose.onNodeWithText("Esci senza salvare").performClick()
        assertEquals(old, app.photoWidgets.settings.get(id).copy(cropAspect = null))
        controller.pause().stop().destroy()
    }
    @Test fun revokedFrozenPhotoIsRejectedByTheRealPanelWithoutReclaimingTheOtherDisplay() {
        val id = setup()
        shadowOf(app).declareComponentUnbindable(ComponentName(app, MusicService::class.java))
        val request = Intent(app, LauncherActivity::class.java).setAction(PhotoWidgetProvider.OPEN)
        PhotoWidgetTarget(id, "a", 1, 100).put(request)
        val launcher = Robolectric.buildActivity(LauncherActivity::class.java, request).create().get()
        val next = shadowOf(launcher).nextStartedActivity
        runBlocking { app.catalog.hideAlbums(listOf("folder"), true) }
        val panel = Robolectric.buildActivity(MainPanelActivity::class.java, next).setup().visible().windowFocusChanged(true)
        compose.waitUntil(5000) { shadowOf(Looper.getMainLooper()).idle(); app.session.error != null }
        assertNull(panel.get().windowModel.route.value.viewer)
        assertTrue(app.session.error!!.contains("non è più disponibile"))
        assertNull(shadowOf(panel.get()).nextStartedActivity)
        panel.pause().stop().destroy()
    }
    private fun capture(view: android.view.View, name: String) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888); view.draw(Canvas(bitmap))
        val file = File("../qa/rendered/$name.png").absoluteFile; file.parentFile!!.mkdirs(); file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
}
