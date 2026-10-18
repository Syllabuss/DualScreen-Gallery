package it.ayngallery

import android.app.PendingIntent
import android.appwidget.*
import android.content.*
import android.graphics.Bitmap
import android.net.Uri
import android.os.*
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import androidx.work.*
import coil3.SingletonImageLoader
import coil3.decode.BitmapFactoryDecoder
import coil3.request.*
import coil3.toBitmap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class PhotoWidgetConfig(val selection: FrameConfig = FrameConfig(), val fixedMedia: String? = null,
    val minutes: Int = 30, val fallbackDisplay: String = "", val seconds: Int? = null, val fade: Boolean = false, val zoom: Float = 1f, val panX: Float = 0f, val panY: Float = 0f, val cropAspect: Float? = null, val transition: String = "NONE", val order: String = "OLDEST") {
    fun validate(requireSelection: Boolean = true) {
        require(zoom.isFinite() && zoom in 1f..5f && panX.isFinite() && panX in -1f..1f && panY.isFinite() && panY in -1f..1f)
        selection.validate(requireSelection)
        require(minutes == 0 || minutes in 30..1440)
        require(fixedMedia == null || fixedMedia.isNotBlank() && fixedMedia.length <= 512)
        require(fallbackDisplay.length <= 512)
        require(seconds == null || seconds in listOf(5, 10, 30, 60, 300))
        require(cropAspect == null || cropAspect.isFinite() && cropAspect > 0f)
        require(order in setOf("NEWEST", "OLDEST", "SHUFFLE"))
        require(transition in setOf("NONE", "LEFT", "RIGHT", "UP", "DOWN", "ROTATE"))
    }
    fun media(data: CatalogSnapshot): List<MediaEntity> {
        val items = selection.media(data).filter { fixedMedia == null || it.id == fixedMedia }
        return if (order == "NEWEST") items.sortedWith(compareByDescending<MediaEntity> { it.modified }.thenBy { it.id })
            else items.sortedWith(compareBy<MediaEntity> { it.modified }.thenBy { it.id })
    }
    fun encode(requireSelection: Boolean = true): String {
        validate(requireSelection)
        return JSONObject().put("version", 1).put("selection", JSONObject(selection.encode(requireSelection)))
            .put("fixed", fixedMedia ?: JSONObject.NULL).put("minutes", minutes).put("display", fallbackDisplay)
            .put("seconds", seconds ?: JSONObject.NULL).put("fade", fade).put("zoom", zoom).put("panX", panX).put("panY", panY).put("cropAspect", cropAspect ?: JSONObject.NULL).put("transition", transition).put("order", order).toString()
    }
    companion object {
        fun decode(text: String, requireSelection: Boolean = true): PhotoWidgetConfig {
            require(text.length <= 80_000)
            val json = JSONObject(text); require(json.getInt("version") == 1)
            return PhotoWidgetConfig(FrameConfig.decode(json.getJSONObject("selection").toString(), requireSelection),
                if (json.isNull("fixed")) null else json.getString("fixed"), json.getInt("minutes"), json.getString("display"),
                if (json.isNull("seconds")) null else json.getInt("seconds"), json.optBoolean("fade", false), json.optDouble("zoom", 1.0).toFloat(), json.optDouble("panX", 0.0).toFloat(), json.optDouble("panY", 0.0).toFloat(),
                if (!json.has("cropAspect") || json.isNull("cropAspect")) null else json.getDouble("cropAspect").toFloat(), json.optString("transition", "NONE"), json.optString("order", if (json.getJSONObject("selection").optBoolean("shuffled")) "SHUFFLE" else "OLDEST")).also { it.validate(requireSelection) }
        }
    }
}

