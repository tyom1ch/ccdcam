package at.sturq.ccdcam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A uniform name typo would not fail at runtime: glGetUniformLocation returns -1, the value
 * stays 0 and the look silently breaks. These checks catch that at build time.
 */
class ShaderUniformsTest {

    private fun shader(name: String): String = listOf(
        File("src/main/assets/shaders/$name"),       // Gradle: module dir is the working dir
        File("app/src/main/assets/shaders/$name"),
    ).first { it.exists() }.readText()

    private val frag by lazy { shader("ccd.frag") }
    private val vert by lazy { shader("passthrough.vert") }

    private fun uniformsOf(src: String): Map<String, String> =
        Regex("""uniform\s+(\w+)\s+(\w+)\s*;""").findAll(src)
            .associate { it.groupValues[2] to it.groupValues[1] }

    /** Uniforms set directly by CcdSurfaceProcessor, not through LookUniforms. */
    private val processorUniforms = setOf(
        "sTexture", "uResolution", "uTime", "uTexMatrix", "uBufferToEffect", "uEffectToBuffer",
        "uNightShot", "uFade", "uFadeWhite",
    )

    @Test
    fun everyLookUniformIsDeclaredInFrag() {
        val declared = uniformsOf(frag)
        for (name in LookUniforms.NAMES) {
            assertTrue("$name missing in ccd.frag", name in declared)
        }
        assertEquals("vec3", declared["uWarmGrade"])
    }

    @Test
    fun everyFragUniformIsSetBySomeone() {
        val unset = uniformsOf(frag).keys - LookUniforms.NAMES.toSet() - processorUniforms
        assertTrue("uniforms nobody sets: $unset", unset.isEmpty())
    }

    @Test
    fun uniformsSharedBetweenStagesHaveSameType() {
        val v = uniformsOf(vert)
        val f = uniformsOf(frag)
        for (name in v.keys intersect f.keys) {
            assertEquals("type mismatch for $name", v[name], f[name])
        }
    }

    @Test
    fun onlyLoopBoundsRemainConst() {
        val consts = Regex("""const\s+\w+\s+(\w+)\s*=""").findAll(frag).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("SMEAR_SAMPLES", "FLARE_SAMPLES"), consts)
    }

    @Test
    fun osdShadersDeclareWhatTheRendererBinds() {
        val v = shader("osd.vert")
        val f = shader("osd.frag")
        for (a in listOf("aPos", "aUv", "aColor")) assertTrue(a, Regex("""attribute\s+\w+\s+$a\s*;""").containsMatchIn(v))
        assertEquals("mat3", uniformsOf(v)["uLcdToBuffer"])
        assertEquals("sampler2D", uniformsOf(f)["sAtlas"])
    }

    @Test
    fun weaveShaderDeclaresWhatTheInterlacerBinds() {
        val f = shader("weave.frag")
        val u = uniformsOf(f)
        assertEquals("sampler2D", u["sCur"])
        assertEquals("sampler2D", u["sPrev"])
        assertEquals("mat3", u["uBufferToEffect"])
        assertEquals("float", u["uLines"])
        assertEquals("float", u["uBlend"])
    }

    @Test
    fun superNightShotHoldsThreeOfFourFrames() {
        val held = (0L until 8L).map { Interlacer.isHeldFrame(it) }
        assertEquals(listOf(false, true, true, true, false, true, true, true), held)
    }

    @Test
    fun fieldParityAlternatesPerPictureLine() {
        val lines = 576f
        assertEquals(0, Interlacer.fieldOf(0.5f / lines, lines))
        assertEquals(1, Interlacer.fieldOf(1.5f / lines, lines))
        assertEquals(0, Interlacer.fieldOf(2.5f / lines, lines))
        assertEquals(1, Interlacer.fieldOf(575.5f / lines, lines))
    }

    @Test
    fun pictureSpaceVaryingIsWired() {
        assertTrue(Regex("""varying\s+vec2\s+vEffectUv\s*;""").containsMatchIn(vert))
        assertTrue(Regex("""varying\s+vec2\s+vEffectUv\s*;""").containsMatchIn(frag))
        assertTrue(vert.contains("vEffectUv = (uBufferToEffect * vec3(aTexCoord.xy, 1.0)).xy;"))
        assertEquals("mat3", uniformsOf(vert)["uBufferToEffect"])
        assertEquals("mat3", uniformsOf(frag)["uEffectToBuffer"])
    }

    @Test
    fun everyVertUniformIsSetByTheProcessor() {
        val unset = uniformsOf(vert).keys - processorUniforms
        assertTrue("vertex uniforms nobody sets: $unset", unset.isEmpty())
    }
}
