package it.ayngallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.conflate

/** The editor's partner display follows its exact range, cursor and image recipe. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun MediaEditPreviewPanel(activity: PanelActivity, editor: MediaEditorPanel, data: CatalogSnapshot, prefs: GalleryPreferences) {
    val app = activity.application as GalleryApp
    val target = remember(editor.request) { ExternalRequest.decode(editor.request).targets.single() }
    val item = data.byId[target.id]?.takeIf { data.usable(it) && target.matches(it) }
    val started by activity.isStartedFlow.collectAsState()
    val videoRecipe = remember(editor.recipe) { editor.recipe?.let { runCatching { VideoRecipe.decode(it) }.getOrNull() } }
    var preview by remember(target.id) { mutableStateOf<ImagePreview?>(null) }
    var videoInfo by remember(target.id) { mutableStateOf<VideoInfo?>(null) }
    var error by remember(target.id) { mutableStateOf(false) }
    val currentEditor by rememberUpdatedState(editor)

    LaunchedEffect(target.id, item?.uri, editor.playing, started, editor.recipe?.let { runCatching { ImageRecipe.decode(it).gifFrame }.getOrNull() }) {
        if (!started || item == null || target.kind == "AUDIO" || (target.kind == "VIDEO" && editor.playing)) return@LaunchedEffect
        suspend fun load(position: Long) {
            try {
                val raw = currentEditor.recipe
                val loaded = withContext(Dispatchers.IO + NonCancellable) {
                    val uri = app.catalog.uri(item, data)
                    if (target.kind == "VIDEO") {
                        val details = videoInfo ?: VideoEditing.inspect(app, uri)
                        val range = raw?.let { VideoRecipe.decode(it) }
                        val first = range?.start ?: 0L
                        val last = (range?.end ?: details.duration) - 1
                        VideoEditing.poster(app, uri, details, position.coerceIn(first, last.coerceAtLeast(first))) to details
                    } else ImageEditing.preview(app.contentResolver, uri, if (target.kind == "GIF") "GIF_FIRST_FRAME" else "STATIC", raw?.let { ImageRecipe.decode(it).gifFrame } ?: 0) to null
                }
                if (!currentCoroutineContext().isActive) loaded.first.bitmap.recycle()
                else { preview = loaded.first; videoInfo = loaded.second; error = false }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = true }
        }
        // Decode serially, dropping obsolete cursor positions without waiting for a drag to finish.
        if (target.kind == "VIDEO") snapshotFlow { currentEditor.previewPosition }.conflate().collect { cursor ->
            load(cursor); delay(80)
        } else load(0)
    }
    DisposableEffect(preview) { val image = preview; onDispose { image?.bitmap?.recycle() } }

    LaunchedEffect(target.id, videoRecipe, editor.playing, started) {
        if (target.kind != "VIDEO") return@LaunchedEffect
        if (editor.playing && started && item != null && videoRecipe != null) {
            app.video.play(item, activity.slot, videoRecipe.mute, "editor", videoRecipe)
            app.video.player?.seekTo((editor.previewPosition - videoRecipe.start).coerceIn(0, videoRecipe.length))
        } else app.video.stop(activity.slot, target.id, "editor")
    }
    LaunchedEffect(editor.seekRevision, editor.playing) {
        if (editor.playing && videoRecipe != null && app.video.owner == activity.slot) {
            val next = (editor.previewPosition - videoRecipe.start).coerceIn(0, videoRecipe.length)
            app.video.player?.seekTo(next)
        }
    }
    DisposableEffect(activity, target.id) { onDispose { app.video.stop(activity.slot, target.id, "editor") } }
    DisposableEffect(app.video.player) {
        val player = app.video.player
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && app.video.surface == "editor" && app.video.owner == activity.slot)
                    app.session.updateMediaEditorPreview(playing = false)
            }
        }
        player?.addListener(listener)
        onDispose { player?.removeListener(listener) }
    }

    Box(Modifier.fillMaxSize().background(Color.Black).testTag("media-edit-preview"), contentAlignment = Alignment.Center) {
        when {
            item == null -> Text(localized(R.string.text_media_unavailable_91dca9, "Media non disponibile"), color = Color.White)
            target.kind == "AUDIO" -> Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                MediaThumbnail(item, data, app.catalog, prefs, Modifier.fillMaxWidth().weight(1f), animated = false)
                Text(target.name, color = Color.White, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                Text(segmentTime(editor.previewPosition), modifier = Modifier.padding(12.dp), color = Color.White.copy(alpha = .8f))
            }
            target.kind == "VIDEO" && editor.playing && app.video.owner == activity.slot -> AndroidView(
                factory = { PlayerView(it).apply { useController = false; setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING) } },
                update = { it.player = app.video.player }, onRelease = { it.player = null }, modifier = Modifier.fillMaxSize())
            preview != null -> {
                val image = preview!!
                if (target.kind == "VIDEO" && videoRecipe != null) CropCanvas(image, videoRecipe.crop(), editor.showResult,
                    Modifier.fillMaxSize().padding(12.dp).testTag("video-edit-partner-canvas"), editable = !editor.showResult, guideGrid = true) {
                    if (it.right - it.left >= .01f && it.bottom - it.top >= .01f)
                        app.session.updateMediaEditorRecipe(videoRecipe.copy(left = it.left, top = it.top, right = it.right, bottom = it.bottom).encode())
                }
                else if (target.kind != "VIDEO") {
                    val initial = if (target.kind == "GIF") "GIF_FIRST_FRAME" else "STATIC"
                    val recipe = editor.recipe?.let { runCatching { ImageRecipe.decode(it) }.getOrNull() } ?: ImageRecipe(input = initial)
                    CropCanvas(image, recipe, editor.showResult, Modifier.fillMaxSize().padding(12.dp).testTag("image-edit-partner-canvas"),
                        editable = !editor.showResult, filter = recipe.filter, guideGrid = true) { app.session.updateMediaEditorRecipe(it.encode()) }
                }
            }
            error -> Text(localized(R.string.text_preview_unavailable_135044, "Anteprima non disponibile"), color = Color.White)
            else -> if (!prefs.reducedMotion) CircularProgressIndicator(color = Color.White)
        }
        if (item != null && editor.recipe != null && target.kind in setOf("VIDEO", "AUDIO")) FilledIconButton({
            val start = videoRecipe?.start ?: runCatching { AudioRecipe.decode(editor.recipe).start }.getOrDefault(0)
            val end = videoRecipe?.end ?: runCatching { AudioRecipe.decode(editor.recipe).end }.getOrDefault(Long.MAX_VALUE)
            app.session.updateMediaEditorPreview(position = if (editor.previewPosition >= end) start else editor.previewPosition,
                playing = !editor.playing)
        }, Modifier.align(Alignment.BottomEnd).padding(18.dp).size(64.dp).testTag("editor-preview-play")) {
            GalleryIcon(if (editor.playing) R.drawable.ph_pause else R.drawable.ph_play,
                if (editor.playing) localized(R.string.text_stop_preview_049a11, "Ferma anteprima") else localized(R.string.text_play_segment_47e94f, "Riproduci segmento"), Modifier.size(32.dp))
        }
    }
}
