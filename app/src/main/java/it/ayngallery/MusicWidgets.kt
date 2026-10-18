package it.ayngallery

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray

data class MusicWidgetConfig(val background: String = "SYSTEM", val fallbackDisplay: String = "", val showShuffle: Boolean = false,
    val color: Int? = null, val playlist: String? = null, val effect: String = "NONE", val barsColor: Int? = null, val initialTrack: String? = null, val discHole: Boolean = true,
    val circle: Boolean = false) {
    val rotatingCover get() = effect in setOf("DISC", "CIRCLE", "COIN")
    val circularCover get() = circle || rotatingCover
}

class MusicWidgetSettings(context: Context) {
    private val prefs = context.getSharedPreferences("music_widgets_v1", Context.MODE_PRIVATE)
    fun get(id: Int) = MusicWidgetConfig("SYSTEM", prefs.getString("display:$id", "") ?: "", prefs.getBoolean("shuffle:$id", false),
        if (prefs.contains("color:$id")) prefs.getInt("color:$id", Color.BLACK) else null, prefs.getString("playlist:$id", null),
        prefs.getString("effect:$id", "NONE")?.takeIf { it in setOf("NONE", "DISC", "CIRCLE", "COIN", "PULSE", "BARS") } ?: "NONE",
        if (prefs.contains("barsColor:$id")) prefs.getInt("barsColor:$id", Color.BLACK) else null, prefs.getString("initialTrack:$id", null), prefs.getBoolean("discHole:$id", true))
    fun save(id: Int, value: MusicWidgetConfig) {
        require(id > 0 && value.background in setOf("DARK", "LIGHT", "SYSTEM", "TRANSPARENT", "ARTWORK", "THEME"))
        require(value.playlist == null || value.playlist.length in 1..512)
        require(value.initialTrack == null || value.initialTrack.length in 1..512)
        require(value.effect in setOf("NONE", "DISC", "CIRCLE", "COIN", "PULSE", "BARS"))
        val edit = prefs.edit().putString("background:$id", value.background).putString("display:$id", value.fallbackDisplay).putBoolean("shuffle:$id", value.showShuffle).putString("playlist:$id", value.playlist)
        edit.putString("effect:$id", value.effect)
        edit.putString("initialTrack:$id", value.initialTrack).putBoolean("discHole:$id", value.discHole)
        if (value.color == null) edit.remove("color:$id") else edit.putInt("color:$id", value.color)
        if (value.barsColor == null) edit.remove("barsColor:$id") else edit.putInt("barsColor:$id", value.barsColor)
        check(edit.commit())
    }
    fun remove(id: Int) { check(prefs.edit().remove("background:$id").remove("display:$id").remove("shuffle:$id").remove("error:$id").remove("color:$id").remove("playlist:$id").remove("initialTrack:$id").remove("effect:$id").remove("barsColor:$id").remove("discHole:$id").commit()) }
    fun error(id: Int): String? = prefs.getString("error:$id", null)
    fun error(id: Int, message: String?) { check(prefs.edit().putString("error:$id", message).commit()) }
}

data class MusicWidgetDisplay(val mediaId: String?, val title: String, val artist: String, val status: String,
    val artwork: String?, val playing: Boolean, val position: Long, val duration: Long,
    val available: Boolean, val previous: Boolean, val next: Boolean, val favorite: Boolean, val shuffle: Boolean = false, val repeat: Int = Player.REPEAT_MODE_OFF)

internal fun initialWidgetPlayback(data: CatalogSnapshot, live: PlaybackState?, config: MusicWidgetConfig): PlaybackState? {
    val track = config.initialTrack?.let(data.byId::get)?.takeIf { it.kind == "AUDIO" && data.usable(it) } ?: return live
    return if (live?.playing == true || live?.id == track.id) live else (live ?: PlaybackState()).copy(id = track.id, queue = listOf(track.id), index = 0, position = 0, duration = track.duration ?: 0)
}

internal fun musicWidgetDisplay(catalog: Catalog, data: CatalogSnapshot, live: PlaybackState?, error: String? = null): MusicWidgetDisplay {
    val prefs = catalog.context.getSharedPreferences("music", Context.MODE_PRIVATE)
    val liveQueue = live?.queue?.takeIf { it.isNotEmpty() }
    val queue = liveQueue ?: runCatching { val saved = JSONArray(prefs.getString("queue", "[]")); (0 until saved.length()).map { saved.getString(it) } }.getOrDefault(emptyList())
    val index = (if (liveQueue != null) live?.index ?: 0 else prefs.getInt("index", 0)).coerceIn(0, queue.lastIndex.coerceAtLeast(0))
    val id = live?.id ?: queue.getOrNull(index)
    fun available(id: String?) = data.byId[id]?.takeIf { it.kind == "AUDIO" && data.usable(it) }
    val item = available(id) ?: available(queue.getOrNull(index)) ?: data.visibleMedia.filter { it.kind == "AUDIO" && data.usable(it) }
        .minWithOrNull(compareBy(MediaNames.natural) { it.title?.takeIf(String::isNotBlank) ?: it.name })
    val current = item != null && item.id == live?.id
    val position = if (current) live?.position ?: 0 else if (item?.id == queue.getOrNull(index)) prefs.getLong("position", 0).coerceAtLeast(0) else 0
    val playing = current && live?.playing == true
    return MusicWidgetDisplay(item?.id, item?.title?.takeIf { it.isNotBlank() } ?: item?.name ?: if (queue.isEmpty()) "" else localized(R.string.text_track_unavailable_b73d64, "Brano non disponibile"),
        item?.artist?.takeIf { it.isNotBlank() } ?: "",
        error ?: live?.error ?: if (item == null && queue.isNotEmpty()) localized(R.string.text_source_needs_relinking_653c58, "Sorgente da ricollegare") else "",
        item?.let { catalog.artwork(it, data) }, playing, position, if (current) live?.duration ?: item?.duration ?: 0L else item?.duration ?: 0L,
        item != null, item != null && (current || item.id in queue), item != null && (if (current) live?.hasNext == true else index < queue.lastIndex || prefs.getInt("repeat", 0) == Player.REPEAT_MODE_ALL), item?.favorite == true,
        live?.shuffle ?: prefs.getBoolean("shuffle", false), live?.repeat ?: prefs.getInt("repeat", Player.REPEAT_MODE_OFF))
}

