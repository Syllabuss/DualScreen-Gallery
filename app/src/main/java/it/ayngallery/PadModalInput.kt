package it.ayngallery

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalInputModeManager

internal class PadModalRuntime {
    var context = PadContext.DIALOG
    var captureAction: ((PadAction) -> Boolean)? = null
    var input: PadWindowInput? = null
    var owner: PanelActivity? = null
    var hasFocus: (() -> Boolean)? = null
    var captureKey: ((KeyEvent) -> Boolean)? = null
    var captureMotion: ((android.view.MotionEvent) -> Boolean)? = null
    var clearCapture: (() -> Unit)? = null
    var enterPadMode: (() -> Unit)? = null
    fun motion(event: android.view.MotionEvent): Boolean {
        owner?.let { activity ->
            val target = activity.session.controllerTarget(activity)
            if (target !== activity) return target?.receiveControllerMotion(event) ?: true
        }
        enterPadMode?.invoke()
        return captureMotion?.invoke(event) ?: input?.motion(event) ?: false
    }
    fun key(event: KeyEvent): Boolean {
        owner?.let { activity ->
            val target = activity.session.controllerTarget(activity)
            if (target !== activity) {
                if (padButton(event.keyCode) == null) return false
                return target?.receiveControllerKey(event) ?: true
            }
        }
        if (padButton(event.keyCode) != null) enterPadMode?.invoke()
        return captureKey?.invoke(event) ?: input?.key(event) ?: false
    }
}

/** Registered inside the native modal's composition, so its focus owner is never the underlying page. */
@Composable
internal fun PadModalRegistration(runtime: PadModalRuntime, dismiss: () -> Unit, rootFocus: FocusRequester, autoFocus: Boolean = true, initialFocus: FocusRequester? = null) {
    val focus = LocalFocusManager.current
    val view = LocalView.current
    val inputMode = LocalInputModeManager.current
    val app = LocalContext.current.applicationContext as? GalleryApp ?: return
    val owner = generateSequence(LocalContext.current) { (it as? android.content.ContextWrapper)?.baseContext }
        .filterIsInstance<PanelActivity>().firstOrNull()
    val dismissNow = rememberUpdatedState(dismiss)
    LaunchedEffect(rootFocus, autoFocus, initialFocus) {
        if (autoFocus) inputMode.requestInputMode(InputMode.Keyboard)
        androidx.compose.runtime.withFrameNanos { }
        // A native popup may have closed during a display swap before this frame.
        try {
            if (initialFocus != null) initialFocus.requestFocus()
            else { rootFocus.requestFocus(); if (autoFocus) focus.moveFocus(FocusDirection.Enter) }
        }
        catch (_: IllegalStateException) { return@LaunchedEffect }
    }
    DisposableEffect(runtime, focus, view, app, inputMode, owner) {
        val previous = owner?.controllerModal
        owner?.controllerModal = runtime
        runtime.owner = owner; runtime.hasFocus = { view.hasWindowFocus() }
        runtime.enterPadMode = { inputMode.requestInputMode(InputMode.Keyboard) }
        fun step(direction: FocusDirection) { if (focus.moveFocus(direction)) app.sounds.play("settings-navigation") }
        val input = PadWindowInput({ app.preferences.state.value.pad }, { runtime.context }, emit = { action ->
            if (runtime.captureAction?.invoke(action) != true && owner?.sliderAction?.invoke(action) != true) when (action) {
                PadAction.UP -> step(FocusDirection.Up)
                PadAction.DOWN -> step(FocusDirection.Down)
                PadAction.LEFT -> step(FocusDirection.Left)
                PadAction.RIGHT -> step(FocusDirection.Right)
                PadAction.CONFIRM -> {
                    view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
                    view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
                }
                PadAction.BACK -> dismissNow.value()
                else -> Unit // No media, music, deletion or display command crosses a modal.
            }
        })
        runtime.input = input
        view.setOnGenericMotionListener { _, event ->
            if (!view.hasWindowFocus()) false else {
                if (event.isFromSource(android.view.InputDevice.SOURCE_JOYSTICK) || event.isFromSource(android.view.InputDevice.SOURCE_GAMEPAD)) runtime.enterPadMode?.invoke()
                runtime.motion(event)
            }
        }
        val handler = Handler(Looper.getMainLooper())
        var hadFocus = false
        val repeat = object : Runnable {
            override fun run() {
                if ((owner == null || app.session.controllerTarget(owner) === owner) &&
                    (view.hasWindowFocus() || owner?.let(app.session::receivesController) == true)) { input.poll(); hadFocus = true } else {
                    input.clear()
                    if (hadFocus) { runtime.clearCapture?.invoke(); hadFocus = false }
                }
                handler.postDelayed(this, 20)
            }
        }
        handler.post(repeat)
        onDispose {
            if (owner?.controllerModal === runtime) owner.controllerModal = previous
            handler.removeCallbacks(repeat); input.clear(); runtime.input = null; runtime.owner = null; runtime.hasFocus = null; runtime.enterPadMode = null; view.setOnGenericMotionListener(null)
        }
    }
}

