package io.github.poweran2020.rclone.manager.data

import android.content.Context
import io.github.poweran2020.rclone.manager.R

enum class ThemeMode(val titleResId: Int) {
    SYSTEM(R.string.theme_system),
    LIGHT(R.string.theme_light),
    DARK(R.string.theme_dark)
}

enum class AppLanguage(val titleResId: Int, val code: String) {
    SYSTEM(R.string.lang_system, ""),
    ZH(R.string.lang_zh, "zh"),
    EN(R.string.lang_en, "en")
}

class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    fun getThemeMode(): ThemeMode {
        val name = prefs.getString("theme_mode", ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name
        return runCatching { ThemeMode.valueOf(name) }.getOrDefault(ThemeMode.SYSTEM)
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString("theme_mode", mode.name).apply()
    }

    fun getAppLanguage(): AppLanguage {
        val name = prefs.getString("app_language", AppLanguage.SYSTEM.name) ?: AppLanguage.SYSTEM.name
        return runCatching { AppLanguage.valueOf(name) }.getOrDefault(AppLanguage.SYSTEM)
    }

    fun setAppLanguage(lang: AppLanguage) {
        prefs.edit().putString("app_language", lang.name).apply()
    }
}
