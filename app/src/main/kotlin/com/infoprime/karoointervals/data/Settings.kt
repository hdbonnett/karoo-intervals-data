package com.infoprime.karoointervals.data

import android.content.Context
import java.time.DayOfWeek

data class Settings(
    /** Intervals athlete id, e.g. i12345 (shown in Intervals under Settings > Developer). */
    val athleteId: String = "",
    /** Intervals API key from Settings > Developer. */
    val apiKey: String = "",
    val weekStart: DayOfWeek = DayOfWeek.MONDAY,
    val liveReplacesTodaysPlan: Boolean = true,
    /** Overrides the FTP taken from the latest ride in Intervals. */
    val ftpOverride: Int? = null,
) {
    val isConfigured: Boolean get() = athleteId.isNotBlank() && apiKey.isNotBlank()
}

/**
 * Stored in app-private SharedPreferences. Note the API key is readable by anyone with
 * root or adb backup access to the device; backups are disabled in the manifest.
 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): Settings = Settings(
        athleteId = prefs.getString(KEY_ATHLETE, "").orEmpty(),
        apiKey = prefs.getString(KEY_API_KEY, "").orEmpty(),
        weekStart = runCatching { DayOfWeek.valueOf(prefs.getString(KEY_WEEK_START, null)!!) }
            .getOrDefault(DayOfWeek.MONDAY),
        liveReplacesTodaysPlan = prefs.getBoolean(KEY_REPLACE_PLAN, true),
        ftpOverride = prefs.getInt(KEY_FTP, 0).takeIf { it > 0 },
    )

    fun save(settings: Settings) {
        prefs.edit()
            .putString(KEY_ATHLETE, settings.athleteId.trim())
            .putString(KEY_API_KEY, settings.apiKey.trim())
            .putString(KEY_WEEK_START, settings.weekStart.name)
            .putBoolean(KEY_REPLACE_PLAN, settings.liveReplacesTodaysPlan)
            .putInt(KEY_FTP, settings.ftpOverride ?: 0)
            .apply()
    }

    private companion object {
        const val KEY_ATHLETE = "athlete_id"
        const val KEY_API_KEY = "api_key"
        const val KEY_WEEK_START = "week_start"
        const val KEY_REPLACE_PLAN = "live_replaces_plan"
        const val KEY_FTP = "ftp_override"
    }
}
