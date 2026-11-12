package at.sturq.ccdcam.firmware

import org.junit.Assert.assertEquals
import org.junit.Test

class LcdRotationTest {

    // Pixel 9 Pro XL preview view 1008 x 1700, 9:16 region displayed with FIT_CENTER.
    private val region = LcdLayout.displayedRegion(1008f, 1700f, 9f / 16f)
    private val eps = 0.01f

    private fun assertPoint(ex: Float, ey: Float, p: Pair<Float, Float>) {
        assertEquals("x", ex, p.first, eps)
        assertEquals("y", ey, p.second, eps)
    }

    @Test
    fun portraitIsIdentityPlusOffset() {
        val f = LcdLayout.RotatedFrame(region, 0)
        assertEquals(region.width, f.width, eps)
        assertEquals(region.height, f.height, eps)
        assertPoint(region.left, region.top, f.toScreen(0f, 0f))
    }

    @Test
    fun landscapeFrameIs16x9AndTheLcdFillsIt() {
        for (cw in listOf(90, 270)) {
            val f = LcdLayout.RotatedFrame(region, cw)
            assertEquals(region.height, f.width, eps)
            assertEquals(region.width, f.height, eps)
            val lcd = LcdLayout.lcd(Box(0f, 0f, f.width, f.height))
            assertEquals(f.width, lcd.width, 1f)
            assertEquals(f.height, lcd.height, 1f)
        }
    }

    /**
     * OrientationEventListener 90 = "left side at the top": the phone is turned clockwise, so the
     * user's right is the screen's top. The upright display's right edge must land there.
     */
    @Test
    fun phoneTurnedClockwisePutsUprightRightAtScreenTop() {
        val f = LcdLayout.RotatedFrame(region, 90)
        assertPoint(region.centerX, region.top, f.toScreen(f.width, f.height / 2f))
        // Upright top is the screen's left.
        assertPoint(region.left, region.centerY, f.toScreen(f.width / 2f, 0f))
    }

    @Test
    fun phoneTurnedCounterClockwisePutsUprightRightAtScreenBottom() {
        val f = LcdLayout.RotatedFrame(region, 270)
        assertPoint(region.centerX, region.bottom, f.toScreen(f.width, f.height / 2f))
        assertPoint(region.right, region.centerY, f.toScreen(f.width / 2f, 0f))
    }

    @Test
    fun upsideDownMirrorsBothAxes() {
        val f = LcdLayout.RotatedFrame(region, 180)
        assertPoint(region.right, region.bottom, f.toScreen(0f, 0f))
    }

    @Test
    fun touchMappingIsTheInverse() {
        for (cw in listOf(0, 90, 180, 270)) {
            val f = LcdLayout.RotatedFrame(region, cw)
            for ((x, y) in listOf(10f to 20f, f.width / 3f to f.height * 0.8f, f.width to f.height)) {
                val s = f.toScreen(x, y)
                assertPoint(x, y, f.toVirtual(s.first, s.second))
            }
        }
    }
}
