package it.ayngallery

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.conflate

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun VideoEditorScreen(activity: PanelActivity, encoded: String, data: CatalogSnapshot, prefs: GalleryPreferences,
    dismiss: () -> Unit, accepted: () -> Unit, updateRecipe: (String) -> Unit = {}) {
    val target = remember(encoded) { ExternalRequest.decode(encoded).targets.single() }
    val app = activity.application as GalleryApp
    var raw by rememberSaveable(encoded) { mutableStateOf<String?>(null) }
    val paired = app.session.canSwap() && app.session.hasOtherPanel(activity)
    LaunchedEffect(app.session.mediaEditor?.recipe) { app.session.mediaEditor?.recipe?.let { if (it != raw) raw = it } }
    val recipe = remember(raw) { raw?.let(VideoRecipe::decode) }
    LaunchedEffect(raw) { raw?.let(updateRecipe) }
    var baseName by rememberSaveable(encoded) { mutableStateOf(target.name.substringBeforeLast('.', target.name).take(130) + localized(R.string.text_segment_8d73b9, "-segmento")) }
    var result by rememberSaveable(encoded) { mutableStateOf(false) }
    LaunchedEffect(result) { app.session.updateMediaEditorPreview(result = result) }
    var preview by remember(encoded) { mutableStateOf<ImagePreview?>(null) }
    var info by remember(encoded) { mutableStateOf<VideoInfo?>(null) }
    var previewPosition by rememberSaveable(encoded) { mutableLongStateOf(0L) }
    LaunchedEffect(previewPosition) { app.session.updateMediaEditorPreview(position = previewPosition) }
    var error by rememberSaveable(encoded) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var consent by remember { mutableStateOf(false) }
    var audioOnly by rememberSaveable(encoded) { mutableStateOf(false) }
    var startText by rememberSaveable(encoded) { mutableStateOf("0") }
    var endText by rememberSaveable(encoded) { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val sourceReady = data.byId[target.id]?.let { data.usable(it) && target.matches(it) && it.kind == "VIDEO" } == true
    val rangeApplied = recipe?.let { r -> runCatching { VideoRecipe.millis(startText, r.duration) == r.start && VideoRecipe.millis(endText, r.duration) == r.end }.getOrDefault(false) } == true
    val started by activity.isStartedFlow.collectAsState()
    val playing = app.session.mediaEditor?.playing == true && app.video.mediaId == target.id && app.video.surface == "editor" && app.video.player != null
    fun stop() { app.video.stop(app.video.owner, target.id, "editor"); app.session.updateMediaEditorPreview(playing = false) }
    fun change(value: VideoRecipe, seekTo: Long? = null) {
        if (!busy) { stop(); previewPosition = (seekTo ?: previewPosition).coerceIn(value.start, value.end); raw = value.encode(); startText = (value.start / 1000.0).toString(); endText = (value.end / 1000.0).toString(); error = null }
    }
    fun editRange(firstText: String, lastText: String) {
        val r = recipe ?: return
        stop(); startText = firstText; endText = lastText
        try {
            val first = VideoRecipe.millis(firstText, r.duration); val last = VideoRecipe.millis(lastText, r.duration)
            val changed = r.copy(start = first, end = last)
            raw = changed.encode(); previewPosition = (if (first != r.start) first else last).coerceIn(first, last); error = null
        } catch (failure: IllegalArgumentException) { error = localized(R.string.text_invalid_range_7d3864, "Intervallo non valido") }
    }
    DisposableEffect(activity, target.id, sourceReady, started) {
        if (!sourceReady || !started) stop()
        onDispose { stop() }
    }
    DisposableEffect(app.video.player, paired) {
        val player = app.video.player
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (!paired && state == androidx.media3.common.Player.STATE_ENDED && app.video.surface == "editor" && app.video.owner == activity.slot) {
                    recipe?.let { previewPosition = it.end }
                    app.session.updateMediaEditorPreview(playing = false)
                }
            }
        }
        if (!paired) player?.addListener(listener)
        onDispose { player?.removeListener(listener) }
    }
    LaunchedEffect(encoded) {
        try {
            val details = withContext(Dispatchers.IO) {
                val fresh = app.catalog.readMediaSnapshot(target.id)
                val item = fresh.byId[target.id]?.takeIf { fresh.usable(it) && target.matches(it) && it.kind == "VIDEO" } ?: throw SourceChanged()
                VideoEditing.inspect(app, app.catalog.uri(item, fresh))
            }
            ensureActive(); info = details
            if (raw == null) { val initial = VideoRecipe(details.duration); raw = initial.encode(); endText = (details.duration / 1000.0).toString() }
            else require(recipe?.duration == details.duration) { localized(R.string.text_the_source_duration_has_changed_reopen_the_edito_be0a12, "La durata della sorgente è cambiata: riapri l'editor") }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: localized(R.string.text_video_unreadable_041806, "Video non leggibile") }
    }
    val currentRecipe by rememberUpdatedState(recipe)
    LaunchedEffect(encoded, info, playing, paired) {
        if (playing || paired) return@LaunchedEffect
        val details = info ?: return@LaunchedEffect
        snapshotFlow { previewPosition }.conflate().collect { cursor ->
            try {
                val range = currentRecipe
                val loaded = withContext(Dispatchers.IO + NonCancellable) {
                    val fresh = app.catalog.readMediaSnapshot(target.id)
                    val item = fresh.byId[target.id]?.takeIf { fresh.usable(it) && target.matches(it) && it.kind == "VIDEO" } ?: throw SourceChanged()
                    val first = range?.start ?: 0L
                    val last = (range?.end ?: details.duration) - 1
                    VideoEditing.poster(app, app.catalog.uri(item, fresh), details, cursor.coerceIn(first, last.coerceAtLeast(first)))
                }
                if (!currentCoroutineContext().isActive) loaded.bitmap.recycle() else preview = loaded
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: localized(R.string.text_preview_frame_unavailable_3d562e, "Fotogramma di anteprima non disponibile") }
            delay(80)
        }
    }
    DisposableEffect(preview) { val current = preview; onDispose { current?.bitmap?.recycle() } }
    LaunchedEffect(playing, recipe?.start, recipe?.end) {
        if (playing) while (isActive) {
            val r = recipe ?: break
            previewPosition = (r.start + (app.video.player?.currentPosition ?: 0L)).coerceIn(r.start, r.end)
            delay(100)
        }
    }
    var pendingRecipe by rememberSaveable(encoded) { mutableStateOf<String?>(null) }
    var pendingName by rememberSaveable(encoded) { mutableStateOf("") }
    val pickDestination = rememberEditorDestinationPicker(activity, data, target, SourceRole.VISUAL,
        busy = { busy = it }, error = { error = it }) { album ->
        if (pendingName.endsWith(".mp3")) app.files.enqueueAudioExport(target, AudioRecipe.decode(requireNotNull(pendingRecipe)), album, pendingName)
        else app.files.enqueueVideoExport(target, VideoRecipe.decode(requireNotNull(pendingRecipe)), album, pendingName)
        accepted()
    }
    fun submit(allowGame: Boolean = false) {
        val frozen = recipe?.copy(allowGame = allowGame) ?: return
        stop(); pendingRecipe = if (audioOnly) AudioRecipe(frozen.duration, frozen.start, frozen.end, allowGame = allowGame, format = "MP3", sourceKind = "VIDEO").encode() else frozen.encode()
        pendingName = "$baseName.${if (audioOnly) "mp3" else "mp4"}"
        pickDestination()
    }
    MediaEditorPage(onDismissRequest = { if (!busy) dismiss() }, title = { GalleryDialogTitle(R.drawable.ph_video_camera, localized(R.string.text_edit_video_e5572e, "Modifica video")) }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(target.name, Modifier.testTag("video-editor-target"), style = MaterialTheme.typography.titleSmall)
            Text(localized(R.string.text_trim_time_and_crop_the_frame_the_original_its_fa_58180e, "Taglia il tempo e ritaglia l'inquadratura. L'originale, i suoi Preferiti e le raccolte rimangono intatti."), style = MaterialTheme.typography.bodySmall)
            val r = recipe; val image = preview; val details = info
            if (r != null && details != null && (paired || image != null)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!result, { result = false; stop() }, label = { Text(localized(R.string.text_frame_4098fb, "Inquadratura")) })
                    FilterChip(result, { result = true; stop() }, label = { Text(localized(R.string.text_result_d28636, "Risultato")) })
                }
                if (!paired && playing) AndroidView(factory = { PlayerView(it).apply { useController = true; setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING) } },
                    update = { it.player = app.video.player }, onRelease = { it.player = null }, modifier = Modifier.fillMaxWidth().height(200.dp))
                else if (!paired && image != null) CropCanvas(image, r.crop(), result, Modifier.fillMaxWidth().height(200.dp).testTag("video-crop-canvas")) {
                    // Keep a usable minimum crop when dragging the image editor's shared handles.
                    if (it.right - it.left >= .01f && it.bottom - it.top >= .01f) change(r.copy(left = it.left, top = it.top, right = it.right, bottom = it.bottom))
                }
                Text(localized(R.string.text_the_still_frame_shows_the_initial_crop_play_to_c_4ea1d0, "Scorri la barra per vedere il fotogramma corrente. Riproduci per controllare solo l’intervallo scelto."), style = MaterialTheme.typography.bodySmall)
                GalleryButton(if (playing) localized(R.string.text_stop_preview_049a11, "Ferma anteprima") else localized(R.string.text_play_segment_47e94f, "Riproduci segmento"), Modifier.fillMaxWidth().testTag("video-segment-preview"), enabled = sourceReady && !busy && started && (playing || rangeApplied)) {
                    if (playing) stop() else data.byId[target.id]?.let {
                        if (previewPosition >= r.end) previewPosition = r.start
                        result = true; app.session.updateMediaEditorPreview(position = previewPosition, playing = true)
                        if (!paired) { app.video.play(it, activity.slot, r.mute, "editor", r); app.video.player?.seekTo(previewPosition - r.start) }
                    }
                }
                if (playing) app.video.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text(localized(R.string.text_segment_new_copy_duration_1_s_33ce36, "Intervallo · durata nuova copia %1\$s", segmentTime(r.length)), Modifier.testTag("video-segment-summary"), style = MaterialTheme.typography.titleSmall)
                RangeSlider(r.start.toFloat()..r.end.toFloat(), onValueChange = { range ->
                    val oldStart = r.start
                    val first = range.start.toLong().coerceIn(0, r.duration - 250)
                    val last = range.endInclusive.toLong().coerceIn(first + 250, r.duration)
                    val movedStart = kotlin.math.abs(first - oldStart) > kotlin.math.abs(last - r.end)
                    change(r.copy(start = first, end = last), if (movedStart) first else last)
                }, enabled = !busy, valueRange = 0f..r.duration.toFloat(), modifier = Modifier.testTag("video-time-range").semantics { contentDescription = localized(R.string.text_video_segment_range_314ef0, "Intervallo del segmento video") })
                Text("${segmentTime(previewPosition)} · ${segmentTime(r.start)} – ${segmentTime(r.end)}", Modifier.testTag("video-preview-position"), style = MaterialTheme.typography.bodySmall)
                GallerySlider(previewPosition.coerceIn(r.start, r.end).toFloat(), { position ->
                    previewPosition = position.toLong().coerceIn(r.start, r.end)
                    app.session.updateMediaEditorPreview(position = previewPosition, seek = true)
                    if (playing) app.video.player?.seekTo((previewPosition - r.start).coerceAtLeast(0))
                }, enabled = !busy, valueRange = r.start.toFloat()..r.end.toFloat(), modifier = Modifier.fillMaxWidth().testTag("video-preview-seek").semantics { contentDescription = localized(R.string.video_preview_position, "Posizione anteprima video") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(startText, { editRange(it, endText) }, enabled = !busy, singleLine = true,
                        label = { Text(localized(R.string.text_start_seconds_a70528, "Inizio · secondi")) }, modifier = Modifier.weight(1f).testTag("video-start"))
                    OutlinedTextField(endText, { editRange(startText, it) }, enabled = !busy, singleLine = true,
                        label = { Text(localized(R.string.text_end_seconds_60bd23, "Fine · secondi")) }, modifier = Modifier.weight(1f).testTag("video-end"))
                }
                GalleryButton(localized(R.string.text_full_video_cae633, "Video intero"), enabled = !busy) { change(r.copy(start = 0, end = r.duration)) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GalleryButton(localized(R.string.text_full_frame_1f765a, "Inquadratura intera"), enabled = !busy) { change(r.copy(left = 0f, top = 0f, right = 1f, bottom = 1f)) }
                    listOf("1:1" to 1f, "16:9" to 16f / 9, localized(R.string.text_portrait_332772, "Verticale") to 3f / 4).forEach { (label, ratio) ->
                        GalleryButton(label, enabled = !busy) { val crop = r.crop().ratio(details.width, details.height, ratio); change(r.copy(left = crop.left, top = crop.top, right = crop.right, bottom = crop.bottom)) }
                    }
                }
                listOf(localized(R.string.text_left_af36a4, "Sinistra") to r.left, localized(R.string.text_top_85e2ad, "Alto") to r.top, localized(R.string.text_right_ddcff9, "Destra") to r.right, localized(R.string.text_bottom_e201c6, "Basso") to r.bottom).forEachIndexed { index, (label, value) ->
                    Text("$label · ${(value * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                    val range = when (index) { 0 -> 0f..(r.right - .01f); 1 -> 0f..(r.bottom - .01f); 2 -> (r.left + .01f)..1f; else -> (r.top + .01f)..1f }
                    GallerySlider(value.coerceIn(range), { v -> change(when (index) { 0 -> r.copy(left = v); 1 -> r.copy(top = v); 2 -> r.copy(right = v); else -> r.copy(bottom = v) }) },
                        enabled = !busy, valueRange = range, modifier = Modifier.testTag("video-edge:$index").semantics { contentDescription = localized(R.string.text_1_s_edge_c30958, "Bordo %1\$s", label) })
                }
                Text(localized(R.string.text_video_resolution_a7f5f2, "Risoluzione video"), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(720, 1080).forEach { limit -> FilterChip(r.height == limit, { change(r.copy(height = limit)) }, enabled = !busy, label = { Text("${limit}p") }) } }
                Text(localized(R.string.text_output_height_is_1_s_px_width_follows_the_chosen_de6116, "L'altezza del risultato è %1\$s px; la larghezza segue l'inquadratura scelta. Il file viene ricodificato in MP4 con video H.264 e audio AAC, quando presente. I metadati personali non vengono copiati.", r.height), style = MaterialTheme.typography.bodySmall)
                Row { Checkbox(r.mute, { change(r.copy(mute = it)) }, enabled = !busy && details.audio, modifier = Modifier.testTag("video-mute")); Text(if (details.audio) localized(R.string.text_export_without_audio_ab231b, "Esporta senza audio") else localized(R.string.text_source_has_no_audio_0732a4, "Sorgente senza audio")) }
                GallerySettingSwitch(localized(R.string.editor_save_mp3, "Salva come MP3"), audioOnly, Modifier.testTag("video-export-mp3"), enabled = details.audio && !busy) { audioOnly = it }
                OutlinedTextField(baseName, { if (it.length <= 170) { baseName = it; error = null } }, singleLine = true, enabled = !busy,
                    label = { Text(localized(R.string.text_new_copy_name_1_s_cfecd3, "Nome della nuova copia · .%1\$s", if (audioOnly) "mp3" else "mp4")) }, modifier = Modifier.fillMaxWidth().testTag("video-export-name"))
                Text(localized(R.string.text_encoding_takes_time_and_temporary_space_no_files_ad5504, "La ricodifica richiede tempo e spazio temporaneo. Nessun file viene sovrascritto: se il nome esiste viene aggiunto un suffisso."), style = MaterialTheme.typography.bodySmall)
            } else if (error == null) { if (!prefs.reducedMotion) LinearProgressIndicator(Modifier.fillMaxWidth()); Text(localized(R.string.text_preparing_preview_cda2d9, "Preparazione dell'anteprima…")) }
            if (!sourceReady) Text(localized(R.string.text_the_source_has_changed_or_is_unavailable_reopen_43f906, "La sorgente è cambiata o non è disponibile. Riapri l'editor."), color = MaterialTheme.colorScheme.error)
            error?.let { Text(it, Modifier.testTag("video-editor-error"), color = MaterialTheme.colorScheme.error) }
        }
    }, dismissButton = { GalleryIconButton(R.drawable.ph_x, localized(R.string.text_cancel_18c9d9, "Annulla"), Modifier.testTag("video-editor-cancel"), enabled = !busy, onClick = dismiss) }, confirmButton = {
        IconButton({ if (prefs.gameMode) consent = true else submit() }, enabled = info != null && rangeApplied && sourceReady && baseName.isNotBlank() && !busy,
            modifier = Modifier.testTag("video-export-confirm")) { GalleryIcon(R.drawable.ph_floppy_disk, localized(R.string.text_save_a_copy_0680bd, "Salva una copia")) }
    })
    if (consent) GalleryAlertDialog(onDismissRequest = { consent = false }, title = { Text(localized(R.string.text_export_while_gaming_a6b51c, "Esportare mentre giochi?")) },
        text = { Text(localized(R.string.text_video_encoding_may_slow_the_game_and_use_more_ba_0160f6, "La codifica video può rallentare il gioco e consumare batteria. Puoi esportare più tardi.")) },
        dismissButton = { TextButton({ consent = false }) { Text(localized(R.string.text_later_b81861, "Più tardi")) } }, confirmButton = { TextButton({ consent = false; submit(true) }) { Text(localized(R.string.text_export_now_4bb86b, "Esporta ora")) } })
}
