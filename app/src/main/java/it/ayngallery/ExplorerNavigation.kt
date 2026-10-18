package it.ayngallery

/** Navigation foundation only; provider credentials and transfer jobs never belong in this state. */
internal enum class ExplorerSide { LOCAL, REMOTE }

internal data class ExplorerLocation(val providerId: String, val directoryId: String)
internal data class ExplorerEntry(val location: ExplorerLocation, val id: String, val name: String,
    val directory: Boolean, val size: Long?, val modified: Long?, val readable: Boolean,
    val writable: Boolean = false)

internal data class ExplorerPaneState(
    val location: ExplorerLocation? = null,
    val back: List<ExplorerLocation> = emptyList(),
    val forward: List<ExplorerLocation> = emptyList(),
    val entries: List<ExplorerEntry> = emptyList(),
    val selected: Map<String, ExplorerEntry> = emptyMap(),
    val focusedId: String? = null,
    val request: Long = 0,
    val loading: Boolean = false,
    val focusHistory: Map<ExplorerLocation, String?> = emptyMap(),
) {
    fun open(next: ExplorerLocation) = copy(location = next,
        back = if (location == null || location == next) back else back + location,
        forward = if (location == next) forward else emptyList(), entries = emptyList(),
        focusedId = if (next == location) focusedId else focusHistory[next],
        focusHistory = if (location == null) focusHistory else focusHistory + (location to focusedId),
        request = request + 1, loading = true)

    fun previous(): ExplorerPaneState {
        val next = back.lastOrNull() ?: return this
        return copy(location = next, back = back.dropLast(1), forward = forward + listOfNotNull(location),
            entries = emptyList(), focusedId = focusHistory[next],
            focusHistory = if (location == null) focusHistory else focusHistory + (location to focusedId), request = request + 1, loading = true)
    }

    fun next(): ExplorerPaneState {
        val next = forward.lastOrNull() ?: return this
        return copy(location = next, forward = forward.dropLast(1), back = back + listOfNotNull(location),
            entries = emptyList(), focusedId = focusHistory[next],
            focusHistory = if (location == null) focusHistory else focusHistory + (location to focusedId), request = request + 1, loading = true)
    }

    fun listed(ticket: Long, at: ExplorerLocation, result: List<ExplorerEntry>): ExplorerPaneState {
        if (ticket != request || at != location) return this
        require(result.all { it.location == at } && result.map { it.id }.distinct().size == result.size)
        return copy(entries = result.toList(), loading = false,
            focusedId = focusedId?.takeIf { id -> result.any { it.id == id } } ?: result.firstOrNull()?.id)
    }

    fun toggle(entry: ExplorerEntry): ExplorerPaneState {
        require(entry in entries)
        val key = "${entry.location.providerId}\u0000${entry.id}"
        return copy(selected = if (key in selected) selected - key else selected + (key to entry))
    }

    fun frozenSelection(): List<ExplorerEntry> = selected.values.toList()
}

internal data class ExplorerNavigation(
    val local: ExplorerPaneState = ExplorerPaneState(),
    val remote: ExplorerPaneState = ExplorerPaneState(),
    val active: ExplorerSide = ExplorerSide.LOCAL,
    val swapped: Boolean = false,
) {
    fun pane(side: ExplorerSide) = if (side == ExplorerSide.LOCAL) local else remote
    fun update(side: ExplorerSide, change: (ExplorerPaneState) -> ExplorerPaneState) =
        if (side == ExplorerSide.LOCAL) copy(local = change(local)) else copy(remote = change(remote))
    fun swap() = copy(swapped = !swapped)
    fun upper() = if (swapped) ExplorerSide.LOCAL else ExplorerSide.REMOTE
    fun lower() = if (swapped) ExplorerSide.REMOTE else ExplorerSide.LOCAL
}
