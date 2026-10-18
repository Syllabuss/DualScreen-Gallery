package it.ayngallery

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun settingsFocusContent(selected: Boolean, reducedMotion: Boolean, selectedScale: Float = 1.045f): Modifier {
    val scale by androidx.compose.animation.core.animateFloatAsState(if (selected) selectedScale else 1f,
        androidx.compose.animation.core.tween(if (reducedMotion) 0 else 180), label = "settings focus")
    return Modifier.graphicsLayer { scaleX = scale; scaleY = scale }
}

@Composable
internal fun SettingsInfoCard(app: GalleryApp) {
    val prefs by app.preferences.state.collectAsStateWithLifecycle()
    OutlinedCard(onClick = { app.session.settingsInfoFocused = false; app.session.settingsSection = "INFO"; app.session.settingsModel()?.go("SETTINGS_INFO") },
        border = androidx.compose.foundation.BorderStroke(if (app.session.settingsInfoFocused) 3.dp else 1.dp, if (app.session.settingsInfoFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 28.dp).testTag("settings-partner-info")) {
        Column(Modifier.fillMaxSize().then(settingsFocusContent(app.session.settingsInfoFocused, prefs.reducedMotion)), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            GalleryIcon(R.drawable.ph_info, null, Modifier.size(56.dp).then(settingsFocusContent(app.session.settingsInfoFocused, prefs.reducedMotion, 1.12f)))
            Spacer(Modifier.height(16.dp))
            Text(localized(R.string.info_title, "Info e permessi"), style = MaterialTheme.typography.headlineSmall)
        }
    }
}

@Composable
internal fun DualScreenDisclaimer(app: GalleryApp) {
    if (!app.session.canSwap()) return
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.BottomCenter) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GalleryIcon(R.drawable.ph_info, null)
            Text(localized(R.string.dual_screen_disclaimer, "Pensata per due schermi. La modalità a schermo singolo è sperimentale e non ancora sufficientemente collaudata."),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
