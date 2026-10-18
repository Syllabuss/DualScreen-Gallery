package it.ayngallery

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun NetworkScreen(activity: PanelActivity, upper: Boolean) {
    val app = activity.application as GalleryApp
    val screenScope = rememberCoroutineScope()
    val explorer = app.explorer
    val browsing by explorer.browsing
    val nav by explorer.state.collectAsState()
    val dual = app.session.canSwap()
    val form by explorer.connectionForm
    val transferRequest by explorer.transferRequest
    val preview by explorer.preview
    val jobsVisible by explorer.jobsVisible
    val recovery by explorer.recoveryRequest
    val archive by explorer.zipRequest
    val actions by explorer.actions
    val preferences by app.preferences.state.collectAsState()
    val errors by explorer.errors.collectAsState()
    val errorBelow = !dual || app.session.screens.firstOrNull { it.key == activity.screenKey }?.id != android.view.Display.DEFAULT_DISPLAY
    if (browsing && !form && errorBelow) errors[ExplorerSide.REMOTE]?.let { message ->
        val transferError = message == R.string.explorer_transfer_error
        GalleryAlertDialog(onDismissRequest = { explorer.errors.value = explorer.errors.value - ExplorerSide.REMOTE },
            modifier = Modifier.testTag("explorer-connection-error"),
            properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false),
            icon = { GalleryIcon(R.drawable.ph_info, null) },
            title = { Text(explorerText(if (transferError) R.string.text_operations_389997 else R.string.explorer_connect)) },
            text = { Text(explorerText(message)) },
            confirmButton = { if (!transferError) TextButton({ explorer.connectionForm.value = true }) { Text(explorerText(R.string.explorer_connect)) } },
            dismissButton = { TextButton({ explorer.errors.value = explorer.errors.value - ExplorerSide.REMOTE }) { Text(explorerText(R.string.text_close_32d407)) } })
    }
    Column(Modifier.fillMaxSize()) {
        if (!browsing) Box(Modifier.weight(1f)) { FtpScreen(activity, guideOnly = dual && app.session.current(activity)?.id != android.view.Display.DEFAULT_DISPLAY) }
        else {
            if (!dual) Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExplorerSide.entries.forEach { side -> FilterChip(nav.active == side, { explorer.active(side) },
                    { Text(explorerText(if (side == ExplorerSide.LOCAL) R.string.explorer_device else R.string.explorer_computer)) }) }
            }
            val side = if (dual) { if (upper) ExplorerSide.REMOTE else ExplorerSide.LOCAL } else nav.active
            ExplorerPane(app, side, Modifier.weight(1f))
        }
    }
    if (browsing && form && (!upper || !dual)) ExplorerConnectionDialog(nav.remote.loading,
        explorer.lastAddress, explorer.lastUser, { explorer.connectionForm.value = false }, draft = explorer::draftConnection) { address, user, password ->
        explorer.connect(address, user, password); explorer.connectionForm.value = false
    }
    if (actions != null && (!dual || upper != preferences.actionMenusBelow)) {
        val side = actions!!
        val selected = nav.pane(side).frozenSelection()
        val other = nav.pane(if (side == ExplorerSide.LOCAL) ExplorerSide.REMOTE else ExplorerSide.LOCAL)
        GallerySideSheet({ explorer.actions.value = null }) {
            Text(localized(R.string.trash_selected_count, "%1\$s elementi selezionati", selected.size))
            if (selected.size == 1 && !selected.single().directory) GalleryButton(localized(R.string.text_preview_c10f62, "Anteprima"), Modifier.fillMaxWidth()) { explorer.actions.value = null; explorer.preview(side, selected.single()) }
            GalleryButton(localized(R.string.explorer_copy, "Copia"), Modifier.fillMaxWidth(), enabled = other.location != null) { explorer.actions.value = null; explorer.transferRequest.value = side to false }
            GalleryButton(localized(R.string.explorer_move, "Sposta"), Modifier.fillMaxWidth(), enabled = other.location != null && selected.all { it.writable }) { explorer.actions.value = null; explorer.transferRequest.value = side to true }
        }
    }
    if (recovery != null && (!upper || !dual)) {
        val job = recovery!!
        GalleryAlertDialog(onDismissRequest = { explorer.recoveryRequest.value = null },
            title = { Text(localized(R.string.network_recovery, "Recupera trasferimento")) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(job.originalName)
                Text(localized(R.string.network_recovery_help, ""))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({ explorer.recoveryRequest.value = null; explorer.recoveryLocalPicker.value = true }) { Text(localized(R.string.explorer_device, "")) }
                    TextButton({ explorer.recoveryRequest.value = null; explorer.connectionForm.value = true }) { Text(localized(R.string.explorer_connect, "")) }
                }
                Text("${localized(R.string.network_source, "")}: ${job.sourceKey} / ${job.sourceAlbumId}", maxLines = 3)
                Text("${localized(R.string.network_destination, "")}: ${job.destinationAlbumId} / ${job.destinationParent}", maxLines = 3)
            } },
            confirmButton = { Button({ explorer.recover(job) }, enabled = explorer.recoveryReady(job)) { Text(localized(R.string.network_resume, "Riprendi")) } },
            dismissButton = { TextButton({ explorer.recoveryRequest.value = null }) { Text(localized(R.string.text_cancel_18c9d9, "Annulla")) } })
    }
    if (archive != null && (!upper || !dual)) GalleryAlertDialog(onDismissRequest = { explorer.zipRequest.value = null },
        title = { Text(localized(R.string.explorer_extract, "Estrai archivio")) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(archive!!.entry.name); Text(localized(R.string.explorer_transfer_help, "")); ExplorerSide.entries.forEach { side -> Text("${localized(if (side == ExplorerSide.LOCAL) R.string.explorer_device else R.string.explorer_computer, "")}: ${nav.pane(side).location?.directoryId.orEmpty()}", maxLines = 2, overflow = TextOverflow.Ellipsis) } } },
        confirmButton = { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { ExplorerSide.entries.forEach { side ->
            Button({ val frozen = archive!!; explorer.zipRequest.value = null; explorer.extract(frozen, side) }, enabled = nav.pane(side).location != null) { Text(localized(if (side == ExplorerSide.LOCAL) R.string.explorer_device else R.string.explorer_computer, "")) }
        } } })
    if (jobsVisible && (!upper || !dual)) GalleryAlertDialog(onDismissRequest = { explorer.jobsVisible.value = false }, title = { Text(localized(R.string.text_operations_389997, "Operazioni")) },
        text = { Box(Modifier.fillMaxWidth().heightIn(max = 320.dp)) { OperationsScreen(app) { action -> screenScope.launch { try { action() } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { app.session.fail(localized(R.string.explorer_transfer_error, "")) } } } } },
        confirmButton = { TextButton({ explorer.jobsVisible.value = false }) { Text(localized(R.string.text_close_32d407, "Chiudi")) } })
    if (preview != null && (upper || !dual)) ExplorerPreviewDialog(activity, preview!!) { explorer.preview.value = null }
    if (transferRequest != null && (!upper || !dual)) {
        val request = transferRequest!!
        val from = nav.pane(request.first)
        val to = nav.pane(if (request.first == ExplorerSide.LOCAL) ExplorerSide.REMOTE else ExplorerSide.LOCAL)
        GalleryAlertDialog(onDismissRequest = { explorer.transferRequest.value = null },
            title = { Text(localized(if (request.second) R.string.explorer_move else R.string.explorer_copy, "")) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(localized(R.string.trash_selected_count, "%1\$s elementi selezionati", from.selected.size))
                Text(localized(R.string.text_destination_1_s_27ea61, "Destinazione: %1\$s", to.location?.directoryId.orEmpty()))
                Text(localized(R.string.explorer_transfer_help, ""), style = MaterialTheme.typography.bodySmall)
            } }, confirmButton = { Button({ explorer.transferRequest.value = null; explorer.transfer(request.first, request.second) }) { Text(localized(R.string.explorer_continue, "Continua")) } },
            dismissButton = { TextButton({ explorer.transferRequest.value = null }) { Text(localized(R.string.text_cancel_18c9d9, "Annulla")) } })
    }
}

