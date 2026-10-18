package it.ayngallery

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.concurrent.TimeUnit

internal fun trashDaysRemaining(entry: TrashEntry, now: Long): Long =
    kotlin.math.ceil((entry.expiresAt - now).coerceAtLeast(0L).toDouble() / TimeUnit.DAYS.toMillis(1)).toLong()

private fun restoreInProgress(operation: FileOperation) = operation.kind in setOf("RESTORE", "RESTORE_COPY") &&
    !FileOperations.terminal(operation.phase) && operation.phase !in setOf("FAILED", "PARTIAL", "INTERRUPTED", "DEFERRED")

internal fun GalleryApp.selectRecoveryTool(key: String) {
    explorer.connectionForm.value = false
    explorer.ftpTransfersVisible.value = false
    if (key == "FTP" || key == "BROWSE") {
        session.recoveryTab = "FTP"
        explorer.browse(key == "BROWSE")
    } else session.recoveryTab = key
}

internal fun GalleryApp.cycleRecoveryTool(direction: Int) {
    val tools = listOf("TRASH", "DUPLICATES", "FTP", "BROWSE")
    val current = if (session.recoveryTab == "FTP" && explorer.browsing.value) "BROWSE" else session.recoveryTab
    selectRecoveryTool(tools[Math.floorMod(tools.indexOf(current).coerceAtLeast(0) + direction, tools.size)])
    sounds.play("navigate")
}

@Composable
internal fun RecoveryScreen(activity: PanelActivity, data: CatalogSnapshot, prefs: GalleryPreferences,
    task: (suspend () -> Unit) -> Unit, trash: (FileActionRequest) -> Unit) {
    val app = activity.application as GalleryApp
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(64.dp).fillMaxHeight().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly) {
            val browsing by app.explorer.browsing
            listOf("TRASH" to R.drawable.ph_trash, "DUPLICATES" to R.drawable.ph_magnifying_glass,
                "FTP" to R.drawable.ph_folder_plus, "BROWSE" to R.drawable.ph_desktop_tower).forEach { (key, icon) ->
                val label = when (key) {
                    "TRASH" -> localized(R.string.text_trash_9a3a36, "Cestino")
                    "FTP" -> localized(R.string.explorer_share, "Condividi cartella")
                    "BROWSE" -> localized(R.string.explorer_browse, "Esplora risorse")
                    else -> localized(R.string.text_find_duplicates_d1ed15, "Trova duplicati")
                }
                val checked = if (key == "FTP" || key == "BROWSE") app.session.recoveryTab == "FTP" && browsing == (key == "BROWSE")
                    else app.session.recoveryTab == key
                FilledIconToggleButton(checked, {
                    app.selectRecoveryTool(key)
                }, Modifier.fillMaxWidth().height(56.dp).testTag("recovery-tab:$key"), shape = RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp)) { GalleryIcon(icon, label) }
            }
        }
        Box(Modifier.weight(1f)) {
            if (app.session.recoveryTab == "TRASH") TrashScreen(app, data, task, restoreElsewhere = trash)
            else if (app.session.recoveryTab == "FTP") NetworkScreen(activity, upper = false)
            else DuplicateScreen(activity, data, prefs, task, trash)
        }
    }
}

@Composable
internal fun TrashPreviewScreen(app: GalleryApp) {
    val entries by app.files.trash.collectAsStateWithLifecycle()
    val operations by app.files.operations.collectAsStateWithLifecycle()
    val entry = entries.firstOrNull { it.mediaId == app.session.trashPreviewId }
    Box(Modifier.fillMaxSize().testTag("trash-preview"), contentAlignment = Alignment.Center) {
        if (entry == null) Text(localized(R.string.text_choose_media_217616, "Scegli media"), color = MaterialTheme.colorScheme.onSurfaceVariant)
        else {
            TrashThumbnail(entry, app, Modifier.fillMaxSize().testTag("trash-preview:${entry.mediaId}"), preview = true)
            if (operations.any { it.mediaId == entry.mediaId && restoreInProgress(it) }) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = .3f)), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
    }
}

