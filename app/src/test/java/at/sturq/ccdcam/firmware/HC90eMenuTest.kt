package at.sturq.ccdcam.firmware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HC90eMenuTest {

    @Test
    fun categoriesInGuideOrderWithGuideLabels() {
        assertEquals(
            listOf("CAMERA SET", "MEMORY SET", "PICT.APPLI.", "EDIT/PLAY", "STANDARD SET", "TIME/LANGU.", "CCD LOOK"),
            HC90eMenu.categories.map { it.label },  // the app's CCD LOOK comes after the guide's six
        )
    }

    @Test
    fun lookTweaksDefaultToTheStockLook() {
        val d = FirmwareSettings()
        assertEquals(at.sturq.ccdcam.profile.Profiles.HC90E_PAL, LookTweaks.profile(d))
        assertEquals(1f, LookTweaks.beepVolume(d))
        val t = d.with(HC90eMenu.SCANLINES, LookTweaks.OFF).with(HC90eMenu.INTERLACE, LookTweaks.OFF)
            .with(HC90eMenu.CCD_SMEAR, LookTweaks.ON).with(HC90eMenu.BEEP_VOL, LookTweaks.LOW)
        val p = LookTweaks.profile(t).shader
        assertEquals(0f, p.scanlineAmp)
        assertEquals(0f, p.interlace)
        assertTrue(p.smearStrength > 0f)
        assertTrue(LookTweaks.beepVolume(t) < 0.5f)
        assertEquals(LookTweaks.OFF, t.valueOf(HC90eMenu.SCANLINES))
        assertEquals(LookTweaks.STD, t.valueOf(HC90eMenu.SHARPNESS))
        for (id in HC90eMenu.TWEAKS) assertTrue(id, HC90eMenu.item(id).isAvailable(Lamp.TAPE))
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
        assertTrue(HC90eMenu.BURN_OSD in HC90eMenu.pMenuFor(Lamp.TAPE))
        assertEquals(FirmwareSettings.BURN_OSD_ON, FirmwareSettings().with(HC90eMenu.BURN_OSD, "ON").burnOsd)
    }

    @Test
    fun recordingFoldersRenamedWithLegacyKept() {
        assertEquals("Movies/Handycam", Hc90eController.MOVIES_DIR)
        assertEquals("Pictures/Handycam", Hc90eController.PICTURES_DIR)
        assertEquals("Movies/CCDCam", Hc90eController.LEGACY_MOVIES_DIR)
    }

    @Test
    fun audioEmulationSettings() {
        val d = FirmwareSettings()
        // defaults: DV sound, 12BIT (Sony default) -> 32 kHz profile, original replaced
        assertEquals(at.sturq.ccdcam.audio.AudioProfile.DV12_32K, d.audioProfile())
        assertEquals(at.sturq.ccdcam.audio.AudioProfile.DV16_48K,
            d.with(HC90eMenu.AUDIO_MODE, FirmwareSettings.AUDIO_16BIT).audioProfile())
        assertEquals(at.sturq.ccdcam.audio.AudioProfile.LOFI,
            d.with(HC90eMenu.AUDIO_EMU, FirmwareSettings.AUDIO_EMU_LOFI).audioProfile())
        assertEquals(at.sturq.ccdcam.audio.AudioProfile.CLEAN,
            d.with(HC90eMenu.AUDIO_EMU, FirmwareSettings.AUDIO_EMU_CLEAN).audioProfile())
        assertEquals(FirmwareSettings.ORIG_AUDIO_REPLACE, d.origAudio)
        assertTrue(HC90eMenu.item(HC90eMenu.AUDIO_EMU).isAvailable(Lamp.TAPE))
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
    }

    @Test
    fun shownMenusHoldOnlyWorkingEntries() {
        // P-MENU as shown: the documented order with the non-working entries left out.
        val tape = HC90eMenu.pMenuFor(Lamp.TAPE)
        assertEquals(listOf("MENU", "EXPOSURE", "SPOT FOCUS", "FADER", "SPOT METER", "WHITE BAL."), HC90eMenu.page(tape, 0))
        assertEquals(3, HC90eMenu.pageCount(tape))
        assertFalse("TELE MACRO" in tape)
        assertFalse(HC90eMenu.P_MENU_SET_UP in tape)
        assertEquals(listOf("MENU", "END SEARCH", "DATA CODE", "REC MODE", "REMAINING", "BEEP"), HC90eMenu.pMenuFor(Lamp.PLAY_EDIT))
        // MENU drum as shown: working items of this lamp only, no empty categories.
        for (lamp in Lamp.entries) {
            val menu = HC90eMenu.categoriesFor(lamp)
            assertTrue(menu.all { c -> c.items.isNotEmpty() && c.items.all { it.isAvailable(lamp) } })
        }
        assertFalse(HC90eMenu.categoriesFor(Lamp.TAPE).any { c -> c.items.any { it.id == HC90eMenu.DATA_CODE } })
        assertTrue(HC90eMenu.categoriesFor(Lamp.PLAY_EDIT).any { c -> c.items.any { it.id == HC90eMenu.DATA_CODE } })
        assertFalse(HC90eMenu.categoriesFor(Lamp.TAPE).any { it.id == "TIME/LANGU." })
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
