package at.sturq.ccdcam.firmware

/**
 * FADER timing: fade in from the fader colour when recording starts, fade out to it before
 * the recording stops. Duration is an approximation (no figure in the guide).
 */
object Fader {
    const val DURATION_MS = 2_500L

    /** Fade amount (1 = fully the fader colour) [elapsedMs] into a fade-in or fade-out. */
    fun amount(elapsedMs: Long, fadeIn: Boolean, durationMs: Long = DURATION_MS): Float {
        val t = (elapsedMs.toFloat() / durationMs).coerceIn(0f, 1f)
        val eased = t * t * (3f - 2f * t)                      // smoothstep
        return if (fadeIn) 1f - eased else eased
    }

    fun isDone(elapsedMs: Long, durationMs: Long = DURATION_MS) = elapsedMs >= durationMs
}
