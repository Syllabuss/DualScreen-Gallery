package it.ayngallery

/** Move a stable, ordered selection as one block; never duplicate or discard an album. */
internal fun moveAlbumBlock(order: List<String>, selected: Set<String>, target: String,
    after: Boolean = order.indexOf(target) >= order.indexOfFirst { it in selected }): List<String> {
    if (target in selected || target !in order) return order
    val moving = order.filter { it in selected }
    if (moving.isEmpty()) return order
    val rest = order.filterNot { it in selected }.toMutableList()
    rest.addAll(rest.indexOf(target) + if (after) 1 else 0, moving)
    return rest
}