internal fun photoWidgetSequence(config: PhotoWidgetConfig, items: List<MediaEntity>, epoch: Long, now: Long, widgetId: Int): List<MediaEntity> {
    val ordered = if (config.order == "SHUFFLE" || config.selection.shuffled) items.shuffled(kotlin.random.Random(epoch xor widgetId.toLong())) else items
    if (ordered.isEmpty()) return ordered
    val offset = (((now - epoch).coerceAtLeast(0) / ((config.seconds ?: 30) * 1000L)) % ordered.size).toInt()
    return ordered.drop(offset) + ordered.take(offset)
}

data class PhotoWidgetTarget(val widgetId: Int, val id: String, val modified: Long, val size: Long) {
    fun put(intent: Intent) { intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId).putExtra("photo_media", id)
        .putExtra("photo_modified", modified).putExtra("photo_size", size) }
    companion object {
        fun from(intent: Intent): PhotoWidgetTarget? {
            val id = intent.getStringExtra("photo_media")?.takeIf { it.isNotBlank() && it.length <= 512 } ?: return null
            val widget = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
            val modified = intent.getLongExtra("photo_modified", 0); val size = intent.getLongExtra("photo_size", 0)
            return if (widget > 0 && modified >= 0 && size >= 0) PhotoWidgetTarget(widget, id, modified, size) else null
        }
    }
}

class PhotoWidgetSettings(context: Context) {
    private val prefs = context.getSharedPreferences("photo_widgets_v1", Context.MODE_PRIVATE)
    fun get(id: Int): PhotoWidgetConfig = prefs.getString("config:$id", null)?.let { runCatching { PhotoWidgetConfig.decode(it) }.getOrNull() } ?: PhotoWidgetConfig()
    fun slideshowEpoch(id: Int): Long {
        val key = "slideshow_epoch:$id"
        val existing = prefs.getLong(key, 0)
        if (existing > 0) return existing
        val now = System.currentTimeMillis()
        check(prefs.edit().putLong(key, now).commit())
        return now
    }
    fun resetSlideshow(id: Int) { check(prefs.edit().putLong("slideshow_epoch:$id", System.currentTimeMillis()).commit()) }
    internal fun save(id: Int, config: PhotoWidgetConfig) { require(id > 0); check(prefs.edit().putString("config:$id", config.encode()).commit()) }
    fun configured(id: Int) = prefs.contains("config:$id")
    fun cursor(id: Int): FrameCursor {
        val current = prefs.getString("current:$id", null)
        return runCatching {
            val text = prefs.getString("deck:$id", null) ?: return@runCatching FrameCursor(current = current)
            require(text.length <= 2_000_000)
            val json = JSONObject(text); require(json.getInt("version") == 1)
            fun ids(key: String, max: Int): List<String> {
                val array = json.getJSONArray(key); require(array.length() <= max)
                return (0 until array.length()).map { array.getString(it).also { id -> require(id.length in 1..512) } }
            }
            FrameCursor(ids("ids", 10_000), current, json.getBoolean("shuffled"), ids("bag", 10_000), ids("previous", 64), ids("forward", 64))
        }.getOrDefault(FrameCursor(current = current))
    }
    internal fun cursor(id: Int, cursor: FrameCursor) {
        val json = if (cursor.ids.size <= 10_000) JSONObject().put("version", 1).put("ids", JSONArray(cursor.ids))
            .put("bag", JSONArray(cursor.bag)).put("previous", JSONArray(cursor.previous)).put("forward", JSONArray(cursor.forward))
            .put("shuffled", cursor.shuffled).toString().takeIf { it.length <= 2_000_000 } else null
        check(prefs.edit().putString("current:$id", cursor.current).putString("deck:$id", json).commit())
    }
    internal fun remove(id: Int) { check(prefs.edit().remove("slideshow_epoch:$id").remove("config:$id").remove("current:$id").remove("deck:$id").commit()) }
}

