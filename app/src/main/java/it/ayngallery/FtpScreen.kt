package it.ayngallery

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun FtpScreen(activity: PanelActivity, guideOnly: Boolean = false) {
    val app = activity.application as GalleryApp
    val state by app.ftp.state.collectAsState()
    val operations by app.files.operations.collectAsState()
    LaunchedEffect(state.running) { if (!state.running) app.explorer.ftpTransfersVisible.value = false }
    val separateGuide = app.session.canSwap()
    val rootName by produceState<String?>(null, state.tree) {
        value = state.tree?.let { raw -> withContext(Dispatchers.IO) {
            runCatching { val folder = FtpSharedFolder(app, Uri.parse(raw)); folder.documents.stat(folder.root).name }.getOrNull()
        } }
    }
    var localError by remember { mutableStateOf<String?>(null) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) try {
            app.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            app.ftp.choose(tree); localError = null
        } catch (error: Exception) { localError = error.message }
    }
    Column(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.weight(1f).testTag(if (guideOnly) "ftp-guide" else "ftp-controls"),
        contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GalleryIcon(R.drawable.ph_desktop_tower, null, Modifier.size(36.dp))
                Text(localized(R.string.ftp_title, "Condivisione FTP"), style = MaterialTheme.typography.headlineSmall)
            }
        }
        if (!guideOnly) {
            item { Text(localized(R.string.ftp_description, "Invia e ricevi file dal PC o dal telefono nella cartella scelta. La condivisione rimane attiva finché la fermi.")) }
            item {
                Surface(shape = GalleryShape, color = MaterialTheme.colorScheme.surfaceVariant) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(localized(R.string.ftp_folder, "Cartella condivisa"), style = MaterialTheme.typography.labelMedium)
                            Text(rootName ?: localized(R.string.ftp_choose, "Scegli cartella"), style = MaterialTheme.typography.titleMedium)
                        }
                        GalleryIconButton(R.drawable.ph_folders, localized(R.string.ftp_choose, "Scegli cartella"), enabled = !state.running && !state.starting) {
                            val initial = state.tree?.let(Uri::parse)
                            val fullStorage = android.os.Build.VERSION.SDK_INT >= 30 && android.os.Environment.isExternalStorageManager()
                            if (fullStorage) app.contentResolver.notifyChange(android.provider.DocumentsContract.buildRootsUri(SharedStorageDocuments.AUTHORITY), null)
                            folderPicker.launch(if (fullStorage && initial?.authority != SharedStorageDocuments.AUTHORITY)
                                android.provider.DocumentsContract.buildRootUri(SharedStorageDocuments.AUTHORITY, SharedStorageDocuments.ROOT) else initial)
                        }
                    }
                }
            }
        }
        if (guideOnly || !separateGuide) item {
            Surface(shape = GalleryShape, color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(localized(R.string.ftp_connect, "Come collegarti"), style = MaterialTheme.typography.titleMedium)
                    Text(localized(R.string.ftp_upload_limits, ""), style = MaterialTheme.typography.bodySmall)
                    if (state.running) SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(state.address.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                                GalleryIconButton(R.drawable.ph_copy_simple, localized(R.string.explorer_copy, "Copia"), Modifier.testTag("ftp-copy-address")) {
                                    app.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(
                                        android.content.ClipData.newPlainText("FTP", state.address.orEmpty()))
                                }
                            }
                            Text("${localized(R.string.ftp_username, "Utente")}: ${FtpServer.USER}")
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("${localized(R.string.ftp_password, "Password")}: ${state.password.orEmpty()}", Modifier.weight(1f))
                                GalleryIconButton(R.drawable.ph_copy_simple, localized(R.string.explorer_copy, "Copia"), Modifier.testTag("ftp-copy-password")) {
                                    val clip = android.content.ClipData.newPlainText("FTP", state.password.orEmpty())
                                    if (android.os.Build.VERSION.SDK_INT >= 33) clip.description.extras = android.os.PersistableBundle().apply {
                                        putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
                                    }
                                    app.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(clip)
                                }
                            }
                        }
                    } else Text(localized(R.string.ftp_wifi_required, "Collega il Thor e l’altro dispositivo alla stessa rete Wi-Fi."))
                    Text(localized(R.string.ftp_security, "Usa un client FTP sull’altro dispositivo. Solo rete fidata: FTP non cifra i trasferimenti. I file esistenti non vengono sovrascritti e non possono essere cancellati da remoto."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        (localError ?: state.error)?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
    }
    if (guideOnly || !separateGuide) Button(onClick = { app.explorer.ftpTransfersVisible.value = true }, enabled = state.running,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 28.dp).height(56.dp).testTag("ftp-transfers")) {
        Text(localized(R.string.ftp_transfers, "Trasferimenti"), style = MaterialTheme.typography.titleMedium)
    }
    if (!guideOnly) Button(onClick = { if (state.running) app.ftp.stop() else app.ftp.start() },
        enabled = state.running || !state.starting && rootName != null,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 28.dp).height(56.dp).testTag("ftp-start-stop")) {
        if (state.starting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else Text(localized(if (state.running) R.string.ftp_stop else R.string.ftp_start,
            if (state.running) "Ferma condivisione" else "Avvia condivisione"), style = MaterialTheme.typography.titleMedium)
    }
    }
    if (!guideOnly && state.running && app.explorer.ftpTransfersVisible.value) GalleryAlertDialog(
        onDismissRequest = { app.explorer.ftpTransfersVisible.value = false },
        modifier = Modifier.fillMaxWidth(.8f),
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(localized(R.string.ftp_transfers, "Trasferimenti")) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp).testTag("ftp-transfer-history"),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(operations.filter { it.kind == "FTP_RECEIVE" && it.createdAt >= (state.startedAt ?: Long.MAX_VALUE) }, key = { it.id }) { transfer ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (transfer.phase == "RECEIVING") CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        else GalleryIcon(if (transfer.phase == "COMPLETED") R.drawable.ph_checks else R.drawable.ph_info, null)
                        Column(Modifier.weight(1f)) {
                            Text(transfer.requestedName, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text(localized(when (transfer.phase) { "COMPLETED" -> R.string.ftp_received; "RECEIVING", "COPYING", "STAGED" -> R.string.ftp_receiving; else -> R.string.ftp_failed },
                                when (transfer.phase) { "COMPLETED" -> "Ricevuto"; "RECEIVING", "COPYING", "STAGED" -> "Ricezione in corso"; else -> "Trasferimento interrotto" }), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

            }
        },
        confirmButton = { TextButton({ app.explorer.ftpTransfersVisible.value = false }) {
            Text(localized(R.string.text_close_32d407, "Chiudi"))
        } })

}
