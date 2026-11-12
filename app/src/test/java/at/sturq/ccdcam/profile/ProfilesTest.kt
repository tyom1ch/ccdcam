package at.sturq.ccdcam.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProfilesTest {

    /** `const <type> NAME = value;` from ccd.frag, keyed by NAME. */
    private val fragConsts: Map<String, String> by lazy {
        // Gradle runs unit tests with the module directory (app/) as working directory.
        val frag = listOf(
            File("src/main/assets/shaders/ccd.frag"),
            File("app/src/main/assets/shaders/ccd.frag"),
        ).first { it.exists() }
        Regex("""const\s+\w+\s+(\w+)\s*=\s*([^;]+);""")
            .findAll(frag.readText())
            .associate { it.groupValues[1] to it.groupValues[2].trim() }
    }

    private fun float(name: String): Float =
        fragConsts[name]?.toFloat() ?: error("$name not found in ccd.frag")

    private fun vec3(name: String): Triple<Float, Float, Float> {
        val raw = fragConsts[name] ?: error("$name not found in ccd.frag")
        val parts = raw.removePrefix("vec3(").removeSuffix(")").split(",").map { it.trim().toFloat() }
        return Triple(parts[0], parts[1], parts[2])
    }

    @Test
    fun ccdcamDefaultMatchesShaderConstantsExactly() {
        val p = Profiles.CCDCAM_DEFAULT.shader
        assertEquals(float("CCD_X"), p.ccdX)
        assertEquals(float("CCD_Y"), p.ccdY)
        assertEquals(float("LINES"), p.lines)
        assertEquals(float("SMEAR_THRESHOLD"), p.smearThreshold)
        assertEquals(float("SMEAR_STRENGTH"), p.smearStrength)
        assertEquals(float("SMEAR_RANGE"), p.smearRange)
        assertEquals(float("FLARE_THRESHOLD"), p.flareThreshold)
        assertEquals(float("FLARE_RANGE"), p.flareRange)
        assertEquals(float("FLARE_STRENGTH"), p.flareStrength)
        assertEquals(float("CHROMA_NOISE_AMP"), p.chromaNoiseAmp)
        assertEquals(float("LUMA_GRAIN_AMP"), p.lumaGrainAmp)
        assertEquals(float("BLACK_LIFT"), p.blackLift)
        assertEquals(vec3("WARM_GRADE"), p.warmGrade)
        assertEquals(float("SATURATION"), p.saturation)
        assertEquals(float("CONTRAST"), p.contrast)
        assertEquals(float("SCANLINE_AMP"), p.scanlineAmp)
        assertEquals(float("VIGNETTE_STRENGTH"), p.vignetteStrength)
        assertEquals(float("CHROMA_SHIFT"), p.chromaShift)
    }

    @Test
    fun loopSampleCountsStayConst() {
        // Not part of ShaderParams on purpose; guard that they did not change silently.
        assertEquals("8", fragConsts["SMEAR_SAMPLES"])
        assertEquals("5", fragConsts["FLARE_SAMPLES"])
    }

    @Test
    fun ccdcamDefaultKeepsLegacyStretchAndTimecode() {
        // MainActivity.applyAspectLabel() always set stretch = 0.78f; timecode was 30 fps.
        assertEquals(0.78f, Profiles.CCDCAM_DEFAULT.stretch)
        assertEquals(30, Profiles.CCDCAM_DEFAULT.timecodeFps)
    }

    @Test
    fun hc90eIsASeparateProfile() {
        assertNotEquals(Profiles.CCDCAM_DEFAULT.id, Profiles.HC90E_PAL.id)
        assertTrue(Profiles.ALL.contains(Profiles.HC90E_PAL))
        assertEquals(Profiles.ALL.size, Profiles.ALL.map { it.id }.toSet().size)
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
