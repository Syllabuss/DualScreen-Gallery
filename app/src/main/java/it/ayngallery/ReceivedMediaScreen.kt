package it.ayngallery

import android.graphics.drawable.Animatable
import android.provider.DocumentsContract
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import coil3.asDrawable
import coil3.compose.AsyncImage
import coil3.decode.BitmapFactoryDecoder
import coil3.request.ImageRequest
import coil3.request.CachePolicy
import coil3.gif.repeatCount
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

@Composable
fun ReceivedMediaScreen(activity: PanelActivity, encoded: String, prefs: GalleryPreferences, suspended: Boolean) {
    val app = activity.application as GalleryApp
    val request = remember(encoded) { ReceivedMedia.decode(encoded) }
    var raw by rememberSaveable(encoded) { mutableStateOf<String?>(null) }
    var selected by rememberSaveable(encoded) { mutableIntStateOf(0) }
    var opened by rememberSaveable(encoded) { mutableStateOf(false) }
    var error by rememberSaveable(encoded) { mutableStateOf<String?>(null) }
    var confirmingLink by rememberSaveable(encoded) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var read by remember { mutableIntStateOf(0) }
    var readable by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    var openJob by remember { mutableStateOf<Job?>(null) }
    val root = remember(raw) { raw?.let(::JSONObject) }
    val files = remember(raw) { root?.getJSONArray("files")?.let { values -> (0 until values.length()).map { IncomingFile.decode(values.getJSONObject(it).toString()) } }.orEmpty() }
    val file = files.getOrNull(selected)
    val token = remember(encoded, file?.id) { UUID.randomUUID().toString() }
    val started by activity.isStartedFlow.collectAsState()
    LaunchedEffect(encoded) {
        if (raw == null) try { raw = IncomingMedia.inspected(activity, request.selection) { read = it } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: localized(R.string.text_content_unavailable_df9efa, "Contenuti non disponibili") }
    }
    LaunchedEffect(file, started, activity.focused, revision) {
        readable = false
        if (file != null && started) {
            try { IncomingMedia.readable(activity, file); readable = true }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: localized(R.string.text_temporary_access_expired_d75210, "Accesso temporaneo scaduto"); opened = false; app.music.finishPreview(token); app.video.stop(activity.slot, file.id, "received") }
        }
    }
    DisposableEffect(encoded, file?.id) {
        onDispose { openJob?.cancel(); app.music.finishPreview(token); app.video.stop(activity.slot, file?.id, "received") }
    }
    LaunchedEffect(started, suspended) {
        if (!started || suspended) { openJob?.cancel(); app.video.stop(activity.slot, file?.id, "received"); if (app.music.previewToken.value == token) app.music.pause() }
    }
    fun choose(index: Int) {
        openJob?.cancel(); app.music.finishPreview(token); app.video.stop(activity.slot, file?.id, "received")
        selected = index; opened = false; error = null; readable = false
    }
    DisposableEffect(activity, files, selected) {
        val handler: (PadAction) -> Boolean = { action -> when (action) {
            PadAction.MEDIA_NEXT, PadAction.MEDIA_PREVIOUS -> { if (files.isNotEmpty()) choose(Math.floorMod(selected + if (action == PadAction.MEDIA_NEXT) 1 else -1, files.size)); true }
            PadAction.DELETE -> { error = localized(R.string.text_temporary_content_is_read_only_link_the_file_and_b53bf7, "Il contenuto temporaneo è in lettura. Collega il file e autorizza la sua cartella per modificarlo."); true }
            PadAction.FAVORITE, PadAction.PIN -> { error = localized(R.string.text_link_the_file_or_import_a_library_copy_to_use_fa_29ea7c, "Collega il file o importa una copia nella libreria per usare Preferiti e confronto."); true }
            PadAction.MENU -> { opened = false; true }
            PadAction.ZOOM_IN, PadAction.ZOOM_OUT, PadAction.PAN_UP, PadAction.PAN_DOWN, PadAction.PAN_LEFT, PadAction.PAN_RIGHT -> {
                activity.padTransform?.invoke(action, file?.id); true
            }
            else -> false
        } }
        activity.receivedAction = handler
        onDispose { if (activity.receivedAction === handler) activity.receivedAction = null }
    }
    fun open() {
        val target = file ?: return
        openJob?.cancel()
        openJob = scope.launch {
            busy = true; error = null
            try {
                IncomingMedia.readable(activity, target)
                if (target.kind == "AUDIO") app.music.previewReceived(target, token)
                opened = true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: localized(R.string.text_could_not_open_aa1697, "Apertura non riuscita") }
            finally { busy = false }
        }
    }
    Column(Modifier.fillMaxSize().padding(12.dp).testTag("received-screen"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(localized(R.string.text_received_content_cb883c, "Contenuti ricevuti"), style = MaterialTheme.typography.titleLarge)
        if (root == null) {
            if (!prefs.reducedMotion) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(localized(R.string.text_reading_1_s_2_s_1a4230, "Lettura · %1\$s / %2\$s", read, request.selection.uris.size))
        } else if (files.isNotEmpty()) LazyRow(Modifier.heightIn(max = 100.dp).testTag("received-files"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(files, key = { _, value -> value.id }) { index, value -> FilterChip(index == selected, { choose(index) },
                label = { Text(value.name, maxLines = 2) }, modifier = Modifier.widthIn(max = 220.dp).testTag("received-file:${value.id}")) }
        }
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            if (opened && file != null && readable) ReceivedSurface(activity, file, prefs, started && !suspended, token)
            else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                file?.let { Text(it.name, style = MaterialTheme.typography.titleMedium); Text(fileSize(it.size), style = MaterialTheme.typography.labelLarge) }
                Text(localized(R.string.text_open_to_view_this_file_with_temporary_access_imp_45ef00, "Apri per consultare questo file con accesso temporaneo. Importa una copia per conservarne i byte nella cartella che scegli. Mantieni collegamento conserva soltanto l'accesso al documento, quando concesso dal provider."))
                Text(localized(R.string.text_no_provider_scanning_automatic_copying_or_adding_0604c6, "Nessuna scansione del provider, copia automatica o aggiunta ai Preferiti. Il collegamento non garantisce la disponibilità offline."), style = MaterialTheme.typography.bodySmall)
                root?.getJSONArray("errors")?.let { values -> if (values.length() > 0) Text((0 until values.length()).joinToString("\n") { values.getString(it) }, Modifier.testTag("received-unavailable"), color = MaterialTheme.colorScheme.error) }
            }
        }
        error?.let { Text(it, Modifier.testTag("received-error"), color = MaterialTheme.colorScheme.error, maxLines = 3) }
        GalleryButton(if (opened) localized(R.string.text_reopen_temporary_file_32d294, "Riapri il file temporaneo") else localized(R.string.text_open_12abcf, "Apri"), Modifier.fillMaxWidth().testTag("received-open"), enabled = !busy && file != null && readable, primary = true) { open() }
        GalleryButton(localized(R.string.text_import_a_copy_42d133, "Importa una copia…"), Modifier.fillMaxWidth().testTag("received-import"), enabled = !busy && files.isNotEmpty()) {
            openJob?.cancel(); app.music.finishPreview(token); app.video.stop(activity.slot, file?.id, "received")
            activity.windowModel.incoming(IncomingSelection(request.selection.id, files.map { it.uri }).encode())
        }
        val linkable = file != null && request.canPersist && DocumentsContract.isDocumentUri(activity, file.uri.toUri())
        GalleryButton(if (linkable) localized(R.string.text_keep_link_1e44fb, "Mantieni collegamento…") else localized(R.string.text_choose_file_to_link_4d549c, "Scegli il file per collegarlo…"), Modifier.fillMaxWidth().testTag("received-link"), enabled = !busy && file != null && readable) {
            if (linkable) confirmingLink = true else activity.chooseDocument()
        }
        if (!readable && file != null) TextButton({ error = null; revision++ }, Modifier.fillMaxWidth().testTag("received-retry")) { Text(localized(R.string.text_check_access_again_80d1e3, "Ricontrolla accesso")) }
    }
    if (confirmingLink && file != null) {
        val target = file
        GalleryAlertDialog(onDismissRequest = { if (!busy) confirmingLink = false }, title = { Text(localized(R.string.text_keep_link_0fa353, "Mantieni collegamento?")) }, text = {
            Text(localized(R.string.text_1_s_n_nread_only_access_to_this_document_only_no_216299, "%1\$s\n\nSolo questo documento, in lettura. Nessuna copia o scansione delle altre cartelle. Il provider può rendere il file indisponibile o revocare l'accesso; per conservarne i byte usa Importa una copia.", target.name),
                Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()))
        }, dismissButton = { TextButton({ confirmingLink = false }, enabled = !busy) { Text(localized(R.string.text_cancel_18c9d9, "Annulla")) } }, confirmButton = {
            TextButton({ busy = true; scope.launch {
                try {
                    val id = DocumentLinks(app.catalog).link(target, request.canPersist)
                    app.music.finishPreview(token); app.video.stop(activity.slot, target.id, "received")
                    app.session.noticeSource(localized(R.string.text_read_only_link_retained_original_in_provider_no_935dea, "Collegamento conservato in lettura; originale nel provider, nessuna copia."))
                    activity.windowModel.received(null); activity.windowModel.go(if (target.kind == "AUDIO") "MUSIC" else "PHOTO")
                    app.session.selectMedia(id)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = failure.message ?: localized(R.string.text_link_not_retained_91c97f, "Collegamento non conservato"); confirmingLink = false }
                finally { busy = false }
            } }, enabled = !busy && readable, modifier = Modifier.testTag("received-link-confirm")) { Text(localized(R.string.text_keep_link_c839e2, "Conserva collegamento")) }
        })
    }
}

