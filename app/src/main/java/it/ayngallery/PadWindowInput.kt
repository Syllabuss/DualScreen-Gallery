package it.ayngallery

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs

internal fun padButton(key: Int): PadButton? = when (key) {
    KeyEvent.KEYCODE_DPAD_UP -> PadButton.UP; KeyEvent.KEYCODE_DPAD_DOWN -> PadButton.DOWN
    KeyEvent.KEYCODE_DPAD_LEFT -> PadButton.LEFT; KeyEvent.KEYCODE_DPAD_RIGHT -> PadButton.RIGHT
    KeyEvent.KEYCODE_BUTTON_A -> PadButton.A; KeyEvent.KEYCODE_BUTTON_B -> PadButton.B
    KeyEvent.KEYCODE_BUTTON_X -> PadButton.X; KeyEvent.KEYCODE_BUTTON_Y -> PadButton.Y
    KeyEvent.KEYCODE_BUTTON_L1 -> PadButton.L1; KeyEvent.KEYCODE_BUTTON_R1 -> PadButton.R1
    KeyEvent.KEYCODE_BUTTON_L2 -> PadButton.L2; KeyEvent.KEYCODE_BUTTON_R2 -> PadButton.R2
    KeyEvent.KEYCODE_BUTTON_THUMBL -> PadButton.L3; KeyEvent.KEYCODE_BUTTON_THUMBR -> PadButton.R3
    KeyEvent.KEYCODE_BUTTON_START -> PadButton.START; KeyEvent.KEYCODE_BUTTON_SELECT -> PadButton.SELECT
    else -> null // System Back/Enter, volume, Home and text input retain their native handling.
}
internal fun padAxes(event: MotionEvent, leftStickHandled: Boolean = false): Map<PadButton, Float> {
    fun greatest(a: Float, b: Float) = if (abs(a) > abs(b)) a else b
    val x = if (leftStickHandled) event.getAxisValue(MotionEvent.AXIS_HAT_X) else greatest(event.getAxisValue(MotionEvent.AXIS_HAT_X), event.getAxisValue(MotionEvent.AXIS_X))
    val y = if (leftStickHandled) event.getAxisValue(MotionEvent.AXIS_HAT_Y) else greatest(event.getAxisValue(MotionEvent.AXIS_HAT_Y), event.getAxisValue(MotionEvent.AXIS_Y))
    val rx = greatest(event.getAxisValue(MotionEvent.AXIS_Z), event.getAxisValue(MotionEvent.AXIS_RX))
    val ry = greatest(event.getAxisValue(MotionEvent.AXIS_RZ), event.getAxisValue(MotionEvent.AXIS_RY))
    return mapOf(PadButton.LEFT to -x, PadButton.RIGHT to x, PadButton.UP to -y, PadButton.DOWN to y,
        PadButton.L2 to maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE)),
        PadButton.R2 to maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS)),
        PadButton.RS_LEFT to -rx, PadButton.RS_RIGHT to rx, PadButton.RS_UP to -ry, PadButton.RS_DOWN to ry)
}

/** One recording, one device. Axis snapshots are atomic so opposite directions never form a chord. */
internal class PadRecording(private val settings: PadSettings) {
    var deviceId: Int? = null
        private set
    var candidate: Set<PadButton> = emptySet()
        private set
    private val held = mutableMapOf<PadButton, MutableSet<String>>()
    fun update(id: Int, channel: String, values: Map<PadButton, Boolean>): Boolean {
        if (deviceId == null && values.values.none { it }) return false
        if (deviceId == null) deviceId = id
        if (deviceId != id) return false
        val wasHeld = held.values.any { it.isNotEmpty() }
        values.forEach { (button, pressed) ->
            val sources = held.getOrPut(button) { mutableSetOf() }
            if (pressed) sources.add(channel) else sources.remove(channel)
        }
        val down = held.filterValues { it.isNotEmpty() }.keys
        if (down.isNotEmpty() && down.size >= candidate.size) candidate = down.toSet()
        return wasHeld && down.isEmpty() && candidate.isNotEmpty()
    }
    fun axes(id: Int, values: Map<PadButton, Float>) = update(id, "axis", values.mapValues { (button, value) ->
        value.isFinite() && value >= if (held[button]?.contains("axis") == true) settings.release else settings.press
    })
    fun clear() { held.clear(); deviceId = null; candidate = emptySet() }
}

