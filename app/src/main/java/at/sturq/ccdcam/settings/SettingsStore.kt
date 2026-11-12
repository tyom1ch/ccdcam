package at.sturq.ccdcam.settings

import android.content.Context
import android.content.SharedPreferences
import at.sturq.ccdcam.firmware.FirmwareSettings
import at.sturq.ccdcam.firmware.HC90eMenu
import at.sturq.ccdcam.firmware.RecMode
import at.sturq.ccdcam.firmware.VirtualCassette

/** Aspect ratio setting; [prefValue] is the exact string CCDCam has always stored. */
enum class AspectSetting(val prefValue: String) {
    RATIO_16_9("16:9"),
    RATIO_4_3("4:3"),
}

/**
 * Typed access to the app's existing SharedPreferences file ("ccdcam"). Keys and stored
 * values of the pre-profile app are kept as they were, so upgrading installs keep their
 * settings.
 */
class SettingsStore(private val prefs: SharedPreferences) {

    constructor(context: Context) :
        this(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

    /** Same rule as the original MainActivity: only an explicit "4:3" means 4:3. */
    var aspect: AspectSetting
        get() = if (prefs.getString(KEY_ASPECT, AspectSetting.RATIO_16_9.prefValue) ==
            AspectSetting.RATIO_4_3.prefValue
        ) AspectSetting.RATIO_4_3 else AspectSetting.RATIO_16_9
        set(value) {
            prefs.edit().putString(KEY_ASPECT, value.prefValue).apply()
        }

    /**
     * HC90E firmware settings and the cassette in the camcorder. Stored as strings under
     * `fw_*` keys; anything missing or invalid falls back to the HC90E defaults.
     */
    var firmware: FirmwareSettings
        get() {
            val d = FirmwareSettings()
            fun opt(key: String, itemId: String, def: String): String {
                val v = prefs.getString(key, null)
                return if (v != null && HC90eMenu.item(itemId).options.any { it.id == v }) v else def
            }
            fun long(key: String): Long? = prefs.getString(key, null)?.toLongOrNull()
            val recMode = prefs.getString(KEY_FW_REC_MODE, null)
                ?.let { v -> RecMode.entries.firstOrNull { it.name == v } } ?: d.recMode
            val cassette = when (prefs.getString(KEY_FW_TAPE_INSERTED, null)) {
                "false" -> null
                else -> runCatching {
                    VirtualCassette(
                        usedSpMs = long(KEY_FW_TAPE_USED_SP_MS) ?: 0L,
                        counterMs = long(KEY_FW_TAPE_COUNTER_MS) ?: 0L,
                    )
                }.getOrDefault(VirtualCassette.fresh())
            }
            return FirmwareSettings(
                recMode = recMode,
                audioMode = opt(KEY_FW_AUDIO_MODE, HC90eMenu.AUDIO_MODE, d.audioMode),
                dataCode = opt(KEY_FW_DATA_CODE, HC90eMenu.DATA_CODE, d.dataCode),
                remaining = opt(KEY_FW_REMAINING, HC90eMenu.REMAINING, d.remaining),
                beep = opt(KEY_FW_BEEP, HC90eMenu.BEEP, d.beep),
                menuRotate = opt(KEY_FW_MENU_ROTATE, HC90eMenu.MENU_ROTATE, d.menuRotate),
                burnOsd = opt(KEY_FW_BURN_OSD, HC90eMenu.BURN_OSD, d.burnOsd),
                exposure = opt(KEY_FW_EXPOSURE, HC90eMenu.EXPOSURE, d.exposure),
                whiteBal = opt(KEY_FW_WHITE_BAL, HC90eMenu.WHITE_BAL, d.whiteBal),
                fader = opt(KEY_FW_FADER, HC90eMenu.FADER, d.fader),
                superNs = opt(KEY_FW_SUPER_NS, HC90eMenu.SUPER_NS, d.superNs),
                audioEmu = opt(KEY_FW_AUDIO_EMU, HC90eMenu.AUDIO_EMU, d.audioEmu),
                origAudio = opt(KEY_FW_ORIG_AUDIO, HC90eMenu.ORIG_AUDIO, d.origAudio),
                tapeNoise = opt(KEY_FW_TAPE_NOISE, HC90eMenu.TAPE_NOISE, d.tapeNoise),
                tweaks = HC90eMenu.TWEAKS.mapNotNull { id ->
                    val first = HC90eMenu.item(id).options.first().id
                    opt(tweakKey(id), id, first).takeIf { it != first }?.let { id to it }
                }.toMap(),
                cassette = cassette,
            )
        }
        set(v) {
            val e = prefs.edit()
            for (id in HC90eMenu.TWEAKS) e.putString(tweakKey(id), v.tweak(id))
            e
                .putString(KEY_FW_REC_MODE, v.recMode.name)
                .putString(KEY_FW_AUDIO_MODE, v.audioMode)
                .putString(KEY_FW_DATA_CODE, v.dataCode)
                .putString(KEY_FW_REMAINING, v.remaining)
                .putString(KEY_FW_BEEP, v.beep)
                .putString(KEY_FW_MENU_ROTATE, v.menuRotate)
                .putString(KEY_FW_BURN_OSD, v.burnOsd)
                .putString(KEY_FW_EXPOSURE, v.exposure)
                .putString(KEY_FW_WHITE_BAL, v.whiteBal)
                .putString(KEY_FW_FADER, v.fader)
                .putString(KEY_FW_SUPER_NS, v.superNs)
                .putString(KEY_FW_AUDIO_EMU, v.audioEmu)
                .putString(KEY_FW_ORIG_AUDIO, v.origAudio)
                .putString(KEY_FW_TAPE_NOISE, v.tapeNoise)
                .putString(KEY_FW_TAPE_INSERTED, (v.cassette != null).toString())
                .putString(KEY_FW_TAPE_USED_SP_MS, (v.cassette?.usedSpMs ?: 0L).toString())
                .putString(KEY_FW_TAPE_COUNTER_MS, (v.cassette?.counterMs ?: 0L).toString())
                .apply()
        }

    companion object {
        const val PREFS_NAME = "ccdcam"
        const val KEY_ASPECT = "aspect"
        const val KEY_FW_REC_MODE = "fw_rec_mode"
        const val KEY_FW_AUDIO_MODE = "fw_audio_mode"
        const val KEY_FW_DATA_CODE = "fw_data_code"
        const val KEY_FW_REMAINING = "fw_remaining"
        const val KEY_FW_BEEP = "fw_beep"
        const val KEY_FW_MENU_ROTATE = "fw_menu_rotate"
        const val KEY_FW_BURN_OSD = "fw_burn_osd"
        const val KEY_FW_EXPOSURE = "fw_exposure"
        const val KEY_FW_WHITE_BAL = "fw_white_bal"
        const val KEY_FW_FADER = "fw_fader"
        const val KEY_FW_SUPER_NS = "fw_super_ns"
        const val KEY_FW_AUDIO_EMU = "fw_audio_emu"
        const val KEY_FW_ORIG_AUDIO = "fw_orig_audio"
        /** fw_tweak_scanlines, fw_tweak_ccd_noise, ... */
        fun tweakKey(itemId: String) = "fw_tweak_" + itemId.lowercase().replace(' ', '_')
        const val KEY_FW_TAPE_NOISE = "fw_tape_noise"
        const val KEY_FW_TAPE_INSERTED = "fw_tape_inserted"
        const val KEY_FW_TAPE_USED_SP_MS = "fw_tape_used_sp_ms"
        const val KEY_FW_TAPE_COUNTER_MS = "fw_tape_counter_ms"
    }
}
