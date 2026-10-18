package it.ayngallery

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.decode.BitmapFactoryDecoder
import coil3.request.ImageRequest
import coil3.video.VideoFrameDecoder
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun ImportMediaDialog(activity: PanelActivity, encoded: String, data: CatalogSnapshot, prefs: GalleryPreferences,
    dismiss: () -> Unit, accepted: () -> Unit) {
    val app = activity.application as GalleryApp
    val selection = remember(encoded) { IncomingSelection.decode(encoded) }
    var inspectedRaw by rememberSaveable(encoded) { mutableStateOf<String?>(null) }
    var chosen by rememberSaveable(encoded) { mutableStateOf(selection.uris) }
    var destination by rememberSaveable(encoded) { mutableStateOf("") }
    var customName by rememberSaveable(encoded) { mutableStateOf<String?>(null) }
    var editingName by rememberSaveable(encoded) { mutableStateOf(false) }
    var keepBoth by rememberSaveable(encoded) { mutableStateOf(true) }
    var allowGame by rememberSaveable(encoded) { mutableStateOf(false) }
    var error by rememberSaveable(encoded) { mutableStateOf<String?>(null) }
    var folders by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var loaded by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val inspected = remember(inspectedRaw) { inspectedRaw?.let(::JSONObject) }
    val files = remember(inspectedRaw) { inspected?.getJSONArray("files")?.let { array ->
        (0 until array.length()).map { IncomingFile.decode(array.getJSONObject(it).toString()) }
    }.orEmpty() }
    val targets = files.filter { it.uri in chosen }
    val musical = targets.isNotEmpty() && targets.all { it.kind == "AUDIO" }
    val role = if (musical) "MUSIC" else if (targets.any { it.kind == "AUDIO" }) "MIXED" else "VISUAL"
    val candidates = data.albums.filter { album -> album.id !in data.excludedAlbums && data.albumMembership.any { link ->
        link.albumId == album.id && link.present && data.sources.any { it.id == link.sourceId && it.id != DeviceGallery.SOURCE && !it.singleDocument &&
            it.enabled && it.reachable && it.role in setOf(role, "MIXED") }
    } }.sortedWith(compareBy(MediaNames.natural) { it.path })
    val selected = candidates.find { it.id == destination }
    val name = customName ?: targets.singleOrNull()?.proposedName.orEmpty()
    val height = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { response ->
        val uri = response.data?.data
        if (response.resultCode == Activity.RESULT_OK && uri != null) scope.launch {
            busy = true; error = null
            try { destination = withContext(Dispatchers.IO) { app.catalog.authorizeDestination(uri, SourceRole.valueOf(role), response.data?.flags ?: 0) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: localized(R.string.text_destination_not_authorized_9ab1e7, "Destinazione non autorizzata") }
            finally { busy = false }
        }
    }
    LaunchedEffect(encoded) {
        if (inspectedRaw != null) return@LaunchedEffect
        try {
            inspectedRaw = IncomingMedia.inspected(activity, selection) { loaded = it }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: localized(R.string.text_files_not_acquired_6bf6a7, "File non acquisiti") }
    }
    GalleryAlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text(localized(R.string.text_import_a_copy_9719ca, "Importa una copia")) }, text = {
        Column(Modifier.heightIn(max = height * .56f).verticalScroll(rememberScrollState()).testTag("import-content"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(localized(R.string.text_chosen_files_only_no_provider_scanning_or_cloud_49b792, "Solo i file scelti. Nessuna scansione del provider o sincronizzazione cloud, nessuna modifica all'originale esterno."), style = MaterialTheme.typography.bodySmall)
            if (inspected == null) {
                if (!prefs.reducedMotion) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(localized(R.string.text_reading_chosen_files_1_s_2_s_d7f692, "Lettura dei file scelti · %1\$s / %2\$s", loaded, selection.uris.size), Modifier.testTag("import-loading"))
            } else {
                Text(localized(R.string.text_1_s_of_2_s_selected_files_fbb670, "%1\$s di %2\$s file scelti", targets.size, selection.uris.size), Modifier.testTag("import-count"), style = MaterialTheme.typography.titleSmall)
                val errors = inspected.getJSONArray("errors")
                if (errors.length() > 0) Text(localized(R.string.text_these_files_are_not_included_n_a7c42f, "Questi file non sono inclusi:\n") + (0 until errors.length()).joinToString("\n") { errors.getString(it) }, Modifier.testTag("import-unavailable"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.height((height * .28f).coerceIn(112.dp, 240.dp)).testTag("import-files"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(files, key = { it.id }) { file -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Checkbox(file.uri in chosen, { include -> chosen = if (include) chosen + file.uri else chosen - file.uri; customName = null; error = null }, enabled = !busy,
                            modifier = Modifier.testTag("import-choice:${file.id}").semantics { contentDescription = localized(R.string.text_include_1_s_414139, "Includi %1\$s", file.name) })
                        if (file.kind == "AUDIO") GalleryIcon(R.drawable.ph_music_notes, null, Modifier.size(48.dp))
                        else {
                            val image = remember(file) { ImageRequest.Builder(activity).data(file.uri).size(160, 160)
                                .memoryCacheKey("import:${selection.id}:${file.id}:${file.size}:${file.modified}")
                                .decoderFactory(if (file.kind == "VIDEO") VideoFrameDecoder.Factory() else BitmapFactoryDecoder.Factory()).build() }
                            AsyncImage(image, localized(R.string.text_preview_of_1_s_62847b, "Anteprima di %1\$s", file.name), Modifier.size(48.dp), contentScale = ContentScale.Crop)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(file.name, Modifier.testTag("import-target:${file.id}"), style = MaterialTheme.typography.bodyMedium)
                            Text("${when (file.kind) { "IMAGE" -> localized(R.string.text_image_850b6f, "Immagine"); "AUDIO" -> localized(R.string.text_track_3c0db2, "Brano"); "VIDEO" -> "Video"; else -> "GIF" }} · ${fileSize(file.size)}", style = MaterialTheme.typography.bodySmall)
                            Text(localized(R.string.text_copy_1_s_47dc62, "Copia: %1\$s", file.proposedName), style = MaterialTheme.typography.labelSmall)
                        }
                    } }
                }
                GalleryButton(if (targets.size == 1) localized(R.string.text_name_1_s_7ad389, "Nome: %1\$s", name) else localized(R.string.text_original_names_for_multiple_selection_559576, "Nomi originali per la selezione multipla"),
                    Modifier.fillMaxWidth().testTag("import-name"), enabled = !busy && targets.size == 1) { editingName = true }
                Box {
                    GalleryButton(selected?.let { localized(R.string.text_destination_1_s_27ea61, "Destinazione: %1\$s", it.path) } ?: localized(R.string.text_choose_destination_7320c1, "Scegli destinazione"), Modifier.fillMaxWidth().testTag("import-destination"), enabled = !busy && targets.isNotEmpty()) { folders = true }
                    GalleryDropdownMenu(folders, { folders = false }, Modifier.heightIn(max = 240.dp)) {
                        candidates.forEach { album -> DropdownMenuItem({ Text(album.path) }, { destination = album.id; folders = false }, modifier = Modifier.testTag("import-folder:${album.id}")) }
                    }
                }
                GalleryButton(localized(R.string.text_choose_another_folder_423db4, "Scegli un'altra cartella…"), Modifier.fillMaxWidth(), enabled = !busy && targets.isNotEmpty()) {
                    app.session.current(activity)?.let { here -> picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
                        .putExtra(ActivityResultContracts.StartActivityForResult.EXTRA_ACTIVITY_OPTIONS_BUNDLE, ActivityOptions.makeBasic().setLaunchDisplayId(here.id).toBundle())) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(keepBoth, { keepBoth = it }, enabled = !busy, modifier = Modifier.testTag("import-keep-both").semantics { contentDescription = localized(R.string.text_keep_both_when_the_name_is_taken_212871, "Mantieni entrambe quando il nome è occupato") })
                    Text(localized(R.string.text_keep_both_if_the_name_is_taken_cbae14, "Mantieni entrambe se il nome è occupato"), style = MaterialTheme.typography.bodySmall)
                }
                Text(localized(R.string.text_copies_original_bytes_without_recompression_reta_6efbf0, "Copia dei byte originali, senza ricompressione: conserva anche gli eventuali metadati personali. Non vengono aggiunti Preferiti o collegamenti al provider. La nuova copia sarà disponibile nella destinazione solo dopo la verifica."), style = MaterialTheme.typography.bodySmall)
                Text(localized(R.string.text_if_temporary_access_expires_before_acquisition_t_8742b5, "Se l'accesso temporaneo scade prima dell'acquisizione, l'operazione si ferma: riapri il selettore e riprova. Puoi seguire ogni file in Copie ed esportazioni."), style = MaterialTheme.typography.bodySmall)
                if (prefs.gameMode) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(allowGame, { allowGame = it }, enabled = !busy, modifier = Modifier.testTag("import-game-consent").semantics { contentDescription = localized(R.string.text_allow_import_while_gaming_ddd9a8, "Autorizza importazione durante il gioco") })
                    Text(localized(R.string.text_import_while_gaming_anyway_reading_from_the_prov_352ee8, "Importa comunque durante il gioco. La lettura del provider può usare batteria e rallentarlo."), style = MaterialTheme.typography.bodySmall)
                }
            }
            error?.let { Text(it, Modifier.testTag("import-error"), color = MaterialTheme.colorScheme.error) }
            if (busy) Text(localized(R.string.text_preparing_operations_1e55d8, "Preparazione delle operazioni…"), style = MaterialTheme.typography.labelSmall)
        }
    }, dismissButton = { TextButton({ dismiss() }, enabled = !busy, modifier = Modifier.testTag("import-cancel")) { Text(localized(R.string.text_cancel_18c9d9, "Annulla")) } }, confirmButton = {
        TextButton(enabled = !busy && targets.isNotEmpty() && selected != null && (targets.size != 1 || name.isNotBlank()) && (!prefs.gameMode || allowGame),
            modifier = Modifier.testTag("import-confirm"), onClick = {
                val frozen = targets.toList(); val album = selected!!.id; val names = if (frozen.size == 1) mapOf(frozen.single().id to name) else emptyMap()
                val keep = keepBoth; val consent = allowGame; busy = true; error = null
                scope.launch {
                    try { withContext(Dispatchers.IO) { app.files.enqueueImports(frozen, album, names, keep, consent) }; accepted() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = failure.message ?: localized(R.string.text_import_did_not_start_7308d0, "Importazione non avviata") }
                    finally { busy = false }
                }
            }) { Text(localized(R.string.text_import_copies_38f630, "Importa copie")) }
    })
    if (editingName && targets.size == 1) {
        val field = rememberTextFieldState(name)
        var nameError by remember { mutableStateOf<String?>(null) }
        GalleryAlertDialog(onDismissRequest = { editingName = false }, title = { Text(localized(R.string.text_new_copy_name_f686d2, "Nome della nuova copia")) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(localized(R.string.text_keep_the_original_extension_the_name_does_not_co_d2004e, "Mantieni l'estensione originale: il nome non converte il formato."), style = MaterialTheme.typography.bodySmall)
                BasicTextField(field, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("import-name-input")
                    .semantics { contentDescription = localized(R.string.text_new_copy_name_f686d2, "Nome della nuova copia") }.background(MaterialTheme.colorScheme.surfaceVariant, GalleryShape).padding(14.dp),
                    lineLimits = TextFieldLineLimits.SingleLine, textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary))
                nameError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, dismissButton = { TextButton({ editingName = false }) { Text(localized(R.string.text_cancel_18c9d9, "Annulla")) } }, confirmButton = {
            TextButton({
                try { val value = field.text.toString(); FileNames.validate(value, targets.single().proposedName); customName = value; editingName = false }
                catch (failure: IllegalArgumentException) { nameError = failure.message }
            }, enabled = field.text.isNotBlank() && field.text.length <= 180, modifier = Modifier.testTag("import-name-save")) { Text(localized(R.string.text_use_this_name_c09501, "Usa questo nome")) }
        })
    }
}
