package at.sturq.ccdcam.firmware

/** Rectangle in view pixels (no android.graphics dependency, so layout is unit-testable). */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2f
    val centerY get() = (top + bottom) / 2f
    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
}

/**
 * Where the emulated 16:9 LCD sits on screen. The HC90E preview shows the 16:9 shared camera
 * region with PreviewView FIT_CENTER; in the portrait UI that region appears as 9:16. The LCD is
 * the 16:9 band fitted into it — the same band EffectGeometry fits the picture into
 * (CcdSurfaceProcessor.Framing.lcdAspect), so OSD and picture line up.
 */
object LcdLayout {

    const val LCD_ASPECT = 16f / 9f

    /** Displayed camera region inside a view of [viewW]×[viewH] for FIT_CENTER. */
    fun displayedRegion(viewW: Float, viewH: Float, regionAspect: Float): Box {
        val viewAspect = viewW / viewH
        return if (regionAspect < viewAspect) {
            val w = viewH * regionAspect
            Box((viewW - w) / 2f, 0f, (viewW + w) / 2f, viewH)
        } else {
            val h = viewW / regionAspect
            Box(0f, (viewH - h) / 2f, viewW, (viewH + h) / 2f)
        }
    }

    /** The 16:9 LCD band fitted, centred, into [region]. */
    fun lcd(region: Box, lcdAspect: Float = LCD_ASPECT): Box {
        val regionAspect = region.width / region.height
        return if (lcdAspect < regionAspect) {
            val w = region.height * lcdAspect
            Box(region.centerX - w / 2f, region.top, region.centerX + w / 2f, region.bottom)
        } else {
            val h = region.width / lcdAspect
            Box(region.left, region.centerY - h / 2f, region.right, region.centerY + h / 2f)
        }
    }

    /**
     * Upright drawing frame for the firmware display while the activity stays portrait.
     *
     * [deviceRotationCw] is how far the phone is turned clockwise from portrait (the
     * OrientationEventListener bucket: 0/90/180/270). The display is drawn in a virtual
     * [width] × [height] frame and turned by [canvasAngle] (Canvas.rotate convention: positive is
     * clockwise on screen) about the centre of [region], so it stays upright for the user.
     */
    class RotatedFrame(val region: Box, deviceRotationCw: Int) {
        val turns = ((deviceRotationCw / 90) % 4 + 4) % 4
        val canvasAngle: Float = -90f * turns
        val width: Float = if (turns % 2 == 1) region.height else region.width
        val height: Float = if (turns % 2 == 1) region.width else region.height
        private val cos = Math.cos(Math.toRadians(canvasAngle.toDouble())).toFloat().roundTo()
        private val sin = Math.sin(Math.toRadians(canvasAngle.toDouble())).toFloat().roundTo()

        /** Virtual frame point → screen point. */
        fun toScreen(x: Float, y: Float): Pair<Float, Float> {
            val dx = x - width / 2f
            val dy = y - height / 2f
            return Pair(region.centerX + dx * cos - dy * sin, region.centerY + dx * sin + dy * cos)
        }

        /** Screen point → virtual frame point (inverse rotation). */
        fun toVirtual(x: Float, y: Float): Pair<Float, Float> {
            val dx = x - region.centerX
            val dy = y - region.centerY
            return Pair(width / 2f + dx * cos + dy * sin, height / 2f - dx * sin + dy * cos)
        }

        private fun Float.roundTo(): Float = Math.round(this).toFloat()   // exact quarter turns
    }

    /** A box given in LCD fractions (x0, y0, x1, y1) → view pixels. */
    fun at(lcd: Box, x0: Float, y0: Float, x1: Float, y1: Float) = Box(
        lcd.left + x0 * lcd.width, lcd.top + y0 * lcd.height,
        lcd.left + x1 * lcd.width, lcd.top + y1 * lcd.height,
    )

    // ---- Element positions, fractions of the LCD (measured from the guide's figures and the
    //      ITmedia LCD photos; see docs/hc90e-ui-references.md) ----

    /** Bottom-right buttons on the recording screen (guide p. 26: 6 and 7). */
    val END_SEARCH_BUTTON = floatArrayOf(0.600f, 0.845f, 0.787f, 0.955f)
    val P_MENU_BUTTON = floatArrayOf(0.797f, 0.845f, 0.985f, 0.955f)

    /** Personal Menu (ITmedia 28036, guide p. 34). */
    val PMENU_UP = floatArrayOf(0.055f, 0.160f, 0.180f, 0.315f)
    val PMENU_DOWN = floatArrayOf(0.055f, 0.765f, 0.180f, 0.920f)
    val PMENU_CLOSE = floatArrayOf(0.830f, 0.160f, 0.955f, 0.315f)
    val PMENU_COL_X = floatArrayOf(0.245f, 0.470f, 0.530f, 0.755f)
    val PMENU_ROW_Y = floatArrayOf(0.160f, 0.375f, 0.430f, 0.645f, 0.700f, 0.915f)

    /** MENU drum (ITmedia 28037, guide p. 34). */
    val DRUM = floatArrayOf(0.265f, 0.110f, 0.845f, 0.985f)
    val DRUM_UP = floatArrayOf(0.480f, 0.130f, 0.620f, 0.265f)
    val DRUM_DOWN = floatArrayOf(0.480f, 0.855f, 0.620f, 0.985f)
    val MENU_BACK = floatArrayOf(0.055f, 0.855f, 0.180f, 0.975f)
    val MENU_OK = floatArrayOf(0.820f, 0.855f, 0.945f, 0.975f)
    val MENU_CLOSE = floatArrayOf(0.830f, 0.130f, 0.955f, 0.265f)
    const val DRUM_SELECTED_CENTER_Y = 0.540f
    const val DRUM_ROW_H = 0.150f

    /** Tape playback transport row (guide p. 21; ITmedia 28039). */
    val PLAY_STOP = floatArrayOf(0.195f, 0.845f, 0.335f, 0.955f)
    val PLAY_REW = floatArrayOf(0.345f, 0.845f, 0.485f, 0.955f)
    val PLAY_PLAY_PAUSE = floatArrayOf(0.495f, 0.845f, 0.635f, 0.955f)
    val PLAY_FF = floatArrayOf(0.645f, 0.845f, 0.785f, 0.955f)

    /** Text size of the indicator row, fraction of LCD height. */
    const val OSD_TEXT = 0.068f
    const val BUTTON_TEXT = 0.060f
}
