package it.ayngallery

import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot



import androidx.compose.foundation.Canvas

import androidx.compose.foundation.background

import androidx.compose.foundation.border

import androidx.compose.foundation.gestures.detectTapGestures

import androidx.compose.foundation.gestures.awaitEachGesture

import androidx.compose.foundation.gestures.awaitFirstDown

import androidx.compose.animation.fadeIn

import androidx.compose.animation.fadeOut

import androidx.compose.animation.core.tween

import androidx.compose.foundation.layout.*

import androidx.compose.material3.*

import androidx.compose.runtime.*

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.compose.runtime.saveable.rememberSaveable

import androidx.compose.ui.Alignment

import androidx.compose.ui.Modifier

import androidx.compose.ui.draw.clipToBounds

import androidx.compose.ui.draw.clip

import androidx.compose.ui.geometry.Offset

import androidx.compose.ui.geometry.Size

import androidx.compose.ui.graphics.Color

import androidx.compose.ui.graphics.graphicsLayer

import androidx.compose.ui.input.pointer.pointerInput

import androidx.compose.ui.layout.ContentScale

import androidx.compose.ui.platform.LocalDensity

import androidx.compose.ui.platform.testTag

import androidx.compose.ui.unit.dp

import androidx.compose.ui.semantics.semantics

import androidx.compose.ui.semantics.stateDescription

import androidx.compose.ui.semantics.selected

import androidx.compose.ui.viewinterop.AndroidView

import androidx.media3.common.util.UnstableApi

import androidx.media3.ui.PlayerView

import kotlinx.coroutines.ensureActive

import kotlinx.coroutines.launch

import kotlin.math.max

import kotlin.math.min



@Composable

