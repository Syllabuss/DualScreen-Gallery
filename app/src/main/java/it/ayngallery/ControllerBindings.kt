package it.ayngallery

import org.json.JSONArray
import org.json.JSONObject

enum class PadContext(private val titleText: () -> String) { LIBRARY({ localized(R.string.text_library_93c372, "Libreria") }), VIEWER({ localized(R.string.text_preview_e9bcae, "Anteprima") }), PLAYER({ localized(R.string.text_music_012df9, "Musica") }), DIALOG({ localized(R.string.text_dialogs_c3422e, "Dialoghi") }), EDITOR({ "Editor" }) ; val title: String get() = titleText() }
enum class PadButton(private val titleText: () -> String) {
    UP({ "D-pad / stick ↑" }), DOWN({ "D-pad / stick ↓" }), LEFT({ "D-pad / stick ←" }), RIGHT({ "D-pad / stick →" }),
    A({ localized(R.string.text_a_received_1f521d, "A ricevuto") }), B({ localized(R.string.text_b_received_d8ce9e, "B ricevuto") }), X({ localized(R.string.text_x_received_1f44c5, "X ricevuto") }), Y({ localized(R.string.text_y_received_7e8297, "Y ricevuto") }), L1({ "L1" }), R1({ "R1" }), L2({ "L2 / trigger" }), R2({ "R2 / trigger" }),
    L3({ "L3" }), R3({ "R3" }), START({ "Start" }), SELECT({ "Select" }),
    RS_UP({ localized(R.string.text_right_stick_5a153b, "Stick destro ↑") }), RS_DOWN({ localized(R.string.text_right_stick_9bc58d, "Stick destro ↓") }), RS_LEFT({ localized(R.string.text_right_stick_9b9fdf, "Stick destro ←") }), RS_RIGHT({ localized(R.string.text_right_stick_aa4367, "Stick destro →") });
    val title: String get() = titleText()
}
enum class PadAction(private val titleText: () -> String, val repeats: Boolean = false) {
    UP({ localized(R.string.text_focus_up_bb1e59, "Focus su") }, true), DOWN({ localized(R.string.text_focus_down_d198fa, "Focus giù") }, true), LEFT({ localized(R.string.text_focus_left_bbc430, "Focus a sinistra") }, true), RIGHT({ localized(R.string.text_focus_right_8b0117, "Focus a destra") }, true),
    CONFIRM({ localized(R.string.text_confirm_662c0e, "Conferma") }), BACK({ localized(R.string.text_back_804268, "Indietro") }), MEDIA_PREVIOUS({ localized(R.string.text_previous_media_3e439b, "Media precedente") }), MEDIA_NEXT({ localized(R.string.text_next_media_228a8d, "Media successivo") }),
    TRACK_PREVIOUS({ localized(R.string.text_previous_track_b3f5fe, "Brano precedente") }), TRACK_NEXT({ localized(R.string.text_next_track_b0ff2f, "Brano successivo") }), PLAY_PAUSE({ localized(R.string.text_music_play_pause_b180bc, "Play / pausa musica") }),
    SEEK_BACK({ localized(R.string.text_music_10_seconds_c20111, "Musica −10 secondi") }, true), SEEK_FORWARD({ localized(R.string.text_music_10_seconds_453447, "Musica +10 secondi") }, true), FAVORITE({ localized(R.string.text_toggle_favorite_41f17b, "Preferito sì / no") }),
    SETTINGS({ localized(R.string.text_settings_7b855c, "Impostazioni") }), PIN({ localized(R.string.text_pin_compare_ff6bd8, "Fissa / confronta") }), MENU({ localized(R.string.text_media_menu_17ce51, "Menu del media") }), DISPLAY({ localized(R.string.text_screens_menu_37fb30, "Menu schermi") }), SWAP({ localized(R.string.text_swap_panels_a7b7be, "Scambia pannelli") }), FRAME({ localized(R.string.text_photo_frame_mode_be123c, "Modalità cornice") }), DELETE({ localized(R.string.text_move_to_trash_confirm_98b863, "Sposta nel cestino · conferma") }),
    TAB_PREVIOUS({ localized(R.string.text_previous_section_079e1f, "Sezione precedente") }), TAB_NEXT({ localized(R.string.text_next_section_9416ef, "Sezione successiva") }), ZOOM_OUT({ "Zoom −" }, true), ZOOM_IN({ "Zoom +" }, true),
    TOGGLE_VIEW({ localized(R.string.tools_view, "Pulsante lista / griglia") }),
    GRID_SMALLER({ localized(R.string.text_thumbnails_c2d7a9, "Miniature −") }, true), GRID_LARGER({ localized(R.string.text_thumbnails_9a085b, "Miniature +") }, true),
    PAN_UP({ localized(R.string.text_pan_up_919906, "Pan su") }, true), PAN_DOWN({ localized(R.string.text_pan_down_458c10, "Pan giù") }, true), PAN_LEFT({ localized(R.string.text_pan_left_78d13c, "Pan a sinistra") }, true), PAN_RIGHT({ localized(R.string.text_pan_right_9990b6, "Pan a destra") }, true);
    val title: String get() = titleText()
}
data class PadBinding(val buttons: Set<PadButton>, val label: String = "") {
    fun title() = label.ifBlank { buttons.sortedBy { it.ordinal }.joinToString(" + ") { it.title } }
}
data class PadProfile(val bindings: Map<PadContext, Map<PadAction, PadBinding>> = padDefaults()) {
    fun conflicts(context: PadContext, action: PadAction, binding: PadBinding) = bindings[context].orEmpty()
        .filter { it.key != action && (it.value.buttons == binding.buttons ||
            (it.value.buttons.size > 1 && binding.buttons.size > 1 &&
                (it.value.buttons.containsAll(binding.buttons) || binding.buttons.containsAll(it.value.buttons)))) }.keys
    fun assign(context: PadContext, action: PadAction, binding: PadBinding?, replace: Boolean = false): PadProfile {
        require(action in padActions(context))
        if (binding != null) validateBinding(binding)
        val current = bindings[context].orEmpty().toMutableMap()
        val conflicts = binding?.let { conflicts(context, action, it) }.orEmpty()
        require(conflicts.isEmpty() || replace) { localized(R.string.text_binding_already_used_by_1_s_74e2c7, "Associazione già usata da %1\$s", conflicts.joinToString { it.title }) }
        conflicts.forEach(current::remove)
        if (binding == null) current.remove(action) else current[action] = binding
        return copy(bindings = bindings + (context to current.toMap()))
    }
}
data class PadSettings(val profiles: Map<String, PadProfile> = mapOf("default" to PadProfile()),
    val press: Float = 0.60f, val release: Float = 0.35f, val delay: Long = 360, val repeat: Long = 100) {
    fun profile(device: String?) = profiles[device] ?: profiles.getValue("default")
    fun validate() {
        require(profiles.size in 1..17 && "default" in profiles && profiles.keys.all { it.isNotBlank() && it.length <= 512 }) { localized(R.string.text_one_shared_profile_and_up_to_16_personal_profile_aad090, "Sono ammessi il profilo comune e fino a 16 profili personali") }
        require(press.isFinite() && release.isFinite() && release in 0.05f..0.80f && press in 0.20f..0.95f && release < press)
        require(delay in 150..1000 && repeat in 60..400)
        profiles.values.forEach { profile ->
            require(profile.bindings.keys == PadContext.entries.toSet())
            profile.bindings.forEach { (context, bindings) ->
                require(bindings.keys.all { it in padActions(context) })
                bindings.values.forEach(::validateBinding)
                require(bindings.values.map { it.buttons }.distinct().size == bindings.size) { localized(R.string.text_conflicting_bindings_60437b, "Associazioni in conflitto") }
                require(bindings.all { (action, binding) -> profile.conflicts(context, action, binding).isEmpty() })
                require(setOf(PadAction.UP, PadAction.DOWN, PadAction.LEFT, PadAction.RIGHT, PadAction.CONFIRM, PadAction.BACK).all { it in bindings }) {
                    localized(R.string.text_1_s_directions_confirm_and_back_must_remain_assi_d66f86, "%1\$s: direzioni, Conferma e Indietro devono restare assegnati", context.title)
                }
            }
        }
    }
    fun encode(check: Boolean = true): String {
        if (check) validate()
        val json = JSONObject().put("version", 1).put("press", press).put("release", release).put("delay", delay).put("repeat", repeat)
        val all = JSONObject()
        profiles.forEach { (id, profile) ->
            val contexts = JSONObject()
            profile.bindings.forEach { (context, bindings) ->
                val actions = JSONObject()
                bindings.forEach { (action, binding) -> actions.put(action.name,
                    JSONObject().put("buttons", JSONArray(binding.buttons.sortedBy { it.ordinal }.map { it.name })).put("label", binding.label)) }
                contexts.put(context.name, actions)
            }
            all.put(id, contexts)
        }
        return json.put("profiles", all).toString()
    }
    companion object {
        fun decode(value: String, check: Boolean = true): PadSettings {
            require(value.length <= 150_000)
            val json = JSONObject(value)
            require(json.keys().asSequence().toSet() == setOf("version", "press", "release", "delay", "repeat", "profiles"))
            fun integer(key: String): Long {
                val number = json.get(key)
                require(number is Int || number is Long) { localized(R.string.text_invalid_1_s_field_949520, "Campo %1\$s non valido", key) }
                return (number as Number).toLong()
            }
            fun decimal(key: String): Float {
                val number = json.get(key)
                require(number is Number) { localized(R.string.text_invalid_1_s_field_949520, "Campo %1\$s non valido", key) }
                return number.toFloat()
            }
            require(integer("version") == 1L)
            val profiles = json.getJSONObject("profiles")
            return PadSettings(profiles.keys().asSequence().associateWith { id ->
                val contexts = profiles.getJSONObject(id)
                PadProfile(contexts.keys().asSequence().associate { context ->
                    val actions = contexts.getJSONObject(context)
                    PadContext.valueOf(context) to actions.keys().asSequence().associate { action ->
                        val binding = actions.getJSONObject(action); val buttons = binding.getJSONArray("buttons")
                        require(binding.keys().asSequence().toSet() == setOf("buttons", "label") && binding.get("label") is String)
                        val keys = (0 until buttons.length()).map { index ->
                            require(buttons.get(index) is String); PadButton.valueOf(buttons.getString(index))
                        }
                        require(keys.distinct().size == keys.size)
                        PadAction.valueOf(action) to PadBinding(keys.toSet(), binding.getString("label"))
                    }
                }).let { profile ->
                    val defaults = padDefaults().getValue(PadContext.PLAYER)
                    val previousDefaults = defaults - setOf(PadAction.TAB_PREVIOUS, PadAction.TAB_NEXT)
                    if (profile.bindings[PadContext.PLAYER] == previousDefaults)
                        profile.copy(bindings = profile.bindings + (PadContext.PLAYER to defaults))
                    else profile
                }
            }, decimal("press"), decimal("release"), integer("delay"), integer("repeat")).also { if (check) it.validate() }
        }
    }
}
private val padDirections = setOf(PadButton.UP, PadButton.DOWN, PadButton.LEFT, PadButton.RIGHT,
    PadButton.RS_UP, PadButton.RS_DOWN, PadButton.RS_LEFT, PadButton.RS_RIGHT)
