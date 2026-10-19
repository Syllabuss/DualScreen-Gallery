package it.ayngallery

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.zIndex
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

internal data class MainTab(val key: String, private val titleText: () -> String, val icon: Int) { val title: String get() = titleText() }
internal val MainTabs = listOf(MainTab("PHOTO", { localized(R.string.text_photos_494e08, "Foto") }, R.drawable.ph_images), MainTab("ALBUM", { localized(R.string.nav_albums, "Album") }, R.drawable.ph_folders),
    MainTab("VIDEO", { localized(R.string.nav_videos, "Video") }, R.drawable.ph_film_strip), MainTab("MUSIC", { localized(R.string.text_music_012df9, "Musica") }, R.drawable.ph_music_notes),
    MainTab("FAVORITE", { localized(R.string.text_favorites_d1d566, "Preferiti") }, R.drawable.ph_heart), MainTab("TV", { localized(R.string.tv_title, "TV online") }, R.drawable.ph_television_simple))
internal fun visibleNavbarTabs(prefs: GalleryPreferences): List<MainTab> =
    NavbarOrder.editable(prefs).filterNot { it in prefs.disabledCategories }.map { key -> MainTabs.first { it.key == key } }
internal fun navbarVisible(prefs: GalleryPreferences): Boolean =
    visibleNavbarTabs(prefs).size > 1 || InterfaceTools.SINGLE_NAVBAR !in prefs.hiddenTools
internal fun preferredGalleryTab(disabled: Set<String>, preferred: String = "ALBUM"): String = preferred.takeIf { it in NavbarOrder.DEFAULT && it !in disabled } ?: listOf("ALBUM", "PHOTO", "VIDEO", "MUSIC", "FAVORITE").first { it !in disabled }
internal fun visualPreviewSelection(data: CatalogSnapshot, selectedId: String?, fallback: MediaEntity? = null): MediaEntity? =
    selectedId?.let(data.byId::get)?.takeIf { it.kind in setOf("IMAGE", "GIF", "VIDEO") && data.visible(it) }
        ?: fallback?.takeIf { it.kind in setOf("IMAGE", "GIF", "VIDEO") && data.visible(it) }
internal fun initialVisualPreview(data: CatalogSnapshot, rememberedId: String?, persist: Boolean): MediaEntity? =
    if (persist) visualPreviewSelection(data, rememberedId) else null
internal fun initialPreviewId(data: CatalogSnapshot, rememberedId: String?, persist: Boolean, category: String): String? =
    initialVisualPreview(data, rememberedId, persist)?.id ?: data.visibleMedia.asSequence()
        .filter { it.available && matchesPreviewCategory(category, it) }
        .sortedByDescending { it.modified }
        .firstOrNull()?.id
