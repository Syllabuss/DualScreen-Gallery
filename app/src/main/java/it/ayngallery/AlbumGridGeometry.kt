package it.ayngallery

internal data class AlbumGridCell(val row: Int, val start: Int, val span: Int) {
    val center get() = start * 2 + span
    val rows get() = if (span == 4) 2 else 1
}

internal data class AlbumGridLayout(val bounds: List<androidx.compose.ui.geometry.Rect>, val bandOffsets: List<Float>,
    val bands: List<List<Int>>, val bandStarts: List<Int>, val bandRows: List<Int>, val cellBands: List<Int>)
internal data class AlbumGridPlacement(val columns: Int = 0, val cells: Map<String, AlbumGridCell> = emptyMap(), val order: List<String> = emptyList())

/** Content coordinates shared by hit testing and placement animation, including partially filled bands. */
internal fun albumGridLayout(cells: List<AlbumGridCell>, unit: Float, gap: Float): AlbumGridLayout {
    val lastRow = cells.maxOfOrNull { it.row + it.rows } ?: 0
    val byRow = cells.indices.groupBy { cells[it].row }
    val bands = mutableListOf<List<Int>>(); val starts = mutableListOf<Int>(); val rows = mutableListOf<Int>()
    val cellBands = IntArray(cells.size)
    var row = 0
    while (row < lastRow) {
        val start = row
        var end = minOf(start + 2, lastRow)
        val indices = mutableListOf<Int>()
        // A large tile may now start on either row; never split it between lazy items.
        while (row < end) {
            for (i in byRow[row].orEmpty()) { indices += i; end = maxOf(end, cells[i].row + cells[i].rows); cellBands[i] = bands.size }
            row++
        }
        starts += start; rows += end - start; bands += indices
    }
    return AlbumGridLayout(cells.map { cell ->
        val x = (unit + gap) * (cell.start / 2)
        val top = (unit + gap) * cell.row
        val size = unit * cell.rows + gap * (cell.rows - 1)
        androidx.compose.ui.geometry.Rect(x, top, x + size, top + size)
    }, starts.map { it * (unit + gap) }, bands, starts, rows, cellBands.toList())
}
internal fun albumGridCells(spans: List<Int>, columns: Int, preferred: List<AlbumGridCell>? = null): List<AlbumGridCell> {
    require(columns >= 2)
    if (preferred != null && preferred.size == spans.size) {
        val used = mutableSetOf<Pair<Int, Int>>()
        val valid = preferred.indices.all { i ->
            val c = preferred[i]
            c.span == (if (spans[i] == 4) 4 else 2) && c.row in 0..spans.size * 2 && c.start >= 0 && c.start % 2 == 0 && c.start / 2 + c.rows <= columns &&
                (c.row until c.row + c.rows).all { y -> (c.start / 2 until c.start / 2 + c.rows).all { x -> used.add(y to x) } }
        }
        if (valid) return preferred
    }
    val occupied = mutableSetOf<Pair<Int, Int>>()
    var cursor = 0
    return spans.map { requested ->
        val size = if (requested == 4) 2 else 1
        var row = cursor / columns
        var start = cursor % columns
        while (true) {
            if (start + size > columns) { row++; start = 0 }
            if ((row until row + size).all { y ->
                (start until start + size).all { x -> (y to x) !in occupied }
            }) break
            start++
        }
        for (y in row until row + size) for (x in start until start + size) occupied += y to x
        cursor = row * columns + start + 1
        AlbumGridCell(row, start * 2, size * 2)
    }
}

/** Swap the covered tile into the vacated area and move only tiles covered by the new footprint. */
internal fun swapAlbumGridCells(cells: List<AlbumGridCell>, source: Int, target: Int, columns: Int): List<AlbumGridCell> {
    if (source !in cells.indices || target !in cells.indices || source == target) return cells
    return moveAlbumGridCell(cells, source, cells[target].row, cells[target].start / 2, columns)
}

/** Pin the entire footprint, ignoring its old occupancy, then relocate only intersecting tiles.
 * Each displaced tile is placed once; pinned tiles cannot be displaced again. */
