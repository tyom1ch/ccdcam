package at.sturq.ccdcam

import java.util.Locale

/**
 * Camcorder-style timecode `HH:MM:SS:FF` for an elapsed time in milliseconds.
 * The frame field runs 0 until [fps] - 1 (30 fps NTSC-style today, 25 for PAL later).
 */
fun formatTimecode(ms: Long, fps: Int): String {
    require(fps > 0) { "fps must be positive, was $fps" }
    val totalSec = ms / 1000
    val frames = ((ms % 1000) * fps / 1000).toInt()
    return String.format(
        Locale.US, "%02d:%02d:%02d:%02d",
        totalSec / 3600, (totalSec % 3600) / 60, totalSec % 60, frames
    )
}