internal fun sessionVisualPreview(data: CatalogSnapshot, visualId: String?, selectedId: String?, selectedThisSession: Boolean,
    persist: Boolean, category: String?, fallback: MediaEntity?): MediaEntity? {
    val selectionActive = selectedThisSession || persist
    val id = visualId.takeIf { selectionActive } ?: selectedId.takeIf { category == null && selectionActive }
    return visualPreviewSelection(data, id, fallback.takeIf { category != null })
}
private data class GalleryScene(
    val route: GalleryRoute,
    val viewer: String?,
    val viewerVisible: Boolean,
    val comparisonVisible: Boolean,
    val items: List<MediaEntity>,
    val loading: Boolean,
    val albumCover: Boolean = false,
    val lockedCoverAlbumId: String? = null,
    val fullPlayer: Boolean = false,
    val previewSection: String? = null,
    val descending: Boolean = true,
    val orderRevision: Long = 0,
    val previewFocusRevision: Int = 0,
    val videoSurface: Boolean = false,
    val frameVisible: Boolean = false,
    val settingsPanel: String? = null,
    val gridDirection: androidx.compose.ui.unit.IntOffset = androidx.compose.ui.unit.IntOffset.Zero,
    val tvPreview: Boolean = false,
) {
    val key: String
        get() = when {
            tvPreview -> "TV_PREVIEW"
            settingsPanel != null -> "SETTINGS_PREVIEW/$settingsPanel"
            frameVisible -> "FRAME"
            videoSurface -> "VIDEO/$viewer"
            comparisonVisible -> "COMPARE"
            fullPlayer -> "PLAYER"
            viewerVisible -> "VIEWER/$viewer" + if (albumCover) "/album" else "/media"
            else -> "${route.tab}/${route.album}/${route.collection}/${route.musicView}/${route.musicGroup}/${route.kind}"
        }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun GalleryHome(activity: PanelActivity) {
    val app = activity.application as GalleryApp
    val videoFolders by activity.windowModel.videoFolders.collectAsStateWithLifecycle()
    val vm = activity.windowModel
    val data by app.catalog.snapshot.collectAsStateWithLifecycle()
    val incomingRequest by activity.windowModel.incomingRequest.collectAsStateWithLifecycle()
    val received by activity.windowModel.received.collectAsStateWithLifecycle()
    val listing by vm.listing.collectAsStateWithLifecycle()
    val route by vm.route.collectAsStateWithLifecycle()
    val albumVisible by activity.isStartedFlow.collectAsState()
    val canSwap = remember(app.session.revision, app.session.state) { app.session.canSwap() }
    LaunchedEffect(route.tab, route.album, data.sources, albumVisible) {
        if (albumVisible) app.albums.show(activity.slot, route) else app.albums.leave(activity.slot)
    }
    DisposableEffect(activity) { onDispose { app.albums.leave(activity.slot) } }
    val albumGrid by vm.albumGrid.collectAsStateWithLifecycle()
    val albumColumns by (if (route.tab == "VIDEO" && videoFolders && route.album == null) vm.videoAlbumColumns else vm.albumColumns).collectAsStateWithLifecycle()
    val mediaGrid by vm.mediaGrid(route.tab).collectAsStateWithLifecycle()
    val mediaColumns by vm.mediaColumns(route.tab).collectAsStateWithLifecycle()
    val selectedAlbums by vm.selectedAlbums.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val descending by vm.descending.collectAsStateWithLifecycle()
    val orderRevision by vm.sortReset.collectAsStateWithLifecycle()
    val listingReady = listing.initialized && listing.query == query && listing.sort == sort && listing.descending == descending &&
        listing.route.copy(viewer = null, forceLibrary = false) == route.copy(viewer = null, forceLibrary = false)
    val items = if (listingReady) listing.items else emptyList()
    // Associate the reset with the newly sorted data, never with the still-presented old list.
    var deliveredSortRevision by remember { mutableLongStateOf(0L) }
    val readySortRevision = if (listingReady && (orderRevision == 0L || listing.descending == vm.sortResetDescending)) orderRevision else deliveredSortRevision
    SideEffect { deliveredSortRevision = readySortRevision }
    val prefs by app.preferences.state.collectAsStateWithLifecycle()
    val albumRootContent = route.album == null && (route.tab == "ALBUM" || route.tab == "VIDEO" && videoFolders)
    val tvFavorites = if (prefs.tvEnabled && prefs.tvInMediaFavorites && route.tab == "FAVORITE") app.tv.store.channels.filter { it.favorite } else emptyList()
    val hasGridContent = remember(data, albumRootContent, prefs.nestedAlbums, prefs.showEmptyFolders, query, items, tvFavorites) { if (albumRootContent) data.topAlbums(prefs).any {
        query.isBlank() || it.name.contains(query.trim(), true) || it.path.contains(query.trim(), true)
    } else items.isNotEmpty() || tvFavorites.isNotEmpty() }
    val showFolderShortcut = hasGridContent && (route.tab != "VIDEO" || data.visibleMedia.any { it.kind == "VIDEO" })
    val gridActive = if (albumRootContent) route.tab == "VIDEO" || albumGrid else mediaGrid
    val settingsCategoryFocus = remember { FocusRequester() }
    val navigationTabs = visibleNavbarTabs(prefs)
    DisposableEffect(data.unlockedAlbumIds) {
        if (data.unlockedAlbumIds.isNotEmpty()) activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        else activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
    }
    fun toolShown(tool: String) = route.tab == "FAVORITE" && tool == "VIEW" || InterfaceTools.shown(prefs, route.tab, tool)
    LaunchedEffect(prefs.disabledCategories, prefs.tvEnabled, prefs.tvInNavbar, route.tab) {
        if (route.tab == "TV" && !prefs.tvEnabled) vm.go(preferredGalleryTab(prefs.disabledCategories, prefs.defaultSection))
        if (route.tab in prefs.disabledCategories) vm.go(preferredGalleryTab(prefs.disabledCategories, prefs.defaultSection))
    }
    val frameConfig by vm.frameConfig.collectAsStateWithLifecycle()
    val playback by app.music.state.collectAsStateWithLifecycle()
    val comparison by app.comparison.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var helpContext by remember { mutableStateOf<PadContext?>(null) }
    var actionMedia by app.session::mediaOptions
    var previewControlsVisible by remember { mutableStateOf(true) }
    LaunchedEffect(playback.playing) { if (playback.playing) { app.session.musicMiniSeen = true; app.session.hidePausedMini = false } }
    var replacePin by remember { mutableStateOf<MediaEntity?>(null) }
    var fileRequest by remember { mutableStateOf<FileActionRequest?>(null) }
    var externalRequest by rememberSaveable { mutableStateOf<String?>(null) }
    val mediaEditor = app.session.mediaEditor
    @Composable fun editorControls(editor: MediaEditorPanel) {
        val close = { app.session.closeMediaEditor() }
        val accepted = { app.session.closeMediaEditor(); vm.go("OPERATIONS") }
        val updateRecipe: (String) -> Unit = app.session::updateMediaEditorRecipe
        when (ExternalRequest.decode(editor.request).targets.single().kind) {
            "VIDEO" -> VideoEditorScreen(activity, editor.request, data, prefs, close, accepted, updateRecipe)
            "AUDIO" -> AudioEditorScreen(activity, editor.request, data, prefs, close, accepted, updateRecipe)
            else -> ImageEditorScreen(activity, editor.request, data, prefs, close, accepted, updateRecipe)
        }
    }
    var collectionTargets by remember { mutableStateOf<List<String>?>(null) }
    var selected by remember(route.tab, route.album, route.collection, route.musicView, route.musicGroup, route.kind) { mutableStateOf(emptySet<String>()) }
    var mediaMenu by remember { mutableStateOf(false) }
    var multi by remember(route.tab, route.album, route.collection, route.musicView, route.musicGroup, route.kind) { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = multi) { selected = emptySet(); multi = false; mediaMenu = false }
    val libraryRole = activity.slot == app.session.librarySlot
    val chromeSurface = GalleryThemes.get(app.session.settingsDraft.takeIf { app.session.settingsOwner != null } ?: prefs).colors.surface.copy(alpha = if (prefs.backgroundMode == "PHOTO") .90f else .94f)
    val libraryRoute by (app.session.libraryModel()?.route ?: vm.route).collectAsStateWithLifecycle()
    LaunchedEffect(libraryRoute.tab) {
        fileRequest = null; externalRequest = null; collectionTargets = null
        if (prefs.tvEnabled && libraryRoute.tab != "TV" && !app.tv.artworkPickerPending) { app.tv.closeDraft() }
    }
    val settingsActive = app.session.settingsOwner != null
    val tvBrowserKeyboard = prefs.tvEnabled && libraryRole && !settingsActive && route.tab == "TV" && app.tv.browsing
    DisposableEffect(activity, tvBrowserKeyboard) {
        val previous = activity.window.attributes.softInputMode
        if (tvBrowserKeyboard) activity.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        onDispose { if (tvBrowserKeyboard) {
            activity.window.setSoftInputMode(previous)
            // Window.setSoftInputMode(0) marks the mode unspecified without clearing its old bits.
            val restored = activity.window.attributes
            restored.softInputMode = previous
            activity.window.attributes = restored
        } }
    }
    val returnedVideo = app.video.returnedFromPip && app.video.player != null && app.video.surface == "viewer" &&
        app.video.owner == activity.slot && !settingsActive && mediaEditor == null && libraryRoute.tab !in setOf("MUSIC", "PLAYER", "FTP")
    val settingsRevision = app.session.settingsTransitionRevision
    val settingsEntrance = remember(settingsRevision, prefs.reducedMotion) {
        androidx.compose.animation.core.Animatable(if (prefs.reducedMotion || settingsRevision == 0) 1f else 0f)
    }
    LaunchedEffect(settingsEntrance) {
        if (settingsEntrance.value < 1f) {
            // Let both panels settle their routes, insets and chrome while covered.
            withFrameNanos { }; withFrameNanos { }
            settingsEntrance.animateTo(1f, tween(220))
        }
    }
    val settingsPreview = app.session.settingsOwner != null && app.session.settingsOwner != activity.slot
    val settingsRouteFlow = app.session.settingsModel()?.route ?: vm.route
    val settingsRoute by settingsRouteFlow.collectAsStateWithLifecycle()
    val frameActive = !returnedVideo && libraryRoute.tab == "FRAME" && libraryRoute.viewer == null && !settingsActive && mediaEditor == null
    val frameModel = app.session.libraryModel() ?: vm
    val frameConfiguring by frameModel.frameConfiguring.collectAsStateWithLifecycle()
    val framePlaying by frameModel.framePlaying.collectAsStateWithLifecycle()
    val frameDimmed by frameModel.frameDimmed.collectAsStateWithLifecycle()
    val frameInteraction by frameModel.frameInteraction.collectAsStateWithLifecycle()
    LaunchedEffect(frameActive, framePlaying, frameConfiguring, frameInteraction, libraryRole, canSwap, albumVisible) {
        if (libraryRole) {
            if (frameActive && framePlaying && !frameConfiguring && canSwap && albumVisible) {
                kotlinx.coroutines.delay(10_000)
                frameModel.frameDimmed.value = true
            } else frameModel.frameDimmed.value = false
        }
    }
    val frameControls = frameActive && libraryRole && (canSwap || frameConfiguring)
    val activeVideo = app.video.player != null && app.video.surface == "viewer" && (app.video.liveChannel != null || data.byId[app.video.mediaId]?.let { it.kind == "VIDEO" && !it.convertedGif } == true)
    val videoPlaying by app.video.playing.collectAsStateWithLifecycle()
    LaunchedEffect(libraryRole, activeVideo, videoPlaying, app.video.dimNavigation, app.video.navigationInteraction,
        settingsActive, canSwap, albumVisible, prefs.videoDimSeconds) {
        if (libraryRole && activeVideo && videoPlaying && !app.video.dimNavigation && !settingsActive && canSwap && albumVisible && prefs.videoDimSeconds > 0) {
            val interaction = app.video.navigationInteraction
            kotlinx.coroutines.delay(prefs.videoDimSeconds * 1000L)
            if (interaction == app.video.navigationInteraction && app.video.playing.value && app.session.settingsOwner == null)
                app.video.dimNavigation = true
        }
    }
    val heldVideo = activeVideo
    val fullPreviewPlayer = !returnedVideo && !libraryRole && !settingsActive && mediaEditor == null && (libraryRoute.tab in setOf("MUSIC", "PLAYER") || libraryRoute.tab == "FAVORITE" && !heldVideo && app.session.pinnedPreviewId == null && data.byId[app.session.selectedMediaId]?.let { it.kind == "AUDIO" && it.favorite } == true)
    LaunchedEffect(libraryRole, libraryRoute.tab, app.video.floating, settingsActive) {
        if (libraryRole && !settingsActive && (libraryRoute.tab in setOf("PHOTO", "ALBUM", "VIDEO", "FAVORITE") || libraryRoute.tab == "TV" && app.video.liveChannel != null)) app.video.returnFromTools()
    }
    // A queue transition follows the new song; browsing another song alone does not start it.
    LaunchedEffect(playback.id) {
        if (libraryRole && route.tab in setOf("MUSIC", "PLAYER") && route.viewer == null && playback.id != null)
            app.session.selectMedia(playback.id)
    }
    LaunchedEffect(libraryRoute.tab) {
        if (libraryRole && libraryRoute.tab == "MUSIC" && app.session.selectedMediaId == null && playback.id != null) app.session.selectMedia(playback.id)
    }
    LaunchedEffect(route.tab, libraryRole, data.initialized) {
        if (libraryRole && route.tab == "FAVORITE" && data.initialized && vm.consumeFavoriteEntry()) {
            val favorites = data.visibleMedia.filter { it.favorite && it.available }
            app.session.updateMediaContext(favorites.sortedByDescending { it.favoriteAddedAt }.map { it.id })
            app.session.selectMedia(favorites.maxByOrNull { it.favoriteAddedAt }?.id)
        }
    }
    val previewCategory = libraryRoute.tab.takeIf { it in setOf("PHOTO", "VIDEO", "FAVORITE") && !(it == "VIDEO" && videoFolders && libraryRoute.album == null) }
    val categoryItems = remember(data, previewCategory) { if (previewCategory == null) emptyList() else data.visibleMedia.filter {
        it.available && matchesPreviewCategory(previewCategory, it) && (previewCategory != "FAVORITE" || it.favorite)
    }.sortedByDescending { if (previewCategory == "FAVORITE") it.favoriteAddedAt else it.modified } }
    val categoryItemIds = remember(categoryItems) { categoryItems.mapTo(hashSetOf()) { it.id } }
    val pinnedImage = app.session.pinnedPreviewId?.takeIf { prefs.previewPinEnabled }?.let(data.byId::get)?.takeIf { data.usable(it) && it.kind in setOf("IMAGE", "GIF") }
    LaunchedEffect(prefs.previewPinEnabled, pinnedImage?.id, data.initialized) {
        if (data.initialized && pinnedImage == null) { app.session.pinnedPreviewId = null; app.session.pinActivationRequestId = null }
    }
    LaunchedEffect(prefs.comparisonEnabled) {
        if (!prefs.comparisonEnabled) app.stopService(android.content.Intent(app, FloatingImageService::class.java))
    }
    val selectedVisual = pinnedImage ?: if (previewCategory != null) data.byId[app.session.selectedMediaId]?.takeIf { it.id in categoryItemIds } ?: categoryItems.firstOrNull()
        else if (libraryRoute.album == null && (libraryRoute.tab == "ALBUM" || libraryRoute.tab == "VIDEO" && videoFolders)) null
        else data.byId[app.session.selectedMediaId]?.takeIf { data.usable(it) && it.kind in setOf("IMAGE", "GIF", "VIDEO") }
    val previewAlbum = if (libraryRoute.tab == "ALBUM" || libraryRoute.tab == "VIDEO" && videoFolders && libraryRoute.album == null) app.session.previewAlbumId?.let(data.albumsById::get) ?: data.topAlbums(prefs).firstOrNull() else null
    val lockedPreviewAlbum = previewAlbum?.takeIf { selectedVisual == null && data.albumLocked(it.id) }
    val albumCover = if (lockedPreviewAlbum != null) data.lockedCover(lockedPreviewAlbum) else if (selectedVisual == null) previewAlbum?.let(data::cover) else null
    val pinnedVideo = app.video.mediaId?.takeIf { app.video.player != null && app.video.surface == "viewer" && app.video.owner == activity.slot &&
        heldVideo && (returnedVideo || libraryRoute.tab !in setOf("MUSIC", "PLAYER", "FTP")) &&
        (returnedVideo || !libraryRole || route.viewer != null) && !settingsActive && mediaEditor == null && !frameActive && !fullPreviewPlayer }?.let(data.byId::get)?.takeIf { data.usable(it) && it.kind == "VIDEO" && !it.convertedGif }
    val viewer = pinnedVideo?.id ?: if (previewCategory != null) route.viewer?.takeIf { it in categoryItemIds } ?: selectedVisual?.id
        else route.viewer ?: if (!libraryRole && !route.forceLibrary) (if (lockedPreviewAlbum != null) albumCover?.id else selectedVisual?.id ?: albumCover?.id) else null
    val showComparison = !returnedVideo && (route.tab == "COMPARE" || (!libraryRole && !route.forceLibrary && route.viewer == null && comparison.enabled))
    val showViewer = returnedVideo || !frameActive && !settingsPreview && !showComparison && !fullPreviewPlayer && (route.viewer != null || (!libraryRole && !route.forceLibrary))
    var welcomeDismissed by rememberSaveable { mutableStateOf(false) }
    val tutorialCompleted by app.preferences.tutorialCompleted.collectAsStateWithLifecycle()
    val tutorialStarted by app.preferences.tutorialStarted.collectAsStateWithLifecycle()
    val needsFolder = data.initialized && !tutorialCompleted && (tutorialStarted || app.session.tutorialInProgress || data.sources.none { it.enabled && it.id != DeviceGallery.SOURCE && !it.singleDocument })
    var usageAccessPrompt by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(libraryRole, canSwap, app.session.usageAccessGranted, app.session.shouldPromptUsageAccess, welcomeDismissed, needsFolder) {
        if (libraryRole && canSwap && app.session.shouldPromptUsageAccess && (!needsFolder || welcomeDismissed))
            usageAccessPrompt = true
    }
    val singleLiveFullscreen = app.session.isSingleScreen && activity.singleScreenVideoFullscreen &&
        app.video.liveChannel != null && app.video.player != null && !app.video.floating && !app.video.pipOpening && !settingsActive
    val immersiveViewer = (showViewer || fullPreviewPlayer || singleLiveFullscreen) && !showComparison
    LaunchedEffect(viewer, showViewer) {
        // Actions are shared by both panels; changing the presenter must not dismiss them.
    }
    val immersiveFrame = frameActive && !libraryRole
    DisposableEffect(immersiveViewer, immersiveFrame, prefs.showUpperStatusBar, activity.manuallyDimmed, app.video.dimNavigation, frameDimmed) {
        activity.immersivePreview(immersiveViewer || immersiveFrame)
        onDispose { activity.immersivePreview(false) }
    }
    val showPreviewPlayer = !frameActive && !libraryRole && !settingsActive && !showComparison && playback.id != null && mediaEditor == null
    val focusManager = LocalFocusManager.current
    val inputMode = LocalInputModeManager.current
    val rootFocus = remember { FocusRequester() }
    val saveable = rememberSaveableStateHolder()
    var searching by rememberSaveable { mutableStateOf(false) }
    var addFolderRole by remember { mutableStateOf<SourceRole?>(null) }
    val nextScene = GalleryScene(route, viewer, showViewer, showComparison, items,
        !frameActive && route.needsMediaListing && !showViewer && !fullPreviewPlayer && !showComparison && !listingReady,
        pinnedVideo == null && route.viewer == null && (selectedVisual == null || lockedPreviewAlbum != null) && albumCover != null, lockedPreviewAlbum?.id.takeIf { pinnedVideo == null }, fullPlayer = fullPreviewPlayer, previewSection = (if (pinnedImage != null) "PIN" else libraryRoute.tab).takeIf { !libraryRole && !settingsPreview }, descending = descending, orderRevision = readySortRevision, previewFocusRevision = if (pinnedVideo != null) app.video.previewRevision else if (pinnedImage != null) 0 else app.session.previewFocusRevision, videoSurface = showViewer && data.byId[viewer]?.let { it.kind == "VIDEO" && !it.convertedGif } == true, frameVisible = frameActive, settingsPanel = settingsRoute.tab.takeIf { settingsPreview }, gridDirection = if (pinnedImage != null) androidx.compose.ui.unit.IntOffset.Zero else app.session.gridPreviewDirection,
        tvPreview = prefs.tvEnabled && !libraryRole && !settingsActive && (libraryRoute.tab == "TV" || app.video.liveChannel != null && !app.video.floating && !app.video.pipOpening && libraryRoute.tab !in setOf("MUSIC", "PLAYER", "FTP") || libraryRoute.tab == "FAVORITE" && prefs.tvInMediaFavorites && app.tv.favoritePreview && !activeVideo))
    androidx.activity.compose.ReportDrawnWhen { data.initialized && !nextScene.loading }
    var presented by remember { mutableStateOf(nextScene) }
    LaunchedEffect(nextScene) { if (!nextScene.loading) presented = nextScene }
    LaunchedEffect(app.session.previewSwipe) {
        if (app.session.previewSwipe) {
            withFrameNanos { }; withFrameNanos { }
            app.session.previewSwipe = false
        }
    }
    var entered by remember { mutableStateOf(false) }
    val openingAlpha by animateFloatAsState(if (entered) 1f else 0f, tween(if (prefs.reducedMotion) 0 else 480, easing = androidx.compose.animation.core.FastOutSlowInEasing), label = localized(R.string.text_opening_0945e5, "apertura"))
    val swapAlpha by animateFloatAsState(if (app.session.swapFading) 0f else 1f, tween(if (prefs.reducedMotion) 0 else 180), label = localized(R.string.text_panel_swap_6e908b, "scambio pannelli"))
    LaunchedEffect(Unit) {
        rootFocus.requestFocus()
        if (!prefs.reducedMotion) {
            kotlinx.coroutines.delay(220)
            kotlinx.coroutines.withTimeoutOrNull(280) { app.catalog.snapshot.first { it.initialized } }
        }
        androidx.compose.runtime.withFrameNanos { }
        androidx.compose.runtime.withFrameNanos { }
        entered = true
    }
    LaunchedEffect(app.session.state.swapped) { vm.syncRole(app.session.state.swapped) }
    LaunchedEffect(route.album, data.excludedAlbums) { if (route.album in data.excludedAlbums) vm.back() }
    DisposableEffect(activity, focusManager, items, viewer, route.tab, data, showViewer, showComparison, settingsActive, settingsPreview) {
        activity.enterPadMode = { inputMode.requestInputMode(InputMode.Keyboard) }
        activity.clearPadFocus = { focusManager.clearFocus(force = true) }
        activity.moveFocus = moveFocus@ { action ->
            if (action == PadAction.DOWN && route.tab == "SETTINGS_HUB" && app.session.canSwap() && app.session.settingsHubBottomFocused) {
                app.session.settingsInfoFocused = true
                focusManager.clearFocus(force = true)
                return@moveFocus
            }
            if (frameModel.route.value.tab == "FRAME") frameModel.wakeFrame()
            app.video.wakeNavigation()
            val direction = when (action) {
                PadAction.UP -> FocusDirection.Up
                PadAction.DOWN -> FocusDirection.Down
                PadAction.LEFT -> FocusDirection.Left
                else -> FocusDirection.Right
            }
            if (settingsActive || settingsPreview) {
                val moved = focusManager.moveFocus(direction)
                if (!moved && action == PadAction.DOWN) activity.settingsScrollEnd?.invoke()
                if (moved) app.sounds.play("settings-navigation")
                return@moveFocus
            }
            // The library owns ordered list navigation and entry from the sort toolbar.
            if (activity.mediaGridFocus?.invoke(action) == true) return@moveFocus

            val index = items.indexOfFirst { it.id == app.session.selectedMediaId }
            val step = if (vm.mediaGrid(route.tab).value) vm.mediaColumns(route.tab).value else 1
            if (route.tab == "MUSIC" && (route.musicView == "TRACKS" || route.musicGroup != null) &&
                !activity.settingsHeaderHasFocus && action == PadAction.DOWN && index >= 0 && index + step >= items.size) {
                app.session.selectMedia(items.last().id)
                return@moveFocus
            }
            val moved = activity.albumGridFocus?.invoke(action) == true || focusManager.moveFocus(direction)
            if (!moved && action == PadAction.DOWN && route.tab == "SETTINGS_HUB" && app.session.canSwap()) {
                app.session.settingsInfoFocused = true
                focusManager.clearFocus(force = true)
            }
            if (moved && app.session.settingsOwner == activity.slot) app.sounds.play("settings-navigation")
        }
        activity.navigateMedia = { delta ->
            if (showComparison) app.comparison.update(app.comparison.state.value.navigate(app.session.selectedMediaId,
                app.session.mediaContext.filter { data.byId[it]?.let(data::visible) == true }, delta))
            else if (route.tab == "PLAYER" && !showViewer) { if (delta < 0) app.music.previous() else app.music.next() }
            else {
                val ongoingVideo = app.video.player != null && app.video.surface == "viewer" && data.byId[app.video.mediaId]?.let { !it.convertedGif } == true
                val context = if (ongoingVideo) app.video.context.mapNotNull { data.byId[it]?.takeIf(data::visible) }
                    else if (showViewer) app.session.mediaContext.mapNotNull { data.byId[it]?.takeIf(data::visible) } else items
                if (context.isNotEmpty()) {
                    val currentId = app.video.mediaId?.takeIf { ongoingVideo } ?: app.session.selectedMediaId?.takeIf { id -> context.any { it.id == id } } ?: vm.route.value.viewer ?: viewer
                    val index = context.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
                    val next = context[Math.floorMod(index + delta, context.size)]
                    val continueVideo = ongoingVideo && app.video.mediaId == currentId
                    val videoSlot = app.video.owner
                    val muted = app.video.player?.volume == 0f
                    app.session.selectMedia(next.id)
                    if (continueVideo) {
                        app.session.updateMediaContext(context.map { it.id })
                        if (next.kind == "VIDEO" && !next.convertedGif) app.video.play(next, videoSlot, muted, resume = false)
                        else app.video.stop(videoSlot)
                    }
                    if (route.viewer != null) vm.open(next.id)
                }
            }
        }
        onDispose { activity.enterPadMode = null; activity.clearPadFocus = null; activity.moveFocus = null; activity.navigateMedia = null }
    }
    DisposableEffect(activity, libraryRole, settingsActive, mediaEditor, frameActive, route, data, albumRootContent) {
        val handler: (PadAction) -> Boolean = { action ->
            if (libraryRole && app.session.canSwap() && route.tab == "TV" && !settingsActive && action == PadAction.BACK && app.tv.backNavigation()) true
            else if (libraryRole && !settingsActive && mediaEditor == null && !frameActive && !multi &&
                action == PadAction.BACK && app.video.closePausedViewer()) true
            else if (libraryRole && !settingsActive && mediaEditor == null && !frameActive && !multi && app.video.player == null &&
                action == PadAction.BACK && route.tab == "ALBUM" && route.album == null && vm.selectedAlbums.value.isEmpty() && !vm.albumMenu.value && app.session.visualSelectedThisSession) {
                app.video.stop(app.video.owner)
                app.session.dismissVisualPreview()
                true
            } else if (!libraryRole || settingsActive || mediaEditor != null || frameActive ||
                (route.tab !in setOf("ALBUM", "PHOTO", "VIDEO", "FAVORITE") && route.viewer == null) || (albumRootContent && route.viewer == null)) false
            else if (action == PadAction.CONFIRM && !activity.settingsHeaderHasFocus && !multi &&
                (route.tab == "VIDEO" || route.viewer != null || activity.focusedMediaItemId == app.session.selectedMediaId)) {
                val target = app.session.selectedMediaId?.let(data.byId::get)?.takeIf { it.kind == "VIDEO" && !it.convertedGif && data.usable(it) }
                val photo = app.session.selectedMediaId?.let(data.byId::get)?.takeIf { it.kind in setOf("IMAGE", "GIF") && data.usable(it) }
                if (target == null && photo != null && (app.video.player == null || app.video.floating) && (app.session.remotePreviewVisible(activity) || route.viewer != null)) {
                    app.session.interactImageOverlay()
                    app.session.imageOverlayVisible = !app.session.imageOverlayVisible
                    true
                } else if (target == null) false else {
                    if (app.video.mediaId == target.id && app.video.player != null) app.video.toggle()
                    else {
                        val preview = app.session.previewActivity()
                        if (preview == null) vm.open(target.id)
                        app.video.requestPlay(target, preview?.slot ?: activity.slot, false)
                    }
                    true
                }
            } else false
        }
        activity.videoControllerAction = handler
        val stick: (Float, Float, Long) -> Boolean = { x, y, now ->
            if (libraryRole && !settingsActive && mediaEditor == null && !frameActive && !multi &&
                route.tab in setOf("ALBUM", "PHOTO", "VIDEO", "FAVORITE")) app.video.leftStick(x, y, now) else false
        }
        activity.videoStickAction = stick
        onDispose {
            if (activity.videoControllerAction === handler) activity.videoControllerAction = null
            if (activity.videoStickAction === stick) activity.videoStickAction = null
        }
    }
    fun task(block: suspend () -> Unit) {
        scope.launch {
            try { withContext(Dispatchers.IO) { block() } }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { app.session.fail(error.message ?: localized(R.string.text_operation_failed_f17e12, "Operazione non riuscita")) }
        }
    }
    fun activate(item: MediaEntity) {
        if (multi) { selected = if (item.id in selected) selected - item.id else selected + item.id; return }
        if (app.session.pinnedPreviewId?.let { it != item.id } == true && item.kind in setOf("IMAGE", "GIF", "VIDEO")) {
            app.session.pinActivationRequestId = item.id
            return
        }
        val videoRunning = app.video.player != null && app.video.surface == "viewer" && app.catalog.snapshot.value.byId[app.video.mediaId]?.let { it.kind == "VIDEO" && !it.convertedGif } == true
        val currentPlayback = app.music.state.value
        val alreadySelected = app.session.selectedMediaId == item.id
        app.sounds.play("navigate")
        app.session.updateMediaContext(items.map { it.id }); app.session.selectMedia(item.id)
        if (item.kind == "AUDIO") {
            if (!alreadySelected) return
            if (!app.video.suspendedForTools && !app.video.floating) app.video.stop(app.video.owner)
            if (currentPlayback.id == item.id && currentPlayback.playing) app.music.pause()
            else app.music.play(item.id, items.filter { it.kind == "AUDIO" }.map { it.id })
            if (!app.session.remotePreviewVisible(activity)) vm.go("PLAYER")
        } else if (item.kind == "VIDEO" && !item.convertedGif) {
            if (!alreadySelected && !prefs.videoSkipPreview) {
                if (!videoRunning && !app.session.remotePreviewVisible(activity)) vm.open(item.id)
                return
            }
            if (app.session.isSingleScreen) vm.open(item.id)
            if (videoRunning && app.video.mediaId == item.id) app.video.toggle()
            else {
                app.video.requestPlay(item, app.session.previewActivity()?.slot ?: activity.slot, false)
            }
        } else {
            if (videoRunning && app.video.keepsPlaying && app.video.player?.isPlaying == true) app.video.suspendForMedia()
            else if (videoRunning) app.video.stop(app.video.owner)
            if (!app.session.remotePreviewVisible(activity)) vm.open(item.id)
        }
    }
    if (app.session.pinActivationRequestId != null && (!libraryRole || !app.session.canSwap())) {
        GalleryDesign(prefs) { PreviewPinDialog(app) { app.session.confirmPinnedActivation() } }
    }
    if (app.session.floatingImageRequestId != null && (!libraryRole || !app.session.canSwap())) FloatingImagePermissionDialog(app, activity)
    fun external(action: ExternalAction, targets: List<MediaEntity>, copy: ShareCopyOptions? = null) {
        try { externalRequest = ExternalRequest(action, targets.map(ExternalTarget::from), copy).encode(); actionMedia = null }
        catch (failure: IllegalArgumentException) { app.session.fail(failure.message ?: localized(R.string.text_selection_unavailable_3930d5, "Selezione non disponibile")) }
    }
    fun compare(item: MediaEntity) {
        if (data.byId[item.id]?.let(data::usable) != true) { app.session.fail(localized(R.string.text_media_unavailable_for_comparison_8fd848, "Media non disponibile per il confronto")); return }
        val next = app.comparison.state.value.pin(item.id, app.session.mediaContext)
        if (next == null) replacePin = item else { app.comparison.update(next); vm.go("COMPARE") }
    }
    DisposableEffect(activity, settingsPreview, settingsActive, libraryRole, showComparison, showViewer, viewer, route, data, playback.id, app.session.selectedMediaId, prefs.navbarOrder, prefs.disabledCategories, prefs.ftpEnabled, prefs.tvEnabled, prefs.tvInNavbar, prefs.tvInMediaFavorites, navigationTabs, frameControls, hasGridContent, gridActive, albumColumns, mediaColumns) {
        activity.padContext = when {
            frameControls -> PadContext.LIBRARY
            settingsPreview -> PadContext.DIALOG
            showComparison || showViewer || route.tab in setOf("FRAME", "RECEIVED") -> PadContext.VIEWER
            route.tab in setOf("MUSIC", "PLAYER") -> PadContext.PLAYER
            route.tab in MainTabs.map { it.key } || route.tab in setOf("COLLECTION", "FTP") -> PadContext.LIBRARY
            else -> PadContext.DIALOG
        }
        val execute: (PadAction) -> Unit = { action ->
            if (frameActive) frameModel.wakeFrame()
            val frameHandled = (route.tab == "FRAME" && !showViewer && activity.frameAction?.invoke(action) == true) ||
                (route.tab == "RECEIVED" && activity.receivedAction?.invoke(action) == true)
            if (!frameHandled) {
            val current = app.comparison.state.value
            val activePane = current.panes.firstOrNull { it.id == current.active }
            val id = when {
                showComparison && activePane != null -> current.target(activePane, app.session.selectedMediaId)
                showViewer -> viewer
                route.tab == "PLAYER" -> app.music.state.value.id
                route.tab in setOf("RECEIVED", "FTP") -> null
                else -> activity.focusedMediaItemId ?: app.session.selectedMediaId
            }
            val tvContext = prefs.tvEnabled && (route.tab == "TV" || route.tab == "FAVORITE" && prefs.tvInMediaFavorites && app.tv.favoritePreview)
            val target = if (tvContext) null else data.byId[id]?.takeIf(data::usable)
            when (action) {
                PadAction.MEDIA_PREVIOUS -> if (app.video.liveChannel != null) app.tv.changeChannel(-1, app.session.previewActivity()?.slot ?: activity.slot) else if (!tvContext) activity.navigateMedia?.invoke(-1)
                PadAction.MEDIA_NEXT -> if (app.video.liveChannel != null) app.tv.changeChannel(1, app.session.previewActivity()?.slot ?: activity.slot) else if (!tvContext) activity.navigateMedia?.invoke(1)
                PadAction.TRACK_PREVIOUS -> app.music.previous()
                PadAction.TRACK_NEXT -> app.music.next()
                PadAction.PLAY_PAUSE -> if (tvContext) app.video.toggle() else app.music.toggle()
                PadAction.SEEK_BACK -> app.music.seek((app.music.state.value.position - 10_000).coerceAtLeast(0))
                PadAction.SEEK_FORWARD -> app.music.seek(app.music.state.value.position + 10_000)
                PadAction.FAVORITE -> if (tvContext) app.tv.selected?.let { entry -> task { app.tv.requestFavorite(entry) } } else target?.let { task { app.catalog.favorite(it.id) } }
                PadAction.SETTINGS -> {
                    if (tvContext) app.tv.editSelected() else {
                    val focusedAlbum = if ((route.tab == "ALBUM" || route.tab == "VIDEO" && videoFolders) && route.album == null)
                        app.session.previewAlbumId?.takeIf(data.albumsById::containsKey) else null
                    when {
                        focusedAlbum != null -> { app.session.openAlbumOptions(focusedAlbum) }
                        target != null -> actionMedia = target
                        else -> app.session.openSettings(activity)
                    }
                }
                }
                PadAction.DELETE -> target?.let { fileRequest = FileActionRequest("TRASH", listOf(it)) }
                PadAction.MENU -> {
                    if (tvContext) app.tv.editSelected() else {
                    val focusedAlbum = if ((route.tab == "ALBUM" || route.tab == "VIDEO" && videoFolders) && route.album == null)
                        app.session.previewAlbumId?.takeIf(data.albumsById::containsKey) else null
                    when {
                        focusedAlbum != null -> { app.session.openAlbumOptions(focusedAlbum) }
                        target != null -> actionMedia = target
                        else -> app.session.openSettings(activity)
                    }
                }
                }
                PadAction.DISPLAY -> vm.go("DISPLAY")
                PadAction.GRID_SMALLER, PadAction.GRID_LARGER -> if (!showViewer && hasGridContent && (gridActive || route.tab in setOf("VIDEO", "FAVORITE")) && route.tab in MainTabs.map { it.key }) {
                    val delta = if (action == PadAction.GRID_SMALLER) 1 else -1
                    if (route.tab == "VIDEO" && !gridActive) app.preferences.videoSmallList(action == PadAction.GRID_SMALLER)
                    else if (route.tab == "FAVORITE" && !gridActive) app.preferences.favoriteSmallList(action == PadAction.GRID_SMALLER)
                    else if (albumRootContent) vm.albumColumns(albumColumns + delta) else vm.mediaColumns(mediaColumns + delta)
                }
                PadAction.TOGGLE_VIEW -> if (route.tab == "TV") app.tv.store.grid(!app.tv.store.grid) else if (!showViewer && hasGridContent && route.tab in MainTabs.map { it.key }) {
                    if (route.tab == "VIDEO" && route.album == null) vm.cycleVideoView()
                    else if (albumRootContent) { if (!prefs.albumCarousel) vm.albumGrid(!albumGrid) } else vm.mediaGrid(!mediaGrid)
                }
                PadAction.SWAP -> app.session.swapAnimated()
                PadAction.FRAME -> if (route.tab == "FRAME") vm.back() else vm.startFrame()
                PadAction.PIN -> if (showComparison && activePane != null && target != null) {
                    app.comparison.update(current.copy(panes = current.panes.map { if (it.id != activePane.id) it
                        else if (it.pinned) it.copy(pinned = false, mediaId = null, view = ViewTransform())
                        else it.copy(pinned = true, mediaId = target.id, context = app.session.mediaContext) }))
                } // Comparison is dormant: legacy profiles must not open it from the gallery.
                PadAction.TAB_PREVIOUS, PadAction.TAB_NEXT -> {
                    val category = if (route.tab == "TV" && !prefs.tvInNavbar) "VIDEO" else route.tab
                    val index = navigationTabs.indexOfFirst { it.key == category }.coerceAtLeast(0)
                    vm.go(navigationTabs[Math.floorMod(index + if (action == PadAction.TAB_NEXT) 1 else -1, navigationTabs.size)].key)
                }
                PadAction.ZOOM_IN, PadAction.ZOOM_OUT, PadAction.PAN_UP, PadAction.PAN_DOWN, PadAction.PAN_LEFT, PadAction.PAN_RIGHT ->
                    activity.padTransform?.invoke(action, target?.id)
                else -> Unit
            }
            }
        }
        activity.padAction = execute
        onDispose { if (activity.padAction === execute) activity.padAction = null }
    }
    val settingsSave = remember { mutableStateOf<SettingsSave?>(null) }
    var discardSettings by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = settingsSave.value?.let { it.dirty || it.busy } == true) { if (settingsSave.value?.busy != true) discardSettings = true }
    if (discardSettings) GalleryDesign(prefs) { SettingsDiscardDialog(dismiss = { discardSettings = false }, discard = {
        discardSettings = false
        settingsSave.value?.discard?.invoke()
        if (!app.session.settingsBack()) vm.back()
    }) }
    CompositionLocalProvider(LocalSettingsSave provides settingsSave) { GalleryDesign(app.session.settingsDraft.takeIf { settingsActive } ?: prefs, animatePalette = needsFolder) {
        Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().then(if (libraryRole && !settingsActive && !immersiveViewer && !frameActive) Modifier.galleryBackground(app, data, prefs) else Modifier.background(MaterialTheme.colorScheme.background))
            .then(if (immersiveViewer || immersiveFrame) Modifier else Modifier.windowInsetsPadding(if (app.session.isSingleScreen) WindowInsets.safeDrawing.exclude(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)).let { if (tvBrowserKeyboard) it.exclude(WindowInsets.ime) else it } else if (tvBrowserKeyboard) WindowInsets.safeDrawing.exclude(WindowInsets.ime) else WindowInsets.safeDrawing))
            .then(if (libraryRole && activeVideo && !settingsActive) Modifier.pointerInput(app.video) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    app.video.wakeNavigation()
                }
            } else Modifier)
            .then(if (frameActive && libraryRole) Modifier.pointerInput(frameModel) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    if (!frameModel.frameDimmed.value) frameModel.wakeFrame()
                }
            } else Modifier).graphicsLayer {
                val entrance = if (prefs.reducedMotion) 1f else openingAlpha
                alpha = entrance * swapAlpha * settingsEntrance.value
                scaleX = 1f; scaleY = 1f
            }
            .focusRequester(rootFocus).focusable()) {
            if (mediaEditor == null && !settingsPreview && !immersiveFrame && !immersiveViewer) Box(Modifier.fillMaxWidth()) { Row(Modifier.fillMaxWidth().background(chromeSurface).testTag("gallery-header").onFocusChanged { activity.settingsHeaderHasFocus = it.hasFocus }.focusGroup().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (route.album == null && (route.musicGroup != null || route.tab !in MainTabs.map { it.key }))
                    GalleryIconButton(R.drawable.ph_arrow_left, localized(R.string.text_back_804268, "Indietro")) { activity.onBackPressedDispatcher.onBackPressed() }
                val librarySearch = !showViewer && !showComparison && route.tab in NavbarOrder.DEFAULT
                val albumRoot = librarySearch && route.album == null && (route.tab == "ALBUM" || route.tab == "VIDEO" && videoFolders)
                if (librarySearch && multi) {
                    Text(localized(R.string.text_1_s_selected_e2edc4, "%1\$s selezionati", selected.size), Modifier.weight(1f))
                    GalleryIconButton(R.drawable.ph_dots_three, localized(R.string.text_selected_media_options_079c9a, "Opzioni media selezionati")) { mediaMenu = true }
                } else if (albumRoot && selectedAlbums.isNotEmpty()) {
                    Text(localized(R.string.text_1_s_selected_e2edc4, "%1\$s selezionati", selectedAlbums.size), Modifier.weight(1f))
                    GalleryIconButton(R.drawable.ph_checks, localized(R.string.text_select_all_0e5b81, "Seleziona tutti")) { vm.selectAlbums(data.topAlbums(prefs).map { it.id }.toSet()) }
                    GalleryIconButton(R.drawable.ph_x, localized(R.string.text_deselect_all_d1cf57, "Deseleziona tutti")) { vm.selectAlbums(emptySet()) }
                    GalleryIconButton(R.drawable.ph_dots_three, localized(R.string.text_selected_album_options_839aa6, "Opzioni album selezionati")) { vm.albumMenu(true) }
                } else {
                if (libraryRole && librarySearch && route.tab == "VIDEO" && prefs.tvEnabled && !prefs.tvInNavbar && !searching && selectedAlbums.isEmpty()) {
                    GalleryIconButton(R.drawable.ph_television_simple, localized(R.string.tv_title, "TV online"), Modifier.testTag("video-open-tv")) { app.tv.enter(); vm.go("TV") }
                }
                if (librarySearch && route.tab == "FAVORITE" && toolShown("FILTER") && (tvFavorites.isNotEmpty() || data.visibleMedia.any { it.available && it.favorite })) FavoriteFilters(vm, route.kind)
                if (librarySearch && route.tab != "MUSIC" && hasGridContent && gridActive && toolShown("ZOOM")) {
                    GalleryIconButton(R.drawable.ph_magnifying_glass_plus, localized(R.string.text_larger_albums_806813, "Ingrandisci album"), enabled = (if (albumRoot) albumColumns else mediaColumns) > 2) { if (albumRoot) vm.albumColumns(albumColumns - 1) else vm.mediaColumns(mediaColumns - 1) }
                    GalleryIconButton(R.drawable.ph_magnifying_glass_minus, localized(R.string.text_smaller_albums_52ae9a, "Rimpicciolisci album")) { if (albumRoot) vm.albumColumns(albumColumns + 1) else vm.mediaColumns(mediaColumns + 1) }
                }
                if (librarySearch && route.tab in setOf("VIDEO", "FAVORITE") && hasGridContent && !gridActive && toolShown("ZOOM")) {
                    val small by (if (route.tab == "VIDEO") app.preferences.videoSmallList else app.preferences.favoriteSmallList).collectAsStateWithLifecycle()
                    GalleryIconButton(if (small) R.drawable.ph_magnifying_glass_plus else R.drawable.ph_magnifying_glass_minus, localized(if (small) R.string.text_larger_albums_806813 else R.string.text_smaller_albums_52ae9a, if (small) "Ingrandisci album" else "Rimpicciolisci album")) { if (route.tab == "VIDEO") app.preferences.videoSmallList(!small) else app.preferences.favoriteSmallList(!small) }
                }
                if (librarySearch && showFolderShortcut && route.tab in setOf("ALBUM", "PHOTO", "VIDEO") && !toolShown("ZOOM"))
                    GalleryIconButton(R.drawable.ph_plus_circle, localized(R.string.choose_media_folder, "Aggiungi cartella"), Modifier.testTag("${route.tab.lowercase()}-add-folder")) { addFolderRole = SourceRole.VISUAL }
                if (librarySearch && hasGridContent && MediaTimeline.supported(route) && toolShown("TIMELINE")) TimelineMenu(app, prefs, route.tab)
                if (librarySearch && route.tab == "MUSIC" && data.visibleMedia.any { it.available && it.kind == "AUDIO" } && !multi && toolShown("MUSIC_MODE")) MusicModeMenu(vm, route.musicView)
                if (librarySearch && searching) {
                    OutlinedTextField(query, vm::search, Modifier.weight(1f).height(48.dp).testTag("library-search"), singleLine = true, textStyle = MaterialTheme.typography.bodyMedium,
                        placeholder = { Text(if (route.tab == "ALBUM") localized(R.string.text_search_albums_f6f4bb, "Cerca album") else localized(R.string.text_search_collection_053f46, "Cerca nella raccolta"), style = MaterialTheme.typography.bodyMedium) }, shape = GalleryShape)
                    GalleryIconButton(R.drawable.ph_arrow_left, localized(R.string.text_close_search_9cf7ab, "Chiudi ricerca")) { vm.search(""); searching = false }
                    if (showFolderShortcut && route.tab in setOf("ALBUM", "PHOTO", "VIDEO") && toolShown("ZOOM"))
                        GalleryIconButton(R.drawable.ph_plus_circle, localized(R.string.choose_media_folder, "Aggiungi cartella"), Modifier.testTag("${route.tab.lowercase()}-add-folder")) { addFolderRole = SourceRole.VISUAL }
                } else {
                    if (librarySearch && route.album == null && route.musicGroup == null) Spacer(Modifier.weight(1f))
                    else {
                    val sectionIcon = when (route.tab) {
                        "SETTINGS" -> when (app.session.settingsSection) { "INTERFACE" -> R.drawable.ph_squares_four; "PLAYBACK" -> R.drawable.ph_play; else -> R.drawable.ph_squares_four }
                        "SETTINGS_SOUNDS" -> R.drawable.ph_music_notes; "SETTINGS_INPUT" -> R.drawable.ph_game_controller
                        "SETTINGS_DEVICE" -> R.drawable.ph_monitor_arrow_up; "TRASH" -> R.drawable.ph_desktop_tower; "SETTINGS_INFO" -> R.drawable.ph_info
                        else -> null
                    }
                    sectionIcon?.let { GalleryIcon(it, null, Modifier.size(24.dp)); Spacer(Modifier.width(10.dp)) }
                    Text(if (showComparison) localized(R.string.text_comparison_5db262, "Confronto") else if (showViewer) localized(R.string.text_preview_e9bcae, "Anteprima") else route.album?.let { data.albumsById[it]?.name }
                        ?: MainTabs.find { it.key == route.tab }?.title ?: when (route.tab) {
                            "SOURCES" -> localized(R.string.media_folders_title, "Cartelle multimediali"); "RELINK" -> localized(R.string.text_relink_files_f3aef2, "Ricollega i file"); "RELINK_FOLDER" -> localized(R.string.text_relink_a_folder_d8543d, "Ricollega una cartella"); "HIDDEN" -> localized(R.string.text_hidden_albums_5ff5a5, "Album nascosti"); "COLLECTION" -> localized(R.string.text_collections_2ffdd0, "Raccolte");
                            "FTP" -> "FTP"
                            "SETTINGS_PIN" -> localized(R.string.album_pin_settings, "PIN degli album")
                            "SETTINGS_SOUNDS" -> localized(R.string.sounds_title, "Suoni"); "SETTINGS_LANGUAGE" -> localized(R.string.language_title, "Lingua"); "SETTINGS_INFO" -> localized(R.string.info_title, "Info e permessi")
                            "SETTINGS" -> when (app.session.settingsSection) {
                                "THEME" -> localized(R.string.text_appearance_f9ce8f, "Tema")
                                "INTERFACE" -> localized(R.string.text_interface_a86468, "Interfaccia")
                                "PLAYBACK" -> localized(R.string.text_playback_2b0fe5, "Riproduzione")
                                else -> localized(R.string.text_settings_7b855c, "Impostazioni")
                            }
                            "SETTINGS_HUB" -> localized(R.string.text_settings_7b855c, "Impostazioni"); "SETTINGS_LIBRARY" -> localized(R.string.library_settings_title, "Libreria"); "SETTINGS_PERMISSIONS" -> localized(R.string.permissions_settings_title, "Permessi"); "SETTINGS_DEVICE" -> localized(R.string.text_screens_and_tools_153eab, "Schermi e backup"); "SETTINGS_INPUT" -> localized(R.string.text_controls_3c6824, "Comandi"); "MAINTENANCE" -> localized(R.string.text_screens_and_tools_153eab, "Schermi e backup"); "CONTROLS" -> localized(R.string.text_controls_54b475, "Controlli"); "TRANSFER" -> localized(R.string.text_save_settings_4daada, "Conserva le impostazioni"); "DISPLAY" -> localized(R.string.text_two_screens_dde4ec, "Due schermi"); "PLAYER" -> localized(R.string.text_playing_5ef505, "In ascolto")
                            "TRASH" -> localized(R.string.tools_title, "Strumenti"); "OPERATIONS" -> localized(R.string.text_operations_389997, "Operazioni"); "DUPLICATES" -> localized(R.string.text_identical_duplicates_5f7af8, "Duplicati identici"); "FRAME" -> localized(R.string.text_photo_frame_b05b1e, "Cornice"); "TOUCH" -> localized(R.string.text_touch_gestures_241cad, "Gesti touch"); else -> localized(R.string.text_library_93c372, "Libreria")
                        }, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                if (librarySearch && showFolderShortcut && route.tab in setOf("ALBUM", "PHOTO", "VIDEO") && !multi && toolShown("ZOOM"))
                    GalleryIconButton(R.drawable.ph_plus_circle, localized(R.string.choose_media_folder, "Aggiungi cartella"), Modifier.testTag("${route.tab.lowercase()}-add-folder")) { addFolderRole = SourceRole.VISUAL }
                    if (librarySearch && toolShown("SEARCH")) GalleryIconButton(R.drawable.ph_magnifying_glass, if (query.isBlank()) localized(R.string.text_search_and_filter_c3edb2, "Cerca e filtra") else localized(R.string.text_edit_search_1_s_b5e403, "Modifica ricerca: %1\$s", query)) { searching = true }
                    if (librarySearch && hasGridContent && !(albumRootContent && prefs.albumCarousel) && toolShown("VIEW")) GalleryIconButton(if (route.tab == "VIDEO" && route.album == null && videoFolders) R.drawable.ph_folders else if (if (albumRoot) albumGrid else mediaGrid) R.drawable.ph_list else R.drawable.ph_squares_four,
                        if (if (albumRoot) albumGrid else mediaGrid) localized(R.string.text_list_view_8cb747, "Vista elenco") else localized(R.string.text_grid_view_c4886b, "Vista griglia")) {
                        if (route.tab == "VIDEO" && route.album == null) vm.cycleVideoView() else if (albumRoot) vm.albumGrid(!albumGrid) else vm.mediaGrid(!mediaGrid)
                    }
                }
                if (searching && toolShown("FILTER") && !showViewer && !showComparison && (route.tab in setOf("PHOTO") || route.tab == "ALBUM" && route.album != null)) {
                    FormatFilter(vm, route)
                }
                if (route.tab == "SETTINGS_HUB") {
                    val preferredSettingsGrid by app.preferences.settingsGrid.collectAsStateWithLifecycle()
                    val settingsGrid = preferredSettingsGrid && !app.session.isSingleScreen
                    if (!app.session.isSingleScreen) GalleryIconButton(if (settingsGrid) R.drawable.ph_rows else R.drawable.ph_grid_nine,
                        localized(if (settingsGrid) R.string.settings_rows_title else R.string.settings_grid_title, "Vista"),
                        Modifier.testTag("settings-view-toggle").focusProperties { down = settingsCategoryFocus }) { app.preferences.settingsGrid(!settingsGrid) }
                    GalleryIconButton(R.drawable.ph_trash, localized(R.string.text_trash_9a3a36, "Cestino"),
                        Modifier.testTag("settings-trash-shortcut").focusProperties { down = settingsCategoryFocus }) {
                        app.session.settingsSection = "TRASH"; app.session.recoveryTab = "TRASH"; app.session.trashPreviewId = null; vm.go("TRASH")
                    }
                }
                if (route.tab.startsWith("SETTINGS") || route.tab in setOf("CONTROLS", "TOUCH", "TRANSFER", "DISPLAY", "MAINTENANCE")) {
                    val save = settingsSave.value
                    if (save != null) GalleryIconButton(R.drawable.ph_floppy_disk, localized(R.string.text_save_c5997e, "Salva"), Modifier.testTag(save.tag), enabled = save.enabled) { save.save() }
                } else if (frameActive && libraryRole && framePlaying && canSwap) GalleryIconButton(R.drawable.ph_moon_stars, localized(R.string.video_dim_navigation, "Oscura lo schermo di navigazione"), Modifier.testTag("frame-dim-toggle")) { frameModel.frameDimmed.value = true }
                else if (route.tab == "ALBUM" && route.album != null && !showViewer) OpenAlbumMenu(activity, data, route.album!!)
                else if (!frameActive && route.tab !in setOf("FTP", "TRASH", "DUPLICATES", "SOURCES", "COLLECTION", "HIDDEN")) GalleryIconButton(R.drawable.ph_gear_six, localized(R.string.text_settings_7b855c, "Impostazioni")) { app.session.openSettings(activity) }
                }
            }
                if (libraryRole && route.tab == "TV" && app.tv.browsing && !settingsActive) {
                    Box(Modifier.align(Alignment.Center)) { TvNetworkInfo() }
                } else if (libraryRole && activeVideo && videoPlaying && canSwap && !settingsActive && !app.video.dimNavigation && !searching && !multi && selectedAlbums.isEmpty()) {
                    GalleryIconButton(R.drawable.ph_moon_stars, localized(R.string.video_dim_navigation, "Oscura lo schermo di navigazione"),
                        Modifier.align(Alignment.Center).zIndex(2f).background(chromeSurface.copy(alpha = 1f), CircleShape).focusProperties { canFocus = false }.testTag("navigation-video-moon")) { app.video.dimNavigation = true }
                }
            }
            val touchContext = when {
                showComparison || showViewer || route.tab in setOf("FRAME", "RECEIVED") -> PadContext.VIEWER
                route.tab == "MUSIC" || route.tab == "PLAYER" -> PadContext.PLAYER
                route.tab in MainTabs.map { it.key } || route.tab == "COLLECTION" -> PadContext.LIBRARY
                else -> PadContext.DIALOG
            }
            Box(Modifier.weight(1f).testTag("gallery-content").galleryEdgeBack(mediaEditor == null && route.album != null && !showViewer) { activity.onBackPressedDispatcher.onBackPressed() }.galleryTouch(prefs.touch, touchContext,
                enabled = !settingsPreview && data.initialized && listingReady && !multi && helpContext == null && actionMedia == null && replacePin == null &&
                    fileRequest == null && externalRequest == null && mediaEditor == null && collectionTargets == null && incomingRequest == null,
                target = "${presented.key}/$viewer/${app.session.selectedMediaId}/${comparison.active}/${playback.id}", action = activity::performTouchAction)) {
                if (!data.initialized) Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    if (!prefs.reducedMotion) CircularProgressIndicator()
                    Text(localized(R.string.text_preparing_your_library_373c55, "Preparazione della tua raccolta…"), Modifier.padding(16.dp))
                } else if (mediaEditor != null) {
                    if (mediaEditor.ownerSlot == activity.slot) editorControls(mediaEditor)
                    else MediaEditPreviewPanel(activity, mediaEditor, data, prefs)
                } else LibraryPullRefreshBox(app, libraryRole && !settingsPreview && !showViewer && !showComparison && !multi && selectedAlbums.isEmpty() &&
                    route.tab in setOf("ALBUM", "PHOTO", "VIDEO", "MUSIC", "FAVORITE"), Modifier.fillMaxSize().testTag("library-pull-refresh")) {
                    val sceneTransition = androidx.compose.animation.core.updateTransition(presented, label = "gallery-navigation")
                    val sceneDuration = if (settingsEntrance.value < 1f || prefs.reducedMotion || app.session.swapBusy || app.session.previewSwipe) 0 else
                        if (sceneTransition.currentState.settingsPanel != null || presented.settingsPanel != null || sceneTransition.currentState.route.tab.startsWith("SETTINGS") || presented.route.tab.startsWith("SETTINGS")) 320 else 240
                    sceneTransition.Crossfade(modifier = Modifier.fillMaxSize(), contentKey = { it.key }, animationSpec = tween(sceneDuration)) { scene ->
                    val progress by sceneTransition.animateFloat(transitionSpec = { tween(sceneDuration) }, label = "grid-preview-position") { target -> if (target.key == scene.key) 1f else 0f }
                    val direction = presented.gridDirection
                    val gridMove = scene.viewerVisible && presented.viewerVisible && scene.previewSection == presented.previewSection &&
                        scene.settingsPanel == null && presented.settingsPanel == null && direction != androidx.compose.ui.unit.IntOffset.Zero && prefs.gridPreviewMotion && !prefs.reducedMotion
                    Box(Modifier.fillMaxSize().graphicsLayer {
                        if (gridMove) {
                            val offset = (1f - progress) * if (scene.key == presented.key) -1f else 1f
                            translationX = offset * direction.x * size.width
                            translationY = offset * direction.y * size.height
                        }
                    }) {
                    val destination = scene.key
                    val route = scene.route
                    val items = scene.items.mapNotNull { data.byId[it.id]?.takeIf(data::visible) }
                    val viewer = scene.viewer
                    if (singleLiveFullscreen || prefs.tvEnabled && !libraryRole && app.video.liveChannel != null && !app.video.floating && !app.video.pipOpening && !settingsActive && libraryRoute.tab !in setOf("MUSIC", "PLAYER", "FTP") && scene.settingsPanel == null) TvVideoPreview(activity)
                    else if (prefs.tvEnabled && !settingsActive && !libraryRole && libraryRoute.tab == "FAVORITE" && prefs.tvInMediaFavorites && app.tv.favoritePreview && !activeVideo && scene.settingsPanel == null) TvScreen(activity, preview = true)
                    else if (prefs.tvEnabled && !settingsActive && libraryRoute.tab == "TV" && scene.settingsPanel == null) TvScreen(activity, preview = !libraryRole && canSwap)
                    else if (scene.frameVisible) PhotoFrameScreen(activity, data, prefs, blocked = false, actions = { actionMedia = it }, compare = ::compare, remove = { fileRequest = FileActionRequest("TRASH", listOf(it)) }, task = ::task, model = frameModel, previewOnly = !libraryRole, controlsOnly = libraryRole && canSwap)
                    else if (scene.fullPlayer) PreviewMusicPanel(app, playback, data, prefs, fullPlayer = true, onActions = { actionMedia = it }, onChooseFolder = { addFolderRole = SourceRole.MUSIC })
                    else if (scene.settingsPanel == "DUPLICATES" || scene.settingsPanel == "TRASH" && app.session.recoveryTab == "DUPLICATES") DuplicateScreen(activity, data, prefs, ::task, { fileRequest = it }, resultsOnly = true)
                    else if (scene.settingsPanel == "TRASH" && app.session.recoveryTab == "FTP") NetworkScreen(activity, upper = true)
                    else if (scene.settingsPanel == "TRASH" && app.session.recoveryTab == "TRASH") TrashPreviewScreen(app)
                    else if (scene.settingsPanel == "CONTROLS") ControllerQuickGuide()
                    else if (scene.settingsPanel == "SETTINGS_HUB") SettingsInfoCard(app)
                    else if (scene.settingsPanel == "SETTINGS_INFO") AppInfoScreen(app.session.settingsActivity(), part = "DETAILS")
                    else if (scene.settingsPanel == "SETTINGS_SOUNDS") SoundTraceScreen(app)
                    else if (scene.settingsPanel == "SETTINGS_DEVICE") DualScreenDisclaimer(app)
                    else if (scene.settingsPanel != null) SettingsPreviewScreen(app, data, app.session.settingsDraft ?: prefs,
                        app.session.settingsSection.takeIf { settingsRoute.tab == "SETTINGS" }.orEmpty())
                    else if ((scene.route.tab == "FTP" || !libraryRole && libraryRoute.tab == "FTP") && pinnedVideo == null)
                        NetworkScreen(activity, upper = !libraryRole && canSwap)
                    else if (scene.comparisonVisible) ComparisonScreen(activity, data, prefs, { actionMedia = it }, ::task)
                    else if (scene.viewerVisible) {
                        MediaViewer(activity, data.byId[viewer]?.takeIf { if (scene.lockedCoverAlbumId != null) data.albumsById[scene.lockedCoverAlbumId]?.let(data::lockedCover)?.id == it.id else if (scene.albumCover) data.artworkAllowed(it) else data.visible(it) }, data, prefs,
                            onActions = { actionMedia = it }, overlayBusy = actionMedia != null || fileRequest != null || externalRequest != null || settingsActive, albumCover = scene.albumCover, lockedCoverAlbumId = scene.lockedCoverAlbumId,
                            onControlsVisibility = { previewControlsVisible = it },
                            onChooseFolder = { role -> addFolderRole = role })
                    } else if (scene.loading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (!prefs.reducedMotion) CircularProgressIndicator() else Text(localized(R.string.text_loading_fda05a, "Caricamento…"))
                    } else SettingsControllerContent(activity, route.tab, app.session.settingsSection) { when (route.tab) {
                        "SOURCES" -> SourceScreen(activity, data, ::task)
                        "RELINK" -> { val relinkSource by vm.relinkSource.collectAsStateWithLifecycle(); MediaRelinkScreen(activity, data, prefs, relinkSource) }
                        "RELINK_FOLDER" -> { val relinkSource by vm.relinkSource.collectAsStateWithLifecycle(); FolderRelinkScreen(activity, data, prefs, relinkSource) }
                        "RECEIVED" -> received?.let { ReceivedMediaScreen(activity, it, prefs, incomingRequest != null) }
                        "HIDDEN" -> HiddenScreen(data, app.catalog, ::task)
                        "SETTINGS_SOUNDS", "SETTINGS_HUB", "SETTINGS_LIBRARY", "SETTINGS_PERMISSIONS", "SETTINGS_DEVICE", "SETTINGS_INPUT" -> SettingsHubScreen(activity, route.tab, settingsCategoryFocus)
                        "SETTINGS_PIN" -> AlbumPinSettings(app)
                        "SETTINGS_LANGUAGE" -> LanguageSettingsScreen(app)
                        "SETTINGS_INFO" -> AppInfoScreen(activity, part = if (app.session.canSwap()) "HEADER" else "ALL")
                        "SETTINGS" -> PreferencesScreen(app, prefs, ::task, transfer = { vm.go("TRANSFER") }, maintenance = { vm.go("MAINTENANCE") }, touch = { vm.go("TOUCH") }, subsection = { key -> app.sounds.play("settings"); app.session.settingsDraft = null; app.session.settingsSection = key; vm.go(if (key == "LANGUAGE") "SETTINGS_LANGUAGE" else "SETTINGS") },
                            section = if (app.session.settingsOwner == activity.slot) app.session.settingsSection else "ALL",
                            initialDraft = app.session.settingsDraft.takeIf { app.session.settingsOwner == activity.slot },
                            onDraft = { if (app.session.settingsOwner == activity.slot) app.session.settingsDraft = it }) { vm.go("CONTROLS") }
                        "TOUCH" -> TouchSettingsScreen(app, prefs)
                        "MAINTENANCE" -> SettingsHubScreen(activity, "SETTINGS_DEVICE")
                        "CONTROLS" -> PadSettingsScreen(app, prefs)
                        "TRANSFER" -> PreferencesTransferScreen(activity, prefs)
                        "DISPLAY" -> DisplayScreen(activity)
                        "OPERATIONS" -> OperationsScreen(app, ::task)
                        "DUPLICATES" -> DuplicateScreen(activity, data, prefs, ::task, { fileRequest = it })
                        "TRASH" -> RecoveryScreen(activity, data, prefs, ::task) { fileRequest = it }
                        "COLLECTION" -> if (route.collection == null) CollectionScreen(activity, data) else
                            CollectionDetailScreen(activity, route.collection, data, prefs, ::task, ::activate, { actionMedia = it })
                        "PLAYER" -> MusicScreen(app, data, playback, ::task, compact = !app.session.remotePreviewVisible(activity))
                        "FRAME" -> PhotoFrameScreen(activity, data, prefs, incomingRequest != null || actionMedia != null || fileRequest != null || externalRequest != null || helpContext != null || replacePin != null || collectionTargets != null,
                            actions = { actionMedia = it }, compare = ::compare, remove = { fileRequest = FileActionRequest("TRASH", listOf(it)) }, task = ::task)
                        "FAVORITE" -> if (prefs.tvEnabled && prefs.tvInMediaFavorites) Column {
                            LibraryToolbar(vm, route, items.size, multi, onMulti = { multi = !multi; if (!multi) selected = emptySet() })
                            TvMixedFavorites(activity, data, items, prefs, multi, selected, ::activate, { multi = true; selected = selected + it.id }, activeScene = destination == nextScene.key, renderedSortRevision = scene.orderRevision)
                        } else {
                            Column { LibraryToolbar(vm, route, items.size, multi, onMulti = { multi = !multi; if (!multi) selected = emptySet() })
                                saveable.SaveableStateProvider(destination) { LibraryGrid(activity, route, data, items, prefs, multi, selected, ::activate, { multi = true; selected = selected + it.id }, onChooseFolder = { addFolderRole = it }, activeScene = destination == nextScene.key, timelineDescending = scene.descending, renderedSortRevision = scene.orderRevision) }
                            }
                        }
                        "MUSIC" -> saveable.SaveableStateProvider("$destination/${route.musicView}") { MusicLibraryScreen(activity, route, data, items, prefs, multi, selected, ::activate, { multi = true; selected = selected + it.id },
                            onMulti = { multi = !multi; if (!multi) selected = emptySet() }, onSelectionAction = { kind ->
                                val targets = items.filter { it.id in selected }
                                if (kind == "COLLECTION") collectionTargets = targets.map { it.id }
                                else if (kind == "FAVORITE") { task { app.catalog.favoriteAll(targets.map { it.id }, true) }; multi = false }
                                else fileRequest = FileActionRequest(kind, targets)
                            }, onChooseFolder = { addFolderRole = it }, activeScene = destination == nextScene.key, renderedSortRevision = scene.orderRevision, timelineDescending = scene.descending) }
                        "ALBUM" -> if (route.album == null) saveable.SaveableStateProvider("album-root") { AlbumScreen(activity, data, ::task, onChooseFolder = { addFolderRole = SourceRole.VISUAL }) } else Column {
                            AlbumChildren(activity, data, route.album, prefs)
                            LibraryToolbar(vm, route, items.size, multi, onMulti = { multi = !multi; if (!multi) selected = emptySet() })
                            saveable.SaveableStateProvider(destination) { LibraryGrid(activity, route, data, items, prefs, multi, selected, ::activate, { multi = true; selected = selected + it.id }, onChooseFolder = { addFolderRole = it }, activeScene = destination == nextScene.key, timelineDescending = scene.descending, renderedSortRevision = scene.orderRevision) }
                        }
                        else -> if (route.tab == "VIDEO" && videoFolders && route.album == null) AlbumScreen(activity, data, ::task, videoOnly = true, onChooseFolder = { addFolderRole = SourceRole.VISUAL }) else Column {
                            if (route.tab == "VIDEO" && route.album != null) AlbumChildren(activity, data, route.album, prefs)
                            LibraryToolbar(vm, route, items.size, multi, onMulti = { multi = !multi; if (!multi) selected = emptySet() })
                            if (route.tab == "MUSIC" && items.isNotEmpty()) GalleryButton(localized(R.string.text_play_this_collection_65be1f, "Riproduci questa raccolta"),
                                Modifier.padding(horizontal = 16.dp)) { app.music.play(items.first().id, items.map { it.id }); vm.go("PLAYER") }
                            saveable.SaveableStateProvider(destination) { LibraryGrid(activity, route, data, items, prefs, multi, selected, ::activate, { multi = true; selected = selected + it.id }, onChooseFolder = { addFolderRole = it }, activeScene = destination == nextScene.key, timelineDescending = scene.descending, renderedSortRevision = scene.orderRevision) }
                        }
                    }
                } } } }
                if (mediaEditor == null && InterfaceTools.shown(prefs, libraryRoute.tab, "SWAP") && !settingsPreview && canSwap && !immersiveFrame && !immersiveViewer && route.tab in MainTabs.map { it.key }) {
                    SmallFloatingActionButton(onClick = { app.session.swapAnimated() },
                        shape = CircleShape,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).size(52.dp).focusProperties { canFocus = false }.testTag("swap-panels")) {
                        GalleryIcon(R.drawable.ph_arrows_down_up, localized(R.string.text_swap_screens_7455a4, "Scambia schermi"), Modifier.size(28.dp))
                    }
                }
                if (showPreviewPlayer && (playback.playing || app.session.musicMiniSeen && !app.session.hidePausedMini) && !fullPreviewPlayer && data.byId[viewer]?.kind != "VIDEO")
                    PreviewMusicPanel(app, playback, data, prefs, fullPlayer = false,
                        controlsVisible = !showViewer || previewControlsVisible, bottomReserve = if (prefs.gifFrameCount && prefs.gifFrameCounter && prefs.gifFrameSelector && data.byId[viewer]?.kind == "GIF") 96.dp else 0.dp)
            }
            if (!settingsActive) AnimatedVisibility(visible = navbarVisible(prefs) && !singleLiveFullscreen && mediaEditor == null && !settingsPreview && !showViewer && !fullPreviewPlayer && !frameActive && (route.tab in MainTabs.map { it.key } || route.tab == "FTP"),
                enter = fadeIn(tween(if (prefs.reducedMotion) 0 else 220)), exit = fadeOut(tween(if (prefs.reducedMotion) 0 else 220))) {
                Column(Modifier.fillMaxWidth().background(chromeSurface)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = .72f), thickness = 1.dp)
                    GalleryNavbar(app, prefs, route.tab, { vm.go(it) }, compact = !app.session.isSingleScreen && app.session.current(activity)?.id == android.view.Display.DEFAULT_DISPLAY)
                }
            }

        }
        val frameShade = frameActive && libraryRole && canSwap && framePlaying && !frameConfiguring && frameDimmed
        val dimmed = activity.manuallyDimmed || frameShade || libraryRole && !settingsActive && mediaEditor == null && !frameActive && app.video.surface == "viewer" && app.video.player != null && app.video.owner != activity.slot && app.video.dimNavigation
        val dimAlpha by animateFloatAsState(if (dimmed) .9f else 0f, tween(if (prefs.reducedMotion) 0 else 220), label = "video-navigation-dim")
        if (dimAlpha > .001f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dimAlpha))
            .focusProperties { canFocus = false }.clickable { activity.manuallyDimmed = false; if (frameActive) frameModel.wakeFrame() else app.video.wakeNavigation() }.testTag(if (frameActive) "frame-dim-navigation" else "video-dim-navigation"))
        if (!prefs.reducedMotion && openingAlpha < .99f) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .graphicsLayer { alpha = 1f - openingAlpha }.testTag("console-opening"), contentAlignment = Alignment.Center) {
            val primary = MaterialTheme.colorScheme.primary
            val secondary = MaterialTheme.colorScheme.secondary
            val upperOpening = app.session.current(activity)?.id == android.view.Display.DEFAULT_DISPLAY
            Canvas(Modifier.fillMaxSize()) {
                if (upperOpening) drawRect(androidx.compose.ui.graphics.Brush.radialGradient(listOf(primary.copy(alpha = .28f), secondary.copy(alpha = .08f), Color.Transparent)))
                else {
                    drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(0f to primary.copy(alpha = .16f), .28f to Color.Transparent, .72f to Color.Transparent, 1f to secondary.copy(alpha = .14f)))
                    val glow = androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color.Transparent, primary.copy(alpha = .24f), Color.Transparent))
                    drawLine(glow, Offset(0f, size.height * .04f), Offset(size.width, size.height * .04f), 1.dp.toPx())
                    drawLine(glow, Offset(0f, size.height * .96f), Offset(size.width, size.height * .96f), 1.dp.toPx())
                }
            }
            if (upperOpening)
            androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.app_brand), null, Modifier.size(96.dp))
        }
        }
        if (prefs.tvEnabled && !settingsActive && (!activity.intent.getBooleanExtra("paired_gallery", false) || app.session.current(activity)?.id == android.view.Display.DEFAULT_DISPLAY)) {
            if (app.tv.adding || app.tv.editing) TvChannelDialog(activity)
            TvPlaybackDialogs(activity)
        }
        VideoReplacementDialog(app, activity.slot)
        VideoResumeDialog(app, activity.slot)
    if (libraryRole) app.session.albumUnlockRequest?.let { id ->
        AlbumPinDialog(app.catalog.protection, "UNLOCK", dismiss = { app.session.albumUnlockRequest = null }, accepted = {
            app.session.albumUnlockRequest = null
            if (id in app.catalog.snapshot.value.albumsById && id in app.catalog.protection.state.value.unlocked) {
                vm.albumAnchor = id
                vm.go(if (vm.route.value.tab == "VIDEO" && videoFolders) "VIDEO" else "ALBUM", album = id)
            }
        })
    }
        if (activity.brightnessAccessRequested) GalleryAlertDialog(
            onDismissRequest = { activity.brightnessAccessRequested = false },
            title = { Text(localized(R.string.console_brightness_permission_title, "Controlla la luminosità")) },
            text = { Text(localized(R.string.console_brightness_permission_body, "Per regolare la luminosità con lo stick destro, consenti a DS Gallery di modificare le impostazioni di sistema. Il volume non richiede questo permesso.")) },
            confirmButton = { TextButton({ activity.openBrightnessPermission() }) { Text(localized(R.string.console_brightness_permission_action, "Apri permesso")) } },
            dismissButton = { TextButton({ activity.brightnessAccessRequested = false }) { Text(localized(R.string.text_cancel_18c9d9, "Annulla")) } })
        addFolderRole?.let { role ->
            AddFolderGuidanceDialog(continueAction = { addFolderRole = null; activity.chooseWholeStorage(role) }, dismiss = { addFolderRole = null })
        }
        if (mediaMenu) GalleryAlertDialog(onDismissRequest = { mediaMenu = false }, title = { Text(localized(R.string.text_1_s_selected_media_babf2f, "%1\$s media selezionati", selected.size)) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row { TextButton({ selected = items.map { it.id }.toSet() }) { Text(localized(R.string.text_select_all_0e5b81, "Seleziona tutti")) }
                    TextButton({ selected = emptySet(); multi = false; mediaMenu = false }) { Text(localized(R.string.text_deselect_all_d1cf57, "Deseleziona tutti")) } }
                GalleryButton(localized(R.string.text_add_to_favorites_ecc911, "Aggiungi ai Preferiti"), Modifier.fillMaxWidth(), enabled = selected.isNotEmpty()) {
                    val ids = selected.toList(); task { app.catalog.favoriteAll(ids, true) }; mediaMenu = false
                }
                GalleryButton(localized(R.string.text_add_to_a_collection_c19880, "Aggiungi a una raccolta"), Modifier.fillMaxWidth(), enabled = selected.isNotEmpty()) {
                    collectionTargets = selected.toList(); mediaMenu = false
                }
                GalleryButton(localized(R.string.text_share_628ff2, "Condividi…"), Modifier.fillMaxWidth(), enabled = selected.isNotEmpty()) {
                    val frozen = selected.mapNotNull { data.byId[it] }
                    if (frozen.size == selected.size) external(ExternalAction.SHARE, frozen)
                    else app.session.fail(localized(R.string.text_the_selection_changed_select_the_files_again_83e934, "La selezione è cambiata. Seleziona nuovamente i file."))
                    mediaMenu = false
                }
                (listOf("COPY", "MOVE", "TRASH") + if (prefs.directDeleteEnabled) listOf("DELETE_DIRECT") else emptyList()).forEach { kind ->
                    GalleryButton(if (kind == "DELETE_DIRECT") localized(R.string.direct_delete, "Elimina direttamente") else fileActionName(kind), Modifier.fillMaxWidth(), enabled = selected.isNotEmpty(), destructive = kind in setOf("TRASH", "DELETE_DIRECT")) {
                        val frozen = selected.mapNotNull { data.byId[it] }
                        if (frozen.size == selected.size) fileRequest = FileActionRequest(if (kind == "DELETE_DIRECT") "TRASH" else kind, frozen, permanent = kind == "DELETE_DIRECT")
                        else app.session.fail(localized(R.string.text_the_selection_changed_select_the_files_again_83e934, "La selezione è cambiata. Seleziona nuovamente i file."))
                        mediaMenu = false
                    }
                }
            }
        }, confirmButton = { TextButton({ mediaMenu = false }) { Text(localized(R.string.text_close_32d407, "Chiudi")) } })
        collectionTargets?.let { ids -> CollectionTargetDialog(app, data, ids, done = { collectionTargets = null; multi = false; selected = emptySet() }, dismiss = { collectionTargets = null }) }
        helpContext?.let { context -> PadHelpDialog(context, prefs.pad.profile(activity.lastPadDevice?.descriptor), activity.lastPadDevice?.name) { helpContext = null } }
        val albumActionModel = app.session.libraryModel() ?: vm
        val albumActionOpen by albumActionModel.albumMenu.collectAsStateWithLifecycle()
        val albumActionIds by albumActionModel.selectedAlbums.collectAsStateWithLifecycle()
        if (albumActionOpen && !settingsActive && (!canSwap || libraryRole == prefs.actionMenusBelow))
            AlbumOptionsDialog(app, data, albumActionIds.ifEmpty { setOfNotNull(app.session.albumActionTarget) }) { albumActionModel.albumMenu(false); app.session.albumActionTarget = null }
        if (!canSwap || libraryRole == prefs.actionMenusBelow) AlbumDialogs(activity, app, data, prefs, ::task)
        actionMedia?.takeIf { !canSwap || libraryRole == prefs.actionMenusBelow }?.let { frozen -> MediaMenu(frozen, data, app, ::task, ::compare, fileAction = { kind ->
            fileRequest = FileActionRequest(if (kind == "DELETE_DIRECT") "TRASH" else kind, listOf(frozen), permanent = kind == "DELETE_DIRECT"); actionMedia = null
        }, dismiss = { actionMedia = null }, external = { action, copy -> external(action, listOf(frozen), copy) }, editMedia = {
            try { app.session.openMediaEditor(activity, ExternalRequest(ExternalAction.OPEN, listOf(ExternalTarget.from(frozen))).encode()); actionMedia = null }
            catch (failure: IllegalArgumentException) { app.session.fail(failure.message ?: localized(R.string.text_media_unavailable_91dca9, "Media non disponibile")) }
        }, insideAlbum = libraryRoute.tab == "ALBUM", displayOptions = { app.session.openPlaybackSettings(activity, frozen.kind) }) }
        if (needsFolder && !app.session.hostsTutorial(activity)) TutorialWelcomeScreen(prefs, app.session.tutorialLeaving)
        if (!welcomeDismissed && needsFolder && app.session.hostsTutorial(activity) && !settingsActive && mediaEditor == null && !frameActive && libraryRoute.tab in MainTabs.map { it.key }) {
            WelcomeTutorial(activity) { welcomeDismissed = true }
        }
        if (usageAccessPrompt && libraryRole) {
            GalleryAlertDialog(onDismissRequest = {
                usageAccessPrompt = false
                app.session.deferUsageAccessPrompt()
            }, title = { Text(localized(R.string.dual_screen_access_title, "Gestione dei due schermi")) },
                text = { Text(localized(R.string.dual_screen_access_description,
                    "Per lasciare la galleria sullo schermo libero quando apri un gioco e mandarla in background quando torni alla Home, Android deve consentire a DS Gallery di riconoscere l'app in primo piano. Può leggere il nome dell'app attiva, non i suoi contenuti.")) },
                confirmButton = { TextButton({ usageAccessPrompt = false; app.session.openUsageAccessSettings(activity) }, Modifier.testTag("dual-screen-usage-access")) {
                    Text(localized(R.string.dual_screen_access_action, "Apri impostazioni"))
                } },
                dismissButton = { TextButton({ usageAccessPrompt = false; app.session.deferUsageAccessPrompt() }) {
                    Text(localized(R.string.text_later_b81861, "Non ora"))
                } })
        }
        externalRequest?.let { encoded -> ExternalMediaDialog(activity, encoded, data, prefs) { externalRequest = null } }
        incomingRequest?.let { encoded -> ImportMediaDialog(activity, encoded, data, prefs,
            dismiss = { vm.incoming(null) }, accepted = { vm.incoming(null); vm.go("OPERATIONS") }) }
        fileRequest?.let { frozen -> FileActionDialog(frozen, data, app, prefs, activity, ::task,
            accepted = { fileRequest = null; multi = false; selected = emptySet(); if (frozen.kind != "TRASH") vm.go("OPERATIONS") }, dismiss = { fileRequest = null }) }
        replacePin?.let { frozen -> GalleryAlertDialog(onDismissRequest = { replacePin = null }, title = { Text(localized(R.string.text_all_panes_are_pinned_5d759f, "Tutti i riquadri sono fissati")) }, text = {
            Column { Text(localized(R.string.text_choose_which_pin_to_replace_with_1_s_c6ec95, "Scegli il pin da sostituire con %1\$s.", frozen.name))
                comparison.panes.forEachIndexed { index, pane -> GalleryButton("${index + 1} · ${data.byId[pane.mediaId]?.takeIf(data::visible)?.name ?: localized(R.string.text_unavailable_4107fc, "Indisponibile")}", Modifier.fillMaxWidth()) {
                    if (data.byId[frozen.id]?.let(data::usable) != true) app.session.fail(localized(R.string.text_the_media_is_no_longer_available_47e2f8, "Il media non è più disponibile"))
                    else app.comparison.state.value.pin(frozen.id, app.session.mediaContext, pane.id)?.let { app.comparison.update(it); vm.go("COMPARE") }
                    replacePin = null
                } }
            }
        }, confirmButton = { TextButton({ replacePin = null }) { Text(localized(R.string.text_cancel_18c9d9, "Annulla")) } }) }
    }
}
}

