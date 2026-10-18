package it.ayngallery

import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class UiSoundPreview(val cue: String, val duration: Long, val samples: List<Float>, val startedAt: Long = 0)

/** Short UI cues only; the existing music player remains the sole media player. */
class UiSounds(private val app: GalleryApp) {
    companion object { val CUES = setOf("navigate", "notice", "delete", "favorite", "settings", "settings-navigation", "save", "restore", "unlock", "unlock-error", "reorder", "pin-digit-1", "pin-digit-2", "pin-digit-3", "preview-pin", "preview-unpin", "comparison-first", "comparison-second", "comparison-close", "network-transfer", "zoom-out", "zoom-in", "pin-digit-4", "video-replace", "section") }
    private val pool = SoundPool.Builder().setMaxStreams(2).setAudioAttributes(AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
    private val ready = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    private val defaults: Map<String, Int>
    private val ids: MutableMap<String, Int>
    val customCues = MutableStateFlow<Set<String>>(emptySet())
    private val customData = mutableMapOf<String, CustomUiSound>()
    private val imports = Mutex()
    private var closed = false
    private fun customFile(cue: String): java.io.File {
        require(cue in CUES)
        return java.io.File(app.filesDir, "ui-sounds/$cue.wav")
    }
    private val streams = mutableMapOf<String, Int>()
    private val last = mutableMapOf<String, Long>()
    private var previousNavigation: String? = null
    private var navigationSection: String? = null
    private val observer: kotlinx.coroutines.Job
    val preview = MutableStateFlow<UiSoundPreview?>(null)
    private var previewJob: kotlinx.coroutines.Job? = null
    private var previewStream = 0
    private var previewGeneration = 0L
    private var lastPreview = -1000L
    private val mediaPlaying get() = app.music.state.value.playing || app.video.playing.value
    private val waveforms by lazy {
        org.json.JSONObject(app.resources.openRawResource(R.raw.ui_sound_waveforms).bufferedReader().use { it.readText() })
    }
    init {
        pool.setOnLoadCompleteListener { _, id, status -> if (status == 0) ready.add(id) }
        defaults = mapOf("navigate" to pool.load(app, R.raw.ui_navigate, 1), "notice" to pool.load(app, R.raw.ui_notice, 1),
            "delete" to pool.load(app, R.raw.ui_delete, 1), "favorite" to pool.load(app, R.raw.ui_favorite, 1),
            "settings" to pool.load(app, R.raw.ui_settings, 1), "settings-navigation" to pool.load(app, R.raw.ui_settings_navigation, 1),
            "save" to pool.load(app, R.raw.ui_save, 1), "restore" to pool.load(app, R.raw.ui_restore, 1),
            "unlock" to pool.load(app, R.raw.ui_unlock, 1), "unlock-error" to pool.load(app, R.raw.ui_unlock_error, 1),
            "reorder" to pool.load(app, R.raw.ui_reorder, 1),
            "pin-digit-1" to pool.load(app, R.raw.ui_pin_digit_1, 1), "pin-digit-2" to pool.load(app, R.raw.ui_pin_digit_2, 1),
            "pin-digit-3" to pool.load(app, R.raw.ui_pin_digit_3, 1),
            "pin-digit-4" to pool.load(app, R.raw.ui_pin_digit_4, 1),
            "video-replace" to pool.load(app, R.raw.ui_video_replace, 1),
            "tutorial-open" to pool.load(app, R.raw.ui_restore, 1),
            "tutorial-next-1" to pool.load(app, R.raw.ui_tutorial_next_1, 1),
            "tutorial-next-2" to pool.load(app, R.raw.ui_tutorial_next_2, 1),
            "tutorial-previous" to pool.load(app, R.raw.ui_tutorial_previous, 1),
            "tutorial-complete" to pool.load(app, R.raw.ui_restore, 1),
            "section" to pool.load(app, R.raw.ui_section, 1),
            "tutorial-skip" to pool.load(app, R.raw.ui_tutorial_skip, 1),
            "preview-pin" to pool.load(app, R.raw.ui_preview_pin, 1),
            "preview-unpin" to pool.load(app, R.raw.ui_preview_unpin, 1),
            "comparison-second" to pool.load(app, R.raw.ui_comparison_second, 1),
            "comparison-close" to pool.load(app, R.raw.ui_comparison_close, 1),
            "zoom-out" to pool.load(app, R.raw.ui_zoom_out, 1),
            "zoom-in" to pool.load(app, R.raw.ui_zoom_in, 1),
            "comparison-first" to pool.load(app, R.raw.ui_settings_navigation, 1),
            "network-transfer" to pool.load(app, R.raw.ui_save, 1))
        ids = defaults.toMutableMap()
        app.catalog.scope.launch(Dispatchers.IO) {
            imports.withLock {
                CUES.forEach { cue ->
                    val file = customFile(cue)
                    if (file.exists() || java.io.File(file.path + ".bak").exists()) runCatching {
                        val sound = android.util.AtomicFile(file).openRead().use(CustomUiSound::read)
                        loadCustom(cue, sound, persist = false)
                    }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                }
            }
        }
        observer = app.catalog.scope.launch {
            combine(app.preferences.state, app.music.state, app.video.playing) { prefs, music, video -> (prefs.uiSounds && !music.playing && !video) to prefs.disabledSounds }
                .distinctUntilChanged().collect { (enabled, disabled) -> if (!enabled) mute() else muteDisabled(disabled) }
        }
    }
    suspend fun import(cue: String, uri: android.net.Uri) = withContext(Dispatchers.IO) { imports.withLock {
        require(cue in CUES)
        val sound = app.contentResolver.openInputStream(uri)?.use(CustomUiSound::read)
            ?: error(localized(R.string.sound_custom_invalid, "Scegli un file WAV valido, lungo al massimo 6 secondi."))
        loadCustom(cue, sound, persist = true)
    } }
    private suspend fun loadCustom(cue: String, sound: CustomUiSound, persist: Boolean) {
        val file = customFile(cue)
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val temporary = java.io.File.createTempFile("cue-", ".wav", file.parentFile)
        var id = 0
        var published = false
        try {
            temporary.writeBytes(sound.wav)
            synchronized(this) { check(!closed); id = pool.load(temporary.path, 1) }
            val loaded = kotlinx.coroutines.withTimeoutOrNull(3000) { while (id !in ready) kotlinx.coroutines.delay(16); true } == true
            check(loaded) { localized(R.string.sound_custom_load_failed, "Impossibile caricare il suono. Quello precedente è stato conservato.") }
            withContext(kotlinx.coroutines.NonCancellable) {
                synchronized(this@UiSounds) {
                    check(!closed)
                    if (persist) {
                        val atomic = android.util.AtomicFile(file)
                        val stream = atomic.startWrite()
                        try { stream.write(sound.wav); atomic.finishWrite(stream) }
                        catch (failure: Throwable) { atomic.failWrite(stream); throw failure }
                    }
                    stopPreview()
                    streams.remove(cue)?.let(pool::stop)
                    val previous = ids.put(cue, id)
                    if (previous != defaults[cue]) previous?.let { pool.unload(it); ready.remove(it) }
                    customData[cue] = sound
                    customCues.value = customData.keys.toSet()
                    published = true
                }
            }
        } finally { if (!published && id != 0) synchronized(this) { if (!closed) { pool.unload(id); ready.remove(id) } }; temporary.delete() }
    }
    suspend fun reset(cue: String) = withContext(Dispatchers.IO) { imports.withLock {
        synchronized(this@UiSounds) {
            check(!closed)
            val file = customFile(cue)
            android.util.AtomicFile(file).delete()
            check(!file.exists() && !java.io.File(file.path + ".bak").exists())
            stopPreview(); streams.remove(cue)?.let(pool::stop)
            ids.put(cue, defaults.getValue(cue))?.takeIf { it != defaults[cue] }?.let { pool.unload(it); ready.remove(it) }
            customData.remove(cue); customCues.value = customData.keys.toSet()
        }
    } }
    suspend fun awaitReady(cue: String): Boolean {
        val id = synchronized(this) { ids[cue] } ?: return false
        return kotlinx.coroutines.withTimeoutOrNull(800) { while (id !in ready) kotlinx.coroutines.delay(16); true } == true
    }
    fun navigation(key: String) {
        val route = app.session.libraryModel()?.route?.value
        val section = "${route?.tab}/${route?.album}"
        val previous = previousNavigation.takeIf { navigationSection == section }
        navigationSection = section
        previousNavigation = key
        if (previous != null && previous != key) play(if (app.session.settingsOwner != null || app.session.actionModalOpen) "settings-navigation" else "navigate")
    }
    @Synchronized fun play(cue: String) {
        if (cue == "section") { streams.remove("navigate")?.let(pool::stop); previousNavigation = null; navigationSection = null }
        if (preview.value != null) return
        if (!app.preferences.state.value.uiSounds || cue in app.preferences.state.value.disabledSounds || mediaPlaying) return
        val id = ids[cue]?.takeIf { it in ready } ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - (last[cue] ?: -1000L) < if (cue == "navigate") 65L else 150L) return
        last[cue] = now
        streams.remove(cue)?.let(pool::stop)
        streams[cue] = pool.play(id, .65f, .65f, 1, 0, 1f)
    }
    @Synchronized private fun muteDisabled(disabled: Set<String>) { disabled.forEach { streams.remove(it)?.let(pool::stop) } }
    @Synchronized fun preview(cue: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (cue !in CUES || preview.value != null || mediaPlaying || now - lastPreview < 250) return false
        val state = customData[cue]?.let { UiSoundPreview(cue, it.duration, it.samples) } ?: run {
            val waveform = waveforms.getJSONObject(cue)
            val points = waveform.getJSONArray("samples")
            UiSoundPreview(cue, waveform.getLong("duration").coerceIn(1, 6000), List(points.length()) { points.getDouble(it).toFloat() })
        }
        streams.values.forEach(pool::stop); streams.clear()
        lastPreview = now
        val generation = ++previewGeneration
        preview.value = state
        previewJob = app.catalog.scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) {
            try {
                val id = ids.getValue(cue)
                kotlinx.coroutines.withTimeoutOrNull(1000) { while (id !in ready) kotlinx.coroutines.delay(16) }
                if (generation != previewGeneration || id !in ready || mediaPlaying) return@launch
                previewStream = pool.play(id, .65f, .65f, 1, 0, 1f)
                if (previewStream == 0) return@launch
                preview.value = state.copy(startedAt = SystemClock.elapsedRealtime())
                kotlinx.coroutines.delay(state.duration + 30)
            } finally { if (generation == previewGeneration) stopPreview() }
        }
        return true
    }
    @Synchronized fun stopPreview() {
        previewGeneration++
        previewJob?.cancel(); previewJob = null
        if (previewStream != 0) pool.stop(previewStream)
        previewStream = 0; preview.value = null
    }
    @Synchronized fun mute() { stopPreview(); streams.values.forEach(pool::stop); streams.clear() }
    @Synchronized fun close() { if (closed) return; closed = true; observer.cancel(); mute(); pool.release() }
}

internal fun uiToast(context: android.content.Context, message: CharSequence, duration: Int): android.widget.Toast {
    (context.applicationContext as? GalleryApp)?.sounds?.play("notice")
    val target = (context.applicationContext as? GalleryApp)?.let { runCatching { it.session.toastContext() }.getOrNull() } ?: context
    return android.widget.Toast.makeText(target, message, duration)
}
