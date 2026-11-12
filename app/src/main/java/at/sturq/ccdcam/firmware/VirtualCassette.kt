package at.sturq.ccdcam.firmware

import kotlin.math.min

/** Tape speed modes (STANDARD SET > REC MODE). LP gives 1.5 × the SP recording time. */
enum class RecMode(val label: String, val tapePerRealMs: Double) {
    SP("SP", 1.0),
    LP("LP", 1.0 / 1.5),
}

/**
 * A Mini DV cassette. Tape use is tracked in SP-equivalent milliseconds so SP and LP scenes
 * can be mixed on one tape, as on the real camcorder.
 *
 * @param spLengthMs tape length at SP speed (DVM60: 60 min SP / 90 min LP)
 * @param usedSpMs   tape already used, in SP-equivalent ms
 * @param counterMs  tape counter (real recorded time) at the end of the recorded part
 */
data class VirtualCassette(
    val label: String = "DVM60",
    val spLengthMs: Long = DVM60_SP_MS,
    val usedSpMs: Long = 0,
    val counterMs: Long = 0,
) {
    init {
        require(spLengthMs > 0)
        require(usedSpMs in 0..spLengthMs)
        require(counterMs >= 0)
    }

    /** Real recording time left in [mode]. */
    fun remainingMs(mode: RecMode): Long = ((spLengthMs - usedSpMs) / mode.tapePerRealMs).toLong()

    /** Whole minutes left, as the "60min" indicator shows them. */
    fun remainingMinutes(mode: RecMode): Int = (remainingMs(mode) / 60_000).toInt()

    val isAtEnd: Boolean get() = usedSpMs >= spLengthMs

    /** "Less than 5 minutes remaining" warning (guide p. 98). */
    fun isNearEnd(mode: RecMode): Boolean = !isAtEnd && remainingMs(mode) < NEAR_END_MS

    /** The tape after recording [realMs] in [mode]; stops at the end of the tape. */
    fun afterRecording(realMs: Long, mode: RecMode): VirtualCassette {
        require(realMs >= 0)
        val recordable = min(realMs, remainingMs(mode))
        val tape = min(spLengthMs - usedSpMs, (recordable * mode.tapePerRealMs).toLong())
        return copy(usedSpMs = usedSpMs + tape, counterMs = counterMs + recordable)
    }

    companion object {
        const val DVM60_SP_MS = 60L * 60_000
        const val NEAR_END_MS = 5L * 60_000

        fun fresh() = VirtualCassette()
    }
}
