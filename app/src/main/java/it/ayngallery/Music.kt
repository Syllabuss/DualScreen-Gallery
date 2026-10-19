package it.ayngallery

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.media.AudioManager
import androidx.core.content.ContextCompat
import android.os.Looper
import android.os.Bundle
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.*
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val AUDIO_PREVIEW_START = "it.ayngallery.AUDIO_PREVIEW_START"
private const val AUDIO_PREVIEW_END = "it.ayngallery.AUDIO_PREVIEW_END"
private const val RECEIVED_AUDIO_START = "it.ayngallery.RECEIVED_AUDIO_START"

data class PlaybackState(
    val id: String? = null,
    val title: String = localized(R.string.text_no_tracks_queued_156b61, "Nessun brano in coda"),
    val artist: String = localized(R.string.text_choose_a_music_folder_2a9069, "Scegli una cartella musicale"),
    val artwork: String? = null,
    val playing: Boolean = false,
    val position: Long = 0,
    val duration: Long = 0,
    val queue: List<String> = emptyList(),
    val index: Int = 0,
    val shuffle: Boolean = false,
    val repeat: Int = Player.REPEAT_MODE_OFF,
    val error: String? = null,
    val hasNext: Boolean? = null,
    val hasPrevious: Boolean? = null,
    val playRequested: Boolean = playing,
)

private fun Player.playbackSnapshot(queue: List<String>, error: String?, playRequested: Boolean = isPlaying) = PlaybackState(
    id = currentMediaItem?.mediaId,
    title = mediaMetadata.title?.toString() ?: localized(R.string.text_no_tracks_queued_156b61, "Nessun brano in coda"),
    artist = mediaMetadata.artist?.toString() ?: localized(R.string.text_artist_unavailable_c612a4, "Artista non disponibile"),
    artwork = mediaMetadata.artworkUri?.toString(),
    playing = isPlaying,
    position = currentPosition.coerceAtLeast(0),
    duration = duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0,
    queue = queue,
    index = currentMediaItemIndex.coerceAtLeast(0),
    shuffle = shuffleModeEnabled,
    repeat = repeatMode,
    error = error,
    hasNext = hasNextMediaItem(),
    hasPrevious = hasPreviousMediaItem(),
    playRequested = playRequested,
)

fun Catalog.musicItem(item: MediaEntity, data: CatalogSnapshot): MediaItem = MediaItem.Builder()
    .setMediaId(item.id).setUri(uri(item, data)).setMediaMetadata(MediaMetadata.Builder()
        .setTitle(item.title?.takeIf { it.isNotBlank() } ?: item.name)
        .setArtist(item.artist?.takeIf { it.isNotBlank() } ?: localized(R.string.text_artist_unavailable_c612a4, "Artista non disponibile"))
        .setAlbumTitle(item.musicAlbum).setArtworkUri(artwork(item, data)?.let(android.net.Uri::parse)).build()).build()

@androidx.annotation.OptIn(UnstableApi::class)
internal fun musicExtractors() = androidx.media3.extractor.DefaultExtractorsFactory()
    .setMp3ExtractorFlags(androidx.media3.extractor.mp3.Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING)