@Composable
private fun LibraryPullRefreshBox(app: GalleryApp, enabled: Boolean, modifier: Modifier = Modifier,
                                  content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    val scope = rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current
    var pullDistance by remember { mutableFloatStateOf(0f) }
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) pullDistance = 0f }
    val threshold = with(density) { 72.dp.toPx() }
    val maxPull = with(density) { 112.dp.toPx() }
    val connection = remember(enabled, threshold, maxPull, refreshing) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (enabled && !refreshing && source == NestedScrollSource.UserInput && available.y > 0f && consumed.y >= 0f)
                    pullDistance = (pullDistance + available.y).coerceAtMost(maxPull)
                else if (available.y < 0f) pullDistance = 0f
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                val shouldRefresh = enabled && !refreshing && pullDistance >= threshold
                pullDistance = 0f
                if (shouldRefresh) scope.launch {
                    refreshing = true
                    try { app.albums.refreshAndWait() }
                    catch (error: kotlinx.coroutines.TimeoutCancellationException) { app.session.fail(localized(R.string.text_album_unavailable_check_permission_or_reopen_the_70b1c5, "Album non disponibile. Controlla il permesso o riapri la cartella.")) }
                    finally { refreshing = false }
                }
                return Velocity.Zero
            }
        }
    }
    Box(modifier.then(if (enabled) Modifier.nestedScroll(connection) else Modifier)) {
        content()
        if (enabled && refreshing) {
            val travel = with(density) { (pullDistance / 2f).toDp() }
            Surface(Modifier.align(Alignment.TopCenter).offset(y = travel).padding(top = 8.dp).testTag("library-refresh-spinner"),
                shape = CircleShape, color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp) {
                Box(Modifier.padding(10.dp)) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.5.dp)
                }
            }
        }
    }
}

