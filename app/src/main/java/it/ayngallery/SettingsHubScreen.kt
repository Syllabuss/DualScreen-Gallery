package it.ayngallery

import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.staggeredgrid.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
private fun PermissionCard(title: String, description: String, action: String, onAction: () -> Unit,
                           status: String, enabled: Boolean = true) {
    OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text(status, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (enabled) GalleryButton(action, Modifier.fillMaxWidth(), primary = true, onClick = onAction)
        }
    }
}

private data class SettingsEntry(val key: String, private val titleText: () -> String, private val detailText: () -> String, val icon: Int) {
    val title: String get() = titleText()
    val detail: String get() = detailText()
}
private val settingsCategories = listOf(
    SettingsEntry("INTERFACE", { localized(R.string.text_interface_a86468, "Interfaccia") }, { localized(R.string.text_gallery_preview_and_navigation_1bc538, "Galleria, anteprima e navigazione") }, R.drawable.ph_squares_four),
    SettingsEntry("PLAYBACK", { localized(R.string.text_playback_2b0fe5, "Riproduzione") }, { localized(R.string.text_music_animation_and_game_mode_678659, "Musica, animazioni e modalità gioco") }, R.drawable.ph_play),
    SettingsEntry("SOUNDS", { localized(R.string.sounds_title, "Suoni") }, { localized(R.string.ui_sounds, "Suoni dell’interfaccia") }, R.drawable.ph_music_notes),
    SettingsEntry("INPUT", { localized(R.string.text_controls_3c6824, "Comandi") }, { localized(R.string.text_buttons_sticks_and_touch_gestures_220de4, "Pulsanti, stick e gesti touch") }, R.drawable.ph_game_controller),
    SettingsEntry("DEVICE", { localized(R.string.text_screens_and_tools_153eab, "Schermi e backup") }, { localized(R.string.text_thor_layout_cache_and_saved_settings_f7e1f8, "Layout Thor e backup delle impostazioni") }, R.drawable.ph_monitor_arrow_up),
    SettingsEntry("TRASH", { localized(R.string.tools_title, "Strumenti") }, { localized(R.string.recovery_description, "Ripristina file e trova duplicati") }, R.drawable.ph_desktop_tower),
    SettingsEntry("INFO", { localized(R.string.info_title, "Info e permessi") }, { localized(R.string.info_description, "Versione, autore, cartelle e permessi") }, R.drawable.ic_info))

