package it.ayngallery

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun VideoReplacementDialog(app: GalleryApp, slot: Int) {
    val request = app.video.replacementRequest?.takeIf { it.slot == slot } ?: return
    var dontAskAgain by remember(request) { mutableStateOf(false) }
    val confirm = remember(request) { FocusRequester() }
    GalleryAlertDialog(onDismissRequest = app.video::dismissReplacement, soundCue = "video-replace",
        icon = { GalleryIcon(R.drawable.ph_video_camera, null) },
        title = { Text(localized(R.string.video_replace_title, "Riprodurre un altro video?")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(localized(R.string.video_replace_description, "Il video attuale verrà chiuso. Vuoi avviare quello selezionato?"))
                GallerySettingSwitch(localized(R.string.video_replace_dont_ask, "Non mostrare più"), dontAskAgain,
                    Modifier.testTag("video-replace-dont-ask")) { dontAskAgain = it }
            }
        },
        confirmButton = {
            LaunchedEffect(request) { withFrameNanos { }; confirm.requestFocus() }
            Button({ app.video.confirmReplacement(dontAskAgain) }, Modifier.focusRequester(confirm).testTag("video-replace-confirm"), shape = GalleryShape) {
                ControllerChoiceBadge("A"); Spacer(Modifier.width(8.dp)); Text(localized(R.string.video_resume_action, "Continua"))
            }
        },
        dismissButton = {
            TextButton(app.video::dismissReplacement, Modifier.testTag("video-replace-cancel")) {
                ControllerChoiceBadge("B"); Spacer(Modifier.width(8.dp)); Text(localized(R.string.text_cancel_18c9d9, "Annulla"))
            }
        })
}
