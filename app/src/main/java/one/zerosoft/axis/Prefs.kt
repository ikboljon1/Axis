package one.zerosoft.axis

import android.content.Context

/** Small wrapper around SharedPreferences for the app settings. */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("axis", Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString(KEY_API, "") ?: ""
        set(v) = sp.edit().putString(KEY_API, v.trim()).apply()

    var mode: AnalysisMode
        get() = runCatching { AnalysisMode.valueOf(sp.getString(KEY_MODE, null) ?: "") }
            .getOrDefault(AnalysisMode.GENERAL)
        set(v) = sp.edit().putString(KEY_MODE, v.name).apply()

    var customPrompt: String
        get() = sp.getString(KEY_CUSTOM, "") ?: ""
        set(v) = sp.edit().putString(KEY_CUSTOM, v).apply()

    /** true = deeper but slower analysis (effort HIGH), false = fast (effort LOW). */
    var precise: Boolean
        get() = sp.getBoolean(KEY_PRECISE, false)
        set(v) = sp.edit().putBoolean(KEY_PRECISE, v).apply()

    private companion object {
        const val KEY_API = "api_key"
        const val KEY_MODE = "mode"
        const val KEY_CUSTOM = "custom_prompt"
        const val KEY_PRECISE = "precise"
    }
}
