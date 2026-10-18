package it.ayngallery

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import org.junit.Assert.*
import org.junit.Test
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestGalleryApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetLoadingTest {
    @After fun close() { (RuntimeEnvironment.getApplication() as GalleryApp).onTerminate() }
    @Test fun nativeLoadingViewsInflateAtMinimumSizesWithoutStealingWidgetGestures() {
        val app = RuntimeEnvironment.getApplication() as GalleryApp
        val layouts = listOf(Triple(R.layout.music_widget_loading_square, 120, 120), Triple(R.layout.music_widget_loading_square, 120, 64), Triple(R.layout.music_widget_loading_horizontal, 220, 76), Triple(R.layout.photo_widget_loading, 240, 180))
        fun check(view: View) {
            assertFalse(view.isClickable)
            assertFalse(view.isFocusable)
            assertTrue(view.width > 0 && view.height > 0)
            if (view is ViewGroup) for (i in 0 until view.childCount) check(view.getChildAt(i))
        }
        for ((layout, width, height) in layouts) {
            val view = RemoteViews(app.packageName, layout).apply(app, FrameLayout(app))
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, height)
            assertNotNull(view.background)
            check(view)
            val image = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            try {
                view.draw(android.graphics.Canvas(image))
                val output = java.io.File("build/reports/${app.resources.getResourceEntryName(layout)}-$width-$height.png")
                output.parentFile!!.mkdirs()
                output.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            } finally { image.recycle() }
        }
        val service = Robolectric.buildService(PhotoWidgetAlbumService::class.java).create()
        assertEquals(R.layout.photo_widget_loading, service.get().onGetViewFactory(Intent()).loadingView.layoutId)
        service.destroy()
    }
}
