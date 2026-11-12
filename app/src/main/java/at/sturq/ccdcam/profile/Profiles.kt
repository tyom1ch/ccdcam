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
        ),
        effectSpace = EffectSpace.TEXTURE,
        stretch = 0.78f,
        timecodeFps = 30,
    )

    /**
     * Sony DCR-HC90E (PAL) emulation. Stage 2: same look values as CCDCAM_DEFAULT but
     * computed in output space. PAL values (720x576 grid, 576 lines, 25 fps timecode,
     * colour) arrive in later stages; see docs/hc90e-reference.md for what is documented.
     */
    val HC90E_PAL = CCDCAM_DEFAULT.copy(
        id = "hc90e_pal",
        displayName = "HC90E PAL",
        hudLabel = "HC90E PAL",
        effectSpace = EffectSpace.OUTPUT,
    )

    val ALL: List<CameraProfile> = listOf(CCDCAM_DEFAULT, HC90E_PAL)

    val DEFAULT: CameraProfile = CCDCAM_DEFAULT

    /** Unknown or missing ids fall back to [DEFAULT] so old installs keep the original look. */
    fun byId(id: String?): CameraProfile = ALL.firstOrNull { it.id == id } ?: DEFAULT
}
