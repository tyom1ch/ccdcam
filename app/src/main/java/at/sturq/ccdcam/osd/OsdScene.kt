package at.sturq.ccdcam.osd

import at.sturq.ccdcam.firmware.Lamp
import at.sturq.ccdcam.firmware.OsdFormat
import at.sturq.ccdcam.firmware.RecMode

/** Everything the indicator layer shows, sampled at one instant. */
data class OsdIndicators(
    val lamp: Lamp,
    val recording: Boolean,
    val recMode: RecMode,
    /** Tape counter incl. the scene being recorded, ms. */
    val counterMs: Long,
    /** Playback time code, ms (PLAY/EDIT). */
    val playbackMs: Long,
    val transport: Transport,
    /** Phone battery %, or -1 if unknown. */
    val batteryPct: Int,
    /** Remaining tape minutes, or null when no cassette is inserted. */
    val remainingMin: Int?,
    /** Remaining-tape indicator visible (REMAINING ON / AUTO window / recording). */
    val showRemaining: Boolean,
    /** Cassette pictogram visible this instant (blinks when the tape warns). */
    val cassetteIconOn: Boolean,
    /** Date and time to show (epoch ms), or null. */
    val dateTimeMs: Long?,
    /** Centered warning text, or null. */
    val message: String?,
    /** NightShot switch on: indicator under the battery. */
    val nightShot: Boolean = false,
    /** Blink phase (1 Hz): REC while recording and the NIGHTSHOT caption are shown only when on. */
    val blinkOn: Boolean = true,
    /** FADER selected and waiting for START (LCD indicator, blinks). */
    val faderArmed: Boolean = false,
) {
    enum class Transport { STOP, PLAY, PAUSE, REW, FF }
}

/** A drawable OSD element in LCD units (1024 × 576 for the 16:9 LCD); y is the cell top. */
sealed interface OsdItem {
    val color: Int

    data class Text(
        val text: String, val x: Float, val y: Float, val align: Align, override val color: Int,
        /** Size relative to the indicator text (1 = normal). */
        val scale: Float = 1f,
    ) : OsdItem
    data class Sprite(val name: String, val x: Float, val y: Float, val align: Align, override val color: Int) : OsdItem

    enum class Align { LEFT, CENTER, RIGHT }
}

/**
 * Builds the HC90E indicator layer (guide p. 26 layout, ITmedia photo proportions). Used for
 * both the preview touch panel and the burned-in video OSD so they look identical; the video
 * variant only differs in what is *included* (no buttons, menus or messages — see [build]).
 */
object OsdScene {

    const val LCD_W = 1024f
    const val LCD_H = 576f

    /** OSD pixel in LCD units: cap height 9 px = 0.063 LCD, "0:06:05" = 0.205 LCD width. */
    const val PX = 4.2f
    const val PY = 4.0f
    const val CELL_H = 14 * PY

    const val WHITE = -0x1
    val STBY_GREEN = argb(0xFF, 0x3B, 0xE6, 0x38)
    val REC_RED = argb(0xFF, 0xF0, 0x2A, 0x24)

    private const val ROW1 = 18f
    private const val ROW2 = ROW1 + CELL_H
    private const val DATE_ROW = 412f
    /** Centred NIGHTSHOT caption: cell top so the caps sit on the LCD centre line. */
    private const val NIGHTSHOT_SCALE = 0.5f
    private const val NIGHTSHOT_ROW = LCD_H / 2f - 6.5f * PY * NIGHTSHOT_SCALE
    private const val LEFT = 20f
    private const val RIGHT = 1009f
    /** SP/LP never closer to the battery minutes than this (narrow 4:3 OSD). */
    private const val SP_MIN_X = 290f

