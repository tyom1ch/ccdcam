package at.sturq.ccdcam.firmware

import at.sturq.ccdcam.profile.CameraProfile
import at.sturq.ccdcam.profile.Profiles

/**
 * App settings (not Sony menu items, except SHARPNESS) that tune the emulated picture and the
 * UI sound level. The first option of each item is the default and reproduces the stock
 * HC90E PAL look exactly. Values are artistic approximations.
 */
object LookTweaks {
    // option ids
    const val STD = "STD"
    const val OFF = "OFF"
    const val ON = "ON"
    const val LOW = "LOW"
    const val MID = "MID"
    const val HIGH = "HIGH"
    const val SOFT = "SOFT"
    const val SHARP = "SHARP"
    const val VIVID = "VIVID"
    const val WARM = "WARM"
    const val COOL = "COOL"

    /** Picture of [s]: the HC90E PAL profile with the CCD LOOK settings and SHARPNESS applied. */
    fun profile(s: FirmwareSettings, base: CameraProfile = Profiles.HC90E_PAL): CameraProfile {
        var p = base.shader
        p = when (s.tweak(HC90eMenu.SCANLINES)) {
            OFF -> p.copy(scanlineAmp = 0f)
            LOW -> p.copy(scanlineAmp = p.scanlineAmp * 0.5f)
            HIGH -> p.copy(scanlineAmp = p.scanlineAmp * 2.4f)
            else -> p
        }
        if (s.tweak(HC90eMenu.INTERLACE) == OFF) p = p.copy(interlace = 0f)
        p = when (s.tweak(HC90eMenu.CCD_NOISE)) {
            LOW -> p.copy(chromaNoiseAmp = p.chromaNoiseAmp * 0.5f, lumaGrainAmp = p.lumaGrainAmp * 0.5f)
            HIGH -> p.copy(chromaNoiseAmp = p.chromaNoiseAmp * 1.8f, lumaGrainAmp = p.lumaGrainAmp * 1.8f)
            else -> p
        }
        // Vertical streaks from bright lights, typical of interline-transfer CCDs.
        if (s.tweak(HC90eMenu.CCD_SMEAR) == ON) p = p.copy(smearStrength = 0.35f)
        p = when (s.tweak(HC90eMenu.FLARE)) {
            OFF -> p.copy(flareStrength = 0f)
            HIGH -> p.copy(flareStrength = p.flareStrength * 2f)
            else -> p
        }
        p = when (s.tweak(HC90eMenu.VIGNETTE)) {
            OFF -> p.copy(vignetteStrength = 0f)
            HIGH -> p.copy(vignetteStrength = p.vignetteStrength * 1.7f)
            else -> p
        }
        p = when (s.tweak(HC90eMenu.COLOR)) {
            VIVID -> p.copy(saturation = p.saturation * 1.18f, contrast = p.contrast * 1.04f)
            WARM -> p.copy(warmGrade = Triple(1.09f, 1.02f, 0.92f))
            COOL -> p.copy(warmGrade = Triple(0.98f, 1.00f, 1.05f))
            else -> p
        }
        p = when (s.tweak(HC90eMenu.SHARPNESS)) {
            SOFT -> p.copy(hBlur = 1.0f)
            SHARP -> p.copy(hBlur = 0.15f)
            else -> p
        }
        return base.copy(shader = p)
    }

    /** Playback level of the UI sounds (BEEP VOL). */
    fun beepVolume(s: FirmwareSettings): Float = when (s.tweak(HC90eMenu.BEEP_VOL)) {
        MID -> 0.55f
        LOW -> 0.28f
        else -> 1f
    }
}
