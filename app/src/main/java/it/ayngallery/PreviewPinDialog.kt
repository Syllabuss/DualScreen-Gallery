package it.ayngallery

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun PreviewPinDialog(app: GalleryApp, accept: (String) -> Unit) {
    val id = app.session.pinActivationRequestId ?: return
    val runtime = remember { PadModalRuntime() }
    fun cancel() { app.session.pinActivationRequestId = null }
    DisposableEffect(id, runtime) {
        runtime.context = PadContext.LIBRARY
        runtime.captureKey = { event ->
            val button = padButton(event.keyCode)
            if (button in setOf(PadButton.A, PadButton.B)) {
                if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    if (button == PadButton.A) accept(id) else cancel()
                }
                true
            } else runtime.input?.key(event) == true
        }
        runtime.captureAction = { action -> when (action) {
            PadAction.CONFIRM -> { accept(id); true }
            PadAction.BACK -> { cancel(); true }
            else -> false
        } }
        onDispose { runtime.captureKey = null; runtime.captureAction = null }
    }
    GalleryAlertDialog(onDismissRequest = ::cancel, padRuntime = runtime,
        title = { Text(localized(R.string.preview_unpin_title, "Cambiare anteprima?")) },
        text = { Text(localized(R.string.preview_unpin_description, "L’immagine è fissata. Aprire questo media rimuoverà il PIN dall’anteprima. Vuoi continuare?")) },
        confirmButton = { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ accept(id) }, Modifier.fillMaxWidth().heightIn(min = 40.dp).testTag("preview-unpin-confirm"), shape = GalleryShape) {
                ControllerChoiceBadge("A"); Spacer(Modifier.width(8.dp)); Text(localized(R.string.video_resume_action, "Continua"))
            }
            TextButton(::cancel, Modifier.fillMaxWidth().heightIn(min = 40.dp).testTag("preview-unpin-cancel")) {
                ControllerChoiceBadge("B"); Spacer(Modifier.width(8.dp)); Text(localized(R.string.text_cancel_18c9d9, "Annulla"))
            }
        } })
}

@Composable
internal fun FloatingImagePermissionDialog(app: GalleryApp, activity: PanelActivity) {
    val id = app.session.floatingImageRequestId ?: return
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        app.session.floatingImageRequestId = null
        if (Settings.canDrawOverlays(activity)) FloatingImageService.open(activity, id)
    }
    GalleryAlertDialog(onDismissRequest = { app.session.floatingImageRequestId = null },
        title = { Text(localized(R.string.preview_compare_setting, "Confronti in finestre mobili")) },
        text = { Text(localized(R.string.preview_overlay_permission, "Consenti a DS Gallery di mostrare finestre sopra le altre app. Puoi aprire fino a due immagini, spostarle e ridimensionarle con due dita. Tocca di nuovo Confronto per chiuderne una, oppure usa la notifica per chiuderle tutte.")) },
        confirmButton = { TextButton({ launcher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${activity.packageName}"))) }) {
            Text(localized(R.string.video_resume_action, "Continua"))
        } }, dismissButton = { TextButton({ app.session.floatingImageRequestId = null }) { Text(localized(R.string.text_cancel_18c9d9, "Annulla")) } })
}

