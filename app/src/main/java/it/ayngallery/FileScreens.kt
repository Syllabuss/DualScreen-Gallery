package it.ayngallery

import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned

import androidx.compose.foundation.*
import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.decode.BitmapFactoryDecoder
import coil3.request.ImageRequest
import coil3.video.VideoFrameDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

data class FileActionRequest(val kind: String, val targets: List<MediaEntity>, val expectedDigests: Map<String, FileDigest> = emptyMap(),
    val retainedMediaIds: Map<String, String> = emptyMap(), val albumFolders: List<AlbumEntity> = emptyList(),
    val expectedTrashEntries: Map<String, TrashEntry> = emptyMap(), val permanent: Boolean = false)

@Composable
internal fun DeletionTargetName(item: MediaEntity, data: CatalogSnapshot, modifier: Modifier = Modifier, highlight: Boolean = true) {
    val favorite = highlight && (data.byId[item.id]?.favorite ?: item.favorite)
    Row(modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (favorite) FavoriteHeart(true, null, Modifier.size(20.dp))
        Text(item.name, color = if (favorite) MaterialTheme.colorScheme.primary else LocalContentColor.current,
            fontWeight = if (favorite) FontWeight.Bold else null, textDecoration = if (favorite) TextDecoration.Underline else null)
    }
}

@Composable
internal fun DeletionFavoritesNotice(targets: List<MediaEntity>, data: CatalogSnapshot) {
    if (targets.none { data.byId[it.id]?.favorite ?: it.favorite }) return
    val highlighted = "♥ " + localized(R.string.sound_favorite, "Preferiti")
    val message = localized(R.string.favorite_deletion_notice, "La selezione include elementi %1\$s.", highlighted)
    val at = message.indexOf(highlighted)
    val color = MaterialTheme.colorScheme.primary
    Text(buildAnnotatedString {
        if (at < 0) append(message) else {
            append(message.take(at))
            withStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline)) { append(highlighted) }
            append(message.drop(at + highlighted.length))
        }
    })
}

fun fileSize(bytes: Long): String {
    if (bytes < 0) return localized(R.string.text_unknown_size_e9b931, "Dimensione sconosciuta")
    if (bytes < 1024) return "$bytes B"
    val unit = if (bytes < 1024 * 1024) "KB" else if (bytes < 1024 * 1024 * 1024L) "MB" else "GB"
    val divisor = when (unit) { "KB" -> 1024.0; "MB" -> 1024.0 * 1024; else -> 1024.0 * 1024 * 1024 }
    return "${java.text.NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(bytes / divisor)} $unit"
}

fun fileActionName(kind: String, recipe: String? = null) = when (kind) {
    "COPY", "NETWORK_COPY", "NETWORK_COPY_BATCH" -> localized(R.string.text_copy_ebd162, "Copia")
    "NETWORK_EXTRACT" -> localized(R.string.explorer_extract, "Estrai archivio")
    "IMPORT" -> localized(R.string.text_import_a_copy_9719ca, "Importa una copia")
    "MOVE", "NETWORK_MOVE", "NETWORK_MOVE_BATCH" -> localized(R.string.text_move_f44eb9, "Sposta"); "RENAME" -> localized(R.string.text_rename_98b79b, "Rinomina"); "TRASH" -> if (recipe == "DIRECT_DELETE") localized(R.string.direct_delete, "Elimina direttamente") else localized(R.string.text_move_to_trash_dd41b3, "Sposta nel cestino")
    "EXPORT_IMAGE" -> if (recipe != null && runCatching { ImageRecipe.decode(recipe).input == "GIF_FIRST_FRAME" }.getOrDefault(false))
        localized(R.string.text_export_gif_frame_a7c5cd, "Esporta fotogramma GIF") else localized(R.string.text_export_image_b28177, "Esporta immagine")
    "EXPORT_VIDEO" -> localized(R.string.text_export_video_segment_d137d3, "Esporta segmento video")
    "EXPORT_AUDIO" -> localized(R.string.text_export_audio_segment_a0faff, "Esporta segmento audio")
    "RESTORE" -> localized(R.string.text_restore_cf1718, "Ripristina"); "RESTORE_COPY" -> localized(R.string.text_recover_copy_1ee15f, "Recupera copia"); "PURGE" -> localized(R.string.text_delete_permanently_8a62e5, "Elimina definitivamente"); "MKDIR", "NETWORK_MKDIR" -> localized(R.string.text_new_folder_cf5925, "Nuova cartella")
    "ARCHIVE" -> localized(R.string.text_keep_copy_in_trash_725033, "Conserva copia nel cestino"); "DISCARD_TRASH_BACKUP" -> localized(R.string.text_keep_original_64df82, "Conserva originale"); else -> localized(R.string.text_operation_053259, "Operazione")
}

