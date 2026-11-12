package at.sturq.ccdcam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameRateTest {

    @Test
    fun choosesOnlyAFixed25Range() {
        assertEquals(25 to 25, FrameRatePlan.choose(listOf(15 to 30, 25 to 25, 30 to 30)))
        // Variable ranges that contain 25 are not CFR: rejected.
        assertNull(FrameRatePlan.choose(listOf(15 to 25, 24 to 30, 30 to 30)))
        assertNull(FrameRatePlan.choose(emptyList()))
    }

    @Test
    fun describeIsSorted() {
        assertEquals("[25,25] [15,30] [30,30]", FrameRatePlan.describe(listOf(30 to 30, 15 to 30, 25 to 25)))
    }

    private fun feed(meter: FrameRateMeter, intervalNs: Long, frames: Int, start: Long = 1_000_000L) {
        var t = start
        repeat(frames) { meter.onFrame(t); t += intervalNs }
    }

    @Test
    fun measuresConstant25And30() {
        val m25 = FrameRateMeter()
        feed(m25, 40_000_000L, 40)
        assertEquals(25, m25.roundedFps())
        val m30 = FrameRateMeter()
        feed(m30, 33_333_333L, 40)
        assertEquals(30, m30.roundedFps())
    }

    @Test
    fun medianIgnoresASingleLateFrame() {
        val m = FrameRateMeter()
        var t = 0L
        repeat(40) { i -> m.onFrame(t); t += if (i == 20) 200_000_000L else 40_000_000L }
        assertEquals(25, m.roundedFps())
    }

    @Test
    fun needsEnoughFramesAndResets() {
        val m = FrameRateMeter()
        feed(m, 40_000_000L, 5)
        assertNull(m.fps())
        feed(m, 40_000_000L, 40, start = 10_000_000_000L)
        m.reset()
        assertNull(m.fps())
    }
}