private val padPans = setOf(PadAction.PAN_UP, PadAction.PAN_DOWN, PadAction.PAN_LEFT, PadAction.PAN_RIGHT)
private fun validateBinding(binding: PadBinding) {
    require(binding.buttons.size in 1..3 && binding.label.length <= 24 && binding.label.none(Char::isISOControl))
    require(binding.buttons.size == 1 || binding.buttons.none { it in padDirections }) { localized(R.string.text_directions_cannot_be_part_of_combinations_50c20a, "Le direzioni non possono far parte di combinazioni") }
}
fun padActions(context: PadContext): List<PadAction> = PadAction.entries.filter {
    it in setOf(PadAction.UP, PadAction.DOWN, PadAction.LEFT, PadAction.RIGHT, PadAction.CONFIRM, PadAction.BACK) ||
        when (context) {
            PadContext.DIALOG -> false
            PadContext.EDITOR -> it in setOf(PadAction.ZOOM_IN, PadAction.ZOOM_OUT) || it in padPans
            PadContext.VIEWER -> it !in setOf(PadAction.TAB_PREVIOUS, PadAction.TAB_NEXT, PadAction.GRID_SMALLER, PadAction.GRID_LARGER, PadAction.TOGGLE_VIEW)
            PadContext.PLAYER -> it !in setOf(PadAction.MEDIA_PREVIOUS, PadAction.MEDIA_NEXT, PadAction.ZOOM_IN, PadAction.ZOOM_OUT) && it !in padPans
            PadContext.LIBRARY -> it !in setOf(PadAction.ZOOM_IN, PadAction.ZOOM_OUT) && it !in padPans
        }
}
fun padDefaults(): Map<PadContext, Map<PadAction, PadBinding>> = PadContext.entries.associateWith { context ->
    val map = linkedMapOf(PadAction.UP to PadButton.UP, PadAction.DOWN to PadButton.DOWN, PadAction.LEFT to PadButton.LEFT,
        PadAction.RIGHT to PadButton.RIGHT, PadAction.CONFIRM to PadButton.A, PadAction.BACK to PadButton.B)
    if (context in setOf(PadContext.LIBRARY, PadContext.VIEWER, PadContext.PLAYER)) {
        map += mapOf(PadAction.SWAP to PadButton.Y, PadAction.FRAME to PadButton.L3, PadAction.SETTINGS to PadButton.X, PadAction.MENU to PadButton.START, PadAction.DISPLAY to PadButton.SELECT)
        if (context != PadContext.VIEWER) {
            map.remove(PadAction.MENU); map.remove(PadAction.DISPLAY)
            map[PadAction.TOGGLE_VIEW] = PadButton.R3
            map[PadAction.GRID_SMALLER] = PadButton.SELECT; map[PadAction.GRID_LARGER] = PadButton.START
        }
        map[if (context == PadContext.PLAYER) PadAction.TRACK_PREVIOUS else PadAction.MEDIA_PREVIOUS] = PadButton.L2
        map[if (context == PadContext.PLAYER) PadAction.TRACK_NEXT else PadAction.MEDIA_NEXT] = PadButton.R2
        if (context == PadContext.VIEWER) { map[PadAction.ZOOM_OUT] = PadButton.L1; map[PadAction.ZOOM_IN] = PadButton.R1 }
        else { map[PadAction.TAB_PREVIOUS] = PadButton.L1; map[PadAction.TAB_NEXT] = PadButton.R1 }
    }
    if (context in setOf(PadContext.VIEWER, PadContext.EDITOR)) {
        map += mapOf(PadAction.PAN_UP to PadButton.RS_UP, PadAction.PAN_DOWN to PadButton.RS_DOWN,
            PadAction.PAN_LEFT to PadButton.RS_LEFT, PadAction.PAN_RIGHT to PadButton.RS_RIGHT)
    }
    map.mapValues { PadBinding(setOf(it.value)) }
}

