package it.ayngallery

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.saveable.rememberSaveable
import android.content.Intent
import android.app.Activity

@Composable
internal fun SoundSettingsScreen(app: GalleryApp, prefs: GalleryPreferences) {
    val soundPause = remember(app) { app.music.pauseForSoundSettings() }
    DisposableEffect(app) { onDispose { app.music.resumeAfterSoundSettings(soundPause) } }
    var enabled by remember { mutableStateOf(prefs.uiSounds) }
    var disabled by remember { mutableStateOf(prefs.disabledSounds) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val preview by app.sounds.preview.collectAsState()
    val custom by app.sounds.customCues.collectAsState()
    var customize by rememberSaveable { mutableStateOf<String?>(null) }
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    var reset by rememberSaveable { mutableStateOf<String?>(null) }
    fun work(action: suspend () -> Unit) {
        busy = true
        app.sounds.stopPreview()
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { uiToast(app, failure.message ?: localized(R.string.sound_custom_load_failed, "Impossibile caricare il suono. Quello precedente è stato conservato."), android.widget.Toast.LENGTH_LONG).show() }
            finally { busy = false }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val cue = pending; pending = null
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && cue != null && uri != null) work { app.sounds.import(cue, uri) }
    }
    customize?.let { cue -> GalleryAlertDialog(onDismissRequest = { customize = null },
        icon = { GalleryIcon(R.drawable.ph_info, null) },
        title = { Text(localized(R.string.sound_custom_action, "Personalizza suono")) },
        text = { Text(localized(R.string.sound_custom_guide, "Scegli un audio WAV lungo al massimo 6 secondi. Verrà conservata una copia nell’app; il file originale non sarà modificato.")) },
        confirmButton = { TextButton(onClick = {
            customize = null; pending = cue
            try {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*")
                app.session.settingsActivity()?.let { activity -> app.session.current(activity)?.let { display ->
                    intent.putExtra(ActivityResultContracts.StartActivityForResult.EXTRA_ACTIVITY_OPTIONS_BUNDLE,
                        android.app.ActivityOptions.makeBasic().setLaunchDisplayId(display.id).toBundle())
                } }
                picker.launch(intent)
            } catch (_: RuntimeException) { pending = null; uiToast(app, localized(R.string.sound_custom_load_failed, "Impossibile caricare il suono. Quello precedente è stato conservato."), android.widget.Toast.LENGTH_LONG).show() }
        }) { Text(localized(R.string.sound_custom_choose, "Scegli audio")) } },
        dismissButton = { TextButton(onClick = { customize = null }) { Text(localized(R.string.text_cancel_18c9d9, "Annulla")) } }) }
    reset?.let { cue -> GalleryAlertDialog(onDismissRequest = { reset = null },
        title = { Text(localized(R.string.sound_custom_reset, "Ripristinare il suono originale?")) },
        confirmButton = { TextButton(onClick = { reset = null; work { app.sounds.reset(cue) } }) { Text(localized(R.string.sound_custom_restore, "Ripristina")) } },
        dismissButton = { TextButton(onClick = { reset = null }) { Text(localized(R.string.text_cancel_18c9d9, "Annulla")) } }) }
    val music by app.music.state.collectAsState()
    val videoPlaying by app.video.playing.collectAsState()
    DisposableEffect(app) { onDispose { app.sounds.stopPreview() } }
    val dirty = enabled != prefs.uiSounds || disabled != prefs.disabledSounds
    Column(Modifier.fillMaxSize()) {
        SettingsSaveAction(enabled = dirty && !busy, dirty = dirty, busy = busy,
            discard = { enabled = prefs.uiSounds; disabled = prefs.disabledSounds }) {
            val master = enabled; val muted = disabled
            busy = true
            scope.launch {
                try { app.preferences.update { it.copy(uiSounds = master, disabledSounds = muted) }
                    app.session.settingsDraft = app.session.settingsDraft?.copy(uiSounds = master, disabledSounds = muted)
                } finally { busy = false }
            }
        }
        LazyColumn(Modifier.weight(1f).testTag("sound-settings"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { GallerySettingSwitch(localized(R.string.ui_sounds, "Suoni dell’interfaccia"), enabled, Modifier.testTag("ui-sounds")) { enabled = it } }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            val labels = listOf(R.string.sound_navigate, R.string.sound_notice, R.string.sound_delete, R.string.sound_favorite,
                R.string.sound_settings, R.string.sound_settings_navigation, R.string.sound_save, R.string.sound_restore, R.string.sound_unlock, R.string.sound_unlock_error, R.string.sound_reorder, R.string.sound_pin_digit_1, R.string.sound_pin_digit_2, R.string.sound_pin_digit_3, R.string.sound_preview_pin, R.string.sound_preview_unpin, R.string.sound_comparison_first, R.string.sound_comparison_second, R.string.sound_comparison_close, R.string.sound_network_transfer, R.string.sound_zoom_out, R.string.sound_zoom_in, R.string.sound_pin_digit_4, R.string.sound_video_replace, R.string.sound_section)
            val cueLabels = UiSounds.CUES.zip(labels).toMap()
            val order = listOf("navigate", "section", "settings", "settings-navigation", "notice", "video-replace", "favorite", "zoom-in", "zoom-out", "save", "delete", "restore", "reorder", "pin-digit-1", "pin-digit-2", "pin-digit-3", "pin-digit-4", "unlock", "unlock-error", "preview-pin", "preview-unpin", "comparison-first", "comparison-second", "comparison-close", "network-transfer")
            order.forEach { cue -> item(key = cue) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    IconButton(onClick = { if (preview?.cue == cue) app.sounds.stopPreview() else app.sounds.preview(cue) },
                        enabled = !busy && !music.playing && !videoPlaying && (preview == null || preview?.cue == cue), modifier = Modifier.testTag("sound-preview:$cue")) {
                        GalleryIcon(if (preview?.cue == cue) R.drawable.ph_stop else R.drawable.ph_play,
                            if (preview?.cue == cue) localized(R.string.text_stop_preview_049a11, "Ferma anteprima") else localized(R.string.sound_preview_action, "Ascolta il suono"))
                    }
                    GallerySettingSwitch(localized(cueLabels.getValue(cue), cue), cue !in disabled, Modifier.weight(1f).testTag("sound:$cue"), enabled = enabled && !busy) {
                        disabled = if (it) disabled - cue else disabled + cue
                    }
                    IconButton(onClick = { if (cue in custom) reset = cue else customize = cue }, enabled = !busy,
                        modifier = Modifier.testTag("sound-custom:$cue")) {
                        GalleryIcon(if (cue in custom) R.drawable.ph_trash else R.drawable.ic_edit_sound,
                            if (cue in custom) localized(R.string.sound_custom_restore, "Ripristina") else localized(R.string.sound_custom_action, "Personalizza suono"))
                    }
                }
            } }
        }
    }
}
