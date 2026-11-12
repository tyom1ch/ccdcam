package at.sturq.ccdcam.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class AudioDspTest {

    private fun sine(freq: Double, fs: Int, seconds: Double, amp: Float = 0.5f) =
        FloatArray((fs * seconds).toInt()) { (amp * sin(2 * PI * freq * it / fs)).toFloat() }

    private fun rms(x: FloatArray, from: Int = x.size / 2) =
        sqrt(x.drop(from).sumOf { (it * it).toDouble() } / (x.size - from)).toFloat()

    @Test
    fun lowPassCutsHighsHighPassCutsLows() {
        val lp = Biquad.lowPass(3_000f, 48_000)
        val hi = sine(12_000.0, 48_000, 0.2).map { lp.process(it) }.toFloatArray()
        assertTrue(rms(hi) < 0.05f)
        val hp = Biquad.highPass(180f, 48_000)
        val dc = FloatArray(48_000) { 0.5f }.map { hp.process(it) }.toFloatArray()
        assertTrue(abs(dc.last()) < 0.01f)
        val pass = Biquad.lowPass(3_000f, 48_000)
        val lo = sine(300.0, 48_000, 0.2).map { pass.process(it) }.toFloatArray()
        assertEquals(rms(sine(300.0, 48_000, 0.2)), rms(lo), 0.03f)
    }

    @Test
    fun agcRaisesQuietSignalsWithinItsLimit() {
        val agc = Agc(48_000, -20f, 18f, 50f, 1_500f)
        val quiet = sine(1_000.0, 48_000, 4.0, amp = 0.01f).map { agc.process(it) }.toFloatArray()
        val gainDb = 20 * kotlin.math.log10(rms(quiet) / rms(sine(1_000.0, 48_000, 4.0, amp = 0.01f)))
        assertTrue("gain $gainDb", gainDb > 6f && gainDb <= 18.5f)
    }

    @Test
    fun softClipIsBoundedAndUnityAtFullScale() {
        for (d in listOf(1.2f, 2f)) {
            assertEquals(1f, AudioMath.softClip(1f, d), 1e-6f)
            assertTrue(abs(AudioMath.softClip(5f, d)) <= 1f)
            assertTrue(AudioMath.softClip(0.1f, d) > 0f)
        }
    }

    @Test
    fun twelveBitIsFinerForQuietThanForLoud() {
        val quietErr = abs(AudioMath.quantize12(0.001f) - 0.001f)
        val loudErr = abs(AudioMath.quantize12(0.9f) - 0.9f)
        assertTrue(quietErr < 0.0005f)
        assertTrue(loudErr < 0.01f)
        assertEquals(0f, AudioMath.quantize12(0f), 0f)
        assertEquals(-AudioMath.quantize12(0.3f), AudioMath.quantize12(-0.3f), 1e-6f)
    }

    @Test
    fun resamplerKeepsDurationAndPitchAcrossChunks() {
        val inp = sine(1_000.0, 48_000, 1.0)
        val r = Resampler(48_000, 32_000, 1)
        val out = (0 until 10).flatMap { k -> r.process(inp.copyOfRange(k * 4800, (k + 1) * 4800)).toList() }
        assertEquals(32_000.0, out.size.toDouble(), 2.0)
        // 1 kHz at 32 kHz: 32 samples per period -> zero crossings every 16 samples
        val crossings = out.zipWithNext().count { (a, b) -> a <= 0f && b > 0f }
        assertEquals(1_000.0, crossings.toDouble(), 3.0)
    }

    @Test
    fun cleanIsBypassAndProfilesKeepLevelsSane() {
        val x = sine(440.0, 48_000, 0.5)
        assertArrayEquals(x, AudioChain(AudioProfile.CLEAN, 48_000, 1).process(x), 0f)
        for (p in listOf(AudioProfile.DV16_48K, AudioProfile.DV12_32K, AudioProfile.LOFI)) {
            val c = AudioChain(p, 48_000, 1)
            val y = c.process(x)
            assertEquals("$p length", x.size.toDouble() * p.outRate / 48_000, y.size.toDouble(), 2.0)
            assertTrue("$p bounded", y.all { abs(it) <= 1f })
            assertTrue("$p not silent", rms(y) > 0.01f)
        }
    }

    @Test
    fun lofiIsBandLimitedAndHissier() {
        val silence = FloatArray(48_000)
        val dv = rms(AudioChain(AudioProfile.DV16_48K, 48_000, 1).process(silence))
        val lofi = rms(AudioChain(AudioProfile.LOFI, 48_000, 1).process(silence))
        assertTrue("lofi hiss $lofi > dv hiss $dv", lofi > dv)
        // 1 kHz + 10 kHz: the 10 kHz share must be much smaller after the lo-fi mic.
        val mix = FloatArray(48_000) { i ->
            (0.2 * sin(2 * PI * 1_000 * i / 48_000.0) + 0.2 * sin(2 * PI * 10_000 * i / 48_000.0)).toFloat()
        }
        fun ratio(p: AudioProfile): Double {
            val y = AudioChain(p, 48_000, 1).process(mix)
            val fs = p.outRate
            return goertzel(y, 10_000.0, fs) / goertzel(y, 1_000.0, fs)
        }
        assertTrue("lo-fi ${ratio(AudioProfile.LOFI)} < dv ${ratio(AudioProfile.DV16_48K)}",
            ratio(AudioProfile.LOFI) < 0.5 * ratio(AudioProfile.DV16_48K))
    }

    /** Magnitude of [freq] in the second half of [x] (Goertzel). */
    private fun goertzel(x: FloatArray, freq: Double, fs: Int): Double {
        val k = 2 * kotlin.math.cos(2 * PI * freq / fs)
        var s1 = 0.0; var s2 = 0.0
        for (i in x.size / 2 until x.size) { val s0 = x[i] + k * s1 - s2; s2 = s1; s1 = s0 }
        return sqrt(s1 * s1 + s2 * s2 - k * s1 * s2)
    }

    @Test
    fun stereoChannelsAreProcessedIndependently() {
        val n = 4_800
        val st = FloatArray(n * 2) { i -> if (i % 2 == 0) (0.5f * sin(2 * PI * 440 * (i / 2) / 48_000.0)).toFloat() else 0f }
        val y = AudioChain(AudioProfile.DV16_48K, 48_000, 2).process(st)
        val left = y.filterIndexed { i, _ -> i % 2 == 0 }.toFloatArray()
        val right = y.filterIndexed { i, _ -> i % 2 == 1 }.toFloatArray()
        assertTrue(rms(left) > 5 * rms(right))
    }
}
