package at.sturq.ccdcam.geometry

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * 2D affine map: x' = a*x + b*y + tx, y' = c*x + d*y + ty.
 * Used in normalized coordinates with y pointing up (GL convention).
 */
data class Affine(
    val a: Float, val b: Float, val c: Float, val d: Float,
    val tx: Float, val ty: Float,
) {
    fun map(x: Float, y: Float): Pair<Float, Float> = Pair(a * x + b * y + tx, c * x + d * y + ty)

    /** this ∘ other: apply [other] first, then this. */
    operator fun times(other: Affine) = Affine(
        a = a * other.a + b * other.c,
        b = a * other.b + b * other.d,
        c = c * other.a + d * other.c,
        d = c * other.b + d * other.d,
        tx = a * other.tx + b * other.ty + tx,
        ty = c * other.tx + d * other.ty + ty,
    )

    fun inverse(): Affine {
        val det = a * d - b * c
        require(abs(det) > 1e-12f) { "singular affine" }
        val ia = d / det
        val ib = -b / det
        val ic = -c / det
        val id = a / det
        return Affine(ia, ib, ic, id, -(ia * tx + ib * ty), -(ic * tx + id * ty))
    }

    /** Column-major 3x3 for glUniformMatrix3fv(transpose = false). */
    fun toGlMat3(): FloatArray = floatArrayOf(a, c, 0f, b, d, 0f, tx, ty, 1f)

    companion object {
        val IDENTITY = Affine(1f, 0f, 0f, 1f, 0f, 0f)

        /** Scale by (sx, sy) around the centre (0.5, 0.5). */
        fun scaleAboutCenter(sx: Float, sy: Float) =
            Affine(sx, 0f, 0f, sy, 0.5f - 0.5f * sx, 0.5f - 0.5f * sy)

        /**
         * Rotates normalized coordinates by [quarterTurnsCcw] × 90° counter-clockwise about the
         * centre (y up). Exact in normalized space for quarter turns: axes swap, ranges stay 0..1.
         */
        fun quarterTurnsCcw(quarterTurnsCcw: Int): Affine {
            val (a, b, c, d) = when (((quarterTurnsCcw % 4) + 4) % 4) {
                0 -> listOf(1f, 0f, 0f, 1f)
                1 -> listOf(0f, -1f, 1f, 0f)   // (x, y) -> (-y, x)
                2 -> listOf(-1f, 0f, 0f, -1f)
                else -> listOf(0f, 1f, -1f, 0f) // (x, y) -> (y, -x)
            }
            // Rotate about (0.5, 0.5): T(0.5) * R * T(-0.5)
            return Affine(a, b, c, d, 0.5f - (a * 0.5f + b * 0.5f), 0.5f - (c * 0.5f + d * 0.5f))
        }
    }
}

/**
 * Maps between the processor's output buffer and the "picture" space the HC90E look is
 * computed in.
 *
 * Picture space: (0,0)..(1,1), y up, axes aligned with the **upright** image as the viewer
 * sees it (preview held in that orientation, MP4 played with its rotation metadata), covering
 * only the picture rectangle (4:3 or 16:9) inside the region that preview and video share.
 *
 * Buffer space: the processor's EGL surface, (0,0)..(1,1), y up — the `aTexCoord` of the
 * full-screen quad.
 */
class EffectGeometry(
    val effectToBuffer: Affine,
    val bufferToEffect: Affine,
) {
    companion object {
        /**
         * @param bufferWidth  width of the processor output buffer, pixels
         * @param bufferHeight height of the processor output buffer, pixels
         * @param regionAspect width/height (buffer orientation) of the centred region that the
         *   downstream preview and video crops share. Clamped to the buffer.
         * @param uprightCwDegrees clockwise rotation that makes the buffer upright
         *   (multiple of 90)
         * @param pictureAspect width/height of the picture in upright orientation (4/3, 16/9).
         *   Fitted inside the upright region; the rest is pillar/letterbox.
         */
        fun compute(
            bufferWidth: Int,
            bufferHeight: Int,
            regionAspect: Float,
            uprightCwDegrees: Int,
            pictureAspect: Float,
        ): EffectGeometry {
            require(bufferWidth > 0 && bufferHeight > 0)
            require(regionAspect > 0f && pictureAspect > 0f)
            val turnsCw = normalizeQuarterTurns(uprightCwDegrees)

            // 1. Shared region inside the buffer (centre crop, like CameraX StreamSharing).
            val bufferAspect = bufferWidth.toFloat() / bufferHeight
            val (rw, rh) = fit(regionAspect, bufferAspect)

            // 2. Upright region aspect in pixels. The region is fitted (not clamped) into the
            //    buffer, so its pixel aspect is regionAspect; a quarter turn swaps the axes.
            val uprightRegionAspect = if (turnsCw % 2 == 1) 1f / regionAspect else regionAspect

            // 3. Picture rectangle inside the upright region.
            val (pw, ph) = fit(pictureAspect, uprightRegionAspect)

            // picture -> upright region -> buffer region -> buffer
            // Upright = buffer rotated CW by turnsCw, so upright -> buffer is CCW by turnsCw.
            val pictureToUpright = Affine.scaleAboutCenter(pw, ph)
            val uprightToRegion = Affine.quarterTurnsCcw(turnsCw)
            val regionToBuffer = Affine.scaleAboutCenter(rw, rh)
            val effectToBuffer = regionToBuffer * uprightToRegion * pictureToUpright
            return EffectGeometry(effectToBuffer, effectToBuffer.inverse())
        }

        /** Fraction (w, h) of a container of aspect [containerAspect] covered by a centred
         *  rectangle of aspect [innerAspect] that fits inside it. */
        internal fun fit(innerAspect: Float, containerAspect: Float): Pair<Float, Float> =
            if (innerAspect < containerAspect) Pair(innerAspect / containerAspect, 1f)
            else Pair(1f, containerAspect / innerAspect)

        internal fun normalizeQuarterTurns(degrees: Int): Int {
            require(degrees % 90 == 0) { "rotation must be a multiple of 90, was $degrees" }
            return ((degrees / 90) % 4 + 4) % 4
        }

        /**
         * Clockwise rotation (0/90/180/270) contained in an android.graphics.Matrix given as its
         * 9 values (MSCALE_X, MSKEW_X, MTRANS_X, MSKEW_Y, MSCALE_Y, MTRANS_Y, ...), in pixel
         * coordinates with y down. Mirroring is not separated out; it only affects the sign of
         * the rotation, not which axis is horizontal.
         */
        fun rotationCwDegreesOf(matrixValues: FloatArray): Int {
            require(matrixValues.size >= 6)
            val scaleX = matrixValues[0]
            val skewY = matrixValues[3]
            // Image of the x unit vector: (scaleX, skewY). In y-down pixels a positive angle is CW.
            val deg = Math.toDegrees(atan2(skewY.toDouble(), scaleX.toDouble()))
            return (((deg / 90.0).roundToInt() * 90) % 360 + 360) % 360
        }
    }
}
