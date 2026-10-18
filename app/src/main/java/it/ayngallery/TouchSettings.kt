package it.ayngallery

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.math.abs
import kotlin.math.hypot

enum class TouchAction(private val titleText: () -> String) {
    NONE({ localized(R.string.text_no_action_f43e02, "Nessuna azione") }), TOGGLE_ZOOM({ "Zoom 1× / 2×" }),
    BACK({ localized(R.string.text_back_804268, "Indietro") }), MENU({ localized(R.string.text_media_menu_17ce51, "Menu del media") }), FAVORITE({ localized(R.string.text_toggle_favorite_41f17b, "Preferito sì / no") }), PIN({ localized(R.string.text_pin_compare_ff6bd8, "Fissa / confronta") }),
    MEDIA_PREVIOUS({ localized(R.string.text_previous_media_3e439b, "Media precedente") }), MEDIA_NEXT({ localized(R.string.text_next_media_228a8d, "Media successivo") }),
    TAB_PREVIOUS({ localized(R.string.text_previous_section_079e1f, "Sezione precedente") }), TAB_NEXT({ localized(R.string.text_next_section_9416ef, "Sezione successiva") }),
    TRACK_PREVIOUS({ localized(R.string.text_previous_track_b3f5fe, "Brano precedente") }), TRACK_NEXT({ localized(R.string.text_next_track_b0ff2f, "Brano successivo") }), PLAY_PAUSE({ localized(R.string.text_music_play_pause_b180bc, "Play / pausa musica") }),
    ZOOM_OUT({ "Zoom −" }), ZOOM_IN({ "Zoom +" }), DIM({ localized(R.string.dim_screen, "Oscura questo schermo") }), SWAP({ localized(R.string.text_swap_already_open_panels_87205f, "Scambia pannelli già aperti") });
    val title: String get() = titleText()
}
enum class TouchDirection(private val titleText: () -> String) { LEFT({ localized(R.string.text_leftward_db8d9b, "Verso sinistra") }), RIGHT({ localized(R.string.text_rightward_2260ce, "Verso destra") }), UP({ localized(R.string.text_upward_a9ec70, "Verso l'alto") }), DOWN({ localized(R.string.text_downward_da6a81, "Verso il basso") }), TAP({ localized(R.string.tutorial_three_tap, "Tocco con tre dita") }) ; val title: String get() = titleText() }
internal val touchContexts = listOf(PadContext.LIBRARY, PadContext.VIEWER, PadContext.PLAYER)
internal fun touchActions(context: PadContext, surface: Boolean = false): List<TouchAction> = TouchAction.entries.filter { action ->
    when (action) {
        TouchAction.TOGGLE_ZOOM -> context == PadContext.VIEWER && surface
        TouchAction.ZOOM_IN, TouchAction.ZOOM_OUT -> context == PadContext.VIEWER
        TouchAction.TAB_PREVIOUS, TouchAction.TAB_NEXT -> true
        TouchAction.MEDIA_PREVIOUS, TouchAction.MEDIA_NEXT -> context != PadContext.PLAYER
        else -> true
    } && context in touchContexts
}
data class TouchZone(val id: String, val context: PadContext, val x: Float, val y: Float,
    val width: Float, val height: Float, val action: TouchAction = TouchAction.MENU) {
    fun contains(px: Float, py: Float) = px >= x && py >= y && px < x + width && py < y + height
    fun overlaps(other: TouchZone) = context == other.context &&
        x < other.x + other.width && other.x < x + width && y < other.y + other.height && other.y < y + height
}
data class TouchSettings(val doubleTap: TouchAction = TouchAction.TOGGLE_ZOOM, val longPress: TouchAction = TouchAction.MENU,
    val threeFinger: Map<PadContext, Map<TouchDirection, TouchAction>> = touchContexts.associateWith { TouchDirection.entries.associateWith { when (it) { TouchDirection.UP -> TouchAction.SWAP; TouchDirection.LEFT -> TouchAction.TAB_PREVIOUS; TouchDirection.RIGHT -> TouchAction.TAB_NEXT; TouchDirection.TAP -> TouchAction.DIM; TouchDirection.DOWN -> TouchAction.NONE } } },
    val zones: List<TouchZone> = emptyList()) {
    fun validate() {
        require(doubleTap in touchActions(PadContext.VIEWER, true) && longPress in touchActions(PadContext.VIEWER, true)) { localized(R.string.text_invalid_touch_action_36e363, "Azione touch non valida") }
        require(threeFinger.keys == touchContexts.toSet()) { localized(R.string.text_invalid_touch_contexts_038d88, "Contesti touch non validi") }
        threeFinger.forEach { (context, directions) ->
            require(directions.keys == TouchDirection.entries.toSet() && directions.values.all { it in touchActions(context) }) { localized(R.string.text_invalid_three_finger_gesture_ef0390, "Gesto a tre dita non valido") }
        }
        require(zones.size <= 12 && zones.map { it.id }.distinct().size == zones.size && zones.groupingBy { it.context }.eachCount().values.all { it <= 4 }) { localized(R.string.text_up_to_four_zones_per_context_6f7946, "Al massimo quattro zone per contesto") }
        zones.forEach { zone ->
            require(zone.id.matches(Regex("[a-zA-Z0-9-]{1,64}")) && zone.context in touchContexts && zone.action in touchActions(zone.context) &&
                listOf(zone.x, zone.y, zone.width, zone.height).all(Float::isFinite) && zone.x >= 0f && zone.y >= 0f &&
                zone.width >= .05f && zone.height >= .05f && zone.x + zone.width <= 1.000001f && zone.y + zone.height <= 1.000001f) { localized(R.string.text_touch_zone_outside_the_usable_area_1d5310, "Zona touch fuori dall'area utile") }
        }
        require(zones.indices.none { i -> zones.drop(i + 1).any { zones[i].overlaps(it) } }) { localized(R.string.text_zones_in_the_same_context_cannot_overlap_d8c35b, "Le zone dello stesso contesto non possono sovrapporsi") }
    }
    fun encode(check: Boolean = true): String {
        if (check) validate()
        val directions = JSONObject()
        threeFinger.forEach { (context, actions) -> directions.put(context.name, JSONObject().apply { actions.forEach { (direction, action) -> put(direction.name, action.name) } }) }
        return JSONObject().put("version", 1).put("doubleTap", doubleTap.name).put("longPress", longPress.name).put("threeFinger", directions)
            .put("zones", JSONArray().apply { zones.forEach { zone -> put(JSONObject().put("id", zone.id).put("context", zone.context.name)
                .put("x", zone.x).put("y", zone.y).put("width", zone.width).put("height", zone.height).put("action", zone.action.name)) } }).toString()
    }
    companion object {
        fun decode(value: String, check: Boolean = true): TouchSettings {
            require(value.toByteArray(Charsets.UTF_8).size <= 16 * 1024) { localized(R.string.text_touch_settings_too_large_b4e1f3, "Impostazioni touch troppo grandi") }
            val parser = JSONTokener(value)
            val root = parser.nextValue() as? JSONObject ?: error(localized(R.string.text_invalid_touch_settings_a76146, "Impostazioni touch non valide"))
            require(parser.nextClean() == '\u0000' && root.keys().asSequence().toSet() == setOf("version", "doubleTap", "longPress", "threeFinger", "zones") && root.get("version") == 1)
            fun JSONObject.text(key: String) = get(key) as? String ?: error(localized(R.string.text_invalid_touch_field_cd73e5, "Campo touch non valido"))
            fun JSONObject.number(key: String) = (get(key) as? Number)?.toFloat()?.takeIf(Float::isFinite) ?: error(localized(R.string.text_invalid_touch_coordinates_0cb272, "Coordinate touch non valide"))
            val three = root.getJSONObject("threeFinger")
            require(three.keys().asSequence().toSet() == touchContexts.map { it.name }.toSet())
            val bindings = touchContexts.associateWith { context ->
                val directions = three.getJSONObject(context.name)
                require(directions.keys().asSequence().toSet() in setOf(TouchDirection.entries.map { it.name }.toSet(), TouchDirection.entries.filter { it != TouchDirection.TAP }.map { it.name }.toSet()))
                TouchDirection.entries.associateWith { if (it == TouchDirection.TAP && !directions.has(it.name)) TouchAction.DIM else TouchAction.valueOf(directions.text(it.name)) }
            }
            val rawZones = root.getJSONArray("zones")
            require(rawZones.length() <= 12)
            val zones = (0 until rawZones.length()).map { index -> rawZones.getJSONObject(index).let { zone ->
                require(zone.keys().asSequence().toSet() == setOf("id", "context", "x", "y", "width", "height", "action"))
                TouchZone(zone.text("id"), PadContext.valueOf(zone.text("context")), zone.number("x"), zone.number("y"),
                    zone.number("width"), zone.number("height"), TouchAction.valueOf(zone.text("action")))
            } }
            return TouchSettings(TouchAction.valueOf(root.text("doubleTap")), TouchAction.valueOf(root.text("longPress")), bindings, zones).also { if (check) it.validate() }
        }
    }
}

