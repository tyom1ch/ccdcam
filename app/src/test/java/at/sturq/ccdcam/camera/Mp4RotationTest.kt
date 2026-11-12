package at.sturq.ccdcam.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class Mp4RotationTest {

    private class Mem(var data: ByteArray) : ByteStore {
        override val size get() = data.size.toLong()
        override fun read(position: Long, dst: ByteArray) { System.arraycopy(data, position.toInt(), dst, 0, dst.size) }
        override fun write(position: Long, src: ByteArray) { System.arraycopy(src, 0, data, position.toInt(), src.size) }
    }

    private fun box(type: String, payload: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        val n = payload.size + 8
        o.write(byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()))
        o.write(type.toByteArray(Charsets.ISO_8859_1)); o.write(payload)
        return o.toByteArray()
    }

    /** tkhd v0 with identity matrix; [video] sets non-zero width/height. */
    private fun tkhd(video: Boolean): ByteArray {
        val p = ByteArray(4 + 20 + 16 + 36 + 8)
        System.arraycopy(Mp4Rotation.matrixFor(0), 0, p, 40, 36)
        if (video) { p[76] = 0x02; p[77] = 0xD0.toByte(); p[80] = 0x05 }
        return box("tkhd", p)
    }

    private fun file(): ByteArray {
        val audio = box("trak", tkhd(false) + box("mdia", ByteArray(12)))
        val video = box("trak", tkhd(true) + box("mdia", ByteArray(12)))
        return box("ftyp", ByteArray(16)) + box("mdat", ByteArray(100)) +
            box("moov", box("mvhd", ByteArray(100)) + audio + video)
    }

    private fun matrixAt(data: ByteArray, tkhdIndex: Int): ByteArray {
        var from = 0
        repeat(tkhdIndex) { from = String(data, Charsets.ISO_8859_1).indexOf("tkhd", from) + 4 }
        val t = String(data, Charsets.ISO_8859_1).indexOf("tkhd", from)
        return data.copyOfRange(t + 4 + 40, t + 4 + 76)
    }

    @Test
    fun patchesOnlyTheVideoTrackAndKeepsTheSize() {
        val m = Mem(file())
        val before = m.data.size
        assertTrue(Mp4Rotation.setVideoRotation(m, 90))
        assertEquals(before, m.data.size)
        assertArrayEquals(Mp4Rotation.matrixFor(0), matrixAt(m.data, 0))    // audio untouched
        assertArrayEquals(Mp4Rotation.matrixFor(90), matrixAt(m.data, 1))
    }

    @Test
    fun matrixValuesMatchMediaMuxerOrientationHints() {
        fun ints(b: ByteArray) = (0 until 9).map {
            ((b[it * 4].toInt() and 0xFF) shl 24) or ((b[it * 4 + 1].toInt() and 0xFF) shl 16) or
                ((b[it * 4 + 2].toInt() and 0xFF) shl 8) or (b[it * 4 + 3].toInt() and 0xFF)
        }
        val one = 0x10000
        assertEquals(listOf(one, 0, 0, 0, one, 0, 0, 0, 0x40000000), ints(Mp4Rotation.matrixFor(0)))
        assertEquals(listOf(0, one, 0, -one, 0, 0, 0, 0, 0x40000000), ints(Mp4Rotation.matrixFor(90)))
        assertEquals(listOf(-one, 0, 0, 0, -one, 0, 0, 0, 0x40000000), ints(Mp4Rotation.matrixFor(180)))
        assertEquals(listOf(0, -one, 0, one, 0, 0, 0, 0, 0x40000000), ints(Mp4Rotation.matrixFor(270)))
        assertArrayEquals(Mp4Rotation.matrixFor(270), Mp4Rotation.matrixFor(-90))
    }

    @Test
    fun noMoovOrNoVideoTrackReturnsFalse() {
        assertFalse(Mp4Rotation.setVideoRotation(Mem(box("ftyp", ByteArray(8)) + box("mdat", ByteArray(8))), 90))
        val audioOnly = box("moov", box("trak", tkhd(false)))
        assertFalse(Mp4Rotation.setVideoRotation(Mem(audioOnly), 90))
    }
}
