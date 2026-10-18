package it.ayngallery

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
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
import coil3.request.CachePolicy
import coil3.video.VideoFrameDecoder
import kotlinx.coroutines.*
import java.io.File

@Composable
fun FolderRelinkScreen(activity: PanelActivity, data: CatalogSnapshot, prefs: GalleryPreferences, source: String?) {
    val app = activity.application as GalleryApp
    val engine = remember(app) { FolderRelinking(app.catalog) }
    val drafts = remember(app) { FolderRelinkDrafts(File(app.filesDir, "folder-relink")) }
    val scope = rememberCoroutineScope()
    val original = data.sources.find { it.id == source }
    val roots = remember(data, source) { source?.let { FolderRelinking.roots(data, it) }.orEmpty() }
    val albums = remember(data, source) { val ids = data.albumMembership.filter { it.sourceId == source }.map { it.albumId }.toSet()
        data.albums.filter { it.id in ids }.sortedWith(compareBy(MediaNames.natural) { it.path }) }
    var rootId by rememberSaveable(source) { mutableStateOf(roots.singleOrNull()?.id) }
    var rootPicker by remember { mutableStateOf(false) }
    var rootQuery by rememberSaveable { mutableStateOf("") }
    var draftId by rememberSaveable(source) { mutableStateOf<String?>(null) }
    var review by remember { mutableStateOf<FolderRelinkReview?>(null) }
    val index = remember(review) { review?.let(::FolderRelinkIndex) }
    var selected by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var verified by rememberSaveable(draftId) { mutableStateOf(false) }
    var consent by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var examined by remember { mutableIntStateOf(0) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var chooseFor by remember { mutableStateOf<FolderReference?>(null) }
    var inspect by remember { mutableStateOf<Pair<FolderReference, FolderFile>?>(null) }
    val allowed = !prefs.gameMode || consent
    fun work(action: suspend () -> String) {
        busy = true; message = null
        scope.launch {
            try { message = action().takeIf { it.isNotBlank() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { message = failure.message?.take(350) ?: localized(R.string.text_folder_unavailable_choose_it_again_no_originals_2a7f89, "Cartella non disponibile. Sceglila nuovamente; nessun originale modificato.") }
            finally { busy = false }
        }
    }
    LaunchedEffect(Unit) {
        draftId?.let { id -> busy = true
            try { val stored = withContext(Dispatchers.IO) { drafts.read(id) to drafts.choices(id) }; review = stored.first; selected = stored.second }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { draftId = null; message = localized(R.string.text_preview_unavailable_choose_the_folder_again_no_r_2a78f0, "Anteprima non disponibile: scegli nuovamente la cartella. Nessun riferimento modificato.") }
            finally { busy = false }
        }
    }
    fun selections(value: Map<String, String>) {
        val id = draftId ?: return
        verified = false
        work { withContext(Dispatchers.IO) { drafts.choices(id, value) }; selected = value; verified = false; "" }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { response ->
        if (response.resultCode == Activity.RESULT_OK && response.data?.data != null && source != null && rootId != null) {
            val intent = response.data!!; val oldRoot = rootId!!; val grant = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            work {
                examined = 0
                val result = withContext(Dispatchers.IO) {
                    val next = engine.preview(source, oldRoot, intent.data!!.toString(), intent.flags and grant == grant, consent) { examined = it }
                    drafts.create(next) to next
                }
                val previous = draftId; draftId = result.first; review = result.second; selected = emptyMap(); verified = false
                previous?.let { withContext(Dispatchers.IO) { drafts.discard(it) } }
                localized(R.string.text_folder_read_choose_and_verify_matches_no_persist_cd57e0, "Cartella letta. Scegli e verifica le corrispondenze; nessun accesso persistente acquisito.")
            }
        }
    }
    fun choose() {
        val here = app.session.current(activity) ?: return
        if (here.key != activity.screenKey || here.key in app.session.state.ceded || !activity.isStartedFlow.value || activity.isFinishing) return
        try { picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            .putExtra(ActivityResultContracts.StartActivityForResult.EXTRA_ACTIVITY_OPTIONS_BUNDLE, ActivityOptions.makeBasic().setLaunchDisplayId(here.id).toBundle())) }
        catch (_: RuntimeException) { message = localized(R.string.text_folder_picker_unavailable_on_this_panel_72be79, "Selettore cartelle non disponibile su questo pannello") }
    }
    Column(Modifier.fillMaxSize().testTag("folder-relink")) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (review == null) localized(R.string.text_find_your_folder_d5b7f4, "Ritrova la tua cartella") else localized(R.string.text_check_matches_871bbc, "Verifica le corrispondenze"), style = MaterialTheme.typography.titleMedium)
            AnimatedVisibility(busy, enter = fadeIn(tween(if (prefs.reducedMotion) 0 else 180)), exit = fadeOut(tween(if (prefs.reducedMotion) 0 else 160))) {
                Column { if (!prefs.reducedMotion) LinearProgressIndicator(Modifier.fillMaxWidth()); Text(localized(R.string.text_verifying_1_s_documents_examined_55eddd, "Verifica in corso · %1\$s documenti esaminati", examined), style = MaterialTheme.typography.bodySmall) }
            }
            message?.let { Text(it, Modifier.testTag("folder-relink-message"), style = MaterialTheme.typography.bodySmall, maxLines = 3) }
        }
        val frozen = review
        if (frozen == null) LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(localized(R.string.text_original_source_1_s_b2b855, "Sorgente originale: %1\$s", original?.title ?: localized(R.string.text_unavailable_1d80f0, "non disponibile")), style = MaterialTheme.typography.titleSmall) }
            item { Text(localized(R.string.text_specify_the_original_folder_then_its_new_locatio_9026c2, "Indica la cartella originale e poi la sua nuova posizione. Prima della conferma vengono letti soltanto i metadati: nessun file è copiato o modificato.")) }
            if (prefs.gameMode) item { Row { Checkbox(consent, { consent = it }, enabled = !busy, modifier = Modifier.testTag("folder-relink-game")); Text(localized(R.string.text_allow_this_check_in_game_mode_5680c6, "Consenti questa verifica durante la modalità Gioco"), Modifier.weight(1f)) } }
            item { GalleryButton(data.albumsById[rootId]?.path ?: localized(R.string.text_choose_original_folder_7c8b3b, "Scegli cartella originale…"), Modifier.fillMaxWidth().testTag("folder-original-root"), enabled = !busy) { rootPicker = true } }
            item { GalleryButton(localized(R.string.text_choose_new_folder_9ec30a, "Scegli nuova cartella…"), Modifier.fillMaxWidth().testTag("folder-choose-root"), primary = true, enabled = !busy && allowed && rootId != null && original != null) { choose() } }
            item { Text(localized(R.string.text_the_chosen_folder_is_added_or_recovered_as_a_rea_3251be, "La cartella scelta sarà aggiunta o recuperata come sorgente in sola lettura soltanto dopo Ricollega. I media non selezionati saranno indicizzati senza ereditare i vecchi Preferiti."), style = MaterialTheme.typography.bodySmall) }
        } else if (index != null) {
            val unambiguous = remember(index) { index.uniqueChoices() }
            LazyColumn(Modifier.weight(1f).testTag("folder-review-list"), contentPadding = PaddingValues(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Surface(shape = GalleryShape, color = MaterialTheme.colorScheme.surfaceVariant) { Column(Modifier.padding(16.dp)) {
                    Text(localized(R.string.text_from_1_s_2e9c11, "Da: %1\$s", frozen.originalPath)); Text(localized(R.string.text_to_1_s_e9250f, "A: %1\$s", frozen.title))
                    Text(localized(R.string.text_1_s_references_2_s_media_in_the_folder_fff099, "%1\$s riferimenti · %2\$s media nella cartella", frozen.references.size, frozen.files.size), style = MaterialTheme.typography.bodySmall)
                    Text(localized(R.string.text_1_s_selected_matches_01a2e5, "%1\$s corrispondenze selezionate", selected.size), Modifier.testTag("folder-selected-count"), style = MaterialTheme.typography.titleSmall)
                } } }
                item { Text(localized(R.string.text_path_name_and_size_are_clues_the_backup_has_no_f_3d5d0a, "Percorso, nome e dimensione sono indizi: il backup non contiene gli hash dei file. Verifica le proposte prima di associarle. I casi ambigui e le versioni differenti richiedono una scelta individuale.")) }
                item { GalleryButton(localized(R.string.text_select_unique_matches_1_s_87601c, "Seleziona uniche (%1\$s)", unambiguous.size), Modifier.fillMaxWidth().testTag("folder-select-unique"), enabled = !busy && unambiguous.isNotEmpty()) {
                    selections(selected + unambiguous.filter { it.key !in selected && it.value !in selected.values })
                } }
                item { GalleryButton(localized(R.string.text_clear_selection_508e08, "Azzera selezione"), Modifier.fillMaxWidth(), enabled = !busy && selected.isNotEmpty()) { selections(emptyMap()) } }
                items(frozen.references, key = { it.id }) { old ->
                    val target = index.files[selected[old.id]]; val candidates = index.candidates(old)
                    Surface(shape = GalleryShape, color = MaterialTheme.colorScheme.surfaceVariant) { Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(FolderRelinkIndex.label(old.path), style = MaterialTheme.typography.titleSmall)
                        Text(target?.let { localized(R.string.text_chosen_1_s_c8f1bf, "Scelto: %1\$s", FolderRelinkIndex.label(it.path)) } ?: when {
                            candidates.isEmpty() -> localized(R.string.text_no_matches_in_the_folder_52d8ec, "Nessuna corrispondenza nella cartella")
                            candidates.size > 1 -> localized(R.string.text_1_s_candidates_choice_required_107fb7, "%1\$s candidati: scelta necessaria", candidates.size)
                            index.unique(old) == null -> localized(R.string.text_different_data_or_path_verification_required_660629, "Dati o percorso differenti: verifica necessaria")
                            else -> localized(R.string.text_suggested_unique_match_needs_confirmation_5111c8, "Corrispondenza unica proposta, da confermare")
                        }, style = MaterialTheme.typography.bodySmall)
                        if (old.excluded) Text(localized(R.string.text_excluded_album_stays_excluded_after_relinking_8db313, "Album escluso: resta escluso dopo il ricollegamento."), style = MaterialTheme.typography.labelSmall)
                        GalleryButton(if (target == null) localized(R.string.text_check_candidates_8155a8, "Controlla candidati…") else localized(R.string.text_check_change_36b194, "Controlla / cambia…"), Modifier.fillMaxWidth().testTag("folder-candidates:${old.id}"), enabled = !busy && candidates.isNotEmpty()) { chooseFor = old }
                        if (target != null) TextButton({ selections(selected - old.id) }, enabled = !busy) { Text(localized(R.string.text_leave_unlinked_16ff6e, "Lascia da ricollegare")) }
                    } }
                }
                item { Text(localized(R.string.text_confirming_authorizes_the_entire_folder_read_onl_43d5fe, "Confermare autorizza tutta la cartella in sola lettura: %1\$s. Vengono trasferiti solo i riferimenti selezionati. Preferiti, raccolte, cover e pin seguono i file; le esclusioni restano attive.", when (frozen.role) { "MUSIC" -> localized(R.string.text_music_and_local_covers_without_adding_them_to_ph_316e17, "musica e copertine locali, senza aggiungerle a Foto"); "VISUAL" -> localized(R.string.text_photos_and_videos_1f1516, "foto e video"); else -> localized(R.string.text_photos_videos_and_music_564f16, "foto, video e musica") }), style = MaterialTheme.typography.bodySmall) }
                if (prefs.gameMode) item { Row { Checkbox(consent, { consent = it }, enabled = !busy, modifier = Modifier.testTag("folder-relink-game")); Text(localized(R.string.text_allow_this_check_in_game_mode_5680c6, "Consenti questa verifica durante la modalità Gioco"), Modifier.weight(1f)) } }
                item { Row { Checkbox(verified, { verified = it }, enabled = !busy && selected.isNotEmpty(), modifier = Modifier.testTag("folder-relink-verify")); Text(localized(R.string.text_i_checked_the_selected_matches_71f4d0, "Ho verificato le corrispondenze selezionate"), Modifier.weight(1f)) } }
                item { GalleryButton(localized(R.string.text_choose_another_folder_423db4, "Scegli un'altra cartella…"), Modifier.fillMaxWidth(), enabled = !busy && allowed) { choose() } }
                item { Spacer(Modifier.height(16.dp)) }
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GalleryButton(localized(R.string.text_discard_838670, "Scarta"), Modifier.weight(1f).testTag("folder-relink-discard"), enabled = !busy) {
                    val id = draftId!!; work { withContext(Dispatchers.IO) { drafts.discard(id) }; draftId = null; review = null; selected = emptyMap(); localized(R.string.text_choices_discarded_permissions_and_references_pre_811d22, "Scelte scartate; permessi e riferimenti conservati") }
                }
                GalleryButton(localized(R.string.text_relink_e366de, "Ricollega"), Modifier.weight(1f).testTag("folder-relink-confirm"), primary = true, enabled = !busy && verified && selected.isNotEmpty() && allowed) {
                    val choices = selected.toMap(); val id = draftId!!
                    work { val result = engine.confirm(frozen, choices, verified, consent); withContext(Dispatchers.IO) { drafts.discard(id) }; draftId = null; review = null; selected = emptyMap()
                        localized(R.string.text_1_s_references_relinked_folder_authorized_for_re_e1eaeb, "%1\$s riferimenti ricollegati. Cartella autorizzata in lettura; nessun originale modificato o media avviato.", result.size) }
                }
            }
        }
    }
    if (rootPicker) GalleryAlertDialog(onDismissRequest = { rootPicker = false }, title = { Text(localized(R.string.text_original_folder_e41647, "Cartella originale")) }, text = {
        Column { OutlinedTextField(rootQuery, { rootQuery = it.take(200) }, label = { Text(localized(R.string.text_search_backup_059b95, "Cerca nel backup")) }, singleLine = true)
            LazyColumn(Modifier.heightIn(max = 280.dp)) { items(albums.filter { it.path.contains(rootQuery, true) }, key = { it.id }) { album ->
                GalleryButton(album.path, Modifier.fillMaxWidth()) { rootId = album.id; rootPicker = false }
            } }
        }
    }, confirmButton = { TextButton({ rootPicker = false }) { Text(localized(R.string.text_close_32d407, "Chiudi")) } })
    chooseFor?.let { old -> GalleryAlertDialog(onDismissRequest = { chooseFor = null }, title = { Text(localized(R.string.text_candidates_for_1_s_b55cd1, "Candidati per %1\$s", old.name)) }, text = {
        LazyColumn(Modifier.heightIn(max = 320.dp)) { items(index?.candidates(old).orEmpty(), key = { it.key }) { file ->
            GalleryButton("${FolderRelinkIndex.label(file.path)} · ${file.size} byte", Modifier.fillMaxWidth().testTag("folder-target:${file.key}"), enabled = !busy) { inspect = old to file; chooseFor = null }
        } }
    }, confirmButton = { TextButton({ chooseFor = null }) { Text(localized(R.string.text_close_32d407, "Chiudi")) } }) }
    inspect?.let { (old, file) ->
        val used = selected.any { it.key != old.id && it.value == file.key }
        GalleryAlertDialog(onDismissRequest = { inspect = null }, title = { Text(localized(R.string.text_compare_the_reference_f68129, "Confronta il riferimento")) }, text = {
            Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                fun date(value: Long) = if (value > 0) java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(value)) else localized(R.string.text_date_unavailable_932e7b, "data non disponibile")
                Text(localized(R.string.text_original_1_s_n_2_s_3_s_n_4_s_cbe330, "Originale: %1\$s\n%2\$s · %3\$s\n%4\$s", FolderRelinkIndex.label(old.path), old.mime, old.size.takeIf { it >= 0 }?.let { "$it byte" } ?: localized(R.string.text_unknown_size_c08f91, "dimensione non nota"), date(old.modified)))
                Text(localized(R.string.text_chosen_1_s_n_2_s_3_s_n_4_s_5b7d73, "Scelto: %1\$s\n%2\$s · %3\$s\n%4\$s", FolderRelinkIndex.label(file.path), file.mime, file.size.takeIf { it >= 0 }?.let { "$it byte" } ?: localized(R.string.text_unknown_size_c08f91, "dimensione non nota"), date(file.modified)))
                if (file.kind != "AUDIO") {
                    val request = remember(file.uri) { ImageRequest.Builder(activity).data(file.uri).size(320, 220).memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED)
                        .decoderFactory(if (file.kind == "VIDEO") VideoFrameDecoder.Factory() else BitmapFactoryDecoder.Factory()).build() }
                    AsyncImage(request, localized(R.string.text_candidate_preview_142090, "Anteprima del candidato"), Modifier.fillMaxWidth().height(150.dp), contentScale = ContentScale.Fit)
                }
                if (used) Text(localized(R.string.text_this_file_is_already_chosen_for_another_referenc_995a49, "Questo file è già scelto per un altro riferimento. Lasciane uno soltanto."), color = MaterialTheme.colorScheme.error)
                Text(localized(R.string.text_confirm_only_if_you_recognize_the_file_identical_c20d48, "Conferma solo se riconosci il file: nomi e dimensioni uguali non dimostrano l'identità. La scelta verrà applicata soltanto con Ricollega."))
            }
        }, confirmButton = { TextButton({ selections(selected + (old.id to file.key)); inspect = null }, enabled = !busy && !used, modifier = Modifier.testTag("folder-associate")) { Text(localized(R.string.text_choose_this_one_255533, "Scegli questo")) } },
            dismissButton = { TextButton({ inspect = null }) { Text(localized(R.string.text_close_32d407, "Chiudi")) } })
    }
}