    /**
     * @param lcdW OSD width in LCD units for the frame's aspect: 1024 for 16:9, 768 for a 4:3
     *   recording (height is always [LCD_H]; positions are fractions of the width).
     * @param forVideo burned-in variant: the date/time row is always included (the camcorder
     *   would record it as data code; the user asked for it in the picture), and nothing
     *   interactive or transient (buttons, menus, messages) is ever part of it.
     */
    fun build(s: OsdIndicators, forVideo: Boolean, lcdW: Float = LCD_W): List<OsdItem> {
        val right = lcdW - (LCD_W - RIGHT)
        val out = ArrayList<OsdItem>()
        // 1 Battery pictogram + minutes
        val level = if (s.batteryPct < 0) 0 else ((s.batteryPct + 12) / 25).coerceIn(0, 4)
        out += OsdItem.Sprite("battery_$level", LEFT, ROW1, OsdItem.Align.LEFT, WHITE)
        val minutes = if (s.batteryPct >= 0) OsdFormat.batteryMinutes(s.batteryPct) else 0
        out += OsdItem.Text(OsdFormat.minutes(minutes), LEFT + 16 * PX, ROW1, OsdItem.Align.LEFT, WHITE)

        if (s.lamp != Lamp.MEMORY) {
            // 2 Recording mode
            out += OsdItem.Sprite(if (s.recMode == RecMode.LP) "lp" else "sp", maxOf(0.320f * lcdW, SP_MIN_X), ROW1, OsdItem.Align.LEFT, WHITE)
        }
        when (s.lamp) {
            Lamp.TAPE -> {
                // 3 STBY / REC, 4 tape counter
                if (s.recording) {
                    if (s.blinkOn) out += OsdItem.Text("REC", 0.590f * lcdW, ROW1, OsdItem.Align.CENTER, REC_RED)
                } else {
                    out += OsdItem.Text("STBY", 0.590f * lcdW, ROW1, OsdItem.Align.CENTER, STBY_GREEN)
                }
                out += OsdItem.Text(OsdFormat.tapeCounter(s.counterMs), right, ROW1, OsdItem.Align.RIGHT, WHITE)
            }
            Lamp.PLAY_EDIT -> {
                val x = 0.630f * lcdW
                when (s.transport) {
                    OsdIndicators.Transport.PLAY -> out += OsdItem.Sprite("play", x, ROW1, OsdItem.Align.LEFT, WHITE)
                    OsdIndicators.Transport.PAUSE -> {
                        out += OsdItem.Sprite("play", x, ROW1, OsdItem.Align.LEFT, WHITE)
                        out += OsdItem.Sprite("pause", x + 8 * PX, ROW1, OsdItem.Align.LEFT, WHITE)
                    }
                    OsdIndicators.Transport.STOP -> out += OsdItem.Sprite("stop", x, ROW1, OsdItem.Align.LEFT, WHITE)
                    OsdIndicators.Transport.FF -> {
                        out += OsdItem.Sprite("play", x, ROW1, OsdItem.Align.LEFT, WHITE)
                        out += OsdItem.Sprite("play", x + 6 * PX, ROW1, OsdItem.Align.LEFT, WHITE)
                    }
                    OsdIndicators.Transport.REW -> Unit
                }
                out += OsdItem.Text(OsdFormat.timeCode(s.playbackMs), right, ROW1, OsdItem.Align.RIGHT, WHITE)
            }
            Lamp.MEMORY -> Unit
        }

        // 5 Remaining tape + cassette pictogram
        if (s.lamp != Lamp.MEMORY) {
            if (s.cassetteIconOn) out += OsdItem.Sprite("cassette", right, ROW2, OsdItem.Align.RIGHT, WHITE)
            if (s.showRemaining && s.remainingMin != null) {
                out += OsdItem.Text(OsdFormat.minutes(s.remainingMin), right - 17 * PX, ROW2, OsdItem.Align.RIGHT, WHITE)
            }
        }

        // NightShot indicator (position: left, under the battery — ITmedia 28035)
        if (s.nightShot) {
            out += OsdItem.Sprite("nightshot", LEFT, ROW2, OsdItem.Align.LEFT, WHITE)
            if (s.blinkOn) {
                out += OsdItem.Text("NIGHTSHOT", lcdW / 2f, NIGHTSHOT_ROW, OsdItem.Align.CENTER, WHITE, NIGHTSHOT_SCALE)
            }
        }

        if (s.faderArmed && s.blinkOn) {
            out += OsdItem.Text("FADER", LEFT, ROW2 + CELL_H, OsdItem.Align.LEFT, WHITE)
        }

        // Date / time row
        s.dateTimeMs?.let { t ->
            val (date, time) = OsdClock.format(t)
            out += OsdItem.Text(date, 0.050f * lcdW, DATE_ROW, OsdItem.Align.LEFT, WHITE)
            out += OsdItem.Text(time, 0.950f * lcdW, DATE_ROW, OsdItem.Align.RIGHT, WHITE)
        }

        if (!forVideo) {
            // Messages go under the centred NIGHTSHOT caption when it is shown.
            val msgY = if (s.nightShot) NIGHTSHOT_ROW + CELL_H * 1.2f else LCD_H * 0.47f
            s.message?.let { out += OsdItem.Text(it, lcdW / 2f, msgY, OsdItem.Align.CENTER, WHITE) }
        }
        return out
    }

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b
}