/** RemoteViews only; all widget controls send commands to the existing MediaSessionService. */
class MusicWidgets(private val app: GalleryApp) {
    val settings = MusicWidgetSettings(app)
    private val playback = MutableStateFlow<PlaybackState?>(null)
    private val revision = MutableStateFlow(0L)
    @Volatile var audioBarsEnabled = false
        private set
    @Volatile private var pulseWidgetsEnabled = false
    @Volatile private var pulsePreviewEnabled = false
    @Volatile private var pulseGridEnabled = false
    @Volatile var audioAnalysisEnabled = false
        private set
    val pulseLevel = MutableStateFlow(0f)
    private var warmup: Job? = null
    @Volatile private var hostAnimationAllowed = true
    @Volatile private var effectWidgets: Map<Int, MusicWidgetConfig> = emptyMap()
    private val coverBitmaps = android.util.LruCache<String, Bitmap>(8)
    @Volatile private var isStarting = false
    private val coverFrames = java.util.concurrent.ConcurrentHashMap<Int, Pair<Bitmap, Long>>()
    private val coverSettling = java.util.concurrent.ConcurrentHashMap<Int, Float>()
    private val manager get() = AppWidgetManager.getInstance(app)
    private val providers = listOf(MusicFullWidget::class.java, MusicMinimalWidget::class.java)
    fun ids(): List<Int> = providers.flatMap { manager.getAppWidgetIds(ComponentName(app, it)).toList() }
    fun owns(id: Int): Boolean = manager.getAppWidgetInfo(id)?.provider in providers.map { ComponentName(app, it) }
    internal fun forgetCover(id: Int) { coverFrames.remove(id); coverSettling.remove(id) }
    fun refresh() { revision.value++ }
    fun previewPulseEnabled(enabled: Boolean) {
        pulsePreviewEnabled = enabled
        audioAnalysisEnabled = playback.value?.playing == true && (audioBarsEnabled || pulseWidgetsEnabled || pulsePreviewEnabled || pulseGridEnabled)
    }
    fun gridPulseEnabled(enabled: Boolean) {
        pulseGridEnabled = enabled
        audioAnalysisEnabled = playback.value?.playing == true && (audioBarsEnabled || pulseWidgetsEnabled || pulsePreviewEnabled || pulseGridEnabled)
    }
    fun starting(value: Boolean) {
        isStarting = value
        app.catalog.scope.launch {
            if (isStarting != value) return@launch
            for (id in ids().filter(::owns)) publishViews(id, partial = true, views = sizedViews(id) { layout ->
                RemoteViews(app.packageName, layout).apply {
                    setImageViewBitmap(R.id.widget_play, widgetIcon(app, if (value) R.drawable.ph_dots_three else if (playback.value?.playing == true) R.drawable.ph_pause else R.drawable.ph_play))
                    for (button in listOf(R.id.widget_previous, R.id.widget_play, R.id.widget_next, R.id.widget_shuffle)) setBoolean(button, "setEnabled", !value)
                    setInt(R.id.widget_play, "setImageAlpha", if (value) 120 else 255)
                    if (value && layout == R.layout.music_widget_tiny) {
                        setBoolean(R.id.widget_root, "setEnabled", false)
                        setBoolean(R.id.widget_background, "setEnabled", false)
                    }
                }
            })
            refresh()
        }
    }
    fun publish(value: PlaybackState?) {
        val changed = playback.value?.playing != value?.playing
        playback.value = value
        if (changed && value?.playing != true) spectrum(FloatArray(6), settling = true)
        if (changed && value?.playing == false && !app.preferences.state.value.gameMode) settleCoverEffects()
        if (changed && value != null) app.catalog.scope.launch {
            if (playback.value?.playing != value.playing) return@launch
            val icon = widgetIcon(app, if (isStarting) R.drawable.ph_dots_three else if (value.playing) R.drawable.ph_pause else R.drawable.ph_play)
            for (id in ids().filter(::owns)) {
                publishViews(id, partial = true, views = sizedViews(id) { layout ->
                    RemoteViews(app.packageName, layout).apply {
                        setImageViewBitmap(R.id.widget_play, icon)
                    }
                })
            }
        }
    }
    fun observe() {
        app.catalog.scope.launch {
            app.session.visiblePanels.map { it < 2 }.distinctUntilChanged().collect {
                hostAnimationAllowed = it
                refresh()
            }
        }
        app.catalog.scope.launch {
            combine(app.catalog.snapshot, app.preferences.state, playback.map { if (it?.playing == true) it.copy(position = 0) else it }.distinctUntilChanged(), revision) { _, _, _, _ -> Unit }
                .collectLatest { update() }
        }
    }
    // Full renders and transport/animation patches must reach the host in one sequence.
    private suspend fun publishViews(id: Int, views: RemoteViews, partial: Boolean = false) = withContext(Dispatchers.Main.immediate) {
        if (partial) manager.partiallyUpdateAppWidget(id, views) else manager.updateAppWidget(id, views)
    }
    internal suspend fun update(targetIds: List<Int> = ids()) {
        if (targetIds.isEmpty()) { audioBarsEnabled = false; pulseWidgetsEnabled = false; audioAnalysisEnabled = (pulsePreviewEnabled || pulseGridEnabled) && playback.value?.playing == true; return }
        val data = app.catalog.readSnapshot()
        if (warmup == null && data.initialized && !app.albums.ready.value && data.sources.any { it.enabled && it.reachable && it.role in setOf("MUSIC", "MIXED") && !it.singleDocument }) {
            withContext(Dispatchers.Main.immediate) {
                if (warmup == null) warmup = app.catalog.scope.launch(Dispatchers.Main.immediate) {
                    app.albums.show(-3, GalleryRoute(tab = "MUSIC"))
                    try { app.albums.refreshAndWait() } finally { app.albums.leave(-3) }
                }
            }
        }
        val activeIds = ids().filter(::owns)
        effectWidgets = activeIds.associateWith(settings::get)
        barColors.keys.retainAll(activeIds.toSet())
        val motionAllowed = hostAnimationAllowed && !app.preferences.state.value.gameMode
        audioBarsEnabled = playback.value?.playing == true && motionAllowed && activeIds.any { settings.get(it).effect == "BARS" }
        pulseWidgetsEnabled = motionAllowed && activeIds.any { settings.get(it).effect == "PULSE" }
        audioAnalysisEnabled = playback.value?.playing == true && !app.preferences.state.value.gameMode && (audioBarsEnabled || pulseWidgetsEnabled || pulsePreviewEnabled || pulseGridEnabled)
        // Publish controls for every widget before any provider artwork read or decoder wait.
        // Cached artwork is sufficient for the first render; the second pass improves it.
        for (id in targetIds.filter(::owns)) {
            val config = settings.get(id)
            val display = musicWidgetDisplay(app.catalog, data, initialWidgetPlayback(data, playback.value, config), settings.error(id))
            val reference = display.mediaId?.let(data.byId::get)?.let { app.catalog.artworkReference(it, data) }
            val cached = reference?.let { coverBitmaps.get("${it.second}/${it.first}/${app.catalog.cacheRevision.value}") }
            publishViews(id, views = render(id, display, config, app.preferences.state.value, cached))
        }
        for (id in targetIds.filter(::owns)) {
            val display = musicWidgetDisplay(app.catalog, data, initialWidgetPlayback(data, playback.value, settings.get(id)), settings.error(id))
            val reference = display.mediaId?.let(data.byId::get)?.let { app.catalog.loadArtworkReference(it, data) }
            val revision = app.catalog.cacheRevision.value
            val artwork = reference?.first?.let { uri ->
                val key = "${reference.second}/$uri/$revision"
                coverBitmaps.get(key) ?:
                try { withTimeout(2500) {
                    val result = SingletonImageLoader.get(app).execute(ImageRequest.Builder(app).data(uri)
                        .memoryCacheKey("music-widget/${reference?.second}/$uri/$revision").allowHardware(false).size(768, 768).build())
                    (result as? SuccessResult)?.image?.toBitmap()?.also { coverBitmaps.put(key, it) }
                } } catch (cancelled: CancellationException) {
                    if (cancelled is TimeoutCancellationException) null else throw cancelled
                } catch (_: Exception) { null }
            }
            currentCoroutineContext().ensureActive()
            // Recheck visibility after asynchronous decoding; never publish a newly hidden cover from an older snapshot.
            val fresh = app.catalog.readSnapshot()
            val checked = musicWidgetDisplay(app.catalog, fresh, initialWidgetPlayback(fresh, playback.value, settings.get(id)), settings.error(id))
            val checkedReference = checked.mediaId?.let(fresh.byId::get)?.let { app.catalog.artworkReference(it, fresh) }
            val safeArt = artwork.takeIf { checked.mediaId == display.mediaId && checkedReference == reference && revision == app.catalog.cacheRevision.value }
            if (owns(id)) publishViews(id, views = render(id, checked, settings.get(id), app.preferences.state.value, safeArt))
        }
    }
    fun render(id: Int, display: MusicWidgetDisplay, config: MusicWidgetConfig, prefs: GalleryPreferences, artwork: Bitmap?): RemoteViews {
        val config = config.copy(background = "SYSTEM", color = null, barsColor = null)
        if (artwork != null) barColors[id] = widgetArtworkAccent(artwork, GalleryThemes.get(prefs).accent.toArgb())
        else if (!display.playing) barColors.remove(id)
        if ((config.rotatingCover || config.effect == "PULSE") && display.playing && !prefs.gameMode) {
            val accent = if (config.background == "THEME") GalleryThemes.get(prefs).accent.toArgb() else Color.rgb(178, 218, 255)
            val light = config.background == "LIGHT" || config.background == "SYSTEM" && app.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK != android.content.res.Configuration.UI_MODE_NIGHT_YES
            coverFrames[id] = (if (config.circularCover) widgetDisc(app, artwork, accent, config.effect == "DISC" && config.discHole) else widgetCover(app, artwork, if (light) Color.WHITE else accent)) to
                (coverFrames[id]?.second ?: SystemClock.elapsedRealtime())
        }
        if ((!config.rotatingCover && config.effect != "PULSE") || prefs.gameMode) forgetCover(id)
        return sizedViews(id, disc = config.circularCover) { layout ->
            musicWidgetViews(app, id, layout, display, config, prefs, artwork, isStarting).apply {
                coverSettling[id]?.takeIf { !display.playing && (config.rotatingCover || config.effect == "PULSE") }?.let { angle ->
                    coverFrames[id]?.first?.let { cover -> setImageViewBitmap(if (layout == R.layout.music_widget_minimal) R.id.widget_cover else R.id.widget_background, if (config.rotatingCover) widgetRotatedDisc(cover, angle) else widgetScaledCover(cover, angle)) }
                }
            }
        }
    }
    private fun settleCoverEffects() {
        for (id in ids().filter { settings.get(it).let { it.effect != "COIN" && (it.rotatingCover || it.effect == "PULSE") } }) {
            val effect = settings.get(id).effect
            val frame = coverFrames[id] ?: continue
            val phase = ((SystemClock.elapsedRealtime() - frame.second) % 9000) / 9000f * 360f
            val angle = if (effect == "PULSE") widgetPulseScale(pulseLevel.value) else if (phase > 180f) phase - 360f else phase
            coverSettling[id] = angle
            app.catalog.scope.launch {
                val motionCover = Bitmap.createScaledBitmap(frame.first, 256, 256, true)
                val started = SystemClock.elapsedRealtime()
                try {
                    do {
                        if (playback.value?.playing == true || !owns(id) || settings.get(id).effect != effect) break
                        val data = app.catalog.snapshot.value
                        if (data.initialized && data.byId[playback.value?.id]?.let(data::usable) != true) break
                        val progress = ((SystemClock.elapsedRealtime() - started) / 650f).coerceIn(0f, 1f)
                        val current = if (effect != "PULSE") widgetDiscRestAngle(angle, progress) else 1f + widgetDiscRestAngle(angle - 1f, progress)
                        coverSettling[id] = current
                        publishViews(id, partial = true, views = sizedViews(id) { layout -> RemoteViews(app.packageName, layout).apply {
                            setViewVisibility(R.id.widget_effect_disc, View.GONE)
                            setViewVisibility(R.id.widget_effect_pulse, View.GONE)
                            setImageViewBitmap(if (layout == R.layout.music_widget_minimal) R.id.widget_cover else R.id.widget_background, if (effect != "PULSE") widgetRotatedDisc(motionCover, current) else widgetScaledCover(motionCover, current))
                        } })
                        if (progress == 1f) break
                        delay(32)
                    } while (currentCoroutineContext().isActive)
                } finally { coverSettling.remove(id); coverFrames.remove(id, frame); refresh() }
            }
        }
    }
    private fun sizedViews(id: Int, disc: Boolean = false, view: (Int) -> RemoteViews): RemoteViews {
        val provider = manager.getAppWidgetInfo(id)?.provider
        val options = manager.getAppWidgetOptions(id)
        val circular = disc || settings.get(id).circularCover
        fun layout(size: android.util.SizeF?): Int {
            val compact = size?.let { musicWidgetCompact(it.width, it.height) } ?: musicWidgetCompact(options)
            val width = size?.width ?: options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).toFloat()
            return when {
                provider == ComponentName(app, MusicMinimalWidget::class.java) && (width <= 0 || width > 110) -> R.layout.music_widget_minimal
                compact -> R.layout.music_widget_tiny
                circular -> R.layout.music_widget_disc
                else -> R.layout.music_widget_artwork
            }
        }
        fun sizedView(size: android.util.SizeF?): RemoteViews {
            val layout = layout(size)
            return view(layout).apply {
                if (layout == R.layout.music_widget_minimal) {
                    val width = size?.width ?: options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).toFloat()
                    val height = size?.height ?: options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT).toFloat()
                    val narrow = width > 0 && width < 260
                    setViewVisibility(R.id.widget_previous, if (narrow) View.GONE else View.VISIBLE)
                    setViewVisibility(R.id.widget_shuffle, if (narrow) View.GONE else View.VISIBLE)
                    if (android.os.Build.VERSION.SDK_INT >= 31 && height > 0) setViewLayoutHeight(R.id.widget_root,
                        minOf(height, 76f), android.util.TypedValue.COMPLEX_UNIT_DIP)
                }
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            @Suppress("DEPRECATION") val sizes = options.getParcelableArrayList<android.util.SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
                .orEmpty().filter { it.width > 0 && it.height > 0 }.distinct()
            if (sizes.isNotEmpty() && sizes.size <= 16) return RemoteViews(sizes.associateWith(::sizedView))
        }
        return sizedView(null)
    }
    private val smoothedBands = FloatArray(6)
    private var targetBands = FloatArray(6)
    private var spectrumTime = 0L
    private var spectrumJob: Job? = null
    private val lastBars = mutableMapOf<Int, List<Int>>()
    private val barColors = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    private var lastBarAt = 0L
    /** One renderer consumes only the latest PCM sample, so host updates cannot queue up. */
    fun spectrum(bands: FloatArray, settling: Boolean = false) {
        if ((!audioAnalysisEnabled && !settling) || !app.getSystemService(android.os.PowerManager::class.java).isInteractive) return
        synchronized(smoothedBands) {
            targetBands = bands.copyOf(6)
            spectrumTime = SystemClock.elapsedRealtime()
            if (spectrumJob?.isActive == true) return
            spectrumJob = app.catalog.scope.launch {
                do {
                val active = audioAnalysisEnabled && playback.value?.playing == true && app.getSystemService(android.os.PowerManager::class.java).isInteractive
                val levels = synchronized(smoothedBands) {
                    val target = if (active && SystemClock.elapsedRealtime() - spectrumTime < 250) targetBands else FloatArray(6)
                    smoothedBands.indices.forEach { i ->
                        smoothedBands[i] = widgetBarEnvelope(smoothedBands[i], target[i])
                    }
                    smoothedBands.copyOf()
                }
                val low = musicPulseLevel(levels)
                pulseLevel.value = if (active) low.coerceIn(0f, 1f) else 0f
                val now = SystemClock.elapsedRealtime()
                val barsDue = now - lastBarAt >= 50 || !active
                if (barsDue) lastBarAt = now
                for ((id, config) in effectWidgets.filterValues { hostAnimationAllowed && it.effect == "BARS" }.takeIf { barsDue }.orEmpty()) {
                    val quantized = levels.map { (it * 64).toInt() }
                    if (lastBars[id] == quantized) continue
                    lastBars[id] = quantized
                    val ink = barColors[id] ?: GalleryThemes.get(app.preferences.state.value).accent.toArgb()
                    val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap).apply { scale(128f / 192f, 128f / 192f) }; val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; alpha = 200 }
                    repeat(18) { index ->
                        val sample = index / 17f * 5f
                        val band = sample.toInt()
                        val level = levels[band] + (levels[(band + 1).coerceAtMost(5)] - levels[band]) * (sample - band)
                        val left = 12f + index * 9.5f
                        canvas.drawRoundRect(RectF(left, 170f - level * 136f, left + 6f, 170f), 3f, 3f, paint)
                    }
                    if (owns(id) && settings.get(id).effect == "BARS")
                        publishViews(id, partial = true, views = sizedViews(id) { layout -> RemoteViews(app.packageName, layout).apply { setImageViewBitmap(R.id.widget_effect_bars, bitmap) } })
                }
                for (id in effectWidgets.filterValues { hostAnimationAllowed && it.effect == "PULSE" }.keys) {
                    val scale = if (active) widgetPulseScale(pulseLevel.value) else 1f
                    publishViews(id, partial = true, views = sizedViews(id) { layout -> RemoteViews(app.packageName, layout).apply {
                        setFloat(R.id.widget_effect_pulse, "setScaleX", scale)
                        setFloat(R.id.widget_effect_pulse, "setScaleY", scale)
                    } })
                }
                if (!active && levels.all { it == 0f }) break
                delay(50)
                } while (currentCoroutineContext().isActive)
            }
        }
    }
}

