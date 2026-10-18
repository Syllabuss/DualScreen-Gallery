package it.ayngallery

internal data class ExplorerTree(val entry: ExplorerEntry, val children: List<ExplorerTree> = emptyList())

/** Freeze the bounded batch before creating anything; reject cycles, aliases and oversized trees. */
internal suspend fun explorerTree(entries: List<ExplorerEntry>, list: suspend (ExplorerLocation) -> List<ExplorerEntry>): List<ExplorerTree> {
    val seen = hashSetOf<Pair<String, String>>()
    var count = 0
    suspend fun freeze(entry: ExplorerEntry, depth: Int): ExplorerTree {
        require(depth <= 32 && ++count <= 10_000)
        FileNames.validateBase(entry.name)
        require(seen.add(entry.location.providerId to entry.id))
        val children = if (!entry.directory) emptyList() else list(entry.location.copy(directoryId = entry.id)).map { freeze(it, depth + 1) }
        return ExplorerTree(entry, children)
    }
    return entries.map { freeze(it, 0) }
}

internal fun explorerTreeJson(trees: List<ExplorerTree>): org.json.JSONArray = org.json.JSONArray().also { array ->
    trees.forEach { tree ->
        val e = tree.entry
        array.put(org.json.JSONObject().put("provider", e.location.providerId).put("parent", e.location.directoryId)
            .put("id", e.id).put("name", e.name).put("directory", e.directory).put("size", e.size ?: org.json.JSONObject.NULL)
            .put("modified", e.modified ?: org.json.JSONObject.NULL).put("readable", e.readable).put("writable", e.writable)
            .put("children", explorerTreeJson(tree.children)))
    }
}
internal fun explorerTreeFromJson(array: org.json.JSONArray): List<ExplorerTree> {
    var count = 0
    val seen = mutableSetOf<Pair<String, String>>()
    fun read(items: org.json.JSONArray, depth: Int): List<ExplorerTree> {
        if (items.length() == 0) return emptyList()
        require(depth <= 32)
        return (0 until items.length()).map { index ->
            require(++count <= 10_000)
            val j = items.getJSONObject(index)
            val e = ExplorerEntry(ExplorerLocation(j.getString("provider"), j.getString("parent")), j.getString("id"), j.getString("name"),
                j.getBoolean("directory"), if (j.isNull("size")) null else j.getLong("size"), if (j.isNull("modified")) null else j.getLong("modified"), j.getBoolean("readable"), j.getBoolean("writable"))
            FileNames.validateBase(e.name); require(seen.add(e.location.providerId to e.id))
            require(e.size == null || e.size >= 0)
            val children = read(j.getJSONArray("children"), depth + 1)
            require(e.directory || children.isEmpty())
            ExplorerTree(e, children)
        }
    }
    return read(array, 0)
}