@Composable
internal fun GalleryAlertDialog(onDismissRequest: () -> Unit, confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier, dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null, title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null, padRuntime: PadModalRuntime? = null, autoFocus: Boolean = true,
    properties: androidx.compose.ui.window.DialogProperties = androidx.compose.ui.window.DialogProperties(), soundCue: String = "notice", initialFocus: FocusRequester? = null,
    containerColor: androidx.compose.ui.graphics.Color = AlertDialogDefaults.containerColor, spreadButtons: Boolean = false) {
    val soundApp = androidx.compose.ui.platform.LocalContext.current.applicationContext as? GalleryApp
    androidx.compose.runtime.LaunchedEffect(soundCue) { soundApp?.sounds?.play(soundCue) }
    val ownRuntime = remember { PadModalRuntime() }
    val runtime = padRuntime ?: ownRuntime
    val rootFocus = remember { FocusRequester() }
    val buttons: @Composable () -> Unit = {
        if (spreadButtons) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            dismissButton?.invoke(); Spacer(Modifier.weight(1f)); confirmButton()
        } else confirmButton()
    }
    androidx.compose.material3.AlertDialog(onDismissRequest, buttons,
        modifier.onPreviewKeyEvent { runtime.key(it.nativeKeyEvent) }.focusRequester(rootFocus).focusable(), if (spreadButtons) null else dismissButton, icon,
        title = { PadModalRegistration(runtime, onDismissRequest, rootFocus, autoFocus, initialFocus); title?.invoke() }, text = text, properties = properties, containerColor = containerColor)
}

@Composable
internal fun GalleryDropdownMenu(expanded: Boolean, onDismissRequest: () -> Unit, modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit) {
    val runtime = remember { PadModalRuntime() }
    val rootFocus = remember { FocusRequester() }
    val soundApp = androidx.compose.ui.platform.LocalContext.current.applicationContext as? GalleryApp
    androidx.compose.runtime.LaunchedEffect(expanded) { if (expanded) soundApp?.sounds?.play("notice") }
    androidx.compose.material3.DropdownMenu(expanded, onDismissRequest,
        modifier.onPreviewKeyEvent { runtime.key(it.nativeKeyEvent) }.focusRequester(rootFocus).focusable()) {
        PadModalRegistration(runtime, onDismissRequest, rootFocus)
        content()
    }
}

@Composable
internal fun GallerySideSheet(dismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val soundApp = androidx.compose.ui.platform.LocalContext.current.applicationContext as? GalleryApp
    androidx.compose.runtime.LaunchedEffect(Unit) { soundApp?.sounds?.play("settings") }
    val runtime = remember { PadModalRuntime() }
    val rootFocus = remember { FocusRequester() }
    androidx.compose.ui.window.Dialog(dismiss, properties = androidx.compose.ui.window.DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().testTag("preview-dismiss-area").clickable(onClick = dismiss))
            Surface(Modifier.align(Alignment.CenterEnd).widthIn(max = 420.dp).fillMaxWidth(.85f).fillMaxHeight()
                .testTag("preview-side-sheet")
                .onPreviewKeyEvent { runtime.key(it.nativeKeyEvent) }.focusRequester(rootFocus).focusable(),
                color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PadModalRegistration(runtime, dismiss, rootFocus)
                    content()
                }
            }
        }
    }
}
