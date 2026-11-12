package at.sturq.ccdcam

import android.opengl.GLES20
import android.util.Log
import at.sturq.ccdcam.profile.CameraProfile

/**
 * Uniform locations of the look parameters in ccd.frag, and upload of a [CameraProfile].
 * Must be created and used on the GL thread with [program] current.
 */
class LookUniforms(program: Int) {

    private val locs: Map<String, Int> = NAMES.associateWith { name ->
        GLES20.glGetUniformLocation(program, name).also {
            // -1 would silently upload nothing (value 0) and break the look.
            if (it < 0) Log.w(TAG, "uniform $name not found in program")
        }
    }

    private fun loc(name: String) = locs.getValue(name)

    fun apply(profile: CameraProfile) {
        val p = profile.shader
        GLES20.glUniform1f(loc("uEffectSpace"), profile.effectSpace.uniformValue)
        GLES20.glUniform1f(loc("uCcdX"), p.ccdX)
        GLES20.glUniform1f(loc("uCcdY"), p.ccdY)
        GLES20.glUniform1f(loc("uLines"), p.lines)
        GLES20.glUniform1f(loc("uSmearThreshold"), p.smearThreshold)
        GLES20.glUniform1f(loc("uSmearStrength"), p.smearStrength)
        GLES20.glUniform1f(loc("uSmearRange"), p.smearRange)
        GLES20.glUniform1f(loc("uFlareThreshold"), p.flareThreshold)
        GLES20.glUniform1f(loc("uFlareRange"), p.flareRange)
        GLES20.glUniform1f(loc("uFlareStrength"), p.flareStrength)
        GLES20.glUniform1f(loc("uChromaNoiseAmp"), p.chromaNoiseAmp)
        GLES20.glUniform1f(loc("uLumaGrainAmp"), p.lumaGrainAmp)
        GLES20.glUniform1f(loc("uBlackLift"), p.blackLift)
        GLES20.glUniform3f(loc("uWarmGrade"), p.warmGrade.first, p.warmGrade.second, p.warmGrade.third)
        GLES20.glUniform1f(loc("uSaturation"), p.saturation)
        GLES20.glUniform1f(loc("uContrast"), p.contrast)
        GLES20.glUniform1f(loc("uScanlineAmp"), p.scanlineAmp)
        GLES20.glUniform1f(loc("uVignetteStrength"), p.vignetteStrength)
        GLES20.glUniform1f(loc("uChromaShift"), p.chromaShift)
        GLES20.glUniform1f(loc("uHBlur"), p.hBlur)
    }

    companion object {
        private const val TAG = "LookUniforms"

        /** Every look uniform [apply] sets; ShaderUniformsTest checks them against ccd.frag. */
        val NAMES: List<String> = listOf(
            "uEffectSpace",
            "uCcdX", "uCcdY", "uLines",
            "uSmearThreshold", "uSmearStrength", "uSmearRange",
            "uFlareThreshold", "uFlareRange", "uFlareStrength",
            "uChromaNoiseAmp", "uLumaGrainAmp",
            "uBlackLift", "uWarmGrade", "uSaturation", "uContrast",
            "uScanlineAmp", "uVignetteStrength", "uChromaShift", "uHBlur",
        )
    }
}
