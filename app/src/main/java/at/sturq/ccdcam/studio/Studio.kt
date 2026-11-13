package at.sturq.ccdcam.studio

import at.sturq.ccdcam.haptics.HapticLevel
import at.sturq.ccdcam.profile.CameraProfile

/**
 * STUDIO: the app's own control room above the emulated camcorder (not part of the Sony UI).
 * Look sliders fine-tune the picture on top of the menu settings (1 = as the menu sets it),
 * NightShot has its IR-spot strength, the camera section gives manual focus / exposure / zoom.
 */
data class StudioSlider(
    val id: String,
    val label: String,
    val min: Float,
    val max: Float,
    val default: Float,
    /** Detent intervals over the range (a tactile step each). */
    val steps: Int,
    /** Value with its own detent feel (the neutral point), or null. */
    val center: Float? = null,
    /** How the value is shown. */
    val format: (Float) -> String = { "×%.2f".format(java.util.Locale.US, it) },
)

object StudioSliders {
    /** FOCUS values up to this are autofocus. */
    const val FOCUS_AF_MAX = 0.04f

    private fun pct(v: Float) = "${Math.round(v * 100)}%"

    // Picture (multipliers on the menu look; 1 = unchanged)
    val SCANLINES = StudioSlider("scanlines", "SCANLINES", 0f, 2f, 1f, 8, 1f)
    val NOISE = StudioSlider("noise", "CCD NOISE", 0f, 2f, 1f, 8, 1f)
    val FLARE = StudioSlider("flare", "FLARE", 0f, 2f, 1f, 8, 1f)
    val VIGNETTE = StudioSlider("vignette", "VIGNETTE", 0f, 2f, 1f, 8, 1f)
    val CHROMA = StudioSlider("chroma", "CHROMA BLEED", 0f, 3f, 1f, 6, 1f)
    val SHARPNESS = StudioSlider("sharpness", "SHARPNESS", 0f, 2f, 1f, 8, 1f)
    val SATURATION = StudioSlider("saturation", "SATURATION", 0f, 2f, 1f, 8, 1f)
    val CONTRAST = StudioSlider("contrast", "CONTRAST", 0.5f, 1.5f, 1f, 8, 1f)
    val WARMTH = StudioSlider("warmth", "WARMTH", -1f, 1f, 0f, 8, 0f) { v -> "%+.2f".format(java.util.Locale.US, v) }
    /** Vertical CCD smear amount added to the menu's CCD SMEAR. */
    val SMEAR = StudioSlider("smear", "CCD SMEAR", 0f, 1f, 0f, 10, format = ::pct)

    // NightShot
    /** Darkening towards the edges, as from the camera's weak built-in IR light. */
    val IR_SPOT = StudioSlider("ir_spot", "IR SPOT", 0f, 1f, 0.7f, 10, format = ::pct)

    // Camera (manual)
    /** Left end = AF; then manual from infinity to the closest focus. */
    val FOCUS = StudioSlider("focus", "FOCUS", 0f, 1f, 0f, 10) { v ->
        when {
            v <= FOCUS_AF_MAX -> "AF"
            v <= FOCUS_AF_MAX + 0.03f -> "∞"
            else -> "MF " + pct((v - FOCUS_AF_MAX) / (1f - FOCUS_AF_MAX))
        }
    }
    /** Exposure shift in EV, added to EXPOSURE / NightShot. */
    val EV = StudioSlider("ev", "EXPOSURE", -2f, 2f, 0f, 12, 0f) { v -> "%+.1f EV".format(java.util.Locale.US, v) }
    /** Linear zoom 0..1 of the lens range. */
    val ZOOM = StudioSlider("zoom", "ZOOM", 0f, 1f, 0f, 10, format = ::pct)

    val LOOK = listOf(SCANLINES, NOISE, FLARE, VIGNETTE, CHROMA, SHARPNESS, SATURATION, CONTRAST, WARMTH, SMEAR)
    val NIGHTSHOT = listOf(IR_SPOT)
    val CAMERA = listOf(FOCUS, EV, ZOOM)
    val ALL = LOOK + NIGHTSHOT + CAMERA

    fun byId(id: String): StudioSlider = ALL.first { it.id == id }
}

/** Current STUDIO state (persisted by SettingsStore). */
data class StudioState(
    val values: Map<String, Float> = emptyMap(),
    /** Manual focus on (FOCUS slider applies); off = autofocus. */
    val manualFocus: Boolean = false,
    val haptics: HapticLevel = HapticLevel.SUBTLE,
) {
    /** FOCUS slider past its AF end: manual focus. */
    val manualFocusActive: Boolean get() = value(StudioSliders.FOCUS) > StudioSliders.FOCUS_AF_MAX

    /** Manual focus position 0 (infinity) … 1 (closest). */
    val focusFraction: Float
        get() = ((value(StudioSliders.FOCUS) - StudioSliders.FOCUS_AF_MAX) / (1f - StudioSliders.FOCUS_AF_MAX)).coerceIn(0f, 1f)

    /** RESET ALL: look, exposure, focus back to auto (tactile level kept). */
    fun resetAll() = copy(values = emptyMap(), manualFocus = false)

    fun value(s: StudioSlider): Float = (values[s.id] ?: s.default).coerceIn(s.min, s.max)
    fun with(s: StudioSlider, v: Float) = copy(values = values + (s.id to v.coerceIn(s.min, s.max)))

    /** Look sliders back to neutral (camera and tactile settings kept). */
    fun resetLook() = copy(values = values - (StudioSliders.LOOK + StudioSliders.NIGHTSHOT).map { it.id }.toSet())
}

object StudioLook {
    /** [base] (menu look) with the STUDIO picture sliders applied. Neutral state = [base]. */
    fun apply(base: CameraProfile, st: StudioState): CameraProfile {
        val p = base.shader
        val warmth = st.value(StudioSliders.WARMTH)
        val (r, g, b) = p.warmGrade
        val sharp = st.value(StudioSliders.SHARPNESS)
        return base.copy(
            shader = p.copy(
                scanlineAmp = p.scanlineAmp * st.value(StudioSliders.SCANLINES),
                chromaNoiseAmp = p.chromaNoiseAmp * st.value(StudioSliders.NOISE),
                lumaGrainAmp = p.lumaGrainAmp * st.value(StudioSliders.NOISE),
                flareStrength = p.flareStrength * st.value(StudioSliders.FLARE),
                vignetteStrength = p.vignetteStrength * st.value(StudioSliders.VIGNETTE),
                chromaShift = p.chromaShift * st.value(StudioSliders.CHROMA),
                // sharpness 1 = menu blur; 2 = no low-pass; 0 = twice the blur (max 1.5 cells)
                hBlur = if (sharp >= 1f) p.hBlur * (2f - sharp) else minOf(1.5f, p.hBlur * (2f - sharp)),
                saturation = p.saturation * st.value(StudioSliders.SATURATION),
                contrast = p.contrast * st.value(StudioSliders.CONTRAST),
                warmGrade = Triple(r * (1f + 0.08f * warmth), g, b * (1f - 0.08f * warmth)),
                smearStrength = p.smearStrength + 0.6f * st.value(StudioSliders.SMEAR),
                irSpot = st.value(StudioSliders.IR_SPOT),
            ),
        )
    }
}