/** Per focused window/device. Key and axis aliases are merged; a chord consumes its component actions. */
class PadInput(private val settings: PadSettings, private val profile: PadProfile, private val context: PadContext) {
    private val held = mutableMapOf<PadButton, MutableSet<String>>()
    private val consumed = mutableSetOf<PadButton>()
    private val repeats = mutableMapOf<PadAction, Long>()
    private val bindings get() = profile.bindings[context].orEmpty()
    fun update(button: PadButton, channel: String, pressed: Boolean, now: Long): List<PadAction> {
        val sources = held.getOrPut(button) { mutableSetOf() }
        val previous = sources.isNotEmpty()
        if (pressed) sources.add(channel) else sources.remove(channel)
        if (previous == sources.isNotEmpty()) return emptyList()
        val down = held.filterValues { it.isNotEmpty() }.keys
        if (sources.isNotEmpty()) {
            val chord = bindings.filter { (_, binding) -> binding.buttons.size > 1 && button in binding.buttons &&
                down.containsAll(binding.buttons) && binding.buttons.none { it in consumed } }.maxByOrNull { it.value.buttons.size }
            if (chord != null) { consumed.addAll(chord.value.buttons); return listOf(chord.key) }
            if (button in consumed || bindings.values.any { it.buttons.size > 1 && button in it.buttons }) return emptyList()
            val action = bindings.entries.firstOrNull { it.value.buttons == setOf(button) }?.key ?: return emptyList()
            if (action.repeats) repeats[action] = now + settings.delay
            return listOf(action)
        }
        bindings.filterValues { button in it.buttons }.keys.forEach(repeats::remove)
        val deferred = if (button !in consumed && bindings.values.any { it.buttons.size > 1 && button in it.buttons })
            bindings.entries.firstOrNull { it.value.buttons == setOf(button) }?.key else null
        if (down.isEmpty()) consumed.clear()
        return listOfNotNull(deferred)
    }
    fun axis(button: PadButton, channel: String, value: Float, now: Long): List<PadAction> {
        val active = held[button]?.contains(channel) == true
        return update(button, channel, value.isFinite() && value >= if (active) settings.release else settings.press, now)
    }
    fun poll(now: Long): List<PadAction> = repeats.filterValues { it <= now }.keys.toList().also { due -> due.forEach {
        repeats[it] = now + if (it in padPans) 40 else if (it in setOf(PadAction.SEEK_BACK, PadAction.SEEK_FORWARD)) maxOf(settings.repeat, 400) else settings.repeat
    } }
    fun clear() { held.clear(); consumed.clear(); repeats.clear() }
}

