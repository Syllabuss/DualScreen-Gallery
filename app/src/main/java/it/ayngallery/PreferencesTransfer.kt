package it.ayngallery

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Portable preferences only. Display grants, SAF access, media and operation journals are never imported. */
object PreferencesTransfer {
    const val MAX_BYTES = 640 * 1024
    private const val KIND = "ayngallery-settings"
    fun encode(value: GalleryPreferences): String {
        value.validate()
        val settings = JSONObject().put("theme", value.themeId).put("compact", value.compact).put("gifMode", value.gifMode)
            .put("gifLimit", value.gifLimit).put("gameMode", value.gameMode).put("reducedMotion", value.reducedMotion)
            .put("slideshowSeconds", value.slideshowSeconds).put("artworkOrder", value.artworkOrder)
            .put("showMediaNames", value.showMediaNames).put("viewerArrows", value.viewerArrows).put("viewerZoom", value.viewerZoom)
            .put("photoNavigator", value.photoNavigator).put("gifFrameCount", value.gifFrameCount).put("alwaysPrevious", value.alwaysPrevious).put("pad", JSONObject(value.pad.encode())).put("touch", JSONObject(value.touch.encode()))
        settings.put("navbarLabels", value.navbarLabels).put("navbarOrder", org.json.JSONArray(value.navbarOrder))
        settings.put("customTheme", value.customTheme?.let { JSONObject(it.encode()) } ?: JSONObject.NULL)
        settings.put("hiddenTools", org.json.JSONArray(value.hiddenTools.sorted())).put("timelineModes", JSONObject(value.timelineModes))
        settings.put("hideGifBadge", value.hideGifBadge).put("uiSounds", value.uiSounds)
        settings.put("showMusicAlbums", false).put("disabledCategories", org.json.JSONArray(value.disabledCategories.sorted()))
            .put("disabledSounds", org.json.JSONArray(value.disabledSounds.sorted())).put("persistSelectedMedia", value.persistSelectedMedia).put("directDeleteEnabled", value.directDeleteEnabled).put("nestedAlbums", value.nestedAlbums)
        settings.put("defaultSection", value.defaultSection)
        settings.put("controllerSorting", value.controllerSorting).put("musicCoverEffect", value.musicCoverEffect).put("videoDimSeconds", value.videoDimSeconds).put("imageOverlayAutoHide", value.imageOverlayAutoHide).put("videoMixAudio", value.videoMixAudio).put("gifFrameCounter", value.gifFrameCounter).put("gifFrameSelector", value.gifFrameSelector).put("ftpEnabled", value.ftpEnabled).put("previewPinEnabled", value.previewPinEnabled).put("comparisonEnabled", value.comparisonEnabled).put("animatedAlbumCovers", value.animatedAlbumCovers).put("videoKeepPlaying", value.videoKeepPlaying).put("actionMenusBelow", value.actionMenusBelow).put("albumCoverLimit", value.albumCoverLimit).put("allAnimatedAlbumCovers", value.allAnimatedAlbumCovers).put("previewNearest", value.previewNearest)
        settings.put("imagePreviewName", value.imagePreviewName).put("gridPreviewMotion", value.gridPreviewMotion)
        settings.put("backgroundX", value.backgroundX).put("backgroundY", value.backgroundY).put("backgroundZoom", value.backgroundZoom)
        settings.put("videoStickControls", value.videoStickControls)
        settings.put("albumShadows", value.albumShadows).put("albumStudio", value.albumStudio)
        settings.put("backgroundMode", value.backgroundMode).put("backgroundEnd", value.backgroundEnd ?: JSONObject.NULL)
            .put("backgroundStrength", value.backgroundStrength).put("backgroundMedia", value.backgroundMedia ?: JSONObject.NULL).put("backgroundOpacity", value.backgroundOpacity)
        settings.put("imageBackdrop", value.imageBackdrop).put("imageBackdropColor", value.imageBackdropColor)
            .put("imageBackdropEnd", value.imageBackdropEnd).put("imageBackdropOpacity", value.imageBackdropOpacity)
        settings.put("musicDuringSleep", value.musicDuringSleep).put("videoDuringSleep", value.videoDuringSleep)
        settings.put("showEmptyFolders", value.showEmptyFolders).put("horizontalAlbums", value.horizontalAlbums).put("albumCarousel", value.albumCarousel).put("videoSkipPreview", value.videoSkipPreview)
        settings.put("tvEnabled", value.tvEnabled).put("tvInNavbar", value.tvInNavbar).put("tvInMediaFavorites", value.tvInMediaFavorites)
        settings.put("showUpperStatusBar", value.showUpperStatusBar).put("confirmVideoReplacement", value.confirmVideoReplacement)
        settings.put("musicColorMode", value.musicColorMode).put("musicBackgroundColor", value.musicBackgroundColor).put("musicBarsColor", value.musicBarsColor)
        return JSONObject().put("kind", KIND).put("version", 36).put("preferences", settings).toString().also {
            require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { localized(R.string.text_settings_too_large_34c3b1, "Impostazioni troppo grandi") }
        }
    }
    fun decode(value: String): GalleryPreferences {
        require(value.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { localized(R.string.text_file_too_large_234633, "File troppo grande") }
        val parser = JSONTokener(value)
        val root = parser.nextValue() as? JSONObject ?: error(localized(R.string.text_unrecognized_format_a07e3b, "Formato non riconosciuto"))
        require(parser.nextClean() == '\u0000') { localized(R.string.text_unexpected_content_after_settings_912e60, "Contenuto inatteso dopo le impostazioni") }
        require(root.keys().asSequence().toSet() == setOf("kind", "version", "preferences")) { localized(R.string.text_unrecognized_format_a07e3b, "Formato non riconosciuto") }
        val version = root.integer("version")
        require(root.get("kind") == KIND && version in 1..36) { localized(R.string.text_unsupported_version_0fd927, "Versione non supportata") }
        val p = root.getJSONObject("preferences")
        require(p.keys().asSequence().toSet() == setOf("theme", "compact", "gifMode", "gifLimit", "gameMode", "reducedMotion",
            "slideshowSeconds", "artworkOrder", "alwaysPrevious", "pad") + (if (version >= 2) setOf("touch") else emptySet<String>()) +
            (if (version >= 3) setOf("showMediaNames", "viewerArrows", "viewerZoom", "photoNavigator", "gifFrameCount") else emptySet<String>()) +
            (if (version >= 4) setOf("navbarLabels") else emptySet<String>()) +
            (if (version >= 5) setOf("navbarOrder") else emptySet<String>()) +
            (if (version >= 6) setOf("customTheme") else emptySet<String>()) +
            (if (version >= 7) setOf("hiddenTools", "timelineModes") else emptySet<String>()) +
            (if (version >= 8) setOf("hideGifBadge", "uiSounds") else emptySet<String>()) +
            (if (version >= 9) setOf("showMusicAlbums", "disabledCategories", "disabledSounds", "persistSelectedMedia") else emptySet<String>()) +
            (if (version >= 10) setOf("directDeleteEnabled", "nestedAlbums") else emptySet<String>()) + (if (version >= 11) setOf("backgroundMode", "backgroundEnd", "backgroundStrength", "backgroundMedia", "backgroundOpacity") else emptySet<String>()) + (if (version >= 12) setOf("albumShadows") else emptySet<String>()) + (if (version >= 13) setOf("albumStudio") else emptySet<String>()) + (if (version >= 14) setOf("videoStickControls") else emptySet<String>()) + (if (version >= 15) setOf("backgroundX", "backgroundY", "backgroundZoom") else emptySet<String>()) + (if (version >= 16) setOf("defaultSection") else emptySet<String>()) + (if (version >= 17) setOf("imagePreviewName", "gridPreviewMotion") else emptySet<String>()) + (if (version >= 18) setOf("controllerSorting") else emptySet<String>()) + (if (version >= 19) setOf("musicCoverEffect", "videoDimSeconds") else emptySet<String>()) + (if (version >= 20) setOf("imageOverlayAutoHide") else emptySet<String>()) + (if (version >= 21) setOf("videoMixAudio") else emptySet<String>()) + (if (version >= 22) setOf("gifFrameCounter", "gifFrameSelector") else emptySet<String>()) + (if (version >= 23) setOf("ftpEnabled") else emptySet<String>()) + (if (version >= 24) setOf("previewPinEnabled", "comparisonEnabled", "animatedAlbumCovers", "videoKeepPlaying") else emptySet<String>()) + (if (version >= 25) setOf("actionMenusBelow") else emptySet<String>()) + (if (version >= 26) setOf("albumCoverLimit", "allAnimatedAlbumCovers") else emptySet<String>()) + (if (version >= 27) setOf("previewNearest") else emptySet<String>()) + (if (version >= 28) setOf("imageBackdrop", "imageBackdropColor", "imageBackdropEnd", "imageBackdropOpacity") else emptySet<String>()) + (if (version >= 29) setOf("musicColorMode", "musicBackgroundColor", "musicBarsColor") else emptySet<String>()) + (if (version >= 30) setOf("confirmVideoReplacement", "showUpperStatusBar") else emptySet<String>()) + (if (version >= 31) setOf("tvEnabled", "tvInNavbar", "tvInMediaFavorites") else emptySet<String>()) + (if (version >= 32) setOf("musicDuringSleep", "videoDuringSleep") else emptySet<String>()) + (if (version >= 33) setOf("showEmptyFolders") else emptySet<String>()) + (if (version >= 34) setOf("horizontalAlbums") else emptySet<String>()) + (if (version >= 35) setOf("albumCarousel") else emptySet<String>()) + (if (version >= 36) setOf("videoSkipPreview") else emptySet<String>())) { localized(R.string.text_incomplete_or_unsupported_settings_8affeb, "Impostazioni incomplete o non supportate") }
        return GalleryPreferences(p.text("theme"), p.boolean("compact"), p.text("gifMode"), p.integer("gifLimit"),
            p.boolean("gameMode"), p.boolean("reducedMotion"), p.integer("slideshowSeconds"), p.text("artworkOrder"),
            p.boolean("alwaysPrevious"), PadSettings.decode(p.getJSONObject("pad").toString()),
            if (version >= 2) TouchSettings.decode(p.getJSONObject("touch").toString()) else TouchSettings(),
            version >= 3 && p.boolean("showMediaNames"), version >= 3 && p.boolean("viewerArrows"), version >= 3 && p.boolean("viewerZoom"),
            version < 3 || p.boolean("photoNavigator"), version < 3 || p.boolean("gifFrameCount"), version >= 4 && p.boolean("navbarLabels"),
            if (version >= 5) p.getJSONArray("navbarOrder").let { array -> List(array.length()) { index ->
                array.get(index) as? String ?: error(localized(R.string.text_invalid_navigation_bar_order_0bf386, "Ordine navbar non valido")) } } else NavbarOrder.DEFAULT,
            if (version >= 6 && !p.isNull("customTheme")) CustomTheme.decode(p.getJSONObject("customTheme").toString()) else null,
            if (version >= 7) p.getJSONArray("hiddenTools").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } else InterfaceTools.defaultHidden,
            if (version >= 7) InterfaceTools.decodeTimeline(p.getJSONObject("timelineModes").toString()) else mapOf("PHOTO" to "MONTH"), version >= 8 && p.boolean("hideGifBadge"), version < 8 || p.boolean("uiSounds"), (version >= 9 && p.boolean("showMusicAlbums")).let { false },
            if (version >= 9) p.getJSONArray("disabledCategories").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } else emptySet(),
            if (version >= 9) p.getJSONArray("disabledSounds").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } else emptySet(),
            version >= 9 && p.boolean("persistSelectedMedia"), version >= 10 && p.boolean("directDeleteEnabled"), version >= 10 && p.boolean("nestedAlbums"), if (version >= 11) p.text("backgroundMode") else "PLAIN", if (version >= 11 && !p.isNull("backgroundEnd")) p.text("backgroundEnd") else null, if (version >= 11) p.integer("backgroundStrength") else 50, if (version >= 11 && !p.isNull("backgroundMedia")) p.text("backgroundMedia") else null, if (version >= 11) p.integer("backgroundOpacity") else 25, if (version >= 12) p.text("albumShadows") else "VERTICAL", version >= 13 && p.boolean("albumStudio"), version < 14 || p.boolean("videoStickControls"), if (version >= 15) p.integer("backgroundX") else 0, if (version >= 15) p.integer("backgroundY") else 0, if (version >= 15) p.integer("backgroundZoom") else 100, if (version >= 16) p.text("defaultSection") else "ALBUM", version < 17 || p.boolean("imagePreviewName"), version < 17 || p.boolean("gridPreviewMotion"), version >= 18 && p.boolean("controllerSorting"), if (version >= 19) p.integer("musicCoverEffect") else 0, if (version >= 19) p.integer("videoDimSeconds") else 0, version >= 20 && p.boolean("imageOverlayAutoHide"), version >= 21 && p.boolean("videoMixAudio"), version < 22 || p.boolean("gifFrameCounter"), version >= 22 && p.boolean("gifFrameSelector"), version >= 23 && p.boolean("ftpEnabled"), version >= 24 && p.boolean("previewPinEnabled"), version >= 24 && p.boolean("comparisonEnabled"), version < 24 || p.boolean("animatedAlbumCovers"), version < 24 || p.boolean("videoKeepPlaying"), version >= 25 && p.boolean("actionMenusBelow"), if (version >= 26) p.integer("albumCoverLimit") else 4, version >= 26 && p.boolean("allAnimatedAlbumCovers"), version >= 27 && p.boolean("previewNearest")).copy(
                imageBackdrop = if (version >= 28) p.text("imageBackdrop") else "GRID",
                imageBackdropColor = if (version >= 28) p.text("imageBackdropColor") else "#252530",
                imageBackdropEnd = if (version >= 28) p.text("imageBackdropEnd") else "#080810",
                imageBackdropOpacity = if (version >= 28) p.integer("imageBackdropOpacity") else 50,
                musicColorMode = if (version >= 29) p.text("musicColorMode") else "THEME",
                musicBackgroundColor = if (version >= 29) p.text("musicBackgroundColor") else "#10101C",
                musicBarsColor = if (version >= 29) p.text("musicBarsColor") else "#66CFFF",
                confirmVideoReplacement = version < 30 || p.boolean("confirmVideoReplacement"), showUpperStatusBar = version >= 30 && p.boolean("showUpperStatusBar"),
                musicDuringSleep = version >= 32 && p.boolean("musicDuringSleep"), videoDuringSleep = version >= 32 && p.boolean("videoDuringSleep"),
                videoSkipPreview = version >= 36 && p.boolean("videoSkipPreview"), albumCarousel = version >= 35 && p.boolean("albumCarousel"), horizontalAlbums = version >= 34 && p.boolean("horizontalAlbums"), showEmptyFolders = version >= 33 && p.boolean("showEmptyFolders"), tvEnabled = version >= 31 && p.boolean("tvEnabled"), tvInNavbar = version >= 31 && p.boolean("tvInNavbar"), tvInMediaFavorites = version >= 31 && p.boolean("tvInMediaFavorites")).also { it.validate() }
    }
    private fun JSONObject.text(key: String) = (get(key) as? String) ?: error(localized(R.string.text_invalid_1_s_field_949520, "Campo %1\$s non valido", key))
    private fun JSONObject.boolean(key: String) = (get(key) as? Boolean) ?: error(localized(R.string.text_invalid_1_s_field_949520, "Campo %1\$s non valido", key))
    private fun JSONObject.integer(key: String): Int {
        val value = get(key)
        require(value is Int || value is Long) { localized(R.string.text_invalid_1_s_field_949520, "Campo %1\$s non valido", key) }
        val number = (value as Number).toLong()
        require(number in Int.MIN_VALUE..Int.MAX_VALUE) { localized(R.string.text_1_s_field_out_of_range_95e5ad, "Campo %1\$s fuori intervallo", key) }
        return number.toInt()
    }
    suspend fun read(resolver: ContentResolver, uri: Uri): String = withContext(Dispatchers.IO) {
        require(uri.scheme == "content") { localized(R.string.text_select_a_system_document_d34685, "Seleziona un documento dal sistema") }
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_BYTES) { localized(R.string.text_file_too_large_234633, "File troppo grande") }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: error(localized(R.string.text_document_unavailable_2d9594, "Documento non disponibile"))
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString().also { decode(it) }
    }
    suspend fun write(resolver: ContentResolver, uri: Uri, value: String) = withContext(Dispatchers.IO) {
        require(uri.scheme == "content") { localized(R.string.text_select_a_system_destination_da89d0, "Seleziona una destinazione dal sistema") }
        decode(value)
        resolver.openOutputStream(uri, "wt")?.use { it.write(value.toByteArray(Charsets.UTF_8)); it.flush() }
            ?: error(localized(R.string.text_destination_unavailable_1a3939, "Destinazione non disponibile"))
        check(read(resolver, uri) == value) { localized(R.string.text_export_unverified_the_document_may_be_incomplete_eceee6, "Esportazione non verificata; il documento potrebbe essere incompleto") }
    }
}
