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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.conflate
import java.util.UUID

@Composable
fun AudioEditorScreen(activity: PanelActivity, encoded: String, data: CatalogSnapshot, prefs: GalleryPreferences, dismiss: () -> Unit, accepted: () -> Unit,
    updateRecipe: (String) -> Unit = {}) {
    val target = remember(encoded) { ExternalRequest.decode(encoded).targets.single() }
    val app = activity.application as GalleryApp
    var raw by rememberSaveable(encoded) { mutableStateOf<String?>(null) }
    LaunchedEffect(app.session.mediaEditor?.recipe) { app.session.mediaEditor?.recipe?.let { if (it != raw) raw = it } }
    val recipe = remember(raw) { raw?.let(AudioRecipe::decode) }
    LaunchedEffect(raw) { raw?.let(updateRecipe) }
    var duration by remember(encoded) { mutableStateOf<Long?>(null) }
    var startText by rememberSaveable(encoded) { mutableStateOf("0") }
    var endText by rememberSaveable(encoded) { mutableStateOf("") }
    var previewPosition by rememberSaveable(encoded) { mutableLongStateOf(0L) }
    var baseName by rememberSaveable(encoded) { mutableStateOf(target.name.substringBeforeLast('.', target.name).take(130) + localized(R.string.text_segment_8d73b9, "-segmento")) }
    var error by rememberSaveable(encoded) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var consent by remember { mutableStateOf(false) }
    var token by remember(encoded) { mutableStateOf<String?>(null) }
    var previewJob by remember { mutableStateOf<Job?>(null) }
    var previewLoading by remember { mutableStateOf(false) }
    val activeToken by app.music.previewToken.collectAsStateWithLifecycle()
    val playback by app.music.state.collectAsStateWithLifecycle()
    val started by activity.isStartedFlow.collectAsState()
    val sourceReady = data.byId[target.id]?.let { data.usable(it) && target.matches(it) && it.kind == "AUDIO" } == true
    val rangeApplied = recipe?.let { r -> runCatching { VideoRecipe.millis(startText, r.duration) == r.start && VideoRecipe.millis(endText, r.duration) == r.end }.getOrDefault(false) } == true
    val listening = token != null && token == activeToken
    val displayedPosition = if (listening && playback.id == target.id && playback.title.startsWith(localized(R.string.text_preview_0b2338, "Anteprima ·"))) playback.position.coerceIn(0, recipe?.length ?: 0) else previewPosition
    val scope = rememberCoroutineScope()
    fun stopPreview(publish: Boolean = true) {
        if (listening) previewPosition = displayedPosition
        previewJob?.cancel(); token?.let(app.music::finishPreview); token = null
        if (publish) app.session.updateMediaEditorPreview(playing = false) }
    fun listen() {
        val r = recipe ?: return
        if (!rangeApplied || !sourceReady || !started || busy || previewLoading) return
        stopPreview(publish = false)
        if (previewPosition >= r.length) previewPosition = 0
        val identity = UUID.randomUUID().toString(); token = identity; previewLoading = true
        app.session.updateMediaEditorPreview(playing = true)
        previewJob = scope.launch {
            try { app.music.preview(target, r, identity); app.music.seekPreview(previewPosition) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: localized(R.string.text_playback_did_not_start_367226, "Ascolto non avviato"); stopPreview() }
            finally { previewLoading = false }
        }
    }
    LaunchedEffect(app.session.mediaEditor?.playing) {
        if (app.session.mediaEditor?.playing == true && !listening && !previewLoading) listen()
        else if (app.session.mediaEditor?.playing == false && (listening || previewLoading)) stopPreview()
    }
    LaunchedEffect(displayedPosition, recipe?.start) {
        app.session.updateMediaEditorPreview(position = (recipe?.start ?: 0) + displayedPosition)
    }
    LaunchedEffect(playback.playing, listening) {
        if (listening && !playback.playing && playback.position >= (recipe?.length ?: Long.MAX_VALUE)) stopPreview()
    }
    fun change(value: AudioRecipe, seekTo: Long? = null) { if (!busy) { stopPreview(); previewPosition = (seekTo ?: previewPosition).coerceIn(0, value.length); raw = value.encode(); startText = (value.start / 1000.0).toString(); endText = (value.end / 1000.0).toString(); error = null } }
    LaunchedEffect(token, listening) {
        if (listening) snapshotFlow { previewPosition }.conflate().collect { cursor ->
            app.music.seekPreview(cursor); delay(80)
        }
    }
    fun editRange(firstText: String, lastText: String) {
        val r = recipe ?: return
        stopPreview(); startText = firstText; endText = lastText
        try {
            val first = VideoRecipe.millis(firstText, r.duration); val last = VideoRecipe.millis(lastText, r.duration)
            val changed = r.copy(start = first, end = last)
            raw = changed.encode(); previewPosition = if (first != r.start) 0 else last - first; error = null
        } catch (failure: IllegalArgumentException) { error = localized(R.string.text_invalid_range_7d3864, "Intervallo non valido") }
    }
    DisposableEffect(activity, target.id, sourceReady, started) {
        if (!sourceReady || !started) stopPreview()
        onDispose { stopPreview() }
    }
    LaunchedEffect(encoded) {
        try {
            val actual = withContext(Dispatchers.IO) {
                val fresh = app.catalog.readMediaSnapshot(target.id)
                val item = fresh.byId[target.id]?.takeIf { fresh.usable(it) && target.matches(it) && it.kind == "AUDIO" } ?: throw SourceChanged()
                AudioEditing.inspect(app, app.catalog.uri(item, fresh))
            }
            ensureActive(); duration = actual
            if (raw == null) { raw = AudioRecipe(actual).encode(); endText = (actual / 1000.0).toString() }
            else require(recipe?.duration == actual) { localized(R.string.text_the_source_duration_has_changed_reopen_the_edito_be0a12, "La durata della sorgente è cambiata: riapri l'editor") }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: localized(R.string.text_unreadable_track_99d3c9, "Brano non leggibile") }
    }
    var pendingRecipe by rememberSaveable(encoded) { mutableStateOf<String?>(null) }
    var pendingName by rememberSaveable(encoded) { mutableStateOf("") }
    val pickDestination = rememberEditorDestinationPicker(activity, data, target, SourceRole.MUSIC,
        busy = { busy = it }, error = { error = it }) { album ->
        app.files.enqueueAudioExport(target, AudioRecipe.decode(requireNotNull(pendingRecipe)), album, pendingName)
        accepted()
    }
    fun submit(allowGame: Boolean = false) {
        val frozen = recipe?.copy(allowGame = allowGame) ?: return
        stopPreview(); pendingRecipe = frozen.encode(); pendingName = "$baseName.${frozen.extension}"
        pickDestination()
    }
    MediaEditorPage(onDismissRequest = { if (!busy) dismiss() }, title = { GalleryDialogTitle(R.drawable.ph_faders_horizontal, localized(R.string.text_edit_audio_90b959, "Modifica audio")) }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MediaThumbnail(data.byId[target.id], data, app.catalog, prefs, Modifier.size(64.dp), animated = false)
                Column(Modifier.weight(1f)) { Text(target.name, Modifier.testTag("audio-editor-target"), style = MaterialTheme.typography.titleSmall)
                    Text(localized(R.string.text_a_new_copy_keeping_the_original_intact_b1e835, "Una nuova copia, con l'originale intatto."), style = MaterialTheme.typography.bodySmall) }
            }
            val r = recipe
            if (r != null && duration == r.duration) {
                Text(localized(R.string.text_segment_new_duration_1_s_468a07, "Intervallo · nuova durata %1\$s", segmentTime(r.length)), Modifier.testTag("audio-segment-summary"), style = MaterialTheme.typography.titleSmall)
                RangeSlider(r.start.toFloat()..r.end.toFloat(), { range ->
                    val oldStart = r.start
                    val first = range.start.toLong().coerceIn(0, r.duration - 250)
                    val last = range.endInclusive.toLong().coerceIn(first + 250, r.duration)
                    change(r.copy(start = first, end = last), if (kotlin.math.abs(first - oldStart) > kotlin.math.abs(last - r.end)) 0 else last - first)
                }, enabled = !busy, valueRange = 0f..r.duration.toFloat(), modifier = Modifier.testTag("audio-time-range").semantics { contentDescription = localized(R.string.text_audio_segment_range_68e0ae, "Intervallo del segmento audio") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(startText, { editRange(it, endText) }, enabled = !busy, singleLine = true,
                        label = { Text(localized(R.string.text_start_seconds_a70528, "Inizio · secondi")) }, modifier = Modifier.weight(1f).testTag("audio-start"))
                    OutlinedTextField(endText, { editRange(startText, it) }, enabled = !busy, singleLine = true,
                        label = { Text(localized(R.string.text_end_seconds_60bd23, "Fine · secondi")) }, modifier = Modifier.weight(1f).testTag("audio-end"))
                }
                GalleryButton(localized(R.string.text_full_track_a05670, "Brano intero"), enabled = !busy) { change(r.copy(start = 0, end = r.duration), 0) }
                Text("${segmentTime(r.start + displayedPosition)} · ${segmentTime(r.start)} – ${segmentTime(r.end)}", Modifier.testTag("audio-preview-position"), style = MaterialTheme.typography.bodySmall)
                GallerySlider((r.start + displayedPosition.coerceIn(0, r.length)).toFloat(), { value -> previewPosition = (value.toLong() - r.start).coerceIn(0, r.length) },
                    enabled = rangeApplied && !busy, valueRange = r.start.toFloat()..r.end.toFloat(), modifier = Modifier.fillMaxWidth().testTag("audio-preview-seek").semantics { contentDescription = localized(R.string.audio_preview_position, "Posizione anteprima audio") })
                GalleryButton(if (listening) localized(R.string.text_stop_preview_049a11, "Ferma anteprima") else localized(R.string.text_listen_to_segment_b9d78e, "Ascolta segmento"), Modifier.fillMaxWidth().testTag("audio-segment-preview"), enabled = rangeApplied && sourceReady && started && !busy && !previewLoading) {
                    if (listening) stopPreview() else listen()
                }
                if (listening && playback.id == target.id && playback.title.startsWith(localized(R.string.text_preview_0b2338, "Anteprima ·"))) {
                    Text(if (playback.playing) localized(R.string.text_playing_5ef505, "In ascolto") else localized(R.string.text_paused_or_finished_4e52b8, "In pausa o terminato"), style = MaterialTheme.typography.bodySmall)
                    playback.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                if (previewLoading) { if (!prefs.reducedMotion) LinearProgressIndicator(Modifier.fillMaxWidth()); Text(localized(R.string.text_preparing_playback_617073, "Preparazione dell'ascolto…"), style = MaterialTheme.typography.bodySmall) }
                Text(localized(R.string.text_preview_uses_the_shared_player_your_original_que_0a71a9, "L'ascolto usa il player comune. La coda originale viene conservata e torna in pausa quando fermi l'anteprima o chiudi l'editor."), style = MaterialTheme.typography.bodySmall)
                Text(localized(R.string.text_new_copy_quality_253f0f, "Qualità della nuova copia"), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("M4A", "MP3").forEach { format ->
                    FilterChip(r.format == format, { change(r.copy(format = format)) }, enabled = !busy, label = { Text(format) }, modifier = Modifier.testTag("audio-format:$format"))
                } }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(128, 192, 256).forEach { rate -> FilterChip(r.bitrate == rate, { change(r.copy(bitrate = rate)) }, enabled = !busy,
                    label = { Text("$rate kb/s") }, modifier = Modifier.testTag("audio-bitrate:$rate")) } }
                OutlinedTextField(baseName, { if (it.length <= 170) { baseName = it; error = null } }, singleLine = true, enabled = !busy, label = { Text(localized(R.string.text_new_copy_name_1_s_cfecd3, "Nome della nuova copia · .%1\$s", r.extension)) }, modifier = Modifier.fillMaxWidth().testTag("audio-export-name"))
                Text(localized(R.string.text_no_overwriting_an_existing_name_receives_a_suffi_1531d1, "Nessuna sovrascrittura: un nome occupato riceve un suffisso. La nuova copia non sostituisce Preferiti o playlist dell'originale."), style = MaterialTheme.typography.bodySmall)
            } else if (error == null) { if (!prefs.reducedMotion) LinearProgressIndicator(Modifier.fillMaxWidth()); Text(localized(R.string.text_reading_duration_8bc1f7, "Lettura della durata…")) }
            if (!sourceReady) Text(localized(R.string.text_the_source_has_changed_or_is_unavailable_reopen_43f906, "La sorgente è cambiata o non è disponibile. Riapri l'editor."), color = MaterialTheme.colorScheme.error)
            error?.let { Text(it, Modifier.testTag("audio-editor-error"), color = MaterialTheme.colorScheme.error) }
        }
    }, dismissButton = { GalleryIconButton(R.drawable.ph_x, localized(R.string.text_cancel_18c9d9, "Annulla"), Modifier.testTag("audio-editor-cancel"), enabled = !busy, onClick = dismiss) }, confirmButton = {
        IconButton({ if (prefs.gameMode) consent = true else submit() }, enabled = duration != null && rangeApplied && sourceReady && baseName.isNotBlank() && !busy,
            modifier = Modifier.testTag("audio-export-confirm")) { GalleryIcon(R.drawable.ph_floppy_disk, localized(R.string.text_save_a_copy_0680bd, "Salva una copia")) }
    })
    if (consent) GalleryAlertDialog(onDismissRequest = { consent = false }, title = { Text(localized(R.string.text_export_while_gaming_a6b51c, "Esportare mentre giochi?")) },
        text = { Text(localized(R.string.text_encoding_may_slow_down_your_game_and_use_more_ba_06e080, "La ricodifica può rallentare il gioco e consumare batteria. Puoi esportare più tardi.")) },
        dismissButton = { TextButton({ consent = false }) { Text(localized(R.string.text_later_b81861, "Più tardi")) } }, confirmButton = { TextButton({ consent = false; submit(true) }) { Text(localized(R.string.text_export_now_4bb86b, "Esporta ora")) } })
}
