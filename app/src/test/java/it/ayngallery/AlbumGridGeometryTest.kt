package it.ayngallery

import org.junit.Test
import org.junit.Assert.*

class AlbumGridGeometryTest {
    @Test fun removingTheSelectedAlbumPrefersItsNextSurvivorThenItsPreviousOne() {
        val before = listOf("a", "b", "c", "d")
        assertEquals("c", albumAnchorAfterRemoval(before, listOf("a", "c", "d"), "b"))
        assertEquals("d", albumAnchorAfterRemoval(before, listOf("a", "d"), "b"))
        assertEquals("b", albumAnchorAfterRemoval(before, listOf("a", "b"), "d"))
        assertEquals("b", albumAnchorAfterRemoval(before, before, "b"))
        assertNull(albumAnchorAfterRemoval(before, emptyList(), "c"))
    }

    @Test fun pixelGeometryMatchesMixedTilesAndPartiallyFilledBands() {
        val packed = albumGridLayout(albumGridCells(listOf(4, 2, 2, 2), 3), 100f, 10f)
        assertEquals(listOf(0f, 220f), packed.bandOffsets)
        assertEquals(androidx.compose.ui.geometry.Rect(0f, 0f, 210f, 210f), packed.bounds[0])
        assertEquals(androidx.compose.ui.geometry.Rect(220f, 110f, 320f, 210f), packed.bounds[2])
        val partial = albumGridLayout(listOf(AlbumGridCell(0, 0, 2), AlbumGridCell(2, 0, 4)), 100f, 10f)
        assertEquals(listOf(0f, 220f), partial.bandOffsets)
        assertEquals(220f, partial.bounds[1].top, 0f)
        assertTrue(albumGridLayout(emptyList(), 100f, 10f).bounds.isEmpty())
    }
    @Test fun cornerSwapPinsTheLargeTileAndReturnsTheCoveredTileToTheOrigin() {
        val cells = albumGridCells(listOf(4, 2, 2, 2), 4)
        val swapped = swapAlbumGridCells(cells, 0, 3, 4)
        assertEquals(AlbumGridCell(1, 4, 4), swapped[0])
        assertEquals(AlbumGridCell(0, 0, 2), swapped[3])
        assertEquals(cells[1], swapped[1]); assertEquals(cells[2], swapped[2])
        assertEquals(swapped, albumGridCells(listOf(4, 2, 2, 2), 4, swapped))
        val layout = albumGridLayout(swapped, 100f, 10f)
        assertEquals(listOf(3), layout.bandRows)
        assertEquals(110f, layout.bounds[0].top, 0f)
    }
    @Test fun cornerSwapsKeepAllSizesInsideTheGridWithoutOverlaps() {
        for (columns in 2..8) {
            val spans = List(30) { if (it % 3 == 0) 4 else 2 }
            val cells = albumGridCells(spans, columns)
            for (source in listOf(0, 3, 12, 27)) for (target in cells.indices) {
                val swapped = swapAlbumGridCells(cells, source, target, columns)
                assertEquals(swapped, albumGridCells(spans, columns, swapped))
                assertEquals(cells.map { it.span }, swapped.map { it.span })
                if (source != target) {
                    assertEquals(cells[target].row, swapped[source].row)
                    assertEquals(minOf(cells[target].start / 2, columns - 2) * 2, swapped[source].start)
                }
            }
        }
        val invalid = listOf(AlbumGridCell(0, 0, 4), AlbumGridCell(0, 0, 2))
        assertNotEquals(invalid, albumGridCells(listOf(4, 2), 4, invalid))
    }
    @Test fun packingPreservesVisibleOrderWithoutOverlapsAtEveryZoom() {
        val random = kotlin.random.Random(54)
        for (columns in 2..8) {
            val spans = List(180) { if (random.nextBoolean()) 4 else 2 }
            val cells = albumGridCells(spans, columns)
            val occupied = mutableSetOf<Pair<Int, Int>>()
            var previous = -1
            cells.forEach { cell ->
                val index = cell.row * columns + cell.start / 2
                assertTrue("Later albums cannot backfill earlier holes", index > previous)
                previous = index
                assertTrue(cell.start / 2 + cell.rows <= columns)
                for (y in cell.row until cell.row + cell.rows) for (x in cell.start / 2 until cell.start / 2 + cell.rows)
                    assertTrue("No two albums occupy the same slot", occupied.add(y to x))
            }
            val packed = albumGridLayout(cells, 100f, 8f)
            cells.indices.forEach { i -> assertTrue(i in packed.bands[packed.cellBands[i]]) }
        }
        assertEquals(10000, albumGridCells(List(10000) { if (it % 3 == 0) 4 else 2 }, 3).size)
    }
    @Test fun standardColumnsKeepTheSameVerticalTrackAtEveryZoom() {
        for (columns in 2..8) {
            val cells = albumGridCells(List(columns * 4) { 2 }, columns)
            for (index in 0 until columns * 3) {
                val down = albumGridTarget(cells, index, PadAction.DOWN, cells[index].center)
                assertEquals(index + columns, down)
                assertEquals(index, albumGridTarget(cells, down, PadAction.UP, cells[index].center))
            }
        }
    }
    @Test fun wideTilesUsePackedRowsRatherThanAnIndexDividedByColumns() {
        val cells = albumGridCells(listOf(4, 2, 2, 2), 3)
        assertEquals(listOf(0, 0, 1, 2), cells.map { it.row })
        assertEquals(3, albumGridTarget(cells, 0, PadAction.DOWN, cells[0].center))
        assertEquals(2, albumGridTarget(cells, 1, PadAction.DOWN, cells[1].center))
        assertEquals(1, albumGridTarget(cells, 2, PadAction.UP, cells[1].center))
        assertEquals(0, albumGridTarget(cells, 2, PadAction.LEFT, cells[2].center))
        assertEquals(1, albumGridTarget(cells, 0, PadAction.RIGHT, cells[0].center))
        assertEquals(1, albumGridTarget(cells, 1, PadAction.RIGHT, cells[1].center))
    }
}
