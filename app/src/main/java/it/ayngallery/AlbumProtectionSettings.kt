package it.ayngallery

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun AlbumPinSettings(app: GalleryApp) {
    val state by app.catalog.protection.state.collectAsStateWithLifecycle()
    var mode by remember { mutableStateOf<String?>(null) }
    val configured = remember(state, mode) { app.catalog.protection.configured }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedCard(Modifier.fillMaxWidth(), shape = GalleryShape) {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AlbumLockBadge()
                Column(Modifier.weight(1f)) {
                    Text(localized(R.string.album_pin_settings, "PIN degli album"), style = MaterialTheme.typography.titleMedium)
                    Text(localized(R.string.album_pin_shared, "Un solo PIN per tutti gli album protetti."), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        GalleryButton(if (configured) localized(R.string.album_pin_change, "Cambia PIN") else localized(R.string.album_pin_add, "Aggiungi PIN"),
            Modifier.fillMaxWidth().testTag("settings-album-pin"), primary = true) { mode = if (configured) "CHANGE" else "SETUP" }
    }
    mode?.let { AlbumPinDialog(app.catalog.protection, it, dismiss = { mode = null }, accepted = { mode = null }) }
}

@Composable
internal fun OpenAlbumMenu(activity: PanelActivity, data: CatalogSnapshot, albumId: String) {
    val app = activity.application as GalleryApp
    var open by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf<String?>(null) }
    val protected = albumId in data.protectedAlbumIds
    Box {
        GalleryIconButton(R.drawable.ph_gear_six, localized(R.string.text_settings_7b855c, "Impostazioni"), Modifier.testTag("open-album-menu")) { open = true }
        GalleryDropdownMenu(open, { open = false }) {
            DropdownMenuItem(text = { Text(localized(if (protected) R.string.album_unlock_action else R.string.album_lock_action, if (protected) "Sblocca" else "Blocca")) },
                leadingIcon = { GalleryIcon(R.drawable.ph_lock, null) }, onClick = { open = false; mode = if (protected) "REMOVE" else "PROTECT" })
            DropdownMenuItem(text = { Text(localized(R.string.text_settings_7b855c, "Impostazioni")) },
                leadingIcon = { GalleryIcon(R.drawable.ph_gear_six, null) }, onClick = { open = false; app.session.openSettings(activity) })
        }
    }
    mode?.let { action -> AlbumPinDialog(app.catalog.protection, action, setOf(albumId), dismiss = { mode = null }, accepted = {
        mode = null
        if (action == "PROTECT") activity.windowModel.back()
    }) }
}
