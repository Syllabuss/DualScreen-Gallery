package it.ayngallery

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun GalleryNavbar(app: GalleryApp, prefs: GalleryPreferences, current: String, navigate: (String) -> Unit, compact: Boolean = false, interactive: Boolean = true) {
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var draft by rememberSaveable { mutableStateOf(NavbarOrder.editable(prefs)) }
    val scope = rememberCoroutineScope()
    @Composable fun Bar(order: List<String>, selectedKey: String, preview: Boolean) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            order.forEach { id -> key(id) {
                val tab = MainTabs.first { it.key == id }
                val selected = selectedKey == id
                val duration = if (prefs.reducedMotion) 0 else 200
                val background by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent, androidx.compose.animation.core.tween(duration), label = "navbar selection")
                val tint by animateColorAsState(if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, androidx.compose.animation.core.tween(duration), label = "navbar tint")
                val iconAlpha by androidx.compose.animation.core.animateFloatAsState(if (selected) 1f else .85f, androidx.compose.animation.core.tween(duration), label = "navbar opacity")
                val iconScale by androidx.compose.animation.core.animateFloatAsState(if (selected) 1f else .94f, androidx.compose.animation.core.tween(duration), label = "navbar icon")
                val itemHeight = when {
                    preview || prefs.navbarLabels -> if (compact) 56.dp else 60.dp
                    compact -> 44.dp
                    else -> 48.dp
                }
                Column(Modifier.weight(1f).height(itemHeight)
                    .testTag(if (preview) "navbar-preview:$id" else "navbar:$id")
                    .semantics { this.selected = selected; contentDescription = if (preview) localized(R.string.text_preview_1_s_81e72b, "Anteprima: %1\$s", tab.title) else tab.title }
                    .focusProperties { canFocus = preview }
                    .combinedClickable(enabled = interactive, onClick = { if (preview) editing = id else navigate(id) },
                        onLongClickLabel = localized(R.string.text_reorder_navigation_bar_f913bd, "Riordina navbar"), onLongClick = if (preview || id == "FTP") null else {{ draft = NavbarOrder.editable(prefs); editing = id }}),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    CompositionLocalProvider(LocalContentColor provides tint) {
                        Box(Modifier.size(38.dp).background(background, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                            GalleryIcon(tab.icon, null, Modifier.graphicsLayer { scaleX = iconScale; scaleY = iconScale; alpha = iconAlpha })
                        }
                        if (preview || prefs.navbarLabels) Text(tab.title, style = MaterialTheme.typography.labelSmall, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } }
        }
    }
    Bar(visibleNavbarTabs(prefs).map { it.key }, current, false)
    editing?.let { selectedKey ->
        val index = draft.indexOf(selectedKey)
        fun move(delta: Int) {
            val target = index + delta
            if (target in draft.indices) draft = draft.toMutableList().apply { add(target, removeAt(index)) }.toList()
        }
        GalleryAlertDialog(onDismissRequest = { editing = null }, title = { Text(localized(R.string.text_reorder_navigation_bar_5b4ba2, "Riordina la navbar")) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(localized(R.string.text_tap_a_section_in_the_preview_and_move_it_with_th_098245, "Tocca una sezione nell’anteprima e spostala con le frecce. Salva per applicare l’ordine."))
                Surface(shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Text(localized(R.string.text_preview_c10f62, "ANTEPRIMA"), Modifier.align(Alignment.CenterHorizontally), style = MaterialTheme.typography.labelMedium)
                        Bar(draft, selectedKey, true)
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    GalleryIconButton(R.drawable.ph_arrow_left, localized(R.string.text_move_left_4e3ed5, "Sposta a sinistra"), enabled = index > 0) { move(-1) }
                    Text(MainTabs.first { it.key == selectedKey }.title)
                    GalleryIconButton(R.drawable.ph_arrow_right, localized(R.string.text_move_right_1d91fd, "Sposta a destra"), enabled = index < draft.lastIndex) { move(1) }
                }
            }
        }, confirmButton = { TextButton(onClick = {
            app.sounds.play("save")
            val order = draft
            scope.launch { app.preferences.update { it.copy(navbarOrder = order + it.navbarOrder.filterNot(order::contains)) }; editing = null }
        }) { Text(localized(R.string.text_save_c5997e, "Salva")) } }, dismissButton = { TextButton({ editing = null }) { Text(localized(R.string.text_cancel_18c9d9, "Annulla")) } })
    }
}
