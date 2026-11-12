package at.sturq.ccdcam.camera

/** Random access to an MP4 file (abstracted so the box walk is unit-testable). */
interface ByteStore {
    val size: Long
    fun read(position: Long, dst: ByteArray)
    fun write(position: Long, src: ByteArray)
}

/**
 * Sets the display rotation of the video track of an MP4 by rewriting the 3x3 matrix in its
 * `tkhd` box in place (36 bytes, file size unchanged). The app keeps the CameraX video pipeline
 * in one fixed orientation and draws picture + OSD upright for the way the phone is held, so it
 * also has to tell players how to turn the frame — deterministically, instead of relying on
 * CameraX's rotation handling, which was inconsistent with the frozen framing.
 */
object Mp4Rotation {

    /** @return true if a video track was found and patched. */
    fun setVideoRotation(store: ByteStore, cwDegrees: Int): Boolean {
        val matrix = matrixFor(cwDegrees)
        val moov = findBox(store, 0, store.size, "moov") ?: return false
        var pos = moov.first
        while (true) {
            val trak = findBox(store, pos, moov.second, "trak") ?: return false
            val tkhd = findBox(store, trak.first, trak.second, "tkhd")
            if (tkhd != null) {
                val version = ByteArray(1).also { store.read(tkhd.first, it) }[0].toInt()
                val matrixPos = tkhd.first + 4 + (if (version == 1) 32 else 20) + 16
                val dims = ByteArray(8).also { store.read(matrixPos + 36, it) }
                if (dims.any { it != 0.toByte() }) {          // width/height != 0: the video track
                    store.write(matrixPos, matrix)
                    return true
                }
            }
            pos = trak.second
        }
    }

    /** MP4 track matrix (a b u / c d v / x y w, 16.16 and 2.30 fixed point) for a CW rotation. */
    fun matrixFor(cwDegrees: Int): ByteArray {
        val (a, b, c, d) = when (((cwDegrees % 360) + 360) % 360) {
            0 -> listOf(1, 0, 0, 1)
            90 -> listOf(0, 1, -1, 0)
            180 -> listOf(-1, 0, 0, -1)
            270 -> listOf(0, -1, 1, 0)
            else -> throw IllegalArgumentException("rotation must be a multiple of 90")
        }
        val out = ByteArray(36)
        fun put(i: Int, v: Int) {
            out[i] = (v ushr 24).toByte(); out[i + 1] = (v ushr 16).toByte()
            out[i + 2] = (v ushr 8).toByte(); out[i + 3] = v.toByte()
        }
        put(0, a shl 16); put(4, b shl 16); put(12, c shl 16); put(16, d shl 16)
        put(32, 0x40000000)
        return out
    }

    /** First box of [type] in [start, end): (payload start, box end), or null. */
    private fun findBox(store: ByteStore, start: Long, end: Long, type: String): Pair<Long, Long>? {
        var pos = start
        val head = ByteArray(8)
        while (pos + 8 <= end) {
            store.read(pos, head)
            var size = u32(head, 0)
            var header = 8L
            if (size == 1L) {
                val big = ByteArray(8).also { store.read(pos + 8, it) }
                size = (u32(big, 0) shl 32) or u32(big, 4)
                header = 16L
            } else if (size == 0L) {
                size = end - pos
            }
            if (size < header) return null
            if (String(head, 4, 4, Charsets.ISO_8859_1) == type) return Pair(pos + header, pos + size)
            pos += size
        }
        return null
    }

    private fun u32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)
}
