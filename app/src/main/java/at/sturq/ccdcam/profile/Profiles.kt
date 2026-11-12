package at.sturq.ccdcam.profile

object Profiles {

    /**
     * The original CCDCam look. Must stay identical to the former `const` values of
     * `assets/shaders/ccd.frag` (before stage 2), to texture-space effects, and to the
     * stretch MainActivity has always applied (0.78); ProfilesTest enforces this.
     */
    val CCDCAM_DEFAULT = CameraProfile(
        id = "ccdcam",
        displayName = "CCDCam",
        hudLabel = "CCD CAM",
        shader = ShaderParams(
            ccdX = 720f,
            ccdY = 720f,
            lines = 480f,
            smearThreshold = 0.86f,
            smearStrength = 0.0f,
            smearRange = 0.38f,
            flareThreshold = 0.90f,
            flareRange = 0.14f,
            flareStrength = 0.30f,
            chromaNoiseAmp = 0.02f,
            lumaGrainAmp = 0.05f,
            blackLift = 0.01f,
            warmGrade = Triple(1.05f, 1.01f, 0.98f),
            saturation = 0.98f,
            contrast = 1.15f,
            scanlineAmp = 0.025f,
            vignetteStrength = 0.35f,
            chromaShift = 0.0003f,
            hBlur = 0f,
        ),
        effectSpace = EffectSpace.TEXTURE,
        stretch = 0.78f,
        timecodeFps = 30,
    )

    /**
     * Sony DCR-HC90E (PAL) emulation.
     *
     * Stage 3 (spatial): the look is sampled on a 720x576 grid (PAL DV active picture,
     * see docs/hc90e-reference.md) with 576 scanlines and a horizontal low-pass, computed
     * in upright picture space, no anamorphic squish (stretch 1.0). The recorded file is
     * still CameraX Recorder output (16:9 H.264: 1280x720 landscape, 720x1280 portrait),
     * NOT a 720x576 file.
     * Colour, frame rate and interlace come in later stages; colour values are still
     * CCDCam's for now. The timecode stays at 30 until real 25 fps capture exists.
     */
    val HC90E_PAL = CCDCAM_DEFAULT.copy(
        id = "hc90e_pal",
        displayName = "HC90E PAL",
        hudLabel = "HC90E PAL",
        effectSpace = EffectSpace.PICTURE,
        stretch = 1.0f,
        shader = CCDCAM_DEFAULT.shader.copy(
            ccdX = 720f,
            ccdY = 576f,
            lines = 576f,
            hBlur = 0.5f,
        ),
    )

    val ALL: List<CameraProfile> = listOf(CCDCAM_DEFAULT, HC90E_PAL)

    val DEFAULT: CameraProfile = CCDCAM_DEFAULT

    /** Unknown or missing ids fall back to [DEFAULT] so old installs keep the original look. */
    fun byId(id: String?): CameraProfile = ALL.firstOrNull { it.id == id } ?: DEFAULT
}