@Composable
internal fun ExplorerPane(app: GalleryApp, side: ExplorerSide, modifier: Modifier = Modifier) {
    val session = app.explorer
    val navigation by session.state.collectAsState()
    val errors by session.errors.collectAsState()
    val localName by session.localFolderName.collectAsState()
    val grids by session.grids.collectAsState()
    val gridView = side in grids
    val pane = navigation.pane(side)
    val transferring by session.transfers.running.collectAsState()
    val transferred by session.transfers.bytes.collectAsState()
    val transferName by session.transfers.name.collectAsState()
    val complete by session.transfers.completed.collectAsState()
    val total by session.transfers.total.collectAsState()
    val target = navigation.pane(if (side == ExplorerSide.LOCAL) ExplorerSide.REMOTE else ExplorerSide.LOCAL)
    val listState = remember(side, pane.location) { session.position(side, pane.location).let { LazyGridState(it.first, it.second) } }
    LaunchedEffect(listState, pane.loading) {
        val at = pane.location
        if (at != null && !pane.loading) snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { session.position(side, at, it.first, it.second) }
    }
    LaunchedEffect(pane.focusedId) {
        val index = pane.entries.indexOfFirst { it.id == pane.focusedId }
        if (index >= 0 && listState.layoutInfo.visibleItemsInfo.none { it.index == index }) listState.animateScrollToItem(index)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) try {
            try { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            catch (_: SecurityException) { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            session.choose(uri)
        } catch (_: Exception) { session.errors.value = session.errors.value + (side to R.string.explorer_local_error) }
    }
    val recoveryPicker by session.recoveryLocalPicker
    LaunchedEffect(recoveryPicker) { if (side == ExplorerSide.LOCAL && recoveryPicker) { session.recoveryLocalPicker.value = false; picker.launch(null) } }
    Column(modifier.fillMaxWidth().padding(8.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, GalleryShape).testTag("explorer-${side.name.lowercase()}").padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GalleryIcon(if (side == ExplorerSide.LOCAL) R.drawable.ph_folders else R.drawable.ph_desktop_tower, null)
            Text(explorerText(if (side == ExplorerSide.LOCAL) R.string.explorer_device else R.string.explorer_computer),
                Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { session.active(side) }, enabled = navigation.active != side) { Text(explorerText(R.string.explorer_control)) }
            GalleryIconButton(if (gridView) R.drawable.ph_list else R.drawable.ph_squares_four, localized(if (gridView) R.string.text_list_view_8cb747 else R.string.text_grid_view_c4886b, "")) { session.grid(side, !gridView) }
            GalleryIconButton(R.drawable.ph_rows, localized(R.string.text_operations_389997, "Operazioni")) { session.jobsVisible.value = true }
            if (side == ExplorerSide.REMOTE && (pane.location != null || pane.loading)) GalleryIconButton(R.drawable.ph_x,
                explorerText(R.string.text_close_32d407)) { session.disconnect() }
            TextButton(onClick = { if (side == ExplorerSide.LOCAL) picker.launch(null) else session.connectionForm.value = true }, enabled = !pane.loading) {
                Text(explorerText(if (side == ExplorerSide.LOCAL) R.string.ftp_choose else R.string.explorer_connect))
            }
        }
        if (pane.location != null) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (side == ExplorerSide.LOCAL) "/" + localName.trim('/')
                else "/" + pane.location!!.directoryId.replace('\\', '/').trim('/'),
                Modifier.weight(1f).clickable(enabled = pane.back.isNotEmpty(), onClickLabel = null) { session.previous(side) }, maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = { session.refresh(side) }, enabled = !pane.loading) { Text(explorerText(R.string.explorer_refresh)) }
        }
        if (pane.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (side == ExplorerSide.LOCAL) errors[side]?.let { Text(explorerText(it), color = MaterialTheme.colorScheme.error) }
        if (pane.location == null && !pane.loading) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    GalleryIcon(if (side == ExplorerSide.LOCAL) R.drawable.ph_folders else R.drawable.ph_desktop_tower, null, Modifier.size(48.dp))
                    Text(explorerText(if (side == ExplorerSide.LOCAL) R.string.explorer_local_intro else R.string.explorer_remote_intro))
                    Button(onClick = { if (side == ExplorerSide.LOCAL) picker.launch(null) else session.connectionForm.value = true }) {
                        Text(explorerText(if (side == ExplorerSide.LOCAL) R.string.ftp_choose else R.string.explorer_connect))
                    }
                }
            }
        } else LazyVerticalGrid(GridCells.Fixed(if (gridView) 3 else 1), Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!pane.loading && pane.entries.isEmpty() && errors[side] == null) item { Text(explorerText(R.string.explorer_empty)) }
            items(pane.entries, key = { it.id }) { entry ->
                val selected = pane.selected.values.any { it.id == entry.id && it.location == entry.location }
                Surface(shape = GalleryShape, color = if (entry.id == pane.focusedId && navigation.active == side)
                    MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
                    Row(Modifier.fillMaxWidth().combinedClickable(onLongClick = { session.focus(side, entry.id); session.options(side) }, onClick = {
                        session.active(side); session.focus(side, entry.id)
                        if (entry.directory) session.open(side, entry.location.copy(directoryId = entry.id))
                        else if (selected) session.preview(side, entry) else session.select(side, entry)
                    }).padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val extension = entry.name.substringAfterLast('.', "").lowercase()
                        GalleryIcon(when {
                            entry.directory -> R.drawable.ph_folders
                            extension in setOf("jpg", "jpeg", "png", "gif", "webp", "avif", "heic") -> R.drawable.ph_images
                            extension in setOf("mp4", "mkv", "webm", "avi", "mov") -> R.drawable.ph_film_strip
                            extension in setOf("mp3", "flac", "wav", "ogg", "m4a", "aac") -> R.drawable.ph_music_notes
                            else -> R.drawable.ph_info
                        }, null)
                        Column(Modifier.weight(1f)) {
                            Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            entry.size?.let { Text(android.text.format.Formatter.formatFileSize(app, it), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        Checkbox(selected, { session.active(side); session.focus(side, entry.id); session.select(side, entry) })
                    }
                }
            }
        }
        if (transferring) Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp)); Text("$complete/$total · $transferName · ${android.text.format.Formatter.formatFileSize(app, transferred)}", Modifier.weight(1f).padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton({ session.transfers.cancel() }) { Text(localized(R.string.text_cancel_18c9d9, "Annulla")) }
        } else if (pane.selected.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val eligible = target.location != null && pane.selected.values.all { it.readable && (it.directory || it.size != null) }
            Button({ session.transferRequest.value = side to false }, enabled = eligible) { Text(localized(R.string.explorer_copy, "Copia")) }
            OutlinedButton({ session.transferRequest.value = side to true }, enabled = eligible && pane.selected.values.all { it.writable }) { Text(localized(R.string.explorer_move, "Sposta")) }
        }
    }
}