@Composable
fun FileActionDialog(request: FileActionRequest, data: CatalogSnapshot, app: GalleryApp, prefs: GalleryPreferences, activity: PanelActivity,
                     task: (suspend () -> Unit) -> Unit, accepted: () -> Unit, dismiss: () -> Unit) {
    val targets = request.targets // Frozen media IDs/locators; shared selection is deliberately never read here.
    val first = targets.firstOrNull() ?: return
    val kind = request.kind
    val trash by app.files.trash.collectAsStateWithLifecycle()
    var selected by remember(request) { mutableStateOf(if (kind in setOf("RENAME", "RESTORE", "RESTORE_COPY")) first.albumId else "") }
    var name by remember(request) { mutableStateOf(first.name) }
    var keepBoth by remember(request) { mutableStateOf(kind != "RENAME") }
    var submitting by remember(request) { mutableStateOf(false) }
    var folders by remember { mutableStateOf(false) }
    var createFolder by remember { mutableStateOf(false) }
    var createAfterChoosing by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var editingName by remember(request) { mutableStateOf(false) }
    var draftName by remember(request) { mutableStateOf(name) }
    var nameError by remember { mutableStateOf<String?>(null) }
    val destinationPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) task {
            val role = when { targets.all { it.kind == "AUDIO" } -> SourceRole.MUSIC
                targets.none { it.kind == "AUDIO" } -> SourceRole.VISUAL; else -> SourceRole.MIXED }
            val id = withContext(Dispatchers.IO) { app.catalog.authorizeDestination(uri, role, result.data?.flags ?: 0) }
            withContext(Dispatchers.Main) { selected = id }
        }
    }
    val candidates = data.albums.filter { album -> data.albumMembership.any { link -> link.albumId == album.id && link.present &&
        data.sources.any { it.id == link.sourceId && it.id != DeviceGallery.SOURCE && !it.singleDocument && it.enabled && it.reachable } } &&
        (album.id !in data.excludedAlbums || (kind in setOf("RESTORE", "RESTORE_COPY") && album.id == first.albumId)) }.sortedWith(compareBy(MediaNames.natural) { it.path })
    val destination = candidates.firstOrNull { it.id == selected }
    val renameFocus = remember(request) { androidx.compose.ui.focus.FocusRequester() }
    val renameKeyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    var renameAttached by remember(request) { mutableStateOf(false) }
    LaunchedEffect(request, renameAttached) { if (kind == "RENAME" && renameAttached) { androidx.compose.runtime.withFrameNanos { }; renameFocus.requestFocus(); renameKeyboard?.show() } }
    GalleryAlertDialog(onDismissRequest = { if (!submitting) dismiss() }, modifier = Modifier.fillMaxWidth(.94f), properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false), title = { GalleryDialogTitle(when (kind) { "COPY" -> R.drawable.ph_copy_simple; "MOVE" -> R.drawable.ph_arrow_line_up_left; "RENAME" -> R.drawable.ph_textbox; else -> R.drawable.ph_trash }, "${if (request.permanent) localized(R.string.direct_delete, "Elimina direttamente") else fileActionName(kind)}${if (targets.size > 1) localized(R.string.text_1_s_files_fe3ced, " · %1\$s file", targets.size) else ""}") },
        text = { Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (targets.size == 1) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (kind in setOf("RESTORE", "RESTORE_COPY")) trash.firstOrNull { it.mediaId == first.id }?.let { TrashThumbnail(it, app, Modifier.size(72.dp)) }
                else MediaThumbnail(first, data, app.catalog, prefs, Modifier.size(72.dp), animated = false)
                DeletionTargetName(first, data, Modifier.weight(1f).testTag("file-action-target"), highlight = kind == "TRASH")
            } else Text(targets.joinToString("\n", limit = 6, truncated = "…") { it.name })
            if (kind == "TRASH" && targets.size > 1) DeletionFavoritesNotice(targets, data)
            Text(localized(R.string.text_source_1_s_n_9a21ed, "Origine: %1\$s\n", data.albumsById[first.albumId]?.path ?: localized(R.string.text_folder_unavailable_1f6e37, "Cartella non disponibile")) +
                localized(R.string.text_1_s_files_2_s_0660da, "%1\$s file · %2\$s", targets.size, if (targets.any { it.size < 0 }) localized(R.string.text_size_needs_verification_bf47ec, "Dimensione da verificare") else fileSize(targets.sumOf { it.size })), style = MaterialTheme.typography.bodySmall)
            if (request.albumFolders.isNotEmpty()) Text(if (kind == "TRASH") localized(R.string.text_selected_media_move_to_trash_original_folders_re_6f7f18, "I media selezionati passano nel cestino; le cartelle originali restano.")
                else localized(R.string.text_album_folders_and_media_structure_will_be_recrea_1c7b5d, "Le cartelle degli album e la struttura dei media saranno ricreate nella destinazione. Nessun file estraneo ai media sarà trasferito."), style = MaterialTheme.typography.bodySmall)
            if (kind == "TRASH") {
                request.retainedMediaIds[first.id]?.let { id ->
                    Text(localized(R.string.text_keep_1_s_nthis_content_will_also_be_checked_agai_5f06f3, "Da conservare: %1\$s\nAnche questo contenuto verrà ricontrollato prima della rimozione.", data.byId[id]?.name ?: localized(R.string.text_copy_unavailable_0f205e, "Copia non disponibile")),
                        Modifier.testTag("duplicate-retained-target"), style = MaterialTheme.typography.bodySmall)
                }
                if (request.permanent) Text(localized(R.string.direct_delete_warning, "Il file verrà eliminato definitivamente e non sarà recuperabile dal cestino.")) else Text(localized(R.string.text_a_verified_copy_will_be_kept_for_seven_days_the_dcf62b, "Una copia verificata verrà conservata per sette giorni. L'originale sarà rimosso solo dopo la verifica."))
                if (!request.permanent) Text(localized(R.string.text_uninstalling_the_app_or_clearing_its_data_also_d_cecefb, "Disinstallare l'app o cancellarne i dati elimina anche il cestino."), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                if (kind != "RENAME") {
                    Box {
                        GalleryButton(destination?.let { localized(R.string.text_destination_1_s_27ea61, "Destinazione: %1\$s", it.path) } ?: localized(R.string.text_choose_destination_7320c1, "Scegli destinazione"), Modifier.fillMaxWidth()) { folders = true }
                        if (folders) GalleryAlertDialog(onDismissRequest = { folders = false; createAfterChoosing = false }, modifier = Modifier.widthIn(max = 700.dp).fillMaxWidth(.94f), properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false), title = { Text(localized(R.string.text_choose_the_destination_8c4b9e, "Scegli la destinazione")) },
                            text = { LazyVerticalGrid(GridCells.Fixed(2), Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(candidates, key = { it.id }) { album ->
                                    Surface(Modifier.fillMaxWidth().clickable { selected = album.id; folders = false; if (createAfterChoosing) { createAfterChoosing = false; createFolder = true } }, shape = GalleryShape,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            MediaThumbnail(data.cover(album), data, app.catalog, prefs, Modifier.size(56.dp))
                                            Column(Modifier.weight(1f)) { Text(album.name, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis); Text(album.path, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) }
                                        }
                                    }
                                }
                                if (candidates.isEmpty()) item { Text(localized(R.string.text_no_authorized_folder_use_choose_another_folder_t_c6cfc5, "Nessuna cartella autorizzata. Usa «Scegli un'altra cartella» per concedere accesso.")) }
                            } }, confirmButton = { TextButton({ folders = false }) { Text(localized(R.string.text_back_804268, "Indietro")) } })
                    }
                    GalleryButton(localized(R.string.text_choose_another_folder_423db4, "Scegli un'altra cartella…"), Modifier.fillMaxWidth()) {
                        app.session.current(activity)?.let { here -> destinationPicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
                            .putExtra(ActivityResultContracts.StartActivityForResult.EXTRA_ACTIVITY_OPTIONS_BUNDLE,
                                ActivityOptions.makeBasic().setLaunchDisplayId(here.id).toBundle())) }
                    }
                    GalleryButton(when (kind) { "MOVE" -> localized(R.string.new_album_move, "Sposta in un nuovo album…"); "COPY" -> localized(R.string.new_album_copy, "Copia in un nuovo album…"); else -> localized(R.string.new_empty_album, "Crea un nuovo album vuoto") }, Modifier.fillMaxWidth()) {
                        if (destination != null) createFolder = true else { createAfterChoosing = true; folders = true }
                    }
                } else Text(localized(R.string.text_destination_same_folder_a0be2e, "Destinazione: stessa cartella"))
                if (targets.size == 1 && request.albumFolders.isEmpty()) {
                    if (kind == "RENAME") OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().focusRequester(renameFocus).onGloballyPositioned { renameAttached = true }.testTag("file-action-name-input"), singleLine = true,
                        label = { Text(localized(R.string.text_file_name_a26de2, "Nome del file")) })
                    else GalleryButton(localized(R.string.text_name_1_s_7ad389, "Nome: %1\$s", name), Modifier.fillMaxWidth()) { draftName = name; nameError = null; editingName = true }
                }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(keepBoth, { keepBoth = it }); Text(localized(R.string.text_keep_both_if_the_name_is_taken_cbae14, "Mantieni entrambe se il nome è occupato")) }
                Text(localized(R.string.text_no_files_will_be_overwritten_favorites_and_colle_5a422b, "Non verrà sovrascritto alcun file. Preferiti e raccolte seguono spostamenti e ripristini."), style = MaterialTheme.typography.bodySmall)
            }
            if (kind in setOf("MOVE", "RENAME", "TRASH")) Text(localized(R.string.text_if_the_file_is_playing_playback_stops_before_rem_8136e9, "Se il file è in riproduzione, verrà fermato prima della rimozione."), style = MaterialTheme.typography.bodySmall)
        } }, confirmButton = {
            TextButton(modifier = Modifier.testTag("confirm-file-action"), enabled = !submitting && (kind == "TRASH" || destination != null) && name.isNotBlank(), onClick = {
                if (kind == "TRASH") app.sounds.play("delete")
                submitting = true
                val frozenName = name; val frozenDestination = destination?.id; val frozenKeepBoth = keepBoth
                task {
                    try {
                        val operationIds = if (kind in setOf("MOVE", "COPY") && request.albumFolders.isNotEmpty()) {
                            app.files.enqueueAlbumMedia(targets, request.albumFolders, kind, requireNotNull(frozenDestination), frozenKeepBoth)
                            emptyList<String>()
                        } else app.files.enqueueAll(targets, kind, frozenDestination, if (targets.size == 1) frozenName else null, frozenKeepBoth,
                            expectedDigests = request.expectedDigests, retainedMediaIds = request.retainedMediaIds, expectedTrashEntries = request.expectedTrashEntries, permanent = request.permanent)
                        val acceptedIds = app.catalog.dao.operations().filter { it.id in operationIds && it.phase !in setOf("SKIPPED", "FAILED", "CANCELLED") }.map { it.mediaId }.toSet()
                        withContext(Dispatchers.Main) { if (kind == "TRASH") app.session.afterTrashRequested(acceptedIds); accepted() }
                    } finally { withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { submitting = false } }
                }
            }) { Text(if (submitting) localized(R.string.text_preparing_7e2891, "Preparazione…") else if (request.permanent) localized(R.string.direct_delete, "Elimina direttamente") else fileActionName(kind)) }
        }, dismissButton = { TextButton({ if (!submitting) dismiss() }, enabled = !submitting) { Text(localized(R.string.text_cancel_18c9d9, "Annulla")) } })
    if (editingName) {
        val textState = rememberTextFieldState(draftName)
        val nameFocus = remember { androidx.compose.ui.focus.FocusRequester() }
        val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
        var nameAttached by remember { mutableStateOf(false) }
        LaunchedEffect(nameAttached) { if (nameAttached) { androidx.compose.runtime.withFrameNanos { }; nameFocus.requestFocus(); keyboard?.show() } }
        var nameFocused by remember { mutableStateOf(false) }
        GalleryAlertDialog(onDismissRequest = { editingName = false }, modifier = Modifier.fillMaxWidth(.94f), properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false), title = { GalleryDialogTitle(R.drawable.ph_textbox, localized(R.string.text_file_name_a26de2, "Nome del file")) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(localized(R.string.text_keep_the_original_extension_the_name_does_not_ch_02a5b8, "Mantieni l'estensione originale. Il nome non cambia il formato del file."), style = MaterialTheme.typography.bodySmall)
            BasicTextField(textState, Modifier.fillMaxWidth().focusRequester(nameFocus).onGloballyPositioned { nameAttached = true }.testTag("file-action-name-input")
                .semantics { contentDescription = localized(R.string.text_file_name_a26de2, "Nome del file") }.onFocusChanged { nameFocused = it.isFocused }
                .border(if (nameFocused) 3.dp else 1.dp, if (nameError != null) MaterialTheme.colorScheme.error else if (nameFocused) GalleryFocus
                    else MaterialTheme.colorScheme.outline, GalleryShape).padding(16.dp),
                lineLimits = TextFieldLineLimits.SingleLine, textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary))
            nameError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton({
            try { val value = textState.text.toString(); FileNames.validate(value, first.name); name = value; editingName = false }
            catch (error: IllegalArgumentException) { nameError = error.message }
        }, enabled = textState.text.isNotBlank()) { Text(localized(R.string.text_use_this_name_c09501, "Usa questo nome")) } }, dismissButton = { TextButton({ editingName = false }) { Text(localized(R.string.text_cancel_18c9d9, "Annulla")) } })
    }
    if (createFolder) {
        val folderName = rememberTextFieldState()
        var folderFocused by remember { mutableStateOf(false) }
        GalleryAlertDialog(onDismissRequest = { if (!creating) createFolder = false }, title = { Text(localized(R.string.text_new_folder_cf5925, "Nuova cartella")) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(destination?.path ?: localized(R.string.text_destination_unavailable_1a3939, "Destinazione non disponibile"))
            BasicTextField(folderName, Modifier.fillMaxWidth().testTag("file-folder-name-input")
                .semantics { contentDescription = localized(R.string.text_folder_name_7eaa40, "Nome della cartella") }.onFocusChanged { folderFocused = it.isFocused }
                .border(if (folderFocused) 3.dp else 1.dp, if (folderFocused) GalleryFocus else MaterialTheme.colorScheme.outline, GalleryShape).padding(16.dp),
                lineLimits = TextFieldLineLimits.SingleLine, textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary))

        } }, confirmButton = { TextButton(enabled = !creating && folderName.text.isNotBlank() && destination != null, onClick = {
            val parent = destination!!.id; val chosenName = folderName.text.toString()
            creating = true
            task {
                try {
                    val id = app.files.createFolder(parent, chosenName.trim(), start = false, reuseExisting = true)
                    app.files.execute(id)
                    val op = app.catalog.dao.operation(id)!!
                    check(op.phase == "COMPLETED") { op.message ?: localized(R.string.text_folder_not_created_c4c5b9, "Cartella non creata") }
                    withContext(Dispatchers.Main) { selected = op.resultMediaId; createFolder = false }
                } finally { withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { creating = false } }
            }
        }) { Text(if (creating) localized(R.string.text_creating_ad9b47, "Creazione…") else localized(R.string.text_create_9bf626, "Crea")) } }, dismissButton = { TextButton({ createFolder = false }, enabled = !creating) { Text(localized(R.string.text_cancel_18c9d9, "Annulla")) } })
    }
}

