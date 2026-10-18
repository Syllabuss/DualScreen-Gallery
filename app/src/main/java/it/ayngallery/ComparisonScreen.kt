package it.ayngallery

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ComparisonScreen(activity: PanelActivity, data: CatalogSnapshot, prefs: GalleryPreferences,
                     actions: (MediaEntity) -> Unit, task: (suspend () -> Unit) -> Unit) {
    val app = activity.application as GalleryApp
    val store = app.comparison
    val state by store.state.collectAsStateWithLifecycle()
    val names by store.names.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableIntStateOf(0) }
    var save by remember { mutableStateOf(false) }
    var sessions by remember { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var reduction by remember { mutableStateOf<ComparisonState?>(null) }
    var paged by remember { mutableStateOf(false) }
    fun active(pane: ComparisonPane) { store.update(store.state.value.copy(active = pane.id)) }
    DisposableEffect(activity, store, data) {
        val handler: (PadAction, String?) -> Unit = { action, id ->
            val current = store.state.value
            val pane = current.panes.firstOrNull { it.id == current.active }
            val target = pane?.let { current.target(it, app.session.selectedMediaId) }
            if (pane != null && target == id && data.byId[target]?.kind in setOf("IMAGE", "GIF"))
                store.update(current.transform(pane.id, pane.view.fromPad(action)))
        }
        activity.padTransform = handler
        onDispose { if (activity.padTransform === handler) activity.padTransform = null }
    }
    Column(Modifier.fillMaxSize()) {
        FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(state.linked, { store.update(state.copy(linked = !state.linked)) }, label = { Text(localized(R.string.text_linked_zoom_ac5b5d, "Zoom collegato")) })
            GalleryButton(if (state.panes.size == 2) localized(R.string.text_four_panes_407964, "Quattro riquadri") else localized(R.string.text_two_panes_e0eda7, "Due riquadri")) {
                val next = state.resize(if (state.panes.size == 2) 4 else 2)
                if (next == null) reduction = state else store.update(next)
            }
            GalleryButton(localized(R.string.text_save_session_beee36, "Salva sessione")) { name = ""; save = true }
            GalleryButton(localized(R.string.text_sessions_733ff6, "Sessioni"), enabled = names.isNotEmpty()) { sessions = true }
        }
        Text(localized(R.string.text_pinned_items_stay_in_place_while_browsing_arrows_389821, "I pin restano fermi mentre sfogli. Le frecce cambiano solo il riquadro attivo."),
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        BoxWithConstraints(Modifier.weight(1f).padding(8.dp)) {
            val wide = maxWidth >= 640.dp
            val compactFour = state.panes.size == 4 && (!wide || maxHeight < 560.dp)
            SideEffect { paged = compactFour }
            LaunchedEffect(state.active, compactFour) {
                if (compactFour) page = state.panes.indexOfFirst { it.id == state.active }.coerceAtLeast(0) / 2
            }
            val visible = if (compactFour) state.panes.drop(page.coerceIn(0, 1) * 2).take(2) else state.panes
            @Composable fun pane(pane: ComparisonPane, modifier: Modifier) {
                val target = state.target(pane, app.session.selectedMediaId)
                val item = data.byId[target]?.takeIf(data::visible)
                val isActive = pane.id == state.active
                Surface(modifier.onFocusChanged { if (it.hasFocus) active(pane) }, shape = GalleryShape,
                    border = BorderStroke(if (isActive) 3.dp else 1.dp,
                        if (isActive) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))) {
                    Column(Modifier.padding(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f).heightIn(min = 48.dp).clickable { active(pane) }, contentAlignment = Alignment.CenterStart) {
                                Text(if (isActive) localized(R.string.text_active_1_s_08406e, "Attivo · %1\$s", state.panes.indexOf(pane) + 1) else localized(R.string.text_pane_1_s_df7792, "Riquadro %1\$s", state.panes.indexOf(pane) + 1),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (isActive) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            GalleryIconButton(R.drawable.ph_push_pin, if (pane.pinned) localized(R.string.text_follow_selection_4b3585, "Segui selezione") else localized(R.string.text_pin_media_05a7ef, "Fissa media"), Modifier.size(48.dp)) {
                                val current = store.state.value
                                store.update(current.copy(active = pane.id, panes = current.panes.map {
                                    if (it.id != pane.id) it else if (it.pinned) it.copy(pinned = false, mediaId = null, view = ViewTransform())
                                    else it.copy(pinned = true, mediaId = app.session.selectedMediaId, context = app.session.mediaContext)
                                }))
                            }
                            if (item != null) GalleryIconButton(R.drawable.ph_gear_six, localized(R.string.text_pane_options_a83fbf, "Opzioni del riquadro")) { active(pane); actions(item) }
                        }
                        Text(if (pane.pinned) item?.name ?: if (target == null) localized(R.string.text_empty_pin_e56092, "Pin vuoto") else localized(R.string.text_media_unavailable_or_hidden_789e84, "Media indisponibile o nascosto")
                            else localized(R.string.text_following_selection_1_s_97b184, "Segue selezione · %1\$s", item?.name ?: localized(R.string.text_no_media_25195d, "nessun media")), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall)
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            if (item == null) Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center) {
                                GalleryIcon(R.drawable.ph_images, null)
                                Text(if (target == null) localized(R.string.text_choose_media_217616, "Scegli un media") else localized(R.string.text_reference_retained_3c0f78, "Riferimento conservato"), style = MaterialTheme.typography.labelSmall)
                            } else key(pane.id, target) {
                                MediaSurface(activity, item, data, prefs, pane.view,
                                    { value -> store.update(store.state.value.transform(pane.id, value)) },
                                    surfaceKey = pane.id, paneActive = isActive, activate = { active(pane) })
                            }
                        }
                    }
                }
            }
            if (wide) Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                visible.chunked(2).forEach { pair -> Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { item -> pane(item, Modifier.weight(1f).fillMaxHeight()) }
                } }
            } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                visible.forEach { item -> pane(item, Modifier.weight(1f).fillMaxWidth()) }
            }
        }
        if (state.panes.size == 4 && paged) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            GalleryButton(localized(R.string.text_panes_12_0684e7, "Riquadri 1–2")) { store.update(state.copy(active = state.panes[0].id)); page = 0 }
            GalleryButton(localized(R.string.text_panes_34_5d606a, "Riquadri 3–4")) { store.update(state.copy(active = state.panes[2].id)); page = 1 }
        }
        state.panes.find { it.id == state.active }?.let { pane ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                GalleryIconButton(R.drawable.ph_arrow_left, localized(R.string.text_previous_media_in_the_active_pane_fe4ef7, "Media precedente nel riquadro attivo")) { activity.navigateMedia?.invoke(-1) }
                TransformControls(pane.view, { store.update(store.state.value.transform(pane.id, it)) }, Modifier.weight(1f))
                GalleryIconButton(R.drawable.ph_arrow_right, localized(R.string.text_next_media_in_the_active_pane_520c0c, "Media successivo nel riquadro attivo")) { activity.navigateMedia?.invoke(1) }
            }
        }
    }
    if (save) GalleryAlertDialog(onDismissRequest = { save = false }, title = { Text(localized(R.string.text_save_comparison_1dff9e, "Salva il confronto")) },
        text = { OutlinedTextField(name, { name = it.take(100) }, singleLine = true, label = { Text(localized(R.string.text_session_name_856023, "Nome della sessione")) }) },
        confirmButton = { TextButton({
            val title = name.trim()
            if (title in names) app.session.fail(localized(R.string.text_a_session_with_this_name_already_exists_choose_a_e1a297, "Una sessione con questo nome esiste già. Scegli un nome diverso."))
            else { task { store.save(title) }; save = false }
        }, enabled = name.isNotBlank()) { Text(localized(R.string.text_save_c5997e, "Salva")) } }, dismissButton = { TextButton({ save = false }) { Text(localized(R.string.text_cancel_18c9d9, "Annulla")) } })
    if (sessions) GalleryAlertDialog(onDismissRequest = { sessions = false }, title = { Text(localized(R.string.text_saved_sessions_3c65dd, "Sessioni salvate")) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) { names.forEach { title ->
            GalleryButton(title, Modifier.fillMaxWidth()) {
                task {
                    if (!store.load(title)) app.session.fail(localized(R.string.text_session_unreadable_no_pins_replaced_fde88c, "Sessione non leggibile; nessun pin sostituito."))
                    sessions = false
                }
            }
        } }
    }, confirmButton = { TextButton({ sessions = false }) { Text(localized(R.string.text_close_32d407, "Chiudi")) } })
    reduction?.let { frozen -> GalleryAlertDialog(onDismissRequest = { reduction = null }, title = { Text(localized(R.string.text_switch_to_two_panes_43f379, "Passare a due riquadri?")) },
        text = { Text(localized(R.string.text_pins_in_panes_3_and_4_will_be_removed_from_this_f500e1, "I pin nei riquadri 3 e 4 verranno rimossi dalla sessione corrente. Salva la sessione prima di continuare se vuoi conservarli.")) },
        confirmButton = { TextButton({
            if (store.state.value != frozen) app.session.fail(localized(R.string.text_the_session_changed_on_the_other_screen_check_th_b1243d, "La sessione è cambiata sull'altro schermo. Verifica di nuovo i pin."))
            else store.update(frozen.resize(2, discard = true)!!)
            reduction = null
        }) { Text(localized(R.string.text_remove_and_switch_to_two_f5d196, "Rimuovi e passa a due")) } }, dismissButton = { TextButton({ reduction = null }) { Text(localized(R.string.text_cancel_18c9d9, "Annulla")) } }) }
}
