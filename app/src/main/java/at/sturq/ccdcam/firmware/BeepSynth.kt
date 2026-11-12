package at.sturq.ccdcam.firmware

import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Synthesized control sounds. The triggers are documented (BEEP: "a melody sounds when you
 * start/stop recording, or operate the touch panel"; warnings sound a melody, guide p. 54/99);
 * the sounds themselves are an approximation — no redistributable recording exists.
 */
enum class Beep(val notes: List<Pair<Double, Int>>) {
    /** Touch-panel key: one short high blip. */
    TOUCH(listOf(2093.0 to 45)),

    /** Recording start: rising two-note figure. */
    REC_START(listOf(1568.0 to 70, 2093.0 to 120)),

    /** Recording stop: falling two-note figure. */
    REC_STOP(listOf(2093.0 to 70, 1568.0 to 120)),

    /** Warning indicator: three quick blips. */
    WARNING(listOf(2349.0 to 60, 0.0 to 50, 2349.0 to 60, 0.0 to 50, 2349.0 to 60)),
}

object BeepSynth {

    const val SAMPLE_RATE = 44_100
    private const val AMPLITUDE = 0.28
    private const val RAMP_MS = 5

    /** 16-bit mono PCM; a frequency of 0 is a rest. Each note has 5 ms fade in/out (no clicks). */
    fun render(beep: Beep, sampleRate: Int = SAMPLE_RATE): ShortArray {
        val total = beep.notes.sumOf { it.second } * sampleRate / 1000
        val out = ShortArray(total)
        var pos = 0
        for ((freq, ms) in beep.notes) {
            val n = ms * sampleRate / 1000
            val ramp = min(n / 2, RAMP_MS * sampleRate / 1000)
            for (i in 0 until n) {
                if (freq > 0) {
                    val env = when {
                        i < ramp -> i.toDouble() / ramp
                        i >= n - ramp -> (n - 1 - i).toDouble() / ramp
                        else -> 1.0
                    }
                    val v = sin(2 * PI * freq * i / sampleRate) * AMPLITUDE * env
                    out[pos + i] = (v * Short.MAX_VALUE).roundToInt().toShort()
                }
            }
            pos += n
        }
        return out
    }
}
