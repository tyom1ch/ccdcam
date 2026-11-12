package at.sturq.ccdcam.profile

object Profiles {

    /**
     * Sony DCR-HC90E (PAL) emulation.
     *
     * Spatial: the look is sampled on a 720x576 grid (PAL DV active picture, see
     * docs/hc90e-reference.md) with 576 scanlines and a horizontal low-pass, in upright picture
     * space, PAL DV 4:2:0 chroma (BT.601). The recorded file is CameraX Recorder output (16:9 H.264: 1280x720 landscape,
     * 720x1280 portrait), NOT a 720x576 file. Noise/grade values are the inherited CCD look;
     * PAL colour, frame rate and interlace come in later stages.
     */
    val HC90E_PAL = CameraProfile(
        id = "hc90e_pal",
        displayName = "HC90E PAL",
        shader = ShaderParams(
            ccdX = 720f,
            ccdY = 576f,
            lines = 576f,
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
            hBlur = 0.5f,
            chroma420 = 1f,
        ),
    )
}
