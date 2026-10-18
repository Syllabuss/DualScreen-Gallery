package it.ayngallery

import androidx.compose.ui.unit.IntOffset

/** Row changes take precedence, including a short final row. */
internal fun gridPreviewDirection(from: Int, to: Int, columns: Int): IntOffset {
    if (from < 0 || to < 0 || from == to || columns < 1) return IntOffset.Zero
    val row = (to / columns).compareTo(from / columns)
    return if (row != 0) IntOffset(0, row) else IntOffset(to.compareTo(from), 0)
}
