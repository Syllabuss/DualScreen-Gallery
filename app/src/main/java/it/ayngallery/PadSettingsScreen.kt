package it.ayngallery

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PadSettingsScreen(app: GalleryApp, prefs: GalleryPreferences) {
    var baselineSounds by rememberSaveable { mutableStateOf(prefs.uiSounds) }
    var draftSounds by rememberSaveable { mutableStateOf(prefs.uiSounds) }
    LaunchedEffect(prefs.uiSounds) {
        if (draftSounds == baselineSounds) draftSounds = prefs.uiSounds
        baselineSounds = prefs.uiSounds
    }
    var draftJson by rememberSaveable { mutableStateOf(prefs.pad.encode()) }
    val draft = remember(draftJson) { PadSettings.decode(draftJson, check = false) }
    val profileId = "default"
    val context = PadContext.LIBRARY
    var captureAction by rememberSaveable { mutableStateOf<String?>(null) }
    var restoreAction by rememberSaveable { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyGridState()
    SettingsScrollEnd({ listState.canScrollForward }) { listState.animateScrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0), listState.layoutInfo.viewportEndOffset.coerceAtLeast(0)) }
    val profile = draft.profile(profileId)
    fun change(value: PadSettings) { draftJson = value.encode(check = false); error = null }
    fun assign(action: PadAction, binding: PadBinding?, replace: Boolean = false) {
        change(draft.copy(profiles = draft.profiles + (profileId to profile.assign(context, action, binding, replace))))
    }
    Column(Modifier.fillMaxSize()) {
        SettingsSaveAction(enabled = !saving && (draft != prefs.pad || draftSounds != prefs.uiSounds), tag = "pad-save", dirty = draft != prefs.pad || draftSounds != prefs.uiSounds, busy = saving, discard = { draftJson = prefs.pad.encode(); draftSounds = prefs.uiSounds }) {
            val submitted = draft
            val submittedSounds = draftSounds
            saving = true; error = null
            scope.launch {
                try {
                    app.preferences.update { it.copy(pad = submitted, uiSounds = submittedSounds) }
                    app.session.settingsDraft = app.session.settingsDraft?.copy(pad = submitted, uiSounds = submittedSounds)
                }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (failure: Exception) { error = failure.message ?: localized(R.string.text_bindings_not_saved_ccc1a2, "Associazioni non salvate") }
                finally { saving = false }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("pad-error")) }
        LazyVerticalGrid(GridCells.Adaptive(340.dp), Modifier.weight(1f).testTag("pad-settings-list"), state = listState, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 28.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(padActions(context).filter { it in setOf(PadAction.CONFIRM, PadAction.BACK, PadAction.SETTINGS, PadAction.SWAP, PadAction.UP, PadAction.DOWN, PadAction.LEFT, PadAction.RIGHT, PadAction.GRID_SMALLER, PadAction.GRID_LARGER, PadAction.TOGGLE_VIEW, PadAction.FRAME) }.filterNot { it == PadAction.PIN || app.session.isSingleScreen && it == PadAction.SWAP }, key = { "${context.name}:${it.name}" }) { action ->
                val binding = profile.bindings[context]?.get(action)
                Surface(Modifier.fillMaxWidth(), shape = GalleryShape, color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    var menu by remember { mutableStateOf(false) }
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(action.title, style = MaterialTheme.typography.titleSmall)
                            Text(binding?.title() ?: localized(R.string.text_unassigned_81757b, "Non assegnato"), Modifier.testTag("pad-binding:${action.name}"), color = MaterialTheme.colorScheme.primary)
                            if (!binding?.label.isNullOrBlank()) Text(binding!!.buttons.sortedBy { it.ordinal }.joinToString(" + ") { it.title }, style = MaterialTheme.typography.bodySmall)
                        }
                        GalleryButton(localized(R.string.text_edit_49e493, "Modifica"), Modifier.testTag("pad-record:${action.name}"), enabled = !saving) { captureAction = action.name }
                        Box {
                            GalleryIconButton(R.drawable.ph_dots_three, localized(R.string.text_1_s_options_a1440f, "Opzioni %1\$s", action.title)) { menu = true }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text(localized(R.string.text_remove_fd69f0, "Rimuovi")) }, enabled = binding != null && !saving && action !in setOf(PadAction.UP, PadAction.DOWN, PadAction.LEFT, PadAction.RIGHT, PadAction.CONFIRM, PadAction.BACK), onClick = { menu = false; assign(action, null) })
                                DropdownMenuItem(text = { Text(localized(R.string.text_restore_cf1718, "Ripristina")) }, enabled = !saving, onClick = {
                                    menu = false
                                    val original = padDefaults().getValue(context)[action]
                                    if (original != null && profile.conflicts(context, action, original).isNotEmpty()) restoreAction = action.name
                                    else assign(action, original)
                                })
                            }
                        }
                    }
                }
            }

        }
    }
    captureAction?.let { name ->
        val action = PadAction.valueOf(name)
        PadCaptureDialog(context, action, profile, draft, apply = { binding, replace -> assign(action, binding, replace); captureAction = null }, dismiss = { captureAction = null })
    }
    restoreAction?.let { name ->
        val action = PadAction.valueOf(name)
        val original = padDefaults().getValue(context).getValue(action)
        GalleryAlertDialog({ restoreAction = null }, title = { Text(localized(R.string.text_reset_1_s_f6bc88, "Ripristina %1\$s", action.title)) }, text = {
            Text(localized(R.string.text_1_s_is_already_used_by_2_s_replace_these_binding_9bc78c, "%1\$s è già usato da %2\$s. Sostituire queste associazioni nella bozza?", original.title(), profile.conflicts(context, action, original).joinToString { it.title }))
        }, confirmButton = { TextButton({ assign(action, original, replace = true); restoreAction = null }) { Text(localized(R.string.text_replace_in_draft_f2a979, "Sostituisci nella bozza")) } },
            dismissButton = { TextButton({ restoreAction = null }) { Text(localized(R.string.text_cancel_18c9d9, "Annulla")) } })
    }
}

