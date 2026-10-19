package it.ayngallery

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun PreferencesScreen(app: GalleryApp, prefs: GalleryPreferences, task: (suspend () -> Unit) -> Unit,
    transfer: () -> Unit = {}, maintenance: () -> Unit = {}, touch: () -> Unit = {}, section: String = "ALL",
    initialDraft: GalleryPreferences? = null, onDraft: (GalleryPreferences) -> Unit = {}, subsection: (String) -> Unit = {}, controls: () -> Unit = {}) {
    var draft by remember(section) { mutableStateOf(initialDraft ?: prefs) }
    var savedIcon by remember { mutableStateOf(LauncherIcon.current(app)) }
    var iconDraft by rememberSaveable(section) { mutableStateOf(savedIcon) }
    val iconDirty = iconDraft != savedIcon
    var pendingSubsection by remember { mutableStateOf<String?>(null) }
    fun openSubsection(key: String) { if (draft != prefs || iconDirty) pendingSubsection = key else subsection(key) }
    pendingSubsection?.let { next -> SettingsDiscardDialog(dismiss = { pendingSubsection = null }, discard = { draft = prefs; iconDraft = savedIcon; onDraft(prefs); pendingSubsection = null; subsection(next) }) }
    val toolsSection = app.session.settingsToolsSection
    var customOpen by rememberSaveable(section) { mutableStateOf(false) }
    BackHandler(enabled = section == "THEME" && customOpen) { customOpen = false }
    LaunchedEffect(draft) { onDraft(draft) }
    val committed = prefs.copy(pad = draft.pad, touch = draft.touch)
    var saved by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var neonExpanded by rememberSaveable { mutableStateOf(false) }
    var pastelExpanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        if (LocalSettingsSave.current == null || customOpen || section == "ALL") Text(when { customOpen && section == "THEME" -> localized(R.string.text_custom_792566, "Personalizzato"); else -> when (section) { "THEME" -> localized(R.string.text_appearance_f9ce8f, "Tema"); "INTERFACE" -> localized(R.string.text_interface_a86468, "Interfaccia"); "VIEWER" -> localized(R.string.text_preview_e9bcae, "Anteprima"); "PLAYBACK" -> localized(R.string.text_playback_2b0fe5, "Riproduzione"); else -> localized(R.string.text_appearance_and_behavior_bbd831, "Aspetto e comportamento") } }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

                    }
                    SettingsSaveAction(enabled = !saving && (draft != committed || iconDirty), dirty = draft != committed || iconDirty, busy = saving, discard = { draft = committed; iconDraft = savedIcon; onDraft(committed) }) {
                        val submitted = draft
                        val submittedIcon = iconDraft
                        saved = false; saving = true
                        task {
                            try { app.preferences.update { submitted.copy(pad = it.pad, touch = it.touch) }; if (submittedIcon != savedIcon) LauncherIcon.select(app, submittedIcon); kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { savedIcon = submittedIcon; saved = true } }
                            finally { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.Main) { saving = false } }
                        }
                    }
                }

            }
        }
        BoxWithConstraints(Modifier.weight(1f)) {
            val columns = if (maxWidth >= 520.dp) 4 else if (maxWidth >= 400.dp) 3 else 2
            val settingsListState = remember(section) { androidx.compose.foundation.lazy.LazyListState() }
            var previousBackgroundMode by remember(section) { mutableStateOf(draft.backgroundMode) }
            LaunchedEffect(draft.backgroundMode) {
                val changed = previousBackgroundMode != draft.backgroundMode
                previousBackgroundMode = draft.backgroundMode
                if (changed && section == "THEME" && draft.backgroundMode != "PLAIN") {
                    // Wait for the expanded controls to participate in the lazy list layout.
                    withFrameNanos { }
                    settingsListState.animateScrollToItem((settingsListState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0), scrollOffset = settingsListState.layoutInfo.viewportSize.height)
                }
            }
            if (section == "PLAYBACK") PlaybackPreferencesPage(app, draft) { draft = it }
            else LazyColumn(state = settingsListState, modifier = Modifier.testTag("settings-list"), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = if (section == "PLAYBACK") 8.dp else 16.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(if (section == "PLAYBACK") 4.dp else 12.dp)) {
                if (section in setOf("ALL", "THEME") && customOpen) {
                    item(key = "custom-editor") { CustomThemeEditor(draft, change = { draft = it }) }
                } else if (section in setOf("ALL", "THEME")) {
                val denseThemeColumns = 4
                item(key = "family:CUSTOM") { Text(localized(R.string.text_custom_792566, "Personalizzato"), Modifier.testTag("theme-family-custom"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
                item(key = "custom-theme") {
                    val colors = (draft.customTheme ?: CustomTheme.from(GalleryThemes.get(draft))).values()
                    OutlinedCard(onClick = {
                        if (draft.themeId != GalleryThemes.CUSTOM) draft = draft.copy(themeId = GalleryThemes.CUSTOM, customTheme = CustomTheme.from(GalleryThemes.get(draft)))
                        customOpen = true
                    }, modifier = Modifier.fillMaxWidth().testTag("custom-theme-open"), shape = GalleryShape) {
                        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Column(Modifier.weight(1f)) { Text(localized(R.string.text_customize_theme_5d7c82, "Personalizza i colori"), style = MaterialTheme.typography.titleMedium); Text(localized(R.string.text_choose_colors_with_a_color_wheel_45c992, "Scegli i colori uno alla volta"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) { colors.take(6).forEach { Box(Modifier.size(18.dp).background(androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(it)), RoundedCornerShape(9.dp))) } }
                        }
                    }
                }
                item(key = "family:AYN") { Text("AYN", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
                items(GalleryThemes.all.filter { it.family == "AYN" }.sortedBy { listOf("ayn_rainbow", GalleryThemes.WHITE, GalleryThemes.BLACK, "ayn_purple").indexOf(it.id) }.chunked(denseThemeColumns), key = { "themes:${it.first().id}" }) { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { theme -> ThemeChoice(theme, draft.themeId == theme.id, prefs.themeId == theme.id, Modifier.weight(1f), compact = true) {
                            draft = draft.copy(themeId = theme.id)
                        } }
                        repeat(denseThemeColumns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                item(key = "family:PASTEL") {
                    ThemeFamilyAccordion("PASTEL", pastelExpanded,
                        Modifier.testTag("theme-accordion:PASTEL")) { pastelExpanded = !pastelExpanded }
                }
                if (pastelExpanded) items(GalleryThemes.all.filter { it.family == "PASTEL" }.chunked(denseThemeColumns), key = { "themes:${it.first().id}" }) { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { theme -> ThemeChoice(theme, draft.themeId == theme.id, prefs.themeId == theme.id, Modifier.weight(1f), compact = true) {
                            draft = draft.copy(themeId = theme.id)
                        } }
                        repeat(denseThemeColumns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                item(key = "family:DARK") {
                    ThemeFamilyAccordion("NEON", neonExpanded,
                        Modifier.testTag("theme-accordion:NEON")) { neonExpanded = !neonExpanded }
                }
                if (neonExpanded) items(GalleryThemes.all.filter { it.family == "DARK" }.chunked(denseThemeColumns), key = { "themes:${it.first().id}" }) { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { theme -> ThemeChoice(theme, draft.themeId == theme.id, prefs.themeId == theme.id, Modifier.weight(1f), compact = true) {
                            draft = draft.copy(themeId = theme.id)
                        } }
                        repeat(denseThemeColumns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                }
                if (section in setOf("ALL", "THEME")) {
                    item(key = "gallery-background-divider") { HorizontalDivider() }
                    item(key = "gallery-background") { GalleryBackgroundSettings(app, draft, change = { draft = it }) }
                }
                if (section == "ALL") {
                item { HorizontalDivider(); Text(localized(R.string.text_behavior_03b2ee, "Comportamento"), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleLarge) }
                item { GalleryButton(localized(R.string.text_controls_and_bindings_bc7885, "Controlli e associazioni"), Modifier.fillMaxWidth().testTag("settings-controls"), onClick = controls) }
                item { GalleryButton(localized(R.string.text_touch_gestures_and_zones_aa170e, "Gesti touch e zone"), Modifier.fillMaxWidth().testTag("settings-touch"), onClick = touch) }
                item { GalleryButton(localized(R.string.text_export_import_settings_5d89ec, "Esporta / importa impostazioni"), Modifier.fillMaxWidth().testTag("settings-transfer"), onClick = transfer) }
                item { GalleryButton(localized(R.string.text_clear_cache_591e39, "Pulisci cache"), Modifier.fillMaxWidth().testTag("settings-maintenance"), onClick = maintenance) }
                }
                if (section in setOf("ALL", "INTERFACE")) {
                item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (key in listOf("LANGUAGE", "THEME")) {
                        val focus = remember { androidx.compose.ui.focus.FocusRequester() }
                        LaunchedEffect(Unit) {
                            if (app.session.settingsReturnControl == key) {
                                repeat(2) { androidx.compose.runtime.withFrameNanos { } }
                                focus.requestFocus(); app.session.settingsReturnControl = null
                            }
                        }
                        GalleryButton(localized(if (key == "LANGUAGE") R.string.language_title else R.string.text_appearance_f9ce8f, if (key == "LANGUAGE") "Lingua" else "Tema"),
                            Modifier.weight(1f).focusRequester(focus).testTag("settings-category:$key"), icon = if (key == "LANGUAGE") R.drawable.ic_globe else R.drawable.ph_images,
                            onClick = { openSubsection(key) })
                    }
                } }
                item { LauncherIconChoice(iconDraft) { iconDraft = it } }
                item { GallerySettingSwitch(localized(R.string.tv_enabled, "Abilita TV online"), draft.tvEnabled, Modifier.testTag("tv-enabled")) { draft = draft.copy(tvEnabled = it) } }
                if (draft.tvEnabled) {
                    item { DependentSetting(true) { GallerySettingSwitch(localized(R.string.tv_navbar, "Mostra TV nella barra di navigazione"), draft.tvInNavbar, Modifier.testTag("tv-navbar")) { draft = draft.copy(tvInNavbar = it, navbarOrder = NavbarOrder.withTv(draft.navbarOrder)) } } }
                    item { DependentSetting(true) { GallerySettingSwitch(localized(R.string.tv_favorites, "Mostra canali TV nei Preferiti"), draft.tvInMediaFavorites) { draft = draft.copy(tvInMediaFavorites = it) } } }
                }
                item { NavbarCategoryEditor(draft) { draft = it } }
                item { HorizontalDivider(Modifier.padding(vertical = 8.dp)); Text(localized(R.string.text_gallery_and_preview_36bf93, "Galleria e anteprima"), style = MaterialTheme.typography.titleMedium) }
                item { GallerySettingSwitch(localized(R.string.text_navigation_bar_labels_63fe86, "Etichette nella barra di navigazione"), draft.navbarLabels, Modifier.testTag("navbar-labels")) { draft = draft.copy(navbarLabels = it) } }
                item { GallerySettingSwitch(localized(R.string.text_show_file_names_557e96, "Mostra nomi dei file"), draft.showMediaNames) { draft = draft.copy(showMediaNames = it) } }
                item { GallerySettingSwitch(localized(R.string.direct_delete_option, "Mostra «Elimina direttamente»"), draft.directDeleteEnabled) { draft = draft.copy(directDeleteEnabled = it) } }
                item { GallerySettingSwitch(localized(R.string.album_hierarchy, "Sfoglia album per cartelle"), draft.nestedAlbums) { draft = draft.copy(nestedAlbums = it) }
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GalleryIcon(R.drawable.ph_info, null, Modifier.size(18.dp))
                        Text(localized(R.string.album_hierarchy_description, "Disattivato: le cartelle che contengono solo altri album vengono saltate, così raggiungi subito i media. Attivato: apri le cartelle una alla volta, mantenendo la struttura delle roots. I file e le cartelle non vengono modificati."), style = MaterialTheme.typography.bodySmall)
                    }
                }
                item { Text(localized(R.string.tools_hint, "Scegli i pulsanti da mostrare. Le scorciatoie del controller restano attive."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                item { GallerySettingSwitch(localized(R.string.upper_status_bar, "Barra di stato sullo schermo superiore"), draft.showUpperStatusBar,
                    Modifier.testTag("upper-status-bar")) { draft = draft.copy(showUpperStatusBar = it) } }
                item { Text(localized(R.string.settings_visible_buttons, "Pulsanti visibili"), style = MaterialTheme.typography.titleMedium) }
                item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InterfaceTools.sections.forEach { key -> FilterChip(toolsSection == key, { app.session.settingsToolsSection = key }, label = { Text(MainTabs.first { it.key == key }.title) }, modifier = Modifier.testTag("tools-section:$key")) }
                } }
                item {
                    SectionToolsSettings(toolsSection, draft) { draft = it }
                }
                }
                if (section in setOf("ALL", "INTERFACE", "VIEWER")) {
                item { Spacer(Modifier.height(8.dp)); HorizontalDivider(); Spacer(Modifier.height(12.dp)); Text(localized(R.string.text_preview_e9bcae, "Anteprima"), style = MaterialTheme.typography.titleMedium) }
                item {
                    Text(localized(R.string.album_shadows_title, "Ombre della preview album"), style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("TOP" to R.string.album_shadow_top, "BOTTOM" to R.string.album_shadow_bottom, "SIDES" to R.string.album_shadow_sides,
                            "ALL" to R.string.album_shadow_all, "VERTICAL" to R.string.album_shadow_vertical, "NONE" to R.string.album_shadow_none).forEach { (value, label) ->
                            FilterChip(draft.albumShadows == value, { draft = draft.copy(albumShadows = value) }, label = { Text(androidx.compose.ui.res.stringResource(label)) })
                        }
                    }
                }
                item {
                    GallerySettingSwitch(localized(R.string.animated_album_covers, "Copertine GIF animate degli album"), draft.animatedAlbumCovers) { draft = draft.copy(animatedAlbumCovers = it, allAnimatedAlbumCovers = it) }
                    if (draft.animatedAlbumCovers) {
                        Text(localized(R.string.text_simultaneous_animated_gifs_1_s_2a1e80, "GIF animate contemporaneamente: %1\$s", draft.albumCoverLimit))
                        GallerySlider(draft.albumCoverLimit.toFloat(), { draft = draft.copy(albumCoverLimit = it.toInt().coerceIn(1, 40)) }, valueRange = 1f..40f, steps = 38, modifier = Modifier.testTag("album-gif-limit"))
                    }
                    GallerySettingSwitch(localized(R.string.album_studio_title, "Cornice studio"), draft.albumStudio) { draft = draft.copy(albumStudio = it) }
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GalleryIcon(R.drawable.ph_info, null, Modifier.size(18.dp))
                        Text(localized(R.string.album_studio_description, "Una scacchiera discreta sui bordi della preview album, sopra le ombre. Sfuma rapidamente verso il centro, lasciando libera l’immagine."), style = MaterialTheme.typography.bodySmall)
                    }
                }
                item { GallerySettingSwitch(localized(R.string.text_preview_arrows_a1fa38, "Frecce nell'anteprima"), draft.viewerArrows) { draft = draft.copy(viewerArrows = it) } }
                item { GallerySettingSwitch(localized(R.string.text_preview_zoom_buttons_7873e1, "Pulsanti zoom nell'anteprima"), draft.viewerZoom) { draft = draft.copy(viewerZoom = it) } }
                item { GallerySettingSwitch(localized(R.string.text_navigator_when_the_photo_is_zoomed_in_9ddb60, "Navigatore quando la foto è ingrandita"), draft.photoNavigator) { draft = draft.copy(photoNavigator = it) } }
                item {
                    GallerySettingSwitch(localized(R.string.controller_sorting, "Calendario e ordinamento con il controller"), draft.controllerSorting, Modifier.testTag("controller-sorting")) { draft = draft.copy(controllerSorting = it) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GalleryIcon(R.drawable.ph_info, null, Modifier.size(18.dp))
                        Text(localized(R.string.controller_sorting_description, "Permette di selezionare l’icona del calendario, il criterio di ordinamento e la freccia di inversione con stick o D-pad e confermare con A. Se disattivato, questi comandi si usano solo con il touch."), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    }
                }
                }
                if (section in setOf("ALL", "INTERFACE", "VIEWER")) {
                item {
                    GallerySettingSwitch(localized(R.string.action_menus_below, "Azioni sullo schermo di navigazione"), draft.actionMenusBelow) { draft = draft.copy(actionMenusBelow = it) }
                    GallerySettingSwitch(localized(R.string.grid_preview_motion, "Scorrimento dell’anteprima dalla griglia"), draft.gridPreviewMotion, Modifier.testTag("grid-preview-motion")) { draft = draft.copy(gridPreviewMotion = it) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GalleryIcon(R.drawable.ph_info, null, Modifier.size(18.dp))
                        Text(localized(R.string.grid_preview_motion_description, "Nella griglia, l’anteprima scorre nella direzione in cui navighi. Disattiva per usare una dissolvenza tra i media."), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    }
                }

                item { GallerySettingSwitch(localized(R.string.hide_gif_badge, "Nascondi etichetta GIF"), draft.hideGifBadge) { draft = draft.copy(hideGifBadge = it) } }
                }
                if (section == "ALL") {
                    item { GallerySettingSwitch(localized(R.string.text_reduce_animations_0fdf71, "Riduci le animazioni"), draft.reducedMotion) { draft = draft.copy(reducedMotion = it) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { GalleryIcon(R.drawable.ph_info, null, Modifier.size(18.dp)); Text(localized(R.string.reduced_motion_help, ""), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall) }
                    }
                    listOf("IMAGE", "VIDEO", "MUSIC").forEach { category ->
                        item { PlaybackMediaSettings(draft, category) { draft = it } }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaybackPreferencesPage(app: GalleryApp, draft: GalleryPreferences, change: (GalleryPreferences) -> Unit) {
    val scroll = remember { androidx.compose.foundation.ScrollState(0) }
    val first = remember { androidx.compose.ui.focus.FocusRequester() }
    SettingsScrollEnd({ scroll.canScrollForward }) { scroll.animateScrollTo(scroll.maxValue) }
    LaunchedEffect(Unit) { repeat(2) { androidx.compose.runtime.withFrameNanos { } }; scroll.scrollTo(0); first.requestFocus() }
    LaunchedEffect(app.session.playbackSettingsCategory) { scroll.scrollTo(0) }
    Column(Modifier.fillMaxSize().verticalScroll(scroll).testTag("settings-list").padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GallerySettingSwitch(localized(R.string.text_reduce_animations_0fdf71, "Riduci le animazioni"), draft.reducedMotion, Modifier.focusRequester(first).testTag("playback-reduced-motion")) { change(draft.copy(reducedMotion = it)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GalleryIcon(R.drawable.ph_info, null, Modifier.size(18.dp))
            Text(localized(R.string.reduced_motion_help, ""), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        }
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("IMAGE" to R.string.playback_images, "VIDEO" to R.string.video_stick_category, "MUSIC" to R.string.text_music_012df9).forEach { (key, title) ->
                    val active = app.session.playbackSettingsCategory == key
                    Surface(onClick = { app.session.playbackSettingsCategory = key },
                        modifier = Modifier.weight(1f).testTag("playback-tab:$key"),
                        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
                        border = BorderStroke(1.dp, if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                        color = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface) {
                        Text(androidx.compose.ui.res.stringResource(title), Modifier.padding(12.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            }
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary), color = MaterialTheme.colorScheme.surface) {
                androidx.compose.animation.AnimatedContent(app.session.playbackSettingsCategory, modifier = Modifier.fillMaxWidth(),
                    transitionSpec = { androidx.compose.animation.fadeIn(tween(if (draft.reducedMotion) 0 else 180)) togetherWith androidx.compose.animation.fadeOut(tween(if (draft.reducedMotion) 0 else 120)) }, label = "playback category") { category ->
                    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 28.dp)) {
                        PlaybackMediaSettings(draft, category, change)
                    }
                }
            }
        }
    }
}

@Composable
internal fun PlaybackChoice(label: String, value: Int, choices: Map<Int, String>, tag: String, change: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).onFocusChanged { focused = it.isFocused }
        .border(if (focused) 3.dp else 0.dp, if (focused) GalleryFocus else androidx.compose.ui.graphics.Color.Transparent, shape)
        .background(if (focused) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent, shape)
        .clip(shape).clickable(role = Role.Button) { expanded = true }.testTag(tag).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Box {
            Text(choices.getValue(value), Modifier.padding(horizontal = 4.dp, vertical = 12.dp), color = MaterialTheme.colorScheme.primary)
            GalleryDropdownMenu(expanded, { expanded = false }) {
                choices.forEach { (id, title) -> DropdownMenuItem(text = { Text(title) }, modifier = Modifier.testTag("$tag:$id"), onClick = { expanded = false; change(id) }) }
            }
        }
    }
}

@Composable
private fun ThemeFamilyAccordion(family: String, expanded: Boolean, modifier: Modifier = Modifier, toggle: () -> Unit) {
    OutlinedCard(onClick = toggle, modifier = modifier.fillMaxWidth(), shape = GalleryShape) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            val neon = family == "NEON"
            Text(localized(if (neon) R.string.text_night_and_neon_f6d332 else R.string.text_pastel_f90857,
                if (neon) "Notturni e neon" else "Pastello"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            GalleryIcon(if (expanded) R.drawable.ph_minus_circle else R.drawable.ph_plus_circle, null)
        }
    }
}

@Composable
private fun NavbarCategoryEditor(draft: GalleryPreferences, change: (GalleryPreferences) -> Unit) {
    val positions = remember { mutableMapOf<String, androidx.compose.ui.layout.LayoutCoordinates>() }
    val currentDraft by rememberUpdatedState(draft)
    val onChange by rememberUpdatedState(change)
    var dragging by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(localized(R.string.navbar_categories_title, "Attiva e riorganizza"), style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth().testTag("navbar-category-editor"), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NavbarOrder.editable(draft).forEach { id ->
                val tab = MainTabs.first { it.key == id }
                val active = id !in draft.disabledCategories
                Column(Modifier.weight(1f)) {
                Surface(
                    modifier = Modifier.fillMaxWidth().onGloballyPositioned { positions[id] = it }
                        .graphicsLayer {
                            alpha = if (id == dragging) .66f else if (active) 1f else .42f
                            scaleX = if (id == dragging) 1.08f else 1f
                            scaleY = if (id == dragging) 1.08f else 1f
                        }
                        .pointerInput(id) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val held = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                                val origin = positions[id]?.takeIf { it.isAttached } ?: return@awaitEachGesture
                                dragging = id
                                var point = origin.localToRoot(held.position)
                                drag(held.id) { change ->
                                    point += change.positionChange()
                                    val target = positions.entries.firstOrNull { (other, coordinates) ->
                                        other != id && coordinates.isAttached && coordinates.boundsInRoot().contains(point)
                                    }?.key
                                    if (target != null) {
                                        val order = currentDraft.navbarOrder
                                        val from = order.indexOf(id)
                                        val to = order.indexOf(target)
                                        if (from >= 0 && to >= 0 && from != to) {
                                            val moved = order.toMutableList().apply { add(to, removeAt(from)) }
                                            onChange(currentDraft.copy(navbarOrder = moved))
                                        }
                                    }
                                    change.consume()
                                }
                                dragging = null
                            }
                        }
                        .toggleable(value = active, role = Role.Checkbox, onValueChange = { enabled ->
                            if (enabled || id == "TV" || NavbarOrder.DEFAULT.count { it !in currentDraft.disabledCategories } > 1)
                                onChange(currentDraft.copy(disabledCategories = if (enabled) currentDraft.disabledCategories - id else currentDraft.disabledCategories + id))
                        })
                        .testTag("category-toggle:$id"),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.5.dp, if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Box(Modifier.fillMaxWidth().height(52.dp), contentAlignment = Alignment.Center) {
                        CompositionLocalProvider(LocalContentColor provides if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) {
                            GalleryIcon(tab.icon, tab.title, Modifier.size(23.dp))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf(-1 to R.drawable.ph_caret_left, 1 to R.drawable.ph_caret_right).forEach { (step, icon) ->
                        val destination = currentDraft.navbarOrder.indexOf(id) + step
                        IconButton(onClick = {
                            val order = currentDraft.navbarOrder.toMutableList()
                            val from = order.indexOf(id)
                            val to = from + step
                            if (to in order.indices) { order.add(to, order.removeAt(from)); onChange(currentDraft.copy(navbarOrder = order)) }
                        }, enabled = destination in currentDraft.navbarOrder.indices, modifier = Modifier.size(40.dp).testTag("category-move:$id:$step")) {
                            GalleryIcon(icon, localized(if (step < 0) R.string.text_move_left_4e3ed5 else R.string.text_move_right_1d91fd, if (step < 0) "Sposta a sinistra" else "Sposta a destra"), Modifier.size(18.dp))
                        }
                    }
                }
                }
            }
        }
        var defaultMenu by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(localized(R.string.default_section, "Sezione iniziale"), Modifier.weight(1f))
            Box {
                OutlinedButton({ defaultMenu = true }, Modifier.testTag("default-section")) {
                    Text(MainTabs.first { it.key == preferredGalleryTab(draft.disabledCategories, draft.defaultSection) }.title)
                }
                GalleryDropdownMenu(defaultMenu, { defaultMenu = false }) {
                    MainTabs.filter { it.key in NavbarOrder.DEFAULT && it.key !in draft.disabledCategories }.forEach { tab ->
                        DropdownMenuItem(text = { Text(tab.title) }, leadingIcon = { GalleryIcon(tab.icon, null) }, modifier = Modifier.testTag("default-section:${tab.key}"), onClick = { onChange(currentDraft.copy(defaultSection = tab.key)); defaultMenu = false })
                    }
                }
            }
        }
        Text(localized(R.string.navbar_categories_help, "Tocca un’icona per attivarla o nasconderla. Tienila premuta e trascinala per cambiare l’ordine."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PlaybackMediaSettings(draft: GalleryPreferences, category: String, change: (GalleryPreferences) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("playback-options:$category"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (category in setOf("ALL", "IMAGE")) {
            ImageBackdropSettings(draft, change)
            GallerySettingSwitch(localized(R.string.text_gif_frame_count_dc95bf, "Numero di fotogrammi GIF"), draft.gifFrameCount) { change(draft.copy(gifFrameCount = it)) }
            DependentSetting(draft.gifFrameCount) { GallerySettingSwitch(localized(R.string.gif_current_frame, "Contatore del fotogramma corrente"), draft.gifFrameCounter) { change(draft.copy(gifFrameCounter = it)) } }
            GallerySettingSwitch(localized(R.string.gif_frame_selector, "Selettore dei fotogrammi"), draft.gifFrameSelector) { change(draft.copy(gifFrameSelector = it)) }

            GallerySettingSwitch(localized(R.string.preview_nearest, "Pixel netti (Nearest-neighbor)"), draft.previewNearest, Modifier.testTag("playback-nearest")) { change(draft.copy(previewNearest = it)) }
                    GallerySettingSwitch(localized(R.string.image_preview_name, "Nome del file nell’anteprima"), draft.imagePreviewName, Modifier.testTag("image-preview-name")) { change(draft.copy(imagePreviewName = it)) }
                    GallerySettingSwitch(localized(R.string.preview_pin_setting, "Pulsante per fissare l’anteprima"), draft.previewPinEnabled) { change(draft.copy(previewPinEnabled = it)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { GalleryIcon(R.drawable.ph_info, null); Text(localized(R.string.preview_pin_description, "Fissa una foto o GIF mentre sfogli gli altri media."), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall) }
                    GallerySettingSwitch(localized(R.string.preview_compare_setting, "Confronti in finestre mobili"), draft.comparisonEnabled) { change(draft.copy(comparisonEnabled = it)) }
                    GallerySettingSwitch(localized(R.string.image_overlay_auto_hide, "Nascondi automaticamente i controlli"), draft.imageOverlayAutoHide, Modifier.testTag("image-overlay-auto-hide")) { change(draft.copy(imageOverlayAutoHide = it)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GalleryIcon(R.drawable.ph_info, null, Modifier.size(18.dp))
                        Text(localized(R.string.image_overlay_auto_hide_description, "Nasconde i controlli dell’anteprima dopo 5 secondi senza interazioni. Tocca la foto o premi A sul media corrente per mostrarli o nasconderli; cambiare foto non riavvia il timer."), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    }
                Column {
                    Text(localized(R.string.text_gifs_in_the_grid_c5be9f, "GIF nella griglia"), style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("OFF" to localized(R.string.text_off_de3be8, "Disattivate"), "SELECTED" to localized(R.string.text_selected_only_07ada4, "Solo selezionata"), "VISIBLE" to localized(R.string.text_multiple_visible_gifs_cd9a7d, "Più GIF visibili")).forEach { (mode, label) ->
                            FilterChip(draft.gifMode == mode, { change(draft.copy(gifMode = mode)) }, label = { Text(label) })
                        }
                    }
                }

                if (draft.gifMode == "VISIBLE") Column {
                    GallerySettingSwitch(localized(R.string.gif_all_visible, "Tutte le GIF visibili"), draft.gifLimit == 0, Modifier.testTag("gif-all-visible")) { change(draft.copy(gifLimit = if (it) 0 else 4)) }
                    if (draft.gifLimit > 0) {
                        Text(localized(R.string.text_simultaneous_animated_gifs_1_s_2a1e80, "GIF animate contemporaneamente: %1\$s", draft.gifLimit))
                        val limits = listOf(4, 6, 12, 20, 24, 35, 40)
                        GallerySlider(limits.indexOfFirst { it >= draft.gifLimit }.coerceAtLeast(0).toFloat(),
                            { change(draft.copy(gifLimit = limits[it.toInt().coerceIn(limits.indices)])) },
                            valueRange = 0f..6f, steps = 5, modifier = Modifier.testTag("gif-limit"))
                    }
                }

        }
        if (category in setOf("ALL", "VIDEO")) {
                Text(localized(R.string.video_stick_category, "Video"), style = MaterialTheme.typography.titleMedium)
                GallerySettingSwitch(localized(R.string.video_confirm_replace, "Conferma prima di sostituire un video in riproduzione"), draft.confirmVideoReplacement,
                    Modifier.testTag("video-confirm-replacement")) { change(draft.copy(confirmVideoReplacement = it)) }
                GallerySettingSwitch(localized(R.string.playback_sleep_continue, "Continua a console chiusa"), draft.videoDuringSleep, Modifier.testTag("video-during-sleep")) { change(draft.copy(videoDuringSleep = it)) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { GalleryIcon(R.drawable.ph_info, null, Modifier.size(18.dp)); Text(localized(R.string.video_sleep_help, ""), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall) }
                GallerySettingSwitch(localized(R.string.video_keep_playing, "Continua in finestra"), draft.videoKeepPlaying) { change(draft.copy(videoKeepPlaying = it)) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { GalleryIcon(R.drawable.ph_info, null); Text(localized(R.string.video_keep_playing_description, "Il video continua in finestra su Musica, FTP e impostazioni, e rientra nell’anteprima tornando alla galleria."), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall) }
                GallerySettingSwitch(localized(R.string.video_mix_audio, "Audio condiviso con altre app"), draft.videoMixAudio, Modifier.testTag("video-mix-audio")) {
                    change(draft.copy(videoMixAudio = it))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GalleryIcon(R.drawable.ph_info, null, Modifier.size(18.dp))
                    Text(localized(R.string.video_mix_audio_description, "Il video continua anche quando la Home o un’altra app riproduce audio: sentirai entrambi. Disattivato, l’avvio di altro audio può mettere il video in pausa."), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                }
                GallerySettingSwitch(localized(R.string.video_stick_controls, "Stick sinistro: volume e avanzamento"), draft.videoStickControls, Modifier.testTag("video-stick-controls")) {
                    change(draft.copy(videoStickControls = it))
                }
                Text(localized(R.string.video_stick_description, "Durante un video, muovi lo stick sinistro su o giù per regolare il volume, a destra o sinistra per avanzare o tornare indietro. Il D-pad resta disponibile per navigare. Disattiva per usare anche lo stick nella galleria."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PlaybackChoice(localized(R.string.video_auto_dim, "Oscuramento automatico"), draft.videoDimSeconds,
                    listOf(0, 5, 10, 30, 60, 300).associateWith { when (it) {
                        0 -> localized(R.string.video_auto_dim_off, "Disattivato")
                        300 -> localized(R.string.text_1_s_minutes_c69ae1, "%1\$s minuti", 5)
                        else -> "$it s"
                    } }, "video-auto-dim") { change(draft.copy(videoDimSeconds = it)) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GalleryIcon(R.drawable.ph_info, null, Modifier.size(18.dp))
                    Text(localized(R.string.video_auto_dim_description, "Durante un video, oscura lo schermo di navigazione dopo il tempo scelto senza interazioni. Toccalo per riattivarlo. Disattivato: usa la luna quando vuoi; la ricerca nasconde il pulsante, non interrompe il timer."), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                }
        }
        if (category in setOf("ALL", "MUSIC")) {
                    Text(localized(R.string.text_music_012df9, "Musica"), style = MaterialTheme.typography.titleMedium)
                    GallerySettingSwitch(localized(R.string.playback_sleep_continue, "Continua a console chiusa"), draft.musicDuringSleep, Modifier.testTag("music-during-sleep")) { change(draft.copy(musicDuringSleep = it)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { GalleryIcon(R.drawable.ph_info, null, Modifier.size(18.dp)); Text(localized(R.string.music_sleep_help, ""), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall) }
                    MusicColorSettings(draft, change)
                    PlaybackChoice(localized(R.string.music_cover_animation, "Animazione della copertina"), draft.musicCoverEffect,
                        MusicCoverEffects.ORDER.associateWith { when (it) {
                            2 -> localized(R.string.music_cover_pulse, "Pulsazione")
                            1 -> localized(R.string.music_cover_disc_hole, "CD con foro")
                            3 -> localized(R.string.music_cover_disc_solid, "CD senza foro")
                            else -> localized(R.string.music_cover_normal, "Normale")
                        } }, "music-cover-choice") { change(draft.copy(musicCoverEffect = it)) }
                    GallerySettingSwitch(localized(R.string.text_previous_always_changes_track_747bc5, "Precedente cambia sempre brano"), draft.alwaysPrevious, Modifier.testTag("always-previous")) {
                        change(draft.copy(alwaysPrevious = it))
                    }
                    Row(Modifier.fillMaxWidth().testTag("music-previous-help"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GalleryIcon(R.drawable.ph_info, null, Modifier.size(18.dp))
                    Text(if (draft.alwaysPrevious) localized(R.string.text_previous_changes_track_even_after_three_seconds_3e5ab8, "Precedente cambia brano anche dopo tre secondi. Se non c'è un brano precedente, torna all'inizio.")
                        else localized(R.string.text_previous_returns_to_the_beginning_after_three_se_9b4ec0, "Precedente torna all'inizio dopo tre secondi; premilo di nuovo entro tre secondi per passare al brano precedente."),
                        modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
        }
    }
}

@Composable
private fun DependentSetting(visible: Boolean, content: @Composable () -> Unit) {
    androidx.compose.animation.AnimatedVisibility(visible, enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GalleryIcon(R.drawable.ph_arrow_bend_down_right, null, Modifier.padding(start = 12.dp).size(20.dp))
            Column(Modifier.weight(1f)) { content() }
        }
    }
}
