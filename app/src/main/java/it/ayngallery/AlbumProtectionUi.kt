package it.ayngallery

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun AlbumLockBadge(modifier: Modifier = Modifier, large: Boolean = false, unlockPending: Boolean = false, onUnlock: (() -> Unit)? = null) {
    var pressed by remember { mutableStateOf(false) }
    var requested by remember { mutableStateOf(false) }
    val opening = unlockPending || requested
    val threshold = with(androidx.compose.ui.platform.LocalDensity.current) { 28.dp.toPx() }
    val scale by androidx.compose.animation.core.animateFloatAsState(if (opening) 1.18f else if (pressed) 1.06f else 1f,
        androidx.compose.animation.core.tween(140), label = "album-unlock-pulse")
    val app = LocalContext.current.applicationContext as? GalleryApp
    val liveUnlock by rememberUpdatedState(onUnlock)
    val pending by rememberUpdatedState(unlockPending)
    LaunchedEffect(unlockPending) { if (!unlockPending) requested = false }
    LaunchedEffect(requested) {
        if (requested && !pending) {
            // Paint the open lock before opening the PIN on the companion panel.
            withFrameNanos { }
            withFrameNanos { }
            liveUnlock?.invoke()
        }
    }
    Box(modifier.size(if (large) 108.dp else 58.dp)
        .then(if (onUnlock != null) Modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                pressed = true
                if (!pending) app?.sounds?.play("settings")
                try {
                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!pending && !requested && (change.position - down.position).getDistance() >= threshold) requested = true
                        change.consume()
                    } while (event.changes.any { it.id == down.id && it.pressed })
                } finally { pressed = false }
            }
        } else Modifier).graphicsLayer { scaleX = scale; scaleY = scale }, contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = .28f), CircleShape))
        Surface(Modifier.size(if (large) 88.dp else 48.dp), shape = CircleShape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = .9f)) {
            Box(contentAlignment = Alignment.Center) {
                androidx.compose.runtime.CompositionLocalProvider(LocalContentColor provides
                    if (opening || pressed) androidx.compose.ui.graphics.Color(0xFF62DE91) else MaterialTheme.colorScheme.onSurface) {
                    GalleryIcon(if (opening) R.drawable.ph_lock_open else R.drawable.ph_lock,
                        localized(R.string.protected_album, "Album protetto"), Modifier.size(if (large) 48.dp else 28.dp).testTag(if (opening) "album-lock-open" else "album-lock-closed"))
                }
            }
        }
    }
}

internal val LocalAnimatedAlbumIds = androidx.compose.runtime.compositionLocalOf { emptySet<String>() }

@Composable
internal fun AlbumCover(album: AlbumEntity, data: CatalogSnapshot, catalog: Catalog, prefs: GalleryPreferences, modifier: Modifier, reorderScale: Float? = null, reorderSelected: Boolean = false) {
    val locked = data.albumLocked(album.id)
    Box(modifier) {
        val animated = album.id in LocalAnimatedAlbumIds.current
        MediaThumbnail(if (locked) data.lockedCover(album) else data.cover(album), data, catalog, prefs, Modifier.fillMaxSize(),
            animated = prefs.animatedAlbumCovers && !locked && animated, lockedAlbumCover = album.id.takeIf { locked })
        if (album.id in data.protectedAlbumIds || locked) AlbumLockBadge(Modifier.align(Alignment.Center))
        if (reorderScale != null) Surface(Modifier.align(Alignment.Center).size(52.dp)
            .graphicsLayer { scaleX = reorderScale; scaleY = reorderScale }.testTag("album-swap-marker:${album.id}"),
            shape = CircleShape, color = MaterialTheme.colorScheme.surface.copy(alpha = .96f),
            border = androidx.compose.foundation.BorderStroke(2.dp,
                if (reorderSelected) androidx.compose.ui.graphics.Color(0xFF69FFF1) else MaterialTheme.colorScheme.primary)) {
            Box(contentAlignment = Alignment.Center) { GalleryIcon(R.drawable.ph_arrows_left_right, null, Modifier.size(24.dp)) }
        }
    }
}

