package it.ayngallery

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.decode.BitmapFactoryDecoder
import coil3.request.ImageRequest
import coil3.video.VideoFrameDecoder
import kotlinx.coroutines.*

@Composable
fun MediaRelinkScreen(activity: PanelActivity, data: CatalogSnapshot, prefs: GalleryPreferences, source: String?) {
    val app = activity.application as GalleryApp
    val engine = remember(app) { MediaRelinking(app.catalog) }
    val scope = rememberCoroutineScope()
    var pendingId by rememberSaveable { mutableStateOf<String?>(null) }
    var raw by rememberSaveable { mutableStateOf<String?>(null) }
    val candidate = remember(raw) { raw?.let(RelinkCandidate::decode) }
    var verified by rememberSaveable(raw) { mutableStateOf(false) }
    var gameConsent by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    val missing = remember(data, source) { MediaRelinking.missing(data, source) }
    val folderScopes = remember(data, source, missing) {
        val ids = missing.map { it.id }.toSet(); val scopes = data.membership.filter { it.mediaId in ids }.map { it.sourceId }.toSet()
        data.sources.filter { !it.singleDocument && (source == null || it.id == source) && it.id in scopes }
    }
    val allowed = !prefs.gameMode || gameConsent
    fun work(action: suspend () -> String) {
        busy = true; message = null
        scope.launch {
            try { message = action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { message = when (failure) {
                is SecurityException -> localized(R.string.text_the_file_is_no_longer_readable_choose_it_again_i_005adc, "Il file non è più leggibile. Sceglilo nuovamente dal selettore Android.")
                is java.io.IOException -> localized(R.string.text_the_document_is_unavailable_or_changed_choose_it_3a88b9, "Il documento non è disponibile o è cambiato. Sceglilo nuovamente dal selettore Android.")
                else -> failure.message?.take(400) ?: localized(R.string.text_relinking_unverified_choose_the_document_again_n_9c784f, "Ricollegamento non verificato. Scegli nuovamente il documento. Nessun originale modificato.")
            } }
            finally { busy = false }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { response ->
        val old = pendingId; pendingId = null
        if (old != null && response.resultCode == Activity.RESULT_OK && response.data != null) work {
            val intent = response.data!!
            val selection = IncomingSelection.from(intent)
            require(selection.uris.size == 1) { localized(R.string.text_choose_only_one_file_for_this_reference_de6fed, "Scegli un solo file per questo riferimento") }
            val selected = IncomingMedia.inspect(activity, selection).single()
            val canPersist = intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0
            raw = engine.preview(old, selected, canPersist).encode(); verified = false
            localized(R.string.text_file_readable_compare_the_data_and_confirm_only_2b77a7, "File leggibile. Confronta i dati e conferma soltanto se è quello che vuoi ricollegare.")
        }
    }
    fun choose(item: MediaEntity) {
        val here = app.session.current(activity) ?: return
        if (here.key != activity.screenKey || here.key in app.session.state.ceded || !activity.isStartedFlow.value || activity.isFinishing) return
        pendingId = item.id
        try { picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(when (item.kind) { "GIF" -> "image/gif"; "VIDEO" -> "video/*"; "AUDIO" -> "audio/*"; else -> "image/*" })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            .putExtra(ActivityResultContracts.StartActivityForResult.EXTRA_ACTIVITY_OPTIONS_BUNDLE, ActivityOptions.makeBasic().setLaunchDisplayId(here.id).toBundle())) }
        catch (_: RuntimeException) { pendingId = null; message = localized(R.string.text_android_picker_unavailable_on_this_panel_4b1ad8, "Selettore Android non disponibile su questo pannello") }
    }
    Column(Modifier.fillMaxSize().testTag("media-relink")) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (candidate == null) localized(R.string.text_recover_links_d1e7cc, "Recupera i collegamenti") else localized(R.string.text_confirm_chosen_file_c62c88, "Conferma il file scelto"), style = MaterialTheme.typography.titleMedium)
            if (busy) { if (!prefs.reducedMotion) LinearProgressIndicator(Modifier.fillMaxWidth()); Text(localized(R.string.text_checking_reference_9b649e, "Verifica del riferimento…")) }
            message?.let { Text(it, Modifier.testTag("relink-message"), style = MaterialTheme.typography.bodySmall, maxLines = 4) }
            if (candidate != null && prefs.gameMode) Row {
                Checkbox(gameConsent, { gameConsent = it }, enabled = !busy, modifier = Modifier.testTag("relink-game"))
                Text(localized(R.string.text_allow_this_check_in_game_mode_5680c6, "Consenti questa verifica durante la modalità Gioco"), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (candidate == null) LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(localized(R.string.text_choose_a_reference_then_its_file_in_the_android_e4100b, "Scegli un riferimento e poi il suo file dal selettore Android. Il backup conserva l'organizzazione, senza copiare i media o concedere accessi automaticamente.")) }
            items(folderScopes, key = { "folder:${it.id}" }) { scope ->
                GalleryButton(if (source == null) localized(R.string.text_relink_folder_1_s_e29041, "Ricollega cartella · %1\$s", scope.title) else localized(R.string.text_relink_a_folder_70c3bc, "Ricollega una cartella…"),
                    Modifier.fillMaxWidth().testTag("relink-folder:${scope.id}"), enabled = !busy && allowed) { activity.windowModel.relinkFolderSource(scope.id) }
            }
            if (prefs.gameMode) item { Row {
                Checkbox(gameConsent, { gameConsent = it }, enabled = !busy, modifier = Modifier.testTag("relink-game"))
                Text(localized(R.string.text_allow_this_check_in_game_mode_5680c6, "Consenti questa verifica durante la modalità Gioco"), Modifier.weight(1f))
            } }
            if (missing.isEmpty()) item { Text(localized(R.string.text_no_references_to_relink_in_this_scope_dcc7ef, "Nessun riferimento da ricollegare in questo ambito."), Modifier.testTag("relink-empty")) }
            items(missing, key = { it.id }) { old ->
                Surface(shape = GalleryShape, color = MaterialTheme.colorScheme.surfaceVariant) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(old.name, style = MaterialTheme.typography.titleSmall)
                        Text(data.albumsById[old.albumId]?.path.orEmpty(), style = MaterialTheme.typography.bodySmall)
                        Text("${old.kind} · ${if (old.size >= 0) "${old.size} byte" else localized(R.string.text_unknown_size_954717, "Dimensione non nota")}", style = MaterialTheme.typography.labelSmall)
                        if (old.albumId in data.excludedAlbums) Text(localized(R.string.text_excluded_album_relinking_keeps_the_exclusion_3c5468, "Album escluso: il ricollegamento mantiene l'esclusione."), style = MaterialTheme.typography.bodySmall)
                        GalleryButton(localized(R.string.text_choose_file_38d369, "Scegli il file…"), Modifier.fillMaxWidth().testTag("relink-file:${old.id}"), enabled = !busy && allowed) { choose(old) }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        } else {
            val old = data.media.find { it.id == candidate.oldId }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(localized(R.string.text_reference_1_s_01d1a7, "Riferimento: %1\$s", candidate.oldName), Modifier.testTag("relink-original"), style = MaterialTheme.typography.titleSmall)
                Text(candidate.oldPath, style = MaterialTheme.typography.bodySmall)
                Text(localized(R.string.text_chosen_file_1_s_368352, "File scelto: %1\$s", candidate.target.name), Modifier.testTag("relink-selected"), style = MaterialTheme.typography.titleSmall)
                Text("${candidate.target.mime} · ${candidate.target.size.takeIf { it >= 0 }?.let { "$it byte" } ?: localized(R.string.text_unknown_size_c08f91, "dimensione non nota")}", style = MaterialTheme.typography.bodySmall)
                if (candidate.target.kind != "AUDIO") {
                    val request = remember(candidate.target) { ImageRequest.Builder(activity).data(candidate.target.uri).size(320, 220).memoryCachePolicy(coil3.request.CachePolicy.DISABLED).diskCachePolicy(coil3.request.CachePolicy.DISABLED)
                        .decoderFactory(if (candidate.target.kind == "VIDEO") VideoFrameDecoder.Factory() else BitmapFactoryDecoder.Factory()).build() }
                    AsyncImage(request, localized(R.string.text_chosen_file_preview_9b3263, "Anteprima del file scelto"), Modifier.fillMaxWidth().height(160.dp), contentScale = ContentScale.Fit)
                }
                Text(localized(R.string.text_name_size_and_date_do_not_prove_this_is_the_orig_d1f102, "Nome, dimensione e data non provano che sia il file originale: il backup non contiene un hash dei media. Anche se coincidono, conferma la corrispondenza."))
                if (candidate.oldName != candidate.target.name || candidate.oldSize != candidate.target.size || candidate.oldModified != candidate.target.modified)
                    Text(localized(R.string.text_the_data_differs_from_the_saved_reference_you_ma_77bf6c, "I dati differiscono dal riferimento salvato. Potresti aver scelto una versione diversa."), color = MaterialTheme.colorScheme.error)
                Text(localized(R.string.text_favorites_collections_covers_and_pins_are_preser_53017d, "Si conservano Preferiti, raccolte, copertine e pin. Se il file è già indicizzato, i riferimenti si uniscono. Gli album esclusi restano esclusi. Nessuna copia, spostamento o riproduzione; accesso persistente solo in lettura."))
                Row {
                    Checkbox(verified, { verified = it }, enabled = !busy, modifier = Modifier.testTag("relink-verify"))
                    Text(localized(R.string.text_i_checked_that_i_want_to_link_this_file_to_the_r_172195, "Ho verificato che voglio associare questo file al riferimento"), Modifier.weight(1f))
                }
                GalleryButton(localized(R.string.text_choose_another_file_4dc30e, "Scegli un altro file…"), Modifier.fillMaxWidth().testTag("relink-again"), enabled = !busy && allowed && old != null) { old?.let(::choose) }
                Spacer(Modifier.height(16.dp))
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GalleryButton(localized(R.string.text_discard_838670, "Scarta"), Modifier.weight(1f).testTag("relink-discard"), enabled = !busy) { raw = null; message = localized(R.string.text_choice_discarded_references_and_permissions_pres_1f4a42, "Scelta scartata; riferimenti e permessi conservati") }
                GalleryButton(localized(R.string.text_relink_e366de, "Ricollega"), Modifier.weight(1f).testTag("relink-confirm"), primary = true, enabled = !busy && verified && allowed) {
                    val frozen = candidate
                    work { engine.confirm(frozen); raw = null; localized(R.string.text_reference_relinked_read_only_original_files_inta_0d4f8c, "Riferimento ricollegato in lettura. File originali intatti; nessuna riproduzione avviata.") }
                }
            }
        }
    }
}
