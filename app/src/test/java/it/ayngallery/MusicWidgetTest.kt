package it.ayngallery

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestGalleryApp::class, qualifiers = "it")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class MusicWidgetTest {
    @Test fun pulseContractsBetweenPeaksAndSettlesContinuouslyFromEveryPhase() {
        assertEquals(.70f, widgetPulseScale(0f), .0001f)
        assertEquals(1.06f, widgetPulseScale(1f), .0001f)
        for (level in listOf(0f, .05f, .25f, .5f, .75f, .95f, 1f)) {
            val scale = widgetPulseScale(level)
            assertTrue(scale in .70f..1.06f)
            assertEquals(scale, 1f + widgetDiscRestAngle(scale - 1f, 0f), .0001f)
            assertEquals(1f, 1f + widgetDiscRestAngle(scale - 1f, 1f), .0001f)
        }
    }
    @Test fun initialTracksStayIndependentAndRemovalClearsOnlyOneWidget() {
        app.widgets.settings.save(81, MusicWidgetConfig(initialTrack = "a"))
        app.widgets.settings.save(82, MusicWidgetConfig(initialTrack = "b"))
        assertEquals("a", app.widgets.settings.get(81).initialTrack)
        assertEquals("b", app.widgets.settings.get(82).initialTrack)
        app.widgets.settings.remove(81)
        assertNull(app.widgets.settings.get(81).initialTrack)
        assertEquals("b", app.widgets.settings.get(82).initialTrack)
    }
    private val app get() = RuntimeEnvironment.getApplication() as GalleryApp
    @After fun release() { app.onTerminate() }
    @Test fun loadingDisablesTransportWithoutOpeningTheAppAndButtonsHavePressedFeedback() {
        val display = MusicWidgetDisplay("song", "Song", "", "", null, false, 0, 1000, true, true, true, false)
        val frame = musicWidgetViews(app, 91, R.layout.music_widget_artwork, display, MusicWidgetConfig(), GalleryPreferences(), null, starting = true).apply(app, FrameLayout(app))
        for (id in listOf(R.id.widget_play, R.id.widget_previous, R.id.widget_next, R.id.widget_shuffle)) assertFalse(frame.findViewById<View>(id).isEnabled)
        frame.findViewById<View>(R.id.widget_play).performClick()
        assertNull(shadowOf(app).nextStartedActivity)
        val normal = musicWidgetViews(app, 91, R.layout.music_widget_artwork, display, MusicWidgetConfig(), GalleryPreferences(), null).apply(app, FrameLayout(app))
        val button = normal.findViewById<View>(R.id.widget_play)
        val idle = button.background.current
        button.isPressed = true
        assertNotSame(idle, button.background.current)
    }
    @Test fun barColorIncludesBlackAndStaysIndependentBetweenWidgets() {
        app.widgets.settings.save(81, MusicWidgetConfig(effect = "BARS", barsColor = Color.BLACK))
        app.widgets.settings.save(82, MusicWidgetConfig(effect = "BARS", barsColor = Color.WHITE))
        assertEquals(Color.BLACK, app.widgets.settings.get(81).barsColor)
        assertEquals(Color.WHITE, app.widgets.settings.get(82).barsColor)
        app.widgets.settings.remove(81)
        assertNull(app.widgets.settings.get(81).barsColor)
        assertEquals(Color.WHITE, app.widgets.settings.get(82).barsColor)
    }
    @Test fun legacySquareUsesFullCoverAndOnlyHorizontalProviderUsesCompactLayout() {
        val display = MusicWidgetDisplay("song", "Song", "", "", null, false, 0, 1000, true, true, true, false)
        for (provider in listOf(MusicFullWidget::class.java, MusicMinimalWidget::class.java)) {
            val id = bind(provider, R.layout.music_widget_artwork)
            val rendered = app.widgets.render(id, display, MusicWidgetConfig(), GalleryPreferences(), null)
            assertEquals(if (provider == MusicMinimalWidget::class.java) R.layout.music_widget_minimal else R.layout.music_widget_artwork, rendered.layoutId)
        }
    }
    private fun bind(provider: Class<out MusicWidgetProvider>, layout: Int): Int {
        val host = shadowOf(AppWidgetManager.getInstance(app))
        val id = host.createWidget(provider, layout)
        host.putWidgetInfo(id, AppWidgetProviderInfo().apply { this.provider = ComponentName(app, provider); initialLayout = layout })
        app.widgets.refresh()
        return id
    }
    @Test @Config(sdk = [33]) fun compactHostAndMissingSizeFallbackChooseTheHorizontalLayoutWithVisibleControls() {
        val id = bind(MusicMinimalWidget::class.java, R.layout.music_widget_minimal)
        val display = MusicWidgetDisplay("song", "Titolo del brano", "Artista", "", null, false, 0, 1000, true, true, true, false)
        val views = app.widgets.render(id, display, MusicWidgetConfig(), GalleryPreferences(), null)
        val chosen = org.robolectric.util.ReflectionHelpers.callInstanceMethod<android.widget.RemoteViews>(views, "getRemoteViewsToApply",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Context::class.java, app),
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(android.util.SizeF::class.java, android.util.SizeF(300f, 64f)))
        assertEquals(R.layout.music_widget_minimal, chosen.layoutId)
        val fallback = views.apply(app, FrameLayout(app))
        val frame = chosen.apply(app, FrameLayout(app))
        assertNotNull(fallback.findViewById<View>(R.id.widget_cover))
        frame.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(64, View.MeasureSpec.EXACTLY))
        frame.layout(0, 0, 300, 64)
        for (button in listOf(R.id.widget_previous, R.id.widget_play, R.id.widget_next, R.id.widget_shuffle)) {
            val control = frame.findViewById<View>(button)
            val bounds = Rect(); control.getDrawingRect(bounds); (frame as android.view.ViewGroup).offsetDescendantRectToMyCoords(control, bounds)
            assertTrue("Compact controls must stay visible: $bounds", bounds.top >= 0 && bounds.bottom <= 64 && control.height >= 36)
        }
        assertTrue(frame.findViewById<TextView>(R.id.widget_title).height >= 12)
    }
    @Test fun threeDistinctNativeLayoutsKeepArtworkLeftAndControlsAtLeast48Dp() = render(false)
    @Test fun nativeWidgetsRemainUsableWithLargeTextAndWhiteArtwork() = render(true)
    @Test fun resizedLayoutsKeepTransportInBoundsAndOpenOnlyTheirOwnSettings() {
        val display = MusicWidgetDisplay("selected-song", "Brano", "Artista", "", null, false, 0, 1000, true, true, true, false, true)
        for ((layout, width, height) in listOf(Triple(R.layout.music_widget_square, 120, 120),
            Triple(R.layout.music_widget_artwork, 120, 120), Triple(R.layout.music_widget_full, 220, 140), Triple(R.layout.music_widget_minimal, 220, 76), Triple(R.layout.music_widget_vertical, 120, 220))) {
            val frame = musicWidgetViews(app, 71, layout, display, MusicWidgetConfig(showShuffle = true), GalleryPreferences(themeId = "stocking_pink"), null).apply(app, FrameLayout(app))
            frame.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            frame.layout(0, 0, width, height)
            for (id in listOf(R.id.widget_previous, R.id.widget_play, R.id.widget_next, R.id.widget_shuffle)) {
                val button = frame.findViewById<View>(id)
                val bounds = Rect(); button.getDrawingRect(bounds); (frame as android.view.ViewGroup).offsetDescendantRectToMyCoords(button, bounds)
                assertTrue("$width × $height: $bounds", button.width > 0 && bounds.left >= 0 && bounds.top >= 0 && bounds.right <= width && bounds.bottom <= height)
            }
            assertEquals(View.VISIBLE, frame.findViewById<View>(R.id.widget_shuffle).visibility)
            frame.findViewById<View>(R.id.widget_title).performClick()
            val open = shadowOf(app).nextStartedActivity
            assertEquals(MusicWidgetConfigActivity::class.java.name, open.component!!.className)
            assertEquals(71, open.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
            assertFalse(open.hasExtra("music_media"))
            assertEquals(1, frame.findViewById<TextView>(R.id.widget_title).maxLines)
        }
    }
    private fun render(largeText: Boolean) {
        if (largeText) RuntimeEnvironment.setFontScale(1.4f)
        val cover = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(if (largeText) Color.WHITE else Color.rgb(154, 84, 164)) }
        val display = MusicWidgetDisplay("song", "Il titolo di un brano piuttosto lungo", "Un artista dal nome completo", "In pausa", "fixture", false, 45_000, 180_000, true, true, true, true)
        val shapes = listOf(Triple(R.layout.music_widget_square, 260, 240), Triple(R.layout.music_widget_full, 320, 212), Triple(R.layout.music_widget_minimal, 320, 76))
        for ((layout, width, height) in shapes) {
            val views = musicWidgetViews(app, layout, layout, display, MusicWidgetConfig(), GalleryPreferences(), cover)
            val frame = views.apply(app, FrameLayout(app)) as FrameLayout
            frame.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            frame.layout(0, 0, width, height)
            val artwork = frame.findViewById<View>(R.id.widget_cover)
            val title = frame.findViewById<TextView>(R.id.widget_title)
            val coverBounds = Rect(); artwork.getDrawingRect(coverBounds); frame.offsetDescendantRectToMyCoords(artwork, coverBounds)
            val titleBounds = Rect(); title.getDrawingRect(titleBounds); frame.offsetDescendantRectToMyCoords(title, titleBounds)
            if (layout == R.layout.music_widget_square) {
                assertEquals(View.VISIBLE, title.visibility)
                assertTrue(artwork.width >= 200 && artwork.height >= 140)
            } else {
                assertTrue("Artwork must stay left of title", coverBounds.right <= titleBounds.left)
                assertTrue(title.width >= 80); assertTrue(title.layout.lineCount > 0)
                if (layout == R.layout.music_widget_full) assertEquals(coverBounds.top, titleBounds.top)
            }
            for (id in listOf(R.id.widget_previous, R.id.widget_play, R.id.widget_next)) {
                val button = frame.findViewById<View>(id)
                assertTrue(button.width >= 48 && button.height >= 48)
                val bounds = Rect(); button.getDrawingRect(bounds); frame.offsetDescendantRectToMyCoords(button, bounds)
                assertTrue("Button outside $width × $height: $bounds", bounds.left >= 0 && bounds.right <= width && bounds.top >= 0 && bounds.bottom <= height)
                assertFalse(button.contentDescription.isNullOrBlank())
            }
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            frame.draw(Canvas(bitmap))
            val name = when (layout) { R.layout.music_widget_square -> "square"; R.layout.music_widget_full -> "full"; else -> "minimal" }
            val file = File("../qa/rendered/widget-$name-${if (largeText) "large-text-white" else "artwork"}.png").absoluteFile
            file.parentFile!!.mkdirs(); file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
        cover.recycle()
    }
    @Test fun artworkBackgroundMaintainsTextContrastForBrightDarkAndTransparentImages() {
        for (color in listOf(Color.WHITE, Color.BLACK, Color.RED, Color.GREEN, Color.BLUE, Color.TRANSPARENT)) {
            val artwork = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            val bg = widgetBackground(widgetArtworkColor(artwork), false)
            assertEquals(0, Color.alpha(bg.getPixel(0, 0))) // Rounded corners are transparent, with no text drawn there.
            for (x in listOf(8, 31, 55)) assertTrue(ColorUtils.calculateContrast(Color.WHITE, bg.getPixel(x, x)) >= 4.5)
            bg.recycle(); artwork.recycle()
        }
    }
    @Test fun widgetsFollowAll14SavedPalettesWithReadableTextAndNativeFocus() {
        val display = MusicWidgetDisplay("song", "La musica continua", "Artista locale", "In pausa", null, false, 45_000, 180_000, true, true, true, false)
        for (theme in GalleryThemes.all) {
            val views = musicWidgetViews(app, 50, R.layout.music_widget_full, display, MusicWidgetConfig("THEME"), GalleryPreferences(themeId = theme.id), null)
            val frame = views.apply(app, FrameLayout(app))
            frame.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(212, View.MeasureSpec.EXACTLY))
            frame.layout(0, 0, 320, 212)
            assertEquals(theme.colors.onSurface.toArgb(), frame.findViewById<TextView>(R.id.widget_title).currentTextColor)
            assertTrue(frame.findViewById<View>(R.id.widget_play).requestFocus())
            val bitmap = Bitmap.createBitmap(320, 212, Bitmap.Config.ARGB_8888)
            frame.draw(Canvas(bitmap))
            assertEquals(theme.background.toArgb(), bitmap.getPixel(160, 5))
            assertTrue(ColorUtils.calculateContrast(theme.colors.onSurface.toArgb(), bitmap.getPixel(160, 5)) >= 4.5)
            val file = File("../qa/rendered/widget-theme-${theme.id}.png").absoluteFile
            file.parentFile!!.mkdirs(); file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }
    @Test fun pausedRecoveryPrivacyAndPerInstanceSettingsShareTheCatalogPolicy() = runBlocking {
        val documents = OperationDocuments()
        documents.attachInfo(app, android.content.pm.ProviderInfo().apply { authority = "fixture"; exported = true; grantUriPermissions = true; readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission })
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("fixture", documents)
        val source = SourceEntity("music", "content://fixture/tree/root", "Music", "MUSIC")
        val album = AlbumEntity("album", "fixture:root", "content://fixture/tree/root/document/root", "Music", "Music", null)
        val song = MediaEntity("song", "fixture:song", "content://fixture/tree/root/document/song", album.id, "song.wav", "audio/wav", "AUDIO", 100, 1, title = "A private title", favorite = true)
        app.contentResolver.takePersistableUriPermission(android.net.Uri.parse(source.treeUri), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        app.catalog.dao.saveSource(source); app.catalog.dao.saveAlbums(listOf(album)); app.catalog.dao.saveMedia(listOf(song))
        app.catalog.dao.saveMembership(listOf(MediaSource(song.id, source.id, 1)))
        app.getSharedPreferences("music", Context.MODE_PRIVATE).edit().putString("queue", "[\"song\"]").putInt("index", 0).commit()
        val paused = musicWidgetDisplay(app.catalog, app.catalog.readSnapshot(), null)
        assertEquals(song.title, paused.title); assertFalse(paused.playing); assertTrue(paused.available)
        val starting = musicWidgetDisplay(app.catalog, app.catalog.readSnapshot(), PlaybackState())
        assertEquals(song.id, starting.mediaId); assertFalse(starting.playing)
        app.getSharedPreferences("music", Context.MODE_PRIVATE).edit().clear().commit()
        val first = musicWidgetDisplay(app.catalog, app.catalog.readSnapshot(), PlaybackState())
        assertEquals(song.id, first.mediaId); assertFalse(first.playing)
        val earlier = song.copy(id = "alphabetical-first", documentKey = "fixture:earlier", title = "A first song", name = "earlier.wav")
        app.catalog.dao.saveMedia(listOf(earlier)); app.catalog.dao.saveMembership(listOf(MediaSource(earlier.id, source.id, 1)))
        val alphabetical = musicWidgetDisplay(app.catalog, app.catalog.readSnapshot(), PlaybackState())
        assertEquals(earlier.id, alphabetical.mediaId)
        app.getSharedPreferences("music", Context.MODE_PRIVATE).edit().putString("queue", "[\"song\"]").commit()
        assertEquals(song.id, musicWidgetDisplay(app.catalog, app.catalog.readSnapshot(), PlaybackState()).mediaId)
        app.catalog.hideAlbums(listOf(album.id), true)
        val hidden = musicWidgetDisplay(app.catalog, app.catalog.readSnapshot(), PlaybackState(id = song.id, queue = listOf(song.id), playing = true))
        assertFalse(hidden.title.contains("private")); assertNull(hidden.artwork); assertFalse(hidden.available); assertFalse(hidden.playing)
        app.widgets.settings.save(11, MusicWidgetConfig("THEME", "display-one"))
        app.widgets.settings.save(12, MusicWidgetConfig("ARTWORK", "display-two"))
        assertEquals("SYSTEM", app.widgets.settings.get(11).background); assertEquals("display-two", app.widgets.settings.get(12).fallbackDisplay)
        app.widgets.settings.remove(11)
        assertEquals(MusicWidgetConfig(), app.widgets.settings.get(11)); assertEquals("display-two", app.widgets.settings.get(12).fallbackDisplay)
    }
    @Test @Config(sdk = [31]) fun oneCellDiscKeepsTheSameCoverSizeWhenPlaybackStarts() {
        val id = bind(MusicMinimalWidget::class.java, R.layout.music_widget_tiny)
        AppWidgetManager.getInstance(app).updateAppWidgetOptions(id, android.os.Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 64)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 64)
        })
        val display = MusicWidgetDisplay("song", "Song", "", "", null, false, 0, 1000, true, true, true, false)
        fun render(playing: Boolean): View = app.widgets.render(id, display.copy(playing = playing), MusicWidgetConfig(effect = "DISC"), GalleryPreferences(), null).apply(app, FrameLayout(app)).also {
            val exact = View.MeasureSpec.makeMeasureSpec(180, View.MeasureSpec.EXACTLY)
            it.measure(exact, exact); it.layout(0, 0, 180, 180)
        }
        val idle = render(false).findViewById<View>(R.id.widget_background)
        val active = render(true).findViewById<View>(R.id.widget_motion_one)
        assertEquals(idle.width, active.width); assertEquals(idle.height, active.height)

    }
    @Test fun launcherOffersExactlyTwoMusicWidgetsAndOnePhotoWidget() {
        val providers = app.packageManager.queryBroadcastReceivers(Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).setPackage(app.packageName), android.content.pm.PackageManager.GET_META_DATA)
            .filter { it.activityInfo.metaData?.containsKey(AppWidgetManager.META_DATA_APPWIDGET_PROVIDER) == true }
            .map { it.activityInfo.name }
        assertEquals(3, providers.size)
        assertEquals(setOf(MusicFullWidget::class.java.name, MusicMinimalWidget::class.java.name, PhotoWidgetProvider::class.java.name), providers.toSet())
    }
    @Test fun coinEffectStaysCenteredInANonSquareTinyHostAndPauses() {
        val display = MusicWidgetDisplay("song", "Song", "", "", null, true, 0, 1000, true, true, true, false)
        val view = musicWidgetViews(app, 17, R.layout.music_widget_tiny, display, MusicWidgetConfig(effect = "COIN"), GalleryPreferences(), null).apply(app, FrameLayout(app))
        val motion = view.findViewById<android.view.ViewGroup>(R.id.widget_effect_disc)
        assertEquals(android.view.Gravity.CENTER, (motion.layoutParams as FrameLayout.LayoutParams).gravity)
        val animation = (motion.getChildAt(0) as FrameLayout).layoutAnimation.animation
        animation.initialize(80, 80, 140, 80)
        val transform = android.view.animation.Transformation()
        animation.getTransformation(0L, transform)
        animation.getTransformation(800L, transform)
        val center = floatArrayOf(40f, 40f); transform.matrix.mapPoints(center)
        assertEquals(40f, center[0], .1f); assertEquals(40f, center[1], .1f)
        val paused = musicWidgetViews(app, 17, R.layout.music_widget_tiny, display.copy(playing = false), MusicWidgetConfig(effect = "COIN"), GalleryPreferences(), null).apply(app, FrameLayout(app))
        assertEquals(View.GONE, paused.findViewById<View>(R.id.widget_effect_disc).visibility)
    }
    @Test fun barAccentUsesCoverColorsAndTransparentArtworkFallsBack() {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.CYAN)
        val accent = widgetArtworkAccent(bitmap, Color.MAGENTA)
        assertTrue(Color.green(accent) > Color.red(accent))
        bitmap.eraseColor(Color.TRANSPARENT)
        assertEquals(Color.MAGENTA, widgetArtworkAccent(bitmap, Color.MAGENTA))
        bitmap.recycle()
    }
    @Test fun oneCellWidgetsHideTransportAndUseTheCoverAsToggle() {
        val options = android.os.Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 80)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 80)
        }
        assertTrue(musicWidgetCompact(options))
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180)
        assertTrue(musicWidgetCompact(options))
        assertFalse(musicWidgetCompact(android.os.Bundle()))
        val display = MusicWidgetDisplay("song", "Song", "Artist", "", null, false, 0, 1000, true, true, true, false)
        val view = musicWidgetViews(app, 17, R.layout.music_widget_tiny, display, MusicWidgetConfig(), GalleryPreferences(), null).apply(app, FrameLayout(app))
        for (id in listOf(R.id.widget_title, R.id.widget_previous, R.id.widget_play, R.id.widget_next, R.id.widget_shuffle)) assertEquals(View.GONE, view.findViewById<View>(id).visibility)
        assertTrue(view.findViewById<View>(R.id.widget_root).isClickable)
    }
    @Test fun effectsRespectPlaybackButIgnoreAppReducedMotionAndKeepControlsAboveArtwork() {
        val paused = MusicWidgetDisplay("song", "Song", "Artist", "", null, false, 0, 1000, true, true, true, false)
        for ((effect, viewId) in listOf("DISC" to R.id.widget_effect_disc, "PULSE" to R.id.widget_effect_pulse, "BARS" to R.id.widget_effect_bars)) {
            app.widgets.settings.save(17, MusicWidgetConfig(effect = effect))
            assertEquals(effect, app.widgets.settings.get(17).effect)
            fun render(playing: Boolean, reduced: Boolean = false) = musicWidgetViews(app, 17, R.layout.music_widget_artwork, paused.copy(playing = playing), MusicWidgetConfig(effect = effect), GalleryPreferences(reducedMotion = reduced), null).apply(app, FrameLayout(app))
            assertEquals(if (effect == "BARS") View.VISIBLE else View.GONE, render(false).findViewById<View>(viewId).visibility)
            val active = render(true)
            assertEquals(View.VISIBLE, active.findViewById<View>(viewId).visibility)
            assertEquals(View.VISIBLE, active.findViewById<View>(R.id.widget_play).visibility)
            assertEquals(View.VISIBLE, render(true, true).findViewById<View>(viewId).visibility)
        }
    }
    @Test fun deletedWidgetsAndDisabledEffectsReleaseTheirCachedCoverFrames() {
        val display = MusicWidgetDisplay("song", "Song", "", "", null, true, 0, 1000, true, true, true, false)
        val field = MusicWidgets::class.java.getDeclaredField("coverFrames").apply { isAccessible = true }
        val frames = field.get(app.widgets) as Map<*, *>
        app.widgets.render(81, display, MusicWidgetConfig(effect = "PULSE"), GalleryPreferences(), null)
        assertTrue(frames.containsKey(81))
        app.widgets.render(81, display, MusicWidgetConfig(effect = "NONE"), GalleryPreferences(), null)
        assertFalse(frames.containsKey(81))
        app.widgets.render(82, display, MusicWidgetConfig(effect = "DISC"), GalleryPreferences(), null)
        assertTrue(frames.containsKey(82))
        MusicFullWidget().onDeleted(app, intArrayOf(82))
        assertFalse(frames.containsKey(82))
    }
    @Test fun discHoleCanBeRemovedAndIsStoredPerWidget() {
        val artwork = Bitmap.createBitmap(768, 768, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val noHole = widgetDisc(app, artwork, Color.MAGENTA, hole = false)
        assertEquals(Color.RED, noHole.getPixel(384, 384))
        assertEquals(0, Color.alpha(noHole.getPixel(0, 0)))
        app.widgets.settings.save(31, MusicWidgetConfig(effect = "DISC", discHole = false))
        assertFalse(MusicWidgetSettings(app).get(31).discHole)
        assertTrue(MusicWidgetSettings(app).get(32).discHole)
        artwork.recycle(); noHole.recycle()
    }
    @Test fun discUsesCircularArtworkAndNativeCoverMotionStopsWhenPaused() {
        val artwork = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val disc = widgetDisc(app, artwork, Color.MAGENTA)
        assertEquals(0, Color.alpha(disc.getPixel(0, 0)))
        assertEquals(0, Color.alpha(disc.getPixel(disc.width / 2, disc.height / 2)))
        assertEquals(Color.RED, disc.getPixel(disc.width / 2, disc.height * 60 / 256))
        val display = MusicWidgetDisplay("song", "Song", "Artist", "", null, true, 0, 1000, true, true, true, false)
        fun render(playing: Boolean) = musicWidgetViews(app, 18, R.layout.music_widget_disc, display.copy(playing = playing), MusicWidgetConfig(effect = "DISC"), GalleryPreferences(), artwork).apply(app, FrameLayout(app))
        val active = render(true)
        val container = active.findViewById<android.view.ViewGroup>(R.id.widget_effect_disc)
        val animation = container.getChildAt(0) as android.widget.FrameLayout
        assertEquals(1, animation.childCount)
        assertEquals(9000L, animation.layoutAnimation.animation.duration)
        assertEquals(android.view.animation.Animation.INFINITE, animation.layoutAnimation.animation.repeatCount)
        assertEquals(View.VISIBLE, active.findViewById<View>(R.id.widget_shuffle).visibility)
        assertEquals(0, render(false).findViewById<android.view.ViewGroup>(R.id.widget_effect_disc).childCount)
        artwork.recycle(); disc.recycle()
    }
    @Test fun horizontalCoverOpensConfigurationAndTransportUsesOnlyBroadcasts() {
        val id = bind(MusicMinimalWidget::class.java, R.layout.music_widget_minimal)
        val display = MusicWidgetDisplay("song", "Song", "Artist", "In pausa", null, false, 0, 1000, true, true, true, false)
        val frame = app.widgets.render(id, display, MusicWidgetConfig(), GalleryPreferences(), null).apply(app, FrameLayout(app))
        frame.findViewById<View>(R.id.widget_next).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(shadowOf(app).nextStartedActivity)
        val command = shadowOf(app).broadcastIntents.last { it.action == MusicWidgetControlReceiver.ACTION }
        assertEquals("NEXT", command.getStringExtra("command")); assertEquals(id, command.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
        frame.findViewById<View>(R.id.widget_cover).performClick()
        val open = shadowOf(app).nextStartedActivity
        assertEquals(MusicWidgetConfigActivity::class.java.name, open.component!!.className)
        assertEquals(id, open.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
        assertNull(shadowOf(app).nextStartedActivity)
    }
    @Test fun longPressIsLeftToTheLauncherInsteadOfOpeningOrControllingTheApp() {
        val display = MusicWidgetDisplay("song", "Song", "Artist", "", null, false, 0, 1000, true, true, true, false)
        for (layout in listOf(R.layout.music_widget_square, R.layout.music_widget_artwork, R.layout.music_widget_minimal)) {
            val frame = musicWidgetViews(app, layout, layout, display, MusicWidgetConfig(), GalleryPreferences(), null).apply(app, FrameLayout(app))
            for (id in listOf(R.id.widget_cover, R.id.widget_title, R.id.widget_play, R.id.widget_previous, R.id.widget_next))
                assertFalse(frame.findViewById<View>(id).performLongClick())
        }
        val photo = photoWidgetViews(app, 52, PhotoWidgetConfig(), GalleryPreferences(), null, null, 0, null).apply(app, FrameLayout(app))
        assertFalse(photo.findViewById<View>(R.id.photo_picture).performLongClick())
        assertNull(shadowOf(app).nextStartedActivity)
        assertTrue(shadowOf(app).broadcastIntents.none { it.action == MusicWidgetControlReceiver.ACTION })
    }
    @Test fun widgetPreferenceNeverReclaimsACededDisplay() {
        org.robolectric.shadows.ShadowDisplayManager.addDisplay("w800dp-h600dp")
        app.session.refreshDisplays()
        val screens = app.session.screens
        assertEquals(2, screens.size)
        val primary = screens.first { it.id == 0 }
        val other = screens.first { it.id != 0 }
        assertTrue(app.session.save(DisplayState(anchor = primary.key, ceded = setOf(other.key))))
        val id = bind(MusicFullWidget::class.java, R.layout.music_widget_square)
        app.widgets.settings.save(id, MusicWidgetConfig("THEME", other.key))
        val open = Intent(app, LauncherActivity::class.java).setAction("it.ayngallery.OPEN_PLAYER").putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        val controller = Robolectric.buildActivity(LauncherActivity::class.java, open).create()
        val panel = shadowOf(controller.get()).nextStartedActivity
        assertEquals(primary.key, panel.getStringExtra("display_key"))
        assertTrue(panel.getBooleanExtra("open_player", false))
        assertEquals(setOf(other.key), app.session.state.ceded)
        assertNull(shadowOf(controller.get()).nextStartedActivity)
        controller.destroy()
    }
}
