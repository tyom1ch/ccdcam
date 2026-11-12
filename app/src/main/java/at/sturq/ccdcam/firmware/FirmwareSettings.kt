package at.sturq.ccdcam.firmware

/** POWER switch lamps (guide p. 34). */
enum class Lamp { TAPE, MEMORY, PLAY_EDIT }

/** Settings the emulated firmware keeps, with the HC90E defaults (guide p. 51–55). */
data class FirmwareSettings(
    val recMode: RecMode = RecMode.SP,
    val audioMode: String = AUDIO_12BIT,
    val dataCode: String = DATA_CODE_OFF,
    val remaining: String = REMAINING_AUTO,
    val beep: String = BEEP_ON,
    val menuRotate: String = MENU_ROTATE_NORMAL,
    val cassette: VirtualCassette? = VirtualCassette.fresh(),
) {
    val beepOn: Boolean get() = beep == BEEP_ON

    /** Current value (option id) of a menu item, or null if the item has no stored value. */
    fun valueOf(itemId: String): String? = when (itemId) {
        HC90eMenu.REC_MODE -> recMode.name
        HC90eMenu.AUDIO_MODE -> audioMode
        HC90eMenu.DATA_CODE -> dataCode
        HC90eMenu.REMAINING -> remaining
        HC90eMenu.BEEP -> beep
        HC90eMenu.MENU_ROTATE -> menuRotate
        else -> null
    }

    /** Returns settings with [itemId] set to [optionId]; unknown items/options are rejected. */
    fun with(itemId: String, optionId: String): FirmwareSettings {
        val item = HC90eMenu.item(itemId)
        require(item.options.any { it.id == optionId }) { "$optionId is not an option of $itemId" }
        return when (itemId) {
            HC90eMenu.REC_MODE -> copy(recMode = RecMode.valueOf(optionId))
            HC90eMenu.AUDIO_MODE -> copy(audioMode = optionId)
            HC90eMenu.DATA_CODE -> copy(dataCode = optionId)
            HC90eMenu.REMAINING -> copy(remaining = optionId)
            HC90eMenu.BEEP -> copy(beep = optionId)
            HC90eMenu.MENU_ROTATE -> copy(menuRotate = optionId)
            else -> throw IllegalArgumentException("$itemId has no stored value")
        }
    }

    companion object {
        const val AUDIO_12BIT = "12BIT"
        const val AUDIO_16BIT = "16BIT"
        const val DATA_CODE_OFF = "OFF"
        const val DATA_CODE_DATE_TIME = "DATE/TIME"
        const val DATA_CODE_CAMERA_DATA = "CAMERA DATA"
        const val REMAINING_AUTO = "AUTO"
        const val REMAINING_ON = "ON"
        const val BEEP_ON = "ON"
        const val BEEP_OFF = "OFF"
        const val MENU_ROTATE_NORMAL = "NORMAL"
        const val MENU_ROTATE_OPPOSITE = "OPPOSITE"
    }
}