/** No live timer or player. One bounded decode per candidate; updates are serialized per process. */
class PhotoWidgets(private val app: GalleryApp) {
    val settings = PhotoWidgetSettings(app)
    private val mutex = Mutex()
    private val revision = MutableStateFlow(0L)
    private val cursors = mutableMapOf<Int, FrameCursor>()
    private val manager get() = AppWidgetManager.getInstance(app)
    fun ids() = manager.getAppWidgetIds(ComponentName(app, PhotoWidgetProvider::class.java)).toList()
    fun owns(id: Int) = id > 0 && manager.getAppWidgetInfo(id)?.provider == ComponentName(app, PhotoWidgetProvider::class.java)
    fun refresh() { revision.update { it + 1 } }
    fun observe() {
        app.catalog.scope.launch {
            combine(app.catalog.snapshot, app.preferences.state, revision) { data, _, _ -> data.initialized }
                .collectLatest { if (it) update() }
        }
    }
    suspend fun configure(id: Int, config: PhotoWidgetConfig) = mutex.withLock {
        val size = photoWidgetSize(app, id)
        check(owns(id)) { localized(R.string.text_widget_no_longer_available_f999f8, "Widget non più disponibile") }
        config.validate()
        if (settings.get(id).let { it.selection != config.selection || it.order != config.order }) {
            settings.resetSlideshow(id)
            settings.cursor(id, FrameCursor())
            cursors.remove(id)
        }
        applyConfig(id, config.forShape(size.width / size.height))
    }
    suspend fun resized(id: Int) = mutex.withLock {
        if (owns(id) && settings.configured(id)) {
            val size = photoWidgetSize(app, id)
            val old = settings.get(id)
            val next = old.forShape(size.width / size.height)
            if (next != old) settings.save(id, next)
        }
        updateOne(id, 0)
    }
    suspend fun assign(id: Int, frozen: MediaEntity) = mutex.withLock {
        val data = app.catalog.readMediaSnapshot(frozen.id)
        val current = data.byId[frozen.id]
        require(current != null && data.usable(current) && current.kind in setOf("IMAGE", "GIF") &&
            !DocumentVersions.changed(frozen.size, frozen.modified, current.size, current.modified)) { localized(R.string.text_photo_changed_or_unavailable_fdb784, "Foto cambiata o non disponibile") }
        val old = settings.get(id)
        applyConfig(id, old.copy(selection = FrameConfig(fill = old.selection.fill), fixedMedia = current.id))
    }
    private suspend fun applyConfig(id: Int, config: PhotoWidgetConfig) {
        check(owns(id)) { localized(R.string.text_widget_no_longer_available_f999f8, "Widget non più disponibile") }
        app.catalog.writer.withLock {
            app.catalog.rememberAlbumsLocked(config.selection.albums.toList())
            config.fixedMedia?.let { app.catalog.rememberLocked(listOf(it)) }
        }
        settings.save(id, config)
        schedule(id, config)
        updateOne(id, 0)
    }
    fun schedule(id: Int, config: PhotoWidgetConfig = settings.get(id)) {
        val work = WorkManager.getInstance(app)
        if (!owns(id) || !settings.configured(id) || config.minutes == 0 || config.fixedMedia != null || config.seconds != null) work.cancelUniqueWork(workName(id))
        else work.enqueueUniquePeriodicWork(workName(id), ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<PhotoWidgetWorker>(config.minutes.toLong(), TimeUnit.MINUTES)
                .setInitialDelay(config.minutes.toLong(), TimeUnit.MINUTES)
                .setInputData(workDataOf("widget" to id)).build())
    }
    suspend fun delete(ids: List<Int>) = mutex.withLock {
        ids.forEach { id -> settings.remove(id); cursors.remove(id); WorkManager.getInstance(app).cancelUniqueWork(workName(id)) }
    }
    suspend fun update(ids: List<Int> = ids(), direction: Int = 0, automatic: Boolean = false, expected: String? = null) = mutex.withLock {
        require(direction in -1..1)
        for (id in ids.filter(::owns)) {
            if (expected != null && settings.cursor(id).current != expected) continue
            val config = settings.get(id)
            val powerSaving = app.getSystemService(PowerManager::class.java).isPowerSaveMode
            val step = if (automatic && (config.minutes == 0 || app.preferences.state.value.gameMode || powerSaving || config.fixedMedia != null)) 0 else direction
            updateOne(id, step)
        }
    }
    private suspend fun updateOne(id: Int, direction: Int) {
        if (!owns(id)) return
        val shape = photoWidgetSize(app, id)
        val stored = settings.get(id)
        val config = if (settings.configured(id)) stored.forShape(shape.width / shape.height) else stored
        if (config != stored) settings.save(id, config)
        if (!settings.configured(id)) { publish(id, config, null, null, 0, null); return }
        if (config.fixedMedia == null && config.selection.scope == "SELECTED" && config.selection.albums.isNotEmpty()) {
            val available = app.catalog.readSnapshot().visibleAlbums().map { it.id }.toSet()
            val selected = config.selection.albums.intersect(available)
            if (selected.isNotEmpty()) try { app.albums.albumContents(selected) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                publish(id, config, null, null, 0, localized(R.string.text_folder_unavailable_1f6e37, "Cartella non disponibile"))
                return
            }
        }
        val data = app.catalog.readSnapshot()
        val size = photoWidgetSize(app, id)
        val pixels = photoWidgetPixels(app, size)
        val eligible = config.media(data)
        var cursor = (cursors[id] ?: settings.cursor(id)).reconcile(eligible.map { it.id }, (config.order == "SHUFFLE" || config.selection.shuffled))
        // Clear excluded pixels before IO, including a current photo removed by a changed configuration.
        val previous = settings.cursor(id).current
        val previousAccess = previous?.let { app.catalog.readMediaSnapshot(it) }
        if (previous !in eligible.map { it.id } || previousAccess?.byId?.get(previous)?.let(previousAccess::usable) != true)
            publish(id, config, null, null, 0, localized(R.string.text_no_photo_available_3cf246, "Nessuna foto disponibile"))
        if (direction != 0 && config.fixedMedia == null) cursor = cursor.move(direction)
        var chosen: MediaEntity? = null
        var bitmap: Bitmap? = null
        val failed = mutableSetOf<String>()
        var lastAttempted: String? = null
        repeat(minOf(eligible.size, 3)) {
            if (chosen != null) return@repeat
            val item = eligible.firstOrNull { it.id == cursor.current && it.id !in failed } ?: return@repeat
            lastAttempted = item.id
            try {
                val fresh = app.catalog.readMediaSnapshot(item.id)
                val target = fresh.byId[item.id]?.takeIf { fresh.usable(it) && it.kind in setOf("IMAGE", "GIF") } ?: throw SecurityException(localized(R.string.text_photo_unavailable_d15d30, "Foto non disponibile"))
                bitmap = withTimeout(3000) {
                    val result = SingletonImageLoader.get(app).execute(ImageRequest.Builder(app).data(app.catalog.uri(target, fresh))
                        .memoryCacheKey("photo-widget/${target.id}/${target.modified}/${target.size}")
                        .memoryCachePolicy(CachePolicy.DISABLED).allowHardware(false).size(pixels, pixels).apply { if (target.kind == "GIF") decoderFactory(BitmapFactoryDecoder.Factory()) }.build())
                    (result as? SuccessResult)?.image?.toBitmap()
                }
                if (bitmap != null) chosen = target
            } catch (cancelled: CancellationException) { if (cancelled !is TimeoutCancellationException) throw cancelled }
            catch (_: Exception) { /* A failed read is not a file mutation. */ }
            if (chosen == null) { failed += item.id; cursor = cursor.reconcile(eligible.filter { it.id !in failed }.map { it.id }, (config.order == "SHUFFLE" || config.selection.shuffled)) }
        }
        val animation = chosen?.takeIf { it.kind == "GIF" && !app.preferences.state.value.gameMode }?.let {
            gifWidgetFrames(app, it, config, size.width.toInt(), size.height.toInt())
        }
        currentCoroutineContext().ensureActive()
        app.catalog.writer.withLock {
            val latest = app.catalog.readSnapshot()
            val checked = chosen?.let { old ->
                val access = app.catalog.readMediaSnapshot(old.id)
                config.media(latest).firstOrNull { it.id == old.id && access.byId[it.id]?.let(access::usable) == true &&
                    !DocumentVersions.changed(old.size, old.modified, it.size, it.modified) }
            }
            if (!owns(id)) return@withLock
            if (checked == null) {
                // Keep all eligible IDs so a later manual or scheduled refresh can retry corrupt/provider failures.
                cursor = cursor.reconcile(config.media(latest).map { it.id }, (config.order == "SHUFFLE" || config.selection.shuffled))
                lastAttempted?.takeIf { it in cursor.ids }?.let { cursor = cursor.copy(current = it) }
                publish(id, config, null, null, 0, if (config.media(latest).isEmpty()) localized(R.string.text_no_photo_available_3cf246, "Nessuna foto disponibile") else localized(R.string.text_preview_unreadable_try_again_45db31, "Anteprima non leggibile · riprova"))
            } else {
                cursor = cursor.copy(current = checked.id)
                publish(id, config, checked, bitmap, config.media(latest).size, null, animation)
            }
            cursors[id] = cursor; settings.cursor(id, cursor)
        }
    }
    private suspend fun publish(id: Int, config: PhotoWidgetConfig, item: MediaEntity?, bitmap: Bitmap?, count: Int, error: String?, animation: WidgetGif? = null) = withContext(Dispatchers.Main) {
        if (owns(id)) {
            manager.updateAppWidget(id, photoWidgetViews(app, id, config, app.preferences.state.value, item, bitmap, count, error, animation))
            if (config.seconds != null && config.fixedMedia == null) manager.notifyAppWidgetViewDataChanged(id, R.id.photo_slideshow)
        }
    }
    companion object { fun workName(id: Int) = "photo-widget-$id" }
}

