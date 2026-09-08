package io.github.poweran2020.rclone.manager.data

import android.content.Context

enum class ThemeMode(val title: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色模式"),
    DARK("深色模式")
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
}
