package at.sturq.ccdcam.camera

import kotlin.math.abs

/**
 * Turns raw OrientationEventListener angles into a steady 0/90/180/270 (clockwise) rotation.
 *
 * A new quadrant is accepted only when the phone is held well inside it (within
 * [acceptWithinDeg] of its centre, i.e. hysteresis around the 45° boundaries) and stays there for
 * [holdMs]. Shaking the phone or holding it near a diagonal therefore does not flip the UI.
 */
class OrientationStabilizer(
    initialCw: Int = 0,
    private val acceptWithinDeg: Int = 25,
    private val holdMs: Long = 400,
) {
    var rotationCw: Int = initialCw
        private set

    private var candidate = -1
    private var candidateSince = 0L

    /** Feeds one sensor angle (0..359, or -1 for unknown). Returns true when [rotationCw] changed. */
    fun update(angle: Int, nowMs: Long): Boolean {
        if (angle < 0) return false
        val q = ((angle + 45) / 90 % 4) * 90
        if (q == rotationCw || distance(angle, q) > acceptWithinDeg) {
            // back in the current quadrant, or not clearly in another one: forget the candidate
            if (q == rotationCw) candidate = -1
            return false
        }
        if (q != candidate) {
            candidate = q
            candidateSince = nowMs
            return false
        }
        if (nowMs - candidateSince < holdMs) return false
        rotationCw = q
        candidate = -1
        return true
    }

    private fun distance(a: Int, b: Int): Int {
        val d = abs(a - b) % 360
        return if (d > 180) 360 - d else d
    }
}