internal fun widgetBarEnvelope(current: Float, target: Float): Float {
    val next = target.coerceIn(0f, 1f)
    val value = current + (next - current) * if (next > current) .55f else .24f
    return if (value < .002f) 0f else value
}

internal fun musicPulseLevel(bands: FloatArray): Float = (bands.getOrElse(0) { 0f } * .38f +
    bands.getOrElse(1) { 0f } * .27f + bands.getOrElse(2) { 0f } * .18f +
    bands.getOrElse(3) { 0f } * .1f + bands.getOrElse(4) { 0f } * .05f +
    bands.getOrElse(5) { 0f } * .02f).coerceIn(0f, 1f)

internal fun widgetDiscRestAngle(angle: Float, progress: Float): Float = angle * (1f - progress.coerceIn(0f, 1f)).let { it * it * it }
internal fun widgetPulseScale(level: Float): Float = .70f + kotlin.math.sqrt(level.coerceIn(0f, 1f)) * .36f
private fun widgetScaledCover(cover: Bitmap, scale: Float): Bitmap = Bitmap.createBitmap(cover.width, cover.height, Bitmap.Config.ARGB_8888).apply {
    val canvas = Canvas(this)
    canvas.scale(scale, scale, width / 2f, height / 2f)
    canvas.drawBitmap(cover, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
}
private fun widgetRotatedDisc(cover: Bitmap, angle: Float): Bitmap = Bitmap.createBitmap(cover.width, cover.height, Bitmap.Config.ARGB_8888).apply {
    val canvas = Canvas(this)
    canvas.rotate(angle, width / 2f, height / 2f)
    canvas.drawBitmap(cover, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
}

internal fun musicWidgetViews(context: Context, id: Int, layout: Int, display: MusicWidgetDisplay,
    config: MusicWidgetConfig, prefs: GalleryPreferences, artwork: Bitmap?, starting: Boolean = false): RemoteViews {
    val theme = GalleryThemes.get(prefs)
    val themeMode = config.background == "THEME"
    val lightColor = config.color?.let { androidx.core.graphics.ColorUtils.calculateLuminance(it) > .5 } ?: false
    val coverBackground = layout == R.layout.music_widget_tiny || layout in setOf(R.layout.music_widget_artwork, R.layout.music_widget_disc)
    val paletteLight = config.background == "LIGHT" || config.background == "SYSTEM" && context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK != android.content.res.Configuration.UI_MODE_NIGHT_YES
    val ink = if (config.background in setOf("DARK", "LIGHT", "SYSTEM", "TRANSPARENT")) (if (paletteLight) Color.BLACK else Color.WHITE) else if (coverBackground) Color.WHITE else if (config.color != null) (if (lightColor) Color.BLACK else Color.WHITE) else if (themeMode) theme.colors.onSurface.toArgb() else Color.WHITE
    val muted = Color.argb(190, Color.red(ink), Color.green(ink), Color.blue(ink))
    val accent = if (themeMode) theme.accent.toArgb() else Color.rgb(178, 218, 255)
    val lightTheme = if (config.background in setOf("DARK", "LIGHT", "SYSTEM", "TRANSPARENT")) paletteLight else !coverBackground && if (config.color != null) lightColor else themeMode && !theme.dark
    val cover = if (config.circularCover) widgetDisc(context, artwork, accent, config.effect == "DISC" && config.discHole) else widgetCover(context, artwork, if (lightTheme) Color.WHITE else accent)
    val background = if (coverBackground) cover else when (config.background) {
        "TRANSPARENT" -> Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        "DARK", "LIGHT", "SYSTEM" -> widgetBackground(if (lightTheme) Color.rgb(248, 248, 250) else Color.rgb(20, 19, 24), true)
        else -> widgetBackground(config.color ?: if (themeMode) theme.background.toArgb() else artwork?.let(::widgetArtworkColor) ?: Color.rgb(33, 40, 65), themeMode || config.color != null)
    }
    val buttonBackground = if (coverBackground) (if (lightTheme) R.drawable.widget_button_overlay_light else R.drawable.widget_button_overlay) else if (lightTheme) R.drawable.widget_button_compact_light else R.drawable.widget_button_compact
    val open = PendingIntent.getActivity(context, id, Intent(context, MusicWidgetConfigActivity::class.java)
        .setData(Uri.parse("ayngallery-widget://$id/open")).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun command(action: String) = PendingIntent.getBroadcast(context, id, Intent(context, MusicWidgetControlReceiver::class.java)
        .setAction(MusicWidgetControlReceiver.ACTION).setData(Uri.parse("ayngallery-widget://$id/$action"))
        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).putExtra("command", action)
        .putExtra("media", display.mediaId), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    return RemoteViews(context.packageName, layout).apply {
        setImageViewBitmap(R.id.widget_background, background); setImageViewBitmap(R.id.widget_cover, cover)
        if (coverBackground) setInt(R.id.widget_header, "setBackgroundResource", if (lightTheme) R.drawable.widget_title_overlay_light else R.drawable.widget_title_overlay)
        if (config.background == "TRANSPARENT" && !coverBackground) setInt(R.id.widget_root, "setBackgroundResource", android.R.color.transparent)
        setTextViewText(R.id.widget_title, if (layout == R.layout.music_widget_minimal && display.artist.isNotBlank()) "${display.title} — ${display.artist}" else display.title); setTextColor(R.id.widget_title, ink)
        if (layout in setOf(R.layout.music_widget_square, R.layout.music_widget_artwork, R.layout.music_widget_disc)) {
            removeAllViews(R.id.widget_title_scroll)
            val metrics = context.resources.displayMetrics
            val textWidth = Paint().apply { textSize = 13f * metrics.scaledDensity }.measureText(display.title) + 16 * metrics.density
            val width = AppWidgetManager.getInstance(context).getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180)
            val headerControls = 64
            if (textWidth > (width - headerControls).coerceAtLeast(48) * metrics.density) {
                setTextColor(R.id.widget_title, Color.TRANSPARENT)
                addView(R.id.widget_title_scroll, RemoteViews(context.packageName, R.layout.music_widget_title_motion).apply {
                    for (view in listOf(R.id.widget_title_one, R.id.widget_title_two)) {
                        setTextViewText(view, display.title); setTextColor(view, ink)
                        if (android.os.Build.VERSION.SDK_INT >= 31) setViewLayoutWidth(view, textWidth, android.util.TypedValue.COMPLEX_UNIT_PX)
                    }
                })
                setOnClickPendingIntent(R.id.widget_title_scroll, open)
            }
        }
        val effect = if (display.playing && !prefs.gameMode) (if (config.rotatingCover) "DISC" else config.effect) else "NONE"
        setViewVisibility(R.id.widget_effect_disc, if (effect == "DISC") View.VISIBLE else View.GONE)
        setViewVisibility(R.id.widget_effect_pulse, if (effect == "PULSE") View.VISIBLE else View.GONE)
        setViewVisibility(R.id.widget_effect_bars, if (config.effect == "BARS" && !prefs.gameMode) View.VISIBLE else View.GONE)
        for ((container, motion, key) in listOf(Triple(R.id.widget_effect_disc, if (config.effect == "COIN") R.layout.music_widget_motion_coin else R.layout.music_widget_motion_disc, "DISC"), Triple(R.id.widget_effect_pulse, R.layout.music_widget_motion_pulse, "PULSE"))) {
            removeAllViews(container)
            if (effect == key) {
                val child = RemoteViews(context.packageName, motion).apply { setImageViewBitmap(R.id.widget_motion_one, cover) }
                if (android.os.Build.VERSION.SDK_INT >= 31) addStableView(container, child, (31 * AppWidgetManager.getInstance(context).getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) + AppWidgetManager.getInstance(context).getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)).coerceAtLeast(1)) else addView(container, child)
            }
        }
        if (effect in setOf("DISC", "PULSE")) {
            if (coverBackground) setImageViewBitmap(R.id.widget_background, null) else setImageViewBitmap(R.id.widget_cover, null)
        }
        // Square widgets open settings from the title; horizontal widgets use the artwork.
        setOnClickPendingIntent(R.id.widget_cover, null); setOnClickPendingIntent(R.id.widget_background, null)
        setOnClickPendingIntent(R.id.widget_root, null)
        setOnClickPendingIntent(R.id.widget_title, open)
        if (layout == R.layout.music_widget_minimal) setOnClickPendingIntent(R.id.widget_cover, open)
        if (layout in setOf(R.layout.music_widget_artwork, R.layout.music_widget_disc)) setOnClickPendingIntent(R.id.widget_header, open)
        fun glyph(view: Int, resource: Int) {
            val bitmap = widgetIcon(context, resource)
            val side = bitmap.width
            if (view == R.id.widget_shuffle && display.repeat == Player.REPEAT_MODE_ONE) {
                Canvas(bitmap).drawText("1", side / 2f, side * .64f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE; textSize = side * .42f; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
                })
            }
            setImageViewBitmap(view, bitmap)
        }
        glyph(R.id.widget_play, if (starting) R.drawable.ph_dots_three else if (display.playing) R.drawable.ph_pause else R.drawable.ph_play)
        glyph(R.id.widget_previous, R.drawable.ph_skip_back); glyph(R.id.widget_next, R.drawable.ph_skip_forward); glyph(R.id.widget_shuffle, if (display.repeat != Player.REPEAT_MODE_OFF) R.drawable.ph_arrows_clockwise else if (display.shuffle) R.drawable.ph_shuffle else R.drawable.ph_arrows_left_right)
        val buttons = listOf(R.id.widget_previous to "PREVIOUS", R.id.widget_play to "TOGGLE", R.id.widget_next to "NEXT")
        for ((view, action) in buttons) {
            setInt(view, "setBackgroundResource", buttonBackground)
            val enabled = !starting && when (action) { "PREVIOUS" -> display.previous; "NEXT" -> display.next; else -> true }
            setBoolean(view, "setEnabled", enabled); setInt(view, "setColorFilter", if (enabled) ink else muted)
            setInt(view, "setImageAlpha", if (enabled) 255 else 100)
            setContentDescription(view, when (action) { "PREVIOUS" -> localized(R.string.text_previous_track_b3f5fe, "Brano precedente"); "NEXT" -> localized(R.string.text_next_track_b0ff2f, "Brano successivo"); else -> if (display.playing) localized(R.string.text_pause_9bd4d1, "Pausa") else localized(R.string.text_play_da1576, "Riproduci") })
            setOnClickPendingIntent(view, command(action))
        }
        setViewVisibility(R.id.widget_shuffle, if (config.showShuffle || layout in setOf(R.layout.music_widget_minimal, R.layout.music_widget_square, R.layout.music_widget_artwork, R.layout.music_widget_disc)) View.VISIBLE else View.GONE)
        setInt(R.id.widget_shuffle, "setBackgroundResource", if (lightTheme) R.drawable.widget_mode_light else R.drawable.widget_mode_dark)
        setBoolean(R.id.widget_shuffle, "setEnabled", display.available && !starting)
        setInt(R.id.widget_shuffle, "setColorFilter", if (display.shuffle || display.repeat != Player.REPEAT_MODE_OFF) theme.accent.toArgb() else muted)
        setContentDescription(R.id.widget_shuffle, if (display.repeat == Player.REPEAT_MODE_ONE) localized(R.string.text_repeat_track_baa748, "Ripeti brano") else if (display.repeat == Player.REPEAT_MODE_ALL) localized(R.string.text_repeat_queue_7bf8e2, "Ripeti coda") else if (display.shuffle) localized(R.string.text_turn_shuffle_off_ff71f4, "Disattiva ordine casuale") else localized(R.string.text_turn_shuffle_on_39e948, "Attiva ordine casuale"))
        setOnClickPendingIntent(R.id.widget_shuffle, command("MODE"))
        setContentDescription(R.id.widget_cover, localized(R.string.text_settings_7b855c, "Impostazioni"))
        if (layout != R.layout.music_widget_minimal) {
            setTextViewText(R.id.widget_artist, display.artist); setTextColor(R.id.widget_artist, muted)
            setTextViewText(R.id.widget_status, display.status); setTextColor(R.id.widget_status, accent)
            setOnClickPendingIntent(R.id.widget_status, open)
            val progress = Bitmap.createBitmap(512, 8, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(progress)
            canvas.drawColor(Color.argb(45, Color.red(ink), Color.green(ink), Color.blue(ink)))
            val fraction = if (display.duration > 0) (display.position.toDouble() / display.duration).coerceIn(0.0, 1.0) else 0.0
            canvas.drawRect(0f, 0f, (512 * fraction).toFloat(), 8f, Paint().apply { color = accent })
            setImageViewBitmap(R.id.widget_progress, progress)
            setOnClickPendingIntent(R.id.widget_progress, open)
            setChronometer(R.id.widget_elapsed, SystemClock.elapsedRealtime() - display.position, null, display.playing)
            setTextColor(R.id.widget_elapsed, muted)
            setTextViewText(R.id.widget_duration, if (display.duration > 0) " / ${widgetTime(display.duration)}" else " / —")
            setTextColor(R.id.widget_duration, muted)
        } else setContentDescription(R.id.widget_title, "${display.title}. ${display.status}")
        if (layout == R.layout.music_widget_full) {
            setInt(R.id.widget_favorite, "setBackgroundResource", buttonBackground)
            setBoolean(R.id.widget_favorite, "setEnabled", display.available)
            setInt(R.id.widget_favorite, "setImageAlpha", if (display.available) 255 else 100)
            setInt(R.id.widget_favorite, "setColorFilter", if (display.favorite) accent else muted)
            setContentDescription(R.id.widget_favorite, if (display.favorite) localized(R.string.text_remove_from_favorites_e1e1ac, "Rimuovi dai Preferiti") else localized(R.string.text_add_to_favorites_ecc911, "Aggiungi ai Preferiti"))
            setOnClickPendingIntent(R.id.widget_favorite, command("FAVORITE"))
        }
        if (layout == R.layout.music_widget_tiny) {
            for (view in listOf(R.id.widget_title, R.id.widget_header, R.id.widget_previous, R.id.widget_play, R.id.widget_next, R.id.widget_shuffle)) setViewVisibility(view, View.GONE)
            setOnClickPendingIntent(R.id.widget_background, command("TOGGLE"))
            setOnClickPendingIntent(R.id.widget_root, command("TOGGLE"))
            setBoolean(R.id.widget_root, "setEnabled", display.available && !starting)
            setBoolean(R.id.widget_background, "setEnabled", display.available && !starting)
            setInt(R.id.widget_background, "setImageAlpha", if (starting) 150 else 255)
            setContentDescription(R.id.widget_root, listOf(display.title, localized(if (display.playing) R.string.text_pause_9bd4d1 else R.string.text_play_da1576, if (display.playing) "Pausa" else "Riproduci")).filter(String::isNotBlank).joinToString(". "))
        }
    }
}