fun MediaViewer(activity: PanelActivity, item: MediaEntity?, data: CatalogSnapshot, prefs: GalleryPreferences,

                onActions: (MediaEntity) -> Unit, albumCover: Boolean = false, overlayBusy: Boolean = false, onControlsVisibility: (Boolean) -> Unit = {}, lockedCoverAlbumId: String? = null,

                onChooseFolder: (SourceRole) -> Unit = { role -> activity.chooseWholeStorage(role) }) {


    var zoom by rememberSaveable(item?.id) { mutableFloatStateOf(1f) }

    var x by rememberSaveable(item?.id) { mutableFloatStateOf(0f) }

    var y by rememberSaveable(item?.id) { mutableFloatStateOf(0f) }

    var rotationTurns by rememberSaveable(item?.id) { mutableIntStateOf(0) }

    val rotation = key(item?.id) {

        val angle by androidx.compose.animation.core.animateFloatAsState(rotationTurns * 90f,

            androidx.compose.animation.core.tween(if (prefs.reducedMotion) 0 else 280), label = "preview rotation")

        angle

    }

    val view = ViewTransform(zoom, x, y)

    val change: (ViewTransform) -> Unit = { value -> val safe = value.bounded(); zoom = safe.zoom; x = safe.x; y = safe.y }

    DisposableEffect(activity, item?.id, item?.kind, view) {

        val handler: (PadAction, String?) -> Unit = { action, id ->

            if (id == item?.id && item?.kind in setOf("IMAGE", "GIF")) change(view.fromPad(action))

        }

        activity.padTransform = handler

        onDispose { if (activity.padTransform === handler) activity.padTransform = null }

    }

    val app = activity.application as GalleryApp

    // Controls have their own themed surface so their contrast does not depend on the artwork.
    val controlBackground = MaterialTheme.colorScheme.surface.copy(alpha = .96f)
    val controlInk = MaterialTheme.colorScheme.onSurface

    val comparisonCount by FloatingImageService.activeCount.collectAsStateWithLifecycle()

    val imageOverlay = !albumCover && item?.kind in setOf("IMAGE", "GIF")

    var lastStickTime by remember(item?.id) { mutableLongStateOf(0L) }

    DisposableEffect(activity, item?.id, imageOverlay, overlayBusy) {

        val sticks: (Float, Float, Float, Float, Long) -> Boolean = { lx, ly, _, ry, now ->

            val currentView = ViewTransform(zoom, x, y)

            if (!imageOverlay || overlayBusy || currentView.zoom <= 1f && kotlin.math.abs(ry) <= prefs.pad.release) false else {

                val seconds = if (lastStickTime == 0L) .02f else ((now - lastStickTime).coerceIn(0, 50) / 1000f)

                lastStickTime = now

                fun axis(value: Float) = if (value.isFinite() && kotlin.math.abs(value) > prefs.pad.release) value else 0f

                val nextZoom = (currentView.zoom * kotlin.math.exp(-axis(ry) * seconds * 1.4f)).coerceIn(.5f, 8f)

                change(ViewTransform(nextZoom, if (nextZoom > 1f) currentView.x - axis(lx) * seconds * .3f else 0f,

                    if (nextZoom > 1f) currentView.y - axis(ly) * seconds * .3f else 0f))

                true

            }

        }

        activity.imageSticks = sticks

        onDispose { if (activity.imageSticks === sticks) activity.imageSticks = null }

    }

    val controlsState = if (imageOverlay) app.session.imageOverlayState else remember { mutableStateOf(true) }

    var controlsVisible by controlsState

    var overlayInteraction by remember { mutableIntStateOf(0) }

    var touching by remember { mutableStateOf(false) }

    var wasBusy by remember { mutableStateOf(false) }

    LaunchedEffect(overlayBusy) {

        if (imageOverlay && wasBusy && !overlayBusy) app.session.interactImageOverlay()

        wasBusy = overlayBusy

    }

    LaunchedEffect(prefs.imageOverlayAutoHide, imageOverlay, controlsVisible, overlayBusy, overlayInteraction, touching, app.session.imageOverlayInteraction) {

        if (prefs.imageOverlayAutoHide && imageOverlay && controlsVisible && !overlayBusy && !touching) {

            val remaining = (5_000 - (android.os.SystemClock.elapsedRealtime() - app.session.imageOverlayInteraction)).coerceAtLeast(0)

            kotlinx.coroutines.delay(remaining)

            app.session.imageOverlayVisible = false

        }

    }

    LaunchedEffect(albumCover, controlsVisible) { onControlsVisibility(albumCover || controlsVisible) }

    val scope = rememberCoroutineScope()

    val playingVideo = item?.kind == "VIDEO" && !item.convertedGif &&

        app.video.owner == activity.slot && app.video.mediaId == item.id && app.video.player != null

    val navigationRoute by (app.session.libraryModel() ?: activity.windowModel).route.collectAsStateWithLifecycle()

    androidx.activity.compose.BackHandler(enabled = playingVideo && app.video.player?.playWhenReady == false) { app.video.closePausedViewer(); controlsVisible = true }
    androidx.activity.compose.BackHandler(enabled = playingVideo && activity.singleScreenVideoFullscreen) { activity.toggleSingleScreenVideoFullscreen() }


    Box(Modifier.fillMaxSize().pointerInput(imageOverlay) {

        awaitEachGesture {

            awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)

            touching = true

            overlayInteraction++

            if (imageOverlay) app.session.interactImageOverlay()

            do { val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial) }

            while (event.changes.any { it.pressed })

            touching = false

        }

    }.testTag("fullscreen-preview")) {

        if (item == null) {

            val category = app.session.libraryModel()?.route?.value?.tab ?: "PHOTO"

            EmptyCategoryPreview(category, prefs) { onChooseFolder(if (category == "MUSIC") SourceRole.MUSIC else SourceRole.VISUAL) }

            return@Box

        }

        if (albumCover) {

            MediaThumbnail(item, data, app.catalog, prefs, Modifier.fillMaxSize().testTag("preview-album-cover").pointerInput(app.session.previewAlbumId) {

                    detectTapGestures(onDoubleTap = {

                        val id = app.session.previewAlbumId

                        if (id != null) {

                            if (data.albumLocked(id)) app.session.albumUnlockRequest = id

                            else app.session.libraryModel()?.go("ALBUM", album = id)

                        }

                    })

                },

                animated = prefs.animatedAlbumCovers && lockedCoverAlbumId == null, active = true, scale = ContentScale.Fit, artwork = true, lockedAlbumCover = lockedCoverAlbumId)

            AlbumPreviewEdges(prefs.albumShadows, prefs.albumStudio)

            if (lockedCoverAlbumId != null) AlbumLockBadge(Modifier.align(Alignment.Center).testTag("album-preview-lock"), large = true, unlockPending = app.session.albumUnlockRequest == lockedCoverAlbumId, onUnlock = {

                app.session.albumUnlockRequest = lockedCoverAlbumId

            })

            val album = app.session.previewAlbumId?.let(data.albumsById::get)

            if (album != null) {

                Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {

                    CompositionLocalProvider(LocalContentColor provides Color.White) { GalleryIcon(R.drawable.ph_images, null, Modifier.padding(end = 10.dp)) }

                    Text(album.name, Modifier.weight(1f), color = Color.White, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)

                    CompositionLocalProvider(LocalContentColor provides Color.White) {


                GalleryIconButton(R.drawable.ph_dots_three, localized(R.string.text_media_options_8aef8c, "Opzioni del media"), Modifier.testTag("preview-album-options")) { app.session.openAlbumOptions(album.id) }

                    }

                }

                if (!album.hidden && !data.albumLocked(album.id)) Text("${data.albumMediaCount(album.id)} media", Modifier.align(Alignment.BottomEnd).padding(16.dp).background(Color.Black.copy(alpha = .65f), GalleryShape).padding(8.dp), color = Color.White)

            }

            return@Box

        }

        MediaSurface(activity, item, data, prefs, view, change, showPlaybackControls = false, rotationDegrees = rotation,

            controlsVisibility = { controlsVisible = it }, overlayVisible = controlsVisible, previewShadows = true,

            tap = { app.session.interactImageOverlay(); controlsVisible = !controlsVisible })

        androidx.compose.animation.AnimatedVisibility(controlsVisible, enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut(),

            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {

            if (data.visible(item)) CompositionLocalProvider(LocalContentColor provides controlInk) {

                Row(Modifier.background(controlBackground, GalleryShape), horizontalArrangement = Arrangement.spacedBy(6.dp)) {

                if (item.kind in setOf("IMAGE", "GIF") && !albumCover) {

                    if (prefs.comparisonEnabled) Box(contentAlignment = Alignment.Center) {

                        CompositionLocalProvider(LocalContentColor provides if (comparisonCount > 0) MaterialTheme.colorScheme.primary else LocalContentColor.current) {

                            GalleryIconButton(R.drawable.ph_browsers, localized(R.string.preview_compare_setting, "Confronti in finestre mobili"), Modifier.testTag("preview-compare")) { FloatingImageService.open(activity, item.id) }

                        }
                        Text("${(2 - comparisonCount).coerceAtLeast(0)}×", Modifier.align(Alignment.TopCenter).offset(y = (-2).dp), style = MaterialTheme.typography.labelSmall)

                    }

                    if (prefs.previewPinEnabled) CompositionLocalProvider(LocalContentColor provides if (app.session.pinnedPreviewId == item.id) MaterialTheme.colorScheme.primary else LocalContentColor.current) {

                        GalleryIconButton(if (app.session.pinnedPreviewId == item.id) R.drawable.ph_push_pin_simple else R.drawable.ph_push_pin,

                            localized(R.string.preview_pin_setting, "Pulsante per fissare l’anteprima"), Modifier.testTag("preview-pin")) { app.session.togglePreviewPin(item.id) }

                    }

                }

                if (playingVideo && app.session.canSwap()) GalleryIconButton(R.drawable.ph_moon_stars, localized(R.string.video_dim_navigation, "Oscura lo schermo di navigazione"), Modifier.testTag("video-dim-toggle")) { app.video.dimNavigation = !app.video.dimNavigation }


                GalleryIconButton(R.drawable.ph_dots_three, localized(R.string.text_media_options_8aef8c, "Opzioni del media")) { onActions(item) }


                }

            }

        }

        androidx.compose.animation.AnimatedVisibility(controlsVisible, enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut(),

            modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {

            if (data.visible(item)) {

                CompositionLocalProvider(LocalContentColor provides controlInk) {
                Row(Modifier.fillMaxWidth().padding(end = if (playingVideo) (if (prefs.videoKeepPlaying) 176 else 176).dp else (56 + (if (prefs.previewPinEnabled) 48 else 0) + (if (prefs.comparisonEnabled) 48 else 0)).dp), verticalAlignment = Alignment.CenterVertically) {

                IconButton(onClick = { scope.launch {

                    try { app.catalog.favorite(item.id); controlsVisible = true }

                    catch (failure: Exception) { if (failure is kotlinx.coroutines.CancellationException) throw failure; app.session.fail(localized(R.string.text_favorite_not_saved_1_s_9d77cf, "Preferito non salvato: %1\$s", failure.message)) }

                } }, modifier = Modifier.size(48.dp).testTag("preview-favorite")

                    .background(controlBackground, GalleryShape)) {

                    FavoriteHeart(item.favorite,

                        if (item.favorite) localized(R.string.text_remove_from_favorites_6556ff, "Rimuovi dai preferiti") else localized(R.string.text_add_to_favorites_63ce28, "Aggiungi ai preferiti"),

                        tint = if (item.favorite) MaterialTheme.colorScheme.primary else controlInk, identity = item.id)

                }

                if (item.kind in setOf("IMAGE", "GIF", "VIDEO")) GalleryIconButton(R.drawable.ph_arrow_clockwise,

                    localized(R.string.preview_rotate, "Ruota anteprima di 90°"), Modifier.padding(start = 4.dp).testTag("preview-rotate")

                        .semantics { stateDescription = "${rotationTurns % 4 * 90}°" }

                        .background(controlBackground, GalleryShape)) { rotationTurns++ }

                if (item.kind == "VIDEO" && !item.convertedGif || (imageOverlay || item.convertedGif) && prefs.imagePreviewName) Text(item.name, Modifier.padding(start = 6.dp)

                    .background(Color.Black.copy(alpha = .65f), GalleryShape).padding(horizontal = 12.dp, vertical = 8.dp)

                    .testTag("preview-video-name"), color = Color.White, maxLines = 1,

                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)

                }

                }

            }

        }

        if (prefs.viewerArrows && controlsVisible) {

            GalleryIconButton(R.drawable.ph_arrow_left, localized(R.string.text_previous_media_3e439b, "Media precedente"), Modifier.align(Alignment.CenterStart)

                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), GalleryShape)) { activity.navigateMedia?.invoke(-1) }

            GalleryIconButton(R.drawable.ph_arrow_right, localized(R.string.text_next_media_228a8d, "Media successivo"), Modifier.align(Alignment.CenterEnd)

                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), GalleryShape)) { activity.navigateMedia?.invoke(1) }

        }

        if (prefs.viewerZoom && controlsVisible && item?.kind in setOf("IMAGE", "GIF"))

            TransformControls(view, change, Modifier.align(Alignment.BottomCenter))

    }

}



