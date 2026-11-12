package at.sturq.ccdcam.profile

/**
 * Look parameters of `assets/shaders/ccd.frag`. Field names map 1:1 to the GLSL constants
 * (camelCase of the SCREAMING_CASE name). Loop sample counts (SMEAR_SAMPLES, FLARE_SAMPLES)
 * are deliberately not here: GLSL ES 2.0 needs constant loop bounds, so they stay `const`.
 *
 * Fed to the shader as uniforms every frame by LookUniforms (CcdSurfaceProcessor).
 */
data class ShaderParams(
    val ccdX: Float,
    val ccdY: Float,
    val lines: Float,
    val smearThreshold: Float,
    val smearStrength: Float,
    val smearRange: Float,
    val flareThreshold: Float,
    val flareRange: Float,
    val flareStrength: Float,
    val chromaNoiseAmp: Float,
    val lumaGrainAmp: Float,
    val blackLift: Float,
    val warmGrade: Triple<Float, Float, Float>,
    val saturation: Float,
    val contrast: Float,
    val scanlineAmp: Float,
    val vignetteStrength: Float,
    val chromaShift: Float,
)

/** Coordinate space the look is computed in (`uEffectSpace` in ccd.frag). */
enum class EffectSpace(val uniformValue: Float) {
    /** Camera texture space (after the transform matrix). The original CCDCam behaviour. */
    TEXTURE(0f),

    /** Output-surface space: grid, scanlines, smear and flare follow the output frame. */
    OUTPUT(1f),
}

/**
 * A camera profile: everything that makes one emulated camera differ from another.
 *
 * @param hudLabel text of the bottom-left HUD label
 * @param stretch horizontal sample range fed to `uStretch` in passthrough.vert
 * @param timecodeFps frame field of the HUD timecode runs 0 until timecodeFps - 1
 */
data class CameraProfile(
    val id: String,
    val displayName: String,
    val hudLabel: String,
    val shader: ShaderParams,
    val effectSpace: EffectSpace,
    val stretch: Float,
    val timecodeFps: Int,
)
