package it.ayngallery

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object InterfaceTools {
    val sections = listOf("PHOTO", "ALBUM", "VIDEO", "MUSIC", "FAVORITE")
    val tools = listOf("ZOOM", "VIEW", "TIMELINE", "SEARCH", "FILTER", "SWAP", "PLAY", "MUSIC_MODE")
    const val SINGLE_NAVBAR = "NAVBAR:SINGLE"
    val defaultHidden: Set<String> = emptySet()
    val validKeys = sections.flatMap { section -> tools.map { "$section:$it" } }.toSet() + SINGLE_NAVBAR
    fun section(tab: String) = if (tab in sections) tab else "PHOTO"
    fun shown(prefs: GalleryPreferences, tab: String, tool: String) = "${section(tab)}:$tool" !in prefs.hiddenTools
    fun decodeTimeline(raw: String): Map<String, String> = JSONObject(raw).let { obj -> obj.keys().asSequence().associateWith { obj.getString(it) } }
}

data class TimelineCell(val media: MediaEntity? = null, val heading: String? = null, val key: String)
object MediaTimeline {
    val modes = setOf("OFF", "DAY", "MONTH", "YEAR")
    fun supported(route: GalleryRoute) = route.tab in setOf("PHOTO", "VIDEO") || route.tab == "ALBUM" && route.album != null
    fun cells(items: List<MediaEntity>, mode: String, locale: Locale, unknown: String, zone: ZoneId = ZoneId.systemDefault(), descending: Boolean = true): List<TimelineCell> {
        if (mode == "OFF") return items.map { TimelineCell(media = it, key = it.id) }
        require(mode in modes)
        val pattern = when (mode) { "DAY" -> "EEEE d MMMM yyyy"; "MONTH" -> "LLLL yyyy"; else -> "yyyy" }
        val formatter = DateTimeFormatter.ofPattern(pattern, locale)
        val order = compareBy<MediaEntity> { it.modified <= 0 }.thenComparator { a, b -> if (descending) b.modified.compareTo(a.modified) else a.modified.compareTo(b.modified) }
        val groups = items.sortedWith(order).groupBy { item ->
            if (item.modified <= 0) "unknown" else Instant.ofEpochMilli(item.modified).atZone(zone).let {
                when (mode) { "DAY" -> it.toLocalDate().toString(); "MONTH" -> "${it.year}-${it.monthValue}"; else -> it.year.toString() }
            }
        }
        return groups.flatMap { (key, media) ->
            val heading = if (key == "unknown") unknown else formatter.format(Instant.ofEpochMilli(media.first().modified).atZone(zone))
            listOf(TimelineCell(heading = heading, key = "date:$key")) + media.map { TimelineCell(media = it, key = it.id) }
        }
    }
}