@Composable
private fun FormatFilter(vm: GalleryWindowModel, route: GalleryRoute) {
    var open by remember { mutableStateOf(false) }
    Box {
        GalleryIconButton(R.drawable.ph_funnel, if (route.kind == "ALL") localized(R.string.text_filter_by_format_113c28, "Filtra per formato") else localized(R.string.text_active_filter_1_s_019879, "Filtro attivo: %1\$s", MediaFilters.label(route.kind)),
            Modifier.testTag("media-format-filter").background(if (route.kind == "ALL") Color.Transparent else MaterialTheme.colorScheme.primary.copy(alpha = .16f), GalleryShape)
                .semantics { selected = route.kind != "ALL" }) { open = true }
        GalleryDropdownMenu(open, { open = false }, Modifier.heightIn(max = 320.dp).testTag("media-format-menu")) {
            MediaFilters.choices(route.tab == "PHOTO").forEach { (key, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { vm.filter(key); open = false },
                    leadingIcon = { RadioButton(route.kind == key, onClick = null, Modifier.size(24.dp)) },
                    modifier = Modifier.testTag("media-format:$key").semantics { selected = route.kind == key })
            }
        }
    }
}

@Composable
internal fun LibraryToolbar(vm: GalleryWindowModel, route: GalleryRoute, count: Int, multi: Boolean, onMulti: () -> Unit) {
    if (count == 0) return
    val order by vm.sort.collectAsStateWithLifecycle()
    val desc by vm.descending.collectAsStateWithLifecycle()
    var ordering by remember { mutableStateOf(false) }
    val reverseFocus = remember { FocusRequester() }
    val inputMode = LocalInputModeManager.current.inputMode
    val focusManager = LocalFocusManager.current
    val revision by vm.sortReset.collectAsStateWithLifecycle()
    var retainReverseFocus by remember { mutableStateOf(false) }
    var reverseHasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(revision) {
        if (retainReverseFocus) { withFrameNanos { }; reverseFocus.requestFocus() }
    }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val panel = androidx.activity.compose.LocalActivity.current as? PanelActivity
        Row(Modifier.onFocusChanged { panel?.settingsHeaderHasFocus = it.hasFocus }.focusGroup(), verticalAlignment = Alignment.CenterVertically) {
            Text("$count media" + if (route.kind != "ALL") " · ${MediaFilters.label(route.kind)}" else "", Modifier.weight(1f).testTag("library-filter-summary"),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box {
                TextButton({ ordering = true }, Modifier.focusProperties { canFocus = (panel?.application as? GalleryApp)?.preferences?.state?.value?.controllerSorting == true }.testTag("library-order"), contentPadding = PaddingValues(horizontal = 4.dp)) { Text(when (order) { "DATE" -> localized(R.string.text_date_cec3a9, "Data"); "SIZE" -> localized(R.string.text_size_86d146, "Dimensione"); "DURATION" -> localized(R.string.text_duration_902f15, "Durata"); "PLAYLIST" -> "Playlist"; "FAVORITE_ADDED" -> localized(R.string.text_added_f6ef7c, "Aggiunta"); else -> localized(R.string.text_name_508690, "Nome") }) }
                GalleryDropdownMenu(ordering, { ordering = false }) {
                    (listOfNotNull(if (route.musicView == "PLAYLISTS" && route.musicGroup != null) "PLAYLIST" to localized(R.string.text_playlist_order_fb1ecd, "Ordine playlist") else null) +
                        listOf("NAME" to localized(R.string.text_natural_name_order_d9f048, "Nome naturale"), "DATE" to localized(R.string.text_last_modified_63c096, "Ultima modifica"), "SIZE" to localized(R.string.text_size_86d146, "Dimensione"), "DURATION" to localized(R.string.text_duration_902f15, "Durata"), (if (route.tab == "FAVORITE") "FAVORITE_ADDED" else "ADDED") to localized(R.string.text_added_f6ef7c, "Aggiunta"))).forEach { (key, label) ->
                        DropdownMenuItem({ Text(label) }, { vm.order(key); ordering = false })
                    }
                }
            }
            CompositionLocalProvider(LocalContentColor provides if (desc != (order in setOf("DATE", "ADDED", "FAVORITE_ADDED"))) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) {
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 32.dp) {
                    IconButton({
                        retainReverseFocus = reverseHasFocus || inputMode == InputMode.Keyboard
                        if (!retainReverseFocus) focusManager.clearFocus(force = true)
                        vm.reverse()
                    }, Modifier.size(32.dp).focusRequester(reverseFocus).onFocusChanged { reverseHasFocus = it.isFocused }.focusProperties { canFocus = (panel?.application as? GalleryApp)?.preferences?.state?.value?.controllerSorting == true }.testTag("library-reverse")) {
                        GalleryIcon(R.drawable.ph_arrow_right, localized(R.string.text_reverse_order_33051e, "Inverti ordine"),
                            Modifier.size(16.dp).graphicsLayer { rotationZ = if (desc == (order in setOf("DATE", "ADDED", "FAVORITE_ADDED"))) 90f else -90f })
                    }
                }
            }

        }
    }
}

