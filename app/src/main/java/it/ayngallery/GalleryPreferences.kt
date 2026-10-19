package it.ayngallery

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object MusicCoverEffects {
    // Preserve stored effect IDs while presenting the requested cycle.
    val ORDER = listOf(0, 2, 1, 3)
    fun next(current: Int) = ORDER[(ORDER.indexOf(current).coerceAtLeast(0) + 1) % ORDER.size]
}

object NavbarOrder {
    val DEFAULT = listOf("PHOTO", "ALBUM", "VIDEO", "MUSIC", "FAVORITE")
    val OPTIONAL = setOf("TV")
    fun valid(value: List<String>) = value.size == value.toSet().size && value.containsAll(DEFAULT) && value.all { it in DEFAULT || it in OPTIONAL }
    fun withTv(order: List<String>): List<String> = if ("TV" in order) order else order.toMutableList().apply { add((indexOf("MUSIC") + 1).coerceAtLeast(0), "TV") }
    fun editable(prefs: GalleryPreferences) = if (prefs.tvEnabled && prefs.tvInNavbar)
        withTv(prefs.navbarOrder) else prefs.navbarOrder.filterNot { it == "TV" }
}

data class GalleryPreferences(val themeId: String = "ayn_rainbow", val compact: Boolean = false,
    val gifMode: String = "SELECTED", val gifLimit: Int = 4, val gameMode: Boolean = false,
    val reducedMotion: Boolean = false, val slideshowSeconds: Int = 8, val artworkOrder: String = ArtworkOrders.DEFAULT,
    val alwaysPrevious: Boolean = false, val pad: PadSettings = PadSettings(), val touch: TouchSettings = TouchSettings(),
    val showMediaNames: Boolean = false, val viewerArrows: Boolean = false, val viewerZoom: Boolean = false,
    val photoNavigator: Boolean = true, val gifFrameCount: Boolean = true, val navbarLabels: Boolean = false, val navbarOrder: List<String> = NavbarOrder.DEFAULT, val customTheme: CustomTheme? = null,
    val hiddenTools: Set<String> = InterfaceTools.defaultHidden, val timelineModes: Map<String, String> = mapOf("PHOTO" to "MONTH"), val hideGifBadge: Boolean = false, val uiSounds: Boolean = true,
    val showMusicAlbums: Boolean = false, val disabledCategories: Set<String> = emptySet(),
    val disabledSounds: Set<String> = emptySet(), val persistSelectedMedia: Boolean = false, val directDeleteEnabled: Boolean = false, val nestedAlbums: Boolean = false, val backgroundMode: String = "PLAIN", val backgroundEnd: String? = null, val backgroundStrength: Int = 50, val backgroundMedia: String? = null, val backgroundOpacity: Int = 25, val albumShadows: String = "VERTICAL", val albumStudio: Boolean = false, val videoStickControls: Boolean = true, val backgroundX: Int = 0, val backgroundY: Int = 0, val backgroundZoom: Int = 100, val defaultSection: String = "ALBUM", val imagePreviewName: Boolean = true, val gridPreviewMotion: Boolean = true, val controllerSorting: Boolean = false, val musicCoverEffect: Int = 0, val videoDimSeconds: Int = 0, val imageOverlayAutoHide: Boolean = false, val videoMixAudio: Boolean = false, val gifFrameCounter: Boolean = true, val gifFrameSelector: Boolean = false, val ftpEnabled: Boolean = false, val previewPinEnabled: Boolean = false, val comparisonEnabled: Boolean = false, val animatedAlbumCovers: Boolean = true, val videoKeepPlaying: Boolean = true, val actionMenusBelow: Boolean = false, val albumCoverLimit: Int = 4, val allAnimatedAlbumCovers: Boolean = false, val previewNearest: Boolean = false,
    val imageBackdrop: String = "GRID", val imageBackdropColor: String = "#252530",
    val imageBackdropEnd: String = "#080810", val imageBackdropOpacity: Int = 50,
    val musicColorMode: String = "THEME", val musicBackgroundColor: String = "#10101C", val musicBarsColor: String = "#66CFFF", val confirmVideoReplacement: Boolean = true, val showUpperStatusBar: Boolean = false,
    val musicDuringSleep: Boolean = false, val videoDuringSleep: Boolean = false, val tvEnabled: Boolean = false, val tvInNavbar: Boolean = false, val tvInMediaFavorites: Boolean = false, val showEmptyFolders: Boolean = false) {
    fun validate() {
        require((GalleryThemes.find(themeId) != null || themeId == GalleryThemes.CUSTOM && customTheme != null) && gifMode in setOf("OFF", "SELECTED", "VISIBLE") && gifLimit in 0..40 && albumCoverLimit in 1..40 &&
            slideshowSeconds in 2..120 && ArtworkOrders.valid(artworkOrder)) { localized(R.string.text_invalid_settings_73e331, "Impostazioni non valide") }
        require(musicCoverEffect in 0..3 && videoDimSeconds in setOf(0, 5, 10, 30, 60, 300))
        require(defaultSection in NavbarOrder.DEFAULT)
        require(NavbarOrder.valid(navbarOrder)) { localized(R.string.text_invalid_navigation_bar_order_0bf386, "Ordine navbar non valido") }
        require(disabledCategories.all { it in NavbarOrder.DEFAULT || it in NavbarOrder.OPTIONAL } && NavbarOrder.DEFAULT.any { it !in disabledCategories })
        require(disabledSounds.all { it in UiSounds.CUES })
        require(backgroundX in -100..100 && backgroundY in -100..100 && backgroundZoom in 100..300)
        require(backgroundMode in setOf("PLAIN", "GRADIENT", "PHOTO") && backgroundStrength in 0..100 && backgroundOpacity in 0..100)
        require(backgroundEnd == null || backgroundEnd.matches(Regex("#[0-9a-fA-F]{6}")))
        require(backgroundMedia == null || backgroundMedia.length in 1..512)
        require(albumShadows in setOf("TOP", "BOTTOM", "SIDES", "ALL", "VERTICAL", "NONE"))
        require(imageBackdrop in ImageBackdrops.MODES && imageBackdropOpacity in 0..100)
        require(musicColorMode in musicColorModes && listOf(musicBackgroundColor, musicBarsColor).all { it.matches(Regex("#[0-9a-fA-F]{6}")) })
        require(listOf(imageBackdropColor, imageBackdropEnd).all { it.matches(Regex("#[0-9a-fA-F]{6}")) })
        customTheme?.validate()
        require(hiddenTools.all { it in InterfaceTools.validKeys } && timelineModes.all { it.key in InterfaceTools.sections && it.value in MediaTimeline.modes })
        pad.validate()
        touch.validate()
    }
}