@Composable
fun TrashScreen(app: GalleryApp, data: CatalogSnapshot, task: (suspend () -> Unit) -> Unit,
    restoreElsewhere: ((FileActionRequest) -> Unit)? = null) {
    val entries by app.files.trash.collectAsStateWithLifecycle()
    val columns by app.preferences.trashColumns.collectAsStateWithLifecycle()
    val operations by app.files.operations.collectAsStateWithLifecycle()
    var restoringIds by remember { mutableStateOf(emptySet<String>()) }
    val activeRestores = operations.filter(::restoreInProgress).map { it.mediaId }.toSet()
    LaunchedEffect(operations) { restoringIds = restoringIds.filter { id -> operations.none { it.mediaId == id && it.kind in setOf("RESTORE", "RESTORE_COPY") && !restoreInProgress(it) } }.toSet() }
    val inputMode = LocalInputModeManager.current.inputMode
    var initialFocusGiven by remember { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var confirmation by remember { mutableStateOf<Pair<Boolean, List<TrashEntry>>?>(null) }
    var clockConfirm by remember { mutableStateOf(false) }
    val guard = remember { TrashClockGuard(app) }
    val orderedEntries = remember(entries) { entries.sortedWith(compareByDescending<TrashEntry> { it.deletedAt }.thenBy { it.mediaId }) }
    LaunchedEffect(orderedEntries) {
        if (orderedEntries.none { it.mediaId == app.session.trashPreviewId }) app.session.trashPreviewId = orderedEntries.firstOrNull()?.mediaId
    }
    val selectable = orderedEntries.filter { !it.purging && it.mediaId !in activeRestores && it.mediaId !in restoringIds }
    val chosen = selectable.filter { it.mediaId in selected }
    LaunchedEffect(entries) { selected = selected.filter { id -> selectable.any { it.mediaId == id } } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            GalleryIconButton(R.drawable.ph_magnifying_glass_plus, localized(R.string.text_larger_albums_806813, "Ingrandisci album"), Modifier.then(Modifier.graphicsLayer { scaleX = .85f; scaleY = .85f }), enabled = columns > 4 && entries.isNotEmpty()) {
                app.preferences.galleryView(false, "TRASH", columns = columns - 1)
            }
            GalleryIconButton(R.drawable.ph_magnifying_glass_minus, localized(R.string.text_smaller_albums_52ae9a, "Rimpicciolisci album"), Modifier.graphicsLayer { scaleX = .85f; scaleY = .85f }, enabled = columns < 8 && entries.isNotEmpty()) {
                app.preferences.galleryView(false, "TRASH", columns = columns + 1)
            }
            CacheClearAction(app, compact = true)
            if (chosen.isNotEmpty()) Text(if (chosen.size == 1) localized(R.string.trash_selected_one, "%1\$s elemento selezionato", chosen.size)
                else localized(R.string.trash_selected_count, "%1\$s elementi selezionati", chosen.size), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            else Spacer(Modifier.weight(1f))
            if (chosen.isNotEmpty()) {
                TextButton({ confirmation = true to chosen.toList() }, enabled = chosen.all { data.byId[it.mediaId] != null }, modifier = Modifier.testTag("trash-restore-selected")) {
                    Text(localized(R.string.text_restore_cf1718, "Ripristina"))
                }
                TextButton({ confirmation = false to chosen.toList() }, Modifier.testTag("trash-delete-selected")) { Text(localized(R.string.trash_delete_selected, "Elimina selezionati")) }
            } else TextButton({ confirmation = false to selectable.toList() }, enabled = selectable.isNotEmpty(), modifier = Modifier.testTag("trash-delete-all")) {
                Text(localized(R.string.trash_delete_all, "Elimina tutto"))
            }
        }
        if (guard.blocked()) Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(localized(R.string.text_the_clock_changed_automatic_cleanup_is_paused_8501bb, "L'orologio è cambiato: la pulizia automatica è sospesa."), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton({ clockConfirm = true }) { Text(localized(R.string.text_check_date_and_resume_cleanup_b9c526, "Verifica data e riprendi pulizia")) }
        }
        if (entries.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .3f), GalleryShape), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GalleryIcon(R.drawable.ph_trash, null, Modifier.size(40.dp))
                Text(localized(R.string.text_trash_is_empty_bd2e58, "Il cestino è vuoto"), style = MaterialTheme.typography.titleMedium)
                Text(localized(R.string.text_no_files_waiting_to_be_restored_c8ec60, "Nessun file in attesa di ripristino."), style = MaterialTheme.typography.bodySmall)
            }
        } else LazyVerticalGrid(GridCells.Fixed(columns), Modifier.weight(1f).testTag("trash-grid"), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(orderedEntries, key = { it.mediaId }) { entry ->
                val checked = entry in chosen
                val previewed = app.session.trashPreviewId == entry.mediaId
                val requester = remember(entry.mediaId) { FocusRequester() }
                LaunchedEffect(inputMode) {
                    if (!initialFocusGiven && inputMode == InputMode.Keyboard && entry.mediaId == orderedEntries.firstOrNull()?.mediaId) {
                        requester.requestFocus(); initialFocusGiven = true
                    }
                }
                var focused by remember(entry.mediaId) { mutableStateOf(false) }
                OutlinedCard(onClick = {
                    if (!entry.purging && entry.mediaId !in activeRestores && entry.mediaId !in restoringIds) {
                        selected = if (entry.mediaId in selected) selected - entry.mediaId else selected + entry.mediaId
                        app.session.trashPreviewId = entry.mediaId
                    }
                }, enabled = !entry.purging && entry.mediaId !in activeRestores && entry.mediaId !in restoringIds, modifier = Modifier.fillMaxWidth().focusRequester(requester).onFocusChanged { focused = it.isFocused; if (it.isFocused) app.session.trashPreviewId = entry.mediaId }
                    .semantics { this.selected = checked }.testTag("trash-item:${entry.mediaId}"),
                    border = BorderStroke(if (checked || focused || previewed) 3.dp else 1.dp, if (focused || previewed) GalleryFocus else if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                    Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                        TrashThumbnail(entry, app, Modifier.fillMaxSize())
                        if (entry.mediaId in activeRestores || entry.mediaId in restoringIds) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = .45f)).testTag("trash-restoring:${entry.mediaId}"), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                        }
                        val days = trashDaysRemaining(entry, System.currentTimeMillis())
                        Text(if (days == 1L) localized(R.string.trash_day_remaining_one, "%1\$s giorno", days)
                            else localized(R.string.trash_days_remaining, "%1\$s giorni", days),
                            Modifier.align(Alignment.TopStart).padding(6.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = .85f), GalleryShape).padding(5.dp),
                            style = MaterialTheme.typography.labelSmall)
                        if (checked) Surface(Modifier.align(Alignment.TopEnd).padding(6.dp).size(26.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                            Box(contentAlignment = Alignment.Center) { Text("✓", color = MaterialTheme.colorScheme.onPrimary) }
                        }
                    }
                }
            }
        }
    }
    confirmation?.let { (restoring, frozen) ->
        GalleryAlertDialog(onDismissRequest = { confirmation = null },
            title = { Text(if (restoring) localized(R.string.trash_restore_confirmation, "Ripristinare %1\$s file?", frozen.size)
                else localized(R.string.text_permanently_delete_1_s_files_5b028e, "Eliminare definitivamente %1\$s file?", frozen.size)) },
            text = {
                Column {
                    Text(if (restoring) localized(R.string.trash_restore_original, "I file torneranno nelle cartelle originali. Eventuali nomi già presenti saranno conservati.")
                        else localized(R.string.text_these_copies_can_no_longer_be_restored_07ed54, "Queste copie non potranno più essere ripristinate."))
                    val targets = frozen.mapNotNull { entry -> data.byId[entry.mediaId]?.copy(name = entry.originalName, size = entry.size) }
                    if (!restoring) {
                        if (targets.size == 1) DeletionTargetName(targets.single(), data) else DeletionFavoritesNotice(targets, data)
                    }
                    if (restoring && restoreElsewhere != null && targets.size == frozen.size && targets.map { it.trashed }.distinct().size == 1)
                        TextButton({ confirmation = null; restoreElsewhere(FileActionRequest(if (targets.first().trashed) "RESTORE" else "RESTORE_COPY", targets,
                            expectedTrashEntries = frozen.associateBy { it.mediaId })) },
                            Modifier.testTag("trash-restore-elsewhere")) {
                            Text(localized(R.string.text_choose_another_folder_423db4, "Scegli un'altra cartella…"))
                        }
                }
            },
            confirmButton = { TextButton({
                if (!restoring) app.sounds.play("delete")
                confirmation = null
                if (restoring) restoringIds = restoringIds + frozen.map { it.mediaId }
                task {
                    var queued = false
                    try {
                    if (restoring) frozen.forEach { entry ->
                        val item = app.catalog.snapshot.value.byId[entry.mediaId] ?: error(localized(R.string.text_media_unavailable_91dca9, "Media non disponibile"))
                        app.files.enqueue(item, if (item.trashed) "RESTORE" else "RESTORE_COPY", entry.originalAlbumId, entry.originalName, keepBoth = true, expectedTrash = entry)
                    } else app.files.purge(frozen, explicit = true)
                    queued = true
                    } finally { if (restoring && !queued) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.Main) { restoringIds = restoringIds - frozen.map { it.mediaId }.toSet() } }
                }
            }, Modifier.testTag(if (restoring) "confirm-trash-restore" else "confirm-purge")) {
                Text(if (restoring) localized(R.string.text_restore_cf1718, "Ripristina") else localized(R.string.text_delete_permanently_8a62e5, "Elimina definitivamente"))
            } }, dismissButton = { TextButton({ confirmation = null }) { Text(localized(R.string.text_cancel_18c9d9, "Annulla")) } })
    }
    if (clockConfirm) GalleryAlertDialog(onDismissRequest = { clockConfirm = false }, title = { Text(localized(R.string.text_is_the_system_date_correct_e852f9, "La data di sistema è corretta?")) },
        text = { Text(localized(R.string.text_1_s_nconfirming_resumes_cleanup_of_expired_copie_d2bb79, "%1\$s\nConfermando riattivi la pulizia delle copie scadute. Correggi prima la data se non è esatta.", java.text.DateFormat.getDateTimeInstance().format(java.util.Date()))) },
        confirmButton = { TextButton({ task { guard.acknowledge(TrashClockSample.now(app)); app.files.cleanupExpired() }; clockConfirm = false }) { Text(localized(R.string.text_confirm_date_6b308b, "Confermo la data")) } },
        dismissButton = { TextButton({ clockConfirm = false }) { Text(localized(R.string.text_cancel_18c9d9, "Annulla")) } })
}
