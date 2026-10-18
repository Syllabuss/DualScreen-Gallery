package it.ayngallery

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun AppMaintenanceActions(app: GalleryApp) {
    val cache by app.cache.status.collectAsStateWithLifecycle()
    val work by remember(app) {
        androidx.work.WorkManager.getInstance(app).getWorkInfosFlow(androidx.work.WorkQuery.Builder.fromStates(
            listOf(androidx.work.WorkInfo.State.RUNNING, androidx.work.WorkInfo.State.ENQUEUED, androidx.work.WorkInfo.State.BLOCKED)).build())
    }.collectAsStateWithLifecycle(initialValue = null)
    val transferring by app.explorer.transfers.running.collectAsStateWithLifecycle()
    var requested by remember { mutableStateOf(false) }
    LaunchedEffect(cache) {
        if (requested && !cache.busy && cache.message != null) {
            android.widget.Toast.makeText(app.session.toastContext(), if (cache.failed) cache.message else localized(R.string.text_cache_cleared_3a29ae, "Cache pulita"), android.widget.Toast.LENGTH_SHORT).show()
            requested = false
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GalleryButton(localized(R.string.text_clear_cache_591e39, "Pulisci cache"),
                Modifier.weight(1f).fillMaxHeight().testTag("info-cache-clear"), enabled = !cache.busy) {
                requested = true; app.cache.requestClear(allowGame = true)
            }
            GalleryButton(localized(R.string.app_reset_action, "Ripristina l’app"),
                Modifier.weight(1f).fillMaxHeight().testTag("info-app-reset"),
                enabled = !cache.busy && !transferring && work?.none {
                    it.state == androidx.work.WorkInfo.State.RUNNING || it.tags.any { tag -> tag.startsWith("file:") }
                } == true,
                icon = R.drawable.ph_arrow_counter_clockwise, destructive = true) { app.session.resetApplicationRequested = true }
        }
    }
}

@Composable
internal fun CacheClearAction(app: GalleryApp, compact: Boolean = false) {
    val status by app.cache.status.collectAsStateWithLifecycle()
    var requested by remember { mutableStateOf(false) }
    LaunchedEffect(status) {
        if (compact && requested && !status.busy && status.message != null) {
            android.widget.Toast.makeText(app.session.toastContext(), if (status.failed) status.message else localized(R.string.text_cache_cleared_3a29ae, "Cache pulita"), android.widget.Toast.LENGTH_SHORT).show()
            requested = false
        }
    }
    Column(if (compact) Modifier.widthIn(max = 140.dp) else Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (compact) TextButton(onClick = { requested = true; app.cache.requestClear(allowGame = true) }, modifier = Modifier.testTag("cache-clear"), enabled = !status.busy,
            colors = ButtonDefaults.textButtonColors(containerColor = MaterialTheme.colorScheme.secondary, contentColor = MaterialTheme.colorScheme.onSecondary)) {
            Text(localized(R.string.text_clear_cache_591e39, "Pulisci cache"), style = MaterialTheme.typography.labelSmall)
        } else GalleryButton(if (status.busy) localized(R.string.text_clearing_cache_911274, "Pulizia cache…") else localized(R.string.text_clear_cache_591e39, "Pulisci cache"), Modifier.fillMaxWidth().testTag("cache-clear"), enabled = !status.busy) {
            // This explicit action authorizes cache cleanup also while game mode is enabled.
            app.cache.requestClear(allowGame = true)
        }
        status.message?.takeIf { !compact }?.let {
            Text(if (status.failed) it else localized(R.string.text_cache_cleared_3a29ae, "Cache pulita"), Modifier.testTag("cache-result"),
                color = if (status.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall, maxLines = if (compact) 1 else Int.MAX_VALUE, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}