@Composable
internal fun AlbumPinDialog(protection: AlbumProtection, mode: String, ids: Set<String> = emptySet(), dismiss: () -> Unit, accepted: () -> Unit) {
    var previous by remember { mutableStateOf<String?>(null) }
    val creating = mode == "SETUP" || mode == "CHANGE" && previous != null || !protection.configured && mode in setOf("PROTECT", "UNLOCK")
    var pin by remember { mutableStateOf("") }
    var first by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var remaining by remember { mutableIntStateOf(protection.waitSeconds()) }
    val scope = rememberCoroutineScope()
    val app = LocalContext.current.applicationContext as? GalleryApp
    var confirmedKey by remember { mutableStateOf<String?>(null) }
    var keyFeedbackRevision by remember { mutableIntStateOf(0) }
    val reducedMotion = app?.preferences?.state?.collectAsState()?.value?.reducedMotion == true
    val firstKeyFocus = remember { FocusRequester() }
    var padActive by remember { mutableStateOf(false) }
    var initialNativeKey by remember { mutableStateOf<Int?>(null) }
    val inputMode = LocalInputModeManager.current
    LaunchedEffect(inputMode.inputMode) { if (inputMode.inputMode == InputMode.Touch) padActive = false }
    LaunchedEffect(keyFeedbackRevision) {
        if (confirmedKey != null) { delay(180); confirmedKey = null }
    }
    LaunchedEffect(remaining) { if (remaining > 0) { delay(1000); remaining = protection.waitSeconds() } }
    suspend fun rejectPin() {
        error = true
        app?.sounds?.play("unlock-error")
        while (pin.isNotEmpty()) { delay(75); pin = pin.dropLast(1) }
        remaining = protection.waitSeconds()
    }
    fun submit() {
        if (pin.length != 4 || busy || remaining > 0) return
        if (creating && first == null) { first = pin; pin = ""; error = false; return }
        if (creating && pin != first) { first = null; busy = true; scope.launch { try { rejectPin() } finally { busy = false } }; return }
        val entered = pin; error = false; busy = true
        scope.launch {
            try {
                val success = when (mode) {
                    "SETUP", "CHANGE" -> if (mode == "CHANGE" && previous == null) {
                        if (protection.authorize(entered)) { previous = entered; true } else false
                    } else protection.configure(entered, previous)
                    "PROTECT" -> protection.protect(ids, entered)
                    "REMOVE" -> protection.unprotect(ids, entered)
                    else -> { if (!protection.configured) protection.configure(entered); protection.unlock(entered) }
                }
                if (success) { pin = ""; if (!(mode == "CHANGE" && !creating)) { app?.sounds?.play("unlock"); accepted() } }
                else rejectPin()
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) {
                error = true
                app?.session?.fail(failure.message ?: localized(R.string.text_settings_not_saved_d67a68, "Impostazioni non salvate"))
            } finally { busy = false }
        }
    }
    fun pressKey(digit: String) {
        if (busy || remaining > 0) return
        // Keep a visible acknowledgement even when press and release occur in one frame.
        confirmedKey = digit; keyFeedbackRevision++
        if (digit == "⌫") pin = pin.dropLast(1) else if (pin.length < 4) {
            if (pin.isEmpty()) error = false
            pin += digit
            app?.sounds?.play("pin-digit-${pin.length}")
            if (pin.length == 4) submit()
        }
    }
    fun beginPad(confirm: Boolean) {
        if (busy || remaining > 0) return
        padActive = true
        inputMode.requestInputMode(InputMode.Keyboard)
        scope.launch {
            // Apply canFocus before requesting the first keypad button.
            withFrameNanos { }
            firstKeyFocus.requestFocus()
            if (confirm) pressKey("1")
        }
    }
    @Composable fun introduction(modifier: Modifier = Modifier) {
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(localized(R.string.pin_hint, "Quattro cifre per i tuoi album. La copertina resta visibile."), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(vertical = 4.dp).testTag("pin-dots")) {
                repeat(4) { index ->
                    val scale by androidx.compose.animation.core.animateFloatAsState(if (error && index >= pin.length) .45f else 1f, androidx.compose.animation.core.tween(110), label = "pin-reject:$index")
                    val color by androidx.compose.animation.animateColorAsState(if (error) MaterialTheme.colorScheme.error else if (index < pin.length) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        androidx.compose.animation.core.tween(100), label = "pin-color:$index")
                    Box(Modifier.size(14.dp).graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (error && index >= pin.length) .25f else 1f }.background(color, CircleShape))
                }
            }
            Box(Modifier.fillMaxWidth().height(48.dp).testTag("pin-status"), contentAlignment = Alignment.Center) {
                if (remaining > 0) Text(localized(R.string.pin_wait, "Riprova tra %1\$d s", remaining), color = MaterialTheme.colorScheme.error)
                else if (error) Text(localized(R.string.pin_wrong, "PIN non corretto. Riprova."), color = MaterialTheme.colorScheme.error)
                else if (busy) CircularProgressIndicator(Modifier.size(24.dp))
            }
        }
    }
    @Composable fun keypad(modifier: Modifier = Modifier) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("", "0", "⌫")).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { digit ->
                        if (digit.isEmpty()) Spacer(Modifier.weight(1f))
                        else {
                        val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                        val pressed by interaction.collectIsPressedAsState()
                        val focused by interaction.collectIsFocusedAsState()
                        val highlighted = pressed || confirmedKey == digit
                        val keyScale by androidx.compose.animation.core.animateFloatAsState(if (highlighted && !reducedMotion) .92f else 1f,
                            androidx.compose.animation.core.tween(if (reducedMotion) 0 else if (highlighted) 60 else 120), label = "pin-key-scale")
                        val background by androidx.compose.animation.animateColorAsState(
                            if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            androidx.compose.animation.core.tween(if (reducedMotion) 0 else if (highlighted) 45 else 120), label = "pin-key-color")
                        val ink = if (highlighted) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                        OutlinedButton(onClick = { pressKey(digit) },
                            interactionSource = interaction, modifier = Modifier.weight(1f).height(58.dp)
                                .then(if (digit == "1") Modifier.focusRequester(firstKeyFocus) else Modifier)
                                .focusProperties { canFocus = padActive }
                                .graphicsLayer { scaleX = keyScale; scaleY = keyScale }.testTag("pin:$digit"), enabled = !busy && remaining == 0,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                            border = androidx.compose.foundation.BorderStroke(if (highlighted || focused) 3.dp else 1.dp,
                                if (highlighted) MaterialTheme.colorScheme.primary else if (focused) GalleryFocus else MaterialTheme.colorScheme.outline),
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = background, contentColor = ink,
                                disabledContainerColor = if (highlighted) background else androidx.compose.ui.graphics.Color.Transparent,
                                disabledContentColor = if (highlighted) ink else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f)),
                            contentPadding = PaddingValues(4.dp)) { Text(digit, style = MaterialTheme.typography.titleLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) }
                        }
                    }
                }
            }
        }
    }
    val runtime = remember { PadModalRuntime() }
    runtime.captureAction = { action ->
        when {
            action == PadAction.BACK -> { if (!busy) { if (pin.isNotEmpty()) pin = pin.dropLast(1) else dismiss() }; true }
            !padActive && action in setOf(PadAction.UP, PadAction.DOWN, PadAction.LEFT, PadAction.RIGHT, PadAction.CONFIRM) -> { beginPad(action == PadAction.CONFIRM); true }
            else -> false
        }
    }
    runtime.captureKey = { event ->
        val nativeEntry = event.keyCode in setOf(android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN,
            android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT, android.view.KeyEvent.KEYCODE_DPAD_CENTER,
            android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_NUMPAD_ENTER) &&
            !event.isFromSource(android.view.InputDevice.SOURCE_GAMEPAD) && !event.isFromSource(android.view.InputDevice.SOURCE_JOYSTICK) &&
            event.device?.supportsSource(android.view.InputDevice.SOURCE_GAMEPAD) != true
        if (initialNativeKey == event.keyCode && event.action == android.view.KeyEvent.ACTION_UP) {
            initialNativeKey = null; true
        } else if (!padActive && nativeEntry && event.action == android.view.KeyEvent.ACTION_DOWN) {
            initialNativeKey = event.keyCode
            beginPad(event.keyCode in setOf(android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)); true
        } else runtime.input?.key(event) ?: false
    }
    GalleryAlertDialog(onDismissRequest = { if (!busy) dismiss() }, padRuntime = runtime, autoFocus = false,
        title = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GalleryIcon(R.drawable.ph_lock, null, Modifier.size(28.dp))
            Text(localized(if (creating && first != null) R.string.pin_confirm else if (creating) R.string.pin_create else R.string.pin_enter,
                if (creating && first != null) "Ripeti il PIN" else if (creating) "Crea il tuo PIN" else "Inserisci il PIN"))
        } },
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(.9f).testTag("album-pin-dialog"),
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        text = { BoxWithConstraints(Modifier.fillMaxWidth().heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
            if (maxWidth >= 360.dp) Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                introduction(Modifier.weight(1f)); keypad(Modifier.weight(1f))
            } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { introduction(); keypad() }
        } }, confirmButton = {}, dismissButton = { TextButton(onClick = dismiss, enabled = !busy,
            modifier = Modifier.focusProperties { canFocus = padActive }) { Text(localized(R.string.text_cancel_18c9d9, "Annulla")) } })
}
