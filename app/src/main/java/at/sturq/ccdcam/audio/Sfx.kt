package at.sturq.ccdcam.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal WAV reader for the app's own PCM 16-bit resources (any channel count -> mono). */
object WavPcm {
    class Mono(val rate: Int, val samples: FloatArray)

    fun parseMono(bytes: ByteArray): Mono {
        require(bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE") { "not a WAV file" }
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var p = 12
        var channels = 0; var rate = 0; var bits = 0; var tag = 0
        var dataOff = -1; var dataLen = 0
        while (p + 8 <= bytes.size) {
            val id = String(bytes, p, 4, Charsets.US_ASCII)
            val size = b.getInt(p + 4)
            if (id == "fmt ") {
                tag = b.getShort(p + 8).toInt(); channels = b.getShort(p + 10).toInt()
                rate = b.getInt(p + 12); bits = b.getShort(p + 22).toInt()
            } else if (id == "data") {
                dataOff = p + 8; dataLen = minOf(size, bytes.size - dataOff)
            }
            p += 8 + size + (size and 1)
        }
        require(tag == 1 && bits == 16 && channels > 0 && dataOff >= 0) { "need PCM 16-bit WAV" }
        val frames = dataLen / 2 / channels
        val out = FloatArray(frames) { f ->
            var s = 0f
            for (c in 0 until channels) s += b.getShort(dataOff + (f * channels + c) * 2) / 32768f
            s / channels
        }
        return Mono(rate, out)
    }
}

/**
 * Mixes the recorder sound effects into a clip's PCM before the camcorder chain (so the AGC
 * reacts to them like to real motor noise and button beeps): the tape mechanism loop under the
 * whole clip, and the UI sounds that played while recording, at their offsets.
 */
class SfxMixer(
    private val loop: FloatArray?,
    private val loopGain: Float,
    private val events: List<Event>,
    private val eventGain: Float,
) {
    class Event(val pcm: FloatArray, val startFrame: Long)

    private var frame = 0L

    val isEmpty: Boolean get() = (loop == null || loop.isEmpty()) && events.isEmpty()

    /** Adds the effects to interleaved [chunk] in place; consecutive calls continue in time. */
    fun mix(chunk: FloatArray, channels: Int) {
        val frames = chunk.size / channels
        for (f in 0 until frames) {
            val t = frame + f
            var s = 0f
            if (loop != null && loop.isNotEmpty()) s += loop[(t % loop.size).toInt()] * loopGain
            for (e in events) {
                val k = t - e.startFrame
                if (k >= 0 && k < e.pcm.size) s += e.pcm[k.toInt()] * eventGain
            }
            if (s != 0f) for (c in 0 until channels) chunk[f * channels + c] += s
        }
        frame += frames
    }

    companion object {
        /** Tape loop is stored at -30 dBFS RMS; -12 dB puts the motor ~-42 dBFS under the audio. */
        val LOOP_GAIN = AudioMath.dbToLin(-41f)
        /** UI sounds are stored at -3 dBFS peak; burned in ~10 dB lower, like through the mic. */
        val EVENT_GAIN = AudioMath.dbToLin(-10f)
    }
}
