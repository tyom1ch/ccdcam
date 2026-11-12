package at.sturq.ccdcam.firmware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class BeepAndLayoutTest {

    @Test
    fun beepsAreShortClickFreeAndNotClipping() {
        for (beep in Beep.entries) {
            val pcm = BeepSynth.render(beep)
            val ms = pcm.size * 1000 / BeepSynth.SAMPLE_RATE
            assertTrue("$beep length $ms", ms in 30..400)
            assertTrue("$beep peak", pcm.maxOf { abs(it.toInt()) } < Short.MAX_VALUE * 0.5)
            assertTrue("$beep starts silent", abs(pcm.first().toInt()) < 200)
            assertTrue("$beep ends silent", abs(pcm.last().toInt()) < 200)
        }
    }

    @Test
    fun recStartRisesAndRecStopFalls() {
        assertTrue(Beep.REC_START.notes.first().first < Beep.REC_START.notes.last().first)
        assertTrue(Beep.REC_STOP.notes.first().first > Beep.REC_STOP.notes.last().first)
    }

    @Test
    fun portraitLcdIsA16x9BandAcrossTheDisplayedRegion() {
        // Pixel 9 Pro XL preview view measured on device: 1008 x 1700, region shown as 9:16.
        val region = LcdLayout.displayedRegion(1008f, 1700f, 9f / 16f)
        assertEquals(956.25f, region.width, 0.5f)
        val lcd = LcdLayout.lcd(region)
        assertEquals(region.width, lcd.width, 0.01f)
        assertEquals(16f / 9f, lcd.width / lcd.height, 0.001f)
        assertEquals(850f, lcd.centerY, 0.5f)
    }

    @Test
    fun layoutFractionsStayInsideTheLcd() {
        val boxes = listOf(
            LcdLayout.END_SEARCH_BUTTON, LcdLayout.P_MENU_BUTTON, LcdLayout.PMENU_UP, LcdLayout.PMENU_DOWN,
            LcdLayout.PMENU_CLOSE, LcdLayout.DRUM, LcdLayout.DRUM_UP, LcdLayout.DRUM_DOWN, LcdLayout.MENU_BACK,
            LcdLayout.MENU_OK, LcdLayout.MENU_CLOSE, LcdLayout.PLAY_STOP, LcdLayout.PLAY_REW,
            LcdLayout.PLAY_PLAY_PAUSE, LcdLayout.PLAY_FF,
        )
        for (b in boxes) {
            assertTrue(b.all { it in 0f..1f })
            assertTrue(b[0] < b[2] && b[1] < b[3])
        }
    }

    @Test
    fun bottomRowButtonsDoNotOverlap() {
        val row = listOf(LcdLayout.PLAY_STOP, LcdLayout.PLAY_REW, LcdLayout.PLAY_PLAY_PAUSE, LcdLayout.PLAY_FF, LcdLayout.P_MENU_BUTTON)
        row.zipWithNext().forEach { (a, b) -> assertTrue(a[2] < b[0]) }
        assertTrue(LcdLayout.END_SEARCH_BUTTON[2] < LcdLayout.P_MENU_BUTTON[0])
    }
}
