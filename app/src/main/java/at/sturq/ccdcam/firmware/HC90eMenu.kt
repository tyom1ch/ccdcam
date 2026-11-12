package at.sturq.ccdcam.firmware

/** One selectable value of a menu item. [label] is the on-screen text. */
data class MenuOption(val id: String, val label: String = id)

/**
 * A menu item as listed in the DCR-HC90E Operating Guide (p. 36–37).
 *
 * @param lamps POWER switch positions in which the item is available. Only verified for the
 *   emulated items; for the others the guide's availability table could not be read and
 *   they are shown grayed out anyway.
 * @param emulated true if the app implements the item; others are shown grayed out
 *   ("Unavailable items will be grayed out", guide p. 34).
 */
data class MenuItem(
    val id: String,
    val label: String = id,
    val options: List<MenuOption> = emptyList(),
    val lamps: Set<Lamp> = Lamp.entries.toSet(),
    val emulated: Boolean = false,
) {
    fun isAvailable(lamp: Lamp): Boolean = emulated && lamp in lamps
}

data class MenuCategory(val id: String, val label: String, val items: List<MenuItem>)

/** Menu tree and Personal Menu of the DCR-HC90E (docs/hc90e-ui-references.md). */
object HC90eMenu {

    // Emulated item ids (also used as stable settings keys).
    const val REC_MODE = "REC MODE"
    const val AUDIO_MODE = "AUDIO MODE"
    const val DATA_CODE = "DATA CODE"
    const val REMAINING = "REMAINING"
    const val BEEP = "BEEP"
    const val MENU_ROTATE = "MENU ROTATE"
    const val END_SEARCH = "END SEARCH"

    // Personal Menu entries that are not menu items.
    const val P_MENU = "MENU"
    const val P_MENU_SET_UP = "P-MENU SET UP"

    private fun opts(vararg ids: String) = ids.map { MenuOption(it) }

    private val TAPE_AND_PLAY = setOf(Lamp.TAPE, Lamp.PLAY_EDIT)

    val categories: List<MenuCategory> = listOf(
        MenuCategory(
            "CAMERA SET", "CAMERA SET",
            listOf(
                "PROGRAM AE", "SPOT METER", "EXPOSURE", "WHITE BAL.", "SHARPNESS",
                "AUTO SHUTTER", "AE SHIFT", "SPOT FOCUS", "FOCUS", "TELE MACRO", "FLASH SET",
                "SUPER NS", "NS LIGHT", "COLOR SLOW S", "ZEBRA", "SELF-TIMER", "DIGITAL ZOOM",
                "STEADYSHOT", "PROGRES.REC",
            ).map { MenuItem(it) },
        ),
        MenuCategory(
            "MEMORY SET", "MEMORY SET",
            listOf(
                "STILL SET", "MOVIE SET", "ALL ERASE", "FORMAT", "FILE NO.", "NEW FOLDER",
                "REC FOLDER", "PB FOLDER",
            ).map { MenuItem(it) },
        ),
        MenuCategory(
            "PICT.APPLI.", "PICT.APPLI.",
            listOf(
                "FADER", "SLIDE SHOW", "D. EFFECT", "PICT. EFFECT", "FRAME REC", "SMTH INT.REC",
                "INT.REC-STL", "DEMO MODE",
            ).map { MenuItem(it) },
        ),
        MenuCategory(
            "EDIT/PLAY", "EDIT/PLAY",
            listOf("VAR. SPD PB", "REC CTRL", "AUD DUB CTRL", "REC MOVIE", "BURN DVD", "BURN VCD")
                .map { MenuItem(it) } +
                MenuItem(END_SEARCH, lamps = TAPE_AND_PLAY, emulated = true),
        ),
        MenuCategory(
            "STANDARD SET", "STANDARD SET",
            listOf(
                MenuItem(REC_MODE, options = opts("SP", "LP"), lamps = TAPE_AND_PLAY, emulated = true),
                MenuItem(
                    AUDIO_MODE, options = opts(FirmwareSettings.AUDIO_12BIT, FirmwareSettings.AUDIO_16BIT),
                    lamps = setOf(Lamp.TAPE), emulated = true,
                ),
                MenuItem("VOLUME"), MenuItem("MULTI-SOUND"), MenuItem("AUDIO MIX"),
                MenuItem("EXT SUR MIC"), MenuItem("LCD/VF SET"), MenuItem("A/V→DV OUT"),
                MenuItem("VIDEO INPUT"), MenuItem("TV TYPE"), MenuItem("USB-CAMERA"),
                MenuItem("USB-PLY/EDT"), MenuItem("DISP GUIDE"), MenuItem("GUIDEFRAME"),
                MenuItem(
                    DATA_CODE,
                    options = opts(
                        FirmwareSettings.DATA_CODE_OFF, FirmwareSettings.DATA_CODE_DATE_TIME,
                        FirmwareSettings.DATA_CODE_CAMERA_DATA,
                    ),
                    lamps = setOf(Lamp.PLAY_EDIT), emulated = true,
                ),
                MenuItem(
                    REMAINING, options = opts(FirmwareSettings.REMAINING_AUTO, FirmwareSettings.REMAINING_ON),
                    lamps = TAPE_AND_PLAY, emulated = true,
                ),
                MenuItem("REMOTE CTRL"), MenuItem("REC LAMP"),
                MenuItem(BEEP, options = opts(FirmwareSettings.BEEP_ON, FirmwareSettings.BEEP_OFF), emulated = true),
                MenuItem("DISP OUTPUT"),
                MenuItem(
                    MENU_ROTATE,
                    options = opts(FirmwareSettings.MENU_ROTATE_NORMAL, FirmwareSettings.MENU_ROTATE_OPPOSITE),
                    emulated = true,
                ),
                MenuItem("A.SHUT OFF"), MenuItem("CALIBRATION"),
            ),
        ),
        MenuCategory(
            "TIME/LANGU.", "TIME/LANGU.",
            listOf("CLOCK SET", "WORLD TIME", "LANGUAGE").map { MenuItem(it) },
        ),
    )

