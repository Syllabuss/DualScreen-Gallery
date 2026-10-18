package it.ayngallery

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.launch

@Composable
internal fun TvChannelBanner(app: GalleryApp) {
    val announcement = app.tv.announcement
    val loading = app.video.liveLoadingChannel
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(announcement, loading) {
        show = announcement != null
        if (loading == null) { kotlinx.coroutines.delay(3000); show = false }
    }
    Box(Modifier.fillMaxSize().padding(bottom = 100.dp, end = 24.dp), contentAlignment = Alignment.BottomEnd) {
        AnimatedVisibility(show, enter = fadeIn(), exit = fadeOut()) {
            Surface(color = Color.Black.copy(alpha = .82f), contentColor = Color.White, shape = RoundedCornerShape(12.dp), modifier = Modifier.widthIn(max = 360.dp).testTag("tv-channel-banner")) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (loading != null) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    else GalleryIcon(R.drawable.ph_television_simple, null, Modifier.size(24.dp))
                    val entry = loading ?: announcement?.first
                    Text(app.tv.store.channels.firstOrNull { entry != null && it.matches(entry) }?.name ?: entry?.name.orEmpty(), maxLines = 2)
                }
            }
        }
    }
}

@Composable
internal fun TvPlaybackDialogs(activity: PanelActivity) {
    val app = activity.application as GalleryApp
    val tv = app.tv
    val scope = rememberCoroutineScope()
    if (tv.noticeVisible && !tv.store.acknowledged) {
        var checked by remember { mutableStateOf(false) }
        val checkbox = remember { FocusRequester() }
        fun leave() { tv.noticeVisible = false; app.session.libraryModel()?.go("VIDEO") }
        GalleryAlertDialog(onDismissRequest = ::leave, initialFocus = checkbox,
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            modifier = Modifier.fillMaxWidth(.8f).widthIn(max = 720.dp).testTag("tv-authorized-use"),
            icon = { GalleryIcon(R.drawable.ph_info, null) },
            title = { Text(localized(R.string.tv_authorized_title, "TV e fonti autorizzate")) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(localized(R.string.tv_authorized_message, ""))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()
                    .focusRequester(checkbox).testTag("tv-authorized-check")
                    .toggleable(checked, role = androidx.compose.ui.semantics.Role.Checkbox, onValueChange = { checked = it })) {
                    Checkbox(checked, null)
                    Text(localized(R.string.tv_authorized_accept, ""))
                }
            } },
            confirmButton = { Button({ tv.store.acknowledge(); tv.noticeVisible = true }, enabled = checked, modifier = Modifier.testTag("tv-authorized-continue")) {
                Text(localized(R.string.video_resume_action, "Continua"))
            } },
            dismissButton = { TextButton(::leave) { Text(localized(R.string.text_cancel_18c9d9, "Annulla")) } })
        return
    }
    if (tv.noticeVisible && !tv.store.sourceAcknowledged) {
        var checked by remember { mutableStateOf(false) }
        GalleryAlertDialog(onDismissRequest = { tv.noticeVisible = false; app.session.libraryModel()?.go("VIDEO") },
            modifier = Modifier.fillMaxWidth(.8f).testTag("tv-personal-sources-notice"),
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            icon = { GalleryIcon(R.drawable.ph_info, null) },
            title = { Text(localized(R.string.tv_personal_notice_title, "Le tue fonti TV")) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(localized(R.string.tv_personal_notice_body, ""))
                Row(Modifier.fillMaxWidth().toggleable(checked, role = androidx.compose.ui.semantics.Role.Checkbox, onValueChange = { checked = it }), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked, null); Text(localized(R.string.tv_authorized_accept, ""))
                }
            } },
            confirmButton = { Button({ tv.store.acknowledgeSources(); tv.noticeVisible = false }, enabled = checked) { Text(localized(R.string.video_resume_action, "Continua")) } },
            dismissButton = { TextButton({ tv.noticeVisible = false; app.session.libraryModel()?.go("VIDEO") }) { Text(localized(R.string.text_cancel_18c9d9, "Annulla")) } })
        return
    }
    tv.removingSource?.let { source ->
        GalleryAlertDialog(onDismissRequest = { tv.removingSource = null },
            title = { Text(localized(R.string.text_delete_permanently_8a62e5, "Elimina")) },
            text = { Text(source.name) },
            confirmButton = { TextButton({ tv.store.removeSource(source.id); tv.removingSource = null; tv.catalog = null }) { Text(localized(R.string.text_delete_permanently_8a62e5, "Elimina")) } },
            dismissButton = { TextButton({ tv.removingSource = null }) { Text(localized(R.string.text_cancel_18c9d9, "Annulla")) } })
        return
    }
    if (tv.addingSource) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        GalleryAlertDialog(onDismissRequest = { tv.addingSource = false }, modifier = Modifier.fillMaxWidth(.85f),
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            title = { Text(localized(R.string.tv_personal_source_add, "Aggiungi playlist")) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it.take(128) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(localized(R.string.text_name_508690, "Nome")) })
                OutlinedTextField(url, { url = it.take(8192) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("URL HTTPS · M3U / M3U8") })
                Text(localized(R.string.tv_personal_source_help, ""), style = MaterialTheme.typography.bodySmall)
                TextButton({ tv.addingSource = false; tv.adding = true }) { Text(localized(R.string.tv_add, "Aggiungi canale")) }
            } },
            confirmButton = { Button({ tv.store.addSource(name, url); tv.addingSource = false }, enabled = name.isNotBlank() && TvNetwork.validUrl(url.trim()) && tv.store.sources.size < 20) { Text(localized(R.string.text_save_c5997e, "Salva")) } },
            dismissButton = { TextButton({ tv.addingSource = false }) { Text(localized(R.string.text_cancel_18c9d9, "Annulla")) } })
        return
    }
    val runtime = remember { PadModalRuntime() }
    runtime.captureKey = { event ->
        val button = padButton(event.keyCode)
        val unrelated = button != null && button !in setOf(PadButton.A, PadButton.B, PadButton.LEFT, PadButton.RIGHT)
        if (unrelated) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                tv.abandonTransientActions()
                val prefs = app.preferences.state.value
                val action = prefs.pad.profile(event.device?.descriptor).bindings[PadContext.LIBRARY].orEmpty()
                    .entries.firstOrNull { it.value.buttons == setOf(button) }?.key
                if (action in setOf(PadAction.TAB_PREVIOUS, PadAction.TAB_NEXT)) {
                    val model = app.session.libraryModel() ?: activity.windowModel
                    val tabs = visibleNavbarTabs(prefs)
                    val category = if (model.route.value.tab == "TV" && !prefs.tvInNavbar) "VIDEO" else model.route.value.tab
                    val index = tabs.indexOfFirst { it.key == category }.coerceAtLeast(0)
                    model.go(tabs[Math.floorMod(index + if (action == PadAction.TAB_NEXT) 1 else -1, tabs.size)].key)
                } else if (action == PadAction.SWAP) app.session.swap()
            }
            true
        } else runtime.input?.key(event) ?: false
    }
    runtime.captureAction = { action ->
        if (action !in setOf(PadAction.LEFT, PadAction.RIGHT, PadAction.CONFIRM, PadAction.BACK)) { tv.abandonTransientActions(); true } else false
    }
    tv.pendingFavorite?.let { request ->
        val adding = request.second
        val confirm = remember { FocusRequester() }
        GalleryAlertDialog(onDismissRequest = { tv.pendingFavorite = null }, padRuntime = runtime, initialFocus = confirm,
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(.72f).testTag("tv-favorite-dialog"),
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            spreadButtons = true,
            title = { Text(localized(if (adding) R.string.tv_favorite_add else R.string.tv_favorite_remove, "I miei canali"),
                modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleLarge) },
            text = { Row(horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                GalleryIcon(R.drawable.ph_heart, null, Modifier.size(32.dp))
                Text(tv.store.channels.firstOrNull { it.matches(request.first) }?.name ?: request.first.name,
                    color = MaterialTheme.colorScheme.primary, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            } }, confirmButton = { Button({
                tv.pendingFavorite = null
                scope.launch { try { tv.store.favorite(request.first, adding); app.sounds.play("favorite") }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (failure: Exception) { tv.failure = tvError(failure) } }
            }, Modifier.focusRequester(confirm).testTag("tv-favorite-confirm")) { ControllerChoiceBadge("A"); Spacer(Modifier.width(8.dp)); Text(localized(R.string.text_confirm_662c0e, "Conferma")) } },
            dismissButton = { TextButton({ tv.pendingFavorite = null }) { ControllerChoiceBadge("B"); Spacer(Modifier.width(8.dp)); Text(localized(R.string.text_cancel_18c9d9, "Annulla")) } })
    }
    tv.sleepResume?.let { entry ->
        val confirm = remember { FocusRequester() }
        GalleryAlertDialog(onDismissRequest = { tv.sleepResume = null }, initialFocus = confirm,
            icon = { GalleryIcon(R.drawable.ph_television_simple, null) }, title = { Text(localized(R.string.tv_sleep_title, "TV in pausa")) },
            text = { Text(localized(R.string.tv_sleep_message, "Vuoi riconnetterti alla diretta?")) },
            confirmButton = { Button({ tv.sleepResume = null; tv.play(entry, app.session.previewActivity()?.slot ?: activity.slot, confirmed = true) }, Modifier.focusRequester(confirm).testTag("tv-sleep-resume")) {
                ControllerChoiceBadge("A"); Spacer(Modifier.width(8.dp)); Text(localized(R.string.video_resume_action, "Continua"))
            } }, dismissButton = { TextButton({ tv.sleepResume = null }) { ControllerChoiceBadge("B"); Spacer(Modifier.width(8.dp)); Text(localized(R.string.text_cancel_18c9d9, "Annulla")) } })
    }
    tv.pendingPlay?.let { request ->
        val confirm = remember { FocusRequester() }
        GalleryAlertDialog(onDismissRequest = { tv.pendingPlay = null }, soundCue = "video-replace", padRuntime = runtime, initialFocus = confirm,
            icon = { GalleryIcon(R.drawable.ph_television_simple, null) },
            title = { Text(localized(R.string.tv_replace_title, "Passare alla TV?")) },
            text = { Text(localized(R.string.tv_replace_description, "Il video attuale verrà chiuso per aprire questo canale. Vuoi continuare?")) },
            confirmButton = { Button({ tv.pendingPlay = null; tv.play(request.first, request.second, confirmed = true) }, Modifier.focusRequester(confirm).testTag("tv-replace-confirm")) {
                ControllerChoiceBadge("A"); Spacer(Modifier.width(8.dp)); Text(localized(R.string.video_resume_action, "Continua"))
            } }, dismissButton = { TextButton({ tv.pendingPlay = null }) { ControllerChoiceBadge("B"); Spacer(Modifier.width(8.dp)); Text(localized(R.string.text_cancel_18c9d9, "Annulla")) } })
    }
    tv.failure?.takeIf { tv.pendingFavorite == null && tv.pendingPlay == null && tv.sleepResume == null }?.let { message ->
        GalleryAlertDialog(onDismissRequest = { tv.failure = null },
            icon = { GalleryIcon(R.drawable.ph_info, null) }, title = { Text(localized(R.string.tv_title, "TV online")) },
            text = { Text(message) }, confirmButton = {
                TextButton({ tv.failure = null; tv.retryAction?.invoke() }, Modifier.testTag("tv-error-retry")) { Text(localized(R.string.tv_retry, "Riprova")) }
            }, dismissButton = { TextButton({ tv.failure = null }) { Text(localized(R.string.text_cancel_18c9d9, "Annulla")) } })
    }
}
