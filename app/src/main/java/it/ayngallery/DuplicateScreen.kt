package it.ayngallery

import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DuplicateScreen(activity: PanelActivity, data: CatalogSnapshot, prefs: GalleryPreferences,
    task: (suspend () -> Unit) -> Unit, trash: (FileActionRequest) -> Unit, resultsOnly: Boolean = false) {
    val app = activity.application as GalleryApp
    val report by app.duplicates.state.collectAsStateWithLifecycle()
    var expandedGroup by rememberSaveable { mutableStateOf<String?>(null) }
    var mode by rememberSaveable { mutableStateOf("ALL") }
    var chosen by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var choosing by rememberSaveable { mutableStateOf(false) }
    var gameAction by remember { mutableStateOf<String?>(null) }
    var keepJson by rememberSaveable { mutableStateOf("{}") }
    val keepers = remember(keepJson) { JSONObject(keepJson) }
    var transferring by app.session::duplicateTransferBusy
    var transfer by remember { mutableStateOf<Pair<DuplicateFile, DuplicateFile>?>(null) }
    var transferError by remember { mutableStateOf<String?>(null) }
    var compare by remember { mutableStateOf<Pair<DuplicateFile, DuplicateFile>?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val dialogHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() * .5f }
    val groups = report.groups.mapNotNull { group -> group.files.filter { it.current(data) != null }.takeIf { it.size > 1 }?.let { group.copy(files = it) } }
    fun scope() = when (mode) { "ALBUM" -> DuplicateScope(albums = chosen.toSet()); "MEDIA" -> DuplicateScope(media = chosen.toSet()); else -> DuplicateScope() }
    fun start() { if (prefs.gameMode) gameAction = "START" else app.duplicates.start(scope()) }
    val showControls = !resultsOnly
    val showResults = resultsOnly || !app.session.canSwap()
    Column(Modifier.fillMaxSize().then(if (resultsOnly) Modifier.padding(12.dp)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, GalleryShape)
        .background(MaterialTheme.colorScheme.surface, GalleryShape) else Modifier)) {
        if (resultsOnly) Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GalleryIcon(R.drawable.ph_magnifying_glass, null)
            Text(localized(R.string.text_find_duplicates_d1ed15, "Trova duplicati"), style = MaterialTheme.typography.titleMedium)
        }
        if (showControls && (report.active || report.notice.isNotBlank())) Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (report.notice.isNotBlank()) Text(report.notice, Modifier.testTag("duplicate-status"), style = MaterialTheme.typography.bodySmall)
                if (report.active) {
                    Text(localized(R.string.text_1_s_2_s_candidates_3_s_read_4_s_unverified_c7af59, "%1\$s / %2\$s candidati · %3\$s letti · %4\$s non verificati", report.checked, report.total, fileSize(report.bytes), report.skipped),
                        Modifier.testTag("duplicate-progress"), style = MaterialTheme.typography.bodySmall)
                    if (!prefs.reducedMotion && report.phase != "PAUSED") LinearProgressIndicator(
                        progress = { if (report.total > 0) report.checked.toFloat() / report.total else 0f }, modifier = Modifier.fillMaxWidth())
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (report.phase == "PAUSED") GalleryButton(localized(R.string.text_resume_b0cc9c, "Riprendi"), Modifier.testTag("duplicate-resume"), enabled = report.phase != "CANCELLING") {
                            if (prefs.gameMode) gameAction = "RESUME" else app.duplicates.resume()
                        } else GalleryButton(localized(R.string.text_pause_9bd4d1, "Pausa"), Modifier.testTag("duplicate-pause"), enabled = report.phase != "CANCELLING", onClick = app.duplicates::pause)
                        GalleryButton(localized(R.string.text_cancel_search_d9c204, "Annulla ricerca"), Modifier.testTag("duplicate-cancel"), enabled = report.phase != "CANCELLING", onClick = app.duplicates::cancel)
                    }
                }
            }
        }
        LazyColumn(Modifier.weight(1f).testTag("duplicates-list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (showControls) item { Text(localized(R.string.duplicate_experimental, "Sperimentale · Controlla i risultati prima di eliminare i file."), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge) }
            if (showControls) item {
                Text(localized(R.string.text_scope_of_the_next_search_237f0a, "Ambito della prossima ricerca"), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("ALL" to localized(R.string.text_entire_catalog_10e9a3, "Tutto il catalogo"), "ALBUM" to localized(R.string.text_folders_23cc92, "Cartelle"), "MEDIA" to localized(R.string.text_chosen_media_25c884, "Media scelti")).forEach { (id, label) ->
                        FilterChip(mode == id, { mode = id; chosen = emptyList() }, enabled = !report.active && !transferring, label = { Text(label) }, modifier = Modifier.testTag("duplicate-scope:$id"))
                    }
                }
                if (mode != "ALL") GalleryButton(localized(R.string.text_choose_1_s_2_s_26e8e4, "Scegli %1\$s · %2\$s", if (mode == "ALBUM") localized(R.string.text_folders_69d99c, "cartelle") else "media", chosen.size),
                    Modifier.fillMaxWidth().testTag("duplicate-choose"), enabled = !report.active && !transferring) { choosing = true }

            }
            if (showControls && !report.active) item {
                GalleryButton(localized(R.string.text_start_a_new_search_d9b3b9, "Avvia una nuova ricerca"), Modifier.fillMaxWidth().testTag("duplicate-start"), primary = true,
                    enabled = !transferring && (mode == "ALL" || chosen.isNotEmpty()), onClick = ::start)
            }
            if (showResults) {
            notice?.let { item { Text(it, Modifier.testTag("duplicate-notice"), color = MaterialTheme.colorScheme.primary) } }
            if (!report.active && report.phase != "IDLE") item {
                Text(localized(R.string.text_1_s_groups_2_s_candidates_read_3_s_unverified_290f94, "%1\$s gruppi · %2\$s candidati letti · %3\$s non verificati", groups.size, report.checked, report.skipped),
                    Modifier.testTag("duplicate-summary"), style = MaterialTheme.typography.titleMedium)

            }
            if (groups.isEmpty()) item {
                Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) { Column(Modifier.fillMaxWidth(.85f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                GalleryIcon(R.drawable.ph_magnifying_glass, null, Modifier.size(48.dp))
                Text(if (report.phase == "COMPLETED" && report.skipped == 0) localized(R.string.text_no_identical_copies_found_in_the_chosen_scope_b98b95, "Nessuna copia identica trovata nel perimetro scelto.")
                    else if (report.phase == "COMPLETED") localized(R.string.text_no_identical_copies_among_verified_files_some_fi_34dda8, "Nessuna copia identica tra i file verificati. Alcuni file non erano leggibili.")
                    else localized(R.string.text_verified_groups_will_appear_here_nothing_is_dele_41b1d4, "I gruppi verificati appariranno qui. Nessuna eliminazione automatica."), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                } }
            }
            if (groups.isNotEmpty()) item {
                if (expandedGroup != null) TextButton({ expandedGroup = null }, Modifier.testTag("duplicate-groups-back")) { GalleryIcon(R.drawable.ph_arrow_left, null) }
                else Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    groups.chunked(6).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { group ->
                                OutlinedCard(onClick = { expandedGroup = group.id }, modifier = Modifier.weight(1f).testTag("duplicate-group:${group.id}")) {
                                    Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                                        MediaThumbnail(group.files.first().current(data), data, app.catalog, prefs, Modifier.fillMaxSize(), animated = false)
                                        Surface(Modifier.align(Alignment.BottomEnd), color = MaterialTheme.colorScheme.surface.copy(alpha = .9f)) { Text(group.files.size.toString(), Modifier.padding(4.dp), style = MaterialTheme.typography.labelSmall) }
                                    }
                                }
                            }
                            repeat(6 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
            groups.filter { it.id == expandedGroup }.forEachIndexed { index, group ->
                val keep = group.files.firstOrNull { it.media.id == keepers.optString(group.id) } ?: group.files.first()
                item(key = "group:${group.id}") {
                    HorizontalDivider()
                    Text(localized(R.string.text_group_1_s_2_s_copies_116e5d, "Gruppo %1\$s · %2\$s copie", index + 1, group.files.size), Modifier.padding(top = 12.dp).testTag("duplicate-group"), style = MaterialTheme.typography.titleMedium)
                    Text(localized(R.string.text_1_s_each_potential_space_to_reclaim_2_s_0deb3a, "%1\$s ciascuna · spazio teorico recuperabile %2\$s", fileSize(group.digest.bytes), fileSize(group.potentialBytes)), style = MaterialTheme.typography.bodySmall)
                    Text(localized(R.string.text_choose_which_copy_to_keep_content_is_checked_aga_db2906, "Scegli quale conservare. Prima del cestino il contenuto viene ricontrollato."), style = MaterialTheme.typography.bodySmall)
                }
                items(group.files, key = { "duplicate:${group.id}:${it.media.id}" }) { file ->
                    Surface(Modifier.animateItem(placementSpec = null, fadeInSpec = tween(if (prefs.reducedMotion) 0 else 260),
                        fadeOutSpec = tween(if (prefs.reducedMotion) 0 else 160)), shape = GalleryShape, color = MaterialTheme.colorScheme.surfaceVariant) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                MediaThumbnail(file.current(data), data, app.catalog, prefs, Modifier.size(64.dp), animated = false)
                                Column(Modifier.weight(1f)) {
                                    Text(file.media.name, Modifier.testTag("duplicate-name:${file.media.id}"), style = MaterialTheme.typography.titleMedium)
                                    Text(file.path, style = MaterialTheme.typography.bodySmall)
                                    Text(if (file.media.id == keep.media.id) localized(R.string.text_keep_d3090f, "Da conservare") else localized(R.string.text_extra_copy_17ffbc, "Copia aggiuntiva"), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                GalleryButton(localized(R.string.text_open_12abcf, "Apri"), Modifier.testTag("duplicate-open:${file.media.id}"), enabled = !transferring) {
                                    app.session.updateMediaContext(group.files.map { it.media.id }); app.session.selectMedia(file.media.id); activity.windowModel.open(file.media.id)
                                }
                                if (file.media.id != keep.media.id) {
                                    GalleryButton(localized(R.string.text_keep_this_one_5d3d76, "Conserva questa"), Modifier.testTag("duplicate-keep:${file.media.id}"), enabled = !report.active && !transferring) {
                                        keepJson = JSONObject(keepJson).put(group.id, file.media.id).toString()
                                    }
                                    GalleryButton(localized(R.string.text_compare_65b080, "Confronta"), Modifier.testTag("duplicate-compare:${file.media.id}"), enabled = !report.active && !transferring) { compare = keep to file }
                                    if (file.current(data)?.favorite == true || data.collectionItems.any { it.mediaId == file.media.id })
                                        GalleryButton(localized(R.string.text_transfer_references_0edaf3, "Sposta riferimenti"), Modifier.testTag("duplicate-refs:${file.media.id}"), enabled = !report.active && !transferring) { transferError = null; transfer = keep to file }
                                    GalleryButton(localized(R.string.text_trash_0f64be, "Cestino…"), Modifier.testTag("duplicate-trash:${file.media.id}"), enabled = !report.active && !transferring) {
                                        file.current(data)?.let { trash(FileActionRequest("TRASH", listOf(it), mapOf(it.id to file.digest), mapOf(it.id to keep.media.id))) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            }
        }
    }
    if (choosing) GalleryAlertDialog(onDismissRequest = { choosing = false }, title = { Text(if (mode == "ALBUM") localized(R.string.text_folders_to_check_708c64, "Cartelle da verificare") else localized(R.string.text_media_to_check_32b34c, "Media da verificare")) }, text = {
        val options = if (mode == "ALBUM") (data.visibleAlbums() + data.visibleAlbums(musical = true)).distinctBy { it.id }.map { it.id to it.path }
            else data.media.filter(data::usable).map { it.id to "${it.name} · ${data.albumsById[it.albumId]?.path.orEmpty()}" }
        LazyColumn(Modifier.heightIn(max = 320.dp)) { items(options, key = { it.first }) { (id, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(id in chosen, { checked ->
                chosen = if (checked) chosen + id else chosen - id
            }, Modifier.testTag("duplicate-scope-item:$id")); Text(label, Modifier.weight(1f)) }
        } }
    }, confirmButton = { TextButton({ choosing = false }) { Text(localized(R.string.text_use_this_selection_48380c, "Usa questa selezione")) } }, dismissButton = { TextButton({ chosen = emptyList(); choosing = false }) { Text(localized(R.string.text_clear_2bdc24, "Svuota")) } })
    gameAction?.let { action -> GalleryAlertDialog(onDismissRequest = { gameAction = null }, title = { Text(localized(R.string.text_read_files_while_gaming_ccc524, "Leggere i file mentre giochi?")) },
        text = { Text(localized(R.string.text_search_may_use_device_storage_and_the_sd_card_in_901521, "La ricerca può impegnare la memoria del dispositivo e la scheda SD. In modalità Gioco parte o riprende soltanto con questa scelta esplicita.")) },
        confirmButton = { TextButton({ if (action == "START") app.duplicates.start(scope(), allowGame = true) else app.duplicates.resume(allowGame = true); gameAction = null }) { Text(localized(R.string.text_search_now_anyway_d2a180, "Cerca anche ora")) } },
        dismissButton = { TextButton({ gameAction = null }) { Text(localized(R.string.text_cancel_18c9d9, "Annulla")) } }) }
    transfer?.let { (keep, file) -> GalleryAlertDialog(onDismissRequest = { if (!transferring) transfer = null }, title = { Text(localized(R.string.text_transfer_references_396f43, "Spostare i riferimenti?")) },
        text = { Column(Modifier.heightIn(max = dialogHeight).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(localized(R.string.text_from_1_s_nto_2_s_n_nfavorites_and_collection_pla_bb8171, "Da %1\$s\nA %2\$s\n\nVerranno spostati il Preferito e i collegamenti nelle raccolte/playlist, mantenendo l'ordine. Le copertine restano invariate. I file non vengono eliminati. Prima verifichiamo di nuovo entrambi i contenuti.", file.media.name, keep.media.name))
            transferError?.let { Text(it, Modifier.testTag("duplicate-transfer-error"), color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(enabled = !transferring, modifier = Modifier.testTag("duplicate-transfer-confirm"), onClick = {
            transferring = true
            task { try { app.duplicates.transferReferences(keep, listOf(file)); withContext(Dispatchers.Main) { transfer = null; notice = localized(R.string.text_favorites_and_links_transferred_no_files_deleted_b9a9f0, "Preferiti e collegamenti trasferiti. Nessun file eliminato.") } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { withContext(Dispatchers.Main) { transferError = localized(R.string.text_verification_failed_or_access_changed_no_referen_ba271c, "Verifica non riuscita o accesso cambiato. Nessun riferimento trasferito; avvia una nuova ricerca.") } }
                finally { withContext(NonCancellable + Dispatchers.Main) { transferring = false } } }
        }) { Text(if (transferring) localized(R.string.text_verifying_62483c, "Verifica…") else localized(R.string.text_transfer_references_0edaf3, "Sposta riferimenti")) } }, dismissButton = { TextButton({ transfer = null }, enabled = !transferring) { Text(localized(R.string.text_cancel_18c9d9, "Annulla")) } }) }
    compare?.let { (keep, file) -> GalleryAlertDialog(onDismissRequest = { compare = null }, title = { Text(localized(R.string.text_compare_these_two_copies_bc72b0, "Confrontare queste due copie?")) },
        text = { Text(localized(R.string.text_1_s_n_2_s_n_nreplaces_the_open_comparison_previo_76dd8a, "%1\$s\n%2\$s\n\nSostituisce il confronto aperto. Le sessioni già salvate restano disponibili.", keep.media.name, file.media.name),
            Modifier.heightIn(max = dialogHeight).verticalScroll(rememberScrollState())) }, confirmButton = {
            TextButton({
                if (keep.current(data) != null && file.current(data) != null) {
                    val ids = listOf(keep.media.id, file.media.id)
                    app.comparison.update(ComparisonState(panes = ids.mapIndexed { i, id -> ComparisonPane("pane_${i + 1}", id, true, ids) }, enabled = true))
                    activity.windowModel.go("COMPARE")
                }
                compare = null
            }) { Text(localized(R.string.text_open_comparison_7d6cae, "Apri confronto")) }
        }, dismissButton = { TextButton({ compare = null }) { Text(localized(R.string.text_cancel_18c9d9, "Annulla")) } }) }
}