internal fun photoWidgetViews(context: Context, id: Int, config: PhotoWidgetConfig, prefs: GalleryPreferences,
    item: MediaEntity?, bitmap: Bitmap?, count: Int, error: String?, animation: WidgetGif? = null): RemoteViews {
    val theme = GalleryThemes.get(prefs)
    val setup = PendingIntent.getActivity(context, id, Intent(context, PhotoWidgetConfigActivity::class.java)
        .setData(Uri.parse("ayngallery-photo://$id/configure")).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val slideshow = config.seconds != null && config.fixedMedia == null && count > 1 && bitmap != null && !prefs.gameMode
    val layout = if (slideshow) photoTransitionLayout(config.transition, config.seconds!!) ?: photoSlideshowLayout(config.seconds, config.fade) else R.layout.photo_widget
    return RemoteViews(context.packageName, layout).apply {
        if (!slideshow) setDisplayedChild(R.id.photo_reveal, 0)
        if (slideshow) {
            setViewVisibility(R.id.photo_picture, View.GONE)
            setRemoteAdapter(R.id.photo_slideshow, Intent(context, PhotoWidgetAlbumService::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).setData(Uri.parse("ayngallery-photo://$id/album/${config.seconds}/${config.fade}")))
            setPendingIntentTemplate(R.id.photo_slideshow, setup)
        }
        setViewVisibility(R.id.photo_background, View.GONE)
        if (animation != null && !slideshow && !prefs.gameMode) {
            setViewVisibility(R.id.photo_animation, View.VISIBLE)
            removeAllViews(R.id.photo_animation)
            setInt(R.id.photo_animation, "setFlipInterval", animation.interval)
            animation.frames.forEach { frame -> addView(R.id.photo_animation, RemoteViews(context.packageName, R.layout.photo_widget_gif_frame).apply { setImageViewBitmap(R.id.photo_frame, frame) }) }
        } else setViewVisibility(R.id.photo_animation, View.GONE)
        val size = photoWidgetSize(context, id)
        val rounded = bitmap?.let { photoWidgetBitmap(it, config.selection.fill,
            size.width.toInt(), size.height.toInt(), if (config.fixedMedia != null) config.zoom else 1f,
            if (config.fixedMedia != null) config.panX else 0f, if (config.fixedMedia != null) config.panY else 0f, maxSide = photoWidgetPixels(context, size)) }
        val picture = if (config.selection.fill) R.id.photo_fill else R.id.photo_fit
        val animated = animation != null && !slideshow && !prefs.gameMode
        setViewVisibility(R.id.photo_fill, if (picture == R.id.photo_fill && bitmap != null && !animated) View.VISIBLE else View.GONE)
        setViewVisibility(R.id.photo_fit, if (picture == R.id.photo_fit && bitmap != null && !animated) View.VISIBLE else View.GONE)
        setImageViewBitmap(R.id.photo_fit, if (picture == R.id.photo_fit) rounded else null)
        setImageViewBitmap(R.id.photo_fill, if (picture == R.id.photo_fill) rounded else null)
        setViewVisibility(R.id.photo_empty, if (bitmap == null) View.VISIBLE else View.GONE)
        setInt(R.id.photo_empty, "setColorFilter", theme.accent.toArgb())
        setContentDescription(R.id.photo_picture, localized(R.string.text_settings_7b855c, "Impostazioni"))
        setOnClickPendingIntent(R.id.photo_picture, null)
        setOnClickPendingIntent(R.id.photo_touch, setup)
        setContentDescription(R.id.photo_touch, localized(R.string.text_settings_7b855c, "Impostazioni"))
        setTextViewText(R.id.photo_title, item?.name ?: localized(R.string.text_your_images_41bd0e, "Le tue immagini"))
        setTextColor(R.id.photo_title, theme.colors.onSurface.toArgb())
        setTextViewText(R.id.photo_status, error ?: if (config.fixedMedia != null) localized(R.string.text_fixed_image_631654, "Immagine fissa") else if (prefs.gameMode) localized(R.string.text_automatic_changes_paused_game_mode_1dd71f, "Automatico sospeso · Gioco")
            else if (config.minutes == 0) localized(R.string.text_manual_changes_6f0bb8, "Cambio manuale") else localized(R.string.text_about_1_s_min_2ef38b, "Circa %1\$s min", config.minutes))
        setTextColor(R.id.photo_status, theme.colors.onSurfaceVariant.toArgb())
        setOnClickPendingIntent(R.id.photo_title, setup)
        for (view in listOf(R.id.photo_title, R.id.photo_status, R.id.photo_previous, R.id.photo_next, R.id.photo_settings))
            setViewVisibility(view, View.GONE)
    }
}

internal fun PhotoWidgetConfig.forShape(aspect: Float): PhotoWidgetConfig {
    val reset = fixedMedia == null || cropAspect != null && kotlin.math.abs(aspect / cropAspect - 1f) > .02f
    return copy(zoom = if (reset) 1f else zoom, panX = if (reset) 0f else panX, panY = if (reset) 0f else panY, cropAspect = aspect)
}

internal fun photoWidgetSize(context: Context, id: Int): android.util.SizeF {
    val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(id)
    val landscape = context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    if (Build.VERSION.SDK_INT >= 31) {
        @Suppress("DEPRECATION") val sizes = options.getParcelableArrayList<android.util.SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
        sizes?.filter { it.width > 0 && it.height > 0 }?.maxByOrNull { if (landscape) it.width else it.height }?.let { return it }
    }
    return android.util.SizeF(options.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,
        options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 240)).coerceAtLeast(1).toFloat(),
        options.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 240)).coerceAtLeast(1).toFloat())
}

