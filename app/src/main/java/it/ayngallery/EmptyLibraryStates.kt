package it.ayngallery

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer

@Composable
internal fun EmptyCategoryPreview(category: String, prefs: GalleryPreferences, chooseFolder: () -> Unit) {
    val favorite = category == "FAVORITE"
    EmptyGallery(MainTabs.find { it.key == category }?.title ?: localized(R.string.text_preview_e9bcae, "Anteprima"),
        if (favorite) localized(R.string.empty_favorites_message, "Non hai ancora aggiunto media ai preferiti.")
        else localized(R.string.settings_preview_no_media, "Non disponi di alcun media per questa categoria."),
        MainTabs.find { it.key == category }?.icon ?: R.drawable.ph_images,
        if (favorite) null else localized(R.string.text_choose_a_folder_56d054, "Scegli una cartella"),
        featured = true, animateGlow = !prefs.reducedMotion, glowTint = if (favorite) Color(0xFFFF38AF) else null,
        heroIcon = if (favorite) { { PulsingFavorite(!prefs.reducedMotion) } } else null, onAction = chooseFolder)
}

@Composable
private fun attentionPulse(enabled: Boolean): Float {
    if (!enabled) return .5f
    val loop = rememberInfiniteTransition(label = "empty-attention")
    val pulse by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(850, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "empty-pulse")
    return pulse
}

@Composable
internal fun PulsingFavorite(animated: Boolean) {
    val pulse = attentionPulse(animated)
    val magenta = Color(0xFFFF38AF)
    Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) { drawCircle(Brush.radialGradient(listOf(magenta.copy(alpha = .16f + pulse * .22f), Color.Transparent)), radius = size.minDimension * (.38f + pulse * .12f)) }
        CompositionLocalProvider(LocalContentColor provides magenta) { GalleryIcon(R.drawable.ph_heart, null, Modifier.size(58.dp).graphicsLayer { scaleX = .96f + pulse * .08f; scaleY = scaleX }) }
    }
}

@Composable
internal fun EmptyFolderButton(description: String = localized(R.string.choose_media_folder, "Scegli una cartella"), chooseFolder: () -> Unit) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as GalleryApp
    val prefs by app.preferences.state.collectAsState()
    val pulse = attentionPulse(!prefs.reducedMotion)
    val color = MaterialTheme.colorScheme.primary
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(196.dp)) { drawCircle(Brush.radialGradient(listOf(color.copy(alpha = .06f + pulse * .24f), Color.Transparent)), radius = size.minDimension * (.38f + pulse * .12f)) }
        OutlinedButton(chooseFolder, Modifier.size(112.dp).graphicsLayer { scaleX = .94f + pulse * .12f; scaleY = scaleX }.testTag("empty-add-folder"), shape = GalleryShape,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surface)) {
            GalleryIcon(R.drawable.ph_plus_circle, description, Modifier.size(48.dp))
        }
    }
}

@Composable
internal fun AddFolderGuidanceDialog(continueAction: () -> Unit, dismiss: () -> Unit) {
    val confirm = remember { FocusRequester() }
    GalleryAlertDialog(onDismissRequest = dismiss, initialFocus = confirm, containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.widthIn(max = 800.dp).fillMaxWidth(.9f), properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        title = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GalleryIcon(R.drawable.ph_magnifying_glass, null); Text(localized(R.string.choose_media_folder, "Scegli una cartella"))
        } },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { GalleryIcon(R.drawable.ph_info, null, Modifier.size(20.dp)); Text(localized(R.string.folder_add_explanation, "Scegli una cartella da sfogliare. Potrai aggiungerne altre quando vuoi.")) }
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                Text(localized(R.string.shared_storage_help, "Per Download o tutta la memoria interna, autorizza l’accesso a tutti i file nelle impostazioni Android. Poi scegli la cartella. Android/data, Android/obb e le cartelle private restano esclusi."), style = MaterialTheme.typography.bodySmall)
            }
        } },
        confirmButton = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(dismiss, Modifier.focusProperties { canFocus = true }.testTag("folder-guidance-cancel")) { Text(localized(R.string.text_cancel_18c9d9, "Annulla")) }
                Button(continueAction, Modifier.focusRequester(confirm).focusProperties { canFocus = true }.testTag("folder-guidance-continue")) {
                    Text(localized(R.string.folder_add_continue, "Prosegui")); Spacer(Modifier.width(8.dp)); GalleryIcon(R.drawable.ph_arrow_right, null)
                }
            }
        })
}
