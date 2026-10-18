package it.ayngallery

/** Per-panel estimate for rendering buffers, in addition to the user's GIF count limit. */
internal fun animatedGifIds(candidates: List<Pair<String, Long>>, requested: Int, heap: Long = Runtime.getRuntime().maxMemory()): Set<String> {
    val budget = minOf(32L * 1024 * 1024, (heap / 16).coerceAtLeast(2L * 1024 * 1024))
    val count = if (requested == 0) 40 else requested.coerceIn(1, 40)
    var used = 0L
    return buildSet {
        for ((id, pixels) in candidates) {
            val cost = pixels.coerceIn(1, Long.MAX_VALUE / 24) * 24
            if (size < count && cost <= budget - used && add(id)) used += cost
        }
    }
}
