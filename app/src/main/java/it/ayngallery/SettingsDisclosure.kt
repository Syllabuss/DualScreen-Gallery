package it.ayngallery

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun SettingsDisclosure(expanded: Boolean, change: (Boolean) -> Unit, tag: String = "settings-advanced") {
    OutlinedCard(onClick = { change(!expanded) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(localized(R.string.settings_advanced, "Avanzate"), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            GalleryIcon(if (expanded) R.drawable.ph_minus_circle else R.drawable.ph_plus_circle, null)
        }
    }
}

@Composable
internal fun SettingsDiscardDialog(dismiss: () -> Unit, discard: () -> Unit) {
    val keepFocus = androidx.compose.runtime.remember { FocusRequester() }
    val discardFocus = androidx.compose.runtime.remember { FocusRequester() }
    val input = androidx.compose.ui.platform.LocalInputModeManager.current
    var keepSelected by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var discardSelected by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    GalleryAlertDialog(onDismissRequest = dismiss,
        title = { Text(localized(R.string.settings_unsaved_title, "Modifiche non salvate")) },
        text = { Text(localized(R.string.settings_unsaved_message, "Vuoi uscire senza salvare le modifiche?")) },
        confirmButton = { TextButton(discard, Modifier.testTag("discard-confirm").focusRequester(discardFocus)
            .focusProperties { left = keepFocus; right = keepFocus; up = keepFocus; down = keepFocus }
            .onFocusChanged { discardSelected = it.isFocused }) {
            if (discardSelected) Text(">", Modifier.padding(end = 8.dp))
            Text(localized(R.string.settings_discard, "Esci senza salvare"))
        } },
        dismissButton = {
            androidx.compose.runtime.LaunchedEffect(Unit) {
                input.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard)
                androidx.compose.runtime.withFrameNanos { }
                keepFocus.requestFocus()
            }
            TextButton(dismiss, Modifier.testTag("discard-keep").focusRequester(keepFocus)
                .focusProperties { left = discardFocus; right = discardFocus; up = discardFocus; down = discardFocus }
                .onFocusChanged { keepSelected = it.isFocused }) {
                if (keepSelected) Text(">", Modifier.padding(end = 8.dp))
                Text(localized(R.string.settings_keep_editing, "Continua a modificare"))
            }
        })
}

@Composable
internal fun WidgetExitConfirmation(dirty: Boolean, busy: Boolean, exit: () -> Unit): () -> Unit {
    var confirming by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val request: () -> Unit = { if (!busy) { if (dirty) confirming = true else exit() } }
    androidx.activity.compose.BackHandler { request() }
    if (confirming) SettingsDiscardDialog({ confirming = false }, { confirming = false; exit() })
    return request
}
