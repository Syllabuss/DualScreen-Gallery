package it.ayngallery

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*

@Composable
fun ArtworkPriorityChoice(value: String?, inherited: Boolean = true, enabled: Boolean = true, choose: (String?) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        GalleryButton(value?.let(ArtworkOrders::label) ?: localized(R.string.text_use_inherited_priority_720160, "Usa la priorità ereditata"), Modifier.fillMaxWidth().testTag("artwork-priority"), enabled = enabled) { menu = true }
        GalleryDropdownMenu(menu, { menu = false }, Modifier.heightIn(max = 320.dp)) {
            if (inherited) DropdownMenuItem({ Text(localized(R.string.text_use_inherited_priority_720160, "Usa la priorità ereditata")) }, { choose(null); menu = false })
            ArtworkOrders.all.forEach { order -> DropdownMenuItem({ Text(ArtworkOrders.label(order)) }, { choose(order); menu = false }, modifier = Modifier.testTag("artwork-order:$order")) }
        }
    }
}

@Composable
fun MusicArtworkDialog(app: GalleryApp, data: CatalogSnapshot, prefs: GalleryPreferences, target: String, title: String, dismiss: () -> Unit) {
    val initial = data.artworkByTarget[target]
    var cover by rememberSaveable(target) { mutableStateOf(initial?.coverMediaId) }
    var priority by rememberSaveable(target) { mutableStateOf(initial?.priority) }
    var picker by rememberSaveable(target) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    if (picker) {
        LocalMediaPicker(app, data, prefs, target, title, "MEDIA", emptySet(), true,
            onSave = { ids -> withContext(Dispatchers.Main) { cover = ids.singleOrNull() } }, done = { picker = false }, dismiss = { picker = false })
        return
    }
    GalleryAlertDialog(onDismissRequest = { if (!busy) dismiss() }, modifier = Modifier.fillMaxWidth(.94f), properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false), title = { GalleryDialogTitle(R.drawable.ph_disc, localized(R.string.text_music_artwork_335159, "Copertina musicale")) }, text = {
        Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            val picture = cover?.let(data.byId::get)?.takeIf(data::artworkAllowed)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MediaThumbnail(picture, data, app.catalog, prefs, Modifier.size(76.dp), artwork = true)
                Text(picture?.name ?: if (cover != null) localized(R.string.text_choice_retained_unavailable_8fbbc3, "Scelta conservata, non disponibile") else localized(R.string.text_automatic_cover_99768a, "Copertina automatica"), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            }
            GalleryButton(localized(R.string.text_choose_local_image_8460fc, "Scegli immagine locale"), Modifier.fillMaxWidth().testTag("artwork-choose"), enabled = !busy) { picker = true }
            Text(localized(R.string.text_priority_ff5835, "Priorità"), style = MaterialTheme.typography.labelLarge)
            ArtworkPriorityChoice(priority, enabled = !busy) { priority = it }
            Text(localized(R.string.text_track_music_album_folder_settings_a_source_witho_9f4410, "Brano → album musicale → cartella → impostazioni. Una sorgente senza copertina passa alla successiva. I tag originali rimangono intatti."), style = MaterialTheme.typography.bodySmall)
            GalleryButton(localized(R.string.text_restore_automatic_artwork_d9141c, "Ripristina automatiche"), enabled = !busy) { cover = null; priority = null }
            Text(localized(R.string.text_draft_changes_tap_save_to_apply_fd453d, "Modifiche in bozza: premi Salva per applicarle."), style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton({
        val submittedCover = cover; val submittedPriority = priority
        busy = true; error = null
        scope.launch {
            try { withContext(Dispatchers.IO) { app.catalog.setMusicArtwork(target, submittedCover, submittedPriority) }; dismiss() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: localized(R.string.text_artwork_not_saved_69810d, "Copertina non salvata") }
            finally { busy = false }
        }
    }, enabled = !busy, modifier = Modifier.testTag("artwork-save")) { Text(if (busy) localized(R.string.text_saving_c4f57f, "Salvataggio…") else localized(R.string.text_save_c5997e, "Salva")) } },
        dismissButton = { TextButton(dismiss, enabled = !busy) { Text(localized(R.string.text_cancel_18c9d9, "Annulla")) } })
}