internal data class TouchPoint(val id: Long, val x: Float, val y: Float, val pressed: Boolean)
internal data class ThreeFingerDecision(val consume: Boolean, val direction: TouchDirection? = null)

/** One pointer sequence in one window. Once a two-finger transform/scroll starts it stays in charge. */
internal class ThreeFingerDrag(private val slop: Float, private val threshold: Float) {
    private val origins = linkedMapOf<Long, TouchPoint>()
    private val positions = mutableMapOf<Long, TouchPoint>()
    private var began: Long? = null
    private var locked = false
    private var claimed = false
    private var cancelled = false
    private var finished = false
    private var moved = false
    fun cancel() { cancelled = true }
    fun update(points: List<TouchPoint>, time: Long): ThreeFingerDecision {
        if (finished) return ThreeFingerDecision(claimed)
        if (began == null) began = time
        points.forEach { point ->
            if (point.pressed && point.id !in origins) origins[point.id] = point
            if (point.id in origins) {
                positions[point.id] = point
                val start = origins.getValue(point.id)
                if (hypot(point.x - start.x, point.y - start.y) > slop) moved = true
            }
        }
        if (origins.size > 3 || time - began!! > 1500) cancelled = true
        if (!claimed) {
            if (points.any { !it.pressed } || positions.any { (id, point) ->
                    val start = origins.getValue(id); hypot(point.x - start.x, point.y - start.y) > slop }) locked = true
            if (origins.size == 3 && points.count { it.pressed } == 3 && !locked && !cancelled && time - began!! <= 350) claimed = true
        }
        if (points.any { it.pressed }) return ThreeFingerDecision(claimed)
        finished = true
        if (!claimed || cancelled) return ThreeFingerDecision(claimed)
        val meanX = origins.map { (id, start) -> positions.getValue(id).x - start.x }.average().toFloat()
        val meanY = origins.map { (id, start) -> positions.getValue(id).y - start.y }.average().toFloat()
        if (maxOf(abs(meanX), abs(meanY)) < threshold) return ThreeFingerDecision(true, TouchDirection.TAP.takeIf { !moved && time - began!! <= 500 })
        val directions = origins.map { (id, start) ->
            val end = positions.getValue(id); val dx = end.x - start.x; val dy = end.y - start.y
            when {
                abs(dx) >= threshold * .5f && abs(dx) >= abs(dy) * 1.5f -> if (dx > 0) TouchDirection.RIGHT else TouchDirection.LEFT
                abs(dy) >= threshold * .5f && abs(dy) >= abs(dx) * 1.5f -> if (dy > 0) TouchDirection.DOWN else TouchDirection.UP
                else -> null
            }
        }
        return ThreeFingerDecision(true, directions.firstOrNull()?.takeIf { direction -> directions.all { it == direction } })
    }
}
