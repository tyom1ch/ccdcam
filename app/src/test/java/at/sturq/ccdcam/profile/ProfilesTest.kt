package at.sturq.ccdcam.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilesTest {

    /**
     * Golden values: the `const` block of ccd.frag as it was before stage 2 turned it into
     * uniforms (upstream commit 735bad7). CCDCAM_DEFAULT must keep rendering exactly this.
     */
    @Test
    fun ccdcamDefaultMatchesOriginalShaderConstants() {
        val p = Profiles.CCDCAM_DEFAULT.shader
        assertEquals(720.0f, p.ccdX)
        assertEquals(720.0f, p.ccdY)
        assertEquals(480.0f, p.lines)
        assertEquals(0.86f, p.smearThreshold)
        assertEquals(0.0f, p.smearStrength)
        assertEquals(0.38f, p.smearRange)
        assertEquals(0.90f, p.flareThreshold)
        assertEquals(0.14f, p.flareRange)
        assertEquals(0.30f, p.flareStrength)
        assertEquals(0.02f, p.chromaNoiseAmp)
        assertEquals(0.05f, p.lumaGrainAmp)
        assertEquals(0.01f, p.blackLift)
        assertEquals(Triple(1.05f, 1.01f, 0.98f), p.warmGrade)
        assertEquals(0.98f, p.saturation)
        assertEquals(1.15f, p.contrast)
        assertEquals(0.025f, p.scanlineAmp)
        assertEquals(0.35f, p.vignetteStrength)
        assertEquals(0.0003f, p.chromaShift)
        // Added in stage 3; 0 keeps the original single-sample path.
        assertEquals(0f, p.hBlur)
    }

    @Test
    fun ccdcamDefaultKeepsLegacyBehaviour() {
        val p = Profiles.CCDCAM_DEFAULT
        // Effects in camera texture space, exactly like the original shader.
        assertEquals(EffectSpace.TEXTURE, p.effectSpace)
        assertEquals(0f, p.effectSpace.uniformValue)
        // MainActivity.applyAspectLabel() always set stretch = 0.78f; timecode was 30 fps.
        assertEquals(0.78f, p.stretch)
        assertEquals(30, p.timecodeFps)
        // Original layout text of the bottom-left HUD label.
        assertEquals("CCD CAM", p.hudLabel)
    }

    @Test
    fun hc90eIsASeparateProfileInPictureSpace() {
        assertNotEquals(Profiles.CCDCAM_DEFAULT.id, Profiles.HC90E_PAL.id)
        assertTrue(Profiles.ALL.contains(Profiles.HC90E_PAL))
        assertEquals(Profiles.ALL.size, Profiles.ALL.map { it.id }.toSet().size)
        assertEquals(EffectSpace.PICTURE, Profiles.HC90E_PAL.effectSpace)
        assertEquals(1f, Profiles.HC90E_PAL.effectSpace.uniformValue)
    }

    @Test
    fun hc90ePalSpatialValues() {
        val p = Profiles.HC90E_PAL
        // PAL DV active picture grid and line count (docs/hc90e-reference.md).
        assertEquals(720f, p.shader.ccdX)
        assertEquals(576f, p.shader.ccdY)
        assertEquals(576f, p.shader.lines)
        assertTrue(p.shader.hBlur > 0f)
        // No anamorphic squish: geometry must not deform objects.
        assertEquals(1.0f, p.stretch)
        // Real capture is still 30 fps; 25 fps timecode waits for the frame-rate stage.
        assertEquals(30, p.timecodeFps)
    }

    @Test
    fun byIdFallsBackToDefault() {
        assertSame(Profiles.CCDCAM_DEFAULT, Profiles.DEFAULT)
        assertSame(Profiles.DEFAULT, Profiles.byId(null))
        assertSame(Profiles.DEFAULT, Profiles.byId("no-such-profile"))
        assertSame(Profiles.HC90E_PAL, Profiles.byId("hc90e_pal"))
        assertSame(Profiles.CCDCAM_DEFAULT, Profiles.byId("ccdcam"))
    }
}
