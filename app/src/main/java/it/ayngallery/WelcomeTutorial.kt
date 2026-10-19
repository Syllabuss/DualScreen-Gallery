// DS Gallery - https://github.com/Syllabuss/DualScreen-Gallery
package it.ayngallery

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import android.view.KeyEvent
import android.net.Uri
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WelcomeTutorial(activity: PanelActivity, finish: () -> Unit) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val app = activity.application as GalleryApp
    val prefs by app.preferences.state.collectAsStateWithLifecycle()
    val data by app.catalog.snapshot.collectAsStateWithLifecycle()
    val sources = data.sources.filter { it.enabled && it.id != DeviceGallery.SOURCE && !it.singleDocument && it.restoreUri == null }
        .distinctBy { DocumentIdentity.key(Uri.parse(it.treeUri), true) }
    val connected = sources.isNotEmpty()
    val runtime = remember { PadModalRuntime() }
    val screenHeight = LocalConfiguration.current.screenHeightDp
    val scope = rememberCoroutineScope()
    var folderRole by remember { mutableStateOf<SourceRole?>(null) }
    val bodyHeight = (screenHeight - 272).coerceIn(200, 390).dp
    val nextFocus = remember { FocusRequester() }
    var entered by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    val languageFade = remember { Animatable(1f) }
    var leftHeld by remember { mutableStateOf(false) }
    var rightHeld by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        app.preferences.beginTutorial()
        app.session.tutorialInProgress = true
        app.session.tutorialLeaving = false
        entered = true
        if (app.sounds.awaitReady("tutorial-open") && page == 0 && !leaving) app.sounds.play("tutorial-open")
    }
    val appearance by animateFloatAsState(if (entered && !leaving) 1f else 0f,
        tween(if (prefs.reducedMotion) 0 else if (leaving) 140 else 240), label = "tutorial-popup")
    fun done() {
        if (leaving) return
        leaving = true
        if (page == 7) app.sounds.play("tutorial-complete")
        app.session.tutorialLeaving = true
        app.session.clearVisualSelection(); app.session.selectMedia(null)
        (app.session.libraryModel() ?: activity.windowModel).go("ALBUM")
        app.session.previewActivity()?.windowModel?.restorePanelRole()
        scope.launch {
            if (!prefs.reducedMotion) delay(450)
            app.preferences.completeTutorial(); app.session.tutorialInProgress = false; app.session.tutorialLeaving = false; finish()
        }
    }
    fun previous() { if (page > 0) { app.sounds.play("tutorial-previous"); page-- } }
    fun next() { if (page < 7) { app.sounds.play(if (page == 0) "tutorial-next-1" else "tutorial-next-2"); page++ } }
    DisposableEffect(runtime, page) {
        runtime.captureAction = { it == PadAction.BACK }
        runtime.captureKey = { event ->
            if (event.keyCode == KeyEvent.KEYCODE_BUTTON_L1 || event.keyCode == KeyEvent.KEYCODE_BUTTON_R1) {
                val right = event.keyCode == KeyEvent.KEYCODE_BUTTON_R1
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    if (right) rightHeld = true else leftHeld = true
                } else if (event.action == KeyEvent.ACTION_UP) {
                    val wasHeld = if (right) rightHeld else leftHeld
                    if (right) rightHeld = false else leftHeld = false
                    if (wasHeld && !event.isCanceled && !leaving) {
                        if (right) { if (page < 7) next() else done() } else if (page > 0) previous()
                    }
                }
                true
            } else runtime.input?.key(event) == true
        }
        onDispose { runtime.captureKey = null; runtime.captureAction = null }
    }
    GalleryAlertDialog(onDismissRequest = {}, initialFocus = nextFocus, padRuntime = runtime,
        modifier = Modifier.fillMaxWidth(.94f).heightIn(max = (screenHeight - 64).dp).graphicsLayer {
            alpha = appearance * languageFade.value; scaleX = .96f + appearance * .04f; scaleY = scaleX
        }.testTag("welcome-tutorial"),
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false),
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val heading = when (page) {
                1 -> R.drawable.ph_translate to localized(R.string.language_title, "Lingua")
                2 -> R.drawable.ph_game_controller to localized(R.string.tutorial_shortcuts_title, "Due schermi, tutto a portata di mano")
                3 -> R.drawable.ph_images to localized(R.string.onboarding_images, "Anteprima delle immagini")
                4 -> R.drawable.ph_video_camera to MainTabs.first { it.key == "VIDEO" }.title
                5 -> R.drawable.ph_arrows_down_up to localized(R.string.text_touch_gestures_241cad, "Gesti touch")
                6 -> R.drawable.ph_folders to localized(R.string.tutorial_folders_title, "Collega le tue cartelle")
                7 -> R.drawable.ph_checks to localized(R.string.tutorial_ready, "È tutto pronto!")
                else -> null
            }
            if (heading != null) TutorialHeading(heading.first, heading.second, Modifier.weight(1f))
            else Spacer(Modifier.weight(1f))
            if (page != 7) TextButton({ app.sounds.play("tutorial-skip"); done() }, Modifier.testTag("tutorial-skip")) { Text(localized(R.string.tutorial_skip, "Salta tutorial")) }
        } },
        text = { Column {
            AnimatedContent(page, transitionSpec = {
                (fadeIn(tween(if (prefs.reducedMotion) 0 else 220)) + scaleIn(initialScale = .97f)) togetherWith
                    fadeOut(tween(if (prefs.reducedMotion) 0 else 120))
            }, label = "tutorial-step") { shownPage ->
                Column(Modifier.fillMaxWidth().height(bodyHeight).padding(top = 6.dp).testTag("tutorial-page:$shownPage"),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(if (shownPage == 6) 4.dp else 8.dp, Alignment.CenterVertically)) {
                    when (shownPage) {
                        0 -> {
                            Image(painterResource(R.drawable.app_brand), null, Modifier.size(120.dp))
                            Text(localized(R.string.text_welcome_to_thor_gallery_f22b0e, "Benvenuto in DS Gallery"), style = MaterialTheme.typography.headlineMedium)
                        }
                        1 -> {
                            val languages = AppLanguage.choices
                            val names = listOf(localized(R.string.language_system, "Lingua del dispositivo"), "Italiano", "English", "Русский", "한국어", "简体中文", "日本語", "Deutsch", "Français", "Português (Brasil)", "Español")
                            PlaybackChoice(localized(R.string.language_title, "Lingua"), languages.indexOf(AppLanguage.current(app)).coerceAtLeast(0), names.mapIndexed { i, name -> i to name }.toMap(), "tutorial-language") { index -> scope.launch {
                                languageFade.animateTo(0f, tween(if (prefs.reducedMotion) 0 else 120))
                                AppLanguage.select(app, languages[index])
                                withFrameNanos { }
                                languageFade.animateTo(1f, tween(if (prefs.reducedMotion) 0 else 220))
                            } }
                            val themes = GalleryThemes.all.filter { it.family == "AYN" }.sortedBy { it.id != prefs.themeId }
                            PlaybackChoice(localized(R.string.text_appearance_f9ce8f, "Tema"), themes.indexOfFirst { it.id == prefs.themeId }.coerceAtLeast(0), themes.mapIndexed { i, theme -> i to theme.name }.toMap(), "tutorial-theme") { index -> scope.launch { app.preferences.update { it.copy(themeId = themes[index].id) } } }
                            TutorialNote(localized(R.string.tutorial_setup_hint, "Puoi cambiare lingua e tema in qualsiasi momento nelle impostazioni."))
                        }
                        2 -> {
                            TutorialGeneralShortcuts(activity, prefs)

                        }
                        3 -> {
                            TutorialControlGrid(listOf(
                                "LS" to localized(R.string.tutorial_pan, "Sposta l’immagine ingrandita"),
                                "RS" to localized(R.string.gallery_background_zoom, "Zoom"),
                                "A" to localized(R.string.tutorial_overlay, "Mostra o nascondi i comandi"),
                                "← →" to localized(R.string.tutorial_swipe_media, "Scorri con un dito per cambiare media")))
                            TutorialNote(localized(R.string.tutorial_double_tap_zoom, "Doppio tocco sull’immagine: zoom avanti o indietro."))
                        }
                        4 -> {
                            TutorialControlGrid(listOf(
                                "LS ↑↓" to localized(R.string.video_volume, "Volume"),
                                "LS ←→" to localized(R.string.tutorial_seek, "Indietro / avanti nel video"),
                                "A" to localized(R.string.tutorial_play_pause, "Play / pausa"),
                                "← →" to localized(R.string.tutorial_double_tap_seek, "Due tocchi ai lati per saltare")), info = localized(R.string.console_stick_body, "In Video e Musica, stick destro su/giù: luminosità di sistema; sinistra/destra: volume della console. Nelle immagini resta lo zoom."))
                        }
                        5 -> {
                            Text(localized(R.string.tutorial_gesture_hint, "Con tre dita: scorri nella direzione indicata o tocca senza trascinare."), style = MaterialTheme.typography.bodySmall)
                            val gestures = prefs.touch.threeFinger[PadContext.LIBRARY].orEmpty().filterValues { it != TouchAction.NONE }
                            fun symbol(direction: TouchDirection) = when (direction) { TouchDirection.UP -> "↑"; TouchDirection.DOWN -> "↓"; TouchDirection.LEFT -> "←"; TouchDirection.RIGHT -> "→"; TouchDirection.TAP -> "TAP" }
                            TutorialControlGrid(gestures.map { (direction, action) -> symbol(direction) to action.title }, endIcons = gestures.map { (direction, action) -> symbol(direction) to when (action) {
                                TouchAction.SWAP -> R.drawable.ph_arrows_down_up
                                TouchAction.TAB_PREVIOUS -> R.drawable.ph_caret_double_left
                                TouchAction.TAB_NEXT -> R.drawable.ph_caret_double_right
                                TouchAction.DIM -> R.drawable.ph_moon_stars
                                else -> R.drawable.ph_gear_six
                            } }.toMap())
                        }
                        6 -> {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(localized(R.string.tutorial_folders_selected, "%1\$d cartelle selezionate", sources.size),
                                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall,
                                    modifier = Modifier.testTag("tutorial-folder-count"))
                                val help = rememberTooltipState(isPersistent = true)
                                TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(), state = help,
                                    tooltip = { PlainTooltip { Text(localized(R.string.tutorial_recap_empty, "Potrai collegare altre cartelle quando vuoi dalle impostazioni.")) } }) {
                                    IconButton({ scope.launch { help.show() } }, Modifier.size(32.dp)) {
                                        GalleryIcon(R.drawable.ph_info, localized(R.string.tutorial_control_help, "Dettagli comando"), Modifier.size(20.dp))
                                    }
                                }
                            }
                            if (connected) TutorialFolderRecap(sources, data, app, prefs)
                            Spacer(Modifier.weight(1f))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                fun choose(role: SourceRole) {
                                    if (android.os.Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager()) folderRole = role
                                    else activity.chooseSource(role, android.os.Build.VERSION.SDK_INT >= 30)
                                }
                                GalleryButton(localized(R.string.tutorial_visual_folder, "Foto e video"), Modifier.weight(1f).height(48.dp).testTag("welcome-choose-folder"), primary = true, icon = R.drawable.ph_images) { choose(SourceRole.VISUAL) }
                                GalleryButton(localized(R.string.text_music_012df9, "Musica"), Modifier.weight(1f).height(48.dp).testTag("welcome-choose-music"), icon = R.drawable.ph_music_notes) { choose(SourceRole.MUSIC) }
                            }
                        }
                        else -> {
                            TutorialReady(prefs)
                        }
                    }
                }
            }
        } },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally)) {
                    repeat(8) { index -> Box(Modifier.size(8.dp).background(if (index <= page) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape).testTag("tutorial-dot:$index:${index <= page}")) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    if (page > 0) TextButton(::previous, Modifier.testTag("tutorial-previous")) {
                        TutorialShoulderBadge("L1", leftHeld); Spacer(Modifier.width(8.dp)); Text(localized(R.string.tutorial_previous, "Precedente"))
                    } else Spacer(Modifier.width(1.dp))
                    TextButton({ if (page < 7) next() else done() }, Modifier.focusRequester(nextFocus).focusProperties { canFocus = true }.testTag("tutorial-next")) {
                        if (page == 7) {
                            CompositionLocalProvider(LocalContentColor provides if (MaterialTheme.colorScheme.surface.luminance() > .35f) Color(0xFFA6095C) else Color(0xFF64DD96)) {
                                GalleryIcon(R.drawable.ph_checks, null); Spacer(Modifier.width(8.dp)); Text(localized(R.string.tutorial_begin, "Iniziamo"))
                                Spacer(Modifier.width(8.dp)); TutorialShoulderBadge("R1", rightHeld)
                            }
                        } else {
                            Text(localized(if (page == 6 && !connected) R.string.onboarding_later else R.string.tutorial_next, if (page == 6 && !connected) "Dopo" else "Prossimo"))
                            Spacer(Modifier.width(8.dp)); TutorialShoulderBadge("R1", rightHeld)
                        }
                    }
                }
            }
        })
    folderRole?.let { role -> AddFolderGuidanceDialog(
        continueAction = { folderRole = null; activity.chooseWholeStorage(role) },
        dismiss = { folderRole = null }) }
}

