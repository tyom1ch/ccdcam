package at.sturq.ccdcam.settings

import android.content.Context
import android.content.SharedPreferences
import at.sturq.ccdcam.profile.CameraProfile
import at.sturq.ccdcam.profile.Profiles

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

    var profile: CameraProfile
        get() = Profiles.byId(prefs.getString(KEY_PROFILE, null))
        set(value) {
            prefs.edit().putString(KEY_PROFILE, value.id).apply()
        }

    companion object {
        const val PREFS_NAME = "ccdcam"
        const val KEY_ASPECT = "aspect"
        const val KEY_PROFILE = "profile"
    }
}