/** Upgrade only the old default pair; user assignments and A/B remain intact. */
fun PadSettings.albumShortcuts(): PadSettings = copy(profiles = profiles.mapValues { (_, profile) ->
    profile.copy(bindings = profile.bindings.mapValues { (context, bindings) ->
        if (context in setOf(PadContext.LIBRARY, PadContext.VIEWER, PadContext.PLAYER) &&
            bindings[PadAction.FAVORITE] == PadBinding(setOf(PadButton.X)) &&
            bindings[PadAction.SWAP] == PadBinding(setOf(PadButton.L3, PadButton.R3)) &&
            bindings.filterKeys { it !in setOf(PadAction.FAVORITE, PadAction.SWAP) }.values.none { PadButton.L3 in it.buttons || PadButton.R3 in it.buttons })
            bindings + mapOf(PadAction.FAVORITE to PadBinding(setOf(PadButton.L3)), PadAction.SWAP to PadBinding(setOf(PadButton.X)),
                PadAction.FRAME to PadBinding(setOf(PadButton.R3)))
        else bindings
    })
})

/** Preserve custom assignments; replace only the previous stock upper-button pair. */
fun PadSettings.gridShortcuts(): PadSettings = copy(profiles = profiles.mapValues { (_, profile) ->
    profile.copy(bindings = profile.bindings.mapValues { (context, bindings) ->
        if (context in setOf(PadContext.LIBRARY, PadContext.PLAYER) &&
            bindings[PadAction.MENU] == PadBinding(setOf(PadButton.START)) &&
            bindings[PadAction.DISPLAY] == PadBinding(setOf(PadButton.SELECT)) &&
            bindings.keys.none { it in setOf(PadAction.GRID_SMALLER, PadAction.GRID_LARGER) } &&
            bindings.filterKeys { it !in setOf(PadAction.MENU, PadAction.DISPLAY) }.values.none {
                PadButton.START in it.buttons || PadButton.SELECT in it.buttons
            }) (bindings - PadAction.MENU - PadAction.DISPLAY) + mapOf(
                PadAction.GRID_SMALLER to PadBinding(setOf(PadButton.SELECT)),
                PadAction.GRID_LARGER to PadBinding(setOf(PadButton.START)))
        else bindings
    })
})

