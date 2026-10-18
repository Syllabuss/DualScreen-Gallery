package it.ayngallery

import android.appwidget.*
import android.content.*
import android.content.pm.ProviderInfo
import android.graphics.*
import android.os.*
import android.provider.DocumentsContract
import android.view.View
import android.widget.*
import androidx.compose.ui.graphics.toArgb
import androidx.work.*
import androidx.work.testing.TestListenableWorkerBuilder
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestGalleryApp::class, qualifiers = "it")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class PhotoWidgetTest {
    @Test fun photoWidgetCanShrinkToOneCellAndStillFillsItsBounds() {
        val parser = app.resources.getXml(R.xml.photo_widget)
        while (parser.next() != org.xmlpull.v1.XmlPullParser.START_TAG) { }
        val attrs = app.resources.obtainAttributes(parser, intArrayOf(android.R.attr.minResizeWidth, android.R.attr.minResizeHeight))
        try {
            assertEquals(app.resources.displayMetrics.density, attrs.getDimension(0, 0f), .01f)
            assertEquals(app.resources.displayMetrics.density, attrs.getDimension(1, 0f), .01f)
        } finally { attrs.recycle(); parser.close() }
        val view = android.view.LayoutInflater.from(app).inflate(R.layout.photo_widget, FrameLayout(app), false)
        val exact = View.MeasureSpec.makeMeasureSpec(64, View.MeasureSpec.EXACTLY)
        view.measure(exact, exact); view.layout(0, 0, 64, 64)
        assertEquals(64, view.findViewById<View>(R.id.photo_fit).width)
        assertEquals(64, view.findViewById<View>(R.id.photo_fit).height)
    }

    @Test fun albumOrderAndSequenceSurviveHostRecreation() {
        val items = listOf("a", "b", "c", "d").map { id -> MediaEntity(id, "key:$id", "content://test/$id", "album", "$id.png", "image/png", "IMAGE", 1, 1) }
        val config = PhotoWidgetConfig(order = "SHUFFLE", seconds = 10)
        assertEquals(config, PhotoWidgetConfig.decode(config.encode()))
        val initial = photoWidgetSequence(config, items, 1000L, 1000L, 7)
        val reopened = photoWidgetSequence(config, items, 1000L, 11000L, 7)
        assertEquals(initial.drop(1) + initial.take(1), reopened)
        assertEquals(reopened, photoWidgetSequence(config, items, 1000L, 11000L, 7))
        assertEquals(items.map { it.id }.toSet(), reopened.map { it.id }.toSet())
        assertEquals(items, photoWidgetSequence(config.copy(order = "OLDEST"), items, 1000L, 1000L, 7))
    }
    @Test fun rotationTransitionFlipsAroundTheVerticalAxis() {
        val out = android.animation.AnimatorInflater.loadAnimator(app, R.animator.photo_rotate_out) as android.animation.ObjectAnimator
        assertEquals("rotationY", out.values.single().propertyName)
        val incoming = android.animation.AnimatorInflater.loadAnimator(app, R.animator.photo_rotate_in) as android.animation.ObjectAnimator
        assertEquals("rotationY", incoming.values.single().propertyName)
    }
    @Test fun slideshowTransitionsPersistAndAllNativeLayoutsKeepTheSettingsStrip() {
        for (effect in listOf("LEFT", "RIGHT", "UP", "DOWN", "ROTATE")) {
            val config = PhotoWidgetConfig(transition = effect, seconds = 30)
            assertEquals(config, PhotoWidgetConfig.decode(config.encode()))
            val view = android.view.LayoutInflater.from(app).inflate(photoTransitionLayout(effect)!!, FrameLayout(app), false)
            assertNotNull(view.findViewById<View>(R.id.photo_slideshow))
            assertNotNull(view.findViewById<View>(R.id.photo_touch))
        }
    }
    @Test fun framingTracksShapeAndAlbumsAlwaysUseDefaultFraming() {
        val fixed = PhotoWidgetConfig(fixedMedia = "photo", zoom = 2f, panX = .4f, panY = -.2f, cropAspect = 1.5f)
        assertEquals(fixed, PhotoWidgetConfig.decode(fixed.encode()))
        assertEquals(fixed, fixed.forShape(1.5f))
        val square = fixed.forShape(1f)
        assertEquals(1f, square.zoom); assertEquals(0f, square.panX); assertEquals(0f, square.panY)
        assertEquals(1f, fixed.copy(fixedMedia = null).forShape(1.5f).zoom)
    }
    @Test fun gifAnimationBudgetRejectsTruncatedStreamsAndOversizedCanvasesBeforeDecoding() {
        val bytes = PhotoGifProvider().bytes
        assertTrue(widgetGifWithinBudget(bytes))
        assertFalse(widgetGifWithinBudget(bytes.copyOf(20)))
        val huge = bytes.copyOf().apply { this[6] = 0xff.toByte(); this[7] = 0x7f; this[8] = 0xff.toByte(); this[9] = 0x7f }
        assertFalse(widgetGifWithinBudget(huge))
        assertFalse(widgetGifWithinBudget(ByteArray(20)))
    }
    @Test fun cropSettingsRoundTripAndPanMovesTheActualSavedWidgetPixels() {
        val config = PhotoWidgetConfig(zoom = 2f, panX = -.7f, panY = .2f)
        assertEquals(config, PhotoWidgetConfig.decode(config.encode()))
        val photo = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        Canvas(photo).apply { drawColor(Color.RED); drawRect(100f, 0f, 200f, 100f, Paint().apply { color = Color.BLUE }) }
        val left = photoWidgetBitmap(photo, true, 100, 100, 2f, 1f, 0f)
        val right = photoWidgetBitmap(photo, true, 100, 100, 2f, -1f, 0f)
        assertEquals(Color.RED, left.getPixel(192, 192)); assertEquals(Color.BLUE, right.getPixel(192, 192))
        assertEquals(0, Color.alpha(left.getPixel(0, 0)))
        assertThrows(IllegalArgumentException::class.java) { config.copy(zoom = Float.NaN).validate() }
    }
    @Test fun nativeGifWidgetFlipsBoundedFramesWithoutStartingAnAppService() {
        val red = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val blue = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val item = MediaEntity("gif", "gif", "content://gif/image", "album", "Animation.gif", "image/gif", "GIF", 100, 1)
        val view = photoWidgetViews(app, 1, PhotoWidgetConfig(fixedMedia = "gif"), GalleryPreferences(), item, red, 1, null, WidgetGif(listOf(red, blue), 100)).apply(app, FrameLayout(app))
        val flip = view.findViewById<ViewFlipper>(R.id.photo_animation)
        assertEquals(View.VISIBLE, flip.visibility); assertEquals(2, flip.childCount); assertEquals(100, org.robolectric.util.ReflectionHelpers.getField<Int>(flip, "mFlipInterval"))
        assertEquals(View.GONE, view.findViewById<View>(R.id.photo_fill).visibility)
        assertEquals(View.GONE, view.findViewById<View>(R.id.photo_fit).visibility)
        assertEquals(Color.RED, ((flip.getChildAt(0) as ImageView).drawable as android.graphics.drawable.BitmapDrawable).bitmap.getPixel(64, 64))
        assertEquals(Color.BLUE, ((flip.getChildAt(1) as ImageView).drawable as android.graphics.drawable.BitmapDrawable).bitmap.getPixel(64, 64))
        assertNull(shadowOf(app).nextStartedService)
        val paused = photoWidgetViews(app, 1, PhotoWidgetConfig(fixedMedia = "gif"), GalleryPreferences(gameMode = true), item, red, 1, null, WidgetGif(listOf(red, blue), 100)).apply(app, FrameLayout(app))
        assertEquals(View.GONE, paused.findViewById<View>(R.id.photo_animation).visibility)
    }
    @Test fun emptyPhotoOpensTheSelectionWithoutLaunchingAGalleryPanel() {
        val frame = photoWidgetViews(app, 52, PhotoWidgetConfig(), GalleryPreferences(), null, null, 0, null).apply(app, FrameLayout(app))
        frame.findViewById<View>(R.id.photo_touch).performClick()
        val setup = shadowOf(app).nextStartedActivity
        assertEquals(PhotoWidgetConfigActivity::class.java.name, setup.component!!.className)
        assertEquals(52, setup.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
        assertNull(shadowOf(app).nextStartedActivity)
    }
    private val app get() = RuntimeEnvironment.getApplication() as GalleryApp
    private lateinit var fixture: FrameFixtureProvider
    private lateinit var tree: android.net.Uri
    @After fun close() { app.onTerminate() }
    private fun setup() {
        fixture = FrameFixtureProvider()
        fixture.attachInfo(app, ProviderInfo().apply { authority = "ayngallery.photowidget"; exported = true; grantUriPermissions = true })
        ShadowContentResolver.registerProviderInternal("ayngallery.photowidget", fixture)
        tree = DocumentsContract.buildTreeDocumentUri("ayngallery.photowidget", "root")
        app.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val images = listOf("a", "b", "c", "d").map { id -> MediaEntity(id, "fixture:$id", DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(),
            if (id == "d") "other" else "folder", "$id.png", "image/png", "IMAGE", 100, 1) }
        runBlocking {
            app.preferences.save(GalleryPreferences(themeId = "rose_milk", reducedMotion = true))
            app.catalog.dao.saveSource(SourceEntity("source", tree.toString(), "Fixture", "VISUAL"))
            app.catalog.dao.saveAlbums(listOf("folder", "other").map { AlbumEntity(it, "fixture:$it", tree.toString(), it, it, null) })
            app.catalog.dao.saveAncestors(listOf("folder", "other").map { AlbumAncestor(it, it) })
            app.catalog.dao.saveMedia(images); app.catalog.dao.saveMembership(images.map { MediaSource(it.id, "source", 1) })
            app.catalog.collection("For widgets", "MEDIA", listOf("b", "d"))
            withTimeout(5000) { app.catalog.snapshot.first { it.initialized && it.media.size == 4 && it.collections.size == 1 } }
        }
    }
    private fun bind(): Int {
        val host = shadowOf(AppWidgetManager.getInstance(app))
        val id = host.createWidget(PhotoWidgetProvider::class.java, R.layout.photo_widget)
        host.putWidgetInfo(id, AppWidgetProviderInfo().apply { provider = ComponentName(app, PhotoWidgetProvider::class.java); initialLayout = R.layout.photo_widget })
        return id
    }
    private fun complete(block: suspend () -> Unit) {
        val result = app.catalog.scope.async { block() }
        val end = System.nanoTime() + 10_000_000_000L
        while (!result.isCompleted && System.nanoTime() < end) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        assertTrue("Update timed out", result.isCompleted); runBlocking { result.await() }
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun view(id: Int) = shadowOf(AppWidgetManager.getInstance(app)).getViewFor(id)
    @Test fun twoInstancesReadRealImagesKeepTheirOwnChoicesAndPersistRandomVisits() {
        setup(); val first = bind(); val second = bind()
        val collection = app.catalog.snapshot.value.collections.single().id
        complete { app.photoWidgets.configure(first, PhotoWidgetConfig(FrameConfig(scope = "SELECTED", collections = setOf(collection)), minutes = 0)) }
        complete { app.photoWidgets.configure(second, PhotoWidgetConfig(fixedMedia = "a", minutes = 60)) }
        assertEquals("b", app.photoWidgets.settings.cursor(first).current); assertEquals("a", app.photoWidgets.settings.cursor(second).current)
        assertEquals("b.png", view(first).findViewById<TextView>(R.id.photo_title).text.toString())
        assertEquals(View.GONE, view(first).findViewById<View>(R.id.photo_empty).visibility)
        complete { app.photoWidgets.update(listOf(first), 1) }
        assertEquals("d", app.photoWidgets.settings.cursor(first).current); assertEquals("a", app.photoWidgets.settings.cursor(second).current)
        val restored = PhotoWidgetSettings(app); assertEquals(app.photoWidgets.settings.cursor(first), restored.cursor(first))
        complete { app.photoWidgets.configure(first, PhotoWidgetConfig(FrameConfig(shuffled = true), minutes = 0)) }
        val visits = mutableListOf(app.photoWidgets.settings.cursor(first).current)
        repeat(3) { complete { app.photoWidgets.update(listOf(first), 1) }; visits += app.photoWidgets.settings.cursor(first).current }
        assertEquals(setOf("a", "b", "c", "d"), visits.toSet())
        complete { app.photoWidgets.assign(second, app.catalog.snapshot.value.byId["c"]!!) }
        assertEquals("c", app.photoWidgets.settings.cursor(second).current); assertEquals("c", app.photoWidgets.settings.get(second).fixedMedia)
        assertTrue(app.catalog.snapshot.value.media.all { it.available && !it.trashed })
    }
    @Test fun hiddenTrashAndRevokedSourceRemovePixelsAndRetainReferences() {
        setup(); val id = bind()
        complete { app.photoWidgets.configure(id, PhotoWidgetConfig(minutes = 0)) }
        complete { app.catalog.hideAlbums(listOf("folder"), true); app.photoWidgets.update(listOf(id)) }
        assertEquals("d", app.photoWidgets.settings.cursor(id).current)
        assertEquals("d.png", view(id).findViewById<TextView>(R.id.photo_title).text.toString())
        val d = app.catalog.snapshot.value.byId["d"]!!
        complete { app.catalog.dao.saveMedia(listOf(d.copy(trashed = true))); app.photoWidgets.update(listOf(id)) }
        assertEquals(View.VISIBLE, view(id).findViewById<View>(R.id.photo_empty).visibility)
        assertEquals(View.GONE, view(id).findViewById<View>(R.id.photo_fit).visibility)
        assertEquals("Le tue immagini", view(id).findViewById<TextView>(R.id.photo_title).text.toString())
        complete { app.catalog.hideAlbums(listOf("folder"), false); app.catalog.dao.saveMedia(listOf(d)); app.photoWidgets.update(listOf(id)) }
        app.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        complete { app.photoWidgets.update(listOf(id)) }
        assertEquals(View.VISIBLE, view(id).findViewById<View>(R.id.photo_empty).visibility)
        assertEquals(4, app.catalog.snapshot.value.media.size)
    }
    @Test fun nativeWorkerSchedulesSupportedCadenceAndGamePowerManualFixedAndDeleteBehaveCorrectly() {
        setup(); val id = bind()
        complete { app.photoWidgets.configure(id, PhotoWidgetConfig(minutes = 60)) }
        val wm = WorkManager.getInstance(app)
        val work = wm.getWorkInfosForUniqueWork(PhotoWidgets.workName(id)).get(5, TimeUnit.SECONDS).single()
        assertEquals(60 * 60_000L, work.periodicityInfo!!.repeatIntervalMillis)
        assertEquals(60 * 60_000L, work.initialDelayMillis)
        val worker = TestListenableWorkerBuilder<PhotoWidgetWorker>(app).setInputData(workDataOf("widget" to id)).build()
        complete { assertEquals(ListenableWorker.Result.success(), worker.doWork()) }
        assertEquals("b", app.photoWidgets.settings.cursor(id).current)
        complete { app.preferences.save(app.preferences.state.value.copy(gameMode = true)); app.photoWidgets.update(listOf(id), 1, automatic = true) }
        assertEquals("b", app.photoWidgets.settings.cursor(id).current)
        complete { app.preferences.save(app.preferences.state.value.copy(gameMode = false)) }
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsPowerSaveMode(true)
        complete { app.photoWidgets.update(listOf(id), 1, automatic = true) }; assertEquals("b", app.photoWidgets.settings.cursor(id).current)
        complete { app.photoWidgets.update(listOf(id), 1) }; assertEquals("c", app.photoWidgets.settings.cursor(id).current)
        complete { app.photoWidgets.configure(id, PhotoWidgetConfig(fixedMedia = "d", minutes = 60)) }
        assertTrue(wm.getWorkInfosForUniqueWork(PhotoWidgets.workName(id)).get(5, TimeUnit.SECONDS).all { it.state == WorkInfo.State.CANCELLED })
        complete { app.photoWidgets.update(listOf(id), 1) }; assertEquals("d", app.photoWidgets.settings.cursor(id).current)
        complete { app.photoWidgets.configure(id, PhotoWidgetConfig(minutes = 0)); app.photoWidgets.delete(listOf(id)) }
        assertEquals(PhotoWidgetConfig(), app.photoWidgets.settings.get(id)); assertNull(app.photoWidgets.settings.cursor(id).current)
    }
    @Test fun boundedCorruptReadsRetryTheNextUntriedImageWithoutErasingPersonalFiles() {
        setup(); fixture.failIds += listOf("a", "b", "c", "d"); val id = bind()
        coil3.SingletonImageLoader.get(app).memoryCache?.clear()
        complete { app.photoWidgets.configure(id, PhotoWidgetConfig(minutes = 0)) }
        assertEquals(View.VISIBLE, view(id).findViewById<View>(R.id.photo_empty).visibility)
        val cursor = app.photoWidgets.settings.cursor(id)
        val current = cursor.current!!
        val next = cursor.nextId()!!
        fixture.reads.clear(); fixture.failIds.remove(next)
        // Match the real widget click: a catalog refresh may recover the image before delivery.
        // Its old pending intent must not advance a second time from the newly recovered photo.
        complete { app.photoWidgets.update(listOf(id), 1, expected = current) }
        assertTrue(fixture.reads.contains(next))
        assertEquals(next, app.photoWidgets.settings.cursor(id).current)
        assertEquals(View.GONE, view(id).findViewById<View>(R.id.photo_empty).visibility)
        assertTrue(app.catalog.snapshot.value.media.all { it.available && !it.trashed })
    }
    @Test fun nativeLayoutsKeepFitFillAccessibleControlsAndAll14ThemesWithLargeText() {
        RuntimeEnvironment.setFontScale(1.4f)
        val photo = Bitmap.createBitmap(100, 50, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(80, 112, 135)) }
        val item = MediaEntity("a", "fixture:a", "content://fixture/a", "album", "Una foto con un nome lungo.png", "image/png", "IMAGE", 100, 1)
        for (theme in GalleryThemes.all) for (fill in listOf(false, true)) {
            val frame = photoWidgetViews(app, 4, PhotoWidgetConfig(FrameConfig(fill = fill)), GalleryPreferences(themeId = theme.id), item, photo, 2, null).apply(app, FrameLayout(app))
            frame.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY)); frame.layout(0, 0, 320, 240)
            assertEquals(theme.colors.onSurface.toArgb(), frame.findViewById<TextView>(R.id.photo_title).currentTextColor)
            assertEquals(if (fill) View.VISIBLE else View.GONE, frame.findViewById<View>(R.id.photo_fill).visibility)
            for (control in listOf(R.id.photo_previous, R.id.photo_settings, R.id.photo_next)) {
                val button = frame.findViewById<View>(control); assertEquals(View.GONE, button.visibility)
                val bounds = Rect(); button.getDrawingRect(bounds); (frame as android.view.ViewGroup).offsetDescendantRectToMyCoords(button, bounds)
                assertTrue(bounds.bottom <= 240 && bounds.right <= 320)
            }
            if (theme.id == "rose_milk" || theme.id == "stocking_pink") capture(frame, "photo-widget-${theme.id}-${if (fill) "fill" else "fit"}")
        }
        photo.recycle()
    }
    @Test fun photoClickOpensOnlyItsConfigurationWithoutChangingDisplayOwnership() {
        setup(); val id = bind()
        complete { app.photoWidgets.configure(id, PhotoWidgetConfig(minutes = 0)) }
        org.robolectric.shadows.ShadowDisplayManager.addDisplay("w800dp-h600dp")
        app.session.refreshDisplays(); val screens = app.session.screens
        val primary = screens.first { it.id == 0 }; val other = screens.first { it.id != 0 }
        assertTrue(app.session.save(DisplayState(anchor = primary.key, ceded = setOf(other.key))))
        complete { app.photoWidgets.configure(id, app.photoWidgets.settings.get(id).copy(fallbackDisplay = other.key)) }
        val selected = app.session.selectedMediaId
        PhotoWidgetProvider().onReceive(app, Intent(PhotoWidgetProvider.STEP).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).putExtra("direction", 1).putExtra("expected", "a")); shadowOf(Looper.getMainLooper()).idle()
        assertNull(shadowOf(app).nextStartedActivity); assertEquals(selected, app.session.selectedMediaId)
        view(id).findViewById<View>(R.id.photo_touch).performClick()
        val open = shadowOf(app).nextStartedActivity
        assertEquals(PhotoWidgetConfigActivity::class.java.name, open.component!!.className)
        assertEquals(id, open.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
        assertNull(shadowOf(app).nextStartedActivity)
        assertEquals(setOf(other.key), app.session.state.ceded)
        assertEquals(selected, app.session.selectedMediaId)
        assertTrue(app.music.state.value.queue.isEmpty())
    }
    @Test fun configValidationPersistenceAndTargetsDoNotAdmitUnsupportedIntervalsOrStaleDrafts() {
        val config = PhotoWidgetConfig(FrameConfig(scope = "SELECTED", albums = setOf("album"), shuffled = true), minutes = 360, fallbackDisplay = "screen")
        assertEquals(config, PhotoWidgetConfig.decode(config.encode()))
        assertThrows(IllegalArgumentException::class.java) { PhotoWidgetConfig(minutes = 5).encode() }
        val empty = PhotoWidgetConfig(FrameConfig(scope = "SELECTED"))
        assertEquals(empty, PhotoWidgetConfig.decode(empty.encode(false), false))
        assertThrows(IllegalArgumentException::class.java) { PhotoWidgetConfig.decode(empty.encode(false)) }
        val settings = PhotoWidgetSettings(app); settings.save(2, config); settings.save(3, PhotoWidgetConfig(minutes = 0))
        settings.remove(2); assertEquals(0, settings.get(3).minutes); assertEquals(PhotoWidgetConfig(), settings.get(2))
        val intent = Intent(); val target = PhotoWidgetTarget(3, "a", 10, 100); target.put(intent); assertEquals(target, PhotoWidgetTarget.from(intent))
        assertNull(PhotoWidgetTarget.from(Intent().putExtra("photo_media", "a")))
    }
    @Test fun aPreviouslyRenderedPhotoOpensCurrentWidgetConfigurationAfterItChanges() {
        setup(); val id = bind()
        complete { app.photoWidgets.configure(id, PhotoWidgetConfig(minutes = 0)) }
        val a = app.catalog.snapshot.value.byId["a"]!!
        val old = photoWidgetViews(app, id, PhotoWidgetConfig(minutes = 0), GalleryPreferences(), a, Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888), 2, null).apply(app, FrameLayout(app))
        complete { app.photoWidgets.configure(id, PhotoWidgetConfig(fixedMedia = "b", minutes = 0)) }
        old.findViewById<View>(R.id.photo_touch).performClick()
        val intent = shadowOf(app).nextStartedActivity
        assertEquals(PhotoWidgetConfigActivity::class.java.name, intent.component!!.className); assertEquals(id, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)); assertFalse(intent.hasExtra("photo_media"))
        val before = app.photoWidgets.settings.get(id)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { app.photoWidgets.assign(id, a.copy(size = a.size + 1)) } }
        assertEquals(before, app.photoWidgets.settings.get(id))
    }
    @Test fun hidingTheAlbumDuringARealDecodeCannotPublishThePrivateBitmap() {
        setup(); val id = bind()
        complete { app.photoWidgets.configure(id, PhotoWidgetConfig(minutes = 0)) }
        coil3.SingletonImageLoader.get(app).memoryCache?.clear()
        val opened = CountDownLatch(1); val release = CountDownLatch(1)
        fixture.beforeOpen = { media -> if (media == "a") { opened.countDown(); check(release.await(5, TimeUnit.SECONDS)) } }
        val update = app.catalog.scope.async { app.photoWidgets.configure(id, PhotoWidgetConfig(fixedMedia = "a", minutes = 0)) }
        try {
            assertTrue(opened.await(5, TimeUnit.SECONDS))
            runBlocking { app.catalog.hideAlbums(listOf("folder"), true) }
        } finally { release.countDown() }
        complete { update.await() }
        assertEquals(View.GONE, view(id).findViewById<View>(R.id.photo_fit).visibility)
        assertEquals(View.VISIBLE, view(id).findViewById<View>(R.id.photo_empty).visibility)
        assertFalse(view(id).findViewById<TextView>(R.id.photo_title).text.contains("a.png"))
    }
    @Test fun actualTwoFrameGifUsesAStaticBitmapAndDoesNotStartAPlaybackService() {
        setup()
        val provider = PhotoGifProvider()
        provider.attachInfo(app, ProviderInfo().apply { authority = "ayngallery.photogif"; exported = true; grantUriPermissions = true })
        ShadowContentResolver.registerProviderInternal("ayngallery.photogif", provider)
        val gifTree = DocumentsContract.buildTreeDocumentUri("ayngallery.photogif", "root")
        app.contentResolver.takePersistableUriPermission(gifTree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val gif = MediaEntity("gif", "gif:picture", DocumentsContract.buildDocumentUriUsingTree(gifTree, "picture").toString(), "gifalbum", "Animated.gif", "image/gif", "GIF", provider.bytes.size.toLong(), 1)
        runBlocking {
            app.catalog.dao.saveSource(SourceEntity("gifsource", gifTree.toString(), "GIF", "VISUAL"))
            app.catalog.dao.saveAlbums(listOf(AlbumEntity("gifalbum", "gif:root", gifTree.toString(), "GIF", "GIF", null)))
            app.catalog.dao.saveMedia(listOf(gif)); app.catalog.dao.saveMembership(listOf(MediaSource("gif", "gifsource", 1)))
        }
        val id = bind(); complete { app.photoWidgets.configure(id, PhotoWidgetConfig(fixedMedia = "gif", minutes = 0)) }
        val drawable = view(id).findViewById<ImageView>(R.id.photo_fit).drawable
        assertTrue(drawable is android.graphics.drawable.BitmapDrawable)
        assertFalse(drawable is android.graphics.drawable.Animatable)
        val pixels = (drawable as android.graphics.drawable.BitmapDrawable).bitmap
        assertEquals(0, Color.alpha(pixels.getPixel(0, 0)))
        assertEquals(Color.RED, pixels.getPixel(pixels.width / 2, pixels.height / 2))
        assertEquals(View.GONE, view(id).findViewById<View>(R.id.photo_empty).visibility)
        assertTrue(app.music.state.value.queue.isEmpty()); assertNull(shadowOf(app).nextStartedService)

    }
    private fun capture(view: View, name: String) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888); view.draw(Canvas(bitmap))
        val file = File("../qa/rendered/$name.png").absoluteFile; file.parentFile!!.mkdirs(); file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
}

