package it.ayngallery

import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URI
import java.nio.charset.CodingErrorAction

/** Catalog metadata only: parsing never opens a stream or starts playback. */
internal data class TvCatalogEntry(
    val streamUrl: String,
    val name: String,
    val channelId: String?,
    val logoUrl: String?,
    val group: String?
) {
    val key: String = tvDigest(channelId?.takeIf { it.isNotBlank() }?.let { "iptv:$it" } ?: streamUrl)
}

internal fun tvDigest(value: String): String = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).let { bytes ->
    val digits = "0123456789abcdef"
    buildString(64) { bytes.forEach { byte -> val n = byte.toInt() and 255; append(digits[n ushr 4]); append(digits[n and 15]) } }
}

internal object TvPlaylist {
    const val MAX_BYTES = 5 * 1024 * 1024
    const val MAX_LINE = 8192
    const val MAX_CHANNELS = 20_000
    private val attribute = Regex("""([\w-]+)="((?:\\.|[^"\\])*)"""")

    /** Caller owns [input]. Network policy must still validate DNS and every actual request. */
    fun parse(input: InputStream): List<TvCatalogEntry> {
        val limited = object : FilterInputStream(input) {
            var bytes = 0L
            private fun count(n: Int) {
                if (n > 0) bytes += n
                require(bytes <= MAX_BYTES) { "TV playlist exceeds byte limit" }
            }
            override fun read(): Int = `in`.read().also { if (it >= 0) count(1) }
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                `in`.read(b, off, len).also(::count)
        }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val reader = InputStreamReader(limited, decoder).buffered()
        fun line(): String? {
            val text = StringBuilder()
            while (true) {
                val c = reader.read()
                if (c < 0) return if (text.isEmpty()) null else text.toString().trim()
                if (c == '\n'.code) return text.toString().trim()
                require(text.length < MAX_LINE) { "TV playlist line exceeds limit" }
                text.append(c.toChar())
            }
        }
        require(line()?.removePrefix("\uFEFF")?.let { it == "#EXTM3U" || it.startsWith("#EXTM3U ") } == true) {
            "Invalid TV playlist header"
        }
        val entries = LinkedHashMap<String, TvCatalogEntry>()
        var metadata: Pair<Map<String, String>, String>? = null
        var records = 0
        while (true) {
            val value = line() ?: break
            if (value.startsWith("#EXTINF:")) {
                // A comma inside a quoted attribute is not the title separator.
                var quoted = false
                var escaped = false
                var comma = -1
                for (i in value.indices) {
                    val c = value[i]
                    if (!escaped && c == '"') quoted = !quoted
                    if (!quoted && c == ',') { comma = i; break }
                    escaped = !escaped && c == '\\'
                }
                metadata = if (comma < 0 || quoted) null else {
                    val attrs = attribute.findAll(value.substring(0, comma)).associate {
                        it.groupValues[1].lowercase(java.util.Locale.ROOT) to
                            it.groupValues[2].replace("\\\"", "\"").replace("\\\\", "\\")
                    }
                    attrs to value.substring(comma + 1).trim()
                }
            } else if (value.isNotEmpty() && !value.startsWith('#')) {
                val info = metadata
                metadata = null
                if (info == null) continue
                require(++records <= MAX_CHANNELS) { "TV playlist exceeds channel limit" }
                val url = httpsUrl(value) ?: continue
                val (attrs, title) = info
                val name = attrs["tvg-name"]?.takeIf { it.isNotBlank() } ?: title.takeIf { it.isNotBlank() } ?: URI(url).host
                entries.putIfAbsent(url, TvCatalogEntry(url, name, attrs["tvg-id"]?.takeIf { it.isNotBlank() },
                    attrs["tvg-logo"]?.let(::httpsUrl), attrs["group-title"]?.takeIf { it.isNotBlank() }))
            }
        }
        return entries.values.toList()
    }

    private fun httpsUrl(value: String): String? = runCatching {
        require(value.none { it.isWhitespace() || it.isISOControl() })
        val uri = URI(value)
        require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
            uri.rawFragment == null && uri.port in listOf(-1, 443))
        value
    }.getOrNull()
}