    private val byId: Map<String, MenuItem> = categories.flatMap { it.items }.associateBy { it.id }

    fun item(id: String): MenuItem = byId[id] ?: throw IllegalArgumentException("unknown menu item $id")

    fun itemOrNull(id: String): MenuItem? = byId[id]

    /** Six buttons per Personal Menu page (2 × 3 grid, guide p. 34). */
    const val P_MENU_PAGE_SIZE = 6

    /**
     * CAMERA-TAPE Personal Menu. Page 1 is documented (guide p. 56 figure, ITmedia photo);
     * pages 2–3 are not, so their contents are an approximation ending with P-MENU SET UP.
     * Order is row by row: left, right.
     */
    val pMenuCameraTape: List<String> = listOf(
        P_MENU, "DISP GUIDE",
        "TELE MACRO", "EXPOSURE",
        "SPOT FOCUS", "FADER",
        // page 2 (approximation)
        "PROGRAM AE", "SPOT METER",
        "WHITE BAL.", "SUPER NS",
        "NS LIGHT", "COLOR SLOW S",
        // page 3 (approximation)
        REC_MODE, AUDIO_MODE,
        REMAINING, BEEP,
        END_SEARCH, P_MENU_SET_UP,
    )

    /** PLAY/EDIT Personal Menu. Page 1 is documented (guide p. 34); the rest is an approximation. */
    val pMenuPlayEdit: List<String> = listOf(
        P_MENU, "DISP GUIDE",
        END_SEARCH, "VOLUME",
        "VAR. SPD PB", "REC CTRL",
        // page 2 (approximation)
        DATA_CODE, REC_MODE,
        REMAINING, BEEP,
        "MULTI-SOUND", P_MENU_SET_UP,
    )

    fun pMenuFor(lamp: Lamp): List<String> = if (lamp == Lamp.PLAY_EDIT) pMenuPlayEdit else pMenuCameraTape

    fun pageCount(entries: List<String>): Int = (entries.size + P_MENU_PAGE_SIZE - 1) / P_MENU_PAGE_SIZE

    fun page(entries: List<String>, page: Int): List<String> =
        entries.drop(page * P_MENU_PAGE_SIZE).take(P_MENU_PAGE_SIZE)

    /** Whether a Personal Menu entry can be used in [lamp]. */
    fun pMenuEntryAvailable(entry: String, lamp: Lamp): Boolean = when (entry) {
        P_MENU -> true
        P_MENU_SET_UP -> false
        else -> itemOrNull(entry)?.isAvailable(lamp) ?: false
    }
}
