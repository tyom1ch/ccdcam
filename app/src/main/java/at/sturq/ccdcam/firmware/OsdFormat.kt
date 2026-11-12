package at.sturq.ccdcam.firmware

import java.util.Locale

/**
 * Text formats of the DCR-HC90E on-screen display (docs/hc90e-ui-references.md).
 * Pure functions: no Android dependencies.
 */
object OsdFormat {

    private fun f(fmt: String, vararg args: Any): String = String.format(Locale.US, fmt, *args)

    /** PAL time-code frame rate. */
    const val PAL_FPS = 25

    /** Tape counter `h:mm:ss` — hour not zero-padded ("0:00:00", Operating Guide p. 26). */
    fun tapeCounter(ms: Long): String {
        val t = ms.coerceAtLeast(0) / 1000
        return f("%d:%02d:%02d", t / 3600, (t % 3600) / 60, t % 60)
    }

    /** Time code `h:mm:ss:ff`, frames 0 until [fps] - 1 ("0:06:26:21" during playback). */
    fun timeCode(ms: Long, fps: Int = PAL_FPS): String {
        require(fps > 0)
        val safe = ms.coerceAtLeast(0)
        val t = safe / 1000
        val ff = ((safe % 1000) * fps / 1000).toInt()
        return f("%d:%02d:%02d:%02d", t / 3600, (t % 3600) / 60, t % 60, ff)
    }

    /** "60min" — remaining tape / battery minutes as printed in the guide. */
    fun minutes(min: Int): String = "${min.coerceAtLeast(0)}min"

    /**
     * Date as shown on the PAL unit. Order D M YYYY with space-padded day/month is an
     * approximation derived from the DATA CODE placeholder `[-- -- ----]` (guide p. 54).
     */
    fun date(year: Int, month: Int, day: Int): String = f("%2d %2d %d", day, month, year)

    /** 24-hour clock with unpadded hour, as on the HC90E CLOCK SET screen ("0:00"). */
    fun clock(hour: Int, minute: Int, second: Int): String = f("%d:%02d:%02d", hour, minute, second)

    /** Unset clock, guide p. 54. */
    const val DATE_UNSET = "-- -- ----"
    const val CLOCK_UNSET = "--:--:--"

    /**
     * InfoLITHIUM-style remaining minutes from the phone battery percentage. Approximation:
     * the 90 min full scale is an assumption (NP-FA50 class pack with the LCD on).
     */
    fun batteryMinutes(percent: Int, fullScaleMinutes: Int = 90): Int =
        (percent.coerceIn(0, 100) * fullScaleMinutes + 50) / 100
}
