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

    private fun convert(inPeriodNs: Long, seconds: Int): List<Long> {
        val d = FrameDecimator()
        val out = ArrayList<Long>()
        var t = 5_000_000_000L
        repeat((seconds * 1e9 / inPeriodNs).toInt()) { d.accept(t)?.let(out::add); t += inPeriodNs }
        return out
    }

    @Test
    fun converts30To25ConstantFrameRate() {
        val out = convert(33_333_333L, 10)
        assertEquals(250.0, out.size.toDouble(), 1.0)
        out.zipWithNext { a, b -> assertEquals(40_000_000L, b - a) }
    }

    @Test
    fun converts60To25ConstantFrameRate() {
        val out = convert(16_666_667L, 10)
        assertEquals(250.0, out.size.toDouble(), 1.0)
        out.zipWithNext { a, b -> assertEquals(40_000_000L, b - a) }
    }

    @Test
    fun outputStaysWithin20msOfCapture() {
        val d = FrameDecimator()
        var t = 0L
        repeat(300) {
            d.accept(t)?.let { pts -> assertEquals(true, kotlin.math.abs(pts - t) <= 20_000_000L) }
            t += 33_333_333L
        }
    }

    @Test
    fun droppedCameraFramesAreFilled() {
        val d = FrameDecimator()
        val out = ArrayList<Long>()
        var t = 0L
        repeat(300) { i ->
            if (i % 37 != 20 && i !in 100..101) d.ticks(t).forEach { out += it }   // single + double drops
            t += 33_333_333L
        }
        out.zipWithNext { a, b -> assertEquals(40_000_000L, b - a) }
    }

    @Test
    fun cameraGapKeepsTheGrid() {
        val d = FrameDecimator()
        val a = d.accept(0L)!!
        val b = d.accept(500_000_000L)!!
        assertEquals(0L, (b - a) % 40_000_000L)
        assertEquals(true, kotlin.math.abs(b - 500_000_000L) <= 20_000_000L)
    }
}
