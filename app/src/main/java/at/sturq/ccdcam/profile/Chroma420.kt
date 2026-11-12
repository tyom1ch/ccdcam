package at.sturq.ccdcam.profile

/**
 * Reference implementation of the shader's PAL DV 4:2:0 step (ccd.frag, uChroma420):
 * BT.601 Y'CbCr, luma kept per cell, chroma = average of the 2x2 cell block.
 */
object Chroma420 {
    fun luma(r: Float, g: Float, b: Float) = 0.299f * r + 0.587f * g + 0.114f * b
    fun cb(r: Float, g: Float, b: Float) = -0.168736f * r - 0.331264f * g + 0.5f * b
    fun cr(r: Float, g: Float, b: Float) = 0.5f * r - 0.418688f * g - 0.081312f * b

    fun toRgb(y: Float, cb: Float, cr: Float) = floatArrayOf(
        y + 1.402f * cr,
        y - 0.344136f * cb - 0.714136f * cr,
        y + 1.772f * cb,
    )

    /** Cell colour [own] with chroma replaced by the average chroma of [block] (4 cells). */
    fun subsample(own: FloatArray, block: List<FloatArray>): FloatArray {
        val c = FloatArray(3) { i -> block.sumOf { it[i].toDouble() }.toFloat() / block.size }
        return toRgb(luma(own[0], own[1], own[2]), cb(c[0], c[1], c[2]), cr(c[0], c[1], c[2]))
    }
}