@Composable
private fun FavoriteFilters(vm: GalleryWindowModel, selected: String) {
    var open by remember { mutableStateOf(false) }
    val types = listOf("IMAGE", "GIF", "VIDEO", "AUDIO")
    val selectedTypes = if (selected == "ALL") emptySet() else selected.split("+").toSet()
    Box {
        GalleryIconButton(R.drawable.ph_funnel, MediaFilters.label(selected), Modifier.testTag("favorite-filter")) { open = true }
        GalleryDropdownMenu(open, { open = false }) {
            DropdownMenuItem({ Text(localized(R.string.text_all_7f65ef, "Tutti")) }, { vm.filter("ALL") },
                leadingIcon = { Checkbox(selected == "ALL", null) })
            types.forEach { kind ->
                DropdownMenuItem({ Text(MediaFilters.label(kind)) }, {
                    val next = if (kind in selectedTypes) selectedTypes - kind else selectedTypes + kind
                    vm.filter(types.filter { it in next }.joinToString("+").ifEmpty { "ALL" })
                }, leadingIcon = { Checkbox(kind in selectedTypes, null) }, modifier = Modifier.testTag("favorite-filter:$kind"))
            }
        }
    }
}

@Composable
internal fun LibraryGrid(activity: PanelActivity, route: GalleryRoute, data: CatalogSnapshot, items: List<MediaEntity>, prefs: GalleryPreferences,
                        multi: Boolean, selected: Set<String>, activate: (MediaEntity) -> Unit, actions: (MediaEntity) -> Unit,
                        onChooseFolder: (SourceRole) -> Unit = { role -> activity.chooseWholeStorage(role) },
                        activeScene: Boolean = true, timelineDescending: Boolean = activity.windowModel.descending.value,
                        renderedSortRevision: Long? = null) {
    val items = remember(items, data) { items.filter(data::visible) }
    val app = activity.application as GalleryApp
    if (route.album?.let(data::albumLocked) == true) {
        var pinOpen by remember(route.album) { mutableStateOf(true) }
        EmptyGallery(localized(R.string.protected_album, "Album protetto"), localized(R.string.pin_hint, "Quattro cifre per i tuoi album. La copertina resta visibile."), R.drawable.ph_lock,
            localized(R.string.unlock_album, "Sblocca album")) { pinOpen = true }
        if (pinOpen) AlbumPinDialog(app.catalog.protection, "UNLOCK", dismiss = { pinOpen = false }, accepted = { pinOpen = false })
        return
    }
    val timelineMode = if (MediaTimeline.supported(route)) prefs.timelineModes[InterfaceTools.section(route.tab)] ?: "OFF" else "OFF"
    val locale = LocalConfiguration.current.locales[0]
    val unknown = localized(R.string.timeline_unknown, "Data sconosciuta")
    val cells = remember(items, timelineMode, locale, unknown, timelineDescending) { MediaTimeline.cells(items, timelineMode, locale, unknown, descending = timelineDescending) }
    val orderedItems = remember(cells) { cells.mapNotNull { it.media } }
    val context = remember(orderedItems) { orderedItems.map { it.id } }
    val indices = remember(cells) { cells.mapIndexedNotNull { index, cell -> cell.media?.let { it.id to index } }.toMap() }
    val query by activity.windowModel.query.collectAsStateWithLifecycle()
    if (items.isEmpty()) {
        if (route.kind != "ALL") {
            EmptyGallery(localized(R.string.text_no_media_matches_this_filter_ae3907, "Nessun media con questo filtro"), localized(R.string.text_filter_1_s_you_can_show_all_formats_while_keepin_c81fc6, "Filtro: %1\$s. Puoi mostrare tutti i formati mantenendo la cartella e la ricerca.", MediaFilters.label(route.kind)),
                R.drawable.ph_funnel, localized(R.string.text_show_all_formats_400e1d, "Mostra tutti i formati")) { activity.windowModel.filter("ALL") }
            return
        }
        if (query.isNotBlank()) {
            EmptyGallery(localized(R.string.text_no_results_0af987, "Nessun risultato"), localized(R.string.text_try_another_name_or_clear_the_search_0cceaf, "Prova un altro nome o cancella la ricerca."), R.drawable.ph_magnifying_glass, localized(R.string.text_clear_search_3664fa, "Cancella ricerca")) { activity.windowModel.search("") }
            return
        }
        if (route.album != null || route.collection != null || route.musicGroup != null) {
            EmptyGallery(if (route.album != null) localized(R.string.text_no_media_in_this_folder_3e95d8, "Nessun media in questa cartella") else localized(R.string.text_no_visible_media_e3bde4, "Nessun media visibile"),
                if (route.musicGroup != null) localized(R.string.text_this_group_has_no_visible_tracks_references_are_0a98e3, "Questo gruppo non contiene brani visibili. I riferimenti rimangono conservati.")
                else localized(R.string.text_subfolders_remain_accessible_above_hidden_or_una_171816, "Le sottocartelle restano accessibili in alto. I media nascosti o non disponibili conservano i loro riferimenti."), R.drawable.ph_folders)
            return
        }
        if (route.tab == "FAVORITE") Box(Modifier.fillMaxSize().testTag("empty-favorites-icon"), contentAlignment = Alignment.Center) { PulsingFavorite(!prefs.reducedMotion) }
        else EmptyFolderButton { onChooseFolder(if (route.tab == "MUSIC") SourceRole.MUSIC else SourceRole.VISUAL) }
        return
    }
    val favoriteGrid by activity.windowModel.mediaGrid(route.tab).collectAsStateWithLifecycle()
    if (route.tab == "FAVORITE" && favoriteGrid) {
        FavoriteMosaic(activity, data, items, prefs, multi, selected, activate, actions, activeScene, renderedSortRevision)
        return
    }
    val grid = rememberLazyGridState()
    val requestedSortRevision by activity.windowModel.sortReset.collectAsStateWithLifecycle()
    val sortRevision = renderedSortRevision ?: requestedSortRevision
    var scrolledRevision by rememberSaveable { mutableLongStateOf(0L) }
    val sortPending = activeScene && sortRevision != scrolledRevision
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    var focusJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var lastFocused by rememberSaveable(app.session.state.swapped) { mutableStateOf(app.session.selectedMediaId) }
    val inputMode = LocalInputModeManager.current.inputMode
    val smallVideoList by (if (route.tab == "FAVORITE") app.preferences.favoriteSmallList else app.preferences.videoSmallList).collectAsStateWithLifecycle()
    val tileGrid by activity.windowModel.mediaGrid(route.tab).collectAsStateWithLifecycle()
    val columns by activity.windowModel.mediaColumns(route.tab).collectAsStateWithLifecycle()
    var restoringFocus by remember { mutableStateOf(true) }
    LaunchedEffect(inputMode, activeScene) { if (activeScene && inputMode == InputMode.Keyboard) restoringFocus = true }
    var frozenSelection by remember { mutableStateOf(app.session.selectedMediaId) }
    val liveSelection = if (activeScene) app.session.selectedMediaId else frozenSelection
    if (activeScene) SideEffect { frozenSelection = liveSelection }
    var musicEntered by remember { mutableStateOf(false) }
    val entryPlayback by app.music.state.collectAsStateWithLifecycle()
    val playingEntry = if (route.tab == "MUSIC" && !musicEntered) entryPlayback.id?.takeIf { id -> items.any { it.id == id } }
        ?: rememberedMusicId(app)?.takeIf { id -> items.any { it.id == id } } ?: items.first().id else null
    LaunchedEffect(activeScene) { if (activeScene && route.tab == "MUSIC") { playingEntry?.let { lastFocused = it }; musicEntered = true } }
    var albumEntered by remember { mutableStateOf(false) }
    val albumEntryFocus = remember { liveSelection?.takeIf { id -> items.any { it.id == id } } ?: items.first().id }
    LaunchedEffect(activeScene) {
        if (activeScene && route.tab == "ALBUM" && !albumEntered) { lastFocused = albumEntryFocus; albumEntered = true }
    }
    var observedSelection by remember { mutableStateOf(liveSelection) }
    if (activeScene && observedSelection != liveSelection) SideEffect {
        observedSelection = liveSelection
        liveSelection?.takeIf { id -> items.any { it.id == id } }?.let { lastFocused = it }
    }
    val changedSelection = liveSelection?.takeIf { id -> activeScene && observedSelection != liveSelection && items.any { it.id == id } }
    val currentMedia = playingEntry ?: (if (sortPending) orderedItems.first().id else null) ?: changedSelection ?: (if (route.tab == "ALBUM" && !albumEntered) albumEntryFocus else lastFocused)?.takeIf { id -> items.any { it.id == id } }
        ?: liveSelection?.takeIf { id -> route.tab == "ALBUM" && route.album != null && items.any { it.id == id } }
        ?: items.first().id
    val focusTarget = currentMedia
    LaunchedEffect(currentMedia, cells, activeScene, tileGrid, columns, inputMode, sortRevision) {
        val index = indices[currentMedia] ?: -1
        if (activeScene && index >= 0 && sortRevision == activity.windowModel.sortReset.value) {
            if (sortPending) {
                if (!activity.settingsHeaderHasFocus) focusManager.clearFocus(force = true)
                grid.scrollToItem(0)
            }
            lastFocused = currentMedia
            app.session.updateMediaContext(context); app.session.selectMedia(currentMedia)
            if (inputMode == InputMode.Keyboard && !activity.settingsHeaderHasFocus) restoringFocus = true
            if (grid.layoutInfo.visibleItemsInfo.none { it.index == index }) grid.scrollToItem(index)
            scrolledRevision = sortRevision
        }
    }
    OrderedLibraryFocus(activity, context, activeScene, !tileGrid, sortRevision) { id ->
        focusJob?.cancel()
        if (id != app.session.selectedMediaId) app.video.browsePreview(id)
        lastFocused = id
        app.session.updateMediaContext(context); app.session.selectMedia(id)
        focusJob = scope.launch {
            val index = indices[id] ?: return@launch
            if (grid.layoutInfo.visibleItemsInfo.none { it.index == index }) grid.scrollToItem(index)
            androidx.compose.runtime.withFrameNanos { }
            requesters[id]?.requestFocus()
        }
    }
    LaunchedEffect(cells, restoringFocus, focusTarget, activeScene) {
        if (activeScene && restoringFocus && sortRevision == activity.windowModel.sortReset.value) {
            val index = indices[focusTarget] ?: -1
            if (index < 0) restoringFocus = false
            else if (grid.layoutInfo.visibleItemsInfo.none { it.index == index }) grid.scrollToItem(index)
        }
    }
    val visibleGifs by remember(grid, cells, prefs.gifLimit) { derivedStateOf {
        animatedGifIds(grid.layoutInfo.visibleItemsInfo.mapNotNull { visible -> cells.getOrNull(visible.index)?.media?.takeIf { it.kind == "GIF" }?.let { it.id to visible.size.width.toLong() * visible.size.height } }, prefs.gifLimit)
    } }
    val active by activity.isStartedFlow.collectAsState()
    val dragSelection = mediaDragSelection(multi && activeScene, cells.mapNotNull { it.media?.id },
        hit = { point -> grid.layoutInfo.visibleItemsInfo.firstOrNull {
            point.x >= it.offset.x && point.x < it.offset.x + it.size.width &&
                point.y >= it.offset.y && point.y < it.offset.y + it.size.height
        }?.index?.let { cells.getOrNull(it)?.media?.id } },
        viewportHeight = { grid.layoutInfo.viewportSize.height }, scroll = { grid.scrollBy(it) },
        select = { id -> data.byId[id]?.let(actions) })
    GalleryGridFocus { LazyVerticalGrid(GridCells.Fixed(if (tileGrid) columns else 1), Modifier.fillMaxSize().then(dragSelection).testTag("library-grid"), state = grid,
        contentPadding = PaddingValues(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 28.dp), horizontalArrangement = Arrangement.spacedBy(if (tileGrid && columns >= 5) 3.dp else 6.dp), verticalArrangement = Arrangement.spacedBy(if (tileGrid && columns >= 5) 3.dp else 6.dp)) {
        items(cells, key = { it.key }, span = { GridItemSpan(if (it.media == null) maxLineSpan else 1) }) { cell ->
            val item = cell.media
            if (item == null) { Text(cell.heading.orEmpty(), Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp).testTag(cell.key), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary); return@items }
            val requester = remember(item.id) { requesters.getOrPut(item.id) { FocusRequester() } }
            DisposableEffect(item.id) { onDispose { if (requesters[item.id] === requester) requesters.remove(item.id) } }
            var coordinates by remember(item.id) { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
            LaunchedEffect(restoringFocus, coordinates, inputMode, focusTarget, activeScene) {
                if (activeScene && restoringFocus && sortRevision == activity.windowModel.sortReset.value && !activity.settingsHeaderHasFocus && item.id == focusTarget && inputMode == InputMode.Keyboard && coordinates?.isAttached == true) {
                    requester.requestFocus()
                    restoringFocus = false
                }
            }
            Box(if (activeScene) Modifier.animateItem(placementSpec = null, fadeInSpec = tween(if (prefs.reducedMotion) 0 else 260), fadeOutSpec = tween(if (prefs.reducedMotion) 0 else 160)) else Modifier) {
                MediaTile(item, data, app.catalog, prefs, item.id == currentMedia, multi, item.id in selected,
                    active && activeScene && !grid.isScrollInProgress && when (prefs.gifMode) { "VISIBLE" -> item.id in visibleGifs; "SELECTED" -> item.id == app.session.selectedMediaId; else -> false }, active,
                    focus = { if (activeScene && sortRevision == requestedSortRevision && (!sortPending || item.id == focusTarget) && (!restoringFocus || item.id == focusTarget)) { if (!restoringFocus) app.video.browsePreview(item.id); val previous = app.session.selectedMediaId; if (app.session.selectedMediaId != item.id) { app.sounds.navigation("media:${item.id}"); if (item.kind != "AUDIO") app.session.previewFocusRevision += 1 }; lastFocused = item.id; app.session.updateMediaContext(context); app.session.selectMedia(item.id); if (tileGrid && route.tab in setOf("PHOTO", "ALBUM", "VIDEO")) app.session.gridPreviewFocus(context, previous, item.id, columns) } },
                    click = { if (activeScene) { val previous = app.session.selectedMediaId; lastFocused = item.id; app.session.updateMediaContext(context); activate(item); if (!multi && tileGrid && route.tab in setOf("PHOTO", "ALBUM", "VIDEO")) app.session.gridPreviewFocus(context, previous, item.id, columns) } }, actions = { if (activeScene) { app.session.updateMediaContext(context); actions(item) } },
                    modifier = Modifier.onGloballyPositioned { coordinates = it }.focusRequester(requester).focusProperties { canFocus = activeScene }.testTag("media:${item.id}"), list = !tileGrid, smallList = route.tab in setOf("VIDEO", "FAVORITE") && smallVideoList, compactCorners = tileGrid && columns >= 5, showArtist = !tileGrid || columns < 6)
            }
        }
    }
}
}