@Composable
private fun TutorialHeading(icon: Int, title: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(shape = GalleryShape, color = MaterialTheme.colorScheme.primaryContainer) { GalleryIcon(icon, null, Modifier.padding(8.dp).size(24.dp)) }
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun TutorialNote(text: String) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, GalleryShape).padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GalleryIcon(R.drawable.ph_info, null, Modifier.size(20.dp)); Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun TutorialGeneralShortcuts(activity: PanelActivity, prefs: GalleryPreferences) {
    val bindings = prefs.pad.profile(activity.lastPadDevice?.descriptor).bindings[PadContext.LIBRARY].orEmpty()
    listOf(PadAction.GRID_SMALLER to "■", PadAction.GRID_LARGER to "▶", PadAction.SWAP to "Y", PadAction.SETTINGS to "X", PadAction.FRAME to "L3", PadAction.TOGGLE_VIEW to "R3").chunked(2).forEach { pair ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            pair.forEach { (action, fallback) ->
                val key = bindings[action]?.buttons?.joinToString(" + ") { when (it) { PadButton.SELECT -> "■"; PadButton.START -> "▶"; else -> it.name } } ?: fallback
                Row(Modifier.weight(1f).height(48.dp).background(MaterialTheme.colorScheme.surfaceVariant, GalleryShape).border(1.dp, MaterialTheme.colorScheme.outlineVariant, GalleryShape).padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ControllerChoiceBadge(key)
                    Text(if (action == PadAction.SETTINGS) localized(R.string.tutorial_actions_short, "Azioni e impostazioni") else action.title, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TutorialControlGrid(items: List<Pair<String, String>>, info: String? = null, endIcons: Map<String, Int> = emptyMap()) {
    items.chunked(2).forEach { pair ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            pair.forEach { (key, label) ->
                Box(Modifier.weight(1f).background(MaterialTheme.colorScheme.surfaceVariant, GalleryShape).border(1.dp, MaterialTheme.colorScheme.outlineVariant, GalleryShape)) {
                    Row(Modifier.height(48.dp).padding(8.dp).padding(end = if (info != null && key.startsWith("LS")) 22.dp else 0.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.width(if (key.startsWith("LS ")) 60.dp else 32.dp), contentAlignment = Alignment.Center) { TutorialControlSymbol(key) }
                        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        endIcons[key]?.let { icon -> Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) { GalleryIcon(icon, null, Modifier.size(18.dp)) }
                        } }
                    }
                    if (info != null && key.startsWith("LS")) {
                        val tooltip = rememberTooltipState(isPersistent = true)
                        val scope = rememberCoroutineScope()
                        Box(Modifier.align(Alignment.BottomEnd).padding(3.dp)) {
                            TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(), state = tooltip,
                                tooltip = { PlainTooltip { Text(info) } }) {
                                IconButton({ scope.launch { tooltip.show() } }, Modifier.size(26.dp)) {
                                    GalleryIcon(R.drawable.ph_info, localized(R.string.tutorial_control_help, "Dettagli comando"), Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun TutorialControlSymbol(key: String) {
    when {
        key.startsWith("LS ") -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ControllerChoiceBadge("LS")
            GalleryIcon(if (key.endsWith("↑↓")) R.drawable.ph_arrows_down_up else R.drawable.ph_arrows_left_right, null, Modifier.size(20.dp))
        }
        key == "← →" -> GalleryIcon(R.drawable.ph_arrows_left_right, null, Modifier.size(28.dp))
        key in listOf("↑", "↓", "←", "→", "TAP") -> Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface) {
            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                if (key == "TAP") GalleryIcon(R.drawable.ph_dots_three, null, Modifier.size(20.dp))
                else GalleryIcon(if (key == "←") R.drawable.ph_caret_double_left else if (key == "→") R.drawable.ph_caret_double_right else R.drawable.ph_arrow_right, null,
                    Modifier.size(20.dp).graphicsLayer { rotationZ = when (key) { "↑" -> -90f; "↓" -> 90f; else -> 0f } })
            }
        }
        else -> ControllerChoiceBadge(key)
    }
}

@Composable
private fun TutorialFolderRecap(sources: List<SourceEntity>, data: CatalogSnapshot, app: GalleryApp, prefs: GalleryPreferences) {
    val coverSize = if (LocalConfiguration.current.screenHeightDp < 540) 48.dp else 64.dp
    BoxWithConstraints(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, GalleryShape).padding(horizontal = 10.dp, vertical = 6.dp).testTag("tutorial-folder-recap")) {
        val capacity = ((maxWidth.value + 8) / 88).toInt().coerceIn(2, 7)
        val overflow = sources.size > capacity
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            sources.take(if (overflow) capacity - 1 else capacity).forEach { source ->
                val rootKey = DocumentIdentity.key(Uri.parse(source.treeUri), true)
                val root = data.albums.firstOrNull { DocumentIdentity.key(Uri.parse(it.documentUri)) == rootKey }
                val ids = data.membership.filter { it.sourceId == source.id }.map { it.mediaId }.toSet()
                val cover = root?.let(data::cover) ?: data.visibleMedia.filter { it.id in ids && it.available }.maxByOrNull { it.modified }
                Column(Modifier.width(80.dp).testTag("tutorial-folder:${source.id}"), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(coverSize).clip(GalleryShape).background(MaterialTheme.colorScheme.surface)) {
                        MediaThumbnail(cover, data, app.catalog, prefs, Modifier.fillMaxSize(), animated = false)
                        Surface(Modifier.align(Alignment.TopEnd).padding(3.dp), shape = CircleShape, color = MaterialTheme.colorScheme.surface.copy(alpha = .95f)) {
                            Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                if (source.role != "MUSIC") GalleryIcon(R.drawable.ph_images, null, Modifier.size(16.dp))
                                if (source.role != "VISUAL") GalleryIcon(R.drawable.ph_music_notes, null, Modifier.size(16.dp))
                            }
                        }
                    }
                    Text(source.title, Modifier.padding(top = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                }
            }
            if (overflow) Box(Modifier.width(80.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Box(Modifier.size(coverSize).background(MaterialTheme.colorScheme.surface, GalleryShape).testTag("tutorial-folder-overflow"), contentAlignment = Alignment.Center) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { GalleryIcon(R.drawable.ph_dots_three, null, Modifier.size(24.dp)) }
                    }
                }
            }
        }
    }
}


/** The other display stays decorative and intercepts every pointer throughout onboarding. */
@Composable
internal fun TutorialWelcomeScreen(prefs: GalleryPreferences, leaving: Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val greetings = remember { context.resources.getStringArray(R.array.tutorial_greetings) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var greeting by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(owner, leaving) {
        if (!leaving) owner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (true) { delay(3000); greeting = (greeting + 1) % greetings.size }
        }
    }
    val alpha by animateFloatAsState(if (leaving) 0f else 1f, tween(if (prefs.reducedMotion) 0 else 420), label = "welcome-reveal")
    val scale by animateFloatAsState(if (leaving) 1.16f else 1f, tween(if (prefs.reducedMotion) 0 else 420), label = "welcome-zoom")
    val drift = if (prefs.reducedMotion) .5f else {
        val loop = rememberInfiniteTransition(label = "welcome-glow")
        val animated by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Reverse), label = "welcome-drift")
        animated
    }
    val primary = MaterialTheme.colorScheme.primary
    Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha; scaleX = scale; scaleY = scale }.background(MaterialTheme.colorScheme.background).pointerInput(Unit) {
        awaitPointerEventScope { while (true) { awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } } }
    }.testTag("tutorial-welcome-screen"), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val x = if (prefs.reducedMotion) .5f else .3f + drift * .4f
            drawRect(Brush.radialGradient(listOf(primary.copy(alpha = .22f), Color.Transparent), center = Offset(size.width * x, size.height * .5f), radius = size.maxDimension * .65f))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("DS Gallery", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onBackground.copy(alpha = .6f))
            AnimatedContent(greeting, transitionSpec = {
                (fadeIn(tween(if (prefs.reducedMotion) 0 else 650)) togetherWith fadeOut(tween(if (prefs.reducedMotion) 0 else 500)))
                    .using(SizeTransform { _, _ -> tween(if (prefs.reducedMotion) 0 else 400) })
            }, label = "welcome-language") { index ->
                Text(greetings[index], Modifier.fillMaxWidth().padding(horizontal = 32.dp), textAlign = TextAlign.Center,
                    fontSize = 40.sp, lineHeight = 48.sp, fontWeight = FontWeight.Light, maxLines = 2)
            }
        }
    }
}


