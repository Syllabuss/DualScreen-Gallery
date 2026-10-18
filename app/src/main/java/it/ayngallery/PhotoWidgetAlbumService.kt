package it.ayngallery

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import coil3.SingletonImageLoader
import coil3.decode.BitmapFactoryDecoder
import coil3.request.*
import coil3.toBitmap
import kotlinx.coroutines.*

/** The launcher advances frames; only the requested image is decoded, with a bounded size. */
class PhotoWidgetAlbumService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = object : RemoteViewsFactory {
        private val app = application as GalleryApp
        private val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
        private var rows = emptyList<MediaEntity>()
        override fun onCreate() = Unit
        override fun onDestroy() { rows = emptyList() }
        override fun onDataSetChanged() {
            rows = if (!app.photoWidgets.owns(id)) emptyList() else runBlocking(Dispatchers.IO) {
                val config = app.photoWidgets.settings.get(id)
                try {
                    withTimeout(8000) { app.albums.albumContents(config.selection.albums) }
                    val items = config.media(app.catalog.readSnapshot()).take(10_000)
                    val epoch = app.photoWidgets.settings.slideshowEpoch(id)
                    photoWidgetSequence(config, items, epoch, System.currentTimeMillis(), id)
                } catch (_: Exception) { emptyList() }
            }
        }
        override fun getCount() = rows.size
        override fun getViewTypeCount() = 2
        override fun getItemId(position: Int) = position.toLong()
        override fun hasStableIds() = false
        override fun getLoadingView() = RemoteViews(packageName, R.layout.photo_widget_loading)
        override fun getViewAt(position: Int): RemoteViews {
            val reveal = position == 0
            val view = RemoteViews(packageName, if (reveal) R.layout.photo_widget_frame_reveal else R.layout.photo_widget_frame)
            if (reveal) view.setDisplayedChild(R.id.photo_reveal, 0)
            val expected = rows.getOrNull(position) ?: return view
            if (!app.photoWidgets.owns(id)) return view
            return runBlocking(Dispatchers.IO) {
                try {
                    withTimeout(3000) {
                        val data = app.catalog.readMediaSnapshot(expected.id)
                        val item = data.byId[expected.id]?.takeIf { data.usable(it) && !DocumentVersions.changed(expected.size, expected.modified, it.size, it.modified) } ?: return@withTimeout view
                        val size = photoWidgetSize(app, id)
                        val pixels = photoWidgetPixels(app, size)
                        val result = SingletonImageLoader.get(app).execute(ImageRequest.Builder(app).data(app.catalog.uri(item, data))
                            .memoryCachePolicy(CachePolicy.DISABLED).allowHardware(false).size(pixels, pixels)
                            .apply { if (item.kind == "GIF") decoderFactory(BitmapFactoryDecoder.Factory()) }.build())
                        val bitmap = (result as? SuccessResult)?.image?.toBitmap() ?: return@withTimeout view
                        val fresh = app.catalog.readMediaSnapshot(item.id)
                        if (fresh.byId[item.id]?.let { fresh.usable(it) && !DocumentVersions.changed(item.size, item.modified, it.size, it.modified) } != true) return@withTimeout view
                        val config = app.photoWidgets.settings.get(id)
                        view.setImageViewBitmap(R.id.photo_frame, photoWidgetBitmap(bitmap, true,
                            size.width.toInt(), size.height.toInt(), maxSide = pixels))
                        if (item.kind == "GIF" && !app.preferences.state.value.gameMode) {
                            gifWidgetFrames(app, item, config.copy(selection = config.selection.copy(fill = true)), size.width.toInt(), size.height.toInt())?.let { animation ->
                                view.setViewVisibility(R.id.photo_frame, android.view.View.GONE)
                                view.setViewVisibility(R.id.photo_animation, android.view.View.VISIBLE)
                                view.setInt(R.id.photo_animation, "setFlipInterval", animation.interval)
                                animation.frames.forEach { frame -> view.addView(R.id.photo_animation, RemoteViews(packageName, R.layout.photo_widget_gif_frame).apply {
                                    setImageViewBitmap(R.id.photo_frame, frame)
                                }) }
                            }
                        }
                        val finalAccess = app.catalog.readMediaSnapshot(item.id)
                        if (finalAccess.byId[item.id]?.let { finalAccess.usable(it) && !DocumentVersions.changed(item.size, item.modified, it.size, it.modified) } != true) return@withTimeout RemoteViews(packageName, R.layout.photo_widget_frame)
                        view
                    }
                // An access/decode failure after setting pixels must never return that partially built view.
                } catch (_: Exception) { RemoteViews(packageName, R.layout.photo_widget_frame) }
            }
        }
    }
}

internal fun photoTransitionLayout(effect: String, seconds: Int = 5): Int? = when (effect) {
    "LEFT" -> when (seconds) {
        5 -> R.layout.photo_widget_album_5_left
        10 -> R.layout.photo_widget_album_10_left
        30 -> R.layout.photo_widget_album_30_left
        60 -> R.layout.photo_widget_album_60_left
        300 -> R.layout.photo_widget_album_300_left
        else -> null
    }
    "RIGHT" -> when (seconds) {
        5 -> R.layout.photo_widget_album_5_right
        10 -> R.layout.photo_widget_album_10_right
        30 -> R.layout.photo_widget_album_30_right
        60 -> R.layout.photo_widget_album_60_right
        300 -> R.layout.photo_widget_album_300_right
        else -> null
    }
    "UP" -> when (seconds) {
        5 -> R.layout.photo_widget_album_5_up
        10 -> R.layout.photo_widget_album_10_up
        30 -> R.layout.photo_widget_album_30_up
        60 -> R.layout.photo_widget_album_60_up
        300 -> R.layout.photo_widget_album_300_up
        else -> null
    }
    "DOWN" -> when (seconds) {
        5 -> R.layout.photo_widget_album_5_down
        10 -> R.layout.photo_widget_album_10_down
        30 -> R.layout.photo_widget_album_30_down
        60 -> R.layout.photo_widget_album_60_down
        300 -> R.layout.photo_widget_album_300_down
        else -> null
    }
    "ROTATE" -> when (seconds) {
        5 -> R.layout.photo_widget_album_5_rotate
        10 -> R.layout.photo_widget_album_10_rotate
        30 -> R.layout.photo_widget_album_30_rotate
        60 -> R.layout.photo_widget_album_60_rotate
        300 -> R.layout.photo_widget_album_300_rotate
        else -> null
    }
    else -> null
}

internal fun photoSlideshowLayout(seconds: Int, fade: Boolean): Int = when (seconds) {
    5 -> if (fade) R.layout.photo_widget_album_5_fade else R.layout.photo_widget_album_5_still
    10 -> if (fade) R.layout.photo_widget_album_10_fade else R.layout.photo_widget_album_10_still
    60 -> if (fade) R.layout.photo_widget_album_60_fade else R.layout.photo_widget_album_60_still
    300 -> if (fade) R.layout.photo_widget_album_300_fade else R.layout.photo_widget_album_300_still
    else -> if (fade) R.layout.photo_widget_album_30_fade else R.layout.photo_widget_album_30_still
}
