package it.ayngallery

import android.app.WallpaperManager
import android.content.ClipData
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class ExternalAction(private val titleText: () -> String, private val confirmText: () -> String) {
    SHARE({ localized(R.string.text_share_5812e6, "Condividi") }, { localized(R.string.text_choose_app_965609, "Scegli app") }), OPEN({ localized(R.string.text_open_with_531e1d, "Apri con") }, { localized(R.string.text_choose_app_965609, "Scegli app") }), WALLPAPER({ localized(R.string.text_android_wallpaper_67c9fc, "Sfondo Android") }, { localized(R.string.text_open_android_crop_tool_3130eb, "Apri ritaglio Android") });
    val title: String get() = titleText()
    val confirm: String get() = confirmText()
}

data class ExternalTarget(val id: String, val key: String, val uri: String, val name: String,
    val mime: String, val kind: String, val size: Long, val modified: Long) {
    fun matches(item: MediaEntity) = id == item.id && key == item.documentKey && uri == item.uri && name == item.name &&
        mime == item.mime && kind == item.kind && !DocumentVersions.changed(size, modified, item.size, item.modified)
    companion object {
        fun from(item: MediaEntity) = ExternalTarget(item.id, item.documentKey, item.uri, item.name, item.mime, item.kind, item.size, item.modified)
    }
}

/** Only frozen locators are saved by the window. No Activity or permission is retained. */
data class ExternalRequest(val action: ExternalAction, val targets: List<ExternalTarget>, val copy: ShareCopyOptions? = null) {
    init {
        require(targets.isNotEmpty() && targets.size <= 128) { localized(R.string.text_choose_1128_files_to_open_ee836b, "Scegli da 1 a 128 file per questa apertura") }
        require(targets.map { it.id }.distinct().size == targets.size)
        require(action == ExternalAction.SHARE || targets.size == 1)
        require(action != ExternalAction.WALLPAPER || targets.single().kind == "IMAGE") { localized(R.string.text_wallpaper_requires_a_static_image_60bd7b, "Lo sfondo richiede un'immagine statica") }
        require(targets.all { it.uri.toUri().scheme == "content" })
        if (copy != null) require(action == ExternalAction.SHARE && targets.all { it.kind == "IMAGE" }) { localized(R.string.text_converted_copies_require_static_images_a4ba7d, "Le copie convertite richiedono immagini statiche") }
    }
    fun encode(): String = JSONObject().put("action", action.name).put("targets", JSONArray().apply {
        targets.forEach { put(JSONObject().put("id", it.id).put("key", it.key).put("uri", it.uri).put("name", it.name)
            .put("mime", it.mime).put("kind", it.kind).put("size", it.size).put("modified", it.modified)) }
    }).apply { copy?.let { put("copy", JSONObject(it.encode())) } }.toString().also { require(it.toByteArray(Charsets.UTF_8).size <= 256 * 1024) { localized(R.string.text_selection_too_large_choose_fewer_files_c3c718, "Selezione troppo grande: riduci i file scelti") } }
    companion object {
        fun decode(value: String): ExternalRequest {
            require(value.toByteArray(Charsets.UTF_8).size <= 256 * 1024)
            val root = JSONObject(value); val files = root.getJSONArray("targets")
            require(files.length() in 1..128)
            return ExternalRequest(ExternalAction.valueOf(root.getString("action")), (0 until files.length()).map { index ->
                val item = files.getJSONObject(index)
                ExternalTarget(item.getString("id"), item.getString("key"), item.getString("uri"), item.getString("name"),
                    item.getString("mime"), item.getString("kind"), item.getLong("size"), item.getLong("modified"))
            }, if (root.has("copy")) ShareCopyOptions.decode(root.getJSONObject("copy").toString()) else null)
        }
    }
}

