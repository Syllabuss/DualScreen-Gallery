package it.ayngallery

import java.util.Locale

/** One filter for type or image format; search, visibility and album scope still apply. */
object MediaFilters {
    val choices get() = listOf("ALL" to localized(R.string.text_all_formats_5f54e8, "Tutti i formati"), "IMAGE" to localized(R.string.text_images_a9f46a, "Immagini"), "PNG" to "PNG", "JPEG" to "JPG / JPEG",
        "GIF" to "GIF", "WEBP" to "WebP", "OTHER_IMAGE" to localized(R.string.text_other_images_af27b2, "Altre immagini"), "VIDEO" to "Video", "AUDIO" to localized(R.string.text_music_012df9, "Musica"))
    fun valid(key: String) = choices.any { it.first == key } || (key.contains("+") && key.split("+").let { parts -> parts.distinct().size == parts.size && parts.all { it in setOf("IMAGE", "GIF", "VIDEO", "AUDIO") } })
    fun label(key: String): String = if (key.contains("+")) key.split("+").joinToString(" · ") { label(it) } else choices.firstOrNull { it.first == key }?.second ?: localized(R.string.text_all_formats_5f54e8, "Tutti i formati")
    fun choices(photoOnly: Boolean) = choices.filter { !photoOnly || it.first !in setOf("VIDEO", "AUDIO") }
    private fun imageFormat(item: MediaEntity): String {
        val mime = item.mime.substringBefore(';').trim().lowercase(Locale.ROOT)
        return when (mime) {
            "image/png", "image/x-png" -> "PNG"
            "image/jpeg", "image/jpg", "image/pjpeg" -> "JPEG"
            "image/gif" -> "GIF"
            "image/webp" -> "WEBP"
            "", "application/octet-stream" -> when (item.name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
                "png" -> "PNG"; "jpg", "jpeg", "jpe" -> "JPEG"; "gif" -> "GIF"; "webp" -> "WEBP"; else -> "OTHER_IMAGE"
            }
            else -> "OTHER_IMAGE"
        }
    }
    fun matches(key: String, item: MediaEntity): Boolean = if (key.contains("+")) key.split("+").any { matches(it, item) } else when (key) {
        "ALL" -> true
        "IMAGE", "GIF", "VIDEO", "AUDIO" -> item.kind == key
        "PNG", "JPEG", "WEBP", "OTHER_IMAGE" -> item.kind == "IMAGE" && imageFormat(item) == key
        else -> false
    }
}