@Composable
fun SettingsHubScreen(activity: PanelActivity, page: String, categoryFocus: FocusRequester? = null) {
    val app = activity.application as GalleryApp
    val session = app.session
    val prefs by app.preferences.state.collectAsStateWithLifecycle()
    val vm = activity.windowModel
    var advanced by rememberSaveable(page) { mutableStateOf(false) }
    var pendingInputTab by remember { mutableStateOf<String?>(null) }
    val saveState = LocalSettingsSave.current
    var showHelp by remember { mutableStateOf(false) }
    var inputTab by rememberSaveable { mutableStateOf("PAD") }
    val inputState = rememberSaveableStateHolder()
    fun selectInputTab(next: String) {
        if (inputTab != next && saveState?.value?.busy != true) {
            if (saveState?.value?.dirty == true) pendingInputTab = next else inputTab = next
        }
    }
    val inputAction by rememberUpdatedState { action: PadAction ->
        when (action) {
            PadAction.TAB_PREVIOUS, PadAction.TAB_NEXT -> { selectInputTab(if (inputTab == "PAD") "TOUCH" else "PAD"); true }
            PadAction.SETTINGS, PadAction.MENU -> { showHelp = true; true }
            else -> false
        }
    }
    DisposableEffect(activity, page) {
        val handler = { action: PadAction -> inputAction(action) }
        if (page == "SETTINGS_INPUT") activity.settingsInputAction = handler
        onDispose { if (activity.settingsInputAction === handler) activity.settingsInputAction = null }
    }
    val catalog by app.catalog.snapshot.collectAsStateWithLifecycle()
    val operations by app.files.operations.collectAsStateWithLifecycle()
    var focusedCategory by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        if (page == "SETTINGS_HUB") {
            val firstCategory = categoryFocus ?: remember { FocusRequester() }
            LaunchedEffect(Unit) {
                if (session.lastSettingsCategory != null) { androidx.compose.runtime.withFrameNanos { }; firstCategory.requestFocus() }
            }
            val grid by app.preferences.settingsGrid.collectAsStateWithLifecycle()
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val entries = settingsCategories.filter { it.key != "INFO" || !session.canSwap() }
            val rows = (entries.size + 2) / 3
            val cellHeight = ((maxHeight - 32.dp - 16.dp * (rows - 1)) / rows).coerceAtLeast(110.dp)
            androidx.compose.animation.Crossfade(grid, animationSpec = androidx.compose.animation.core.tween(if (prefs.reducedMotion) 0 else 220), label = "settings-view") { tileGrid ->
            LazyVerticalGrid(GridCells.Fixed(if (tileGrid) 3 else 1), Modifier.fillMaxSize().testTag("settings-hub"), contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(entries, key = { it.key }) { entry ->
                    OutlinedCard(onClick = {
                        session.lastSettingsCategory = entry.key
                        session.settingsSection = entry.key
                        if (entry.key == "TRASH") { session.recoveryTab = "TRASH"; session.trashPreviewId = null }
                        vm.go(if (entry.key in setOf("THEME", "INTERFACE", "VIEWER", "PLAYBACK")) "SETTINGS" else if (entry.key == "TRASH") "TRASH" else "SETTINGS_${entry.key}")
                    }, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).then(if (entry.key == (session.lastSettingsCategory ?: "INTERFACE")) Modifier.focusRequester(firstCategory) else Modifier).then(if (tileGrid) Modifier.height(cellHeight) else Modifier.heightIn(min = 80.dp))
                        .onFocusChanged { if (it.isFocused) { focusedCategory = entry.key; session.settingsHubBottomFocused = entries.indexOf(entry) >= (if (tileGrid) (rows - 1) * 3 else entries.lastIndex) } else if (focusedCategory == entry.key) { focusedCategory = null; session.settingsHubBottomFocused = false } }
                        .testTag("settings-category:${entry.key}"), shape = RoundedCornerShape(18.dp),
                        border = androidx.compose.foundation.BorderStroke(if (focusedCategory == entry.key) 3.dp else 1.dp,
                            if (focusedCategory == entry.key) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        val contentMotion = settingsFocusContent(focusedCategory == entry.key, prefs.reducedMotion)
                        val iconMotion = settingsFocusContent(focusedCategory == entry.key, prefs.reducedMotion, 1.12f)
                        if (tileGrid) Column(Modifier.fillMaxSize().padding(12.dp).then(contentMotion), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            GalleryIcon(entry.icon, null, iconMotion)
                            Spacer(Modifier.height(12.dp))
                            Text(entry.title, fontWeight = FontWeight.SemiBold, maxLines = 2,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        } else
                        Row(Modifier.padding(16.dp).then(contentMotion), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            GalleryIcon(entry.icon, null, Modifier.size(30.dp).then(iconMotion))
                            Column(Modifier.weight(1f)) {
                                Text(entry.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                Text(entry.detail, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { GalleryIcon(R.drawable.ph_arrow_right, null) }
                        }
                    }
                }
            }
            }
            }
        } else if (page == "SETTINGS_SOUNDS") {
            SoundSettingsScreen(app, prefs)
        } else if (page == "SETTINGS_INPUT") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("PAD" to localized(R.string.text_buttons_and_sticks_bf2200, "Pulsanti e stick"), "TOUCH" to localized(R.string.text_touch_gestures_241cad, "Gesti touch")).forEach { (key, label) ->
                        FilterChip(inputTab == key, {
                            selectInputTab(key)
                        }, modifier = Modifier.testTag("input-tab:$key"), label = { Text(label) })
                    }
                }
                TextButton({ showHelp = true }, Modifier.testTag("input-help")) { Text(localized(R.string.text_help_79df3a, "Guida")); Spacer(Modifier.width(8.dp)); ControllerChoiceBadge("X") }
            }
            Box(Modifier.weight(1f)) {
                inputState.SaveableStateProvider(inputTab) {
                    if (inputTab == "PAD") PadSettingsScreen(app, prefs) else TouchSettingsScreen(app, prefs)
                }
            }
        } else if (page == "SETTINGS_PERMISSIONS") {
            LazyColumn(Modifier.fillMaxSize().testTag("settings-permissions"), contentPadding = PaddingValues(16.dp)) {
                item { PermissionControls(activity) }
            }
        } else {
            val entries = when (page) {
                "SETTINGS_LIBRARY" -> listOf(
                    "SOURCES" to localized(R.string.media_folders_title, "Cartelle multimediali"),
                    "COLLECTION" to localized(R.string.text_collections_and_playlists_8b30ee, "Raccolte e playlist"),
                    "HIDDEN" to localized(R.string.text_hidden_albums_5ff5a5, "Album nascosti"),
                    "SETTINGS_PIN" to localized(R.string.album_pin_settings, "PIN degli album"),
                    "TRASH" to localized(R.string.text_trash_9a3a36, "Cestino"),
                    "DUPLICATES" to localized(R.string.text_find_duplicates_d1ed15, "Trova duplicati"),
                    "COPY_VISUAL" to localized(R.string.copy_photos_title, "Copia foto e video in una cartella…"),
                    "COPY_MUSIC" to localized(R.string.copy_music_title, "Copia musica in una cartella…"))
                else -> listOf("TRANSFER" to localized(R.string.text_export_or_import_settings_5f5279, "Esporta o importa impostazioni"))
            }
            LazyColumn(Modifier.fillMaxSize().testTag("settings-tools"), contentPadding = PaddingValues(if (page == "SETTINGS_DEVICE") 8.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (page == "SETTINGS_DEVICE") {
                    item { Text(localized(R.string.text_manage_both_screens_dcba9a, "Gestisci i due schermi"), style = MaterialTheme.typography.titleMedium) }
                    item { DisplayControls(activity) }
                }
                val visibleEntries = if (page == "SETTINGS_LIBRARY" && operations.any { !FileOperations.terminal(it.phase) })
                    listOf("OPERATIONS" to localized(R.string.text_operations_to_finish_4f1ecf, "Operazioni da completare")) + entries else entries
                val extraKeys = setOf("DUPLICATES", "COPY_VISUAL", "COPY_MUSIC", "TRANSFER")
                visibleEntries.filterNot { it.first in extraKeys }.forEach { (key, label) ->
                    if (page == "SETTINGS_LIBRARY") when (key) {
                        "SOURCES" -> item { Text(localized(R.string.settings_organize, "Cartelle e raccolte"), style = MaterialTheme.typography.titleMedium) }
                        "HIDDEN" -> item { Text(localized(R.string.settings_privacy, "Privacy"), style = MaterialTheme.typography.titleMedium) }
                        "TRASH" -> item { Text(localized(R.string.settings_file_management, "Gestione dei file"), style = MaterialTheme.typography.titleMedium) }
                    }
                    item(key = key) { SettingsToolRow(activity, key, label) }
                }
                if (page == "SETTINGS_DEVICE") item { PreferencesTransferScreen(activity, prefs, embedded = true) }
            }
        }
    }
    pendingInputTab?.let { next -> SettingsDiscardDialog(dismiss = { pendingInputTab = null }, discard = { saveState?.value?.discard?.invoke(); inputTab = next; pendingInputTab = null }) }
    if (showHelp) PadHelpDialog(PadContext.LIBRARY, prefs.pad.profile(activity.lastPadDevice?.descriptor), activity.lastPadDevice?.name) { showHelp = false }
}