@androidx.annotation.OptIn(UnstableApi::class)
class MusicCoordinator(private val app: GalleryApp) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller: MediaController? = null
    private var future: ListenableFuture<MediaController>? = null
    val state = MutableStateFlow(PlaybackState())
    internal val volume = MutableStateFlow(1f)
    internal val previewToken = MutableStateFlow<String?>(null)
    private var userRevision = 0L
    internal val videoRevision get() = userRevision
    private var videoPause: Pair<String, Long>? = null
    private var pendingVideoResume: Pair<String, Long>? = null
    // Only the service can perform an automatic pause without classifying it as a user transport command.
    internal var pauseAutomaticallyForVideo: ((String?) -> String?)? = null
    private var closed = false
    private var widgetStarting = false
    private var publishedQueue = emptyList<String>()
    internal var soundSettingsPlayer: Player? = null
    internal fun launcherBackground(background: Boolean) {
        // Launchers with their own soundtrack must not stop the foreground music service.
        (soundSettingsPlayer as? ExoPlayer)?.let { it.setAudioAttributes(it.audioAttributes, !background) }
    }
    private var soundFade: Job? = null
    private var soundPause: Triple<Pair<String, Long>, Player, Float>? = null
    private fun cancelSoundFade() {
        soundFade?.cancel(); soundFade = null
        soundPause?.let { (_, player, volume) -> if (soundSettingsPlayer === player) player.volume = volume }
    }
    internal fun pauseForSoundSettings(): Pair<String, Long>? {
        cancelSoundFade()
        val player = soundSettingsPlayer ?: return null
        val id = player.currentMediaItem?.mediaId ?: return null
        if (!player.playWhenReady || player.playerError != null) return null
        val ticket = id to userRevision
        val volume = player.volume
        soundPause = Triple(ticket, player, volume)
        soundFade = scope.launch {
            for (step in 1..20) {
                delay(20)
                if (userRevision != ticket.second || player.currentMediaItem?.mediaId != id) {
                    if (soundSettingsPlayer === player) player.volume = volume
                    return@launch
                }
                player.volume = volume * (1f - step / 20f)
            }
            pauseAutomaticallyForVideo?.invoke(null)
            player.volume = volume
            publish()
        }
        return ticket
    }
    internal fun resumeAfterSoundSettings(ticket: Pair<String, Long>?) {
        if (ticket == null) return
        val saved = soundPause?.takeIf { it.first == ticket } ?: return
        soundFade?.cancel()
        val (_, player, volume) = saved
        if (soundSettingsPlayer !== player || userRevision != ticket.second || player.currentMediaItem?.mediaId != ticket.first ||
            player.playerError != null || app.video.player?.playWhenReady == true) { cancelSoundFade(); soundPause = null; return }
        val start = if (player.playWhenReady) player.volume else 0f
        player.volume = start; player.play()
        soundFade = scope.launch {
            try {
                for (step in 1..20) { delay(20); player.volume = start + (volume - start) * step / 20f }
            } finally { if (soundSettingsPlayer === player) player.volume = volume; soundPause = null }
        }
    }
    fun observe() { scope.launch { try { connect() } catch (error: Exception) { state.value = state.value.copy(error = localized(R.string.text_could_not_connect_to_player_85f1ae, "Connessione al player non riuscita")) } } }
    internal suspend fun widgetCommand(action: String, mediaId: String?, playlist: String? = null, initialTrack: String? = null) = withContext(Dispatchers.Main.immediate) {
        if (widgetStarting) return@withContext
        var starting = action == "TOGGLE" && !state.value.playing
        if (starting) { widgetStarting = true; state.value = state.value.copy(error = null); app.widgets.starting(true) }
        try {
        connect(); publish()
        if (starting && state.value.playing) { widgetStarting = false; app.widgets.starting(false); starting = false }
        val savedQueue = app.getSharedPreferences("music", Context.MODE_PRIVATE).getString("queue", "[]")
        if (action in setOf("TOGGLE", "SHUFFLE", "MODE") && state.value.id == null && savedQueue in setOf(null, "[]")) {
            app.albums.show(-2, GalleryRoute(tab = "MUSIC"))
            try { app.albums.refreshAndWait() } finally { app.albums.leave(-2) }
        }
        val result = serviceCommand("it.ayngallery.WIDGET_COMMAND",
            Bundle().apply { putString("command", action); putString("media", mediaId); putString("playlist", playlist); putString("initialTrack", initialTrack) })
        check(result.resultCode == SessionResult.RESULT_SUCCESS) { localized(R.string.text_widget_command_refused_1_s_0c9fdb, "Comando del widget rifiutato (%1\$s)", result.resultCode) }
        publish()
        if (starting && state.value.id != null) {
            withTimeout(5000) { state.first { it.playing || it.error != null } }
            check(state.value.error == null) { state.value.error.orEmpty() }
        }
        } finally {
            if (starting) { widgetStarting = false; app.widgets.starting(false) }
        }
    }
    private suspend fun serviceCommand(action: String, args: Bundle): SessionResult {
        val player = connect()
        val future = player.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args)
        return suspendCancellableCoroutine { continuation ->
            future.addListener({
                try { if (continuation.isActive) continuation.resume(future.get()) }
                catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
            }, app.mainExecutor)
            continuation.invokeOnCancellation { future.cancel(false) }
        }
    }
    suspend fun preview(target: ExternalTarget, recipe: AudioRecipe, token: String) = withContext(Dispatchers.Main.immediate) {
        app.video.stop(app.video.owner); invalidateVideoResume()
        val revision = userRevision
        try {
            val response = serviceCommand(AUDIO_PREVIEW_START, Bundle().apply {
                putString("target", ExternalRequest(ExternalAction.OPEN, listOf(target)).encode()); putString("recipe", recipe.encode())
                putString("token", token); putLong("revision", revision)
            })
            check(response.resultCode == SessionResult.RESULT_SUCCESS) { response.extras.getString("error") ?: localized(R.string.text_audio_preview_unavailable_f44c32, "Anteprima audio non disponibile") }
            state.value = state.value.copy(error = null)
            publish()
        } catch (cancelled: CancellationException) { finishPreview(token); throw cancelled }
    }
    suspend fun previewReceived(target: IncomingFile, token: String) = withContext(Dispatchers.Main.immediate) {
        require(target.kind == "AUDIO")
        if (!app.video.floating || !app.video.keepsPlaying) app.video.stop(app.video.owner)
        invalidateVideoResume()
        val revision = userRevision
        try {
            val response = serviceCommand(RECEIVED_AUDIO_START, Bundle().apply {
                putString("file", target.encode()); putString("token", token); putLong("revision", revision)
            })
            check(response.resultCode == SessionResult.RESULT_SUCCESS) { response.extras.getString("error") ?: localized(R.string.text_received_track_unavailable_c84466, "Brano ricevuto non disponibile") }
            state.value = state.value.copy(error = null); publish()
        } catch (cancelled: CancellationException) { finishPreview(token); throw cancelled }
    }
    fun finishPreview(token: String) {
        if (previewToken.value == token) invalidateVideoResume()
        scope.launch {
            try { endPreview(token); if (previewToken.value == null) state.value = state.value.copy(error = null); publish() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { state.value = state.value.copy(error = localized(R.string.text_queue_not_restored_try_stopping_the_preview_agai_c21f6d, "Coda non ripristinata: riprova a fermare l'anteprima")) }
        }
    }
    private suspend fun endPreview(token: String?) {
        val response = serviceCommand(AUDIO_PREVIEW_END, Bundle().apply { putString("token", token) })
        check(response.resultCode == SessionResult.RESULT_SUCCESS) { localized(R.string.text_queue_not_restored_443da6, "Coda non ripristinata") }
    }

    private suspend fun connect(): MediaController {
        check(!closed) { localized(R.string.text_player_connection_closed_58b7ea, "Connessione al player chiusa") }
        controller?.let { return it }
        val pending = future ?: MediaController.Builder(app, SessionToken(app, ComponentName(app, MusicService::class.java)))
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(disconnected: MediaController) {
                    if (controller === disconnected) {
                        controller = null; future = null
                        state.value = state.value.copy(playing = false, playRequested = false, error = localized(R.string.text_player_disconnected_try_again_7b801f, "Player disconnesso · riprova"))
                    }
                }
            })
            .buildAsync().also { future = it }
        return suspendCancellableCoroutine { continuation ->
            pending.addListener({
                try {
                    val result = pending.get()
                    if (closed) { result.release(); return@addListener }
                    if (controller == null) {
                        controller = result
                        result.addListener(object : Player.Listener {
                            override fun onEvents(player: Player, events: Player.Events) {
                                if (events.contains(Player.EVENT_TIMELINE_CHANGED)) publishedQueue = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
                                publish()
                            }
                            override fun onPlayerError(error: PlaybackException) {
                                invalidateVideoResume()
                                state.value = state.value.copy(error = localized(R.string.text_playback_failed_1_s_b5e391, "Riproduzione non riuscita: %1\$s", error.errorCodeName))
                            }
                        })
                        publishedQueue = (0 until result.mediaItemCount).map { result.getMediaItemAt(it).mediaId }
                        scope.launch { while (isActive && controller === result) { publish(); delay(if (previewToken.value != null) 160 else 750) } }
                        publish()
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (error: Exception) {
                    future = null
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }, app.mainExecutor)
        }
    }

    private fun publish() {
        val player = controller ?: return
        if (publishedQueue.size != player.mediaItemCount || publishedQueue.getOrNull(player.currentMediaItemIndex) != player.currentMediaItem?.mediaId) {
            publishedQueue = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        }
        volume.value = player.volume
        state.value = player.playbackSnapshot(
            queue = publishedQueue,
            error = state.value.error,
            playRequested = player.playWhenReady && player.playbackState != Player.STATE_ENDED && player.playerError == null,
        )
    }

    private fun command(action: suspend (MediaController) -> Unit) {
        cancelSoundFade(); soundPause = null
        userRevision++
        videoPause = null
        scope.launch {
            try { action(connect()); publish() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { state.value = state.value.copy(error = error.message ?: localized(R.string.text_player_unavailable_try_again_8ee4a5, "Player non disponibile: riprova")) }
        }
    }

    fun play(id: String, ids: List<String>, autoPlay: Boolean = true) = command { player ->
        if (!autoPlay && player.mediaItemCount > 0) return@command
        if (previewToken.value != null) endPreview(previewToken.value)
        val data = withContext(Dispatchers.IO) { app.catalog.readSnapshot() }
        val audio = ids.distinct().mapNotNull(data.byId::get).filter { it.kind == "AUDIO" && data.usable(it) }
        val index = audio.indexOfFirst { it.id == id }
        require(index >= 0) { localized(R.string.text_track_unavailable_b73d64, "Brano non disponibile") }
        player.setMediaItems(audio.map { app.catalog.musicItem(it, data) }, index, 0)
        player.prepare()
        if (autoPlay) player.play() else player.pause()
        state.value = state.value.copy(error = null)
    }
    fun toggle() = command { if (it.playWhenReady) it.pause() else { require(it.mediaItemCount > 0) { localized(R.string.text_empty_queue_39af79, "Coda vuota") }; it.prepare(); it.play() } }
    fun pause() = command { it.pause() }
    fun next() = command { if (it.hasNextMediaItem()) it.seekToNextMediaItem() }
    fun previous() = command { it.seekToPrevious() }
    fun setVolume(value: Float) = command { it.volume = value.coerceIn(0f, 1f) }
    fun seek(position: Long) = command { it.seekTo(position.coerceAtLeast(0)) }
    /** Seek within an editor's clipped preview without treating its transport as a user queue change. */
    fun seekPreview(position: Long) {
        if (previewToken.value == null) return
        scope.launch {
            try { connect().seekTo(position.coerceAtLeast(0)); publish() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { state.value = state.value.copy(error = error.message ?: localized(R.string.text_player_unavailable_try_again_8ee4a5, "Player non disponibile: riprova")) }
        }
    }
    fun shuffle() = command { require(previewToken.value == null) { localized(R.string.text_stop_preview_before_changing_order_82ff53, "Ferma l'anteprima prima di cambiare l'ordine") }; it.repeatMode = Player.REPEAT_MODE_OFF; it.shuffleModeEnabled = !it.shuffleModeEnabled }
    fun cyclePlaybackMode() = command {
        if (previewToken.value == null) {
            when {
                it.shuffleModeEnabled -> { it.shuffleModeEnabled = false; it.repeatMode = Player.REPEAT_MODE_ONE }
                it.repeatMode != Player.REPEAT_MODE_OFF -> it.repeatMode = Player.REPEAT_MODE_OFF
                else -> { it.repeatMode = Player.REPEAT_MODE_OFF; it.shuffleModeEnabled = true }
            }
        }
    }
    fun repeat() = command { require(previewToken.value == null) { localized(R.string.text_stop_preview_before_changing_repeat_65687f, "Ferma l'anteprima prima di cambiare la ripetizione") }; it.shuffleModeEnabled = false; it.repeatMode = if (it.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE }
    fun enqueue(id: String, next: Boolean = false) = command { player ->
        if (previewToken.value != null) endPreview(previewToken.value)
        val data = withContext(Dispatchers.IO) { app.catalog.readSnapshot() }
        val item = data.byId[id]?.takeIf { it.kind == "AUDIO" && data.usable(it) } ?: error(localized(R.string.text_track_unavailable_b73d64, "Brano non disponibile"))
        val media = app.catalog.musicItem(item, data)
        if (next && player.mediaItemCount > 0) player.addMediaItem(player.currentMediaItemIndex + 1, media) else player.addMediaItem(media)
    }
    fun remove(index: Int) = command { require(previewToken.value == null) { localized(R.string.text_stop_preview_before_editing_the_queue_91bc03, "Ferma l'anteprima prima di modificare la coda") }; if (index in 0 until it.mediaItemCount) it.removeMediaItem(index) }
    fun move(from: Int, to: Int) = command { require(previewToken.value == null) { localized(R.string.text_stop_preview_before_editing_the_queue_91bc03, "Ferma l'anteprima prima di modificare la coda") }; if (from in 0 until it.mediaItemCount && to in 0 until it.mediaItemCount) it.moveMediaItem(from, to) }
    fun clear() = command { require(previewToken.value == null) { localized(R.string.text_stop_preview_before_clearing_the_queue_7fbd0a, "Ferma l'anteprima prima di svuotare la coda") }; it.pause(); it.clearMediaItems() }
    fun playQueueIndex(index: Int) = command {
        require(index in 0 until it.mediaItemCount) { localized(R.string.text_track_unavailable_in_queue_d9efbc, "Brano non disponibile in coda") }
        it.seekTo(index, 0); it.prepare(); it.play()
    }
    fun timer(minutes: Int) {
        app.getSharedPreferences("music", Context.MODE_PRIVATE).edit()
            .putLong("timer", if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L else 0L).apply()
    }
    fun pauseForVideo(): String? {
        val pending = pendingVideoResume?.takeIf { it.second == userRevision }
        invalidateVideoResume() // A new audible video also revokes a resume still queued by the previous one.
        val id = pauseAutomaticallyForVideo?.invoke(pending?.first) ?: return null
        videoPause = id to userRevision
        return id
    }
    internal fun pauseForSleep() {
        invalidateVideoResume()
        if (pauseAutomaticallyForVideo != null) pauseAutomaticallyForVideo?.invoke(null)
        else if (controller != null || future != null) pause()
    }
    fun finishVideo(id: String?) {
        if (!app.playbackInteractive) { invalidateVideoResume(); return }
        val token = videoPause ?: return
        videoPause = null
        if (token.first == id && token.second == userRevision &&
            app.catalog.snapshot.value.byId[id]?.let(app.catalog.snapshot.value::usable) == true) {
            pendingVideoResume = token
            scope.launch {
                try {
                    serviceCommand("it.ayngallery.VIDEO_RESUME", Bundle().apply {
                        putString("media", token.first); putLong("revision", token.second)
                    })
                    publish()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* A disconnected service cannot grant an automatic restart. */ }
                finally { if (pendingVideoResume == token) pendingVideoResume = null }
            }
        }
    }
    fun invalidateVideoResume() { cancelSoundFade(); soundPause = null; userRevision++; videoPause = null; pendingVideoResume = null }
    suspend fun releaseForMutation(id: String) {
        invalidateVideoResume()
        if (previewToken.value != null) endPreview(previewToken.value)
        val player = controller ?: if (future != null) connect() else return
        if (player.currentMediaItem?.mediaId == id) { player.pause(); player.stop() }
        for (index in player.mediaItemCount - 1 downTo 0) if (player.getMediaItemAt(index).mediaId == id) player.removeMediaItem(index)
        publish()
    }
    fun close() {
        cancelSoundFade(); soundPause = null
        closed = true
        previewToken.value = null
        videoPause = null; pendingVideoResume = null; pauseAutomaticallyForVideo = null
        scope.cancel()
        controller?.release(); controller = null
        future?.cancel(false); future = null
    }
}

/** Media3 handles listeners/state propagation; the service owns the policy for every controller. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class MusicSessionPlayer(player: Player, private val preferences: PreferenceStore,
    private val invalidateVideoResume: () -> Unit, private val previewActive: () -> Boolean = { false }) : ForwardingSimpleBasePlayer(player) {
    override fun getState(): SimpleBasePlayer.State {
        val state = super.getState()
        val commands = state.availableCommands.buildUpon()
        if (previewActive()) commands.removeAll(Player.COMMAND_CHANGE_MEDIA_ITEMS, Player.COMMAND_SET_MEDIA_ITEM,
            Player.COMMAND_SET_REPEAT_MODE, Player.COMMAND_SET_SHUFFLE_MODE)
        return state.buildUpon().setAvailableCommands(commands.build())
            .setMaxSeekToPreviousPositionMs(if (preferences.state.value.alwaysPrevious) Long.MAX_VALUE else 3000L).build()
    }

    fun refreshPreferences() = invalidateState()

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        invalidateVideoResume() // Even a second pause while already paused revokes the automatic resume.
        return super.handleSetPlayWhenReady(playWhenReady)
    }
    override fun handleStop(): ListenableFuture<*> {
        invalidateVideoResume()
        return super.handleStop()
    }
    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        invalidateVideoResume()
        if (seekCommand != Player.COMMAND_SEEK_TO_PREVIOUS && seekCommand != Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
        val delegate = player
        if (delegate.mediaItemCount > 0) {
            if (delegate.hasPreviousMediaItem() && (preferences.state.value.alwaysPrevious || delegate.currentPosition <= 3000L))
                delegate.seekToPreviousMediaItem()
            else delegate.seekTo(0)
        }
        return com.google.common.util.concurrent.Futures.immediateVoidFuture()
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
class MusicService : MediaSessionService() {
    private lateinit var player: Player
    private lateinit var sessionPlayer: MusicSessionPlayer
    private var mediaSession: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as GalleryApp
    private val prefs by lazy { getSharedPreferences("music", Context.MODE_PRIVATE) }
    private val restored = CompletableDeferred<Unit>()
    private var recoveryFailed = false
    private data class PreviewBackup(val token: String, val items: List<MediaItem>, val index: Int, val position: Long, val shuffle: Boolean, val repeat: Int, val received: IncomingFile? = null)
    private var previewBackup: PreviewBackup? = null
    internal fun previewActive() = previewBackup != null

    private fun restorePreview(data: CatalogSnapshot, token: String?) {
        val backup = previewBackup?.takeIf { token == null || it.token == token } ?: return
        val items = backup.items.mapIndexedNotNull { index, media -> data.byId[media.mediaId]?.takeIf { it.kind == "AUDIO" && data.usable(it) }?.let { index to app.catalog.musicItem(it, data) } }
        val found = items.indexOfFirst { it.first == backup.index }
        player.pause()
        previewBackup = null; app.music.previewToken.value = null
        try {
            if (items.isEmpty()) player.clearMediaItems()
            else { player.setMediaItems(items.map { it.second }, found.coerceAtLeast(0), if (found >= 0) backup.position else 0); player.prepare() }
            player.shuffleModeEnabled = backup.shuffle; player.repeatMode = backup.repeat
            sessionPlayer.refreshPreferences(); persist()
        } catch (error: Exception) { previewBackup = backup; app.music.previewToken.value = backup.token; persist(); throw error }
    }
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                // Also invalidate a pending video resume when music is already paused.
                app.music.invalidateVideoResume(); player.pause()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val app = application as GalleryApp
        val renderers = object : androidx.media3.exoplayer.DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): androidx.media3.exoplayer.audio.AudioSink =
                androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(false).setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(androidx.media3.exoplayer.audio.TeeAudioProcessor(MusicSpectrum(
                        { app.widgets.audioAnalysisEnabled }, { app.widgets.spectrum(it) })))).build()
        }
        player = ExoPlayer.Builder(this, renderers).setLooper(Looper.getMainLooper())
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this,
                musicExtractors()))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true).build()
        scope.launch { app.preferences.state.collect { (player as? ExoPlayer)?.setWakeMode(if (it.musicDuringSleep) C.WAKE_MODE_NETWORK else C.WAKE_MODE_NONE) } }
        ContextCompat.registerReceiver(this, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
        val open = PendingIntent.getActivity(this, 11,
            Intent(this, LauncherActivity::class.java).setAction("it.ayngallery.OPEN_PLAYER"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        sessionPlayer = MusicSessionPlayer(player, app.preferences, app.music::invalidateVideoResume, ::previewActive)
        app.music.soundSettingsPlayer = player
        app.music.pauseAutomaticallyForVideo = { pending ->
            if ((player.playWhenReady || (pending != null && player.currentMediaItem?.mediaId == pending)) &&
                player.playbackState in setOf(Player.STATE_BUFFERING, Player.STATE_READY) && player.playerError == null)
                player.currentMediaItem?.mediaId?.also { if (player.playWhenReady) player.pause() }
            else null
        }
        mediaSession = MediaSession.Builder(this, sessionPlayer).setSessionActivity(open)
            .setMediaButtonPreferences(listOf(CommandButton.Builder(CommandButton.ICON_PREVIOUS)
                .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS).setSlots(CommandButton.SLOT_BACK)
                .setDisplayName(localized(R.string.text_previous_ff1443, "Precedente")).build()))
            .setCallback(object : MediaSession.Callback {
            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                val standard = super.onConnect(session, controller)
                return if (controller.packageName == packageName && controller.uid == android.os.Process.myUid())
                    MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(standard.availableSessionCommands.buildUpon()
                            .add(SessionCommand("it.ayngallery.WIDGET_COMMAND", Bundle.EMPTY))
                            .add(SessionCommand(AUDIO_PREVIEW_START, Bundle.EMPTY)).add(SessionCommand(AUDIO_PREVIEW_END, Bundle.EMPTY))
                            .add(SessionCommand(RECEIVED_AUDIO_START, Bundle.EMPTY))
                            .add(SessionCommand("it.ayngallery.VIDEO_RESUME", Bundle.EMPTY)).build()).build()
                else standard
            }
            override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, command: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                if (command.customAction !in setOf("it.ayngallery.WIDGET_COMMAND", "it.ayngallery.VIDEO_RESUME", AUDIO_PREVIEW_START, AUDIO_PREVIEW_END, RECEIVED_AUDIO_START) || controller.packageName != packageName || controller.uid != android.os.Process.myUid())
                    return super.onCustomCommand(session, controller, command, args)
                val result = SettableFuture.create<SessionResult>()
                val job = scope.launch {
                    try {
                        restored.await() // A tap after process recreation must act on the recovered queue, never an empty temporary player.
                        val data = withContext(Dispatchers.IO) { app.catalog.readSnapshot() }
                        if (command.customAction == AUDIO_PREVIEW_END) {
                            restorePreview(data, args.getString("token")); result.set(SessionResult(SessionResult.RESULT_SUCCESS)); return@launch
                        }
                        if (command.customAction == RECEIVED_AUDIO_START) {
                            val file = IncomingFile.decode(args.getString("file") ?: "")
                            val token = args.getString("token") ?: error(localized(R.string.text_opening_not_identified_3b2f9b, "Apertura non identificata"))
                            require(file.kind == "AUDIO" && java.util.UUID.fromString(token).toString() == token)
                            IncomingMedia.readable(app, file)
                            ensureActive()
                            require(args.containsKey("revision") && args.getLong("revision") == app.music.videoRevision) { localized(R.string.text_opening_superseded_by_another_music_command_893ffe, "Apertura superata da un altro comando musicale") }
                            val media = MediaItem.Builder().setMediaId(file.id).setUri(file.uri).setMimeType(file.mime)
                                .setMediaMetadata(MediaMetadata.Builder().setTitle(file.name).setArtist(localized(R.string.text_received_file_temporary_access_a756d1, "File ricevuto · apertura temporanea")).build()).build()
                            val backup = previewBackup ?: PreviewBackup(token, (0 until player.mediaItemCount).map(player::getMediaItemAt),
                                player.currentMediaItemIndex.coerceAtLeast(0), player.currentPosition.coerceAtLeast(0), player.shuffleModeEnabled, player.repeatMode)
                            previewBackup = backup.copy(token = token, received = file); app.music.previewToken.value = token
                            player.pause(); player.shuffleModeEnabled = false; player.repeatMode = Player.REPEAT_MODE_OFF
                            player.setMediaItem(media); player.prepare(); player.play(); sessionPlayer.refreshPreferences(); persist()
                            result.set(SessionResult(SessionResult.RESULT_SUCCESS)); return@launch
                        }
                        if (command.customAction == AUDIO_PREVIEW_START) {
                            val target = ExternalRequest.decode(args.getString("target") ?: "").targets.single()
                            val recipe = AudioRecipe.decode(args.getString("recipe") ?: "")
                            val token = args.getString("token") ?: error(localized(R.string.text_preview_not_identified_21e557, "Anteprima non identificata"))
                            require(java.util.UUID.fromString(token).toString() == token)
                            val item = data.byId[target.id]?.takeIf { data.usable(it) && target.matches(it) && it.kind == "AUDIO" } ?: throw SourceChanged()
                            val uri = app.catalog.uri(item, data)
                            withContext(Dispatchers.IO) {
                                val actual = FileDocuments(app).stat(uri)
                                if (actual.name != target.name || DocumentVersions.changed(target.size, target.modified, actual.size, actual.modified)) throw SourceChanged()
                                require(AudioEditing.inspect(app, uri) == recipe.duration) { localized(R.string.text_the_source_duration_changed_d3389f, "La durata della sorgente è cambiata") }
                            }
                            ensureActive()
                            require(args.containsKey("revision") && args.getLong("revision") == app.music.videoRevision) { localized(R.string.text_preview_superseded_by_another_music_command_9601ec, "Anteprima superata da un altro comando musicale") }
                            val media = app.catalog.musicItem(item, data).buildUpon().setClippingConfiguration(AudioEditing.clipping(recipe))
                                .setMediaMetadata(app.catalog.musicItem(item, data).mediaMetadata.buildUpon().setTitle(localized(R.string.text_preview_1_s_809e74, "Anteprima · %1\$s", item.title ?: item.name)).build()).build()
                            val backup = previewBackup ?: PreviewBackup(token, (0 until player.mediaItemCount).map(player::getMediaItemAt),
                                player.currentMediaItemIndex.coerceAtLeast(0), player.currentPosition.coerceAtLeast(0), player.shuffleModeEnabled, player.repeatMode)
                            previewBackup = backup.copy(token = token, received = null); app.music.previewToken.value = token
                            player.pause(); player.shuffleModeEnabled = false; player.repeatMode = Player.REPEAT_MODE_OFF
                            player.setMediaItem(media); player.prepare(); player.play()
                            sessionPlayer.refreshPreferences(); persist()
                            result.set(SessionResult(SessionResult.RESULT_SUCCESS)); return@launch
                        }
                        val initial = args.getString("initialTrack")
                        if (command.customAction == "it.ayngallery.WIDGET_COMMAND" && args.getString("command") == "TOGGLE" && initial != null && !player.playWhenReady && player.currentMediaItem?.mediaId != initial) {
                            val chosen = data.byId[initial]?.takeIf { it.kind == "AUDIO" && data.usable(it) }
                                ?: error(localized(R.string.text_track_unavailable_b73d64, "Brano non disponibile"))
                            val tracks = data.visibleMedia.filter { it.kind == "AUDIO" && data.usable(it) }
                                .sortedWith(compareBy(MediaNames.natural) { it.title ?: it.name })
                            withContext(Dispatchers.IO) { app.catalog.writer.withLock { app.catalog.rememberLocked(tracks.map { it.id }) } }
                            player.setMediaItems(tracks.map { app.catalog.musicItem(it, data) }, tracks.indexOfFirst { it.id == chosen.id }, 0); player.prepare()
                        }
                        if (command.customAction == "it.ayngallery.WIDGET_COMMAND" && args.getString("command") in setOf("TOGGLE", "SHUFFLE", "MODE") && player.mediaItemCount == 0) {
                            val playlist = args.getString("playlist")
                            val candidates = if (playlist != null) data.collectionItems.filter { it.collectionId == playlist }.sortedBy { it.position }.mapNotNull { data.byId[it.mediaId] }
                                else data.visibleMedia.filter { it.kind == "AUDIO" }.sortedWith(compareBy(MediaNames.natural) { it.title ?: it.name })
                            val tracks = candidates.filter { it.kind == "AUDIO" && data.usable(it) }
                            require(tracks.isNotEmpty()) { localized(R.string.text_track_unavailable_b73d64, "Brano non disponibile") }
                            withContext(Dispatchers.IO) { app.catalog.writer.withLock { app.catalog.rememberLocked(tracks.map { it.id }) } }
                            player.setMediaItems(tracks.map { app.catalog.musicItem(it, data) }); player.prepare()
                        }
                        val current = player.currentMediaItem?.mediaId
                        if (command.customAction == "it.ayngallery.VIDEO_RESUME") {
                            require(app.playbackInteractive) { "Automatic resume cancelled during sleep" }
                            // Check after IO: a notification pause/seek queued ahead of this request must win.
                            if (current == args.getString("media") && args.containsKey("revision") &&
                                app.music.videoRevision == args.getLong("revision") && !player.playWhenReady && player.playerError == null &&
                                data.byId[current]?.let(data::usable) == true) player.play()
                            result.set(SessionResult(SessionResult.RESULT_SUCCESS)); return@launch
                        }
                        val action = args.getString("command")
                        require(action in setOf("TOGGLE", "PREVIOUS", "NEXT", "FAVORITE", "SHUFFLE", "MODE"))
                        val received = previewBackup?.received?.takeIf { it.id == current }
                        if (received != null) {
                            IncomingMedia.readable(app, received)
                            require(player.currentMediaItem?.mediaId == current && previewBackup?.received == received) { localized(R.string.text_the_track_changed_refresh_the_command_64968c, "Il brano è cambiato: aggiorna il comando") }
                        }
                        require(current != null && (received != null || data.byId[current]?.let(data::usable) == true)) { localized(R.string.text_track_unavailable_b73d64, "Brano non disponibile") }
                        if (action == "FAVORITE") {
                            require(received == null) { localized(R.string.text_import_a_copy_or_keep_the_link_before_adding_to_55a177, "Importa una copia o conserva il collegamento prima di aggiungere ai Preferiti") }
                            require(args.getString("media") == current) { localized(R.string.text_the_track_changed_refresh_the_widget_f90587, "Il brano è cambiato: aggiorna il widget") }
                            withContext(Dispatchers.IO) { app.catalog.favorite(current) }
                        } else {
                            app.music.invalidateVideoResume()
                            when (action) {
                                "TOGGLE" -> if (player.playWhenReady) player.pause() else {
                                    if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition()
                                    if (player.playbackState == Player.STATE_IDLE) player.prepare()
                                    player.play()
                                }
                                "PREVIOUS" -> sessionPlayer.seekToPrevious()
                                "NEXT" -> { require(player.hasNextMediaItem()); player.seekToNextMediaItem() }
                                "SHUFFLE" -> { player.repeatMode = Player.REPEAT_MODE_OFF; player.shuffleModeEnabled = !player.shuffleModeEnabled }
                                "MODE" -> when {
                                    player.shuffleModeEnabled -> { player.shuffleModeEnabled = false; player.repeatMode = Player.REPEAT_MODE_ONE }
                                    player.repeatMode == Player.REPEAT_MODE_ALL -> player.repeatMode = Player.REPEAT_MODE_OFF
                                    player.repeatMode == Player.REPEAT_MODE_ONE -> player.repeatMode = Player.REPEAT_MODE_OFF
                                    else -> { player.repeatMode = Player.REPEAT_MODE_OFF; player.shuffleModeEnabled = true }
                                }
                            }
                        }
                        result.set(SessionResult(SessionResult.RESULT_SUCCESS))
                    } catch (cancelled: CancellationException) { result.cancel(false); throw cancelled }
                    catch (error: Exception) { result.set(SessionResult(SessionError.ERROR_INVALID_STATE, Bundle().apply { putString("error", error.message) })) }
                }
                result.addListener({ if (result.isCancelled) job.cancel() }, mainExecutor)
                return result
            }
            override fun onAddMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> {
                val result = SettableFuture.create<MutableList<MediaItem>>()
                scope.launch {
                    try {
                        restored.await()
                        val data = withContext(Dispatchers.IO) { app.catalog.readSnapshot() }
                        result.set(mediaItems.mapNotNull { requested -> data.byId[requested.mediaId]?.takeIf { it.kind == "AUDIO" && data.usable(it) } }
                            .map { app.catalog.musicItem(it, data) }.toMutableList())
                    } catch (error: Exception) { result.setException(error) }
                }
                return result
            }
        }).build()
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { persist() }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) android.util.Log.i("DSGalleryPlayer", "transport reason=$reason from=${oldPosition.mediaItem?.mediaId} at=${oldPosition.positionMs} to=${newPosition.mediaItem?.mediaId} at=${newPosition.positionMs}")
            }
            override fun onPlayerError(error: PlaybackException) {
                android.util.Log.w("DSGalleryPlayer", "playback error=${error.errorCodeName}", error)
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
                    android.util.Log.d("DSGalleryPlayer", "ready=$playWhenReady reason=$reason")
                if (!playWhenReady && reason != Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) app.music.invalidateVideoResume()
            }
            override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
                if (playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) app.music.invalidateVideoResume()
            }
        })
        scope.launch {
            try {
            val data = withContext(Dispatchers.IO) { app.catalog.readSnapshot() }
            val saved = try { JSONArray(prefs.getString("queue", "[]")) } catch (_: Exception) { JSONArray() }
            val items = (0 until saved.length()).mapNotNull { data.byId[saved.optString(it)]?.takeIf { m -> m.kind == "AUDIO" && data.usable(m) } }
            if (items.isNotEmpty() && player.mediaItemCount == 0) {
                val originalIndex = prefs.getInt("index", 0).coerceIn(0, (saved.length() - 1).coerceAtLeast(0))
                val originalId = saved.optString(originalIndex)
                val index = items.indexOfFirst { it.id == originalId }.coerceAtLeast(0)
                val position = prefs.getLong("position", 0).coerceAtLeast(0)
                player.setMediaItems(items.map { app.catalog.musicItem(it, data) }, index, position)
                player.shuffleModeEnabled = prefs.getBoolean("shuffle", false)
                player.repeatMode = prefs.getInt("repeat", Player.REPEAT_MODE_OFF).takeIf { it == Player.REPEAT_MODE_ONE } ?: Player.REPEAT_MODE_OFF
                player.prepare(); player.pause() // Recovery never grants permission to autoplay.
            }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                recoveryFailed = true
                app.music.state.value = app.music.state.value.copy(error = localized(R.string.text_queue_not_recovered_playback_remains_paused_897fc5, "Coda non recuperata. La riproduzione rimane in pausa."))
            }
            finally { restored.complete(Unit); persist() }
        }
        scope.launch {
            combine(app.catalog.snapshot, app.preferences.state, app.catalog.cacheRevision) { signal, _, _ -> signal }.collectLatest { signal ->
                sessionPlayer.refreshPreferences()
                if (!signal.initialized) return@collectLatest
                // A cached emission from before restoration is not evidence that the newly recovered queue is unavailable.
                val data = withContext(Dispatchers.IO) { app.catalog.readSnapshot() }
                val incoming = previewBackup?.received
                if (incoming != null) {
                    val token = previewBackup!!.token
                    try { IncomingMedia.readable(app, incoming) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        if (previewBackup?.token == token) {
                            restorePreview(data, token)
                            app.music.state.value = app.music.state.value.copy(error = localized(R.string.text_received_track_access_expired_previous_queue_rec_739d68, "Accesso al brano ricevuto scaduto; coda precedente recuperata in pausa"))
                        }
                    }
                    return@collectLatest
                }
                val removed = (0 until player.mediaItemCount).filter { data.byId[player.getMediaItemAt(it).mediaId]?.let(data::usable) != true }
                if (player.currentMediaItemIndex in removed) { app.music.invalidateVideoResume(); player.pause() }
                removed.asReversed().forEach(player::removeMediaItem)
                // Strip invalidated custom artwork even when the song itself remains visible.
                for (i in 0 until player.mediaItemCount) {
                    val old = player.getMediaItemAt(i)
                    val item = data.byId[old.mediaId] ?: continue
                    val artwork = app.catalog.artwork(item, data)
                    if (old.mediaMetadata.artworkUri?.toString() != artwork)
                        player.replaceMediaItem(i, old.buildUpon().setMediaMetadata(old.mediaMetadata.buildUpon()
                            .setArtworkUri(artwork?.let(android.net.Uri::parse)).build()).build())
                }
            }
        }
        scope.launch {
            while (isActive) {
                delay(1000)
                val timer = prefs.getLong("timer", 0)
                if (timer > 0 && System.currentTimeMillis() >= timer) {
                    app.music.invalidateVideoResume(); player.pause(); prefs.edit().putLong("timer", 0).apply()
                }
                if (player.isPlaying && previewBackup == null) prefs.edit().putLong("position", player.currentPosition).apply()
            }
        }
    }

    private fun persist() {
        // Initial session events describe an empty player while Room restores its queue.
        // They must not overwrite the saved queue that recovery is still about to read.
        if (!restored.isCompleted || (recoveryFailed && (previewBackup != null || player.mediaItemCount == 0))) return
        recoveryFailed = false // A new nonempty queue supplied explicitly can replace a recovery that failed.
        val backup = previewBackup
        val queue = JSONArray(backup?.items?.map { it.mediaId } ?: (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId })
        prefs.edit().putString("queue", queue.toString()).putInt("index", backup?.index ?: player.currentMediaItemIndex.coerceAtLeast(0))
            .putLong("position", backup?.position ?: player.currentPosition.coerceAtLeast(0)).putBoolean("shuffle", backup?.shuffle ?: player.shuffleModeEnabled)
            .putInt("repeat", backup?.repeat ?: player.repeatMode).apply()
        app.widgets.publish(player.playbackSnapshot(
            queue = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId },
            error = player.playerError?.let { localized(R.string.text_playback_failed_12d57a, "Riproduzione non riuscita") },
        ))
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession.takeIf { controllerInfo.packageName == packageName || controllerInfo.isTrusted }
    override fun onDestroy() {
        app.music.soundSettingsPlayer = null
        app.music.pauseAutomaticallyForVideo = null
        app.music.invalidateVideoResume()
        unregisterReceiver(noisy)
        scope.cancel()
        persist()
        app.music.previewToken.value = null
        mediaSession?.release(); mediaSession = null
        player.release()
        app.widgets.publish(null)
        super.onDestroy()
    }
}