/** Separate engines prevent a chord spanning two devices. Every focused window owns its own runtime. */
internal class PadWindowInput(private val settings: () -> PadSettings, private val context: () -> PadContext,
    private val emit: (PadAction) -> Unit, private val detected: (InputDevice?) -> Unit = {},
    private val leftStick: (Float, Float, Long) -> Boolean = { _, _, _ -> false },
    private val previewSticks: (MotionEvent, Long) -> Boolean = { _, _ -> false },
    private val rightStick: (Float, Float, Long) -> Boolean = { _, _, _ -> false }) {
    private var previous: Pair<PadSettings, PadContext>? = null
    private val devices = mutableMapOf<Int, Pair<String?, PadInput>>()
    private fun engine(id: Int): PadInput {
        val config = settings(); val context = context()
        if (previous != (config to context)) { clear(); previous = config to context }
        val device = InputDevice.getDevice(id)
        detected(device)
        val prior = devices[id]
        if (prior != null && prior.first == device?.descriptor) return prior.second
        prior?.second?.clear()
        val input = PadInput(config, config.profile(device?.descriptor), context)
        devices[id] = device?.descriptor to input
        return input
    }
    fun key(event: KeyEvent): Boolean {
        val button = padButton(event.keyCode) ?: return false
        if (button in setOf(PadButton.UP, PadButton.DOWN, PadButton.LEFT, PadButton.RIGHT) &&
            !event.isFromSource(InputDevice.SOURCE_GAMEPAD) && !event.isFromSource(InputDevice.SOURCE_JOYSTICK) &&
            event.device?.supportsSource(InputDevice.SOURCE_GAMEPAD) != true) return false
        if (event.action !in setOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) return false
        engine(event.deviceId).update(button, "key", event.action == KeyEvent.ACTION_DOWN, SystemClock.uptimeMillis()).forEach(emit)
        return true
    }
    fun motion(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_MOVE ||
            (!event.isFromSource(InputDevice.SOURCE_GAMEPAD) && !event.isFromSource(InputDevice.SOURCE_JOYSTICK))) return false
        val input = engine(event.deviceId); val now = SystemClock.uptimeMillis()
        val previewHandled = previewSticks(event, now)
        val handled = previewHandled || leftStick(event.getAxisValue(MotionEvent.AXIS_X), event.getAxisValue(MotionEvent.AXIS_Y), now)
        val axes = padAxes(event, handled)
        val rightHandled = previewHandled || rightStick(axes.getValue(PadButton.RS_RIGHT), axes.getValue(PadButton.RS_DOWN), now)
        axes.forEach { (button, value) ->
            val axis = if (rightHandled && button in setOf(PadButton.RS_LEFT, PadButton.RS_RIGHT, PadButton.RS_UP, PadButton.RS_DOWN)) 0f else value
            input.axis(button, "axis", axis, now).forEach(emit)
        }
        return true
    }
    fun poll() {
        if (previous != (settings() to context())) { clear(); return }
        devices.filter { (id, entry) -> entry.first != null && InputDevice.getDevice(id)?.descriptor != entry.first }.keys.toList()
            .forEach { devices.remove(it)?.second?.clear() }
        devices.values.toList().forEach { it.second.poll(SystemClock.uptimeMillis()).forEach(emit) }
    }
    fun clear() { devices.values.forEach { it.second.clear() }; devices.clear(); previous = null }
}