fun PadSettings.settingsShortcut(): PadSettings = copy(profiles = profiles.mapValues { (_, profile) ->
    profile.copy(bindings = profile.bindings.mapValues { (context, bindings) ->
        if (context in setOf(PadContext.LIBRARY, PadContext.VIEWER, PadContext.PLAYER) && bindings[PadAction.PIN] == PadBinding(setOf(PadButton.Y)) && PadAction.SETTINGS !in bindings)
            (bindings - PadAction.PIN) + (PadAction.SETTINGS to PadBinding(setOf(PadButton.Y))) else bindings
    })
})

fun PadSettings.viewShortcut(): PadSettings = copy(profiles = profiles.mapValues { (_, profile) ->
    profile.copy(bindings = profile.bindings.mapValues { (context, bindings) ->
        if (context !in setOf(PadContext.LIBRARY, PadContext.VIEWER, PadContext.PLAYER)) bindings
        else {
            var next = bindings
            val legacyFrame = next[PadAction.FRAME]?.buttons == setOf(PadButton.R3)
            if (legacyFrame) {
                next = next - PadAction.FRAME
                if (next[PadAction.FAVORITE]?.buttons == setOf(PadButton.L3)) next = next - PadAction.FAVORITE
                if (next.values.none { PadButton.L3 in it.buttons }) next = next + (PadAction.FRAME to PadBinding(setOf(PadButton.L3)))
            }
            if (context != PadContext.VIEWER && PadAction.TOGGLE_VIEW !in next && next.values.none { PadButton.R3 in it.buttons })
                next = next + (PadAction.TOGGLE_VIEW to PadBinding(setOf(PadButton.R3)))
            next
        }
    })
})

/** Swap the stock X/Y pair together, preserving deliberate custom assignments. */
fun PadSettings.screenOptionsShortcuts(): PadSettings = copy(profiles = profiles.mapValues { (_, profile) ->
    profile.copy(bindings = profile.bindings.mapValues { (context, bindings) ->
        if (context in setOf(PadContext.LIBRARY, PadContext.VIEWER, PadContext.PLAYER) &&
            bindings[PadAction.SWAP]?.buttons == setOf(PadButton.X) &&
            bindings[PadAction.SETTINGS]?.buttons == setOf(PadButton.Y))
            bindings + mapOf(PadAction.SWAP to PadBinding(setOf(PadButton.Y)), PadAction.SETTINGS to PadBinding(setOf(PadButton.X)))
        else bindings
    })
})
