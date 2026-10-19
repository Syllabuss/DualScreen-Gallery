package it.ayngallery

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun TouchSettingsScreen(app: GalleryApp, prefs: GalleryPreferences) {
    var baselineSounds by rememberSaveable { mutableStateOf(prefs.uiSounds) }
    var draftSounds by rememberSaveable { mutableStateOf(prefs.uiSounds) }
    LaunchedEffect(prefs.uiSounds) {
        if (draftSounds == baselineSounds) draftSounds = prefs.uiSounds
        baselineSounds = prefs.uiSounds
    }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var baselineJson by rememberSaveable { mutableStateOf(prefs.touch.encode()) }
    var draftJson by rememberSaveable { mutableStateOf(baselineJson) }
    val draft = remember(draftJson) { TouchSettings.decode(draftJson, check = false) }
    val validation = remember(draft) { runCatching { draft.validate() }.exceptionOrNull()?.message }
    var contextName by rememberSaveable { mutableStateOf(PadContext.LIBRARY.name) }
    val context = PadContext.valueOf(contextName)
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    SettingsScrollEnd({ listState.canScrollForward }) { listState.animateScrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0), listState.layoutInfo.viewportEndOffset.coerceAtLeast(0)) }
    fun change(next: TouchSettings) { draftJson = next.encode(check = false); error = null }
    Column(Modifier.fillMaxSize()) {
        SettingsSaveAction(enabled = !saving && (draft != prefs.touch || draftSounds != prefs.uiSounds) && validation == null, tag = "touch-save", dirty = draft != prefs.touch || draftSounds != prefs.uiSounds, busy = saving, discard = { baselineJson = prefs.touch.encode(); draftJson = baselineJson; draftSounds = prefs.uiSounds }) {
            val submitted = draft
            val submittedSounds = draftSounds
            val expected = TouchSettings.decode(baselineJson)
            saving = true; error = null
            scope.launch {
                try {
                    app.preferences.update { current ->
                        check(current.touch == expected) { localized(R.string.text_gestures_changed_on_the_other_panel_reload_setti_09990f, "I gesti sono cambiati sull'altro pannello. Ricarica le impostazioni prima di salvare.") }
                        current.copy(touch = submitted, uiSounds = submittedSounds)
                    }
                    baselineJson = submitted.encode()
                    app.session.settingsDraft = app.session.settingsDraft?.copy(touch = submitted, uiSounds = submittedSounds)
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (failure: Exception) { error = failure.message ?: localized(R.string.text_gestures_not_saved_f2b3af, "Gesti non salvati") }
                finally { saving = false }
            }
        }
        (error ?: validation)?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("touch-error"), style = MaterialTheme.typography.bodySmall) }
        LazyColumn(Modifier.weight(1f).testTag("touch-settings-list"), state = listState, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                GallerySettingSwitch(localized(R.string.ui_sounds, "Suoni dell’interfaccia"), draftSounds, Modifier.testTag("ui-sounds")) { draftSounds = it }
            }
            item {
                Text(localized(R.string.text_photos_and_gifs_927c77, "Foto e GIF"), style = MaterialTheme.typography.titleMedium)
                TouchChoice(localized(R.string.text_double_tap_8f6732, "Doppio tocco"), draft.doubleTap, touchActions(PadContext.VIEWER, true).filterNot { it == TouchAction.PIN || app.session.isSingleScreen && it in setOf(TouchAction.SWAP, TouchAction.DIM) }, "touch-double") { change(draft.copy(doubleTap = it)) }
                TouchChoice(localized(R.string.text_long_press_e8ac77, "Pressione prolungata"), draft.longPress, touchActions(PadContext.VIEWER, true).filterNot { it == TouchAction.PIN || app.session.isSingleScreen && it in setOf(TouchAction.SWAP, TouchAction.DIM) }, "touch-long") { change(draft.copy(longPress = it)) }
            }
            item { SettingsDisclosure(advanced, { advanced = it }, "touch-advanced") }
            if (advanced) {
            item {
                HorizontalDivider()
                Text(localized(R.string.text_three_finger_gestures_f7904c, "Gesti a tre dita"), style = MaterialTheme.typography.titleMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    touchContexts.forEach { choice -> FilterChip(context == choice, { contextName = choice.name },
                        label = { Text(choice.title) }, modifier = Modifier.testTag("touch-context:${choice.name}")) }
                }
                Text(localized(R.string.tutorial_gesture_hint, "Con tre dita: scorri nella direzione indicata o tocca senza trascinare."), Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
            }
            items(TouchDirection.entries, key = { "direction:$it" }) { direction ->
                TouchChoice(direction.title, draft.threeFinger.getValue(context).getValue(direction).let { if (app.session.isSingleScreen && it in setOf(TouchAction.SWAP, TouchAction.DIM)) TouchAction.NONE else it }, touchActions(context).filterNot { it == TouchAction.PIN || app.session.isSingleScreen && it in setOf(TouchAction.SWAP, TouchAction.DIM) }, "touch-three:${direction.name}") { action ->
                    change(draft.copy(threeFinger = draft.threeFinger + (context to (draft.threeFinger.getValue(context) + (direction to action)))))
                }
            }
            }
            item {
                GalleryButton(localized(R.string.text_restore_default_gestures_88890a, "Ripristina gesti predefiniti"), Modifier.fillMaxWidth().testTag("touch-defaults")) { change(TouchSettings()) }
                if (advanced) GalleryButton(localized(R.string.text_reload_saved_settings_b7567e, "Ricarica impostazioni salvate"), Modifier.fillMaxWidth().padding(top = 8.dp).testTag("touch-reload")) {
                    baselineJson = prefs.touch.encode(); draftJson = baselineJson; draftSounds = prefs.uiSounds; error = null
                }
            }
        }
    }
}

@Composable
private fun TouchChoice(label: String, value: TouchAction, choices: List<TouchAction>, tag: String, change: (TouchAction) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Box(Modifier.weight(1.3f)) {
            GalleryButton(value.title, Modifier.fillMaxWidth().testTag(tag)) { expanded = true }
            DropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 320.dp)) {
                choices.forEach { action -> DropdownMenuItem(text = { Text(action.title) }, modifier = Modifier.testTag("$tag:${action.name}"),
                    onClick = { change(action); expanded = false }) }
            }
        }
    }
}
