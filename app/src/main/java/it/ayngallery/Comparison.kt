package it.ayngallery

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Offsets are fractions of the scaled, fitted content, never screen pixels. */
data class ViewTransform(val zoom: Float = 1f, val x: Float = 0f, val y: Float = 0f) {
    fun fromPad(action: PadAction): ViewTransform {
        val next = when (action) {
            PadAction.ZOOM_IN -> copy(zoom = zoom * 1.25f)
            PadAction.ZOOM_OUT -> copy(zoom = zoom / 1.25f)
            PadAction.PAN_UP -> if (zoom > 1) copy(y = y + 0.016f) else this
            PadAction.PAN_DOWN -> if (zoom > 1) copy(y = y - 0.016f) else this
            PadAction.PAN_LEFT -> if (zoom > 1) copy(x = x + 0.016f) else this
            PadAction.PAN_RIGHT -> if (zoom > 1) copy(x = x - 0.016f) else this
            else -> this
        }.bounded()
        return if (next.zoom <= 1f) next.copy(x = 0f, y = 0f) else next
    }
    fun bounded() = ViewTransform(zoom.takeIf { it.isFinite() }?.coerceIn(.5f, 8f) ?: 1f,
        x.takeIf { it.isFinite() }?.coerceIn(-0.5f, 0.5f) ?: 0f,
        y.takeIf { it.isFinite() }?.coerceIn(-0.5f, 0.5f) ?: 0f)
}
data class ComparisonPane(val id: String, val mediaId: String? = null, val pinned: Boolean = false,
    val context: List<String> = emptyList(), val view: ViewTransform = ViewTransform())
data class ComparisonState(val panes: List<ComparisonPane> = listOf(ComparisonPane("pane_1"), ComparisonPane("pane_2", pinned = true)),
    val active: String = "pane_1", val linked: Boolean = false, val enabled: Boolean = false) {
    fun target(pane: ComparisonPane, selection: String?) = if (pane.pinned) pane.mediaId else selection
    fun pin(mediaId: String, context: List<String>, replace: String? = null): ComparisonState? {
        val pane = if (replace != null) panes.find { it.id == replace }
            else panes.firstOrNull { it.pinned && it.mediaId == null } ?: panes.firstOrNull { !it.pinned }
        pane ?: return null // Filled pins are never silently discarded.
        return copy(panes = panes.map { if (it.id == pane.id) it.copy(mediaId = mediaId, pinned = true,
            context = context.distinct(), view = ViewTransform()) else it }, active = pane.id, enabled = true)
    }
    fun transform(id: String, value: ViewTransform) = copy(panes = panes.map {
        if (it.id == id || linked) it.copy(view = value.bounded()) else it
    })
    fun resize(count: Int, discard: Boolean = false): ComparisonState? {
        require(count == 2 || count == 4)
        if (!discard && panes.drop(count).any { it.mediaId != null && it.pinned }) return null
        val next = (0 until count).map { index -> panes.getOrNull(index) ?: ComparisonPane("pane_${index + 1}", pinned = true) }
        return copy(panes = next, active = active.takeIf { id -> next.any { it.id == id } } ?: next.first().id)
    }
    fun navigate(selection: String?, globalContext: List<String>, delta: Int): ComparisonState {
        val pane = panes.firstOrNull { it.id == active } ?: return this
        val context = if (pane.pinned) pane.context else globalContext
        if (context.isEmpty()) return this
        val index = context.indexOf(target(pane, selection)).coerceAtLeast(0)
        val id = context[Math.floorMod(index + delta, context.size)]
        // An explicit previous/next command replaces this pane; library focus never does.
        return copy(panes = panes.map { if (it.id == pane.id) it.copy(mediaId = id, pinned = true,
            context = context, view = ViewTransform()) else it })
    }
}