internal fun moveAlbumGridCell(cells: List<AlbumGridCell>, source: Int, row: Int, column: Int, columns: Int): List<AlbumGridCell> {
    if (source !in cells.indices) return cells
    val origin = cells[source]
    val result = cells.toMutableList()
    result[source] = AlbumGridCell(row.coerceAtLeast(0), column.coerceIn(0, columns - origin.rows) * 2, origin.span)
    if (result[source] == origin) return cells
    val occupied = mutableMapOf<Pair<Int, Int>, Int>()
    val pinned = mutableSetOf(source)
    val pending = ArrayDeque<Int>()
    val queued = mutableSetOf<Int>()
    fun footprint(c: AlbumGridCell) = (c.row until c.row + c.rows).flatMap { y ->
        (c.start / 2 until c.start / 2 + c.rows).map { x -> y to x }
    }
    fun blockers(c: AlbumGridCell) = footprint(c).mapNotNull(occupied::get).distinct()
    fun enqueue(i: Int) {
        if (queued.add(i)) {
            footprint(result[i]).forEach { if (occupied[it] == i) occupied.remove(it) }
            pending.addLast(i)
        }
    }
    cells.indices.filter { it != source }.forEach { i -> footprint(cells[i]).forEach { occupied[it] = i } }
    // Large neighbours first, so small tiles do not consume their only available footprint.
    blockers(result[source]).sortedWith(compareByDescending<Int> { cells[it].span }.thenBy { cells[it].row }.thenBy { cells[it].start }).forEach(::enqueue)
    footprint(result[source]).forEach { occupied[it] = source }
    while (pending.isNotEmpty()) {
        val i = pending.removeFirst()
        val size = cells[i].rows
        var destination: AlbumGridCell? = null
        // Reuse the genuinely freed part of the old footprint, including one-cell shifts.
        for (y in origin.row..origin.row + origin.rows - size) for (x in origin.start / 2..origin.start / 2 + origin.rows - size) {
            val candidate = AlbumGridCell(y, x * 2, cells[i].span)
            if (destination == null && blockers(candidate).isEmpty()) destination = candidate
        }
        var distance = 0
        val anchor = cells[i]
        while (destination == null) {
            val candidates = mutableListOf<AlbumGridCell>()
            for (y in maxOf(0, anchor.row - distance)..anchor.row + distance) {
                val dx = distance - kotlin.math.abs(y - anchor.row)
                for (x in listOf(anchor.start / 2 - dx, anchor.start / 2 + dx).distinct()) {
                    if (x in 0..columns - size) {
                        val candidate = AlbumGridCell(y, x * 2, cells[i].span)
                        if (blockers(candidate).none { it in pinned }) candidates += candidate
                    }
                }
            }
            destination = candidates.minWithOrNull(compareBy<AlbumGridCell> { blockers(it).size }.thenBy { it.row }.thenBy { it.start })
            distance++
        }
        blockers(destination).forEach(::enqueue)
        result[i] = destination
        pinned += i
        footprint(destination).forEach { occupied[it] = i }
    }
    return result
}
internal fun albumGridTarget(cells: List<AlbumGridCell>, index: Int, action: PadAction, center: Int): Int {
    val here = cells.getOrNull(index) ?: return -1
    val candidates = cells.indices.filter { it != index }.filter { i ->
        val cell = cells[i]
        when (action) {
            PadAction.UP -> cell.row + cell.rows <= here.row
            PadAction.DOWN -> cell.row >= here.row + here.rows
            PadAction.LEFT -> cell.start + cell.span <= here.start && cell.row < here.row + here.rows && cell.row + cell.rows > here.row
            PadAction.RIGHT -> cell.start >= here.start + here.span && cell.row < here.row + here.rows && cell.row + cell.rows > here.row
            else -> false
        }
    }
    return candidates.minWithOrNull(compareBy<Int> {
        when (action) {
            PadAction.UP -> here.row - cells[it].row - cells[it].rows
            PadAction.DOWN -> cells[it].row - here.row - here.rows
            PadAction.LEFT -> here.start - cells[it].start - cells[it].span
            else -> cells[it].start - here.start - here.span
        }
    }.thenBy { kotlin.math.abs(cells[it].center - center) }) ?: if (action == PadAction.UP && here.row == 0) -1 else index
}
