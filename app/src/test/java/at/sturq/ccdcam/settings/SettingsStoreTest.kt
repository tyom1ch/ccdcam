package at.sturq.ccdcam.settings

import android.content.SharedPreferences
import at.sturq.ccdcam.firmware.FirmwareSettings
import at.sturq.ccdcam.firmware.RecMode
import at.sturq.ccdcam.firmware.VirtualCassette
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsStoreTest {

    @Test
    fun freshInstallDefaultsToOriginalBehaviour() {
        val store = SettingsStore(FakePrefs())
        assertEquals(AspectSetting.RATIO_16_9, store.aspect)
    }

    @Test
    fun readsAspectWrittenByPreProfileVersion() {
        // Pre-profile MainActivity wrote these exact strings under "aspect".
        assertEquals(AspectSetting.RATIO_4_3, SettingsStore(FakePrefs(mapOf("aspect" to "4:3"))).aspect)
        assertEquals(AspectSetting.RATIO_16_9, SettingsStore(FakePrefs(mapOf("aspect" to "16:9"))).aspect)
        assertEquals(AspectSetting.RATIO_16_9, SettingsStore(FakePrefs(mapOf("aspect" to "garbage"))).aspect)
    }

    @Test
    fun writesAspectInLegacyFormat() {
        val prefs = FakePrefs()
        val store = SettingsStore(prefs)
        store.aspect = AspectSetting.RATIO_4_3
        assertEquals("4:3", prefs.data["aspect"])
        store.aspect = AspectSetting.RATIO_16_9
        assertEquals("16:9", prefs.data["aspect"])
    }

    @Test
    fun firmwareDefaultsOnFreshInstall() {
        assertEquals(FirmwareSettings(), SettingsStore(FakePrefs()).firmware)
    }

    @Test
    fun firmwareRoundTripIncludingCassette() {
        val prefs = FakePrefs()
        val store = SettingsStore(prefs)
        val tape = VirtualCassette.fresh().afterRecording(125_000, RecMode.LP)
        val s = FirmwareSettings(
            recMode = RecMode.LP, audioMode = FirmwareSettings.AUDIO_16BIT,
            dataCode = FirmwareSettings.DATA_CODE_DATE_TIME, remaining = FirmwareSettings.REMAINING_ON,
            beep = FirmwareSettings.BEEP_OFF, menuRotate = FirmwareSettings.MENU_ROTATE_OPPOSITE,
            cassette = tape,
        )
        store.firmware = s
        assertEquals(s, SettingsStore(prefs).firmware)
        // Legacy keys untouched.
        assertNull(prefs.data["aspect"])
    }

    @Test
    fun ejectedCassettePersists() {
        val prefs = FakePrefs()
        SettingsStore(prefs).firmware = FirmwareSettings(cassette = null)
        assertNull(SettingsStore(prefs).firmware.cassette)
    }

    @Test
    fun corruptFirmwareValuesFallBackToDefaults() {
        val prefs = FakePrefs(mapOf(
            "fw_rec_mode" to "XP", "fw_beep" to "LOUD", "fw_tape_used_sp_ms" to "-5",
        ))
        val s = SettingsStore(prefs).firmware
        assertEquals(RecMode.SP, s.recMode)
        assertEquals(FirmwareSettings.BEEP_ON, s.beep)
        assertEquals(VirtualCassette.fresh(), s.cassette)
    }

    /** Minimal in-memory SharedPreferences (strings only — all SettingsStore needs). */
    private class FakePrefs(initial: Map<String, String> = emptyMap()) : SharedPreferences {
        val data = initial.toMutableMap()

        override fun getString(key: String?, defValue: String?): String? = key?.let { data[it] } ?: defValue
        override fun contains(key: String?) = key != null && data.containsKey(key)
        override fun getAll(): MutableMap<String, *> = data
        override fun edit(): SharedPreferences.Editor = Editor()

        override fun getStringSet(key: String?, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String?, defValue: Int) = defValue
        override fun getLong(key: String?, defValue: Long) = defValue
        override fun getFloat(key: String?, defValue: Float) = defValue
        override fun getBoolean(key: String?, defValue: Boolean) = defValue
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        inner class Editor : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, String?>()
            override fun putString(key: String, value: String?): SharedPreferences.Editor {
                pending[key] = value; return this
            }
            override fun remove(key: String): SharedPreferences.Editor {
                pending[key] = null; return this
            }
            override fun apply() { commit() }
            override fun commit(): Boolean {
                for ((k, v) in pending) if (v == null) data.remove(k) else data[k] = v
                pending.clear()
                return true
            }
            override fun putStringSet(key: String, values: MutableSet<String>?) = this
            override fun putInt(key: String, value: Int) = this
            override fun putLong(key: String, value: Long) = this
            override fun putFloat(key: String, value: Float) = this
            override fun putBoolean(key: String, value: Boolean) = this
            override fun clear() = this
        }
    }
}