object ExternalMedia {
    private val mimePattern = Regex("[a-z0-9!#$&^_.+\\-]+/[a-z0-9!#$&^_.+\\-]+")
    fun mime(target: ExternalTarget): String {
        val declared = target.mime.substringBefore(';').trim().lowercase()
        if (mimePattern.matches(declared) && declared != "application/octet-stream") return declared
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(target.name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
    }
    fun combinedMime(targets: List<ExternalTarget>): String {
        val types = targets.map(::mime).distinct()
        require(types.isNotEmpty())
        if (types.size == 1) return types.single()
        val families = types.map { it.substringBefore('/') }.distinct()
        return if (families.size == 1 && families.single() in setOf("image", "video", "audio")) "${families.single()}/*" else "*/*"
    }
    fun intent(action: ExternalAction, targets: List<ExternalTarget>): Intent {
        ExternalRequest(action, targets)
        require(action != ExternalAction.WALLPAPER) { localized(R.string.text_use_system_crop_tool_30ec32, "Usa il ritaglio del sistema") }
        val uris = targets.map { it.uri.toUri() }
        return Intent(if (action == ExternalAction.OPEN) Intent.ACTION_VIEW else if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            if (action == ExternalAction.OPEN) setDataAndType(uris.single(), mime(targets.single())) else {
                type = combinedMime(targets)
                if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.single()) else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
            clipData = ClipData(localized(R.string.text_chosen_media_25c884, "Media scelti"), targets.map(::mime).distinct().toTypedArray(), ClipData.Item(uris.first())).also { clip ->
                uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            }
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
    }
    fun chooser(intent: Intent, title: String): Intent = Intent.createChooser(intent, title).apply {
        clipData = intent.clipData
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    internal suspend fun readable(catalog: Catalog, request: ExternalRequest): List<ExternalTarget> = withContext(Dispatchers.IO) {
        val documents = FileDocuments(catalog.context)
        for (target in request.targets) {
            currentCoroutineContext().ensureActive()
            val snapshot = catalog.readMediaSnapshot(target.id)
            val current = snapshot.byId[target.id]
            require(current != null && snapshot.usable(current) && target.matches(current)) { localized(R.string.text_the_selection_changed_or_is_no_longer_available_33adcc, "La selezione è cambiata o non è più disponibile. Riapri il menu.") }
            val uri = catalog.uri(current, snapshot)
            val before = documents.stat(uri)
            require(before.name == target.name && !DocumentVersions.changed(target.size, target.modified, before.size, before.modified)) {
                localized(R.string.text_the_file_changed_after_selection_reopen_the_menu_d4632a, "Il file è cambiato dopo la selezione. Riapri il menu.")
            }
            require(before.mime == current.mime) { localized(R.string.text_the_file_type_changed_reopen_the_menu_079ffe, "Il tipo del file è cambiato. Riapri il menu.") }
            documents.input(uri).use { it.read() } // Check readability; never load a video or a complete album into memory.
            val after = documents.stat(uri)
            require(after.name == before.name && !DocumentVersions.changed(before.size, before.modified, after.size, after.modified)) { localized(R.string.text_the_file_changed_during_verification_3909b4, "Il file è cambiato durante la verifica") }
        }
        // Recheck the complete frozen selection after provider IO, including hides/revocations from the other panel.
        val data = catalog.readSnapshot()
        require(request.targets.all { target -> data.byId[target.id]?.let { data.usable(it) && target.matches(it) } == true }) { localized(R.string.text_selection_access_no_longer_available_ee4372, "Accesso alla selezione non più disponibile") }
        request.targets.map { target -> target.copy(uri = catalog.uri(data.byId.getValue(target.id), data).toString()) }
    }
    suspend fun prepare(catalog: Catalog, request: ExternalRequest): Intent = withContext(Dispatchers.IO) {
        require(request.copy == null) { localized(R.string.text_prepare_and_verify_copies_before_sharing_2a431b, "Prepara e verifica le copie prima di condividerle") }
        val readable = readable(catalog, request)
        if (request.action == ExternalAction.WALLPAPER) {
            val uri = readable.single().uri.toUri()
            try { WallpaperManager.getInstance(catalog.context).getCropAndSetWallpaperIntent(uri).apply {
                clipData = ClipData.newRawUri(localized(R.string.text_chosen_wallpaper_ba1aba, "Sfondo scelto"), uri)
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            } } catch (_: IllegalArgumentException) {
                throw IllegalStateException(localized(R.string.text_this_system_does_not_offer_wallpaper_cropping_fo_7f7e1f, "Questo sistema non offre il ritaglio dello sfondo per questa immagine. Puoi aprirla con un'altra app."))
            }
        } else chooser(intent(request.action, readable), request.action.title)
    }
}