@Composable
fun OperationsScreen(app: GalleryApp, task: (suspend () -> Unit) -> Unit) {
    val operations by app.files.operations.collectAsStateWithLifecycle()
    val prefs by app.preferences.state.collectAsStateWithLifecycle()
    var notifications by remember { mutableStateOf(NotificationManagerCompat.from(app).areNotificationsEnabled()) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notifications = NotificationManagerCompat.from(app).areNotificationsEnabled()
    }
    if (operations.isEmpty()) { EmptyGallery(localized(R.string.text_all_done_046f31, "Tutto in ordine"), localized(R.string.text_progress_results_and_recovery_of_your_file_opera_e3a3f2, "Qui trovi progresso, risultati e recupero delle operazioni sui tuoi file."), R.drawable.ph_folders); return }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(localized(R.string.text_each_file_has_its_own_result_a_successful_copy_w_1dda18, "Ogni file ha il proprio risultato. Una copia riuscita e una cancellazione negata restano uno spostamento parziale."), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (Build.VERSION.SDK_INT >= 33 && !notifications) item {
            Text(localized(R.string.text_operations_continue_without_notifications_enable_5afbfb, "Le operazioni continuano anche senza notifiche. Puoi abilitarle per seguire le copie mentre usi altre app."), style = MaterialTheme.typography.bodySmall)
            GalleryButton(localized(R.string.text_enable_operation_notifications_d6bcd6, "Abilita notifiche delle operazioni")) { notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
        }
        items(operations.filter { !FileOperations.terminal(it.phase) } + operations.filter { FileOperations.terminal(it.phase) }.take(200), key = { it.id }) { op ->
            Surface(shape = GalleryShape, color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${fileActionName(op.kind, op.recipe)} · ${op.originalName}", style = MaterialTheme.typography.titleMedium)
                    Text(when (op.phase) {
                        "QUEUED" -> localized(R.string.text_waiting_6f3524, "In attesa"); "CREATING" -> localized(R.string.text_preparing_copy_ccb050, "Preparazione della copia"); "COPYING" -> localized(R.string.text_copying_0ed6bc, "Copia in corso")
                        "STAGING" -> localized(R.string.text_acquiring_external_file_47201b, "Acquisizione del file esterno"); "STAGE_VERIFIED", "STAGED" -> localized(R.string.text_private_copy_verified_0e4d4b, "Copia privata verificata")
                        "DECODING" -> localized(R.string.text_preparing_image_07c726, "Preparazione dell'immagine"); "ENCODING" -> localized(R.string.text_encoding_new_copy_cddadd, "Codifica della nuova copia")
                        "DEFERRED" -> localized(R.string.text_deferred_in_game_mode_318f70, "Rimandata in modalità Gioco")
                        "VERIFYING", "VERIFIED" -> localized(R.string.text_verifying_integrity_a2ae93, "Verifica dell'integrità"); "PUBLISHING", "PUBLISHED" -> localized(R.string.text_publishing_destination_c735d6, "Pubblicazione della destinazione")
                        "DELETING" -> localized(R.string.text_removing_original_please_wait_5de067, "Rimozione dell'originale · attendi"); "DELETED", "INDEXING" -> localized(R.string.text_updating_references_7a459f, "Aggiornamento dei riferimenti")
                        "COMPLETED" -> localized(R.string.text_completed_97967f, "Completata"); "COPY_ONLY" -> localized(R.string.text_copy_retained_move_incomplete_9fedd1, "Copia conservata · spostamento non completato")
                        "CANCELLED" -> localized(R.string.text_canceled_fdb1ba, "Annullata"); "SKIPPED" -> localized(R.string.text_skipped_9006eb, "Saltata"); "PURGING" -> localized(R.string.text_permanent_cleanup_in_progress_dbdb7b, "Pulizia definitiva in corso")
                        "CLEANUP" -> if (op.kind == "IMPORT") localized(R.string.text_imported_cleaning_private_copy_343dbb, "Importato · pulizia copia privata") else localized(R.string.text_restored_cleaning_private_copy_4c8f4d, "Ripristinato · pulizia copia privata")
                        "ORIGINAL_ONLY" -> localized(R.string.text_original_preserved_015250, "Originale conservato")
                        "RESTORED_WITH_BACKUP" -> localized(R.string.text_restored_backup_copy_retained_ea9ba2, "Ripristinato · copia di sicurezza conservata")
                        "PARTIAL" -> localized(R.string.text_partial_result_6dc371, "Risultato parziale"); "INTERRUPTED" -> localized(R.string.text_interrupted_verification_required_98ab9d, "Interrotta · richiede verifica")
                        else -> localized(R.string.text_incomplete_90fb31, "Non completata")
                    }, color = if (op.phase in setOf("PARTIAL", "FAILED")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    if (op.phase in setOf("STAGING", "COPYING", "VERIFYING", "DECODING", "ENCODING") && !prefs.reducedMotion) {
                        if (op.expectedSize > 0 && !FileOperations.export(op.kind)) LinearProgressIndicator(progress = { (op.bytes.toFloat() / op.expectedSize).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    if (op.kind != "MKDIR") Text("${fileSize(op.bytes)}${if (op.expectedSize >= 0 && !FileOperations.export(op.kind)) " / ${fileSize(op.expectedSize)}" else ""}" +
                        (op.finalName?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
                    op.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (!FileOperations.terminal(op.phase)) {
                        if (op.phase in setOf("PARTIAL", "FAILED", "INTERRUPTED", "DEFERRED")) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GalleryButton(localized(R.string.text_resume_b0cc9c, "Riprendi"), Modifier.weight(1f)) { task { app.files.retry(op.id) } }
                            if (op.phase == "PARTIAL" && op.kind in setOf("COPY", "MOVE", "RENAME"))
                                GalleryButton(localized(R.string.text_keep_copy_782cd4, "Conserva copia"), Modifier.weight(1f)) { task { app.files.keepCopy(op.id) } }
                        }
                        if (op.phase == "PARTIAL" && op.kind in setOf("TRASH", "DISCARD_TRASH_BACKUP")) {
                            if (op.kind == "TRASH") GalleryButton(localized(R.string.text_keep_original_64df82, "Conserva originale"), Modifier.fillMaxWidth()) { task { app.files.resolveTrash(op.id, keepArchive = false) } }
                            GalleryButton(localized(R.string.text_keep_copy_in_trash_725033, "Conserva copia nel cestino"), Modifier.fillMaxWidth()) { task { app.files.resolveTrash(op.id, keepArchive = true) } }
                        }
                        if (op.phase == "PARTIAL" && op.kind == "RESTORE") GalleryButton(localized(R.string.text_keep_backup_copy_bfdd54, "Mantieni copia di sicurezza"), Modifier.fillMaxWidth()) {
                            task { app.files.keepRestoredBackup(op.id) }
                        }
                        if (op.kind != "PURGE" && op.phase !in setOf("DELETING", "DELETED", "INDEXING")) GalleryButton(localized(R.string.text_cancel_when_safe_cb9d24, "Annulla quando sicuro"), enabled = !op.cancelRequested) {
                            task { app.files.cancel(op.id); if (!op.kind.startsWith("NETWORK_")) app.files.schedule(op.id) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TrashThumbnail(entry: TrashEntry, app: GalleryApp, modifier: Modifier = Modifier, preview: Boolean = false) {
    val context = LocalContext.current
    val data by app.catalog.snapshot.collectAsStateWithLifecycle()
    val item = data.byId[entry.mediaId]
    val file = remember(entry.fileName) { app.files.trashFile(entry) }
    if (item?.kind == "AUDIO" || entry.mime.startsWith("audio/")) Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        GalleryIcon(R.drawable.ph_music_notes, entry.originalName)
    } else {
        val request = remember(entry.fileName, entry.digest, preview) { ImageRequest.Builder(context).data(file).size(if (preview) 1600 else 320, if (preview) 1600 else 320)
            .memoryCacheKey("trash:${entry.fileName}:${entry.digest}").apply {
                if (item?.kind == "VIDEO" || entry.mime.startsWith("video/")) decoderFactory(VideoFrameDecoder.Factory()) else decoderFactory(BitmapFactoryDecoder.Factory())
            }.build() }
        AsyncImage(request, entry.originalName, modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentScale = if (preview) ContentScale.Fit else ContentScale.Crop)
    }
}