@Composable

private fun EmptyPreviewFolderPrompt(onChooseFolder: () -> Unit) {

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("preview-empty-folder"),

        contentAlignment = Alignment.Center) {

        Column(Modifier.fillMaxWidth(.78f).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,

            verticalArrangement = Arrangement.spacedBy(14.dp)) {

            Surface(shape = androidx.compose.foundation.shape.CircleShape,

                color = MaterialTheme.colorScheme.primary.copy(alpha = .12f)) {

                Box(Modifier.size(88.dp), contentAlignment = Alignment.Center) {

                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) {

                        GalleryIcon(R.drawable.ph_folders, null, Modifier.size(46.dp))

                    }

                }

            }

            Text(localized(R.string.empty_preview_folder_title, "La tua galleria inizia da qui"),

                style = MaterialTheme.typography.headlineSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,

                textAlign = androidx.compose.ui.text.style.TextAlign.Center)

            Text(localized(R.string.empty_preview_folder_message,

                "Scegli una cartella con foto o video. Le sue sottocartelle appariranno come album e potrai sfogliarli sull’altro schermo."),

                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,

                textAlign = androidx.compose.ui.text.style.TextAlign.Center)

            Button(onChooseFolder, modifier = Modifier.padding(top = 4.dp).testTag("preview-choose-folder")) {

                GalleryIcon(R.drawable.ph_folders, null, Modifier.size(20.dp))

                Spacer(Modifier.width(8.dp))

                Text(localized(R.string.choose_media_folder, "Scegli una cartella"))

            }

        }

    }

}



@OptIn(ExperimentalLayoutApi::class)

@Composable

fun TransformControls(view: ViewTransform, change: (ViewTransform) -> Unit, modifier: Modifier = Modifier) {

    FlowRow(modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.Center) {

        GalleryButton("−") { change(view.copy(zoom = (view.zoom / 1.25f).coerceAtLeast(.5f))) }

        GalleryButton("${(view.zoom * 100).toInt()}% · reset") { change(ViewTransform()) }

        GalleryButton("+") { change(view.copy(zoom = (view.zoom * 1.25f).coerceAtMost(8f))) }

    }

}



/** Shared visual surface: comparison uses the same decoder and the single video player. */

@androidx.annotation.OptIn(UnstableApi::class)

@Composable

