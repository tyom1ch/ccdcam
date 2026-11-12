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
        "sTexture", "uResolution", "uTime", "uTexMatrix", "uStretch", "uRotationRad",
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
    fun outputSpaceVaryingIsWired() {
        assertTrue(Regex("""varying\s+vec2\s+vOutUv\s*;""").containsMatchIn(vert))
        assertTrue(Regex("""varying\s+vec2\s+vOutUv\s*;""").containsMatchIn(frag))
        assertTrue(vert.contains("vOutUv = aTexCoord.xy;"))
    }
}
