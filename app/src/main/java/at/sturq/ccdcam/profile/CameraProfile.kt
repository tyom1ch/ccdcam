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
    /** Horizontal low-pass within a CCD cell, in cells (0 = off, exact original sampling). */
    val hBlur: Float,
)

/**
 * A camera profile: the look parameters of the emulated camera. The app emulates one camera,
 * the DCR-HC90E ([Profiles.HC90E_PAL]); the type stays so look variants (e.g. NightShot) can be
 * expressed as profiles later.
 */
data class CameraProfile(
    val id: String,
    val displayName: String,
    val shader: ShaderParams,
)
