package at.sturq.ccdcam

import org.junit.Assert.assertEquals
import org.junit.Test

class TimecodeTest {

    @Test
    fun zero() {
        assertEquals("00:00:00:00", formatTimecode(0, 30))
    }

    @Test
    fun framesAt30fpsMatchLegacyFormula() {
        // Legacy MainActivity formula: ((ms % 1000) * 30 / 1000)
        assertEquals("00:00:00:15", formatTimecode(500, 30))
        assertEquals("00:00:00:29", formatTimecode(999, 30))
        assertEquals("00:00:01:00", formatTimecode(1000, 30))
    }

    @Test
    fun framesAt25fpsStayBelow25() {
        assertEquals("00:00:00:12", formatTimecode(500, 25))
        assertEquals("00:00:00:24", formatTimecode(999, 25))
    }

    @Test
    fun hoursMinutesSeconds() {
        val ms = ((1 * 3600 + 2 * 60 + 3) * 1000L) + 40
        assertEquals("01:02:03:01", formatTimecode(ms, 30))
        assertEquals("01:02:03:01", formatTimecode(ms, 25))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPositiveFps() {
        formatTimecode(0, 0)
    }
}
