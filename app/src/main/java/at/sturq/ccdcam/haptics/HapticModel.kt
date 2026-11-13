package at.sturq.ccdcam.haptics

/** Tactile events of the emulated camcorder controls (stage 15). */
enum class HapticEvent(
    /** Rare, state-changing events: never dropped by the global pulse limit. */
    val priority: Boolean = false,
) {
    BUTTON_PRESS,
    SWITCH_TOGGLE,
    SLIDER_DETENT,
    SLIDER_CENTER,
    SLIDER_ENDSTOP,
    REC_STARTED(priority = true),
    REC_STOPPED(priority = true),
    TAPE_EJECT(priority = true),
    TAPE_INSERT(priority = true),
    FOCUS_LOCKED(priority = true),
    ACTION_REJECTED(priority = true),
}

/** TACTILE FEEDBACK setting. */
enum class HapticLevel { OFF, SUBTLE, MECHANICAL }

/** Platform-neutral names of the effects (mapped to Android constants in Hc90eHapticEngine). */
enum class Primitive { CLICK, TICK, LOW_TICK, THUD, QUICK_RISE, QUICK_FALL }
enum class Predefined { CLICK, TICK, HEAVY_CLICK, DOUBLE_CLICK }
enum class ViewFeedback { VIRTUAL_KEY, KEYBOARD_TAP, CLOCK_TICK, CONTEXT_CLICK, LONG_PRESS, CONFIRM, REJECT }

/** One step of a pattern: an effect after [delayMs] from the previous step. */
data class Step<T>(val effect: T, val scale: Float = 1f, val delayMs: Int = 0)

/** What to play; one of the three tiers, or nothing. */
sealed interface HapticPattern {
    /** Android 11+: one VibrationEffect.Composition (delays inside the composition). */
    data class Composition(val steps: List<Step<Primitive>>) : HapticPattern
    /** Android 10+: predefined effects, later steps scheduled off the UI thread. */
    data class PredefinedSeq(val steps: List<Step<Predefined>>) : HapticPattern
    /** Android 7+: View.performHapticFeedback (honours the system haptic setting itself). */
    data class ViewSeq(val steps: List<Step<ViewFeedback>>) : HapticPattern
    object None : HapticPattern
}

/** What the device can do, probed at run time. */
data class HapticCapabilities(
    val sdk: Int,
    val hasVibrator: Boolean,
    /** Primitives the vibrator reports as supported (empty below Android 11). */
    val primitives: Set<Primitive> = emptySet(),
    /** Predefined effects usable (Android 10+; on 11+ only those reported supported). */
    val predefined: Set<Predefined> = emptySet(),
    /** Settings > Sound & vibration > touch feedback. */
    val systemHapticsEnabled: Boolean = true,
)

/**
 * Event → pattern. Patterns are short and built from system primitives / predefined effects:
 * the aim is the feel of physical controls (detents, a switch's clunk, the cassette lid),
 * not strength. SUBTLE uses lighter effects and scales; MECHANICAL adds the second "part" of
 * mechanical events. A tier is used only if every effect in it is supported, otherwise the next
 * lower tier is tried.
 */
object HapticRouter {

    fun route(event: HapticEvent, level: HapticLevel, caps: HapticCapabilities): HapticPattern {
        if (level == HapticLevel.OFF || !caps.hasVibrator || !caps.systemHapticsEnabled) return HapticPattern.None
        val mech = level == HapticLevel.MECHANICAL
        if (caps.sdk >= 30) {
            composition(event, mech)?.let { c -> if (c.steps.all { it.effect in caps.primitives }) return c }
            // a lighter variant without the newer primitives (THUD / LOW_TICK / ramps are 31+)
            composition(event, mech)?.let { c ->
                val basic = c.steps.mapNotNull { s -> substitute(s) }
                if (basic.isNotEmpty() && basic.all { it.effect in caps.primitives }) return HapticPattern.Composition(basic)
            }
        }
        if (caps.sdk >= 29) {
            predefined(event, mech).let { p -> if (p.steps.all { it.effect in caps.predefined }) return p }
        }
        return view(event, mech, caps.sdk)
    }

    /** Fallback for primitives missing on a device: THUD/LOW_TICK → CLICK/TICK, ramps dropped. */
    private fun substitute(s: Step<Primitive>): Step<Primitive>? = when (s.effect) {
        Primitive.THUD -> s.copy(effect = Primitive.CLICK)
        Primitive.LOW_TICK -> s.copy(effect = Primitive.TICK)
        Primitive.QUICK_RISE, Primitive.QUICK_FALL -> null
        else -> s
    }