/** Uses the app's single video player and music service; temporary image decoding retains no disk copy. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun ReceivedSurface(activity: PanelActivity, file: IncomingFile, prefs: GalleryPreferences, active: Boolean, token: String) {
    val app = activity.application as GalleryApp
    val scope = rememberCoroutineScope()
    var videoJob by remember(file.id) { mutableStateOf<Job?>(null) }
    DisposableEffect(file.id, active) {
        onDispose { videoJob?.cancel(); app.video.stop(activity.slot, file.id, "received") }
    }
    val playback by app.music.state.collectAsState()
    val previewToken by app.music.previewToken.collectAsState()
    var muted by rememberSaveable(file.id) { mutableStateOf(false) }
    var error by remember(file.id) { mutableStateOf<String?>(null) }
    var loading by remember(file.id) { mutableStateOf(true) }
    var drawable by remember(file.id) { mutableStateOf<Animatable?>(null) }
    var zoom by rememberSaveable(file.id) { mutableFloatStateOf(1f) }
    var x by rememberSaveable(file.id) { mutableFloatStateOf(0f) }
    var y by rememberSaveable(file.id) { mutableFloatStateOf(0f) }
    var dimensions by remember(file.id) { mutableStateOf(Size(1f, 1f)) }
    DisposableEffect(activity, file.id, file.kind, active, zoom, x, y) {
        val handler: (PadAction, String?) -> Unit = { action, id ->
            if (active && id == file.id && file.kind in setOf("IMAGE", "GIF")) {
                val next = ViewTransform(zoom, x, y).fromPad(action)
                zoom = next.zoom; x = next.x; y = next.y
            }
        }
        activity.padTransform = handler
        onDispose { if (activity.padTransform === handler) activity.padTransform = null }
    }
    val animate = file.kind == "GIF" && active && !prefs.gameMode
    DisposableEffect(drawable, animate) { if (animate) drawable?.start() else drawable?.stop(); onDispose { drawable?.stop() } }
    if (!active) { EmptyGallery(localized(R.string.text_opening_paused_f249fe, "Apertura sospesa"), localized(R.string.text_content_becomes_visible_when_this_panel_is_activ_89c5d8, "Il contenuto torna visibile quando questo pannello è attivo.")); return }
    Column(Modifier.fillMaxSize().testTag("received-viewer")) {
        if (file.kind == "AUDIO") {
            Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                GalleryIcon(R.drawable.ph_music_notes, null, Modifier.size(64.dp))
                Text(file.name, style = MaterialTheme.typography.titleMedium)
                Text(localized(R.string.text_temporary_opening_shared_player_4fca7f, "Apertura temporanea · player comune"), style = MaterialTheme.typography.labelMedium)
                if (previewToken == token) {
                    Text("${formatTime(playback.position)} / ${formatTime(playback.duration)}")
                    GalleryButton(if (playback.playing) localized(R.string.text_pause_9bd4d1, "Pausa") else localized(R.string.text_play_da1576, "Riproduci"), Modifier.testTag("received-audio-toggle")) { app.music.toggle() }
                } else Text(localized(R.string.text_playback_was_replaced_by_another_music_command_eec62d, "La riproduzione è stata sostituita da un altro comando musicale."))
                playback.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            return
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds().background(MaterialTheme.colorScheme.surfaceVariant).testTag("received-surface"), contentAlignment = Alignment.Center) {
            if (file.kind == "VIDEO" && app.video.owner == activity.slot && app.video.mediaId == file.id && app.video.surface == "received" && app.video.player != null) {
                AndroidView(factory = { PlayerView(it).apply { useController = true; setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING) } },
                    update = { it.player = app.video.player }, onRelease = { it.player = null }, modifier = Modifier.fillMaxSize())
                app.video.error?.let { Text(it, Modifier.align(Alignment.TopCenter), color = MaterialTheme.colorScheme.error) }
            } else {
                val w = with(LocalDensity.current) { maxWidth.toPx() }; val h = with(LocalDensity.current) { maxHeight.toPx() }
                val fit = min(w / dimensions.width, h / dimensions.height)
                val sw = (dimensions.width * fit * zoom).coerceAtLeast(1f); val sh = (dimensions.height * fit * zoom).coerceAtLeast(1f)
                val lx = max(0f, (sw - w) / 2); val ly = max(0f, (sh - h) / 2)
                val image = remember(file, animate, prefs.reducedMotion) { ImageRequest.Builder(activity).data(file.uri).repeatCount(-1)
                    .memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED).crossfade(if (prefs.reducedMotion) 0 else 220)
                    .apply { if (file.kind == "VIDEO") decoderFactory(VideoFrameDecoder.Factory()); if (file.kind == "GIF" && !animate) decoderFactory(BitmapFactoryDecoder.Factory()) }.build() }
                val base = MaterialTheme.colorScheme.surfaceVariant
                Canvas(Modifier.fillMaxSize()) { drawTransparencyGrid(Offset.Zero, size, base) }
                AsyncImage(image, file.name, Modifier.fillMaxSize().pointerInput(file.id, w, h, dimensions) {
                    if (file.kind !in setOf("IMAGE", "GIF")) return@pointerInput
                    mediaTouchGestures(zoomed = { zoom > 1f }, start = {}, transform = { _, pan, factor ->
                        val next = (zoom * factor).coerceIn(.5f, 8f)
                        val nextX = max(0f, (dimensions.width * fit * next - w) / 2)
                        val nextY = max(0f, (dimensions.height * fit * next - h) / 2)
                        val nextW = (dimensions.width * fit * next).coerceAtLeast(1f)
                        val nextH = (dimensions.height * fit * next).coerceAtLeast(1f)
                        zoom = next
                        x = (x + pan.x / nextW).coerceIn(-nextX / nextW, nextX / nextW)
                        y = (y + pan.y / nextH).coerceIn(-nextY / nextH, nextY / nextH)
                    }, swipe = { direction -> activity.performTouchAction(if (direction > 0) TouchAction.MEDIA_NEXT else TouchAction.MEDIA_PREVIOUS) })
                }.pointerInput(file.id, prefs.touch) {
                    if (file.kind !in setOf("IMAGE", "GIF")) return@pointerInput
                    fun gesture(action: TouchAction) {
                        if (action == TouchAction.TOGGLE_ZOOM) { zoom = if (zoom < 1f) 1f else if (zoom == 1f) 2f else 1f; x = 0f; y = 0f }
                        else activity.performTouchAction(action)
                    }
                    detectTapGestures(onDoubleTap = prefs.touch.doubleTap.takeIf { it != TouchAction.NONE }?.let { action -> { gesture(action) } },
                        onLongPress = prefs.touch.longPress.takeIf { it != TouchAction.NONE }?.let { action -> { gesture(action) } })
                }
                    .graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = (x * sw).coerceIn(-lx, lx); translationY = (y * sh).coerceIn(-ly, ly) }, contentScale = ContentScale.Fit,
                    onLoading = { loading = true }, onError = { loading = false; error = localized(R.string.text_media_unreadable_access_expired_or_format_cannot_563bbc, "Media non leggibile: accesso scaduto o formato non decodificabile") },
                    onSuccess = { loading = false; dimensions = Size(it.result.image.width.toFloat().coerceAtLeast(1f), it.result.image.height.toFloat().coerceAtLeast(1f)); drawable = it.result.image.asDrawable(activity.resources) as? Animatable })
                if (loading && !prefs.reducedMotion) CircularProgressIndicator(Modifier.size(28.dp))
                if (file.kind == "VIDEO") FilledIconButton({ videoJob?.cancel(); videoJob = scope.launch {
                    try { app.video.playReceived(file, activity.slot, muted) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = failure.message ?: localized(R.string.text_video_cannot_be_opened_23b178, "Video non apribile") }
                } }, Modifier.size(64.dp)) { GalleryIcon(R.drawable.ph_play, localized(R.string.text_play_video_ba31ae, "Riproduci video"), Modifier.size(32.dp)) }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, maxLines = 3) }
        if (file.kind == "VIDEO") GalleryButton(if (muted) localized(R.string.text_muted_2228e3, "Silenzioso") else localized(R.string.text_sound_on_935c7c, "Con audio")) { muted = !muted; app.video.mute(activity.slot, muted) }
        else TransformControls(ViewTransform(zoom), change = { zoom = it.zoom; x = 0f; y = 0f })
    }
}
