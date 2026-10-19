package it.ayngallery

data class DisplayState(
    val anchor: String? = null,
    val ceded: Set<String> = emptySet(),
    val swapped: Boolean = false,
    val anchorSlot: Int = 0,
)

/** No lifecycle event in this policy grants permission to open another window. */
object DisplayPolicy {
    fun preferSingleScreenLandscape(screens: List<ScreenInfo>, enabled: Boolean): Boolean = enabled && screens.size == 1

    /** Verified Thor panel sizes, independent of Android display IDs and launch position. */
    fun thorPair(model: String, screens: List<ScreenInfo>): Pair<ScreenInfo, ScreenInfo>? {
        if (!model.replace(" ", "").replace("_", "").equals("AYNThor", true) || screens.size != 2) return null
        fun panel(longest: Int) = screens.singleOrNull { minOf(it.width, it.height) == 1080 && maxOf(it.width, it.height) == longest }
        val lower = panel(1240) ?: return null
        val upper = panel(1920) ?: return null
        return lower to upper
    }

    fun startupOther(current: String, available: Set<String>, remembered: Set<String>, state: DisplayState,
        automatic: Boolean): String? = if (automatic) {
        available.takeIf { it.size == 2 && current in it && state.ceded.isEmpty() }?.firstOrNull { it != current }
    } else rememberedOther(current, available, remembered, state)
    fun rememberedOther(current: String, available: Set<String>, remembered: Set<String>, state: DisplayState): String? =
        remembered.takeIf { it.size == 2 && current in it && available.containsAll(it) && it.none(state.ceded::contains) }
            ?.firstOrNull { it != current }
    /** The card restored by primary-display Recents must own the primary display.
     * Preserve which physical panel browses when exchanging the two Activity slots. */
    fun thorTaskState(lower: String, upper: String, state: DisplayState): DisplayState {
        val previousLowerSlot = when (state.anchor) {
            lower -> state.anchorSlot
            upper -> 1 - state.anchorSlot
            else -> 0
        }
        return state.copy(anchor = lower, anchorSlot = 1,
            swapped = if (previousLowerSlot == 0) !state.swapped else state.swapped)
    }
    fun ordinaryTarget(launchDisplay: String, available: Set<String>, state: DisplayState): String? {
        state.anchor?.let { if (it in available && it !in state.ceded) return it }
        return launchDisplay.takeIf { it in available && it !in state.ceded }
    }

    fun singleHere(current: String, available: Set<String>, state: DisplayState) =
        state.copy(anchor = current, ceded = (state.ceded + available) - current)

    fun expand(current: String, target: String, available: Set<String>, state: DisplayState): DisplayState {
        require(current != target && current in available && target in available)
        return state.copy(anchor = current, ceded = state.ceded - current - target)
    }

    fun cede(current: String, remaining: Set<String>, state: DisplayState) =
        state.copy(anchor = remaining.firstOrNull { it !in state.ceded }, ceded = state.ceded + current)

    fun canSwap(started: Set<String>, allowed: Set<String>, state: DisplayState) =
        started.size == 2 && started.all { it in allowed && it !in state.ceded }
}
