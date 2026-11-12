package at.sturq.ccdcam.firmware

/** Screens of the emulated touch panel (docs/hc90e-ui-references.md, screens 1–4). */
sealed interface Screen {
    /** Recording/playback indicators only. */
    data object Main : Screen

    /** Personal Menu, [page] 0-based. */
    data class PMenu(val page: Int) : Screen

    /** MENU drum, category level. */
    data class MenuCategories(val index: Int) : Screen

    /** MENU drum, item level of [category]. */
    data class MenuItems(val category: Int, val index: Int) : Screen

    /** SPOT FOCUS / SPOT METER: touch the subject on the LCD; AUTO / END. */
    data class Spot(val itemId: String) : Screen

    /** Option screen of [itemId]; [selected] is the highlighted option, [returnTo] the caller. */
    data class Setting(val itemId: String, val selected: String, val returnTo: Screen) : Screen
}

/** Side effects the host (activity) has to carry out. */
sealed interface NavEffect {
    data object None : NavEffect
    data class SettingsChanged(val settings: FirmwareSettings) : NavEffect
    data object EndSearch : NavEffect
}

/**
 * Touch-panel navigation of the DCR-HC90E (guide p. 34): P-MENU → MENU → category → item →
 * setting, [OK] confirms, ↩ goes back one level, ✕ closes. Pure state machine, no Android.
 */
class FirmwareNavigator(
    settings: FirmwareSettings,
    lamp: Lamp,
) {
    var settings: FirmwareSettings = settings
        private set

    var lamp: Lamp = lamp
        set(value) {
            field = value
            screen = Screen.Main
        }

    var screen: Screen = Screen.Main
        private set

    val isMenuOpen: Boolean get() = screen != Screen.Main

    fun replaceSettings(newSettings: FirmwareSettings) {
        settings = newSettings
    }

    // ---- Main / close ----

    fun openPMenu() {
        screen = Screen.PMenu(0)
    }

    fun close() {
        screen = Screen.Main
    }

    // ---- Personal Menu ----

    private val pMenuEntries get() = HC90eMenu.pMenuFor(lamp)

    val pMenuPageCount: Int get() = HC90eMenu.pageCount(pMenuEntries)

    fun pMenuPageEntries(page: Int): List<String> = HC90eMenu.page(pMenuEntries, page)

    /** ⇑ / ⇓ page buttons; the page list wraps around like the camcorder's. */
    fun pMenuPage(delta: Int) {
        val s = screen as? Screen.PMenu ?: return
        val n = pMenuPageCount
        screen = Screen.PMenu(((s.page + delta) % n + n) % n)
    }

    fun pMenuSelect(entry: String): NavEffect {
        val s = screen as? Screen.PMenu ?: return NavEffect.None
        if (!HC90eMenu.pMenuEntryAvailable(entry, lamp)) return NavEffect.None
        if (entry == HC90eMenu.P_MENU) {
            screen = Screen.MenuCategories(0)
            return NavEffect.None
        }
        return activate(HC90eMenu.item(entry), returnTo = s)
    }

    // ---- MENU drum ----

    /**
     * ▲ / ▼ on the drum. [down] = the ▼ button. MENU ROTATE NORMAL "scrolls the menu items
     * downwards by touching ▼" (the item above moves into the bar), OPPOSITE scrolls upwards.
     */
    fun drumStep(down: Boolean) {
        val normal = settings.menuRotate == FirmwareSettings.MENU_ROTATE_NORMAL
        val delta = if (down == normal) -1 else 1
        screen = when (val s = screen) {
            is Screen.MenuCategories -> s.copy(index = wrap(s.index + delta, HC90eMenu.categories.size))
            is Screen.MenuItems -> s.copy(index = wrap(s.index + delta, HC90eMenu.categories[s.category].items.size))
            else -> s
        }
    }

    /** Touching a row of the drum selects it directly (guide p. 34 ③). */
    fun drumTouch(index: Int) {
        screen = when (val s = screen) {
            is Screen.MenuCategories -> s.copy(index = wrap(index, HC90eMenu.categories.size))
            is Screen.MenuItems -> s.copy(index = wrap(index, HC90eMenu.categories[s.category].items.size))
            else -> s
        }
    }

    // ---- OK / back ----

    fun ok(): NavEffect = when (val s = screen) {
        is Screen.MenuCategories -> {
            screen = Screen.MenuItems(s.index, 0)
            NavEffect.None
        }
        is Screen.MenuItems -> {
            val item = HC90eMenu.categories[s.category].items[s.index]
            if (item.isAvailable(lamp)) activate(item, returnTo = s) else NavEffect.None
        }
        is Screen.Setting -> {
            screen = s.returnTo
            if (settings.valueOf(s.itemId) != s.selected) {
                settings = settings.with(s.itemId, s.selected)
                NavEffect.SettingsChanged(settings)
            } else {
                NavEffect.None
            }
        }
        else -> NavEffect.None
    }

    /** ↩ : one level back without changing anything. */
    fun back() {
        screen = when (val s = screen) {
            is Screen.Setting -> s.returnTo
            is Screen.MenuItems -> Screen.MenuCategories(s.category)
            is Screen.MenuCategories -> Screen.PMenu(0)
            else -> Screen.Main
        }
    }

    // ---- Setting screen ----

    fun selectOption(optionId: String) {
        val s = screen as? Screen.Setting ?: return
        if (HC90eMenu.item(s.itemId).options.any { it.id == optionId }) {
            screen = s.copy(selected = optionId)
        }
    }

    /** True if the setting screen shows OK (value changed) rather than ↩. */
    val settingChanged: Boolean
        get() = (screen as? Screen.Setting)?.let { settings.valueOf(it.itemId) != it.selected } ?: false

    private fun activate(item: MenuItem, returnTo: Screen): NavEffect {
        if (item.id == HC90eMenu.END_SEARCH) {
            screen = Screen.Main
            return NavEffect.EndSearch
        }
        if (item.id == HC90eMenu.SPOT_FOCUS || item.id == HC90eMenu.SPOT_METER) {
            screen = Screen.Spot(item.id)
            return NavEffect.None
        }
        val current = settings.valueOf(item.id) ?: return NavEffect.None
        screen = Screen.Setting(item.id, current, returnTo)
        return NavEffect.None
    }

    private fun wrap(i: Int, n: Int) = ((i % n) + n) % n
}
