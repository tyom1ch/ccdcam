package at.sturq.ccdcam.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilesTest {

    @Test
    fun hc90ePalSpatialValues() {
        val p = Profiles.HC90E_PAL.shader
        // PAL DV active picture grid and line count (docs/hc90e-reference.md).
        assertEquals(720f, p.ccdX)
        assertEquals(576f, p.ccdY)
        assertEquals(576f, p.lines)
        assertTrue(p.hBlur > 0f)
        // PAL DV samples colour 4:2:0
        assertEquals(1f, p.chroma420)
    }

    /** Guards the look against accidental edits; change together with tools/sim.py. */
    @Test
    fun hc90eLookValues() {
        val p = Profiles.HC90E_PAL.shader
        assertEquals(0.0f, p.smearStrength)
        assertEquals(0.90f, p.flareThreshold)
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
    }

    @Test
    fun identity() {
        assertEquals("hc90e_pal", Profiles.HC90E_PAL.id)
        assertEquals("HC90E PAL", Profiles.HC90E_PAL.displayName)
    }
}
