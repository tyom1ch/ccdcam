package at.sturq.ccdcam.profile

import org.junit.Assert.assertEquals
import org.junit.Test

class Chroma420Test {

    private fun near(e: FloatArray, a: FloatArray, eps: Float = 1e-3f) =
        e.indices.forEach { assertEquals("ch $it", e[it], a[it], eps) }

    @Test
    fun bt601RoundTrip() {
        for (rgb in listOf(floatArrayOf(1f, 0f, 0f), floatArrayOf(0.2f, 0.7f, 0.4f), floatArrayOf(0.5f, 0.5f, 0.5f))) {
            val y = Chroma420.luma(rgb[0], rgb[1], rgb[2])
            near(rgb, Chroma420.toRgb(y, Chroma420.cb(rgb[0], rgb[1], rgb[2]), Chroma420.cr(rgb[0], rgb[1], rgb[2])))
        }
    }

    @Test
    fun uniformBlockIsUnchanged() {
        val c = floatArrayOf(0.3f, 0.6f, 0.2f)
        near(c, Chroma420.subsample(c, List(4) { c }))
    }

    @Test
    fun lumaIsKeptChromaIsShared() {
        val red = floatArrayOf(0.9f, 0.1f, 0.1f)
        val grey = floatArrayOf(0.5f, 0.5f, 0.5f)
        val out = Chroma420.subsample(grey, listOf(red, grey, grey, grey))
        // luma of the cell is preserved
        assertEquals(Chroma420.luma(0.5f, 0.5f, 0.5f), Chroma420.luma(out[0], out[1], out[2]), 1e-3f)
        // a quarter of the red cell's colour bleeds into the grey cell
        assertEquals(true, out[0] > out[1] && out[0] > out[2])
    }
}
