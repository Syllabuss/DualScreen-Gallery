package it.ayngallery

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PreferencesTransferScreen(activity: PanelActivity, prefs: GalleryPreferences, embedded: Boolean = false) {
    val app = activity.application as GalleryApp
    val scope = rememberCoroutineScope()
    var export by rememberSaveable { mutableStateOf<String?>(null) }
    var candidate by rememberSaveable { mutableStateOf<String?>(null) }
    var baseline by rememberSaveable { mutableStateOf<String?>(null) }
    var common by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var failed by rememberSaveable { mutableStateOf(false) }
    val scroll = rememberScrollState()
    LaunchedEffect(candidate) { if (candidate != null) scroll.scrollTo(0) }
    fun work(exporting: Boolean = false, action: suspend () -> String) {
        busy = true; message = null; failed = false
        scope.launch {
            try { message = action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                failed = true
                message = if (exporting) localized(R.string.text_export_unverified_the_destination_document_may_b_364634, "Esportazione non verificata. Il documento nella destinazione può essere incompleto; riprova scegliendo un nuovo file. Le impostazioni restano intatte.")
                    else localized(R.string.text_operation_failed_check_format_access_and_free_sp_9f7c43, "Operazione non riuscita. Controlla formato, accesso e spazio disponibile; le impostazioni restano intatte.")
            }
            finally { busy = false }
        }
    }
    val savePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val frozen = export; export = null
        if (result.resultCode == Activity.RESULT_OK && frozen != null && result.data?.data != null) work(exporting = true) {
            PreferencesTransfer.write(app.contentResolver, result.data!!.data!!, frozen)
            localized(R.string.text_settings_exported_and_verified_the_document_cont_10cce0, "Impostazioni esportate e verificate. Il documento non contiene i media.")
        }
    }
    val openPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data?.data != null) work {
            // Keep only canonical, validated JSON in saved UI state; provider padding is not saved across rotation.
            val value = withContext(Dispatchers.IO) { PreferencesTransfer.encode(PreferencesTransfer.decode(
                PreferencesTransfer.read(app.contentResolver, result.data!!.data!!))) }
            candidate = value; baseline = PreferencesTransfer.encode(app.preferences.state.value); common = false
            localized(R.string.text_document_verified_check_the_preview_before_impor_ab933c, "Documento verificato. Controlla l'anteprima prima di importare.")
        }
    }
    fun launchPicker(intent: Intent, exporting: Boolean) {
        try {
            val here = app.session.current(activity) ?: error(localized(R.string.text_display_unavailable_8b459c, "Display non disponibile"))
            intent.putExtra(ActivityResultContracts.StartActivityForResult.EXTRA_ACTIVITY_OPTIONS_BUNDLE,
                ActivityOptions.makeBasic().setLaunchDisplayId(here.id).toBundle())
            if (exporting) savePicker.launch(intent) else openPicker.launch(intent)
        } catch (_: RuntimeException) { export = null; failed = true; message = localized(R.string.text_file_picker_unavailable_on_this_panel_993597, "Selettore file non disponibile su questo pannello.") }
    }
    Column(if (embedded) Modifier.fillMaxWidth() else Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxWidth().padding(if (embedded) 6.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (candidate == null) localized(R.string.text_keep_your_preferences_53df46, "Le tue preferenze, da conservare") else localized(R.string.text_review_what_will_change_f71fce, "Controlla cosa cambierà"), style = MaterialTheme.typography.titleMedium)
                Text(localized(R.string.text_themes_behavior_controller_and_touch_gestures_in_e02831, "Temi, comportamento, controller e gesti touch in un documento locale."), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (busy) {
                    if (!prefs.reducedMotion) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(localized(R.string.text_checking_document_cf3a92, "Verifica del documento…"), style = MaterialTheme.typography.bodySmall)
                }
                message?.let { Text(it, Modifier.testTag("transfer-message"), color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        Column((if (embedded) Modifier else Modifier.weight(1f).verticalScroll(scroll)).padding(horizontal = if (embedded) 6.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (candidate == null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(Modifier.weight(1f), shape = GalleryShape, color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(Modifier.fillMaxWidth().padding(if (embedded) 8.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    GalleryButton(localized(R.string.text_export_settings_b58a03, "Esporta impostazioni…"), Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("export-settings"), enabled = !busy) {
                        try {
                            export = PreferencesTransfer.encode(app.preferences.state.value)
                            launchPicker(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json")
                                .putExtra(Intent.EXTRA_TITLE, localized(R.string.text_thorgallery_settings_json_c065ac, "DS Gallery-impostazioni.json"))
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION), true)
                        } catch (_: Exception) { failed = true; message = localized(R.string.text_current_settings_cannot_be_exported_9a7577, "Le impostazioni attuali non possono essere esportate.") }
                    }
                }
            }
            Surface(Modifier.weight(1f), shape = GalleryShape, color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(Modifier.fillMaxWidth().padding(if (embedded) 8.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    GalleryButton(localized(R.string.text_choose_settings_f671c8, "Scegli impostazioni…"), Modifier.fillMaxWidth().testTag("choose-settings"), enabled = !busy) {
                        candidate = null; baseline = null; message = null
                        launchPicker(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream"))
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), false)
                    }
                }
            }
            }
            }
            candidate?.let { raw ->
                val imported = remember(raw) { PreferencesTransfer.decode(raw) }
                Surface(shape = GalleryShape, color = MaterialTheme.colorScheme.surfaceVariant) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(localized(R.string.text_preview_e9bcae, "Anteprima"), style = MaterialTheme.typography.titleMedium)
                        Text(localized(R.string.text_theme_1_s_ngifs_2_s_n_0e9255, "Tema: %1\$s\nGIF: %2\$s\n", GalleryThemes.get(imported).name, when (imported.gifMode) { "OFF" -> localized(R.string.text_off_de3be8, "Disattivate"); "VISIBLE" -> localized(R.string.text_visible_9c4acf, "Visibili"); else -> localized(R.string.text_selected_only_07ada4, "Solo selezionata") }) +
                            localized(R.string.text_personal_controller_profiles_1_s_ngame_mode_2_s_59f2c2, "Profili controller personali: %1\$s\nModalità Gioco: %2\$s", imported.pad.profiles.size - 1, if (imported.gameMode) localized(R.string.text_yes_ad273c, "sì") else "no"), Modifier.testTag("settings-preview"))
                        Text(
                            localized(R.string.text_reduced_motion_1_s_nphoto_interval_2_s_s_n_488a86, "Movimento ridotto: %1\$s\nCambio immagine: %2\$s s\n", if (imported.reducedMotion) localized(R.string.text_yes_ad273c, "sì") else "no", imported.slideshowSeconds) +
                            localized(R.string.text_artwork_1_s_nprevious_2_s_n_462b2e, "Copertine: %1\$s\nPrecedente: %2\$s\n", ArtworkOrders.label(imported.artworkOrder), if (imported.alwaysPrevious) localized(R.string.text_always_previous_track_c63ca0, "sempre brano precedente") else localized(R.string.text_restart_after_three_seconds_2e0291, "riavvio dopo tre secondi")) +
                            localized(R.string.text_stick_activate_1_s_release_2_s_nrepeat_3_s_4_s_m_a3104d, "Stick: attiva %1\$s%%, rilascia %2\$s%%\nRipetizione: %3\$s / %4\$s ms", (imported.pad.press * 100).toInt(), (imported.pad.release * 100).toInt(), imported.pad.delay, imported.pad.repeat),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(localized(R.string.text_double_tap_1_s_nlong_press_2_s_n_610152, "Doppio tocco: %1\$s\nPressione prolungata: %2\$s\n", imported.touch.doubleTap.title, imported.touch.longPress.title) +
                            localized(R.string.text_touch_zones_1_s_nassigned_three_finger_direction_26358f, "Zone touch: %1\$s\nDirezioni a tre dita assegnate: %2\$s", imported.touch.zones.size, imported.touch.threeFinger.values.sumOf { bindings -> bindings.values.count { it != TouchAction.NONE } }),
                            Modifier.testTag("settings-touch-preview"), style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(common, { common = it }, enabled = !busy, modifier = Modifier.testTag("import-common-profile"))
                            Text(localized(R.string.text_also_use_the_document_s_shared_controls_de11b1, "Usa anche i comandi comuni del documento"), Modifier.weight(1f))
                        }
                        Text(localized(R.string.text_leave_this_off_to_keep_current_shared_controls_p_8d66bb, "Se lasci questa scelta disattivata, conservi i comandi comuni attuali. I profili personali si attivano soltanto sul controller corrispondente."), style = MaterialTheme.typography.bodySmall)
                        if (prefs != baseline?.let(PreferencesTransfer::decode)) Text(localized(R.string.text_preferences_changed_read_the_document_again_befo_fb7e76, "Le preferenze sono cambiate. Rileggi il documento prima di importare."),
                            Modifier.testTag("settings-import-stale"), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Text(localized(R.string.text_this_document_is_not_a_backup_of_files_or_collec_fca373, "Questo documento non è un backup dei file o delle raccolte. Non contiene sorgenti, permessi, cestino o assegnazioni degli schermi; la cessione al gioco rimane invariata."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
        }
        candidate?.let { raw ->
            Surface(color = MaterialTheme.colorScheme.background) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GalleryButton(localized(R.string.text_discard_838670, "Scarta"), Modifier.weight(1f).testTag("discard-settings-import"), enabled = !busy) { candidate = null; baseline = null; message = null }
                    GalleryButton(localized(R.string.text_import_and_save_586cbd, "Importa e salva"), Modifier.weight(1f).testTag("apply-settings-import"), primary = true,
                        enabled = !busy && prefs == baseline?.let(PreferencesTransfer::decode)) {
                        val expected = baseline!!; val useCommon = common
                        work {
                            withContext(Dispatchers.IO) { app.preferences.importSettings(PreferencesTransfer.decode(raw), PreferencesTransfer.decode(expected), useCommon) }
                            candidate = null; baseline = null
                            localized(R.string.text_settings_imported_and_saved_files_and_screens_st_9e3e7e, "Impostazioni importate e salvate. I file e gli schermi restano invariati.")
                        }
                    }
                }
            }
        }
    }
}
