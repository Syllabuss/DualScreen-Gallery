package it.ayngallery

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*

class MusicWidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val app = application as GalleryApp
        app.sounds.play("settings")
        applyGalleryWindowTheme(this, app.preferences.state.value)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
        if (!app.widgets.owns(id)) { finish(); return }
        val stored = app.widgets.settings.get(id)
        val widgetTitle = getString(if (AppWidgetManager.getInstance(this).getAppWidgetInfo(id)?.provider == android.content.ComponentName(this, MusicMinimalWidget::class.java)) R.string.widget_minimal_name else R.string.widget_full_name)
        val storedEffect = if (stored.effect == "DISC" && !stored.discHole) "CIRCLE" else stored.effect
        setContent {
            val prefs by app.preferences.state.collectAsStateWithLifecycle()
            val data by app.catalog.snapshot.collectAsStateWithLifecycle()
            var initialTrack by rememberSaveable { mutableStateOf(stored.initialTrack) }
            var pickingTrack by rememberSaveable { mutableStateOf(false) }
            var chooseTrack by rememberSaveable { mutableStateOf(stored.initialTrack != null) }
            var effect by rememberSaveable { mutableStateOf(storedEffect) }
            var saving by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf<String?>(null) }
            fun save() {
                saving = true
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { app.widgets.settings.save(id, stored.copy(background = "SYSTEM", color = null, playlist = null, effect = effect, discHole = effect == "DISC", barsColor = null, initialTrack = if (chooseTrack) initialTrack else null)) }
                        app.widgets.refresh()
                        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)); finish()
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { error = localized(R.string.text_configuration_not_saved_try_again_0ef8dd, "Configurazione non salvata. Riprova."); saving = false }
                }
            }
            GalleryDesign(prefs) { Surface(Modifier.fillMaxSize()) {
                val exit = WidgetExitConfirmation(
                    effect != storedEffect || chooseTrack != (stored.initialTrack != null) || (if (chooseTrack) initialTrack else null) != stored.initialTrack,
                    saving, { finish() })
                Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GalleryIconButton(R.drawable.ph_arrow_left, localized(R.string.text_cancel_18c9d9, "Annulla"), enabled = !saving) { exit() }
                        Text(widgetTitle, Modifier.weight(1f).padding(horizontal = 12.dp), style = MaterialTheme.typography.titleLarge)
                        GalleryIconButton(R.drawable.ph_floppy_disk, localized(R.string.text_save_c5997e, "Salva"), enabled = !saving && (!chooseTrack || data.byId[initialTrack]?.let(data::usable) == true)) { save() }
                    }
                    LazyColumn(Modifier.weight(1f).testTag("widget-config-list"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        item {
                            Text(localized(R.string.widget_playback_effect, "Effetto durante la riproduzione"), style = MaterialTheme.typography.titleMedium)
                            listOf("NONE" to R.string.widget_effect_none, "DISC" to R.string.widget_effect_disc, "CIRCLE" to R.string.widget_effect_circle, "COIN" to R.string.widget_effect_coin, "PULSE" to R.string.widget_effect_pulse, "BARS" to R.string.widget_effect_bars).forEach { (key, label) ->
                                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(effect == key, role = Role.RadioButton) {
                                    effect = key
                                }.testTag("widget-effect:$key"), verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(effect == key, null); Text(if (key == "DISC") "CD" else androidx.compose.ui.res.stringResource(label), Modifier.weight(1f))
                                }
                            }
                        }
                        item {
                            Text(localized(R.string.widget_start_playlist, "Playlist iniziale"), style = MaterialTheme.typography.titleMedium)
                            listOf(false to localized(R.string.widget_last_listened, "Ultimo ascolto"), true to localized(R.string.widget_choose_track, "Scegli brano")).forEach { (choice, label) ->
                                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(chooseTrack == choice, role = Role.RadioButton) { chooseTrack = choice; if (choice) pickingTrack = true }
                                    .testTag("widget-start:$choice"), verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(chooseTrack == choice, null); Text(label, Modifier.weight(1f))
                                }
                            }
                        }
                        if (chooseTrack) item {
                            OutlinedButton({ pickingTrack = true }, Modifier.fillMaxWidth().testTag("widget-selected-track")) {
                                Text(data.byId[initialTrack]?.let { it.title ?: it.name } ?: localized(R.string.widget_choose_track, "Scegli brano"), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }
                        }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                if (pickingTrack) WidgetTrackPicker(data, app.catalog, prefs, { pickingTrack = false }) { track ->
                    initialTrack = track.id; chooseTrack = true; pickingTrack = false
                }

            } }
        }
    }
}

@Composable
private fun WidgetTrackPicker(data: CatalogSnapshot, catalog: Catalog, prefs: GalleryPreferences, dismiss: () -> Unit, choose: (MediaEntity) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var grid by rememberSaveable { mutableStateOf(false) }
    val tracks = remember(data, query) {
        val words = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        data.visibleMedia.filter { track -> track.kind == "AUDIO" && data.usable(track) &&
            words.all { word -> listOfNotNull(track.title, track.name, track.artist, track.musicAlbum).any { it.contains(word, ignoreCase = true) } }
        }.sortedWith(compareBy(MediaNames.natural) { it.title ?: it.name })
    }
    val height = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp * .88f
    GalleryAlertDialog(onDismissRequest = dismiss, modifier = Modifier.widthIn(max = 800.dp).fillMaxWidth(.94f),
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(localized(R.string.widget_choose_track, "Scegli brano")) },
        text = {
            Column(Modifier.fillMaxWidth().height(height * .64f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(query, { query = it }, Modifier.weight(1f).testTag("widget-track-search"), singleLine = true,
                        placeholder = { Text(localized(R.string.text_search_collection_053f46, "Cerca nella raccolta")) })
                    GalleryIconButton(if (grid) R.drawable.ph_list else R.drawable.ph_squares_four, if (grid) localized(R.string.text_list_view_8cb747, "Vista elenco") else localized(R.string.text_grid_view_c4886b, "Vista griglia")) { grid = !grid }
                }
                androidx.compose.foundation.lazy.grid.LazyVerticalGrid(androidx.compose.foundation.lazy.grid.GridCells.Adaptive(if (grid) 88.dp else androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp),
                    Modifier.fillMaxWidth().weight(1f).testTag("widget-track-results"), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(tracks, key = { it.id }) { track ->
                        OutlinedCard(onClick = { choose(track) }, modifier = Modifier.fillMaxWidth().testTag("widget-track:${track.id}")) {
                            Column(Modifier.padding(if (grid) 4.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (grid) MediaThumbnail(track, data, catalog, prefs, Modifier.fillMaxWidth().aspectRatio(1f), artwork = true)
                                Text(track.title ?: track.name, maxLines = if (grid) 2 else 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                track.artist?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                            }
                        }
                    }
                }
                if (tracks.isEmpty()) Text(localized(R.string.text_no_results_0af987, "Nessun risultato"))
            }
        }, confirmButton = {})
}
