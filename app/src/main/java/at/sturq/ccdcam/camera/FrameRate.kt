package at.sturq.ccdcam.camera

/**
 * PAL frame rate: what to ask the camera for, and what it actually delivers.
 * Pure Kotlin (ranges as pairs) so the decision and the measurement are unit-testable.
 */
object FrameRatePlan {

    const val PAL_FPS = 25

    /**
     * The AE range to request for PAL capture, or null if the device cannot deliver a constant
     * 25 fps. Only a fixed [25, 25] range gives constant-frame-rate video; a variable range such
     * as [15, 25] would let auto-exposure slow down in low light, and dropping frames from 30 fps
     * would not be CFR either — neither is used.
     *
     * @param supported CameraInfo.getSupportedFrameRateRanges() as (lower, upper) pairs
     */
    fun choose(supported: Collection<Pair<Int, Int>>): Pair<Int, Int>? =
        supported.firstOrNull { it.first == PAL_FPS && it.second == PAL_FPS }

    /** Short label for logs/diagnostics, e.g. "[15,30] [25,25] [30,30]". */
    fun describe(supported: Collection<Pair<Int, Int>>): String =
        supported.sortedWith(compareBy({ it.second }, { it.first })).joinToString(" ") { "[${it.first},${it.second}]" }
}

/**
 * Measures the delivered frame rate from frame timestamps (ns), median of the last [window]
 * frame intervals so a single late frame does not skew it.
 */
class FrameRateMeter(private val window: Int = 30) {

    private val deltas = ArrayDeque<Long>()
    private var last = Long.MIN_VALUE

    @Synchronized
    fun onFrame(timestampNs: Long) {
        if (last != Long.MIN_VALUE && timestampNs > last) {
            deltas.addLast(timestampNs - last)
            if (deltas.size > window) deltas.removeFirst()
        }
        last = timestampNs
    }

    @Synchronized
    fun reset() {
        deltas.clear()
        last = Long.MIN_VALUE
    }

    /** Frames per second, or null until enough frames were seen. */
    @Synchronized
    fun fps(): Double? {
        if (deltas.size < window / 2) return null
        val sorted = deltas.sorted()
        val median = sorted[sorted.size / 2]
        return 1e9 / median
    }

    /** Rounded fps for display ("25p"), or null. */
    fun roundedFps(): Int? = fps()?.let { Math.round(it).toInt() }
}

/**
 * Turns the camera's native rate (e.g. 30 fps) into constant-frame-rate PAL video without asking
 * the camera for 25 fps (a fixed [25,25] AE range is missing or freezes on some phones).
 * Output ticks are exactly one period apart on the camera clock, so the MP4 is CFR and stays in
 * sync with audio; each tick takes the first camera frame within half a period of it (≤ ±20 ms
 * at 25 fps), the others are dropped.
 */
class FrameDecimator(fps: Int = FrameRatePlan.PAL_FPS) {
    private val periodNs = 1_000_000_000L / fps
    private var nextNs = Long.MIN_VALUE

    /** Presentation time for the frame captured at [timestampNs], or null to drop it. */
    fun accept(timestampNs: Long): Long? = ticks(timestampNs).lastOrNull()

    /**
     * Grid ticks to render the frame captured at [timestampNs] for (empty = drop it). When the
     * camera skipped a frame or two (AE slowing down in low light), the ticks that passed are
     * filled with this frame, so the file stays 25p without holes; after a longer stall
     * (> [MAX_FILL] ticks) the grid just moves on.
     */
    @Synchronized
    fun ticks(timestampNs: Long): LongArray {
        if (nextNs == Long.MIN_VALUE) nextNs = timestampNs
        if (timestampNs < nextNs - periodNs / 2) return LongArray(0)
        val out = ArrayList<Long>(2)
        var missed = 0
        while (timestampNs - nextNs > periodNs / 2) {
            if (missed < MAX_FILL) out += nextNs
            missed++
            nextNs += periodNs
        }
        if (missed > MAX_FILL) out.clear()
        out += nextNs
        nextNs += periodNs
        return out.toLongArray()
    }

    @Synchronized
    fun reset() {
        nextNs = Long.MIN_VALUE
    }

    companion object {
        const val MAX_FILL = 3
    }
}