internal fun photoWidgetPixels(context: Context, size: android.util.SizeF): Int = (maxOf(size.width, size.height) * context.resources.displayMetrics.density).toInt().coerceIn(384, 1536)

internal fun photoWidgetBitmap(photo: Bitmap, fill: Boolean, hostWidth: Int = 240, hostHeight: Int = 240, zoom: Float = 1f, panX: Float = 0f, panY: Float = 0f, maxSide: Int = 768): Bitmap {
    val ratio = if (fill) hostWidth.coerceAtLeast(1).toDouble() / hostHeight.coerceAtLeast(1) else photo.width.toDouble() / photo.height
    val width = if (ratio >= 1) maxSide else (maxSide * ratio).toInt().coerceAtLeast(1)
    val height = if (ratio <= 1) maxSide else (maxSide / ratio).toInt().coerceAtLeast(1)
    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        val canvas = android.graphics.Canvas(this)
        val bounds = android.graphics.RectF(0f, 0f, width.toFloat(), height.toFloat())
        canvas.clipPath(android.graphics.Path().apply { addRoundRect(bounds, 16f, 16f, android.graphics.Path.Direction.CW) })
        val src = if (fill) photoWidgetCrop(photo.width, photo.height, ratio, zoom, panX, panY)
            else android.graphics.Rect(0, 0, photo.width, photo.height)
        canvas.drawBitmap(photo, src, bounds, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG))
    }
}