@Composable
internal fun ExplorerConnectionDialog(busy: Boolean, lastAddress: String, lastUser: String, dismiss: () -> Unit, draft: (String, String) -> Unit = { _, _ -> }, connect: (String, String, CharArray) -> Unit) {
    var address by remember { mutableStateOf(lastAddress.ifBlank { "smb://192.168.1.100/DSGallery" }) }
    var user by remember { mutableStateOf(lastUser.ifBlank { "PC\\user" }) }
    var password by remember { mutableStateOf("") }
    GalleryAlertDialog(onDismissRequest = dismiss,
        modifier = Modifier.fillMaxWidth(.94f),
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(explorerText(R.string.explorer_connect)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(explorerText(R.string.explorer_connection_help), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(address, { address = it; draft(address, user) }, Modifier.fillMaxWidth().testTag("explorer-address"), label = { Text(explorerText(R.string.explorer_address)) }, placeholder = { Text("smb://192.168.1.100/DSGallery", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .55f)) }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(user, { user = it; draft(address, user) }, Modifier.weight(1f).testTag("explorer-user"), label = { Text(explorerText(R.string.ftp_username)) }, placeholder = { Text("PC\\user", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .55f)) }, singleLine = true)
                OutlinedTextField(password, { password = it }, Modifier.weight(1f).testTag("explorer-password"), label = { Text(explorerText(R.string.ftp_password)) }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            }
            Text(explorerText(R.string.explorer_password_help), style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GalleryIcon(R.drawable.ph_info, null, Modifier.size(20.dp))
                Text(explorerText(R.string.explorer_example_help), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } },
        confirmButton = { Button(onClick = { val secret = password.toCharArray(); password = ""; connect(address, user, secret) },
            enabled = !busy && address.isNotBlank() && user.isNotBlank()) { Text(explorerText(R.string.explorer_connect)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(explorerText(R.string.text_cancel_18c9d9)) } })
}

private fun explorerText(id: Int) = localized(id, "")