class PreferenceStore(private val context: Context) {
    private val mutation = Mutex()
    private val prefs = context.getSharedPreferences("ui_preferences_v1", Context.MODE_PRIVATE)
    private fun readAlbumPlacement(key: String) = runCatching {
        val json = org.json.JSONObject(prefs.getString(key, "{}") ?: "{}")
        val cells = json.getJSONObject("cells")
        val order = json.getJSONArray("order")
        AlbumGridPlacement(json.getInt("columns"), cells.keys().asSequence().associateWith { id ->
            cells.getJSONArray(id).let { AlbumGridCell(it.getInt(0), it.getInt(1), it.getInt(2)) }
        }, List(order.length()) { order.getString(it) })
    }.getOrDefault(AlbumGridPlacement())
    private val albumPlacement = MutableStateFlow(readAlbumPlacement("album_placement"))
    private val videoAlbumPlacement = MutableStateFlow(readAlbumPlacement("video_album_placement"))
    internal fun albumPlacement(video: Boolean) = if (video) videoAlbumPlacement else albumPlacement
    internal fun reorderAlbumGrid(ids: List<String>, columns: Int, cells: Map<String, AlbumGridCell>, video: Boolean) {
        val next = (ids + albumOrder.value.filterNot { it in ids }).distinct()
        val jsonCells = org.json.JSONObject()
        cells.forEach { (id, cell) -> jsonCells.put(id, org.json.JSONArray(listOf(cell.row, cell.start, cell.span))) }
        val json = org.json.JSONObject().put("columns", columns).put("cells", jsonCells).put("order", org.json.JSONArray(next))
        prefs.edit().putString("album_order", org.json.JSONArray(next).toString())
            .putString(if (video) "video_album_placement" else "album_placement", json.toString()).apply()
        albumPlacement(video).value = AlbumGridPlacement(columns, cells, next)
        albumOrder.value = next
    }
    val albumSizes = MutableStateFlow(runCatching {
        val json = org.json.JSONObject(prefs.getString("album_sizes", "{}") ?: "{}")
        json.keys().asSequence().associateWith { if (json.optInt(it, 2) == 4) 4 else 2 }
    }.getOrDefault(emptyMap()))
    fun albumSize(ids: Set<String>, halfUnits: Int) {
        require(halfUnits in setOf(2, 4) && ids.all { it.length in 1..512 })
        val next = albumSizes.value + ids.associateWith { halfUnits }
        prefs.edit().putString("album_sizes", org.json.JSONObject(next).toString()).apply()
        albumSizes.value = next
    }
    val videoAlbumSizes = MutableStateFlow(runCatching {
        val json = org.json.JSONObject(prefs.getString("video_album_sizes", "{}") ?: "{}")
        json.keys().asSequence().associateWith { if (json.optInt(it, 2) == 4) 4 else 2 }
    }.getOrDefault(emptyMap()))
    fun videoAlbumSize(ids: Set<String>, halfUnits: Int) {
        require(halfUnits in setOf(2, 4) && ids.all { it.length in 1..512 })
        val next = videoAlbumSizes.value + ids.associateWith { halfUnits }
        prefs.edit().putString("video_album_sizes", org.json.JSONObject(next).toString()).apply()
        videoAlbumSizes.value = next
    }
    val videoAlbumColumns = MutableStateFlow(prefs.getInt("video_album_columns", 3).coerceIn(2, 8))
    fun videoAlbumColumns(value: Int) {
        val next = value.coerceIn(2, 8)
        prefs.edit().putInt("video_album_columns", next).apply()
        if (next != videoAlbumColumns.value) (context.applicationContext as? GalleryApp)?.sounds?.play(if (next < videoAlbumColumns.value) "zoom-in" else "zoom-out")
        videoAlbumColumns.value = next
    }
    val tutorialCompleted = MutableStateFlow(prefs.getBoolean("tutorial_completed", false))
    val tutorialStarted = MutableStateFlow(prefs.getBoolean("tutorial_started", false))
    fun beginTutorial() {
        if (tutorialStarted.value) return
        prefs.edit().putBoolean("tutorial_started", true).apply()
        tutorialStarted.value = true
    }
    fun completeTutorial() {
        prefs.edit().putBoolean("tutorial_completed", true).apply()
        tutorialCompleted.value = true
    }
    init {
        if (!prefs.getBoolean("touch_defaults_v2", false)) {
            val current = runCatching { prefs.getString("touch_bindings", null)?.let { TouchSettings.decode(it) } }.getOrNull() ?: TouchSettings()
            val oldStock = current.threeFinger.values.all { directions -> directions.all { (direction, action) ->
                when (direction) { TouchDirection.DOWN -> action in setOf(TouchAction.NONE, TouchAction.DIM)
                    TouchDirection.TAP -> action == TouchAction.DIM; else -> action == TouchAction.NONE }
            } }
            val next = if (oldStock) current.copy(threeFinger = TouchSettings().threeFinger) else current
            prefs.edit().putString("touch_bindings", next.encode()).putBoolean("touch_defaults_v2", true).putBoolean("moon_gesture_default_v1", true).apply()
        }
        if (!prefs.getBoolean("touch_directions_v3", false)) {
            val current = runCatching { prefs.getString("touch_bindings", null)?.let { TouchSettings.decode(it) } }.getOrNull() ?: TouchSettings()
            val defaults = TouchSettings().threeFinger
            val oldStock = defaults.mapValues { (_, directions) -> directions +
                (TouchDirection.LEFT to TouchAction.TAB_NEXT) + (TouchDirection.RIGHT to TouchAction.TAB_PREVIOUS) }
            val next = if (current.threeFinger == oldStock) current.copy(threeFinger = defaults) else current
            prefs.edit().putString("touch_bindings", next.encode()).putBoolean("touch_directions_v3", true).apply()
        }
        if (!prefs.getBoolean("game_mode_retired_v28", false)) {
            prefs.edit().putBoolean("game", false).putBoolean("game_mode_retired_v28", true).apply()
        }

    }
    val musicCoverEffect = MutableStateFlow(prefs.getInt("music_cover_effect", 0).coerceIn(0, 3))
    fun musicCoverEffect(value: Int) {
        require(value in 0..3)
        prefs.edit().putInt("music_cover_effect", value).apply()
        musicCoverEffect.value = value
        state.value = state.value.copy(musicCoverEffect = value)
    }
    val videoFolderView = MutableStateFlow(prefs.getBoolean("video_folder_view", true))
    fun videoFolderView(value: Boolean) { prefs.edit().putBoolean("video_folder_view", value).apply(); videoFolderView.value = value }
    val settingsGrid = MutableStateFlow(prefs.getBoolean("settings_grid", true))
    fun settingsGrid(value: Boolean) { prefs.edit().putBoolean("settings_grid", value).apply(); settingsGrid.value = value }
    val albumOrder = MutableStateFlow(runCatching { org.json.JSONArray(prefs.getString("album_order", "[]")).let { a -> List(a.length()) { a.getString(it) }.distinct() } }.getOrDefault(emptyList()))
    fun rememberAlbums(ids: List<String>) {
        val fresh = ids.filterNot { it in albumOrder.value }
        if (fresh.isNotEmpty()) reorderAlbums(fresh.asReversed() + albumOrder.value)
    }
    fun reorderAlbums(ids: List<String>) {
        val next = (ids + albumOrder.value.filterNot { it in ids }).distinct()
        prefs.edit().putString("album_order", org.json.JSONArray(next).toString()).apply()
        albumOrder.value = next
    }
    val albumGrid = MutableStateFlow(prefs.getBoolean("album_grid", true))
    val albumColumns = MutableStateFlow(prefs.getInt("album_columns", 3).coerceIn(2, 8))
    private val legacyMediaGrid = prefs.getBoolean("media_grid", true)
    private val legacyMediaColumns = prefs.getInt("media_columns", 4).coerceIn(2, 8)
    val photoGrid = MutableStateFlow(prefs.getBoolean("photo_grid", legacyMediaGrid))
    val photoColumns = MutableStateFlow(prefs.getInt("photo_columns", legacyMediaColumns).coerceIn(2, 8))
    val albumMediaGrid = MutableStateFlow(prefs.getBoolean("album_media_grid", true))
    val albumMediaColumns = MutableStateFlow(prefs.getInt("album_media_columns", 3).coerceIn(2, 8))
    val videoSmallList = MutableStateFlow(prefs.getBoolean("video_small_list", false))
    fun videoSmallList(value: Boolean) { prefs.edit().putBoolean("video_small_list", value).apply(); videoSmallList.value = value }
    val videoGrid = MutableStateFlow(prefs.getBoolean("video_grid", legacyMediaGrid))
    val videoColumns = MutableStateFlow(prefs.getInt("video_columns", legacyMediaColumns).coerceIn(2, 8))
    val favoriteSmallList = MutableStateFlow(prefs.getBoolean("favorite_small_list", false))
    fun favoriteSmallList(value: Boolean) { prefs.edit().putBoolean("favorite_small_list", value).apply(); favoriteSmallList.value = value }
    val favoriteGrid = MutableStateFlow(prefs.getBoolean("favorite_grid", true))
    val favoriteColumns = MutableStateFlow(prefs.getInt("favorite_columns", 4).coerceIn(2, 8))
    val musicGrid = MutableStateFlow(prefs.getBoolean("music_grid", if (prefs.contains("media_grid")) legacyMediaGrid else false))
    val musicColumns = MutableStateFlow(prefs.getInt("music_columns", legacyMediaColumns).coerceIn(2, 8))
    val trashGrid = MutableStateFlow(true)
    val trashColumns = MutableStateFlow(prefs.getInt("trash_columns", 6).takeIf { it in 4..8 } ?: 6)
    private val albumViewModes = mutableMapOf<String, MutableStateFlow<Boolean>>()
    private val albumViewColumns = mutableMapOf<String, MutableStateFlow<Int>>()
    private fun albumViewKey(tab: String, albumId: String?) = albumId?.takeIf { tab == "ALBUM" || tab == "VIDEO" }?.let { "album_view:$tab:$it" }
    fun mediaGrid(tab: String, albumId: String? = null): MutableStateFlow<Boolean> {
        albumViewKey(tab, albumId)?.let { key -> return albumViewModes.getOrPut(key) { MutableStateFlow(prefs.getBoolean("${key}_grid", mediaGrid(tab).value)) } }
        return when (tab) {
        "ALBUM" -> albumMediaGrid
        "FAVORITE" -> favoriteGrid
        "TRASH" -> trashGrid
        "VIDEO" -> videoGrid
        "MUSIC" -> musicGrid
        else -> photoGrid
    }
    }
    fun mediaColumns(tab: String, albumId: String? = null): MutableStateFlow<Int> {
        albumViewKey(tab, albumId)?.let { key -> return albumViewColumns.getOrPut(key) { MutableStateFlow(prefs.getInt("${key}_columns", mediaColumns(tab).value).coerceIn(2, 8)) } }
        return when (tab) {
        "ALBUM" -> albumMediaColumns
        "FAVORITE" -> favoriteColumns
        "TRASH" -> trashColumns
        "VIDEO" -> videoColumns
        "MUSIC" -> musicColumns
        else -> photoColumns
    }
    }
    fun galleryView(albums: Boolean, tab: String = "PHOTO", grid: Boolean? = null, columns: Int? = null, albumId: String? = null) {
        val mode = if (albums) albumGrid else mediaGrid(tab, albumId)
        val density = if (albums) albumColumns else mediaColumns(tab, albumId)
        val prefix = if (albums) "album" else albumViewKey(tab, albumId) ?: when (tab) { "ALBUM" -> "album_media"; "FAVORITE" -> "favorite"; "TRASH" -> "trash"; "VIDEO" -> "video"; "MUSIC" -> "music"; else -> "photo" }
        val nextGrid = grid ?: mode.value
        val nextColumns = (columns ?: density.value).coerceIn(if (tab == "TRASH" && !albums) 4 else 2, 8)
        prefs.edit().putBoolean("${prefix}_grid", nextGrid).putInt("${prefix}_columns", nextColumns).apply()
        if (nextColumns != density.value) (context.applicationContext as? GalleryApp)?.sounds?.play(if (nextColumns < density.value) "zoom-in" else "zoom-out")
        mode.value = nextGrid; density.value = nextColumns
    }
    private val legacyTheme = if (!prefs.contains("dark")) "ayn_rainbow" else if (prefs.getBoolean("dark", true)) GalleryThemes.BLACK else GalleryThemes.WHITE
    val state = MutableStateFlow(GalleryPreferences(prefs.getString("theme", null)?.takeIf { GalleryThemes.find(it) != null || it == GalleryThemes.CUSTOM && runCatching { CustomTheme.decode(prefs.getString("custom_theme", "")!!) }.isSuccess } ?: legacyTheme, prefs.getBoolean("compact", false),
        prefs.getString("gif_mode", "SELECTED") ?: "SELECTED", prefs.getInt("gif_limit", 4).coerceIn(0, 40),
        prefs.getBoolean("game", false), prefs.getBoolean("reduced_motion", false), prefs.getInt("slideshow", 8).coerceIn(2, 120),
        prefs.getString("artwork_order", ArtworkOrders.DEFAULT)?.takeIf(ArtworkOrders::valid) ?: ArtworkOrders.DEFAULT,
        prefs.getBoolean("always_previous", false),
        prefs.getString("pad_bindings", null)?.let { runCatching { PadSettings.decode(it).albumShortcuts().gridShortcuts().settingsShortcut().viewShortcut().screenOptionsShortcuts() }.getOrNull() } ?: PadSettings(),
        runCatching { prefs.getString("touch_bindings", null)?.let { TouchSettings.decode(it) } }.getOrNull() ?: TouchSettings(),
        prefs.getBoolean("media_names", false), prefs.getBoolean("viewer_arrows", false), prefs.getBoolean("viewer_zoom", false),
        prefs.getBoolean("photo_navigator", true), prefs.getBoolean("gif_frame_count", true), prefs.getBoolean("navbar_labels", false),
        prefs.getString("navbar_order", null)?.split(",")?.takeIf(NavbarOrder::valid) ?: NavbarOrder.DEFAULT,
        runCatching { prefs.getString("custom_theme", null)?.let(CustomTheme::decode) }.getOrNull(),
        prefs.getStringSet("hidden_tools", InterfaceTools.defaultHidden)!!.filter { it in InterfaceTools.validKeys }.toSet(),
        runCatching { InterfaceTools.decodeTimeline(prefs.getString("timeline_modes", "{\"PHOTO\":\"MONTH\"}")!!) }.getOrDefault(emptyMap()), prefs.getBoolean("hide_gif_badge", false), prefs.getBoolean("ui_sounds", true), false,
        prefs.getStringSet("disabled_categories", emptySet())!!.filter { it in NavbarOrder.DEFAULT || it in NavbarOrder.OPTIONAL }.toSet().takeIf { disabled -> NavbarOrder.DEFAULT.any { it !in disabled } } ?: emptySet(),
        prefs.getStringSet("disabled_sounds", emptySet())!!.filter { it in UiSounds.CUES }.toSet(), prefs.getBoolean("persist_selected_media", false), prefs.getBoolean("direct_delete_enabled", false), prefs.getBoolean("nested_albums", false), prefs.getString("background_mode", "PLAIN") ?: "PLAIN", prefs.getString("background_end", null), prefs.getInt("background_strength", 50).coerceIn(0, 100), prefs.getString("background_media", null), prefs.getInt("background_opacity", 25).coerceIn(0, 100), prefs.getString("album_shadows", "VERTICAL")?.takeIf { it in setOf("TOP", "BOTTOM", "SIDES", "ALL", "VERTICAL", "NONE") } ?: "VERTICAL", prefs.getBoolean("album_studio", false), prefs.getBoolean("video_stick_controls", true), prefs.getInt("background_x", 0).coerceIn(-100, 100), prefs.getInt("background_y", 0).coerceIn(-100, 100), prefs.getInt("background_zoom", 100).coerceIn(100, 300), prefs.getString("default_section", "ALBUM")?.takeIf { it in NavbarOrder.DEFAULT } ?: "ALBUM", prefs.getBoolean("image_preview_name", true), prefs.getBoolean("grid_preview_motion", true), prefs.getBoolean("controller_sorting", false), prefs.getInt("music_cover_effect", 0).coerceIn(0, 3), prefs.getInt("video_dim_seconds", 0).takeIf { it in setOf(0, 5, 10, 30, 60, 300) } ?: 0, prefs.getBoolean("image_overlay_auto_hide", false), prefs.getBoolean("video_mix_audio", false), prefs.getBoolean("gif_frame_counter", true), prefs.getBoolean("gif_frame_selector", false), prefs.getBoolean("ftp_enabled", false), prefs.getBoolean("preview_pin_enabled", false), prefs.getBoolean("comparison_enabled", false), prefs.getBoolean("animated_album_covers", true), prefs.getBoolean("video_keep_playing", true), prefs.getBoolean("action_menus_below", false), prefs.getInt("album_cover_limit", 4).coerceIn(1, 40), prefs.getBoolean("all_animated_album_covers", false), prefs.getBoolean("preview_nearest", false)).copy(
            imageBackdrop = prefs.getString("image_backdrop", "GRID")?.takeIf { it in ImageBackdrops.MODES } ?: "GRID",
            imageBackdropColor = prefs.getString("image_backdrop_color", "#252530")?.takeIf { it.matches(Regex("#[0-9a-fA-F]{6}")) } ?: "#252530",
            imageBackdropEnd = prefs.getString("image_backdrop_end", "#080810")?.takeIf { it.matches(Regex("#[0-9a-fA-F]{6}")) } ?: "#080810",
            imageBackdropOpacity = prefs.getInt("image_backdrop_opacity", 50).coerceIn(0, 100),
            musicColorMode = prefs.getString("music_color_mode", "THEME")?.takeIf { it in musicColorModes } ?: "THEME",
            musicBackgroundColor = prefs.getString("music_background_color", "#10101C")?.takeIf { it.matches(Regex("#[0-9a-fA-F]{6}")) } ?: "#10101C",
            musicBarsColor = prefs.getString("music_bars_color", "#66CFFF")?.takeIf { it.matches(Regex("#[0-9a-fA-F]{6}")) } ?: "#66CFFF",
            confirmVideoReplacement = prefs.getBoolean("confirm_video_replacement", true), showUpperStatusBar = prefs.getBoolean("show_upper_status_bar", false),
            musicDuringSleep = prefs.getBoolean("music_during_sleep", false), videoDuringSleep = prefs.getBoolean("video_during_sleep", false),
            showEmptyFolders = prefs.getBoolean("show_empty_folders", false), tvEnabled = prefs.getBoolean("tv_enabled", false), tvInNavbar = prefs.getBoolean("tv_in_navbar", false), tvInMediaFavorites = prefs.getBoolean("tv_in_favorites", false)))
    suspend fun save(value: GalleryPreferences) = update { value }
    suspend fun update(change: (GalleryPreferences) -> GalleryPreferences) = withContext(Dispatchers.IO) { mutation.withLock {
        val value = change(state.value)
        value.validate()
        val pad = value.pad.encode()
        check(prefs.edit().putString("theme", value.themeId).putBoolean("dark", GalleryThemes.get(value).dark).putBoolean("compact", value.compact)
            .putString("gif_mode", value.gifMode).putInt("gif_limit", value.gifLimit).putBoolean("game", value.gameMode)
            .putBoolean("reduced_motion", value.reducedMotion).putInt("slideshow", value.slideshowSeconds).putString("artwork_order", value.artworkOrder)
            .putBoolean("media_names", value.showMediaNames).putBoolean("viewer_arrows", value.viewerArrows).putBoolean("viewer_zoom", value.viewerZoom)
            .putInt("music_cover_effect", value.musicCoverEffect).putInt("video_dim_seconds", value.videoDimSeconds)
            .putBoolean("video_stick_controls", value.videoStickControls).putBoolean("album_studio", value.albumStudio).putString("album_shadows", value.albumShadows).putBoolean("photo_navigator", value.photoNavigator).putBoolean("gif_frame_count", value.gifFrameCount).putBoolean("gif_frame_counter", value.gifFrameCounter).putBoolean("gif_frame_selector", value.gifFrameSelector).putBoolean("ftp_enabled", value.ftpEnabled).putBoolean("preview_pin_enabled", value.previewPinEnabled).putBoolean("comparison_enabled", value.comparisonEnabled).putBoolean("animated_album_covers", value.animatedAlbumCovers).putBoolean("video_keep_playing", value.videoKeepPlaying).putBoolean("action_menus_below", value.actionMenusBelow).putInt("album_cover_limit", value.albumCoverLimit).putBoolean("all_animated_album_covers", value.allAnimatedAlbumCovers).putBoolean("preview_nearest", value.previewNearest)
            .putString("music_color_mode", value.musicColorMode).putString("music_background_color", value.musicBackgroundColor).putString("music_bars_color", value.musicBarsColor)
            .putString("image_backdrop", value.imageBackdrop).putString("image_backdrop_color", value.imageBackdropColor)
            .putString("image_backdrop_end", value.imageBackdropEnd).putInt("image_backdrop_opacity", value.imageBackdropOpacity)
            .putString("custom_theme", value.customTheme?.encode()).putBoolean("navbar_labels", value.navbarLabels).putString("navbar_order", value.navbarOrder.joinToString(","))
            .putStringSet("hidden_tools", value.hiddenTools).putString("timeline_modes", org.json.JSONObject(value.timelineModes).toString())
            .putBoolean("hide_gif_badge", value.hideGifBadge).putBoolean("ui_sounds", value.uiSounds)
            .putBoolean("show_empty_folders", value.showEmptyFolders).putBoolean("tv_enabled", value.tvEnabled).putBoolean("tv_in_navbar", value.tvInNavbar).putBoolean("tv_in_favorites", value.tvInMediaFavorites)
            .putBoolean("music_during_sleep", value.musicDuringSleep).putBoolean("video_during_sleep", value.videoDuringSleep).putBoolean("show_upper_status_bar", value.showUpperStatusBar).putBoolean("confirm_video_replacement", value.confirmVideoReplacement).remove("show_music_albums").putBoolean("image_overlay_auto_hide", value.imageOverlayAutoHide).putBoolean("video_mix_audio", value.videoMixAudio)
            .putStringSet("disabled_categories", value.disabledCategories).putStringSet("disabled_sounds", value.disabledSounds)
            .putBoolean("persist_selected_media", value.persistSelectedMedia).putBoolean("direct_delete_enabled", value.directDeleteEnabled).putBoolean("nested_albums", value.nestedAlbums)
            .putString("background_mode", value.backgroundMode).putString("background_end", value.backgroundEnd)
            .putBoolean("image_preview_name", value.imagePreviewName).putBoolean("grid_preview_motion", value.gridPreviewMotion).putBoolean("controller_sorting", value.controllerSorting)
            .putString("default_section", value.defaultSection).putInt("background_x", value.backgroundX).putInt("background_y", value.backgroundY).putInt("background_zoom", value.backgroundZoom)
            .putInt("background_strength", value.backgroundStrength).putString("background_media", value.backgroundMedia).putInt("background_opacity", value.backgroundOpacity)
            .putBoolean("always_previous", value.alwaysPrevious).putString("pad_bindings", pad).putString("touch_bindings", value.touch.encode()).commit()) { localized(R.string.text_settings_not_saved_d67a68, "Impostazioni non salvate") }
        state.value = value
        musicCoverEffect.value = value.musicCoverEffect
    } }
    suspend fun importSettings(value: GalleryPreferences, expected: GalleryPreferences, commonProfile: Boolean) = update { current ->
        check(current == expected) { localized(R.string.text_settings_changed_on_the_other_panel_read_the_fil_805d13, "Le impostazioni sono cambiate sull'altro pannello. Rileggi il file prima di importare.") }
        if (commonProfile) value else value.copy(pad = value.pad.copy(profiles = value.pad.profiles +
            ("default" to current.pad.profiles.getValue("default"))))
    }
}
