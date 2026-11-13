package at.sturq.ccdcam.haptics

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Tactile feedback of the emulated controls. Events are routed by [HapticRouter] to the best
 * tier the device supports (primitive compositions on 11+, predefined effects on 10+,
 * View.performHapticFeedback on 7+), rate-limited by [HapticThrottle], and played on a small
 * thread of their own — never on the camera, GL or audio threads. Honours the TACTILE FEEDBACK
 * setting, the system touch-feedback setting and devices without a vibrator.
 */
class Hc90eHapticEngine(private val context: Context, private val view: View) {

    @Volatile var level: HapticLevel = HapticLevel.SUBTLE

    private val vibrator: Vibrator? = run {
        if (Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private val thread = HandlerThread("HC90eHaptic").apply { start() }
    private val haptic = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val throttle = HapticThrottle()

    /** Probed once (support does not change at run time); the system setting is read per event. */
    private val staticCaps: HapticCapabilities by lazy { probe() }

    fun capabilities(): HapticCapabilities = staticCaps.copy(systemHapticsEnabled = systemHapticsEnabled())

    /** Plays [event] (any thread). Dropped when OFF, unsupported, or too soon after the last. */
    fun fire(event: HapticEvent) {
        if (level == HapticLevel.OFF) return
        val now = SystemClock.uptimeMillis()
        val allowed = synchronized(throttle) { throttle.allow(event, now) }
        if (!allowed) return
        when (val p = HapticRouter.route(event, level, capabilities())) {
            is HapticPattern.Composition -> haptic.post { playComposition(p) }
            is HapticPattern.PredefinedSeq -> playPredefined(p)
            is HapticPattern.ViewSeq -> playView(p)
            HapticPattern.None -> Unit
        }
    }

    fun release() {
        haptic.removeCallbacksAndMessages(null)
        thread.quitSafely()
    }

    // ---- capabilities ----

    private fun probe(): HapticCapabilities {
        val v = vibrator
        val has = v?.hasVibrator() == true
        val sdk = Build.VERSION.SDK_INT
        var prims = emptySet<Primitive>()
        var predef = emptySet<Predefined>()
        if (has && v != null) {
            if (sdk >= 30) {
                prims = Primitive.entries.filter { p ->
                    val id = primitiveId(p) ?: return@filter false
                    runCatching { v.arePrimitivesSupported(id).all { it } }.getOrDefault(false)
                }.toSet()
                predef = Predefined.entries.filter { e ->
                    // NO = unsupported; UNKNOWN = the device cannot tell, the system falls back itself
                    runCatching { v.areEffectsSupported(effectId(e))[0] != Vibrator.VIBRATION_EFFECT_SUPPORT_NO }
                        .getOrDefault(false)
                }.toSet()
            } else if (sdk >= 29) {
                predef = Predefined.entries.toSet()
            }
        }
        return HapticCapabilities(sdk, has, prims, predef).also { Log.i(TAG, "haptics $it") }
    }

    private fun systemHapticsEnabled(): Boolean =
        Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0

    // ---- playback ----

    @SuppressLint("NewApi")
    private fun playComposition(p: HapticPattern.Composition) {
        val v = vibrator ?: return
        if (Build.VERSION.SDK_INT < 30) return
        try {
            val c = VibrationEffect.startComposition()
            for (s in p.steps) c.addPrimitive(primitiveId(s.effect) ?: continue, s.scale.coerceIn(0f, 1f), s.delayMs)
            vibrate(v, c.compose())
        } catch (t: Throwable) {
            Log.w(TAG, "composition failed", t)
        }
    }

    @SuppressLint("NewApi")
    private fun playPredefined(p: HapticPattern.PredefinedSeq) {
        val v = vibrator ?: return
        if (Build.VERSION.SDK_INT < 29) return
        var at = 0L
        for (s in p.steps) {
            at += s.delayMs
            haptic.postDelayed({
                runCatching { vibrate(v, VibrationEffect.createPredefined(effectId(s.effect))) }
                    .onFailure { Log.w(TAG, "predefined failed", it) }
            }, at)
        }
    }

    /** performHapticFeedback must run on the UI thread; it honours the system setting itself. */
    private fun playView(p: HapticPattern.ViewSeq) {
        var at = 0L
        for (s in p.steps) {
            at += s.delayMs
            main.postDelayed({ view.performHapticFeedback(viewConstant(s.effect)) }, at)
        }
    }

    @SuppressLint("NewApi", "MissingPermission")
    private fun vibrate(v: Vibrator, e: VibrationEffect) {
        if (Build.VERSION.SDK_INT >= 33) {
            // USAGE_TOUCH: follows the user's touch-feedback intensity setting
            v.vibrate(e, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(e, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build())
        }
    }

    private fun primitiveId(p: Primitive): Int? {
        val sdk = Build.VERSION.SDK_INT
        return when (p) {
            Primitive.CLICK -> if (sdk >= 30) VibrationEffect.Composition.PRIMITIVE_CLICK else null
            Primitive.TICK -> if (sdk >= 30) VibrationEffect.Composition.PRIMITIVE_TICK else null
            Primitive.QUICK_RISE -> if (sdk >= 30) VibrationEffect.Composition.PRIMITIVE_QUICK_RISE else null
            Primitive.QUICK_FALL -> if (sdk >= 30) VibrationEffect.Composition.PRIMITIVE_QUICK_FALL else null
            Primitive.LOW_TICK -> if (sdk >= 31) VibrationEffect.Composition.PRIMITIVE_LOW_TICK else null
            Primitive.THUD -> if (sdk >= 31) VibrationEffect.Composition.PRIMITIVE_THUD else null
        }
    }

    private fun effectId(e: Predefined): Int = when (e) {
        Predefined.CLICK -> VibrationEffect.EFFECT_CLICK
        Predefined.TICK -> VibrationEffect.EFFECT_TICK
        Predefined.HEAVY_CLICK -> VibrationEffect.EFFECT_HEAVY_CLICK
        Predefined.DOUBLE_CLICK -> VibrationEffect.EFFECT_DOUBLE_CLICK
    }

    private fun viewConstant(f: ViewFeedback): Int = when (f) {
        ViewFeedback.VIRTUAL_KEY -> HapticFeedbackConstants.VIRTUAL_KEY
        ViewFeedback.KEYBOARD_TAP -> HapticFeedbackConstants.KEYBOARD_TAP
        ViewFeedback.CLOCK_TICK -> HapticFeedbackConstants.CLOCK_TICK
        ViewFeedback.CONTEXT_CLICK -> HapticFeedbackConstants.CONTEXT_CLICK
        ViewFeedback.LONG_PRESS -> HapticFeedbackConstants.LONG_PRESS
        ViewFeedback.CONFIRM -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CONTEXT_CLICK
        ViewFeedback.REJECT -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
    }

    private companion object {
        const val TAG = "HC90eHaptic"
    }
}