@Composable
private fun SettingsToolRow(activity: PanelActivity, key: String, label: String) {
    val app = activity.application as GalleryApp
    val vm = activity.windowModel
    OutlinedCard(onClick = { when (key) {
        "COPY_VISUAL" -> activity.chooseImport()
        "COPY_MUSIC" -> activity.chooseImport(music = true)
        "SETTINGS_PIN" -> { app.session.settingsSection = "PIN"; vm.go(key) }
        else -> vm.go(key)
    } }, modifier = Modifier.fillMaxWidth().testTag("settings-tool:$key")) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            GalleryIcon(R.drawable.ph_arrow_right, null)
        }
    }
}

@Composable
internal fun ControllerQuickGuide() {
    val cards = listOf(
        Triple(R.drawable.ph_images, R.string.controller_guide_image_title, R.string.controller_guide_image_body),
        Triple(R.drawable.ph_game_controller, R.string.controller_guide_video_title, R.string.controller_guide_video_body),
        Triple(R.drawable.ph_game_controller, R.string.console_stick_title, R.string.console_stick_body),
        Triple(R.drawable.ph_arrows_down_up, R.string.controller_guide_dpad_title, R.string.controller_guide_dpad_body),
        Triple(R.drawable.ph_game_controller, R.string.controller_guide_l3_title, R.string.controller_guide_l3_body),
        Triple(R.drawable.ph_squares_four, R.string.controller_guide_shortcuts_title, R.string.controller_guide_shortcuts_body),
        Triple(R.drawable.ph_arrows_left_right, R.string.controller_guide_tabs_title, R.string.controller_guide_tabs_body)
    )
    Column(Modifier.fillMaxSize().testTag("controller-quick-guide")) {
        Text(localized(R.string.controller_guide_title, "Guida rapida ai comandi"),
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), style = MaterialTheme.typography.titleLarge)
        androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(cards.size) { index ->
                val (icon, title, body) = cards[index]
                Surface(shape = GalleryShape, color = MaterialTheme.colorScheme.surfaceVariant,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        GalleryIcon(icon, null, Modifier.size(24.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(localized(title, ""), style = MaterialTheme.typography.titleSmall)
                            Text(localized(body, ""), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsPreviewScreen(app: GalleryApp, data: CatalogSnapshot, draft: GalleryPreferences, section: String) {
    GalleryDesign(draft) {
        if (section == "INPUT") {
            ControllerQuickGuide()
            return@GalleryDesign
        }
        if (section == "PLAYBACK") {
            PlaybackSettingsPreview(app.session.playbackSettingsCategory, draft)
            return@GalleryDesign
        }
        if (section !in setOf("THEME", "INTERFACE")) {
            Box(Modifier.fillMaxSize().testTag("settings-preview-idle"))
            return@GalleryDesign
        }
        val previewTab = app.session.settingsToolsSection.takeUnless { it in draft.disabledCategories } ?: draft.navbarOrder.first { it !in draft.disabledCategories }
        androidx.compose.animation.Crossfade(previewTab, animationSpec = androidx.compose.animation.core.tween(if (draft.reducedMotion) 0 else 240), label = "settings-preview-tab") { tab ->
        if (section == "INTERFACE" && !interfaceCategoryHasMedia(data, tab)) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth().testTag("settings-preview-unavailable"), contentAlignment = Alignment.Center) {
                    Text(localized(R.string.settings_preview_no_media, "Non disponi di alcun media per questa categoria."),
                        Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            return@Crossfade
        }
        val columns by (if (tab == "ALBUM") app.preferences.albumColumns else app.preferences.mediaColumns(tab)).collectAsStateWithLifecycle()
        val grid by (if (tab == "ALBUM") app.preferences.albumGrid else app.preferences.mediaGrid(tab)).collectAsStateWithLifecycle()
        val samples = remember(data, tab) { data.media.filter { data.visible(it) && when (tab) {
            "MUSIC" -> it.kind == "AUDIO"; "VIDEO" -> it.kind in setOf("VIDEO", "GIF")
            "FAVORITE" -> it.favorite; else -> it.kind in setOf("IMAGE", "GIF", "VIDEO")
        } }.take(12) }
        val albumOrder by app.preferences.albumOrder.collectAsStateWithLifecycle()
        val albums = remember(data, draft.nestedAlbums, albumOrder) {
            val rank = albumOrder.withIndex().associate { it.value to it.index }
            val top = data.topAlbums(draft).map { it.id }.toSet()
            data.visibleAlbums()
                .distinctBy { it.id }.filter { it.id in top }
                .sortedWith(compareBy<AlbumEntity> { rank[it.id] ?: -1 }.thenBy { it.name }).take(12)
        }
        val previewAnimatedIds = remember(data, albums, draft.animatedAlbumCovers, draft.albumCoverLimit, columns) {
            if (!draft.animatedAlbumCovers) emptySet() else animatedGifIds(albums.filter { data.cover(it)?.kind == "GIF" }.map { it.id to 256L * 256 }, draft.albumCoverLimit)
        }
        val hasContent = if (tab == "ALBUM") albums.isNotEmpty() else samples.isNotEmpty()
        val chrome = MaterialTheme.colorScheme.surface.copy(alpha = if (draft.backgroundMode == "PHOTO") .9f else .94f)
        @Composable fun mediaSample(media: MediaEntity) {
            MediaTile(media, data, app.catalog, draft, selected = false, multi = false, checked = false,
                animated = false, active = true, focus = {}, click = {}, actions = {},
                modifier = Modifier.focusProperties { canFocus = false }, list = !grid, mosaic = tab == "FAVORITE")
        }
        Box(Modifier.fillMaxSize().then(Modifier.galleryBackground(app, data, draft)).testTag("settings-preview")) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().background(chrome).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                val leading = if (tab == "MUSIC") listOf("MUSIC_MODE" to R.drawable.ph_music_notes)
                    else if (grid && hasContent) listOf("ZOOM" to R.drawable.ph_magnifying_glass_plus, "ZOOM" to R.drawable.ph_magnifying_glass_minus) else emptyList()
                leading.filter { InterfaceTools.shown(draft, tab, it.first) }.forEach { (tool, icon) ->
                    Box(Modifier.size(48.dp).testTag("settings-preview-tool:$tab:$tool:$icon"), contentAlignment = Alignment.Center) { GalleryIcon(icon, null) }
                }
                if (tab in setOf("ALBUM", "PHOTO", "VIDEO") && !InterfaceTools.shown(draft, tab, "ZOOM"))
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { GalleryIcon(R.drawable.ph_plus_circle, null) }
                if (hasContent && tab in setOf("PHOTO", "VIDEO") && InterfaceTools.shown(draft, tab, "TIMELINE"))
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { GalleryIcon(R.drawable.ph_calendar, null) }
                Spacer(Modifier.weight(1f))
                val trailing = (if (tab in setOf("ALBUM", "PHOTO", "VIDEO") && InterfaceTools.shown(draft, tab, "ZOOM")) listOf("ADD" to R.drawable.ph_plus_circle) else emptyList()) +
                    listOf("SEARCH" to R.drawable.ph_magnifying_glass) +
                    (if (hasContent) listOf("VIEW" to if (grid) R.drawable.ph_list else R.drawable.ph_squares_four) else emptyList())
                trailing.filter { InterfaceTools.shown(draft, tab, it.first) }.forEach { (tool, icon) ->
                    Box(Modifier.size(48.dp).testTag("settings-preview-tool:$tab:$tool:$icon"), contentAlignment = Alignment.Center) { GalleryIcon(icon, null) }
                }
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { GalleryIcon(R.drawable.ph_dots_three, null) }
            }
            if (tab == "FAVORITE" && grid) LazyVerticalStaggeredGrid(StaggeredGridCells.Fixed(columns), Modifier.weight(1f),
                contentPadding = PaddingValues(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalItemSpacing = 8.dp) {
                items(samples, key = { it.id }) { mediaSample(it) }
            }
            else LazyVerticalGrid(GridCells.Fixed(if (grid) columns else 1), Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(if (tab == "ALBUM" && (!grid || columns < 6)) 16.dp else 8.dp),
                horizontalArrangement = Arrangement.spacedBy(if (tab == "ALBUM") if (columns >= 6) 4.dp else 12.dp else 6.dp),
                verticalArrangement = Arrangement.spacedBy(if (tab == "ALBUM") if (columns >= 6) 4.dp else 12.dp else 6.dp)) {
                if (tab == "ALBUM") items(albums, key = { it.id }) { album ->
                    Surface(modifier = Modifier.testTag("settings-preview-album:${album.id}"),
                        shape = if (grid && columns >= 6) RoundedCornerShape(5.dp) else GalleryShape,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        androidx.compose.runtime.CompositionLocalProvider(LocalAnimatedAlbumIds provides previewAnimatedIds) { AlbumTileContent(album, data, app.catalog, draft, grid, columns, false, MaterialTheme.colorScheme.secondary, false) {} }
                    }
                }
                else items(samples, key = { it.id }) { mediaSample(it) }
            }
            Column(Modifier.fillMaxWidth().background(chrome)) {
                HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = .72f))
                GalleryNavbar(app, draft, tab, navigate = {}, interactive = false)
            }
        }
        if (InterfaceTools.shown(draft, tab, "SWAP")) SmallFloatingActionButton(onClick = {},
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).focusProperties { canFocus = false }.testTag("settings-preview-swap")) {
            Text("⇅", style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { contentDescription = localized(R.string.text_swap_screens_7455a4, "Scambia schermi") })
        }
        Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            for (delta in listOf(-1, 0, 1)) Surface(
                onClick = { if (delta != 0) app.session.cycleSettingsPreview(delta) }, enabled = delta != 0,
                modifier = Modifier.testTag(if (delta == 0) "settings-preview-label" else "settings-preview-cycle:$delta").focusProperties { canFocus = false },
                shape = if (delta == 0) RoundedCornerShape(12.dp) else androidx.compose.foundation.shape.CircleShape,
                color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = .85f), contentColor = MaterialTheme.colorScheme.inverseOnSurface) {
                Text(if (delta == 0) localized(R.string.settings_preview_example, "Preview example") else if (delta < 0) "‹ L1" else "R1 ›",
                    Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.labelLarge)
            }
        }
        }
        }
    }
}

