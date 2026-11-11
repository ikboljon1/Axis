package one.zerosoft.axis

import android.content.Context

/** Small wrapper around SharedPreferences for the app settings. */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("axis", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = sp.getString(KEY_SERVER, "") ?: ""
        set(v) = sp.edit().putString(KEY_SERVER, v.trim()).apply()

    var token: String
        get() = sp.getString(KEY_TOKEN, "") ?: ""
        set(v) = sp.edit().putString(KEY_TOKEN, v.trim()).apply()

    val isConfigured: Boolean get() = serverUrl.isNotBlank() && token.isNotBlank()

    var mode: AnalysisMode
        get() = runCatching { AnalysisMode.valueOf(sp.getString(KEY_MODE, null) ?: "") }
            .getOrDefault(AnalysisMode.GENERAL)
        set(v) = sp.edit().putString(KEY_MODE, v.name).apply()

    var customPrompt: String
        get() = sp.getString(KEY_CUSTOM, "") ?: ""
        set(v) = sp.edit().putString(KEY_CUSTOM, v).apply()

    /** true = stronger but slower model on the server, false = fast model. */
    var precise: Boolean
        get() = sp.getBoolean(KEY_PRECISE, false)
        set(v) = sp.edit().putBoolean(KEY_PRECISE, v).apply()

    private companion object {
        const val KEY_SERVER = "server_url"
        const val KEY_TOKEN = "token"
        const val KEY_MODE = "mode"
        const val KEY_CUSTOM = "custom_prompt"
        const val KEY_PRECISE = "precise"
    }
}
