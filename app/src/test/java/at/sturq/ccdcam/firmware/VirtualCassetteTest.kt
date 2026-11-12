package at.sturq.ccdcam.firmware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualCassetteTest {

    private val min = 60_000L

    @Test
    fun freshDvm60Is60MinSpAnd90MinLp() {
        val c = VirtualCassette.fresh()
        assertEquals(60, c.remainingMinutes(RecMode.SP))
        assertEquals(90, c.remainingMinutes(RecMode.LP))
        assertFalse(c.isAtEnd)
    }

    @Test
    fun spRecordingUsesTapeOneToOne() {
        val c = VirtualCassette.fresh().afterRecording(10 * min, RecMode.SP)
        assertEquals(50, c.remainingMinutes(RecMode.SP))
        assertEquals(75, c.remainingMinutes(RecMode.LP))
        assertEquals(10 * min, c.counterMs)
    }

    @Test
    fun lpRecordingUsesTwoThirdsOfTheTape() {
        val c = VirtualCassette.fresh().afterRecording(30 * min, RecMode.LP)
        assertEquals(40, c.remainingMinutes(RecMode.SP))
        assertEquals(60, c.remainingMinutes(RecMode.LP))
        assertEquals(30 * min, c.counterMs)
    }

    @Test
    fun recordingStopsAtTheEndOfTheTape() {
        val c = VirtualCassette.fresh().afterRecording(70 * min, RecMode.SP)
        assertTrue(c.isAtEnd)
        assertEquals(0, c.remainingMinutes(RecMode.SP))
        assertEquals(60 * min, c.counterMs)   // only what fitted on the tape
    }

    @Test
    fun nearEndWarningBelowFiveMinutes() {
        val c = VirtualCassette.fresh().afterRecording(56 * min, RecMode.SP)
        assertTrue(c.isNearEnd(RecMode.SP))
        assertFalse(VirtualCassette.fresh().afterRecording(54 * min, RecMode.SP).isNearEnd(RecMode.SP))
        assertFalse(VirtualCassette.fresh().afterRecording(60 * min, RecMode.SP).isNearEnd(RecMode.SP))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidState() {
        VirtualCassette(usedSpMs = VirtualCassette.DVM60_SP_MS + 1)
    }
}