internal fun photoWidgetCrop(width: Int, height: Int, ratio: Double, zoom: Float, panX: Float, panY: Float): android.graphics.Rect {
    val cropWidth = (minOf(width, (height * ratio).toInt()) / zoom).toInt().coerceAtLeast(1)
    val cropHeight = (minOf(height, (width / ratio).toInt()) / zoom).toInt().coerceAtLeast(1)
    val left = ((width - cropWidth) * (1f - panX.coerceIn(-1f, 1f)) / 2).toInt()
    val top = ((height - cropHeight) * (1f - panY.coerceIn(-1f, 1f)) / 2).toInt()
    return android.graphics.Rect(left, top, left + cropWidth, top + cropHeight)
}

class PhotoWidgetWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as GalleryApp
        val id = inputData.getInt("widget", -1)
        if (!app.photoWidgets.owns(id)) return Result.success()
        return try { app.photoWidgets.update(listOf(id), 1, automatic = true); Result.success() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { Result.retry() }
    }
}

class PhotoWidgetProvider : AppWidgetProvider() {
    companion object { const val STEP = "it.ayngallery.PHOTO_WIDGET_STEP"; const val OPEN = "it.ayngallery.OPEN_PHOTO" }
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != STEP) { super.onReceive(context, intent); return }
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
        val direction = intent.getIntExtra("direction", 0)
        val app = context.applicationContext as GalleryApp
        if (!app.photoWidgets.owns(id) || direction !in setOf(-1, 1)) return
        receive(app) { app.photoWidgets.update(listOf(id), direction, expected = intent.getStringExtra("expected")) }
    }
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val app = context.applicationContext as GalleryApp
        ids.filter(app.photoWidgets::owns).forEach { app.photoWidgets.schedule(it) }
        receive(app) { app.photoWidgets.update(ids.toList()) }
    }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        val app = context.applicationContext as GalleryApp
        receive(app) { app.photoWidgets.resized(id) }
    }
    override fun onDeleted(context: Context, ids: IntArray) {
        val app = context.applicationContext as GalleryApp
        receive(app) { app.photoWidgets.delete(ids.toList()) }
    }
    private fun receive(app: GalleryApp, action: suspend () -> Unit) {
        val pending = goAsync()
        app.catalog.scope.launch {
            try { withTimeout(8000) { action() } }
            catch (cancelled: CancellationException) { if (cancelled !is TimeoutCancellationException) throw cancelled }
            catch (_: Exception) { app.photoWidgets.refresh() }
            finally { pending?.finish() }
        }
    }
}
