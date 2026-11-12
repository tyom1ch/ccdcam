package at.sturq.ccdcam.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class EffectGeometryTest {

    private val eps = 1e-4f

    private fun assertPoint(expected: Pair<Float, Float>, actual: Pair<Float, Float>) {
        assertEquals("x", expected.first, actual.first, eps)
        assertEquals("y", expected.second, actual.second, eps)
    }

    private fun geo(w: Int, h: Int, region: Float, cw: Int, picture: Float) =
        EffectGeometry.compute(w, h, region, cw, picture)

    @Test
    fun identityWhenEverythingMatches() {
        val g = geo(1600, 900, 16f / 9f, 0, 16f / 9f)
        assertPoint(0f to 0f, g.effectToBuffer.map(0f, 0f))
        assertPoint(1f to 1f, g.effectToBuffer.map(1f, 1f))
        assertPoint(0.3f to 0.7f, g.effectToBuffer.map(0.3f, 0.7f))
    }

    @Test
    fun pillarbox4x3InLandscape16x9() {
        val g = geo(1920, 1080, 16f / 9f, 0, 4f / 3f)
        // 4:3 inside 16:9 covers 0.75 of the width, centred.
        assertPoint(0.125f to 0.5f, g.effectToBuffer.map(0f, 0.5f))
        assertPoint(0.875f to 0.5f, g.effectToBuffer.map(1f, 0.5f))
        assertPoint(0.5f to 0f, g.effectToBuffer.map(0.5f, 0f))
        assertPoint(0.5f to 1f, g.effectToBuffer.map(0.5f, 1f))
    }

    @Test
    fun sharedRegionIsCentreCroppedFromA4x3Buffer() {
        // StreamSharing parent 4:3, preview+video children 16:9 -> centre crop of the height.
        val g = geo(1440, 1080, 16f / 9f, 0, 16f / 9f)
        assertPoint(0.5f to 0.125f, g.effectToBuffer.map(0.5f, 0f))
        assertPoint(0.5f to 0.875f, g.effectToBuffer.map(0.5f, 1f))
        assertPoint(0f to 0.5f, g.effectToBuffer.map(0f, 0.5f))
    }

    @Test
    fun portraitHorizontalLinesFollowBufferColumns() {
        // Phone upright, back camera: buffer must be rotated 90° CW to be upright.
        val g = geo(1920, 1080, 16f / 9f, 90, 16f / 9f)
        val left = g.effectToBuffer.map(0f, 0.5f)
        val right = g.effectToBuffer.map(1f, 0.5f)
        // A horizontal line of the upright picture is a vertical line (constant x) in the buffer.
        assertEquals(left.first, right.first, eps)
        assertTrue(abs(left.second - right.second) > 0.1f)
        // Rotating the buffer CW brings its left edge to the top: upright "up" = buffer -x.
        val top = g.effectToBuffer.map(0.5f, 1f)
        val bottom = g.effectToBuffer.map(0.5f, 0f)
        assertTrue("up must point to buffer -x", top.first < bottom.first)
        assertEquals(top.second, bottom.second, eps)
    }

    @Test
    fun portraitLetterboxesLandscapePicture() {
        // Upright region is 9:16; a 4:3 picture fits with height (9/16)/(4/3) = 0.421875.
        val g = geo(1920, 1080, 16f / 9f, 90, 4f / 3f)
        val top = g.effectToBuffer.map(0.5f, 1f)
        val bottom = g.effectToBuffer.map(0.5f, 0f)
        assertEquals(0.421875f, abs(top.first - bottom.first), eps)
        assertEquals(0.5f, (top.first + bottom.first) / 2f, eps)
        // Full upright width = full buffer height.
        val left = g.effectToBuffer.map(0f, 0.5f)
        val right = g.effectToBuffer.map(1f, 0.5f)
        assertEquals(1f, abs(right.second - left.second), eps)
    }

    @Test
    fun oppositeRotationsKeepTheSameAxis() {
        for ((a, b) in listOf(0 to 180, 90 to 270)) {
            val ga = geo(1920, 1080, 16f / 9f, a, 4f / 3f)
            val gb = geo(1920, 1080, 16f / 9f, b, 4f / 3f)
            val da = dirOfEffectX(ga)
            val db = dirOfEffectX(gb)
            // Same line direction, opposite sense.
            assertEquals(-da.first, db.first, eps)
            assertEquals(-da.second, db.second, eps)
        }
    }

    private fun dirOfEffectX(g: EffectGeometry): Pair<Float, Float> {
        val p0 = g.effectToBuffer.map(0.5f, 0.5f)
        val p1 = g.effectToBuffer.map(1f, 0.5f)
        return (p1.first - p0.first) to (p1.second - p0.second)
    }

    /** No deformation: in buffer *pixels* the picture's x and y unit vectors are perpendicular
     *  and their length ratio is exactly the picture aspect, for every combination. */
    @Test
    fun noDeformationInPixelSpace() {
        val buffers = listOf(1920 to 1080, 1440 to 1080, 1080 to 1920, 1000 to 1000)
        val regions = listOf(16f / 9f, 4f / 3f, 1f)
        val pictures = listOf(16f / 9f, 4f / 3f)
        for ((w, h) in buffers) for (r in regions) for (cw in listOf(0, 90, 180, 270)) for (p in pictures) {
            val g = geo(w, h, r, cw, p)
            val o = g.effectToBuffer.map(0f, 0f)
            val x = g.effectToBuffer.map(1f, 0f)
            val y = g.effectToBuffer.map(0f, 1f)
            val vx = (x.first - o.first) * w to (x.second - o.second) * h
            val vy = (y.first - o.first) * w to (y.second - o.second) * h
            val dot = vx.first * vy.first + vx.second * vy.second
            val lx = hypot(vx.first, vx.second)
            val ly = hypot(vy.first, vy.second)
            val ctx = "buffer=${w}x$h region=$r cw=$cw picture=$p"
            assertEquals("perpendicular $ctx", 0f, dot / (lx * ly), 1e-4f)
            assertEquals("aspect $ctx", p, lx / ly, 1e-3f)
        }
    }

    /** The picture never leaves the shared region (nothing visible is cut by the crops). */
    @Test
    fun pictureStaysInsideSharedRegion() {
        for (cw in listOf(0, 90, 180, 270)) for (p in listOf(16f / 9f, 4f / 3f)) {
            val g = geo(1440, 1080, 16f / 9f, cw, p)
            for ((ex, ey) in listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f)) {
                val (bx, by) = g.effectToBuffer.map(ex, ey)
                assertTrue("x in buffer cw=$cw", bx in -eps..1f + eps)
                assertTrue("y in region cw=$cw ($by)", by in 0.125f - eps..0.875f + eps)
            }
        }
    }

    /** HC90E stage 4: the picture is fitted into the emulated 16:9 LCD band first. */
    @Test
    fun lcdContainerInPortraitPillarboxes4x3InsideA16x9Band() {
        val g = EffectGeometry.compute(1920, 1080, 16f / 9f, 90, 4f / 3f, containerAspect = 16f / 9f)
        // Upright region 9:16 -> 16:9 band covers (9/16)/(16/9) = 0.31640625 of its height,
        // the 4:3 picture covers 0.75 of the band's width.
        val top = g.effectToBuffer.map(0.5f, 1f)
        val bottom = g.effectToBuffer.map(0.5f, 0f)
        assertEquals(0.31640625f, abs(top.first - bottom.first), eps)
        val left = g.effectToBuffer.map(0f, 0.5f)
        val right = g.effectToBuffer.map(1f, 0.5f)
        assertEquals(0.75f, abs(right.second - left.second), eps)
    }

    @Test
    fun lcdContainerChangesNothingInLandscape() {
        for (p in listOf(16f / 9f, 4f / 3f)) {
            val a = EffectGeometry.compute(1440, 1080, 16f / 9f, 0, p)
            val b = EffectGeometry.compute(1440, 1080, 16f / 9f, 0, p, containerAspect = 16f / 9f)
            assertEquals(a.effectToBuffer.toGlMat3().toList(), b.effectToBuffer.toGlMat3().toList())
        }
    }

    @Test
    fun noDeformationWithLcdContainer() {
        for (cw in listOf(0, 90, 180, 270)) for (p in listOf(16f / 9f, 4f / 3f)) {
            val w = 1440; val h = 1080
            val g = EffectGeometry.compute(w, h, 16f / 9f, cw, p, containerAspect = 16f / 9f)
            val o = g.effectToBuffer.map(0f, 0f)
            val x = g.effectToBuffer.map(1f, 0f)
            val y = g.effectToBuffer.map(0f, 1f)
            val lx = hypot((x.first - o.first) * w, (x.second - o.second) * h)
            val ly = hypot((y.first - o.first) * w, (y.second - o.second) * h)
            assertEquals("cw=$cw p=$p", p, lx / ly, 1e-3f)
        }
    }

    @Test
    fun inverseRoundTrips() {
        val g = geo(1440, 1080, 16f / 9f, 270, 4f / 3f)
        for ((x, y) in listOf(0.1f to 0.2f, 0.5f to 0.5f, 0.9f to 0.33f)) {
            val (bx, by) = g.effectToBuffer.map(x, y)
            assertPoint(x to y, g.bufferToEffect.map(bx, by))
        }
    }

    @Test
    fun glMat3IsColumnMajor() {
        val m = Affine(1f, 2f, 3f, 4f, 5f, 6f).toGlMat3()
        // column 0 = (a, c, 0), column 1 = (b, d, 0), column 2 = (tx, ty, 1)
        assertEquals(listOf(1f, 3f, 0f, 2f, 4f, 0f, 5f, 6f, 1f), m.toList())
    }

    @Test
    fun rotationFromAndroidMatrixValues() {
        // android.graphics.Matrix value order: MSCALE_X, MSKEW_X, MTRANS_X, MSKEW_Y, MSCALE_Y, ...
        fun m(sx: Float, kx: Float, ky: Float, sy: Float) = floatArrayOf(sx, kx, 0f, ky, sy, 0f, 0f, 0f, 1f)
        assertEquals(0, EffectGeometry.rotationCwDegreesOf(m(2f, 0f, 0f, 2f)))
        // 90° CW in y-down pixels: x unit -> (0, 1)
        assertEquals(90, EffectGeometry.rotationCwDegreesOf(m(0f, -1f, 1f, 0f)))
        assertEquals(180, EffectGeometry.rotationCwDegreesOf(m(-1f, 0f, 0f, -1f)))
        assertEquals(270, EffectGeometry.rotationCwDegreesOf(m(0f, 1f, -1f, 0f)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonQuarterRotation() {
        geo(100, 100, 1f, 45, 1f)
    }
}
