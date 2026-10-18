package it.ayngallery

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/** Visual selection only. Widgets can share this eligibility policy without sharing an in-app timer. */
data class FrameConfig(val scope: String = "ALL", val albums: Set<String> = emptySet(), val collections: Set<String> = emptySet(),
    val seconds: Int = 5, val shuffled: Boolean = false, val fill: Boolean = false, val fade: Boolean = true,
    val animatedGif: Boolean = false, val keepAwake: Boolean = false, val immersive: Boolean = false, val transition: String = "ROTATE") {
    fun validate(requireSelection: Boolean = true) {
        require(transition in setOf("NONE", "FADE", "LEFT", "RIGHT", "UP", "DOWN", "ROTATE"))
        require(scope in setOf("ALL", "FAVORITE", "SELECTED") && seconds in 2..120)
        require(albums.size + collections.size <= 128 && (albums + collections).all { it.isNotBlank() && it.length <= 512 })
        if (requireSelection) require(scope != "SELECTED" || albums.isNotEmpty() || collections.isNotEmpty()) { localized(R.string.text_choose_at_least_one_album_or_collection_f499bc, "Scegli almeno un album o una raccolta") }
    }
    fun media(data: CatalogSnapshot): List<MediaEntity> {
        val folders = albums + data.ancestry.filter { it.ancestorId in albums }.map { it.albumId }
        val members = data.collectionItems.filter { it.collectionId in collections }.map { it.mediaId }.toSet()
        return data.media.filter { it.kind in setOf("IMAGE", "GIF") && data.usable(it) && when (scope) {
            "SELECTED" -> it.albumId in folders || it.id in members; "FAVORITE" -> it.favorite; else -> true
        } }.sortedWith(compareBy<MediaEntity, String>(MediaNames.natural) { it.name }.thenBy { it.id })
    }
    fun encode(requireSelection: Boolean = true): String {
        validate(requireSelection)
        return JSONObject().put("version", 1).put("scope", scope).put("albums", JSONArray(albums.sorted()))
            .put("collections", JSONArray(collections.sorted())).put("seconds", seconds).put("shuffled", shuffled)
            .put("fill", fill).put("fade", fade).put("animatedGif", animatedGif).put("keepAwake", keepAwake).put("immersive", immersive).put("transition", transition).toString()
    }
    companion object {
        fun decode(text: String, requireSelection: Boolean = true): FrameConfig {
            require(text.length <= 80_000)
            val json = JSONObject(text); require(json.getInt("version") == 1)
            fun ids(key: String) = json.getJSONArray(key).let { list ->
                require(list.length() <= 128); (0 until list.length()).map { list.getString(it) }.toSet()
            }
            return FrameConfig(json.getString("scope"), ids("albums"), ids("collections"), json.getInt("seconds"),
                json.getBoolean("shuffled"), json.getBoolean("fill"), json.getBoolean("fade"), json.getBoolean("animatedGif"),
                json.getBoolean("keepAwake"), json.getBoolean("immersive"), json.optString("transition", if (json.getBoolean("fade")) "FADE" else "NONE")).also { it.validate(requireSelection) }
        }
    }
}

/** No bitmaps, nodes, tasks or player references; random visits use a bag and bounded navigation history. */
data class FrameCursor(val ids: List<String> = emptyList(), val current: String? = null, val shuffled: Boolean = false,
    val bag: List<String> = emptyList(), val previous: List<String> = emptyList(), val forward: List<String> = emptyList()) {
    fun reconcile(eligible: List<String>, randomOrder: Boolean, random: Random = Random.Default): FrameCursor {
        val safe = eligible.distinct()
        if (safe.isEmpty()) return FrameCursor(shuffled = randomOrder)
        val reset = ids.isEmpty() || shuffled != randomOrder
        val allowed = safe.toSet()
        val pending = if (!randomOrder) emptyList() else if (reset) safe.shuffled(random)
            else bag.filter { it in allowed } + safe.filter { it !in ids }
        val selected = current?.takeIf { it in allowed } ?: pending.firstOrNull() ?: safe.first()
        return copy(ids = safe, current = selected, shuffled = randomOrder, bag = pending.filter { it != selected },
            previous = if (reset) emptyList() else previous.filter { it in allowed }, forward = if (reset) emptyList() else forward.filter { it in allowed })
    }
    fun nextId(): String? = if (ids.size < 2) null else forward.firstOrNull()
        ?: if (shuffled) bag.firstOrNull() else ids[(ids.indexOf(current).coerceAtLeast(0) + 1) % ids.size]
    fun move(delta: Int, random: Random = Random.Default): FrameCursor {
        require(delta == -1 || delta == 1)
        if (ids.size < 2 || current == null) return this
        if (delta < 0) {
            val target = previous.lastOrNull() ?: ids[Math.floorMod(ids.indexOf(current) - 1, ids.size)]
            return copy(current = target, previous = previous.dropLast(1), forward = (listOf(current) + forward).take(64))
        }
        forward.firstOrNull()?.let { return copy(current = it, forward = forward.drop(1), previous = (previous + current).takeLast(64)) }
        val candidates = if (!shuffled) emptyList() else bag.ifEmpty {
            ids.shuffled(random).toMutableList().apply { if (first() == current) { val other = this[1]; this[1] = this[0]; this[0] = other } }
        }
        val target = if (shuffled) candidates.first() else nextId()!!
        return copy(current = target, bag = candidates.drop(1), previous = (previous + current).takeLast(64))
    }
}