/** Current view stays local; named sessions share Room's atomic metadata backup. */
class ComparisonStore(context: Context, private val scope: CoroutineScope, private val catalog: Catalog) {
    private val prefs = context.getSharedPreferences("comparison_v1", Context.MODE_PRIVATE)
    val state = MutableStateFlow(decode(prefs.getString("current", null)) ?: ComparisonState())
    val names = MutableStateFlow<List<String>>(emptyList())
    init { scope.launch { catalog.dao.observeComparisonSessions().collect { names.value = it.map { session -> session.name } } } }
    private var write: Job? = null
    fun update(value: ComparisonState) {
        val previous = state.value
        if (previous == value) return
        state.value = value
        write?.cancel()
        write = scope.launch(Dispatchers.IO) {
            val onlyViewChanged = previous.copy(panes = previous.panes.map { it.copy(view = ViewTransform()) }) ==
                value.copy(panes = value.panes.map { it.copy(view = ViewTransform()) })
            if (onlyViewChanged) delay(150)
            val json = encode(value).toString()
            currentCoroutineContext().ensureActive()
            withContext(Dispatchers.Main.immediate) {
                if (state.value == value) prefs.edit().putString("current", json).apply()
            }
        }
    }
    suspend fun save(name: String) = withContext(Dispatchers.IO) { catalog.writer.withLock {
        val title = name.trim()
        require(title.isNotEmpty() && title.length <= 100) { localized(R.string.text_choose_a_name_of_1100_characters_20d6b6, "Scegli un nome tra 1 e 100 caratteri") }
        require(title.none(Char::isISOControl)) { localized(R.string.text_invalid_name_2e3d77, "Nome non valido") }
        require(catalog.dao.comparisonSession(title) == null) { localized(R.string.text_a_session_with_this_name_already_exists_5ae07c, "Una sessione con questo nome esiste già") }
        catalog.rememberLocked(state.value.panes.mapNotNull { it.mediaId })
        catalog.dao.saveComparisonSessions(listOf(ComparisonSessionEntity(UUID.randomUUID().toString(), title, encode(state.value).toString())))
        names.value = catalog.dao.comparisonSessions().map { it.name }
    } }
    suspend fun load(name: String): Boolean {
        val value = withContext(Dispatchers.IO) { decode(catalog.dao.comparisonSession(name)?.state) } ?: return false
        update(value.copy(enabled = true)); return true
    }
    suspend fun delete(name: String) = withContext(Dispatchers.IO) { catalog.writer.withLock {
        catalog.dao.deleteComparisonSession(name)
        names.value = catalog.dao.comparisonSessions().map { it.name }
    } }
    companion object {
        fun encode(state: ComparisonState) = JSONObject().put("version", 1).put("active", state.active).put("linked", state.linked).put("enabled", state.enabled)
            .put("panes", JSONArray(state.panes.map { pane -> JSONObject().put("id", pane.id).put("media", pane.mediaId ?: JSONObject.NULL)
                .put("pinned", pane.pinned).put("context", JSONArray(pane.context))
                .put("zoom", pane.view.zoom.toDouble()).put("x", pane.view.x.toDouble()).put("y", pane.view.y.toDouble()) }))
        fun decode(raw: String?): ComparisonState? = try {
            if (raw == null) null else {
                val json = JSONObject(raw)
                require(json.getInt("version") == 1)
                val array = json.getJSONArray("panes")
                require(array.length() == 2 || array.length() == 4)
                val panes = (0 until array.length()).map { i ->
                    val pane = array.getJSONObject(i)
                    val context = pane.optJSONArray("context") ?: JSONArray()
                    require(context.length() <= 100_000)
                    ComparisonPane(pane.getString("id"), pane.optString("media").takeIf { it.isNotEmpty() && it != "null" },
                        pane.optBoolean("pinned"), (0 until context.length()).map { context.getString(it) }.distinct(),
                        ViewTransform(pane.optDouble("zoom", 1.0).toFloat(), pane.optDouble("x", 0.0).toFloat(), pane.optDouble("y", 0.0).toFloat()).bounded())
                }
                require(panes.map { it.id }.distinct().size == panes.size)
                ComparisonState(panes, json.optString("active").takeIf { id -> panes.any { it.id == id } } ?: panes.first().id, json.optBoolean("linked"), json.optBoolean("enabled"))
            }
        } catch (_: Exception) { null }
    }
}