    private fun c(vararg s: Step<Primitive>) = HapticPattern.Composition(s.toList())
    private fun p(vararg s: Step<Predefined>) = HapticPattern.PredefinedSeq(s.toList())
    private fun v(vararg s: Step<ViewFeedback>) = HapticPattern.ViewSeq(s.toList())

    private fun composition(e: HapticEvent, mech: Boolean): HapticPattern.Composition? {
        val k = if (mech) 1f else 0.6f
        return when (e) {
            HapticEvent.BUTTON_PRESS -> c(Step(Primitive.CLICK, 0.7f * k))
            HapticEvent.SWITCH_TOGGLE ->
                if (mech) c(Step(Primitive.CLICK, 0.9f), Step(Primitive.LOW_TICK, 0.6f, 35))
                else c(Step(Primitive.CLICK, 0.55f))
            HapticEvent.SLIDER_DETENT -> c(Step(Primitive.TICK, 0.5f * k))
            HapticEvent.SLIDER_CENTER -> c(Step(Primitive.CLICK, 0.8f * k))
            HapticEvent.SLIDER_ENDSTOP -> c(Step(Primitive.THUD, 0.8f * k))
            HapticEvent.REC_STARTED ->
                if (mech) c(Step(Primitive.QUICK_RISE, 0.4f), Step(Primitive.CLICK, 1f, 10))
                else c(Step(Primitive.CLICK, 0.8f))
            HapticEvent.REC_STOPPED ->
                if (mech) c(Step(Primitive.CLICK, 0.9f), Step(Primitive.QUICK_FALL, 0.4f, 40))
                else c(Step(Primitive.CLICK, 0.6f), Step(Primitive.TICK, 0.4f, 60))
            HapticEvent.TAPE_EJECT ->
                if (mech) c(Step(Primitive.CLICK, 0.9f), Step(Primitive.LOW_TICK, 0.6f, 70), Step(Primitive.THUD, 0.7f, 90))
                else c(Step(Primitive.CLICK, 0.6f), Step(Primitive.TICK, 0.4f, 80))
            HapticEvent.TAPE_INSERT ->
                if (mech) c(Step(Primitive.LOW_TICK, 0.6f), Step(Primitive.THUD, 0.8f, 80), Step(Primitive.CLICK, 0.9f, 60))
                else c(Step(Primitive.TICK, 0.4f), Step(Primitive.CLICK, 0.6f, 90))
            HapticEvent.FOCUS_LOCKED -> c(Step(Primitive.TICK, 0.5f * k), Step(Primitive.TICK, 0.8f * k, 60))
            HapticEvent.ACTION_REJECTED ->
                c(Step(Primitive.LOW_TICK, 0.7f * k), Step(Primitive.LOW_TICK, 0.7f * k, 50), Step(Primitive.LOW_TICK, 0.7f * k, 50))
        }
    }

    private fun predefined(e: HapticEvent, mech: Boolean): HapticPattern.PredefinedSeq = when (e) {
        HapticEvent.BUTTON_PRESS -> p(Step(if (mech) Predefined.CLICK else Predefined.TICK))
        HapticEvent.SWITCH_TOGGLE -> p(Step(if (mech) Predefined.HEAVY_CLICK else Predefined.CLICK))
        HapticEvent.SLIDER_DETENT -> p(Step(Predefined.TICK))
        HapticEvent.SLIDER_CENTER -> p(Step(Predefined.CLICK))
        HapticEvent.SLIDER_ENDSTOP -> p(Step(Predefined.HEAVY_CLICK))
        HapticEvent.REC_STARTED -> p(Step(Predefined.HEAVY_CLICK))
        HapticEvent.REC_STOPPED -> p(Step(Predefined.CLICK), Step(Predefined.TICK, delayMs = 70))
        HapticEvent.TAPE_EJECT ->
            if (mech) p(Step(Predefined.CLICK), Step(Predefined.TICK, delayMs = 80), Step(Predefined.HEAVY_CLICK, delayMs = 90))
            else p(Step(Predefined.CLICK), Step(Predefined.TICK, delayMs = 80))
        HapticEvent.TAPE_INSERT ->
            if (mech) p(Step(Predefined.TICK), Step(Predefined.HEAVY_CLICK, delayMs = 80), Step(Predefined.CLICK, delayMs = 60))
            else p(Step(Predefined.TICK), Step(Predefined.CLICK, delayMs = 90))
        HapticEvent.FOCUS_LOCKED -> p(Step(Predefined.TICK), Step(Predefined.TICK, delayMs = 60))
        HapticEvent.ACTION_REJECTED -> p(Step(Predefined.DOUBLE_CLICK))
    }