@Composable
private fun TutorialReady(prefs: GalleryPreferences) {
    val spin = remember { Animatable(0f) }
    val swell = remember { Animatable(1f) }
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!prefs.reducedMotion) {
            spin.animateTo(720f, tween(850, easing = FastOutSlowInEasing))
            swell.animateTo(1.15f, tween(180))
            swell.animateTo(1f, tween(240))
        }
        settled = true
    }
    val pulse = if (settled && !prefs.reducedMotion) {
        val loop = rememberInfiniteTransition(label = "tutorial-ready")
        loop.animateFloat(0f, 1f, infiniteRepeatable(tween(1700), RepeatMode.Restart), label = "welcome-ring").value
    } else 0f
    Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
        if (settled) Box(Modifier.size((108 + 40 * pulse).dp).border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = .5f * (1-pulse)), CircleShape))
        Image(painterResource(if (prefs.themeId == GalleryThemes.BLACK) R.drawable.app_icon_light else R.drawable.app_brand), null, Modifier.size(108.dp).graphicsLayer { rotationY = spin.value; cameraDistance = 12f * density; scaleX = swell.value; scaleY = swell.value }.clip(CircleShape))
    }
}

@Composable
private fun TutorialShoulderBadge(label: String, held: Boolean) {
    val scale by animateFloatAsState(if (held) 1.22f else 1f, tween(100), label = "tutorial-shoulder")
    Box(Modifier.graphicsLayer { scaleX = scale; scaleY = scale }) { ControllerChoiceBadge(label) }
}
