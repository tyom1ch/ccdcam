package at.sturq.ccdcam.firmware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirmwareNavigatorTest {

    private fun nav(lamp: Lamp = Lamp.TAPE, s: FirmwareSettings = FirmwareSettings()) = FirmwareNavigator(s, lamp)

    private val stdIndex = HC90eMenu.categoriesFor(Lamp.TAPE).indexOfFirst { it.id == "STANDARD SET" }

    @Test
    fun pMenuOpensOnPageOneAndWraps() {
        val n = nav()
        n.openPMenu()
        assertEquals(Screen.PMenu(0), n.screen)
        n.pMenuPage(-1)
        assertEquals(Screen.PMenu(2), n.screen)   // 1/3 -> 3/3
        n.pMenuPage(1)
        assertEquals(Screen.PMenu(0), n.screen)
        n.close()
        assertEquals(Screen.Main, n.screen)
    }

    @Test
    fun menuButtonOpensCategoryDrum() {
        val n = nav()
        n.openPMenu()
        n.pMenuSelect(HC90eMenu.P_MENU)
        assertEquals(Screen.MenuCategories(0), n.screen)
    }

    @Test
    fun grayedEntriesDoNothing() {
        val n = nav()
        n.openPMenu()
        assertEquals(NavEffect.None, n.pMenuSelect("TELE MACRO"))
        assertEquals(Screen.PMenu(0), n.screen)
    }

    @Test
    fun changeRecModeThroughMenuAndConfirmWithOk() {
        val n = nav()
        n.openPMenu()
        n.pMenuSelect(HC90eMenu.P_MENU)
        n.drumTouch(stdIndex)
        n.ok()                                            // into STANDARD SET items
        assertEquals(Screen.MenuItems(stdIndex, 0), n.screen)
        n.ok()                                            // REC MODE setting
        val setting = n.screen as Screen.Setting
        assertEquals(HC90eMenu.REC_MODE, setting.itemId)
        assertEquals("SP", setting.selected)
        assertFalse(n.settingChanged)                     // shows ↩
        n.selectOption("LP")
        assertTrue(n.settingChanged)                      // shows OK
        val effect = n.ok()
        assertTrue(effect is NavEffect.SettingsChanged)
        assertEquals(RecMode.LP, n.settings.recMode)
        assertEquals(Screen.MenuItems(stdIndex, 0), n.screen)
    }

    @Test
    fun backDiscardsTheChange() {
        val n = nav()
        n.openPMenu()
        n.pMenuSelect(HC90eMenu.P_MENU)
        n.drumTouch(stdIndex); n.ok(); n.ok()
        n.selectOption("LP")
        n.back()
        assertEquals(RecMode.SP, n.settings.recMode)
        assertEquals(Screen.MenuItems(stdIndex, 0), n.screen)
        n.back()
        assertEquals(Screen.MenuCategories(stdIndex), n.screen)
    }

    @Test
    fun pMenuShortcutReturnsToPMenu() {
        val n = nav()
        n.openPMenu()
        n.pMenuPage(1)                                    // page 2/3 holds BEEP
        n.pMenuSelect(HC90eMenu.BEEP)
        n.selectOption(FirmwareSettings.BEEP_OFF)
        n.ok()
        assertFalse(n.settings.beepOn)
        assertEquals(Screen.PMenu(1), n.screen)
    }

    @Test
    fun drumRotationFollowsMenuRotate() {
        val normal = nav()
        normal.openPMenu(); normal.pMenuSelect(HC90eMenu.P_MENU)
        normal.drumStep(down = true)
        assertEquals(Screen.MenuCategories(1), normal.screen)        // ▼ = next row, in list order
        normal.drumStep(down = false)
        normal.drumStep(down = false)
        assertEquals(Screen.MenuCategories(0), normal.screen)        // stops at the top, no wrap
        repeat(20) { normal.drumStep(down = true) }
        assertEquals(Screen.MenuCategories(normal.menu.size - 1), normal.screen)

        val opposite = nav(s = FirmwareSettings(menuRotate = FirmwareSettings.MENU_ROTATE_OPPOSITE))
        opposite.openPMenu(); opposite.pMenuSelect(HC90eMenu.P_MENU)
        opposite.drumStep(down = false)
        assertEquals(Screen.MenuCategories(1), opposite.screen)
    }

    @Test
    fun drumListsOnlyWorkingItems() {
        val n = nav()
        n.openPMenu(); n.pMenuSelect(HC90eMenu.P_MENU)
        n.ok()                                             // CAMERA SET
        assertEquals(Screen.MenuItems(0, 0), n.screen)
        n.ok()                                             // first row works (SPOT METER), no dead PROGRAM AE
        assertEquals(Screen.Spot(HC90eMenu.SPOT_METER), n.screen)
    }

    @Test
    fun endSearchIsAnEffect() {
        val n = nav(Lamp.PLAY_EDIT)
        n.openPMenu()
        assertEquals(NavEffect.EndSearch, n.pMenuSelect(HC90eMenu.END_SEARCH))
        assertEquals(Screen.Main, n.screen)
    }

    @Test
    fun changingLampClosesMenus() {
        val n = nav()
        n.openPMenu()
        n.lamp = Lamp.PLAY_EDIT
        assertEquals(Screen.Main, n.screen)
    }

    @Test
    fun spotFocusOpensTheSpotScreenAndEndCloses() {
        val n = nav()
        n.openPMenu()
        n.pMenuSelect(HC90eMenu.SPOT_FOCUS)
        assertEquals(Screen.Spot(HC90eMenu.SPOT_FOCUS), n.screen)
        assertTrue(n.isMenuOpen)
        n.close()
        assertEquals(Screen.Main, n.screen)
    }

    @Test
    fun exposureWhiteBalanceAndFaderAreSettings() {
        val n = nav()
        n.openPMenu()
        n.pMenuSelect(HC90eMenu.EXPOSURE)
        n.selectOption("+1"); n.ok()
        assertEquals("+1", n.settings.exposure)
        n.pMenuSelect(HC90eMenu.FADER)
        n.selectOption(FirmwareSettings.FADER_WHITE); n.ok()
        assertEquals(FirmwareSettings.FADER_WHITE, n.settings.fader)
        val wb = FirmwareSettings().with(HC90eMenu.WHITE_BAL, FirmwareSettings.WB_OUTDOOR)
        assertEquals(FirmwareSettings.WB_OUTDOOR, wb.whiteBal)
    }

    @Test
    fun faderCurve() {
        assertEquals(1f, Fader.amount(0, fadeIn = true), 1e-6f)
        assertEquals(0f, Fader.amount(Fader.DURATION_MS, fadeIn = true), 1e-6f)
        assertEquals(0f, Fader.amount(0, fadeIn = false), 1e-6f)
        assertEquals(1f, Fader.amount(Fader.DURATION_MS * 2, fadeIn = false), 1e-6f)
        assertEquals(0.5f, Fader.amount(Fader.DURATION_MS / 2, fadeIn = false), 1e-6f)
        assertTrue(Fader.isDone(Fader.DURATION_MS))
    }
}