class PhotoGifProvider : ContentProvider() {
    // GIF89a, red first frame / blue second frame, each 100 ms, loop forever.
    val bytes = "47494638396101000100800000ff00000000ff21ff0b4e45545343415045322e30030100000021f904000a0000002c000000000100010000020244010021f904000a0000002c00000000010001000002024c01003b"
        .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    override fun onCreate() = true
    override fun openFile(uri: android.net.Uri, mode: String): ParcelFileDescriptor {
        val file = File(context!!.cacheDir, "photo-fixture.gif"); file.writeBytes(bytes)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri: android.net.Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): android.database.Cursor {
        val id = DocumentsContract.getDocumentId(uri); val columns = projection ?: emptyArray()
        return android.database.MatrixCursor(columns).apply { addRow(columns.map<String, Any?> { when (it) {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID -> id; DocumentsContract.Document.COLUMN_DISPLAY_NAME -> id
            DocumentsContract.Document.COLUMN_MIME_TYPE -> if (id == "root") DocumentsContract.Document.MIME_TYPE_DIR else "image/gif"
            DocumentsContract.Document.COLUMN_SIZE -> bytes.size.toLong(); DocumentsContract.Document.COLUMN_LAST_MODIFIED -> 1L; else -> 0
        } }.toTypedArray()) }
    }
    override fun getType(uri: android.net.Uri) = "image/gif"
    override fun insert(uri: android.net.Uri, values: ContentValues?): android.net.Uri? = null
    override fun update(uri: android.net.Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    override fun delete(uri: android.net.Uri, selection: String?, args: Array<out String>?) = 0
}