fun MediaSurface(activity: PanelActivity, item: MediaEntity?, data: CatalogSnapshot, prefs: GalleryPreferences,

                 view: ViewTransform, change: (ViewTransform) -> Unit, surfaceKey: String = "viewer",

                 paneActive: Boolean = true, activate: () -> Unit = {}, showPlaybackControls: Boolean = true, tap: ((Float) -> Unit)? = null,

                 controlsVisibility: ((Boolean) -> Unit)? = null, rotationDegrees: Float = 0f, overlayVisible: Boolean = true, previewShadows: Boolean = false) {

    val onTap by rememberUpdatedState(tap)

    val onControlsVisibility by rememberUpdatedState(controlsVisibility)

    val app = activity.application as GalleryApp

    val started by activity.isStartedFlow.collectAsState()

    val usable = item != null && data.usable(item)
    val videoBinding = remember(activity, item?.id, surfaceKey) { Any() }

    var muted by rememberSaveable(surfaceKey, item?.id) { mutableStateOf(false) }

    var dimensions by remember(item?.id) { mutableStateOf(Size(1f, 1f)) }

    LaunchedEffect(item?.id, usable, started, paneActive, prefs.gameMode, app.video.floating) {

        if (item?.convertedGif == true && usable && started && paneActive && !prefs.gameMode && !app.video.floating && !app.video.pipOpening && !app.video.returnedFromPip) {

            try { app.video.play(item, activity.slot, true, surfaceKey) }

            catch (failure: Exception) {

                if (failure is kotlinx.coroutines.CancellationException) throw failure

                app.session.fail(localized(R.string.text_animation_cannot_be_played_1_s_444482, "Animazione non riproducibile: %1\$s", failure.message))

            }

        }

    }

    // PanelActivity owns lifecycle stops. A resume during a PiP return must not dispose
    // the old paused composition and stop the player just transferred to this panel.
    DisposableEffect(item?.id, usable, surfaceKey) {

        onDispose { if (surfaceKey != "viewer" || app.session.settingsOwner == null && !app.video.suspendedForTools && !app.video.pipOpening)

            item?.id?.let {
                if (surfaceKey == "viewer") app.video.stopViewer(activity.slot, it, videoBinding)
                else app.video.stop(activity.slot, it, surfaceKey)
            } }

    }

    if (item == null) { EmptyGallery(localized(R.string.text_your_preview_48eb5c, "La tua anteprima"), localized(R.string.text_select_media_in_the_library_56906c, "Seleziona un media nella libreria."), featured = true, animateGlow = !prefs.reducedMotion); return }

    if (!usable) { EmptyGallery(localized(R.string.text_media_unavailable_91dca9, "Media non disponibile"), localized(R.string.text_the_source_is_disconnected_hidden_or_needs_acces_148e2b, "La sorgente è scollegata, nascosta o richiede nuovamente l'accesso. Il riferimento rimane conservato.")); return }

    if (app.video.floating && app.video.mediaId == item.id) {

        Box(Modifier.fillMaxSize().testTag("video-pip-placeholder")) {

        EmptyGallery(localized(R.string.video_pip_active, "Riproduzione in finestra"),

            localized(R.string.video_pip_description, "Il video continua nella finestra Android. Puoi sfogliare la galleria o aprire altre app. Usa i controlli della finestra per ingrandirla o chiuderla."),

            R.drawable.ph_frame_corners, action = localized(R.string.video_return_screen, "Riporta il video su questo schermo"),
            featured = true, animateGlow = !prefs.reducedMotion, iconBelowTitle = true, actionIcon = R.drawable.ph_frame_corners) {
                app.video.returnToGallery(activity)
            }

        }

        return

    }

    val controlledGif = (prefs.previewNearest || prefs.gifFrameSelector || prefs.gifFrameCount && prefs.gifFrameCounter) && item.kind == "GIF"

    val gifFrames by rememberGifPlayback(app, item, data, usable && controlledGif)

    LaunchedEffect(gifFrames, started, paneActive, prefs.gameMode, gifFrames?.paused, gifFrames?.requestRevision) {

        gifFrames?.animate(started && paneActive && !prefs.gameMode)

    }

    val gifBitmap = gifFrames?.takeUnless { it.failed }?.bitmap

    val transparencyBase = androidx.compose.ui.graphics.lerp(Color(0xFF303030), MaterialTheme.colorScheme.primary, .5f)

    val videoPlayer = app.video.player?.takeIf { item.kind == "VIDEO" && app.video.owner == activity.slot &&

        app.video.surface == surfaceKey && app.video.mediaId == item.id }

    val playingVideo = videoPlayer != null

    val swipePreview = item.kind == "AUDIO"

    Column(Modifier.fillMaxSize()) {

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds()

            .then(if (swipePreview) Modifier.pointerInput(item.id, started, paneActive) {

                mediaTouchGestures(zoomed = { false }, start = {}, transform = { _, _, _ -> }, swipe = { direction ->

                    if (started && paneActive) { activate(); activity.navigateMedia?.invoke(direction) }

                })

            } else Modifier).testTag("media-surface:$surfaceKey").semantics {

            stateDescription = localized(R.string.text_zoom_1_s_position_2_s_3_s_abeae2, "Zoom %1\$s%% · posizione %2\$s%%, %3\$s%%", (view.zoom * 100).toInt(), (view.x * 100).toInt(), (view.y * 100).toInt())

        }, contentAlignment = Alignment.Center) {

            val width = with(LocalDensity.current) { maxWidth.toPx() }

            val height = with(LocalDensity.current) { maxHeight.toPx() }

            val fit = min(width / dimensions.width, height / dimensions.height)

            val fittedWidth = (dimensions.width * fit).coerceAtLeast(1f)

            val fittedHeight = (dimensions.height * fit).coerceAtLeast(1f)

            val rotationScale = PreviewRotation.fit(width, height, fittedWidth, fittedHeight, rotationDegrees)

            val rotated = Modifier.graphicsLayer { rotationZ = rotationDegrees; scaleX = rotationScale; scaleY = rotationScale }

            var slide by remember(item.id) { mutableFloatStateOf(0f) }

            var settling by remember(item.id) { mutableStateOf(false) }

            val swipeScope = rememberCoroutineScope()

            var swipeJob by remember(item.id) { mutableStateOf<kotlinx.coroutines.Job?>(null) }

            var zoomJob by remember(item.id) { mutableStateOf<kotlinx.coroutines.Job?>(null) }

            DisposableEffect(item.id) { onDispose { swipeJob?.cancel(); zoomJob?.cancel() } }

            val dragPhoto: (Float) -> Unit = { distance -> if (!settling && app.session.pinnedPreviewId != item.id) slide = distance.coerceIn(-width, width) }

            val releasePhoto: (Int) -> Unit = { direction ->

                if (!settling && app.session.pinnedPreviewId != item.id && (slide != 0f || direction != 0)) {

                    val nextDirection = direction.takeIf { it != 0 && previewNeighbor(item.id, it, app.session.mediaContext, data) != null } ?: 0

                    settling = true

                    swipeJob = swipeScope.launch {

                        val target = if (nextDirection == 0) 0f else if (nextDirection > 0) -width else width

                        if (!prefs.reducedMotion) androidx.compose.animation.core.Animatable(slide).animateTo(target,

                            androidx.compose.animation.core.tween(220)) { slide = value }

                        val selectionBefore = app.session.selectedMediaId

                        if (nextDirection != 0 && previewNeighbor(item.id, nextDirection, app.session.mediaContext, app.catalog.snapshot.value) != null) {

                            if (item.kind == "VIDEO") app.session.previewSwipe = true

                            activate(); activity.navigateMedia?.invoke(nextDirection)

                        }

                        if (nextDirection == 0 || app.session.selectedMediaId == selectionBefore) {

                            if (!prefs.reducedMotion) androidx.compose.animation.core.Animatable(slide).animateTo(0f,

                                androidx.compose.animation.core.tween(220)) { slide = value }

                            slide = 0f; settling = false

                        }

                    }

                }

            }

            val neighbor = if (slide == 0f) null else previewNeighbor(item.id, if (slide < 0f) 1 else -1, app.session.mediaContext, data)

            val scaledWidth = fittedWidth * view.zoom; val scaledHeight = fittedHeight * view.zoom

            // A zoomed portrait can still fit across the wide panel: allow panning on that axis too.

            // At the limit an image edge remains at the viewport centre, so it never disappears entirely.

            val limitX = if (view.zoom > 1f) scaledWidth / 2f else 0f

            val limitY = if (view.zoom > 1f) scaledHeight / 2f else 0f

            val offsetX = (view.x * scaledWidth).coerceIn(-limitX, limitX)

            val offsetY = (view.y * scaledHeight).coerceIn(-limitY, limitY)

            Canvas(Modifier.fillMaxSize()) {

                if (item.kind == "VIDEO" && !item.convertedGif) drawRect(Color.Black)

                else drawImageBackdrop(prefs, transparencyBase)

            }

            when (item.kind) {

                "AUDIO" -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {

                    MediaThumbnail(item, data, app.catalog, prefs, Modifier.fillMaxWidth(0.65f).aspectRatio(1f)

                        .pointerInput(item.id) { detectTapGestures { onTap?.invoke(.5f) } }, active = started, scale = ContentScale.Fit,

                        backgroundColor = Color.Transparent)

                    Text(item.artist ?: localized(R.string.text_artist_unavailable_c612a4, "Artista non disponibile"))

                    GalleryButton(localized(R.string.text_play_track_402432, "Riproduci brano"), primary = true) { activate(); app.music.play(item.id, listOf(item.id)) }

                }

                "VIDEO" -> if (videoPlayer != null) {

                    val navigate by rememberUpdatedState<(Int) -> Unit>({ direction ->

                        if (started && paneActive && previewNeighbor(item.id, direction, if (item.convertedGif) app.session.mediaContext else app.video.context, app.catalog.snapshot.value) != null) {

                            activate(); activity.navigateMedia?.invoke(direction)

                        }

                    })

                    val delegate = videoPlayer

                    val galleryPlayer = remember(delegate) { GalleryVideoNavigation(delegate, { navigate(it) }, app.video::showSeek) }

                    galleryPlayer.previousAvailable = previewNeighbor(item.id, -1, if (item.convertedGif) app.session.mediaContext else app.video.context, data) != null

                    galleryPlayer.nextAvailable = previewNeighbor(item.id, 1, if (item.convertedGif) app.session.mediaContext else app.video.context, data) != null

                    AndroidView(factory = { context -> (android.view.LayoutInflater.from(context).inflate(R.layout.gallery_preview_video, null) as GalleryVideoView).apply {

                        useController = !item.convertedGif

                        setKeepContentOnPlayerReset(true)
                        controllerAutoShow = false
                        controllerShowTimeoutMs = 5000

                        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)

                        if (!item.convertedGif) setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility -> onControlsVisibility?.invoke(visibility == android.view.View.VISIBLE) })

                    } },

                        update = { playerView ->
                            if (playerView.player !== galleryPlayer) {
                                playerView.player = galleryPlayer
                                playerView.hideController()
                                onControlsVisibility?.invoke(false)
                                if (surfaceKey == "viewer") app.video.attachViewer(delegate, activity.slot, videoBinding)
                            }

                            playerView.navigate = { navigate(it) }
                            playerView.onFullscreen = if (!item.convertedGif && app.session.isSingleScreen) activity::toggleSingleScreenVideoFullscreen else null
                            playerView.fullscreen = activity.singleScreenVideoFullscreen

                            playerView.onPreviewTap = if (item.convertedGif) ({ onTap?.invoke(.5f) }) else null

                            playerView.onDoubleTapSeek = if (!item.convertedGif) app.video::doubleTapSeek else null

                            playerView.onPip = if (surfaceKey == "viewer" && !item.convertedGif && activity.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE))

                                ({ app.video.openPip(activity) }) else null

                            playerView.onSubtitles = if (!item.convertedGif && app.video.subtitleTracksAvailable)

                                ({ app.video.showSubtitleSelection(activity, localized(R.string.video_subtitles, "Sottotitoli")) }) else null

                            playerView.onAudio = if (!item.convertedGif) ({ app.video.showAudioSelection(activity) }) else null
                            playerView.onVolume = if (!item.convertedGif) ({ anchor -> app.video.showVolume(activity, anchor) }) else null
                            playerView.onSettings = { anchor -> app.video.showPlaybackSettings(activity, anchor) }

                            playerView.player?.videoSize?.takeIf { it.width > 0 && it.height > 0 }?.let { dimensions = Size(it.width.toFloat(), it.height.toFloat()) }

                            playerView.videoSurfaceView?.apply { rotation = rotationDegrees; scaleX = rotationScale; scaleY = rotationScale

                                (parent as? android.view.ViewGroup)?.clipChildren = false }

                            playerView.clipChildren = false

                        }, onRelease = { it.player = null }, modifier = Modifier.fillMaxSize())

                    var flowVisible by remember(delegate) { mutableStateOf(false) }
                    val flowRevision = app.video.flowFeedbackRevision
                    LaunchedEffect(flowRevision) { if (flowRevision > 0) { flowVisible = true; kotlinx.coroutines.delay(1500); flowVisible = false } }
                    androidx.compose.animation.AnimatedVisibility(flowVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.Center).padding(24.dp)) {
                        Row(Modifier.background(Color.Black.copy(alpha = .8f), androidx.compose.foundation.shape.RoundedCornerShape(18.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            GalleryIcon(when (app.video.flowMode) { 1 -> R.drawable.ph_arrows_left_right; 2 -> R.drawable.ph_arrows_clockwise; 3 -> R.drawable.ph_shuffle; else -> R.drawable.ph_arrow_counter_clockwise }, null)
                            Text(localized(when (app.video.flowMode) { 1 -> R.string.video_flow_playlist; 2 -> R.string.video_flow_repeat; 3 -> R.string.video_flow_random; else -> R.string.video_flow_once }, "Modalità video"), color = Color.White)
                        }
                    }
                    var seekVisible by remember(delegate) { mutableStateOf(false) }

                    val seekRevision = app.video.seekFeedbackRevision

                    val seekSeconds = app.video.seekFeedbackSeconds

                    LaunchedEffect(seekRevision) {

                        if (seekRevision > 0) { seekVisible = true; kotlinx.coroutines.delay(850); seekVisible = false }

                    }

                    androidx.compose.animation.AnimatedVisibility(seekVisible,

                        enter = fadeIn(tween(120)) + androidx.compose.animation.scaleIn(initialScale = .9f), exit = fadeOut(tween(220)),

                        modifier = Modifier.align(if (seekSeconds < 0) Alignment.CenterStart else Alignment.CenterEnd).padding(36.dp)) {

                        Text("${if (seekSeconds > 0) "+" else "−"}${kotlin.math.abs(seekSeconds)} s",

                            Modifier.background(Color.Black.copy(alpha = .8f), androidx.compose.foundation.shape.RoundedCornerShape(20.dp))

                                .padding(horizontal = 20.dp, vertical = 14.dp).testTag("video-seek-feedback"),

                            color = Color.White, style = MaterialTheme.typography.titleLarge)

                    }

                    app.video.error?.let { Text(it, Modifier.align(Alignment.TopCenter).background(MaterialTheme.colorScheme.surface).padding(12.dp)) }

                } else {

                    if (neighbor != null) MediaThumbnail(neighbor, data, app.catalog, prefs,

                        Modifier.fillMaxSize().graphicsLayer { translationX = slide + if (slide < 0f) width else -width },

                        active = started, scale = ContentScale.Fit, backgroundColor = Color.Black, crossfadeMillis = 0)

                    MediaThumbnail(item, data, app.catalog, prefs, Modifier.fillMaxSize()

                        .pointerInput(item.id, width) {

                            mediaTouchGestures(zoomed = { false }, start = {}, transform = { _, _, _ -> },

                                swipe = { releasePhoto(it) }, drag = dragPhoto, release = releasePhoto)

                        }

                        .pointerInput(item.id) { detectTapGestures { onTap?.invoke(.5f) } }

                        .graphicsLayer { translationX = slide }.then(rotated), active = started, scale = ContentScale.Fit,

                        backgroundColor = if (!item.convertedGif) Color.Black else Color.Transparent,

                        dimensions = { w, h -> if (w > 0 && h > 0) dimensions = Size(w.toFloat(), h.toFloat()) })

                    FilledIconButton({ activate(); app.video.requestPlay(item, activity.slot, muted, surfaceKey) }, Modifier.graphicsLayer { translationX = slide }.size(64.dp).testTag("video-play")) {

                        GalleryIcon(R.drawable.ph_play, localized(R.string.text_play_video_ba31ae, "Riproduci video"), Modifier.size(32.dp))

                    }

                }

                else -> {

                    if (neighbor != null) MediaThumbnail(neighbor, data, app.catalog, prefs,

                        Modifier.fillMaxSize().testTag("preview-neighbor:${neighbor.id}").graphicsLayer {

                            translationX = slide + if (slide < 0f) width else -width

                        }, animated = false, active = started, scale = ContentScale.Fit,

                        backgroundColor = Color.Transparent, crossfadeMillis = 0, filterQuality = previewFilter(prefs))

                    val geometry by rememberUpdatedState(Size(fittedWidth, fittedHeight))

                    val current by rememberUpdatedState(view)

                    val update by rememberUpdatedState(change)

                    val release by rememberUpdatedState(releasePhoto)

                    MediaThumbnail(item, data, app.catalog, prefs, Modifier.fillMaxSize()

                        .pointerInput(item.id, width, height) {

                            var running = current

                            mediaTouchGestures(zoomed = { running.zoom > 1f }, start = { zoomJob?.cancel(); running = current }, transform = { centroid, pan, factor ->

                            activate()

                            val old = running; val next = (old.zoom * factor).coerceIn(.5f, 8f); val ratio = next / old.zoom

                            val center = Offset(width / 2, height / 2)

                            val sx = geometry.width * next; val sy = geometry.height * next

                            val oldX = if (old.zoom > 1f) old.x * geometry.width * old.zoom else 0f

                            val oldY = if (old.zoom > 1f) old.y * geometry.height * old.zoom else 0f

                            val lx = if (next > 1f) sx / 2 else 0f; val ly = if (next > 1f) sy / 2 else 0f

                            val px = ((oldX - centroid.x + center.x) * ratio + centroid.x - center.x + pan.x).coerceIn(-lx, lx)

                            val py = ((oldY - centroid.y + center.y) * ratio + centroid.y - center.y + pan.y).coerceIn(-ly, ly)

                            running = ViewTransform(next, px / sx, py / sy).bounded()

                            update(running)

                        }, swipe = { direction -> if (app.session.pinnedPreviewId != item.id) { activate(); activity.navigateMedia?.invoke(direction) } }, drag = dragPhoto, release = { release(it) }) }.pointerInput(item.id, prefs.touch) {

                            fun gesture(action: TouchAction) {

                                activate()

                                if (action == TouchAction.TOGGLE_ZOOM) {

                                    onControlsVisibility?.invoke(false)

                                    zoomJob?.cancel()

                                    val from = current

                                    val to = ViewTransform(if (from.zoom < 1f) 1f else if (from.zoom <= 1.01f) 2f else 1f)

                                    zoomJob = swipeScope.launch {

                                        if (prefs.reducedMotion) update(to)

                                        else androidx.compose.animation.core.Animatable(0f).animateTo(1f, tween(260)) {

                                            update(ViewTransform(from.zoom + (to.zoom - from.zoom) * value, from.x * (1 - value), from.y * (1 - value)))

                                        }

                                    }

                                }

                                else activity.performTouchAction(action)

                            }

                            detectTapGestures(onTap = { position ->

                                activate()

                                val fraction = position.x / width

                                onTap?.invoke(fraction)

                            }, onDoubleTap = prefs.touch.doubleTap.takeIf { it != TouchAction.NONE }?.let { action -> { gesture(action) } },

                                onLongPress = prefs.touch.longPress.takeIf { it != TouchAction.NONE }?.let { action -> { gesture(action) } })

                        }.graphicsLayer {

                            rotationZ = rotationDegrees; scaleX = view.zoom * rotationScale; scaleY = view.zoom * rotationScale

                            val radians = Math.toRadians(rotationDegrees.toDouble())

                            val rotatedWidth = (width * kotlin.math.abs(kotlin.math.cos(radians)) + height * kotlin.math.abs(kotlin.math.sin(radians))).toFloat() * scaleX

                            val travel = max(width, (width + rotatedWidth) / 2f + kotlin.math.abs(offsetX))

                            translationX = offsetX + slide * travel / width; translationY = offsetY

                        },

                        frame = gifBitmap, animated = item.kind == "GIF" && paneActive, active = started, scale = ContentScale.Fit, crossfadeMillis = 0, filterQuality = previewFilter(prefs),

                        dimensions = { w, h -> if (w > 0 && h > 0) dimensions = Size(w.toFloat(), h.toFloat()) },

                        backgroundColor = Color.Transparent)

                }

            }

            if (previewShadows) androidx.compose.animation.AnimatedVisibility(overlayVisible && !playingVideo, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {

                AlbumPreviewEdges(prefs.albumShadows, prefs.albumStudio)

            }

            androidx.compose.animation.AnimatedVisibility(visible = overlayVisible && prefs.photoNavigator && view.zoom > 1 && item.kind in setOf("IMAGE", "GIF"),

                modifier = Modifier.align(Alignment.BottomEnd),

                enter = fadeIn(tween(if (prefs.reducedMotion) 0 else 180)),

                exit = fadeOut(tween(if (prefs.reducedMotion) 0 else 180))) {

                val navigatorAccent = MaterialTheme.colorScheme.primary

                val navigatorView by rememberUpdatedState(view)

                val navigatorChange by rememberUpdatedState(change)

                val navigatorDimensions by rememberUpdatedState(dimensions)

                val navigatorOwner = remember(item.id) { Any() }
                DisposableEffect(navigatorOwner) { onDispose {
                    if (app.session.previewNavigatorOwner === navigatorOwner) { app.session.previewNavigatorBounds = null; app.session.previewNavigatorOwner = null }
                } }
                Box(Modifier.padding(12.dp).size(168.dp, (168f * dimensions.height / dimensions.width).coerceIn(84f, 180f).dp)
                    .then(Modifier.onGloballyPositioned { app.session.previewNavigatorOwner = navigatorOwner; app.session.previewNavigatorBounds = it.boundsInRoot() })

                    .border(1.5.dp, navigatorAccent, GalleryShape).clip(GalleryShape).pointerInput(item.id) {

                        fun moveTo(point: Offset) {

                            val d = navigatorDimensions

                            val fit = min(size.width / d.width, size.height / d.height)

                            val mw = d.width * fit; val mh = d.height * fit

                            val cx = ((point.x - (size.width - mw) / 2f) / mw).coerceIn(0f, 1f)

                            val cy = ((point.y - (size.height - mh) / 2f) / mh).coerceIn(0f, 1f)

                            navigatorChange(navigatorView.copy(x = .5f - cx, y = .5f - cy))

                        }

                        awaitEachGesture {

                            val down = awaitFirstDown()

                            down.consume()

                            moveTo(down.position)

                            do {

                                val event = awaitPointerEvent()

                                val finger = event.changes.firstOrNull { it.id == down.id } ?: break

                                finger.consume()

                                if (finger.pressed) moveTo(finger.position)

                            } while (finger.pressed)

                        }

                    }

                    .background(MaterialTheme.colorScheme.surface.copy(alpha = .85f), GalleryShape).testTag("photo-navigator")) {

                    MediaThumbnail(item, data, app.catalog, prefs, Modifier.align(Alignment.Center).aspectRatio(dimensions.width / dimensions.height).clip(GalleryShape), scale = ContentScale.Fit,

                        frame = gifBitmap, animated = item.kind == "GIF" && paneActive, active = started, backgroundColor = Color.Transparent, filterQuality = previewFilter(prefs))

                    Canvas(Modifier.fillMaxSize()) {

                        val fitMini = min(size.width / dimensions.width, size.height / dimensions.height)

                        val mw = dimensions.width * fitMini; val mh = dimensions.height * fitMini

                        val vw = min(1f, width / scaledWidth); val vh = min(1f, height / scaledHeight)

                        val cx = .5f - offsetX / scaledWidth; val cy = .5f - offsetY / scaledHeight

                        val left = (cx - vw / 2).coerceIn(0f, 1f); val right = (cx + vw / 2).coerceIn(0f, 1f)

                        val top = (cy - vh / 2).coerceIn(0f, 1f); val bottom = (cy + vh / 2).coerceIn(0f, 1f)

                        drawRoundRect(navigatorAccent, Offset((size.width - mw) / 2 + left * mw,

                            (size.height - mh) / 2 + top * mh), Size((right - left) * mw, (bottom - top) * mh),

                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()),

                            style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))

                    }

                }

            }

            if (gifFrames?.seeking == true) CircularProgressIndicator(Modifier.align(Alignment.Center).size(32.dp).testTag("gif-frame-seeking"))

            gifFrames?.takeUnless { it.failed }?.let { frames ->

                PreviewDock("gif-strip", R.drawable.ph_film_strip, overlayVisible && prefs.gifFrameSelector,
                    modifier = Modifier.padding(end = if (prefs.photoNavigator && view.zoom > 1) 192.dp else 0.dp), avoidNavigator = false, fixed = true) { dragModifier ->

                    BoxWithConstraints(dragModifier) {

                        GifFrameStrip(frames, (maxWidth - 24.dp).coerceAtLeast(84.dp))

                    }

                }

            }



            if (prefs.gifFrameCount && item.kind == "GIF") {

                val count by produceState<Int?>(null, item.id, item.modified, prefs.gifFrameCount) {

                    value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {

                        val context = kotlinx.coroutines.currentCoroutineContext()

                        try { app.contentResolver.openInputStream(app.catalog.uri(item, data))?.use { GifFrameCount.read(it) { context.ensureActive() } } }

                        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }

                        catch (_: Exception) { null }

                    }

                }

                androidx.compose.animation.AnimatedVisibility(overlayVisible, enter = fadeIn(), exit = fadeOut(),

                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp,

                        bottom = if (prefs.photoNavigator && view.zoom > 1) (168f * dimensions.height / dimensions.width).coerceIn(84f, 180f).dp + 20.dp else 12.dp)) {

                    val controller = gifFrames?.takeUnless { it.failed }

                    val label = if (controller != null && controller.bitmap != null) localized(R.string.gif_current_of_total,

                        "%1\$s / %2\$s fotogrammi", (controller.index + 1).toString().padStart(controller.count.toString().length), controller.count.toString())

                        else count?.let { localized(R.string.text_1_s_frames_ea0c5d, "%1\$s fotogrammi", it) } ?: localized(R.string.text_frame_count_unavailable_3e8d19, "Fotogrammi non disponibili")

                    Text(label,

                        Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = .85f), GalleryShape)

                            .padding(8.dp).testTag("gif-frame-count"), style = MaterialTheme.typography.labelSmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace))

                }

            }

        }

        if (showPlaybackControls && item.kind == "VIDEO") Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {

            if (item.kind == "VIDEO") GalleryButton(if (muted) localized(R.string.text_muted_2228e3, "Silenzioso") else localized(R.string.text_sound_on_935c7c, "Con audio")) {

                activate(); muted = !muted

                if (app.video.surface == surfaceKey) app.video.mute(activity.slot, muted)

            }

        }

    }

}



