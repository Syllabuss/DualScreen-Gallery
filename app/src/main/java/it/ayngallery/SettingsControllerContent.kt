package it.ayngallery

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged

@Composable
internal fun SettingsControllerContent(activity: PanelActivity, page: String, section: String, content: @Composable () -> Unit) {
    if (activity.session.settingsOwner != activity.slot && !page.startsWith("SETTINGS") &&
        page !in setOf("CONTROLS", "TOUCH", "DISPLAY", "MAINTENANCE", "TRANSFER")) { content(); return }
    val requester = remember(page, section) { FocusRequester() }
    var hasFocus by remember(page, section) { mutableStateOf(false) }
    var hubArmed by remember(page) { mutableStateOf(page != "SETTINGS_HUB" || activity.session.lastSettingsCategory != null) }
    DisposableEffect(activity, page) {
        val activate: () -> Boolean = {
            if (hubArmed) false else { hubArmed = true; requester.requestFocus(); true }
        }
        activity.armSettingsFocus = activate
        onDispose { if (activity.armSettingsFocus === activate) activity.armSettingsFocus = null }
    }
    LaunchedEffect(page, section) {
        if (page != "SETTINGS_HUB") { androidx.compose.runtime.withFrameNanos { }; requester.requestFocus() }
    }
    DisposableEffect(activity, page, section) {
        val acquire: () -> Unit = { if (!hasFocus && !activity.settingsHeaderHasFocus) requester.requestFocus() }
        activity.ensurePadFocus = acquire
        onDispose { if (activity.ensurePadFocus === acquire) activity.ensurePadFocus = null }
    }
    GalleryGridFocus { Box(Modifier.fillMaxSize().focusRequester(requester).onFocusChanged { hasFocus = it.hasFocus }.focusGroup()) { content() } }
}

/** Once focus reaches the last control, Down also reveals its trailing notes and padding. */
@Composable
internal fun SettingsScrollEnd(canScroll: () -> Boolean, scroll: suspend () -> Unit) {
    val activity = androidx.activity.compose.LocalActivity.current as? PanelActivity
    val scope = rememberCoroutineScope()
    val currentCanScroll by rememberUpdatedState(canScroll)
    val currentScroll by rememberUpdatedState(scroll)
    DisposableEffect(activity) {
        val reveal = { if (currentCanScroll()) { scope.launch { currentScroll() }; true } else false }
        activity?.settingsScrollEnd = reveal
        onDispose { if (activity?.settingsScrollEnd === reveal) activity.settingsScrollEnd = null }
    }
}