@Composable
internal fun PermissionControls(activity: PanelActivity, showFolders: Boolean = true) {
    val app = activity.application as GalleryApp
    val session = app.session
    val vm = activity.windowModel
    val catalog by app.catalog.snapshot.collectAsStateWithLifecycle()
            var permissionRevision by remember { mutableStateOf(0) }
            androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { app.session.refreshUsageAccess(); permissionRevision++ }
            val usageAccess = app.session.usageAccessGranted
            val notifications = remember(permissionRevision) { androidx.core.app.NotificationManagerCompat.from(activity).areNotificationsEnabled() }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
 if (showFolders) PermissionCard(
                    localized(R.string.media_folders_title, "Cartelle multimediali"),
                    localized(R.string.settings_folders_permission, "L’accesso ai file viene concesso quando scegli le cartelle nella Libreria."),
                    localized(R.string.settings_manage_folders, "Gestisci cartelle"), { session.settingsSection = "INFO"; vm.go("SOURCES") },
                    if (catalog.sources.any { it.enabled && it.reachable && !it.singleDocument && it.id != DeviceGallery.SOURCE }) localized(R.string.settings_folder_access_ready, "Accesso disponibile") else localized(R.string.settings_folder_access_missing, "Da configurare"))
 PermissionCard(
                    localized(R.string.permission_usage_title, "Rilevamento del ritorno alla Home"),
                    localized(R.string.permission_usage_description, "Serve solo per mandare entrambi i pannelli in background quando torni alla Home, lasciando la galleria sull’altro schermo mentre usi un’altra app."),
                    localized(if (usageAccess) R.string.permission_usage_enabled else R.string.permission_enable_usage, if (usageAccess) "Attivo" else "Attiva accesso"),
                    { app.session.openUsageAccessSettings(activity) },
                    if (usageAccess) localized(R.string.permission_usage_enabled, "Attivo") else localized(R.string.permission_status_not_enabled, "Da attivare"),
                    enabled = !usageAccess)
 PermissionCard(
                    localized(R.string.permission_notifications_title, "Notifiche delle operazioni"),
                    localized(R.string.permission_notifications_description, "Le notifiche mostrano l’avanzamento delle copie e delle altre operazioni in corso."),
                    localized(R.string.permission_manage_android, "Apri permessi Android"), {
                        val here = session.current(activity)
                        if (here != null) try {
                            activity.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, activity.packageName), android.app.ActivityOptions.makeBasic().setLaunchDisplayId(here.id).toBundle())
                        } catch (_: RuntimeException) { activity.devicePermissionSettings() }
                    },
                    if (notifications) localized(R.string.permission_status_enabled, "Attive") else localized(R.string.permission_status_not_enabled, "Disattivate"))
    }
}