@Composable
internal fun PadHelpDialog(context: PadContext, profile: PadProfile, device: String?, dismiss: () -> Unit) {
    GalleryAlertDialog(dismiss, title = { Text(localized(R.string.text_controls_1_s_b42d62, "Comandi · %1\$s", context.title)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(device ?: localized(R.string.text_shared_profile_controller_not_detected_yet_f52b68, "Profilo comune · controller non ancora rilevato"), style = MaterialTheme.typography.bodySmall)
            profile.bindings[context].orEmpty().filterKeys { it != PadAction.PIN }.forEach { (action, binding) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(binding.title(), Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
                    Text(action.title, Modifier.weight(1f))
                }
            }
            Text(localized(R.string.text_letters_indicate_codes_received_from_android_ren_0d4b3a, "Le lettere indicano i codici ricevuti da Android. Puoi cambiarne l'etichetta in Controlli. Le scorciatoie valgono soltanto nella finestra attiva."), style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(dismiss) { Text(localized(R.string.text_close_32d407, "Chiudi")) } })
}

@Composable
internal fun PadCaptureDialog(context: PadContext, action: PadAction, profile: PadProfile, tuning: PadSettings,
    apply: (PadBinding, Boolean) -> Unit, dismiss: () -> Unit) {
    val runtime = remember { PadModalRuntime() }
    var recording by rememberSaveable { mutableStateOf(true) }
    var candidateNames by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var label by rememberSaveable { mutableStateOf("") }
    var editingLabel by rememberSaveable { mutableStateOf(false) }
    var replace by rememberSaveable { mutableStateOf(false) }
    val recordingInput = remember(tuning) { PadRecording(tuning) }
    var deviceName by remember { mutableStateOf<String?>(null) }
    val useFocus = remember { FocusRequester() }
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val textHeight = with(LocalDensity.current) { windowHeight.toDp() } * 0.55f
    LaunchedEffect(Unit) { if (recording) candidateNames = emptyList() }
    val candidate = candidateNames.map(PadButton::valueOf).toSet()
    val binding = PadBinding(candidate, label)
    val conflicts = if (candidate.isEmpty()) emptySet() else profile.conflicts(context, action, binding)
    val valid = candidate.size in 1..3 && (candidate.size == 1 || candidate.none { it in setOf(PadButton.UP, PadButton.DOWN, PadButton.LEFT, PadButton.RIGHT, PadButton.RS_UP, PadButton.RS_DOWN, PadButton.RS_LEFT, PadButton.RS_RIGHT) })
    fun received(complete: Boolean) {
        candidateNames = recordingInput.candidate.sortedBy { it.ordinal }.map { it.name }
        deviceName = recordingInput.deviceId?.let { InputDevice.getDevice(it)?.name ?: localized(R.string.text_controller_code_1_s_dd98f6, "Controller · codice %1\$s", it) }
        if (complete) recording = false
    }
    runtime.captureKey = if (recording) { event ->
        val button = padButton(event.keyCode)
        if (button == null || event.action !in setOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) false else {
            received(recordingInput.update(event.deviceId, "key", mapOf(button to (event.action == KeyEvent.ACTION_DOWN)))); true
        }
    } else null
    runtime.captureMotion = if (recording) { event ->
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK) && !event.isFromSource(InputDevice.SOURCE_GAMEPAD)) false else {
            received(recordingInput.axes(event.deviceId, padAxes(event))); true
        }
    } else null
    runtime.clearCapture = { if (recording) { recordingInput.clear(); candidateNames = emptyList(); deviceName = null } }
    LaunchedEffect(recording, valid, conflicts.isEmpty(), replace) {
        if (!recording && valid && (conflicts.isEmpty() || replace)) useFocus.requestFocus()
    }
    GalleryAlertDialog(dismiss, title = { Text(localized(R.string.text_record_1_s_52eb4b, "Registra · %1\$s", action.title)) }, padRuntime = runtime, text = {
        Column(Modifier.heightIn(max = textHeight).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (recording) localized(R.string.text_press_the_button_or_combination_then_release_you_ca7221, "Premi il pulsante o la combinazione e rilascia. Puoi muovere anche stick o trigger.") else localized(R.string.text_input_received_check_the_binding_before_using_it_d15720, "Input ricevuto. Controlla l'associazione prima di usarla."))
            Text(binding.title().ifBlank { localized(R.string.text_listening_04c474, "In ascolto…") }, Modifier.testTag("pad-candidate"), style = MaterialTheme.typography.titleMedium)
            deviceName?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (!recording) GalleryButton(if (label.isBlank()) localized(R.string.text_optional_physical_label_01b29a, "Etichetta fisica facoltativa…") else localized(R.string.text_label_1_s_45b4a6, "Etichetta: %1\$s", label), Modifier.fillMaxWidth().testTag("pad-label-edit")) {
                editingLabel = true
            }
            if (!recording && !valid) Text(localized(R.string.text_use_one_to_three_buttons_directions_are_recorded_9a7276, "Usa da uno a tre pulsanti; le direzioni si registrano singolarmente."), color = MaterialTheme.colorScheme.error)
            if (conflicts.isNotEmpty()) {
                Text(localized(R.string.text_already_assigned_1_s_617740, "Già assegnato: %1\$s", conflicts.joinToString { it.title }), Modifier.testTag("pad-conflict"), color = MaterialTheme.colorScheme.error)
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(replace, { replace = it }); Text(localized(R.string.text_replace_these_bindings_in_the_draft_d23325, "Sostituisci queste associazioni nella bozza")) }
            }
            Text(localized(R.string.text_home_volume_and_system_keys_cannot_be_recorded_t_1b3b70, "Home, volume e tasti di sistema non si registrano. La prova sul controller reale stabilisce a quale lettera fisica corrisponde il codice ricevuto."), style = MaterialTheme.typography.bodySmall)
            Text(localized(R.string.text_if_a_key_does_not_appear_firmware_may_reserve_it_0f06b5, "Se un tasto non compare, può essere riservato dal firmware o non supportato: prova un altro comando. Per interrompere la registrazione usa Indietro di sistema o Annulla."), style = MaterialTheme.typography.bodySmall)
            TextButton({ recording = true; recordingInput.clear(); candidateNames = emptyList(); deviceName = null; replace = false }) { Text(localized(R.string.text_record_again_8c6a0c, "Registra di nuovo")) }
        }
    }, confirmButton = { TextButton({ apply(binding, replace) }, enabled = !recording && valid && (conflicts.isEmpty() || replace), modifier = Modifier.testTag("pad-use").focusRequester(useFocus)) { Text(localized(R.string.text_use_binding_499a9d, "Usa associazione")) } },
        dismissButton = { TextButton(dismiss) { Text(localized(R.string.text_cancel_18c9d9, "Annulla")) } })
    if (editingLabel) {
        val field = rememberTextFieldState(label)
        val text = field.text.toString().trim()
        val accepted = text.length <= 24 && text.none(Char::isISOControl)
        GalleryAlertDialog({ editingLabel = false }, title = { Text(localized(R.string.text_physical_label_5f15ae, "Etichetta fisica")) }, text = {
        var fieldFocused by remember { mutableStateOf(false) }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(localized(R.string.text_the_name_to_show_in_hints_up_to_24_characters_th_b24e2b, "Il nome che vuoi vedere nei suggerimenti, fino a 24 caratteri. Il codice del pulsante resta invariato."), style = MaterialTheme.typography.bodySmall)
            BasicTextField(field,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth().onFocusChanged { fieldFocused = it.isFocused }
                    .border(if (fieldFocused) 3.dp else 1.dp, if (fieldFocused) GalleryFocus else MaterialTheme.colorScheme.outline, GalleryShape)
                    .padding(16.dp).testTag("pad-physical-label").semantics { contentDescription = localized(R.string.text_optional_physical_label_6a760e, "Etichetta fisica facoltativa") },
                lineLimits = TextFieldLineLimits.SingleLine)
            if (!accepted) Text(localized(R.string.text_up_to_24_characters_without_control_characters_cfac52, "Massimo 24 caratteri, senza caratteri di controllo"), color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton({ label = text; editingLabel = false }, Modifier.testTag("pad-label-use"), enabled = accepted) { Text(localized(R.string.text_use_label_39f1df, "Usa etichetta")) } },
        dismissButton = { TextButton({ editingLabel = false }) { Text(localized(R.string.text_cancel_18c9d9, "Annulla")) } })
    }
}
