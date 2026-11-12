package at.sturq.ccdcam.firmware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HC90eMenuTest {

    @Test
    fun categoriesInGuideOrderWithGuideLabels() {
        assertEquals(
            listOf("CAMERA SET", "MEMORY SET", "PICT.APPLI.", "EDIT/PLAY", "STANDARD SET", "TIME/LANGU."),
            HC90eMenu.categories.map { it.label },
        )
    }

    @Test
    fun standardSetStartsLikeTheGuide() {
        val std = HC90eMenu.categories.first { it.id == "STANDARD SET" }.items.map { it.id }
        assertEquals(listOf("REC MODE", "AUDIO MODE", "VOLUME", "MULTI-SOUND", "AUDIO MIX"), std.take(5))
        assertTrue(std.containsAll(listOf("DATA CODE", "REMAINING", "BEEP", "MENU ROTATE", "A.SHUT OFF")))
    }

    @Test
    fun burnOsdIsAnEmulatedTapeSettingDefaultOff() {
        val item = HC90eMenu.item(HC90eMenu.BURN_OSD)
        assertTrue(item.isAvailable(Lamp.TAPE))
        assertEquals(listOf("OFF", "ON"), item.options.map { it.id })
        assertEquals("OFF", FirmwareSettings().valueOf(HC90eMenu.BURN_OSD))
        assertTrue(HC90eMenu.BURN_OSD in HC90eMenu.page(HC90eMenu.pMenuCameraTape, 2))
        assertEquals(FirmwareSettings.BURN_OSD_ON, FirmwareSettings().with(HC90eMenu.BURN_OSD, "ON").burnOsd)
    }

    @Test
    fun recordingFoldersRenamedWithLegacyKept() {
        assertEquals("Movies/Handycam", Hc90eController.MOVIES_DIR)
        assertEquals("Pictures/Handycam", Hc90eController.PICTURES_DIR)
        assertEquals("Movies/CCDCam", Hc90eController.LEGACY_MOVIES_DIR)
    }

    @Test
    fun superNightShotIsInCameraSet() {
        // HC90E guide p. 36: SUPER NS and NS LIGHT exist on this model.
        val cam = HC90eMenu.categories.first { it.id == "CAMERA SET" }.items.map { it.id }
        assertTrue("SUPER NS" in cam)
        assertTrue("NS LIGHT" in cam)
    }

    @Test
    fun defaultsAreTheFirstOption() {
        val d = FirmwareSettings()
        for (id in listOf(HC90eMenu.REC_MODE, HC90eMenu.AUDIO_MODE, HC90eMenu.DATA_CODE,
            HC90eMenu.REMAINING, HC90eMenu.BEEP, HC90eMenu.MENU_ROTATE)) {
            assertEquals(id, HC90eMenu.item(id).options.first().id, d.valueOf(id))
        }
    }

    @Test
    fun emulatedValueItemsHaveOptions() {
        HC90eMenu.categories.flatMap { it.items }.filter { it.emulated && it.id !in HC90eMenu.ACTION_ITEMS }
            .forEach { assertTrue("${it.id} options", it.options.size >= 2) }
    }

    @Test
    fun cameraTapePMenuFirstPageIsDocumented() {
        // Guide p. 56 figure and ITmedia 28036, row by row.
        assertEquals(
            listOf("MENU", "DISP GUIDE", "TELE MACRO", "EXPOSURE", "SPOT FOCUS", "FADER"),
            HC90eMenu.page(HC90eMenu.pMenuCameraTape, 0),
        )
        assertEquals(3, HC90eMenu.pageCount(HC90eMenu.pMenuCameraTape))   // "1/3"
        assertEquals(HC90eMenu.P_MENU_SET_UP, HC90eMenu.pMenuCameraTape.last())
    }

    @Test
    fun playEditPMenuFirstPageIsDocumented() {
        // Guide p. 34 figure.
        assertEquals(
            listOf("MENU", "DISP GUIDE", "END SEARCH", "VOLUME", "VAR. SPD PB", "REC CTRL"),
            HC90eMenu.page(HC90eMenu.pMenuPlayEdit, 0),
        )
    }

    @Test
    fun everyPMenuEntryIsAMenuItemOrSpecial() {
        (HC90eMenu.pMenuCameraTape + HC90eMenu.pMenuPlayEdit).forEach {
            assertTrue(it, it == HC90eMenu.P_MENU || it == HC90eMenu.P_MENU_SET_UP || HC90eMenu.itemOrNull(it) != null)
        }
    }

    @Test
    fun availabilityFollowsLampAndEmulation() {
        assertTrue(HC90eMenu.pMenuEntryAvailable(HC90eMenu.P_MENU, Lamp.TAPE))
        assertFalse(HC90eMenu.pMenuEntryAvailable(HC90eMenu.P_MENU_SET_UP, Lamp.TAPE))
        assertFalse(HC90eMenu.pMenuEntryAvailable("TELE MACRO", Lamp.TAPE))         // not emulated
        // stage 10: first P-MENU page comes alive
        for (e in listOf("EXPOSURE", "SPOT FOCUS", "FADER")) assertTrue(e, HC90eMenu.pMenuEntryAvailable(e, Lamp.TAPE))
        assertFalse(HC90eMenu.pMenuEntryAvailable("FADER", Lamp.MEMORY))
        assertTrue(HC90eMenu.pMenuEntryAvailable("SUPER NS", Lamp.TAPE))
        assertTrue(HC90eMenu.pMenuEntryAvailable(HC90eMenu.REC_MODE, Lamp.TAPE))
        assertFalse(HC90eMenu.pMenuEntryAvailable(HC90eMenu.DATA_CODE, Lamp.TAPE))  // playback only
        assertTrue(HC90eMenu.pMenuEntryAvailable(HC90eMenu.DATA_CODE, Lamp.PLAY_EDIT))
    }
}
