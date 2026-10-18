package it.ayngallery

import androidx.compose.ui.unit.IntOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class GridPreviewDirectionTest {
    @Test fun gridMovesFollowRowsAndColumnsWithoutAnimatingInitialFocus() {
        assertEquals(IntOffset(1, 0), gridPreviewDirection(0, 1, 3))
        assertEquals(IntOffset(-1, 0), gridPreviewDirection(2, 1, 3))
        assertEquals(IntOffset(0, 1), gridPreviewDirection(1, 4, 3))
        assertEquals(IntOffset(0, -1), gridPreviewDirection(4, 1, 3))
        assertEquals(IntOffset.Zero, gridPreviewDirection(-1, 0, 3))
        assertEquals(IntOffset.Zero, gridPreviewDirection(1, 1, 3))
    }
}