    private fun view(e: HapticEvent, mech: Boolean, sdk: Int): HapticPattern.ViewSeq {
        val confirm = if (sdk >= 30) ViewFeedback.CONFIRM else ViewFeedback.CONTEXT_CLICK
        // before Android 11 there is no REJECT constant: LONG_PRESS is the one unlike any press
        val reject = if (sdk >= 30) ViewFeedback.REJECT else ViewFeedback.LONG_PRESS
        return when (e) {
            HapticEvent.BUTTON_PRESS -> v(Step(if (mech) ViewFeedback.VIRTUAL_KEY else ViewFeedback.KEYBOARD_TAP))
            HapticEvent.SWITCH_TOGGLE -> v(Step(ViewFeedback.CONTEXT_CLICK))
            HapticEvent.SLIDER_DETENT -> v(Step(ViewFeedback.CLOCK_TICK))
            HapticEvent.SLIDER_CENTER -> v(Step(ViewFeedback.CONTEXT_CLICK))
            HapticEvent.SLIDER_ENDSTOP -> v(Step(ViewFeedback.VIRTUAL_KEY))
            HapticEvent.REC_STARTED -> v(Step(confirm))
            HapticEvent.REC_STOPPED -> v(Step(ViewFeedback.VIRTUAL_KEY), Step(ViewFeedback.CLOCK_TICK, delayMs = 70))
            HapticEvent.TAPE_EJECT, HapticEvent.TAPE_INSERT ->
                v(Step(ViewFeedback.CONTEXT_CLICK), Step(ViewFeedback.CLOCK_TICK, delayMs = 90))
            HapticEvent.FOCUS_LOCKED -> v(Step(confirm))
            HapticEvent.ACTION_REJECTED -> v(Step(reject))
        }
    }
}

/**
 * Limits the pulse rate: a minimum gap per event type (drag detents at most every
 * [detentGapMs]) and a global gap between any two non-priority pulses, so fast movement or
 * repeated taps never become a continuous buzz. Priority events (REC, tape, focus, reject)
 * always pass.
 */
class HapticThrottle(
    private val detentGapMs: Long = 45,
    private val sameEventGapMs: Long = 70,
    private val globalGapMs: Long = 25,
) {
    private val last = HashMap<HapticEvent, Long>()
    private var lastAny = Long.MIN_VALUE / 2

    fun allow(event: HapticEvent, nowMs: Long): Boolean {
        if (!event.priority) {
            val gap = if (event == HapticEvent.SLIDER_DETENT) detentGapMs else sameEventGapMs
            val prev = last[event]
            if (prev != null && nowMs - prev < gap) return false
            if (nowMs - lastAny < globalGapMs) return false
        }
        last[event] = nowMs
        lastAny = nowMs
        return true
    }
}

/**
 * Detents of a slider over [min]..[max] with [steps] intervals: emits a pulse only when a step
 * boundary is crossed, a distinct one at the centre (if [center] is given) and at the ends
 * (once until the slider leaves the end). Continuous drags between steps stay silent.
 */
class SliderDetents(
    private val min: Float, private val max: Float, private val steps: Int,
    private val center: Float? = null,
) {
    private var lastStep: Int? = null
    private var atEnd = false
    private var lastValue: Float? = null

    fun onValue(value: Float): HapticEvent? {
        val v = value.coerceIn(min, max)
        val step = ((v - min) / (max - min) * steps).let { kotlin.math.round(it).toInt() }
        val prevValue = lastValue
        val prevStep = lastStep
        lastValue = v; lastStep = step
        if (prevStep == null) { atEnd = v == min || v == max; return null }
        val end = v == min || v == max
        if (end) {
            if (atEnd) return null
            atEnd = true
            return HapticEvent.SLIDER_ENDSTOP
        }
        atEnd = false
        if (center != null && prevValue != null && (prevValue - center) * (v - center) <= 0f && prevValue != center) {
            return HapticEvent.SLIDER_CENTER
        }
        return if (step != prevStep) HapticEvent.SLIDER_DETENT else null
    }
}