internal fun musicWidgetCompact(options: Bundle): Boolean {
    return musicWidgetCompact(options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).toFloat(),
        options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT).toFloat())
}

internal fun musicWidgetCompact(width: Float, height: Float): Boolean =
    width > 0 && height > 0 && (width <= 110 || height <= 110)

private fun widgetIcon(context: Context, resource: Int): Bitmap {
    val side = (18 * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).apply { density = context.resources.displayMetrics.densityDpi }
    ContextCompat.getDrawable(context, resource)?.mutate()?.apply { setBounds(0, 0, side, side); draw(Canvas(bitmap)) }
    return bitmap
}

internal fun widgetDisc(context: Context, artwork: Bitmap?, accent: Int, hole: Boolean = true): Bitmap {
    val key = WidgetCoverKey(true, artwork, artwork?.generationId ?: 0, accent, hole)
    return widgetCoverCache.get(key) ?: renderWidgetDisc(context, artwork, accent, hole).also { widgetCoverCache.put(key, it) }
}
private fun renderWidgetDisc(context: Context, artwork: Bitmap?, accent: Int, hole: Boolean): Bitmap {
    val source = widgetCover(context, artwork, accent)
    return Bitmap.createBitmap(768, 768, Bitmap.Config.ARGB_8888).apply {
        val canvas = Canvas(this)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.save()
        canvas.clipPath(Path().apply { addCircle(384f, 384f, 354f, Path.Direction.CW) })
        canvas.drawBitmap(source, 0f, 0f, paint); canvas.restore()
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f
        paint.shader = SweepGradient(384f, 384f, intArrayOf(Color.TRANSPARENT, accent, Color.WHITE, Color.TRANSPARENT), null)
        canvas.drawCircle(384f, 384f, 363f, paint)
        paint.shader = null; paint.style = Paint.Style.FILL
        if (hole) {
            paint.color = Color.argb(180, 12, 12, 16); canvas.drawCircle(384f, 384f, 60f, paint)
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR); canvas.drawCircle(384f, 384f, 24f, paint)
        }
    }
}

