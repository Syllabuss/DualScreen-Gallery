package it.ayngallery

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

internal fun interfaceCategoryHasMedia(data: CatalogSnapshot, section: String): Boolean =
    if (section == "ALBUM") data.visibleAlbums().isNotEmpty()
    else data.media.any { data.visible(it) && when (section) {
        "MUSIC" -> it.kind == "AUDIO"
        "VIDEO" -> it.kind == "VIDEO"
        "FAVORITE" -> it.favorite
        else -> it.kind in setOf("IMAGE", "GIF")
    } }

internal fun timelineLabel(mode: String) = when (mode) {
    "DAY" -> localized(R.string.timeline_day, "Giorno")
    "MONTH" -> localized(R.string.timeline_month, "Mese")
    "YEAR" -> localized(R.string.timeline_year, "Anno")
    else -> localized(R.string.timeline_off, "Nessun raggruppamento")
}

@Composable
internal fun TimelineMenu(app: GalleryApp, prefs: GalleryPreferences, tab: String) {
    var open by remember { mutableStateOf(false) }
    val section = InterfaceTools.section(tab)
    val mode = prefs.timelineModes[section] ?: "OFF"
    val scope = rememberCoroutineScope()
    Box {
        CompositionLocalProvider(LocalContentColor provides if (mode == "OFF") MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary) {
            GalleryIconButton(R.drawable.ph_calendar, localized(R.string.timeline_title, "Navigazione cronologica"), Modifier.focusProperties { canFocus = prefs.controllerSorting }.testTag("timeline-menu")) { open = true }
        }
        GalleryDropdownMenu(open, { open = false }) {
            listOf("OFF", "DAY", "MONTH", "YEAR").forEach { value ->
                DropdownMenuItem(text = { Text(timelineLabel(value)) }, leadingIcon = { if (value == mode) Text("✓") },
                    modifier = Modifier.testTag("timeline:$value"), onClick = {
                        open = false
                        scope.launch { app.preferences.update { it.copy(timelineModes = it.timelineModes + (section to value)) } }
                    })
            }
        }
    }
}

@Composable
internal fun SectionToolsSettings(section: String, prefs: GalleryPreferences, enabled: Boolean = true, change: (GalleryPreferences) -> Unit) {
    val context = if (section == "MUSIC") PadContext.PLAYER else PadContext.LIBRARY
    val owner = androidx.activity.compose.LocalActivity.current as? PanelActivity
    val mappings = prefs.pad.profile(owner?.lastPadDevice?.descriptor).bindings[context].orEmpty()
    fun shortcut(action: PadAction) = mappings[action]?.let { binding -> binding.label.ifBlank {
        binding.buttons.sortedBy { it.ordinal }.joinToString(" + ") { when (it) { PadButton.SELECT -> "■"; PadButton.START -> "▶"; else -> it.name } }
    } }.orEmpty()
    val available = when (section) {
        "MUSIC" -> listOf("MUSIC_MODE", "VIEW", "SEARCH", "SWAP")
        "VIDEO" -> listOf("ZOOM", "VIEW", "TIMELINE", "SEARCH", "SWAP")
        "FAVORITE" -> listOf("ZOOM", "VIEW", "SEARCH", "FILTER", "SWAP")
        else -> listOf("ZOOM", "VIEW", "TIMELINE", "SEARCH", "FILTER", "SWAP")
    }
    OutlinedCard(Modifier.fillMaxWidth(), shape = GalleryShape) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val tab = MainTabs.first { it.key == section }
                GalleryIcon(tab.icon, null)
                Text(tab.title, style = MaterialTheme.typography.titleMedium)
            }
            if (section == "ALBUM") Text(localized(R.string.settings_album_tools_note, "Queste opzioni valgono anche per i media dentro un album."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            available.filter { it != "SWAP" || owner?.session?.isSingleScreen != true }.forEach { tool ->
                val label = when (tool) {
                    "ZOOM" -> localized(R.string.tools_zoom, "Pulsanti + e −")
                    "VIEW" -> localized(R.string.tools_view, "Pulsante lista / griglia")
                    "TIMELINE" -> localized(R.string.timeline_title, "Navigazione cronologica")
                    "SEARCH" -> localized(R.string.tools_search, "Pulsante ricerca")
                    "FILTER" -> localized(R.string.tools_filter, "Filtro dei formati")
                    "MUSIC_MODE" -> localized(R.string.tools_music_mode, "Menu della libreria musicale")
                    else -> localized(R.string.tools_swap, "Pulsante scambia schermi")
                }
                val actions = when (tool) { "ZOOM" -> listOf(PadAction.GRID_SMALLER, PadAction.GRID_LARGER); "SWAP" -> listOf(PadAction.SWAP); "VIEW" -> listOf(PadAction.TOGGLE_VIEW); else -> emptyList() }
                val shortcut = actions.map(::shortcut).filter { it.isNotBlank() }.joinToString(" / ")
                val shown = InterfaceTools.shown(prefs, section, tool)
                Row(Modifier.fillMaxWidth().testTag("tool:$section:$tool").toggleable(shown, enabled = enabled, role = Role.Switch) {
                    val key = "$section:$tool"
                    change(prefs.copy(hiddenTools = if (it) prefs.hiddenTools - key else prefs.hiddenTools + key))
                }.heightIn(min = 48.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(label, style = MaterialTheme.typography.bodyMedium); if (shortcut.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(localized(R.string.controller_shortcuts, "Scorciatoie controller"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        actions.map(::shortcut).filter { it.isNotBlank() }.forEach { key ->
                            Surface(shape = androidx.compose.foundation.shape.CircleShape, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                                Box(Modifier.defaultMinSize(minWidth = 28.dp, minHeight = 28.dp).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) { Text(key, style = MaterialTheme.typography.labelSmall) }
                            }
                        }
                    } }
                    Switch(shown, null, enabled = enabled)
                }
            }
            if (shortcut(PadAction.SETTINGS).isNotBlank()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                GalleryIcon(R.drawable.ph_info, null, Modifier.size(18.dp))
                Text(localized(R.string.controller_shortcuts, "Scorciatoie controller") + " · " + localized(R.string.text_settings_7b855c, "Impostazioni"), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Surface(shape = androidx.compose.foundation.shape.CircleShape, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                    Box(Modifier.defaultMinSize(minWidth = 28.dp, minHeight = 28.dp).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) { Text(shortcut(PadAction.SETTINGS), style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
    }
}
