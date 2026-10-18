package it.ayngallery

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun MediaEditorPage(onDismissRequest: () -> Unit, title: @Composable () -> Unit,
    text: @Composable () -> Unit, dismissButton: @Composable () -> Unit, confirmButton: @Composable () -> Unit) {
    val runtime = remember { PadModalRuntime() }
    val focus = remember { FocusRequester() }
    BackHandler(onBack = onDismissRequest)
    Surface(Modifier.fillMaxSize().testTag("media-editor-page").onPreviewKeyEvent { runtime.key(it.nativeKeyEvent) }
        .focusRequester(focus).focusable()) {
        Column {
            PadModalRegistration(runtime, onDismissRequest, focus)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { title() }
                dismissButton(); confirmButton()
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.secondary.copy(alpha = .6f))
            Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp)) { text() }
        }
    }
}

/** Native folder picker; a selected destination uses the existing durable export journal. */
internal fun editorDestinationIntent(activity: PanelActivity, data: CatalogSnapshot, target: ExternalTarget): Intent {
    val parent = data.albumsById[data.byId[target.id]?.albumId]?.documentUri
    return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        .apply {
            parent?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(it)) }
            activity.session.current(activity)?.let { here ->
                putExtra(ActivityResultContracts.StartActivityForResult.EXTRA_ACTIVITY_OPTIONS_BUNDLE,
                    ActivityOptions.makeBasic().setLaunchDisplayId(here.id).toBundle())
            }
        }
}

@Composable
internal fun rememberEditorDestinationPicker(activity: PanelActivity, data: CatalogSnapshot, target: ExternalTarget,
    role: SourceRole, busy: (Boolean) -> Unit, error: (String?) -> Unit, save: suspend (String) -> Unit): () -> Unit {
    val app = activity.application as GalleryApp
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { response ->
        val uri = response.data?.data
        if (response.resultCode == Activity.RESULT_OK && uri != null) scope.launch {
            busy(true); error(null)
            try {
                val album = withContext(Dispatchers.IO) { app.catalog.authorizeDestination(uri, role, response.data?.flags ?: 0) }
                save(album)
                app.sounds.play("save")
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { error(failure.message ?: localized(R.string.text_export_did_not_start_c11e45, "Esportazione non avviata")) }
            finally { busy(false) }
        }
    }
    return { picker.launch(editorDestinationIntent(activity, data, target)) }
}