internal fun widgetTime(ms: Long): String = "%d:%02d".format(ms / 60_000, ms / 1000 % 60)
internal fun widgetArtworkAccent(bitmap: Bitmap, fallback: Int): Int {
    val sample = Bitmap.createScaledBitmap(bitmap, 32, 32, true)
    return try {
        val pixels = IntArray(1024); sample.getPixels(pixels, 0, 32, 0, 0, 32, 32)
        val palette = musicArtworkColors(pixels, MusicColors(Color.BLACK, fallback))
        if (androidx.core.graphics.ColorUtils.calculateContrast(palette.accent, palette.background) >= 3.0) palette.accent
        else androidx.core.graphics.ColorUtils.blendARGB(palette.accent,
            if (androidx.core.graphics.ColorUtils.calculateLuminance(palette.background) > .45) Color.BLACK else Color.WHITE, .65f)
    } finally { if (sample !== bitmap) sample.recycle() }
}
internal fun widgetArtworkColor(bitmap: Bitmap): Int {
    var red = 0L; var green = 0L; var blue = 0L; var count = 0
    for (y in 0 until bitmap.height step (bitmap.height / 8).coerceAtLeast(1)) for (x in 0 until bitmap.width step (bitmap.width / 8).coerceAtLeast(1)) {
        val pixel = bitmap.getPixel(x, y); if (Color.alpha(pixel) < 64) continue
        red += Color.red(pixel); green += Color.green(pixel); blue += Color.blue(pixel); count++
    }
    return if (count == 0) Color.rgb(33, 40, 65) else Color.rgb((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
}
internal fun widgetBackground(color: Int, themeMode: Boolean): Bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
    val base = if (themeMode) color else Color.rgb(Color.red(color) / 4, Color.green(color) / 4, Color.blue(color) / 4)
    val end = if (themeMode) color else Color.rgb(Color.red(color) / 9, Color.green(color) / 9, Color.blue(color) / 9)
    // A rounded bitmap also works on pre-31 widget hosts, which cannot apply clipToOutline remotely.
    Canvas(this).drawRoundRect(RectF(0f, 0f, 64f, 64f), 6f, 6f, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(0f, 0f, 64f, 64f, base, end, Shader.TileMode.CLAMP) })
}
private data class WidgetCoverKey(val disc: Boolean, val artwork: Bitmap?, val generation: Int, val accent: Int, val hole: Boolean)
private val widgetCoverCache = object : android.util.LruCache<WidgetCoverKey, Bitmap>(8 * 1024 * 1024) {
    override fun sizeOf(key: WidgetCoverKey, value: Bitmap) = value.allocationByteCount
}
internal fun widgetCover(context: Context, artwork: Bitmap?, accent: Int): Bitmap {
    val key = WidgetCoverKey(false, artwork, artwork?.generationId ?: 0, accent, false)
    return widgetCoverCache.get(key) ?: renderWidgetCover(context, artwork, accent).also { widgetCoverCache.put(key, it) }
}
private fun renderWidgetCover(context: Context, artwork: Bitmap?, accent: Int): Bitmap = Bitmap.createBitmap(768, 768, Bitmap.Config.ARGB_8888).apply {
    val canvas = Canvas(this); val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    canvas.clipPath(Path().apply { addRoundRect(RectF(0f, 0f, 768f, 768f), 108f, 108f, Path.Direction.CW) })
    if (artwork != null) {
        val side = minOf(artwork.width, artwork.height); val x = (artwork.width - side) / 2; val y = (artwork.height - side) / 2
        canvas.drawBitmap(artwork, Rect(x, y, x + side, y + side), RectF(0f, 0f, 768f, 768f), paint)
    } else {
        paint.shader = LinearGradient(0f, 0f, 768f, 768f, Color.rgb(37, 49, 76), Color.rgb(68, 49, 78), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, 768f, 768f, paint); paint.shader = null; paint.color = Color.argb(36, 255, 255, 255); paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f
        canvas.drawCircle(384f, 384f, 312f, paint); canvas.drawCircle(384f, 384f, 258f, paint)
        ContextCompat.getDrawable(context, R.drawable.ph_music_notes)?.mutate()?.apply { setTint(accent); setBounds(240, 240, 528, 528); draw(canvas) }
    }
}

