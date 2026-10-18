package it.ayngallery

import android.content.Context
import android.net.Uri
import java.io.File

/** Only an existing per-track file in our private artwork cache can be embedded artwork. */
fun embeddedArtworkUri(context: Context, item: MediaEntity): String? {
    val expected = File(context.cacheDir, "artwork/${item.id}.art")
    val raw = item.artworkUri?.let(Uri::parse) ?: return expected.takeIf { it.isFile }?.toURI()?.toString()
    if (raw.scheme == "content") return expected.takeIf { it.isFile }?.toURI()?.toString()
    if (raw.scheme != "file" || !raw.authority.isNullOrEmpty()) return null
    val file = runCatching { File(java.net.URI(raw.toString())) }.getOrNull() ?: return null
    return raw.toString().takeIf { file.toPath().toAbsolutePath().normalize() == expected.toPath().toAbsolutePath().normalize() && file.isFile }
}

object ArtworkOrders {
    const val DEFAULT = "MANUAL,EMBEDDED,FOLDER"
    val all = listOf(DEFAULT, "MANUAL,FOLDER,EMBEDDED", "EMBEDDED,MANUAL,FOLDER", "EMBEDDED,FOLDER,MANUAL", "FOLDER,MANUAL,EMBEDDED", "FOLDER,EMBEDDED,MANUAL")
    fun valid(value: String?) = value in all
    fun label(value: String) = value.split(',').joinToString(" → ") { when (it) { "MANUAL" -> localized(R.string.text_local_choice_bb1037, "Scelta locale"); "EMBEDDED" -> localized(R.string.text_embedded_b70dbe, "Incorporata"); else -> localized(R.string.text_folder_2e95e4, "Cartella") } }
    fun track(id: String) = "TRACK:$id"
    fun folder(id: String) = "FOLDER:$id"
    fun coverRank(name: String) = if (name.lowercase(java.util.Locale.ROOT) in setOf("cover.jpg", "folder.jpg", "front.jpg", "cover.png", "folder.png")) 0 else 1
}
