package it.ayngallery

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import org.json.JSONObject

data class GalleryRoute(val tab: String = "ALBUM", val album: String? = null, val collection: String? = null,
    val viewer: String? = null, val forceLibrary: Boolean = false, val kind: String = "ALL",
    val musicView: String = "TRACKS", val musicGroup: String? = null) {
    val needsMediaListing get() = tab in setOf("PHOTO", "VIDEO", "MUSIC", "FAVORITE", "ALBUM", "COLLECTION")
}
data class GalleryListing(val route: GalleryRoute = GalleryRoute(), val items: List<MediaEntity> = emptyList(), val initialized: Boolean = false,
    val query: String = "", val sort: String = "NAME", val descending: Boolean = false)

internal fun defaultGalleryOrdering(route: GalleryRoute): Pair<String, Boolean> = when {
    route.tab == "FAVORITE" -> "FAVORITE_ADDED" to true
    route.tab == "MUSIC" -> "NAME" to false
    route.tab == "ALBUM" && route.album == null -> "NAME" to false
    route.needsMediaListing -> "DATE" to true
    else -> "NAME" to false
}

class GalleryWindowModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    val relinkSource = saved.getStateFlow<String?>("relink_source", null)
    fun relinkSource(id: String?) { saved["relink_source"] = id; go("RELINK") }
    fun relinkFolderSource(id: String) { saved["relink_source"] = id; go("RELINK_FOLDER") }
    val incomingRequest = saved.getStateFlow<String?>("incoming_request", null)
    fun incoming(value: String?) { value?.let { IncomingSelection.decode(it) }; saved["incoming_request"] = value }
    val received = saved.getStateFlow<String?>("received_request", null)
    fun received(value: String?) {
        value?.let { ReceivedMedia.decode(it) }; saved["received_request"] = value
        if (value != null) go("RECEIVED")
    }
    var pendingSourceRole: String
        get() = saved["pending_source_role"] ?: SourceRole.VISUAL.name
        set(value) { saved["pending_source_role"] = value }
    private val catalog = (application as GalleryApp).catalog
    val route = MutableStateFlow(GalleryRoute(saved.get<String>("tab")?.takeUnless { it == "BACKUP" || it == "COMPARE" } ?: preferredGalleryTab((application as GalleryApp).preferences.state.value.disabledCategories, (application as GalleryApp).preferences.state.value.defaultSection), saved["album"], saved["collection"],
        saved["viewer"], saved["force_library"] ?: false, saved.get<String>("kind")?.takeIf(MediaFilters::valid) ?: "ALL", saved["music_view"] ?: "TRACKS", saved["music_group"]))
    private val preferences = (application as GalleryApp).preferences
    val videoFolders = preferences.videoFolderView
    fun cycleVideoView() {
        when { videoFolders.value -> { videoFolders(false); mediaGrid(true) }; mediaGrid("VIDEO").value -> mediaGrid(false); else -> videoFolders(true) }
    }
    fun videoFolders(value: Boolean) { preferences.videoFolderView(value); go("VIDEO") }
    val albumGrid = preferences.albumGrid
    val albumColumns = preferences.albumColumns
    val videoAlbumColumns = preferences.videoAlbumColumns
    fun mediaGrid(tab: String) = preferences.mediaGrid(tab, route.value.takeIf { it.tab == tab }?.album)
    fun mediaColumns(tab: String) = preferences.mediaColumns(tab, route.value.takeIf { it.tab == tab }?.album)
    var albumAnchor: String?
        get() = saved[if (route.value.tab == "VIDEO") "video_album_anchor" else "album_anchor"]
        set(value) { saved[if (route.value.tab == "VIDEO") "video_album_anchor" else "album_anchor"] = value }
    val selectedAlbums = saved.getStateFlow("selected_albums", emptySet<String>())
    val albumMenu = saved.getStateFlow("album_menu", false)
    fun albumGrid(value: Boolean) { preferences.galleryView(true, grid = value) }
    fun albumColumns(value: Int) { if (route.value.tab == "VIDEO" && videoFolders.value && route.value.album == null) preferences.videoAlbumColumns(value) else preferences.galleryView(true, columns = value) }
    fun mediaGrid(value: Boolean) { preferences.galleryView(false, route.value.tab, grid = value, albumId = route.value.album) }
    fun mediaColumns(value: Int) { preferences.galleryView(false, route.value.tab, columns = value, albumId = route.value.album) }
    fun selectAlbums(ids: Set<String>) { saved["selected_albums"] = ids }
    fun albumMenu(value: Boolean) { saved["album_menu"] = value }
    val query = saved.getStateFlow("query", "")
    private val activeOrdering = MutableStateFlow(defaultGalleryOrdering(route.value))
    val sort: StateFlow<String> = activeOrdering.map { it.first }.stateIn(viewModelScope, SharingStarted.Eagerly, activeOrdering.value.first)
    val descending: StateFlow<Boolean> = activeOrdering.map { it.second }.stateIn(viewModelScope, SharingStarted.Eagerly, activeOrdering.value.second)
    private val customOrdering get() = saved.getStateFlow("custom_sort:${orderingKey()}", false)
    private fun orderingKey(route: GalleryRoute = this.route.value) = when {
        route.tab == "MUSIC" -> "music"
        route.tab == "ALBUM" && route.album != null -> "album_media"
        route.tab == "ALBUM" -> "albums"
        route.tab == "PHOTO" -> "photos"
        route.tab == "VIDEO" -> "videos"
        route.tab == "FAVORITE" -> "favorites_added"
        route.tab == "COLLECTION" -> "collection"
        else -> "other:${route.tab}"
    }
    private fun applyOrdering(route: GalleryRoute, forceDefault: Boolean = false) {
        val key = orderingKey(route)
        val custom = saved.get<Boolean>("custom_sort:$key") ?: false
        if (forceDefault || !custom) {
            defaultGalleryOrdering(route).also { (value, reverse) -> saved["sort:$key"] = value; saved["descending:$key"] = reverse }
        }
        val defaults = defaultGalleryOrdering(route)
        activeOrdering.value = (saved.get<String>("sort:$key") ?: defaults.first) to (saved.get<Boolean>("descending:$key") ?: defaults.second)
    }
    init {
        applyOrdering(route.value)
    }
    val frameConfig = MutableStateFlow(saved.get<String>("frame_config")?.let { runCatching { FrameConfig.decode(it) }.getOrNull() }
        ?: FrameConfig())
    val frameCursor = MutableStateFlow(FrameCursor(current = saved["frame_current"]))
    val framePlaying = MutableStateFlow(saved.get<Boolean>("frame_playing") ?: false)
    val frameDimmed = MutableStateFlow(false)
    val frameInteraction = MutableStateFlow(0)
    fun wakeFrame() { frameDimmed.value = false; frameInteraction.value += 1 }
    fun configureFrame(config: FrameConfig) { val encoded = config.encode(); saved["frame_config"] = encoded; frameConfig.value = config }
    fun frameCursor(value: FrameCursor) { saved["frame_current"] = value.current; frameCursor.value = value }
    fun framePlaying(value: Boolean) { saved["frame_playing"] = value; framePlaying.value = value; wakeFrame() }
    val frameConfiguring = MutableStateFlow(saved.get<Boolean>("frame_configuring") ?: false)
    fun frameConfiguring(value: Boolean) { saved["frame_configuring"] = value; frameConfiguring.value = value }
    fun startFrame() {
        val session = (getApplication<Application>() as GalleryApp).session
        saved["frame_return_swapped"] = session.state.swapped
        framePlaying(false)
        frameConfiguring(true)
        val current = route.value
        val config = frameConfig.value
        when {
            current.album != null -> configureFrame(config.copy(scope = "SELECTED", albums = setOf(current.album), collections = emptySet()))
            current.collection != null -> configureFrame(config.copy(scope = "SELECTED", albums = emptySet(), collections = setOf(current.collection)))
            current.tab == "FAVORITE" -> configureFrame(config.copy(scope = "FAVORITE"))
        }
        go("FRAME")

    }
    private val ordering = activeOrdering
    val musicGroups: StateFlow<MusicGroupListing> = combine(catalog.snapshot, route, query) { data, route, search ->
        MusicGroupListing(route.musicView, search, it.ayngallery.musicGroups(data, route.musicView, if (route.musicGroup == null) search else ""), data.initialized)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MusicGroupListing())
    val listing: StateFlow<GalleryListing> = combine(catalog.snapshot, route, query, ordering) { data, route, search, order ->
        if (!route.needsMediaListing) return@combine GalleryListing(route, initialized = data.initialized,
            query = search, sort = order.first, descending = order.second)
        val group = route.musicGroup?.let { id -> it.ayngallery.musicGroups(data, route.musicView).find { it.id == id } }
        val groupIds = group?.tracks?.map { it.id }?.toSet()
        val collectionItems = route.collection?.let { id -> data.collectionItems.filter { it.collectionId == id }.map { it.mediaId } }
        val collection = collectionItems?.toSet()
        val words = search.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val list = data.visibleMedia.filter { item ->
            val type = when (route.tab) {
                "PHOTO", "VIDEO" -> matchesPreviewCategory(route.tab, item) && item.available
                "MUSIC" -> item.kind == "AUDIO"
                "FAVORITE" -> item.favorite
                else -> route.collection != null || item.available
            }
            type && (route.musicGroup == null || (groupIds != null && item.id in groupIds)) && (route.album == null || item.albumId == route.album) &&
                (collection == null || item.id in collection) && MediaFilters.matches(route.kind, item) &&
                words.all { word -> listOfNotNull(item.name, item.title, item.artist, item.musicAlbum, data.albumsById[item.albumId]?.path)
                    .any { it.contains(word, ignoreCase = true) } }
        }
        val comparator = mediaOrderComparator(order.first)
        val playlistOrder = if (route.musicView == "PLAYLISTS" && route.musicGroup != null) group?.tracks?.mapIndexed { index, item -> item.id to index }?.toMap() else null
        val collectionOrder = collectionItems?.mapIndexed { index, id -> id to index }?.toMap()
        val sorted = if (collectionOrder != null) list.sortedBy { collectionOrder[it.id] }
            else if (playlistOrder != null && order.first == "PLAYLIST") list.sortedBy { playlistOrder[it.id] }.let { if (order.second) it.reversed() else it }
            else list.sortedWith(if (order.second) comparator.reversed() else comparator)
        GalleryListing(route, sorted, data.initialized, search, order.first, order.second)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GalleryListing())

    private fun update(next: GalleryRoute, preserveSelection: Boolean = false) {
        if (next.tab == "BACKUP") { update(next.copy(tab = "SETTINGS")); return }
        val current = route.value
        if (current != next) getApplication<GalleryApp>().tv.abandonTransientActions()
        if (next != current && !preserveSelection) {
            getApplication<GalleryApp>().video.dismissResume()
            getApplication<GalleryApp>().session.pinActivationRequestId = null
        }
        if (!preserveSelection && next.tab == "FAVORITE" && current.tab != "FAVORITE") saved["favorite_entry_pending"] = true
        if (!preserveSelection && current.tab == "ALBUM" && current.album != null && next.album == null && next.tab == "ALBUM") {
            albumAnchor = current.album
            getApplication<GalleryApp>().session.previewAlbum(current.album)
            getApplication<GalleryApp>().session.clearVisualSelection()
        }
        if (orderingKey(next) != orderingKey(current)) {
            saved["sort:${orderingKey(current)}"] = activeOrdering.value.first
            saved["descending:${orderingKey(current)}"] = activeOrdering.value.second
            saved["custom_sort:${orderingKey(current)}"] = customOrdering.value
            route.value = next
            applyOrdering(next)
        }
        route.value = next
        saved["tab"] = next.tab; saved["album"] = next.album; saved["collection"] = next.collection
        saved["viewer"] = next.viewer; saved["force_library"] = next.forceLibrary; saved["kind"] = next.kind
        saved["music_view"] = next.musicView; saved["music_group"] = next.musicGroup
    }
    private fun restoreFrameDisplayRole() {
        if (!saved.contains("frame_return_swapped")) return
        val restoreSwapped = saved.get<Boolean>("frame_return_swapped")
        saved.remove<Boolean>("frame_return_swapped")
        frameDimmed.value = false
        val session = (getApplication<Application>() as GalleryApp).session
        if (restoreSwapped != null && session.canSwap() && session.state.swapped != restoreSwapped) session.swap()
    }
    fun consumeFavoriteEntry(): Boolean {
        val pending = saved.get<Boolean>("favorite_entry_pending") ?: (route.value.tab == "FAVORITE")
        saved["favorite_entry_pending"] = false
        return pending
    }
    private var lastVideoRoute: GalleryRoute? = null
    fun go(tab: String, album: String? = null, collection: String? = null, musicView: String = "TRACKS", musicGroup: String? = null) {
        if (tab == "BACKUP") { go("SETTINGS"); return }
        val current = route.value
        if (tab == "TV" && current.tab != "TV") getApplication<GalleryApp>().tv.enter()
        if (current.tab == "VIDEO") lastVideoRoute = current.copy(viewer = null, forceLibrary = true)
        val returningVideo = if (tab == "VIDEO" && current.tab != "VIDEO" && album == null && collection == null) lastVideoRoute else null
        val next = returningVideo ?: GalleryRoute(tab, album, collection, forceLibrary = true,
            kind = if (current.tab == tab && current.collection == collection) current.kind else "ALL", musicView = musicView, musicGroup = musicGroup)
        if (tab != current.tab) getApplication<GalleryApp>().video.dismissReplacement()
        if (tab != current.tab && tab in NavbarOrder.DEFAULT) getApplication<GalleryApp>().video.wakeNavigation()
        if (next == current) {
            if (tab == "VIDEO" && getApplication<GalleryApp>().session.libraryModel() === this)
                getApplication<GalleryApp>().video.returnFromTools(force = true)
            return
        }
        if (tab != current.tab && getApplication<GalleryApp>().session.libraryModel() === this) {
            val session = getApplication<GalleryApp>().session
            session.mediaOptions = null; session.albumActionTarget = null; session.albumCommand = null
            session.albumDialogs.apply { hide = null; request = null; cover = null; pinMode = null; pinAlbums = emptySet() }
        }
        if (getApplication<GalleryApp>().session.libraryModel() === this) {
            if (tab in setOf("MUSIC", "PLAYER", "FTP") || tab == "TV" && getApplication<GalleryApp>().video.liveChannel == null || tab.startsWith("SETTINGS")) getApplication<GalleryApp>().video.suspendForTools()
            else if (tab in setOf("PHOTO", "ALBUM", "VIDEO", "FAVORITE", "TV")) getApplication<GalleryApp>().video.returnFromTools(force = tab == "VIDEO")
        }
        if (tab == "MUSIC" && current.tab != "MUSIC") getApplication<GalleryApp>().music.state.value.takeIf { it.playing }?.id?.let { getApplication<GalleryApp>().session.selectMedia(it) }
        selectAlbums(emptySet())
        albumMenu(false)
        if (tab.startsWith("SETTINGS") && current.tab.startsWith("SETTINGS")) getApplication<GalleryApp>().sounds.play("settings-navigation")
        val primary = setOf("PHOTO", "ALBUM", "VIDEO", "MUSIC", "FAVORITE", "TV")
        if (tab != current.tab && tab in primary && current.tab in primary && getApplication<GalleryApp>().session.libraryModel() === this) getApplication<GalleryApp>().sounds.play("section")
        if (tab in primary && album == null && collection == null && musicGroup == null) saved["navigation_history"] = arrayListOf<String>()
        else {
            val history = saved.get<ArrayList<String>>("navigation_history") ?: arrayListOf()
            val encoded = JSONObject().put("tab", current.tab).put("album", current.album).put("collection", current.collection)
                .put("viewer", current.viewer).put("force", current.forceLibrary).put("kind", current.kind).put("query", query.value)
                .put("music_view", current.musicView).put("music_group", current.musicGroup).put("sort", sort.value).put("descending", descending.value)
                .put("custom_sort", customOrdering.value)
            saved["navigation_history"] = ArrayList((history + encoded.toString()).takeLast(20))
        }
        update(next)
        if (tab in primary || tab == "COLLECTION") (getApplication<GalleryApp>()).session.libraryNavigation(this)
        if (current.tab == "FRAME" && tab != "FRAME") restoreFrameDisplayRole()
        if (musicView != "PLAYLISTS" && sort.value == "PLAYLIST") saved["sort"] = "NAME"
        if (album != current.album || collection != current.collection || musicGroup != current.musicGroup || musicView != current.musicView) saved["query"] = ""
    }
    fun musicMode(view: String) { require(view in setOf("TRACKS", "ALBUMS", "ARTISTS", "FOLDERS", "PLAYLISTS")); go("MUSIC", musicView = view) }
    fun openMusicGroup(id: String) { go("MUSIC", musicView = route.value.musicView, musicGroup = id); if (route.value.musicView == "PLAYLISTS") { order("PLAYLIST"); saved["descending"] = false } }
    fun open(id: String) = update(route.value.copy(viewer = id))
    fun filter(kind: String) { require(MediaFilters.valid(kind)); update(route.value.copy(kind = kind)) }
    fun search(value: String) { saved["query"] = value }
    fun order(value: String) { val current = activeOrdering.value; activeOrdering.value = value to current.second; saved["sort:${orderingKey()}"] = value; saved["custom_sort:${orderingKey()}"] = true }
    val sortReset = MutableStateFlow(0L)
    var sortResetDescending: Boolean? = null; private set
    fun reverse() { val current = activeOrdering.value; sortResetDescending = !current.second; sortReset.value += 1; activeOrdering.value = current.first to !current.second; saved["descending:${orderingKey()}"] = !current.second; saved["custom_sort:${orderingKey()}"] = true }
    fun back(): Boolean {
        val current = route.value
        if (selectedAlbums.value.isNotEmpty()) { selectAlbums(emptySet()); albumMenu(false); return true }
        val handled = when {
            current.tab == "RECEIVED" && received.value != null -> { received(null); if (!restorePrevious()) go("PHOTO"); true }
            current.viewer != null -> { update(current.copy(viewer = null, forceLibrary = true)); true }
            restorePrevious() -> true
            current.album != null -> { update(current.copy(album = null)); true }
            current.collection != null -> { update(current.copy(collection = null)); true }
            current.musicGroup != null -> { update(current.copy(musicGroup = null)); true }
            current.tab !in setOf("PHOTO", "ALBUM", "VIDEO", "MUSIC", "FAVORITE") -> { go("PHOTO"); true }
            else -> false
        }
        if (handled && current.tab == "FRAME" && route.value.tab != "FRAME") restoreFrameDisplayRole()
        return handled
    }
    private fun restorePrevious(): Boolean {
        val history = saved.get<ArrayList<String>>("navigation_history") ?: return false
        if (history.isEmpty()) return false
        return try {
            val json = JSONObject(history.last())
            fun nullable(key: String) = json.optString(key).takeIf { it.isNotEmpty() && it != "null" }
            update(GalleryRoute(json.getString("tab"), nullable("album"), nullable("collection"), nullable("viewer"), json.optBoolean("force"), json.optString("kind", "ALL").takeIf(MediaFilters::valid) ?: "ALL", json.optString("music_view", "TRACKS"), nullable("music_group")))
            saved["query"] = json.optString("query", "")
            val restoredKey = orderingKey()
            val restoredCustomSort = json.optBoolean("custom_sort", false)
            saved["sort:$restoredKey"] = json.optString("sort", "NAME")
            saved["descending:$restoredKey"] = json.optBoolean("descending", false)
            saved["custom_sort:$restoredKey"] = restoredCustomSort
            saved["custom_sort"] = restoredCustomSort
            if (!restoredCustomSort) applyOrdering(route.value) else activeOrdering.value = saved.get<String>("sort:$restoredKey").orEmpty().ifBlank { "NAME" } to (saved.get<Boolean>("descending:$restoredKey") ?: false)
            saved["navigation_history"] = ArrayList(history.dropLast(1))
            true
        } catch (_: Exception) { saved["navigation_history"] = arrayListOf<String>(); false }
    }
    fun restorePanelRole() {
        val current = route.value
        update(current.copy(tab = current.tab.takeIf { it in NavbarOrder.DEFAULT || it in NavbarOrder.OPTIONAL } ?: "PHOTO",
            viewer = null, forceLibrary = false))
    }
    fun navigationRestore(): () -> Unit {
        val previous = route.value
        val previousQuery = query.value; val previousSort = activeOrdering.value.first; val previousDescending = activeOrdering.value.second
        val previousCustomOrdering = customOrdering.value
        val history = ArrayList(saved.get<ArrayList<String>>("navigation_history") ?: arrayListOf())
        return {
            update(previous); saved["query"] = previousQuery
            activeOrdering.value = previousSort to previousDescending
            saved["custom_sort:${orderingKey(previous)}"] = previousCustomOrdering
            saved["navigation_history"] = history
        }
    }
    fun takeLibraryFrom(other: GalleryWindowModel) {
        saved["favorite_entry_pending"] = false
        update(other.route.value.copy(viewer = null, forceLibrary = false), preserveSelection = true)
        saved["query"] = other.query.value
        activeOrdering.value = other.sort.value to other.descending.value
        saved["custom_sort:${orderingKey(other.route.value)}"] = other.customOrdering.value
        saved["navigation_history"] = ArrayList(other.saved.get<ArrayList<String>>("navigation_history") ?: arrayListOf())
        lastVideoRoute = other.lastVideoRoute
        saved["album_anchor"] = other.saved.get<String>("album_anchor")
        saved["video_album_anchor"] = other.saved.get<String>("video_album_anchor")
        selectAlbums(other.selectedAlbums.value); albumMenu(false)
        configureFrame(other.frameConfig.value); frameCursor(other.frameCursor.value); framePlaying(other.framePlaying.value); frameConfiguring(other.frameConfiguring.value)
        val frameReturnSwap = other.saved.get<Boolean>("frame_return_swapped")
        if (route.value.tab == "FRAME" && frameReturnSwap != null) saved["frame_return_swapped"] = frameReturnSwap
        else saved.remove<Boolean>("frame_return_swapped")
    }
    fun syncRole(swapped: Boolean) {
        if (saved.contains("last_swapped") && saved.get<Boolean>("last_swapped") != swapped)
            update(route.value.copy(viewer = null, forceLibrary = false), preserveSelection = true)
        saved["last_swapped"] = swapped
    }
}


internal fun mediaSortValue(item: MediaEntity, order: String): Long = when (order) {
    "DATE" -> item.modified
    "SIZE" -> item.size
    "DURATION" -> item.duration ?: -1L
    "ADDED" -> item.added
    else -> item.favoriteAddedAt
}

internal fun mediaOrderComparator(order: String): Comparator<MediaEntity> =
    (if (order in setOf("DATE", "SIZE", "DURATION", "ADDED", "FAVORITE_ADDED")) compareBy { mediaSortValue(it, order) }
    else Comparator<MediaEntity> { a, b -> MediaNames.natural.compare(a.title ?: a.name, b.title ?: b.name) }).thenBy { it.id }
