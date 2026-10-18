package it.ayngallery

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.launch

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun TvVideoPreview(activity: PanelActivity) {
    val app = activity.application as GalleryApp
    val video = app.video
    val prefs by app.preferences.state.collectAsStateWithLifecycle()
    val entry = video.liveChannel ?: return
    val player = video.player ?: return
    val livePlayer = remember(player) { TvLiveCommands(player) }
    val scope = rememberCoroutineScope()

    val record = app.tv.store.channels.firstOrNull { it.matches(entry) }
    val saved = record != null
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(player) { visible = false }
    if (video.floating || video.pipOpening) {
        EmptyGallery(localized(R.string.video_pip_active, "Riproduzione in finestra"),
            localized(R.string.video_pip_description, "Il video continua nella finestra Android."),
            R.drawable.ph_frame_corners, action = localized(R.string.tv_return, "Riporta su questo schermo"),
            featured = true, animateGlow = !prefs.reducedMotion, iconBelowTitle = true, actionIcon = R.drawable.ph_frame_corners) {
                video.returnToGallery(activity)
            }
        return
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { context ->
            (android.view.LayoutInflater.from(context).inflate(R.layout.gallery_preview_video, null) as GalleryVideoView).apply {
                setKeepContentOnPlayerReset(true)
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                setShowNextButton(false); setShowPreviousButton(false); setShowRewindButton(false); setShowFastForwardButton(false)
                setShowSubtitleButton(false); setShowShuffleButton(false)
                controllerAutoShow = false; controllerShowTimeoutMs = 5000; hideController()
                listOf(androidx.media3.ui.R.id.exo_progress, androidx.media3.ui.R.id.exo_position, androidx.media3.ui.R.id.exo_duration,
                    androidx.media3.ui.R.id.exo_settings).forEach { findViewById<android.view.View>(it)?.visibility = android.view.View.GONE }
                findViewById<android.widget.LinearLayout>(androidx.media3.ui.R.id.exo_time)?.let { row ->
                    for (i in 0 until row.childCount) (row.getChildAt(i) as? android.widget.TextView)?.visibility = android.view.View.GONE
                }
                setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visible = it == android.view.View.VISIBLE })
            }
        }, update = { view ->
            if (view.player !== livePlayer) { view.player = livePlayer; view.hideController() }
            view.onDoubleTapSeek = null; view.navigate = {}
            view.onPip = { video.openPip(activity) }
            view.onVolume = { video.showVolume(activity, it) }
            view.onAudio = null; view.onSubtitles = null; view.onSettings = null
        }, onRelease = { it.player = null }, modifier = Modifier.fillMaxSize())
        if (visible) CompositionLocalProvider(LocalContentColor provides androidx.compose.ui.graphics.Color.White) {
        Row(Modifier.align(Alignment.TopStart).fillMaxWidth().background(androidx.compose.ui.graphics.Brush.verticalGradient(
            listOf(androidx.compose.ui.graphics.Color.Black.copy(alpha = .7f), androidx.compose.ui.graphics.Color.Transparent))).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { app.tv.requestFavorite(entry) }) {
                TvFavoriteStar(record?.favorite == true, Modifier.size(24.dp), identity = tvKey(entry))
            }
            Text(record?.name ?: entry.name, Modifier.weight(1f), color = androidx.compose.ui.graphics.Color.White, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if (app.tv.browsing) TvNetworkInfo() else GalleryIconButton(R.drawable.ph_moon_stars, localized(R.string.video_dim_navigation, "Oscura lo schermo di navigazione")) { video.dimNavigation = true }
            GalleryIconButton(R.drawable.ph_x, localized(R.string.text_close_32d407, "Chiudi"), Modifier.testTag("tv-close-player")) { video.closeLive() }
        }
        }
        TvChannelBanner(app)
    }
}
