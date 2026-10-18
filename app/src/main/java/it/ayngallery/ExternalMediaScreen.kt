package it.ayngallery

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

@Composable
fun ExternalMediaDialog(activity: PanelActivity, encoded: String, data: CatalogSnapshot, prefs: GalleryPreferences, dismiss: () -> Unit) {
    val request = remember(encoded) { ExternalRequest.decode(encoded) }
    val app = activity.application as GalleryApp
    if (request.action in setOf(ExternalAction.SHARE, ExternalAction.OPEN) && request.copy == null) {
        LaunchedEffect(encoded) {
            try {
                val intent = ExternalMedia.prepare(app.catalog, request)
                ensureActive()
                if (activity.isStartedFlow.value) activity.openExternal(intent, request)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { app.session.fail(failure.message ?: localized(R.string.text_could_not_open_aa1697, "Apertura non riuscita")) }
            finally { dismiss() }
        }
        return
    }
    var chosen by rememberSaveable(encoded) { mutableStateOf(request.targets.map { it.id }) }
    var error by rememberSaveable(encoded) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var copyRaw by rememberSaveable(encoded) { mutableStateOf(request.copy?.encode()) }
    val copy = remember(copyRaw) { copyRaw?.let(ShareCopyOptions::decode) }
    var batchId by rememberSaveable(encoded) { mutableStateOf<String?>(null) }
    var prepared by remember { mutableStateOf<PreparedShare?>(null) }
    var completed by remember { mutableIntStateOf(0) }
    val currentId by rememberUpdatedState(batchId)
    val started by activity.isStartedFlow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val targets = request.targets.filter { it.id in chosen }
    val convertible = targets.isNotEmpty() && targets.all { it.kind == "IMAGE" }
    val available = targets.isNotEmpty() && targets.all { target -> data.byId[target.id]?.let { data.usable(it) && target.matches(it) } == true }
    val ready = available && (copy == null || convertible) && (batchId == null || prepared?.id == batchId) &&
        (copy == null || !prefs.gameMode || copy.allowGame || batchId != null)
    val confirmEnabled = ready && !busy
    fun discardPreview() {
        val id = batchId; batchId = null; prepared = null
        if (id != null) app.catalog.scope.launch { runCatching { app.shares.discard(id) }.onFailure { app.session.fail(localized(R.string.text_temporary_copies_retained_retry_cleanup_f0c17a, "Copie temporanee conservate: pulizia da riprovare")) } }
    }
    fun change(value: ShareCopyOptions?) { discardPreview(); copyRaw = value?.encode(); error = null }
    val close = { job?.cancel(); discardPreview(); dismiss() }
    DisposableEffect(activity) {
        onDispose {
            job?.cancel()
            if (!activity.isChangingConfigurations) currentId?.let { id -> app.catalog.scope.launch { runCatching { app.shares.discard(id) } } }
        }
    }
    LaunchedEffect(started) { if (!started) job?.cancel() }
    LaunchedEffect(available) { if (!available && batchId != null) { discardPreview(); error = localized(R.string.text_the_selection_is_no_longer_available_reopen_the_672edd, "La selezione non è più disponibile. Riapri il menu.") } }
    LaunchedEffect(batchId) {
        val id = batchId ?: return@LaunchedEffect
        if (prepared?.id == id) return@LaunchedEffect
        try {
            val restored = app.shares.read(id, ExternalRequest(request.action, targets, copy))
            ensureActive()
            if (batchId == id) prepared = restored
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (batchId == id) { discardPreview(); error = failure.message ?: localized(R.string.text_copies_not_recovered_prepare_them_again_7bdf89, "Copie non recuperate: preparale nuovamente") }
        }
    }
    val mixed = targets.isNotEmpty() && ExternalMedia.combinedMime(targets) == "*/*"
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val height = windowHeight * 0.48f
    GalleryAlertDialog(onDismissRequest = close, title = { GalleryDialogTitle(if (request.action == ExternalAction.WALLPAPER) R.drawable.ph_panorama else R.drawable.ph_copy_simple, request.action.title) }, text = {
        Column(Modifier.testTag("external-content").heightIn(max = windowHeight * 0.56f)
            .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${targets.size} ${if (targets.size == 1) localized(R.string.text_selected_file_fc1eda, "file scelto") else localized(R.string.text_selected_files_43f235, "file scelti")}", Modifier.testTag("external-count"), style = MaterialTheme.typography.titleSmall)
            if (request.action == ExternalAction.WALLPAPER) Text(localized(R.string.text_android_will_show_available_crop_tools_and_desti_6acb8e, "Android mostrerà ritaglio e destinazioni disponibili. Gli sfondi dei due display e di iiSU dipendono dal sistema."), style = MaterialTheme.typography.bodySmall)
            else Text(localized(R.string.text_the_chosen_app_receives_temporary_read_access_to_b93bca, "L'app scelta riceverà accesso temporaneo in lettura ai file indicati. Puoi annullare nel selettore Android."), style = MaterialTheme.typography.bodySmall)
            if (mixed) Text(localized(R.string.text_mixed_file_types_some_apps_may_not_accept_every_6c1468, "Tipi diversi: alcune app potrebbero non accettare tutti i file. Puoi togliere elementi dalla selezione."),
                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            if (request.action == ExternalAction.SHARE) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(copy == null, { change(null) }, enabled = !busy, label = { Text(localized(R.string.text_originals_fa1fbe, "Originali")) }, modifier = Modifier.testTag("share-original"))
                    FilterChip(copy != null, { change(ShareCopyOptions()) }, enabled = !busy && convertible, label = { Text(localized(R.string.text_image_copies_646a7b, "Copie d'immagine")) }, modifier = Modifier.testTag("share-copy"))
                }
                if (copy != null) {
                    Text(localized(R.string.text_re_encodes_in_srgb_with_orientation_applied_all_722db5, "Ricodifica in sRGB con orientamento applicato. Tutti i metadati originali, compresa la posizione, vengono esclusi; i nomi delle copie sono anonimi. I pixel possono comunque mostrare informazioni personali."), style = MaterialTheme.typography.bodySmall)
                    if (!convertible) Text(localized(R.string.text_remove_gifs_videos_and_tracks_to_prepare_image_c_e825c6, "Togli GIF, video e brani per preparare copie d'immagine, oppure scegli Originali."), color = MaterialTheme.colorScheme.error)
                    Text(localized(R.string.text_format_73e180, "Formato"), style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("PNG", "JPEG").forEach { format -> FilterChip(copy.format == format, { change(copy.copy(format = format)) }, enabled = !busy,
                            label = { Text(format) }, modifier = Modifier.testTag("share-format:$format")) }
                    }
                    Text(if (copy.format == "PNG") localized(R.string.text_png_preserves_transparency_96ed6e, "PNG conserva la trasparenza.") else localized(R.string.text_jpeg_quality_1_s_transparent_areas_become_white_5dbac0, "JPEG · qualità %1\$s%%. Le parti trasparenti diventano bianche; compressione con perdita.", copy.quality), style = MaterialTheme.typography.bodySmall)
                    Text(localized(R.string.text_maximum_long_edge_no_upscaling_377c8a, "Lato lungo massimo · nessun ingrandimento"), style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0, 1024, 2048, 4096).forEach { edge -> FilterChip(copy.longest == edge, { change(copy.copy(longest = edge)) }, enabled = !busy,
                            label = { Text(if (edge == 0) localized(R.string.text_original_dimensions_ee2d69, "Dimensioni originali") else "$edge px") }, modifier = Modifier.testTag("share-edge:$edge")) }
                    }
                    Text(localized(R.string.text_copies_do_not_enter_the_catalog_or_trash_after_o_8ae906, "Le copie non entrano nel catalogo o nel cestino. Dopo l'apertura del selettore restano leggibili per 24 ore; la pulizia avviene alla prima esecuzione utile. Prepararle non equivale a inviarle."), style = MaterialTheme.typography.bodySmall)
                    if (prefs.gameMode && prepared == null) Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(copy.allowGame, { change(copy.copy(allowGame = it)) }, enabled = !busy,
                            modifier = Modifier.testTag("share-game-consent").semantics { contentDescription = localized(R.string.text_allow_preparation_while_gaming_347fe8, "Autorizza la preparazione durante il gioco") })
                        Text(localized(R.string.text_prepare_while_gaming_anyway_encoding_may_slow_th_02a0ee, "Prepara comunque durante il gioco. La ricodifica può rallentarlo e consumare batteria."), style = MaterialTheme.typography.bodySmall)
                    }
                } else Text(localized(R.string.text_original_files_may_contain_location_metadata_no_f14755, "I file originali possono contenere metadati di posizione. Nessuna conversione applicata."), style = MaterialTheme.typography.bodySmall)
            }
            LazyColumn(Modifier.heightIn(max = height).testTag("external-files"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(request.targets, key = { it.id }) { target ->
                    val current = data.byId[target.id]?.takeIf { data.usable(it) && target.matches(it) }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (request.action == ExternalAction.SHARE) Checkbox(target.id in chosen, { checked ->
                            discardPreview(); chosen = if (checked) chosen + target.id else chosen - target.id; error = null
                        }, enabled = !busy, modifier = Modifier.testTag("external-choice:${target.id}")
                            .semantics { contentDescription = localized(R.string.text_include_1_s_414139, "Includi %1\$s", target.name) })
                        MediaThumbnail(current, data, app.catalog, prefs, Modifier.size(52.dp), animated = false)
                        Column(Modifier.weight(1f)) {
                            Text(target.name, Modifier.testTag("external-target:${target.id}"), style = MaterialTheme.typography.bodyMedium)
                            Text(if (current == null) localized(R.string.text_no_longer_available_reopen_the_menu_5f5c78, "Non più disponibile: riapri il menu") else ExternalMedia.mime(target),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            prepared?.takeIf { confirmEnabled }?.let { batch ->
                Text(localized(R.string.text_copies_ready_1_s_c7ea4e, "Copie pronte · %1\$s", batch.images.size), Modifier.testTag("share-copies-ready"), style = MaterialTheme.typography.titleSmall)
                LazyColumn(Modifier.heightIn(max = height).testTag("share-copy-previews"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(batch.images, key = { it.sourceId }) { image ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(image.uri, localized(R.string.text_preview_of_1_s_62847b, "Anteprima di %1\$s", image.name), Modifier.size(64.dp).testTag("share-copy-preview:${image.sourceId}"))
                            Column { Text(image.name, style = MaterialTheme.typography.bodyMedium)
                                Text("${image.width} × ${image.height} · ${(image.size + 1023) / 1024} KB", Modifier.testTag("share-copy-size:${image.sourceId}"), style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
            error?.let { Text(it, Modifier.testTag("external-error"), color = MaterialTheme.colorScheme.error) }
            if (batchId != null && prepared == null) Text(localized(R.string.text_recovering_preview_9fa505, "Recupero dell'anteprima…"), style = MaterialTheme.typography.labelSmall)
            if (busy) { if (!prefs.reducedMotion) LinearProgressIndicator(Modifier.fillMaxWidth()); Text(if (copy != null && prepared == null) localized(R.string.text_preparing_1_s_2_s_c07b72, "Preparazione · %1\$s / %2\$s", completed, targets.size) else localized(R.string.text_checking_access_a6b304, "Verifica dell'accesso…"), style = MaterialTheme.typography.labelSmall) }
        }
    }, dismissButton = { TextButton(close, Modifier.testTag("external-cancel")) { Text(localized(R.string.text_cancel_18c9d9, "Annulla")) } }, confirmButton = {
        TextButton(enabled = confirmEnabled, modifier = Modifier.testTag("external-confirm"), onClick = {
            val frozen = ExternalRequest(request.action, targets.toList(), copy)
            busy = true; error = null
            job = scope.launch(Dispatchers.Main.immediate) {
                var unclaimed: PreparedShare? = null
                var published: String? = null
                var opened = false
                try {
                    if (copy != null && batchId == null) {
                        unclaimed = app.shares.prepare(frozen) { done, _ -> withContext(Dispatchers.Main.immediate) { completed = done } }
                        ensureActive()
                        val completedBatch = unclaimed!!
                        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                            prepared = completedBatch; batchId = completedBatch.id; busy = false
                        }
                        unclaimed = null
                    } else {
                        val intent = if (copy == null) ExternalMedia.prepare(app.catalog, frozen)
                            else { published = batchId!!; app.shares.publish(published!!, frozen) }
                        ensureActive(); activity.openExternal(intent, frozen); opened = true
                        batchId = null; prepared = null; dismiss()
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = if (failure is SecurityException) localized(R.string.text_file_access_denied_check_the_source_160214, "Accesso al file negato. Verifica la sorgente.") else failure.message ?: localized(R.string.text_could_not_open_aa1697, "Apertura non riuscita") }
                finally {
                    // Successful preparation has no cleanup: publish readiness and release busy
                    // in the same main-thread turn, without an empty IO round trip between them.
                    if (unclaimed != null || published != null && !opened) withContext(NonCancellable + Dispatchers.IO) {
                        unclaimed?.let { runCatching { app.shares.discard(it.id) }.onFailure { app.session.fail(localized(R.string.text_temporary_copies_retained_retry_cleanup_f0c17a, "Copie temporanee conservate: pulizia da riprovare")) } }
                        if (published != null && !opened) runCatching { app.shares.discard(published!!, includePublished = true) }.onFailure { app.session.fail(localized(R.string.text_temporary_copies_retained_retry_cleanup_f0c17a, "Copie temporanee conservate: pulizia da riprovare")) }
                    }
                    if (published != null && !opened) { batchId = null; prepared = null }
                    busy = false
                }
            }
        }) { Text(if (copy != null && prepared == null) localized(R.string.text_prepare_copies_437567, "Prepara copie") else request.action.confirm) }
    })
}
