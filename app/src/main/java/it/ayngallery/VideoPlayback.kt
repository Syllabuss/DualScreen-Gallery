package it.ayngallery

import android.content.Context
import androidx.compose.ui.graphics.toArgb
import androidx.compose.runtime.*
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.TrackSelectionDialogBuilder
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

internal fun videoResumeChoice(action: PadAction): Boolean? = when (action) {
    PadAction.CONFIRM -> true
    PadAction.SETTINGS -> false
    else -> null
}

internal fun shuffledVideoNext(ids: List<String>, current: String, played: MutableSet<String>): String? {
    played += current
    val remaining = ids.filter { it !in played }
    if (remaining.isNotEmpty()) return remaining.random()
    played.clear()
    return ids.filter { it != current }.randomOrNull()
}

@androidx.annotation.OptIn(UnstableApi::class)
class VideoPlayback(private val app: GalleryApp) {
    companion object { const val PIP_OWNER = 2 }
    val floating get() = surface == "pip" && player != null
    internal var pipOpening = false
        private set
    private var pipReturnOwner = -1
    private var pipReturnPanel: java.lang.ref.WeakReference<PanelActivity>? = null
    private var pipReturning = false
    private var viewerBinding: Any? = null
    var returnedFromPip by mutableStateOf(false)
        private set
    var suspendedForTools by mutableStateOf(false)
        private set
    private var automaticForMedia = false
    private var automaticPip = false
    internal var manualPip = false
        private set
    private var automaticReturnRequested = false
    val keepsPlaying get() = app.preferences.state.value.videoKeepPlaying
    var flowMode by mutableIntStateOf(0)
        private set
    var flowFeedbackRevision by mutableIntStateOf(0)
        private set
    fun cycleFlow() {
        setFlow((flowMode + 1) % 4)
    }
    fun setFlow(mode: Int) {
        flowMode = mode.coerceIn(0, 3)
        shufflePlayed.clear()
        flowFeedbackRevision++
        player?.repeatMode = if (flowMode == 2) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }
    private val shufflePlayed = mutableSetOf<String>()
    private fun finished(id: String) {
        app.catalog.scope.launch { app.catalog.saveVideoPosition(id, 0) }
        if (flowMode == 0 || flowMode == 2) { player?.pause(); return }
        val ids = context.mapNotNull { app.catalog.snapshot.value.byId[it] }.filter { app.catalog.snapshot.value.usable(it) && it.kind == "VIDEO" && !it.convertedGif }
        val next = if (flowMode == 3) shuffledVideoNext(ids.map { it.id }, id, shufflePlayed)?.let { next -> ids.first { it.id == next } }
            else ids.getOrNull(ids.indexOfFirst { it.id == id } + 1)
        if (next != null) android.os.Handler(android.os.Looper.getMainLooper()).post { play(next, owner, player?.volume == 0f, surface, resume = false, advanceQueue = true) }
        else player?.pause()
    }
    fun suspendForTools() {
        val current = player ?: return
        if (surface !in setOf("viewer", "pip") || liveChannel == null && app.catalog.snapshot.value.byId[mediaId]?.let { it.kind == "VIDEO" && !it.convertedGif } != true) return
        suspendedForTools = true
        returnedFromPip = false
        if (keepsPlaying && current.playWhenReady && current.playbackState != Player.STATE_ENDED && !floating) {
            app.session.previewActivity()?.let { automaticPip = true; openPip(it, automatic = true) }
            if (!pipOpening && !floating) { automaticPip = false; current.pause() }
        } else if (!floating) current.pause()
        wakeNavigation()
    }
    fun suspendForMedia() { suspendForTools(); automaticForMedia = true }
    fun returnFromTools(force: Boolean = false) {
        if (automaticForMedia && !force) return
        if (!manualPip && (automaticPip || force) && (floating || pipOpening) && !automaticReturnRequested)
            (app.session.previewActivity() ?: pipReturnPanel?.get())?.let { automaticReturnRequested = true; returnToGallery(it) }
    }
    fun browsePreview(id: String?) {
        val tab = app.session.libraryModel()?.route?.value?.tab
        if (tab != null && tab !in setOf("PHOTO", "ALBUM", "VIDEO", "FAVORITE")) return
        if (id != null && app.catalog.snapshot.value.byId[id]?.kind !in setOf("IMAGE", "GIF", "VIDEO")) return
        if (player != null && !floating && !returnedFromPip && player?.playbackState == Player.STATE_ENDED && id != mediaId) {
            suspendedForTools = false
            stop(owner)
        }
    }
    internal var pipActivity: java.lang.ref.WeakReference<VideoPipActivity>? = null
    internal var notificationService: VideoService? = null
    internal fun notificationPlayer(): Player? = (if (liveChannel != null) liveSessionPlayer else player)?.takeIf {
        surface in setOf("viewer", "pip", "received") && app.catalog.snapshot.value.byId[mediaId]?.convertedGif != true
    }
    private fun updateNotification() {
        val current = notificationPlayer()
        notificationService?.updatePlayer(current)
        if (current != null) app.startService(android.content.Intent(app, VideoService::class.java))
    }
    fun observe() {
        app.catalog.scope.launch {
            app.preferences.state.map { listOf(it.videoMixAudio, it.videoKeepPlaying, it.tvEnabled, it.videoDuringSleep) }.distinctUntilChanged().collect { _ ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                    if (!app.preferences.state.value.tvEnabled) { cancelLiveRequest(); if (liveChannel != null) stop(owner) }
                    (player as? ExoPlayer)?.setWakeMode(if (app.preferences.state.value.videoDuringSleep) C.WAKE_MODE_NETWORK else C.WAKE_MODE_NONE)
                    player?.let { it.setAudioAttributes(AudioAttributes.DEFAULT, it.volume > 0f && !app.preferences.state.value.videoMixAudio && !keepsPlaying) }
                }
            }
        }
    }
    private var homePip = false
    private var homeBackgroundSeen = false
    internal val returningFromHome get() = homePip && homeBackgroundSeen && (floating || pipOpening)
    fun leaveGallery(activity: PanelActivity) {
        if (!app.playbackInteractive || !keepsPlaying || player?.playWhenReady != true || surface != "viewer") return
        homePip = true
        openPip(app.session.previewActivity() ?: activity, automatic = true)
        if (!pipOpening && !floating) homePip = false
    }
    fun galleryBackgrounded() {
        if (!homePip) app.session.previewActivity()?.let(::leaveGallery)
        homeBackgroundSeen = homePip
    }
    fun galleryReturned(activity: PanelActivity) {
        if (homePip && homeBackgroundSeen && (floating || pipOpening)) {
            if (app.session.settingsOwner != null || app.session.libraryModel()?.route?.value?.tab in setOf("MUSIC", "PLAYER", "FTP")) {
                homePip = false; homeBackgroundSeen = false
                return
            }
            homeBackgroundSeen = false
            // The library can restart before the original preview panel. Keep the saved owner.
            returnToGallery(pipReturnPanel?.get()?.takeIf { !it.isFinishing && !it.isDestroyed } ?: activity)
        }
    }
    fun openPip(activity: PanelActivity, automatic: Boolean = false) {
        if (pipOpening || floating || surface != "viewer" || player == null) return
        if (!activity.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            app.session.fail(localized(R.string.video_pip_unavailable, "La finestra video non è disponibile su questo dispositivo.")); return
        }
        manualPip = !automatic
        pipReturnOwner = owner
        pipReturnPanel = java.lang.ref.WeakReference(activity)
        returnedFromPip = false
        viewerBinding = null
        pipReturning = false; pipOpening = true; wakeNavigation()
        try {
            activity.startActivity(android.content.Intent(activity, VideoPipActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION),
                android.app.ActivityOptions.makeCustomAnimation(activity, 0, 0).setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY).toBundle())
        } catch (_: RuntimeException) {
            pipOpening = false
            app.session.fail(localized(R.string.video_pip_unavailable, "La finestra video non è disponibile su questo dispositivo."))
        }
    }
    fun returnToGallery(activity: PanelActivity) {
        if (!floating && !pipOpening) return
        pipReturnOwner = activity.slot
        pipReturnPanel = java.lang.ref.WeakReference(activity)
        // Keep ownership in PiP until its window has detached, then attach to a started panel.
        // Bringing the PiP task to the front can stop that panel midway through the handoff.
        pipActivity?.get()?.returnToPanels() ?: run {
            beginPipReturn()
            if (!pipOpening) resumePipViewer(activity)
        }
    }
    internal fun attachPip(activity: VideoPipActivity) {
        pipActivity = java.lang.ref.WeakReference(activity)
        viewerBinding = null
        surface = "pip"; owner = PIP_OWNER; pipOpening = false
        if (pipReturning) activity.window.decorView.post { activity.returnToPanels() }
    }
    private fun restorePip(panel: PanelActivity) {
        if (!floating) return
        pipReturning = false
        viewerBinding = null
        app.session.gridPreviewDirection = androidx.compose.ui.unit.IntOffset.Zero
        surface = "viewer"; owner = panel.slot
        suspendedForTools = false
        // The video owns the preview; the library keeps its route, selection and scroll position.
        returnedFromPip = true
        manualPip = false; automaticPip = false; automaticForMedia = false
        homePip = false; homeBackgroundSeen = false
        automaticReturnRequested = false
        pipReturnPanel = null
        wakeNavigation()
    }
    internal fun beginPipReturn() {
        pipReturning = floating || pipOpening
        if (!pipReturning) return
        automaticReturnRequested = true
        if (app.session.settingsOwner != null) app.session.closeSettings()
        pipActivity?.get()?.detachPlayer()
    }
    internal fun pipClosed(activity: VideoPipActivity) {
        if (pipActivity?.get() !== activity) return
        pipActivity = null
        if (pipReturning) (app.session.previewActivity() ?: pipReturnPanel?.get())?.let(::resumePipViewer)
    }
    internal fun dismissPip(activity: VideoPipActivity) {
        if (pipActivity?.get() !== activity || pipReturning) return
        player?.pause()
        activity.detachPlayer()
        pipActivity = null
        viewerBinding = null
        surface = "viewer"; owner = pipReturnOwner
        pipOpening = false; returnedFromPip = true
        manualPip = false; automaticPip = false; automaticForMedia = false; automaticReturnRequested = false
        homePip = false; homeBackgroundSeen = false
        // Keep the current browser/settings route and the exact playback position.
        wakeNavigation(); updateNotification()
    }
    internal fun resumePipViewer(panel: PanelActivity) {
        if (pipReturning && pipActivity?.get() == null && panel.isStartedFlow.value && !panel.isFinishing &&
            panel.slot == (app.session.previewActivity()?.slot ?: pipReturnOwner)) restorePip(panel)
    }
    internal fun attachViewer(current: Player, slot: Int, binding: Any) {
        if (player === current && owner == slot && surface == "viewer") viewerBinding = binding
    }
    internal fun stopViewer(slot: Int, id: String, binding: Any) {
        // Outgoing Compose views may be disposed after the same player has returned from PiP.
        if (viewerBinding === binding && app.playbackInteractive) stop(slot, id, "viewer")
    }
    val playing = kotlinx.coroutines.flow.MutableStateFlow(false)
    var seekFeedbackSeconds by mutableIntStateOf(0)
        private set
    var seekFeedbackRevision by mutableIntStateOf(0)
        private set
    internal fun showSeek(seconds: Int) { seekFeedbackSeconds = seconds; seekFeedbackRevision++ }
    private var stickNavigation = VideoStickNavigation()
    fun doubleTapSeek(direction: Int) {
        val current = player ?: return
        if (liveChannel != null || surface != "viewer" || current.duration <= 0) return
        val seconds = stickNavigation.seek(direction, android.os.SystemClock.uptimeMillis())
        current.seekTo((current.currentPosition + seconds * 1000L).coerceIn(0, current.duration))
        showSeek(seconds)
    }
    fun leftStick(x: Float, y: Float, now: Long): Boolean {
        if (!app.preferences.state.value.videoStickControls) return false
        val current = player ?: return false
        if (surface != "viewer" || liveChannel == null && app.catalog.snapshot.value.byId[mediaId]?.convertedGif != false) return false
        val pad = app.preferences.state.value.pad
        val step = stickNavigation.update(x, y, now, pad.press, pad.release)
        if (liveChannel == null && step != null && step.seekSeconds != 0) {
            val duration = current.duration
            if (duration > 0) {
                val delta = step.seekSeconds * 1000L
                current.seekTo((current.currentPosition + delta).coerceIn(0, duration))
                showSeek(step.seekSeconds)
            }
        }
        if (step != null && step.volume != 0) {
            setVolume(current.volume + step.volume * .05f)
        }
        return true
    }
    internal fun resumableVideo(item: MediaEntity, now: Long = System.currentTimeMillis()): Boolean =
        item.videoPosition >= 5000 && item.videoPositionAt > 0 && now - item.videoPositionAt in 0..java.util.concurrent.TimeUnit.DAYS.toMillis(7) &&
            (item.duration == null || item.duration <= 0 || item.videoPosition < item.duration - 5000)
    data class ResumeRequest(val id: String, val slot: Int, val muted: Boolean, val surface: String)
    var resumeRequest by mutableStateOf<ResumeRequest?>(null)
        private set
    var context: List<String> = emptyList()
        private set
    var previewRevision = 0
        private set
    var replacementRequest by mutableStateOf<ResumeRequest?>(null)
        private set
    private var replacementPlayer: ExoPlayer? = null
    fun dismissReplacement() {
        replacementRequest = null
        replacementPlayer?.takeIf { it === player }?.play()
        replacementPlayer = null
    }
    fun confirmReplacement(dontAskAgain: Boolean) {
        val request = replacementRequest ?: return
        val previous = replacementPlayer
        replacementRequest = null; replacementPlayer = null
        app.catalog.scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) {
            try {
                if (dontAskAgain) {
                    app.preferences.update { it.copy(confirmVideoReplacement = false) }
                    app.session.settingsDraft = app.session.settingsDraft?.copy(confirmVideoReplacement = false)
                }
                val target = app.catalog.snapshot.value.byId[request.id]
                if (target != null && app.catalog.snapshot.value.usable(target) && app.session.selectedMediaId == target.id && player === previous)
                    requestStart(target, request.slot, request.muted, request.surface)
                else if (player === previous) previous?.play()
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (player === previous) previous?.play()
                app.session.fail(failure.message ?: localized(R.string.text_settings_not_saved_d67a68, "Impostazioni non salvate"))
            }
        }
    }
    fun requestPlay(item: MediaEntity, slot: Int, muted: Boolean, surfaceKey: String = "viewer") {
        if (!app.catalog.snapshot.value.usable(item)) return
        if (replacementRequest?.id == item.id) return
        if (replacementRequest != null) dismissReplacement()
        val current = player
        if (surfaceKey == "viewer" && item.kind == "VIDEO" && !item.convertedGif && mediaId != item.id &&
            current?.playWhenReady == true && current.playbackState != Player.STATE_ENDED &&
            app.catalog.snapshot.value.byId[mediaId]?.convertedGif == false && app.preferences.state.value.confirmVideoReplacement) {
            replacementPlayer = current
            current.pause(); playing.value = false
            resumeRequest = null
            replacementRequest = ResumeRequest(item.id, slot, muted, surfaceKey)
            return
        }
        requestStart(item, slot, muted, surfaceKey)
    }
    private fun requestStart(item: MediaEntity, slot: Int, muted: Boolean, surfaceKey: String) {
        if (surfaceKey == "viewer" && !item.convertedGif && resumableVideo(item))
            resumeRequest = ResumeRequest(item.id, slot, muted, surfaceKey)
        else play(item, slot, muted, surfaceKey, resume = false)
    }
    fun dismissResume() { resumeRequest = null }
    fun handleResumeControllerAction(action: PadAction): Boolean {
        replacementRequest?.let { request ->
            if (action in setOf(PadAction.UP, PadAction.DOWN, PadAction.LEFT, PadAction.RIGHT, PadAction.TAB_PREVIOUS, PadAction.TAB_NEXT,
                    PadAction.MEDIA_PREVIOUS, PadAction.MEDIA_NEXT) || app.session.selectedMediaId != request.id) {
                dismissReplacement(); return false
            }
            if (action == PadAction.BACK) { dismissReplacement(); return true }
            if (action == PadAction.CONFIRM) { confirmReplacement(false); return true }
        }
        val request = resumeRequest ?: return false
        if (action in setOf(PadAction.UP, PadAction.DOWN, PadAction.LEFT, PadAction.RIGHT, PadAction.TAB_PREVIOUS, PadAction.TAB_NEXT,
                PadAction.MEDIA_PREVIOUS, PadAction.MEDIA_NEXT) || app.session.selectedMediaId?.let { it != request.id } == true) {
            dismissResume()
            return false
        }
        if (action == PadAction.BACK) { dismissResume(); return true }
        val resume = videoResumeChoice(action) ?: return false
        confirmResume(resume)
        return true
    }
    fun confirmResume(resume: Boolean) {
        val request = resumeRequest ?: return
        resumeRequest = null
        if (app.session.selectedMediaId?.let { it != request.id } == true) return
        app.catalog.snapshot.value.byId[request.id]?.let { play(it, request.slot, request.muted, request.surface, resume = resume) }
    }
    fun transferViewer(slot: Int) {
        if (player == null || surface != "viewer") return
        owner = slot
        resumeRequest = resumeRequest?.copy(slot = slot)
    }
    var player by mutableStateOf<ExoPlayer?>(null)
        private set
    var mediaId by mutableStateOf<String?>(null)
        private set
    internal var liveLoadingChannel by mutableStateOf<TvCatalogEntry?>(null)
        private set
    internal var liveChannel by mutableStateOf<TvCatalogEntry?>(null)
        private set
    private var liveRequest: kotlinx.coroutines.Job? = null
    private var liveDeadline: kotlinx.coroutines.Job? = null
    private fun clearLiveLoading() { liveDeadline?.cancel(); liveDeadline = null; liveLoadingChannel = null }
    private var liveSessionPlayer: Player? = null
    var owner by mutableIntStateOf(-1)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var subtitleTracksAvailable by mutableStateOf(false)
        private set
    var dimNavigation by mutableStateOf(false)
    var navigationInteraction by mutableLongStateOf(0)
        private set
    fun wakeNavigation() { dimNavigation = false; navigationInteraction++ }
    fun pauseForSettings() = suspendForTools()
    fun toggle() { player?.let {
        if (it.playbackState == Player.STATE_ENDED && liveChannel == null) { it.seekTo(0); it.play() }
        else if (it.playWhenReady) it.pause() else it.play()
        wakeNavigation(); updateNotification()
    } }
    internal fun pauseForSleep() { player?.pause(); playing.value = false; wakeNavigation() }
    internal fun panelStopped(slot: Int) {
        if (!app.playbackInteractive && surface == "viewer" && owner == slot) {
            if (!app.preferences.state.value.videoDuringSleep) pauseForSleep()
            return
        }
        stop(slot)
    }
    fun backToPreview() {
        val current = player ?: return
        if (current.playWhenReady) { current.pause(); dimNavigation = false }
        else stop(owner)
    }
    /** A paused viewer consumes Back before the library can navigate out of a folder. */
    fun closePausedViewer(): Boolean {
        val current = player ?: return false
        if (surface != "viewer" || current.playWhenReady) return false
        stop(owner)
        return true
    }
    private var musicPause: String? = null
    var surface: String by mutableStateOf("viewer")
        private set

    internal fun playbackFailed(failure: PlaybackException) {
        playing.value = false
        app.music.invalidateVideoResume()
        error = localized(R.string.text_video_cannot_be_played_1_s_a0e774, "Video non riproducibile: %1\$s", failure.errorCodeName)
    }

    fun play(item: MediaEntity, slot: Int, muted: Boolean, surfaceKey: String = "viewer", recipe: VideoRecipe? = null, resume: Boolean = true, advanceQueue: Boolean = false) {
        cancelLiveRequest()
        val data = app.catalog.snapshot.value
        if (!data.usable(item)) { error = localized(R.string.text_video_unavailable_ce2e8e, "Video non disponibile"); return }
        start(item, app.catalog.uri(item, data), slot, muted, surfaceKey, recipe, resume, advanceQueue)
    }
    suspend fun playReceived(file: IncomingFile, slot: Int, muted: Boolean) {
        cancelLiveRequest()
        require(file.kind == "VIDEO"); IncomingMedia.readable(app, file)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
            start(file.item("received:${file.id}"), android.net.Uri.parse(file.uri), slot, muted, "received", null)
        }
    }
    internal fun closeLive() {
        val pip = pipActivity?.get()
        pipOpening = false
        stop(owner)
        pip?.finish()
    }
    internal fun cancelLiveRequest() { liveRequest?.cancel(); liveRequest = null; clearLiveLoading() }

    internal fun playLive(entry: TvCatalogEntry, slot: Int) {
        if (!app.preferences.state.value.tvEnabled || liveLoadingChannel != null) return
        liveLoadingChannel = entry; app.tv.failure = null; app.tv.retryAction = { app.tv.play(entry, slot, confirmed = true) }; app.tv.announcement = entry to android.os.SystemClock.elapsedRealtime()
        liveRequest?.cancel()
        liveRequest = app.catalog.scope.launch(Dispatchers.Main.immediate) {
            try {
                kotlinx.coroutines.withContext(Dispatchers.IO) { TvLiveSource.verify(entry.streamUrl) }
                if (!app.preferences.state.value.tvEnabled) return@launch
                saveAndRelease()
                liveChannel = entry; mediaId = "tv:" + tvKey(entry); owner = slot; surface = "viewer"; error = null
                context = emptyList(); suspendedForTools = false; wakeNavigation()
                if (musicPause == null) musicPause = app.music.pauseForVideo()
                val source = androidx.media3.exoplayer.hls.HlsMediaSource.Factory(
                    androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(TvNetwork.client))
                    .setLoadErrorHandlingPolicy(androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(2))
                player = ExoPlayer.Builder(app).setWakeMode(if (app.preferences.state.value.videoDuringSleep) C.WAKE_MODE_NETWORK else C.WAKE_MODE_NONE).setLoadControl(androidx.media3.exoplayer.DefaultLoadControl.Builder()
                    .setBufferDurationsMs(10000, 20000, 1000, 2000).setTargetBufferBytes(12 * 1024 * 1024).build())
                    .setAudioAttributes(AudioAttributes.DEFAULT, !app.preferences.state.value.videoMixAudio && !keepsPlaying)
                    .setHandleAudioBecomingNoisy(true).build().also { live ->
                        live.volume = volumeLevel
                        live.setMediaSource(source.createMediaSource(MediaItem.Builder().setUri(entry.streamUrl).setMediaId(mediaId!!)
                            .setMediaMetadata(MediaMetadata.Builder().setTitle(entry.name).setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_VIDEO).build()).build()))
                        live.addListener(object : Player.Listener {
                            override fun onEvents(player: Player, events: Player.Events) { playing.value = player.playWhenReady && player.playerError == null && player.playbackState != Player.STATE_ENDED }
                            override fun onPlaybackStateChanged(state: Int) {
                                if (this@VideoPlayback.player !== live) return
                                if (state == Player.STATE_READY || state == Player.STATE_ENDED) clearLiveLoading()
                                if (state == Player.STATE_READY && !live.isCurrentMediaItemLive) { live.stop(); error = localized(R.string.tv_error_live, "Questa sorgente non è una diretta."); app.tv.failure = error }
                                if (state == Player.STATE_ENDED) { live.pause(); error = localized(R.string.tv_error_network, "Diretta interrotta. Riprova."); app.tv.failure = error }
                            }
                            override fun onPlayerError(failure: PlaybackException) { if (this@VideoPlayback.player !== live) return; clearLiveLoading(); live.pause(); error = localized(R.string.tv_error_network, "Canale non disponibile. Riprova."); app.tv.failure = error }
                        })
                        liveSessionPlayer = TvLiveCommands(live)
                        live.prepare(); live.play()
                    }
                updateNotification()
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { clearLiveLoading(); app.tv.failure = tvError(failure) }
        }
        watchLiveStartup(entry)
    }

    private fun watchLiveStartup(entry: TvCatalogEntry) {
        if (liveLoadingChannel?.let(::tvKey) != tvKey(entry)) return
        liveDeadline?.cancel()
        liveDeadline = app.catalog.scope.launch(Dispatchers.Main.immediate) {
            kotlinx.coroutines.delay(20_000)
            if (liveLoadingChannel?.let(::tvKey) == tvKey(entry)) {
                liveRequest?.cancel(); liveRequest = null; liveLoadingChannel = null; liveDeadline = null
                if (liveChannel?.let(::tvKey) == tvKey(entry)) { player?.pause(); player?.stop() }
                app.tv.failure = localized(R.string.tv_start_timeout, "Il canale non risponde. Riprova o scegli un altro canale.")
            }
        }
    }

    private fun start(item: MediaEntity, uri: android.net.Uri, slot: Int, muted: Boolean, surfaceKey: String, recipe: VideoRecipe?, resume: Boolean = true, advanceQueue: Boolean = false) {
        suspendedForTools = false; manualPip = false; automaticPip = false; automaticForMedia = false; automaticReturnRequested = false
        stickNavigation = VideoStickNavigation()
        seekFeedbackSeconds = 0; seekFeedbackRevision = 0
        resumeRequest = null; replacementRequest = null; replacementPlayer = null
        if (!advanceQueue) {
            context = app.session.mediaContext.toList(); shufflePlayed.clear()
        }
        previewRevision = app.session.previewFocusRevision
        saveAndRelease()
        subtitleTracksAvailable = false
        if (muted) { app.music.finishVideo(musicPause); musicPause = null }
        else if (musicPause == null) musicPause = app.music.pauseForVideo()
        mediaId = item.id; owner = slot; surface = surfaceKey; error = null; wakeNavigation()
        player = ExoPlayer.Builder(app).setWakeMode(if (app.preferences.state.value.videoDuringSleep) C.WAKE_MODE_NETWORK else C.WAKE_MODE_NONE).setAudioAttributes(AudioAttributes.DEFAULT, !muted && !app.preferences.state.value.videoMixAudio && !keepsPlaying)
            .setHandleAudioBecomingNoisy(true).build().also {
            it.volume = if (muted) 0f else volumeLevel
            if (recipe == null) {
                it.setMediaItem(MediaItem.Builder().setUri(uri).setMediaId(item.id)
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(item.name).setIsPlayable(true)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_MOVIE).build()).build())
                it.repeatMode = if (item.convertedGif || flowMode == 2) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                it.seekTo(if (item.convertedGif || !resume || !resumableVideo(item)) 0 else item.videoPosition.coerceAtLeast(0))
            } else {
                it.setVideoEffects(VideoEditing.effects(recipe))
                it.setMediaItem(VideoEditing.mediaItem(uri, recipe))
                it.seekTo(0)
            }
            it.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    // Keep UI cues quiet while a requested video buffers, too.
                    playing.value = !item.convertedGif && player.playWhenReady && player.playbackState != Player.STATE_ENDED && player.playerError == null
                }
                override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED && !item.convertedGif) finished(item.id) }
                override fun onTracksChanged(tracks: Tracks) {
                    subtitleTracksAvailable = tracks.groups.any { group ->
                        group.type == C.TRACK_TYPE_TEXT && group.isSupported
                    }
                    if (app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
                        android.util.Log.d("DSVideo", "subtitleControls=$subtitleTracksAvailable textGroups=${tracks.groups.count { it.type == C.TRACK_TYPE_TEXT }}")
                }
                override fun onPlayerError(error: PlaybackException) {
                    playbackFailed(error)
                }
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
                        android.util.Log.d("DSVideo", "ready=$playWhenReady reason=$reason surface=$surface owner=$owner")
                    playing.value = !item.convertedGif && playWhenReady && it.playbackState != Player.STATE_ENDED && it.playerError == null
                    if (!playWhenReady && reason != Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) app.music.invalidateVideoResume()
                }
                override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
                    if (playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) app.music.invalidateVideoResume()
                }
            })
            playing.value = !item.convertedGif
            it.prepare(); it.play()
        }
        updateNotification()
    }
    private fun saveAndRelease() {
        homePip = false; homeBackgroundSeen = false
        viewerBinding = null
        returnedFromPip = false
        pipOpening = false
        pipReturning = false
        pipReturnPanel = null
        pipActivity?.get()?.finish(); pipActivity = null
        val current = player
        notificationService?.updatePlayer(null)
        val id = mediaId
        if (current != null && id != null && liveChannel == null && surface !in setOf("editor", "received")) {
            val position = current.currentPosition.coerceAtLeast(0).takeUnless { current.playbackState == Player.STATE_ENDED || current.duration > 0 && it >= current.duration - 2000 } ?: 0
            app.catalog.scope.launch { app.catalog.saveVideoPosition(id, position) }
        }
        replacementRequest = null; replacementPlayer = null
        current?.release(); player = null; playing.value = false
        liveChannel = null; liveSessionPlayer = null
    }
    fun mute(slot: Int, muted: Boolean) {
        if (slot != owner) return
        if (muted) {
            player?.setAudioAttributes(AudioAttributes.DEFAULT, false)
            player?.volume = 0f
            app.music.finishVideo(musicPause); musicPause = null
        } else {
            if (musicPause == null) musicPause = app.music.pauseForVideo()
            player?.setAudioAttributes(AudioAttributes.DEFAULT, !app.preferences.state.value.videoMixAudio && !keepsPlaying)
            player?.volume = volumeLevel
        }
    }
    fun stop(slot: Int, expectedId: String? = null, expectedSurface: String? = null) {
        cancelLiveRequest()
        if (pipOpening && slot == pipReturnOwner && !(liveChannel != null && !app.preferences.state.value.tvEnabled)) return
        if (slot != owner || (expectedId != null && mediaId != expectedId) ||
            (expectedSurface != null && surface != expectedSurface)) return
        saveAndRelease()
        mediaId = null; owner = -1; dimNavigation = false
        suspendedForTools = false; manualPip = false; automaticPip = false; automaticForMedia = false; automaticReturnRequested = false
        resumeRequest = null
        subtitleTracksAvailable = false
        app.music.finishVideo(musicPause); musicPause = null
        notificationService?.stopSelf()
        if (app.session.visiblePanels.value == 0) app.trimTvArtworkIfIdle()
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    fun showSubtitleSelection(context: Context, title: CharSequence) {
        val current = player ?: return
        TrackSelectionDialogBuilder(context, title, current, C.TRACK_TYPE_TEXT)
            .setShowDisableOption(true)
            .build()
            .show()
    }
    private var volumeLevel = 1f
    internal fun setVolume(value: Float) {
        volumeLevel = value.coerceIn(0f, 1f)
        player?.volume = volumeLevel
    }
    fun showVolume(context: Context, anchor: android.view.View) {
        val current = player ?: return
        val maximum = 100
        val colors = GalleryThemes.get(app.preferences.state.value).colors
        fun px(dp: Int) = (dp * context.resources.displayMetrics.density).toInt()
        val content = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(px(16), px(12), px(16), px(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = px(16).toFloat(); setColor(colors.surface.toArgb())
                setStroke(px(1), colors.outline.toArgb())
            }
        }
        val title = android.widget.TextView(context).apply { setTextColor(colors.onSurface.toArgb()) }
        val slider = android.widget.SeekBar(context).apply {
            max = maximum; progress = (current.volume * maximum).toInt()
            contentDescription = localized(R.string.video_volume, "Volume")
            progressTintList = android.content.res.ColorStateList.valueOf(colors.primary.toArgb())
            thumbTintList = progressTintList
        }
        fun showValue() { title.text = localized(R.string.video_volume, "Volume") + " · ${slider.progress * 100 / maximum}%" }
        showValue()
        slider.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar) = Unit
            override fun onProgressChanged(seekBar: android.widget.SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) setVolume(value.toFloat() / maximum)
                showValue()
            }
        })
        content.addView(title); content.addView(slider, android.widget.LinearLayout.LayoutParams(-1, px(48)))
        val popup = android.widget.PopupWindow(content, px(280), android.view.ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            elevation = px(8).toFloat(); isOutsideTouchable = true
        }
        val attachment = object : android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: android.view.View) = Unit
            override fun onViewDetachedFromWindow(view: android.view.View) { popup.dismiss() }
        }
        anchor.addOnAttachStateChangeListener(attachment)
        popup.setOnDismissListener { anchor.removeOnAttachStateChangeListener(attachment) }
        val availableWidth = anchor.rootView.width.coerceAtLeast(px(32))
        popup.width = minOf(px(280), availableWidth - px(16))
        content.measure(android.view.View.MeasureSpec.makeMeasureSpec(popup.width, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(anchor.rootView.height, android.view.View.MeasureSpec.AT_MOST))
        val location = IntArray(2); anchor.getLocationOnScreen(location)
        val left = location[0].coerceIn(px(8), (availableWidth - popup.width - px(8)).coerceAtLeast(px(8)))
        val top = (location[1] - content.measuredHeight - px(8)).coerceAtLeast(px(8))
        popup.showAtLocation(anchor, android.view.Gravity.TOP or android.view.Gravity.LEFT, left, top)
        slider.requestFocus()
    }
    fun showAudioSelection(context: Context) {
        val current = player ?: return
        TrackSelectionDialogBuilder(context, context.getString(androidx.media3.ui.R.string.exo_track_selection_title_audio), current, C.TRACK_TYPE_AUDIO).build().show()
    }
    fun showPlaybackSettings(context: Context, anchor: android.view.View) {
        val current = player ?: return
        android.widget.PopupMenu(context, anchor).apply {
            val modes = menu.addSubMenu(localized(R.string.video_flow_mode, "Modalità di riproduzione"))
            listOf(0 to R.string.video_flow_once, 2 to R.string.video_flow_repeat, 1 to R.string.video_flow_playlist, 3 to R.string.video_flow_random).forEach { (mode, title) ->
                modes.add(1, mode, android.view.Menu.NONE, context.getString(title)).apply {
                    isCheckable = true; isChecked = flowMode == mode
                    setOnMenuItemClickListener { setFlow(mode); true }
                }
            }
            modes.setGroupCheckable(1, true, true)
            val speeds = menu.addSubMenu(context.getString(androidx.media3.ui.R.string.exo_controls_playback_speed))
            for (speed in listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f)) speeds.add("${speed}×").apply {
                isCheckable = true; isChecked = current.playbackParameters.speed == speed
                setOnMenuItemClickListener { current.setPlaybackSpeed(speed); true }
            }
            show()
        }
    }
}
