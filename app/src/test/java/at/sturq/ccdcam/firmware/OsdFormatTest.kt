package at.sturq.ccdcam.firmware

import org.junit.Assert.assertEquals
import org.junit.Test

class OsdFormatTest {

    @Test
    fun tapeCounterMatchesGuideFormat() {
        assertEquals("0:00:00", OsdFormat.tapeCounter(0))          // guide p. 26
        assertEquals("0:06:05", OsdFormat.tapeCounter(365_000))    // ITmedia 28035
        assertEquals("1:02:03", OsdFormat.tapeCounter(3_723_999))
        assertEquals("0:00:00", OsdFormat.tapeCounter(-5))
    }

    @Test
    fun timeCodeUsesPalFrames() {
        assertEquals("0:00:00:00", OsdFormat.timeCode(0))
        assertEquals("0:00:00:24", OsdFormat.timeCode(999))        // 25 fps: frames 0..24
        assertEquals("0:00:01:00", OsdFormat.timeCode(1_000))
        assertEquals("0:06:26:21", OsdFormat.timeCode(386_840))    // ITmedia 28039 value
    }

    @Test
    fun minutes() {
        assertEquals("60min", OsdFormat.minutes(60))
        assertEquals("0min", OsdFormat.minutes(-1))
    }

    @Test
    fun clockIs24HourWithUnpaddedHour() {
        assertEquals("0:05:09", OsdFormat.clock(0, 5, 9))
        assertEquals("21:04:37", OsdFormat.clock(21, 4, 37))
    }

    @Test
    fun dateIsDayMonthYear() {
        assertEquals("13  2 2005", OsdFormat.date(2005, 2, 13))
        assertEquals(" 1 12 2026", OsdFormat.date(2026, 12, 1))
    }

    @Test
    fun batteryMinutesScale() {
        assertEquals(90, OsdFormat.batteryMinutes(100))
        assertEquals(45, OsdFormat.batteryMinutes(50))
        assertEquals(0, OsdFormat.batteryMinutes(0))
        assertEquals(90, OsdFormat.batteryMinutes(150))
    }
}
