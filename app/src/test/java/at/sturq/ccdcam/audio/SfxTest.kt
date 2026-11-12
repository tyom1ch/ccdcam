package at.sturq.ccdcam.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SfxTest {

    private fun wav(rate: Int, channels: Int, samples: ShortArray): ByteArray {
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { data.putShort(it) }
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + samples.size * 2); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(channels.toShort())
        h.putInt(rate); h.putInt(rate * channels * 2); h.putShort((channels * 2).toShort()); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(samples.size * 2)
        return ByteArrayOutputStream().apply { write(h.array()); write(data.array()) }.toByteArray()
    }

    @Test
    fun wavStereoIsAveragedToMono() {
        val m = WavPcm.parseMono(wav(48_000, 2, shortArrayOf(16384, 0, -16384, -16384)))
        assertEquals(48_000, m.rate)
        assertEquals(2, m.samples.size)
        assertEquals(0.25f, m.samples[0], 1e-4f)
        assertEquals(-0.5f, m.samples[1], 1e-4f)
    }

    @Test
    fun importedResourcesAreMono48kPcm() {
        val dir = listOf(File("src/main/res/raw"), File("app/src/main/res/raw")).first { it.exists() }
        for (n in listOf("ui_click", "ui_declick", "alert", "power_on", "cassette_eject", "cassette_insert", "tape_loop")) {
            val m = WavPcm.parseMono(File(dir, "$n.wav").readBytes())
            assertEquals(n, 48_000, m.rate)
            assertTrue(n, m.samples.isNotEmpty() && m.samples.all { kotlin.math.abs(it) <= 1f })
        }
    }

    @Test
    fun loopWrapsAndEventsLandAtTheirOffset() {
        val mixer = SfxMixer(floatArrayOf(0.1f, 0.2f), 1f, listOf(SfxMixer.Event(floatArrayOf(0.5f, 0.5f), 3)), 1f)
        val a = FloatArray(4)          // frames 0..3, mono
        mixer.mix(a, 1)
        val b = FloatArray(4)          // frames 4..7 continue in time
        mixer.mix(b, 1)
        org.junit.Assert.assertArrayEquals(floatArrayOf(0.1f, 0.2f, 0.1f, 0.7f), a, 1e-6f)
        org.junit.Assert.assertArrayEquals(floatArrayOf(0.6f, 0.2f, 0.1f, 0.2f), b, 1e-6f)
    }

    @Test
    fun stereoChunksGetTheEffectOnBothChannels() {
        val mixer = SfxMixer(null, 1f, listOf(SfxMixer.Event(floatArrayOf(0.3f), 1)), 1f)
        val x = FloatArray(4)          // 2 frames, stereo
        mixer.mix(x, 2)
        org.junit.Assert.assertArrayEquals(floatArrayOf(0f, 0f, 0.3f, 0.3f), x, 1e-6f)
        assertTrue(SfxMixer(null, 1f, emptyList(), 1f).isEmpty)
    }
}