abstract class MusicWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = update(context, ids.toList())
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = update(context, listOf(id))
    private fun update(context: Context, ids: List<Int>) {
        val app = context.applicationContext as GalleryApp
        val pending = goAsync() // Keep a cold process alive until the initial RemoteViews have actually been published.
        app.catalog.scope.launch {
            try { withTimeout(8000) { app.widgets.update(ids) } }
            catch (cancelled: CancellationException) { if (cancelled !is TimeoutCancellationException) throw cancelled }
            catch (_: Exception) { app.widgets.refresh() }
            finally { pending?.finish() }
        }
    }
    override fun onDeleted(context: Context, ids: IntArray) { (context.applicationContext as GalleryApp).widgets.let { widgets -> ids.forEach { widgets.settings.remove(it); widgets.forgetCover(it) }; widgets.refresh() } }
}
class MusicFullWidget : MusicWidgetProvider()
class MusicMinimalWidget : MusicWidgetProvider()

class MusicWidgetControlReceiver : BroadcastReceiver() {
    companion object { const val ACTION = "it.ayngallery.WIDGET_CONTROL" }
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val app = context.applicationContext as GalleryApp
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val action = intent.getStringExtra("command") ?: return
        if (!app.widgets.owns(id) || action !in setOf("TOGGLE", "PREVIOUS", "NEXT", "FAVORITE", "SHUFFLE", "MODE")) return
        val pending = goAsync()
        app.catalog.scope.launch {
            try {
                val config = app.widgets.settings.get(id)
                withTimeout(6000) { app.music.widgetCommand(action, intent.getStringExtra("media"), config.playlist, config.initialTrack) }
                app.widgets.settings.error(id, null)
            } catch (cancelled: CancellationException) {
                if (cancelled is TimeoutCancellationException) app.widgets.settings.error(id, localized(R.string.text_player_unreachable_tap_again_to_retry_61e14c, "Player non raggiungibile · ritocca per riprovare")) else throw cancelled
            } catch (_: Exception) { app.widgets.settings.error(id, localized(R.string.text_command_failed_open_the_player_6a5bff, "Comando non riuscito · apri il player")) }
            finally {
                app.widgets.refresh()
                try { withTimeout(2000) { app.widgets.update(listOf(id)) } }
                catch (cancelled: CancellationException) { if (cancelled !is TimeoutCancellationException) throw cancelled }
                catch (_: Exception) { /* The next service/provider event retries the refresh. */ }
                finally { pending?.finish() }
            }
        }
    }
}