/** Keep swipe neighbors in the same visible context as committed navigation. */

internal fun previewNeighbor(current: String, direction: Int, context: List<String>, data: CatalogSnapshot): MediaEntity? {

    val visible = context.mapNotNull { data.byId[it]?.takeIf(data::visible) }

    val index = visible.indexOfFirst { it.id == current }

    if (index < 0 || visible.size < 2) return null

    return visible[Math.floorMod(index + direction, visible.size)].takeIf(data::usable)

}



@Composable

internal fun VideoResumeDialog(app: GalleryApp, slot: Int) {

    val request = app.video.resumeRequest?.takeIf { it.slot == slot } ?: return

    val item = app.catalog.snapshot.value.byId[request.id] ?: return

    val runtime = remember { PadModalRuntime() }

    DisposableEffect(runtime, request) {

        runtime.context = PadContext.LIBRARY

        runtime.captureAction = { action ->

            if (action in setOf(PadAction.UP, PadAction.DOWN, PadAction.LEFT, PadAction.RIGHT, PadAction.TAB_PREVIOUS, PadAction.TAB_NEXT,

                    PadAction.MEDIA_PREVIOUS, PadAction.MEDIA_NEXT)) {

                app.video.dismissResume()

                runtime.owner?.executeControllerAction(action)

                true

            } else false

        }

        runtime.captureKey = { event ->

            val button = padButton(event.keyCode)

            if (button in setOf(PadButton.A, PadButton.X, PadButton.B)) {

                if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) when (button) {

                    PadButton.A -> app.video.confirmResume(true)

                    PadButton.X -> app.video.confirmResume(false)

                    else -> app.video.dismissResume()

                }

                true

            } else runtime.input?.key(event) == true

        }

        onDispose { runtime.captureKey = null; runtime.captureAction = null }

    }

    GalleryAlertDialog(onDismissRequest = app.video::dismissResume, padRuntime = runtime,

        title = { Text(localized(R.string.video_resume_title, "Riprendere il video?")) },

        text = {

            Text(localized(R.string.video_resume_description, "Riprendi da %1\$s oppure ricomincia dall’inizio.", formatTime(item.videoPosition)))

        },

        confirmButton = { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {

            Button({ app.video.confirmResume(true) }, Modifier.fillMaxWidth().heightIn(min = 40.dp).testTag("video-resume"), shape = GalleryShape, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {

                ControllerChoiceBadge("A"); Spacer(Modifier.width(8.dp)); Text(localized(R.string.video_resume_action, "Continua")) }

            }

            OutlinedButton({ app.video.confirmResume(false) }, Modifier.fillMaxWidth().heightIn(min = 40.dp).testTag("video-restart"), shape = GalleryShape, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {

                ControllerChoiceBadge("X"); Spacer(Modifier.width(8.dp)); Text(localized(R.string.video_restart_action, "Ricomincia")) }

            }

            TextButton(app.video::dismissResume, Modifier.fillMaxWidth().heightIn(min = 40.dp).testTag("video-resume-cancel"), shape = GalleryShape, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {

                ControllerChoiceBadge("B"); Spacer(Modifier.width(8.dp)); Text(localized(R.string.text_cancel_18c9d9, "Annulla")) }

            }

        } })

}



@Composable

internal fun ControllerChoiceBadge(key: String) {

    Surface(shape = androidx.compose.foundation.shape.CircleShape,

        border = androidx.compose.foundation.BorderStroke(1.dp, LocalContentColor.current), color = Color.Transparent) {

        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { Text(key, style = MaterialTheme.typography.labelMedium) }

    }

}

internal fun previewFilter(prefs: GalleryPreferences) = if (prefs.previewNearest) androidx.compose.ui.graphics.FilterQuality.None else androidx.compose.ui.graphics.FilterQuality.Low
