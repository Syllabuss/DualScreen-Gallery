package it.ayngallery

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.core.net.toUri
import org.json.JSONObject

/** Untrusted entry contains locators only. Inspection and all retention require a user-visible choice. */
data class ReceivedMedia(val selection: IncomingSelection, val canPersist: Boolean = false) {
    fun encode() = JSONObject().put("v", 1).put("selection", JSONObject(selection.encode())).put("persist", canPersist).toString()
    fun put(intent: Intent) {
        intent.putExtra("received_media", encode())
        intent.clipData = ClipData.newRawUri(localized(R.string.text_chosen_media_25c884, "Media scelti"), selection.uris.first().toUri()).apply {
            selection.uris.drop(1).forEach { addItem(ClipData.Item(it.toUri())) }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (canPersist) intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    }
    companion object {
        fun decode(raw: String): ReceivedMedia {
            require(raw.toByteArray(Charsets.UTF_8).size <= 260 * 1024)
            val root = JSONObject(raw); require(root.getInt("v") == 1)
            return ReceivedMedia(IncomingSelection.decode(root.getJSONObject("selection").toString()), root.getBoolean("persist"))
        }
        @Suppress("DEPRECATION") fun from(intent: Intent): ReceivedMedia {
            require(intent.action in setOf(Intent.ACTION_VIEW, Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE, Intent.ACTION_OPEN_DOCUMENT)) { localized(R.string.text_unsupported_opening_ca41a8, "Apertura non supportata") }
            val values = linkedSetOf<String>()
            if (intent.action in setOf(Intent.ACTION_VIEW, Intent.ACTION_OPEN_DOCUMENT)) intent.data?.let { values += it.toString() }
            if (intent.action == Intent.ACTION_SEND) intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { values += it.toString() }
            if (intent.action == Intent.ACTION_SEND_MULTIPLE) intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { streams ->
                require(streams.size <= 128); streams.forEach { values += it.toString() }
            }
            intent.clipData?.let { clip -> require(clip.itemCount <= 128); for (i in 0 until clip.itemCount) values += requireNotNull(clip.getItemAt(i).uri).toString() }
            val selection = IncomingSelection(java.util.UUID.randomUUID().toString(), values.toList())
            selection.encode() // Bound the complete saved/forwarded payload before launching a task.
            return ReceivedMedia(selection, intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0 &&
                intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0)
        }
    }
}

/** Exported bridge opens one existing safe display; it never restores the remembered display pair. */
class ReceiveMediaActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val request = ReceivedMedia.from(intent)
            request.selection.uris.forEach { require(it.toUri().authority != packageName && !it.toUri().authority!!.startsWith("$packageName.")) }
            val session = (application as GalleryApp).session
            session.refreshDisplays()
            val here = session.current(this) ?: error(localized(R.string.text_display_unavailable_8b459c, "Display non disponibile"))
            val key = DisplayPolicy.ordinaryTarget(here.key, session.screens.map { it.key }.toSet(), session.state)
            val target = session.screens.find { it.key == key } ?: error(localized(R.string.text_no_display_available_explicitly_open_the_gallery_6ef978, "Nessun display disponibile: apri esplicitamente la galleria su uno schermo libero"))
            val next = session.state.copy(anchor = target.key, anchorSlot = if (session.state.anchor == target.key) session.state.anchorSlot else 0)
            if (session.save(next)) session.launch(this, target, next.anchorSlot, received = request)
        } catch (error: Exception) {
            uiToast(this, error.message ?: localized(R.string.text_content_cannot_be_opened_048ecf, "Contenuto non apribile"), Toast.LENGTH_LONG).show()
        }
        finish()
    }
}

/** Stable source namespace distinguishes exact read-only document grants from directory sources. */
val SourceEntity.singleDocument get() = id.startsWith("document:")
