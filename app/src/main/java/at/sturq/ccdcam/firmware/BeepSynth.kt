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
enum class Beep(
    val notes: List<Pair<Double, Int>>,
    /** Part of the BEEP "melody" (silenced by BEEP OFF); mechanical sounds are not. */
    val melody: Boolean = true,
) {
    /** Touch-panel key pressed (res/raw/ui_click). */
    TOUCH(listOf(2093.0 to 45)),
    /** Touch-panel key released (res/raw/ui_declick). */
    TOUCH_UP(listOf(1760.0 to 35)),

    /** Recording start (res/raw/alert; synthesized fallback). */
    REC_START(listOf(1568.0 to 70, 2093.0 to 120)),

    /** Recording stop (res/raw/alert; synthesized fallback). */
    REC_STOP(listOf(2093.0 to 70, 1568.0 to 120)),

    /** Warning indicator (res/raw/alert). */
    WARNING(listOf(2349.0 to 60, 0.0 to 50, 2349.0 to 60, 0.0 to 50, 2349.0 to 60)),

    /** Switching on (res/raw/power_on). */
    POWER_ON(listOf(1568.0 to 60, 2093.0 to 60, 2637.0 to 110)),

    /** Cassette compartment mechanics (res/raw/cassette_*): not a melody. */
    CASSETTE_EJECT(listOf(220.0 to 90), melody = false),
    CASSETTE_INSERT(listOf(180.0 to 60), melody = false),
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
