package io.github.poweran2020.rclone.manager.util

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import io.github.poweran2020.rclone.manager.data.AppLanguage
import java.util.Locale

object LocaleUtil {
    fun getLocalizedContext(baseContext: Context, language: AppLanguage): Context {
        val targetLocale = when (language) {
            AppLanguage.SYSTEM -> {
                val systemLocales = Resources.getSystem().configuration.locales
                if (!systemLocales.isEmpty) systemLocales.get(0) else Locale.getDefault()
            }
            AppLanguage.ZH -> Locale.SIMPLIFIED_CHINESE
            AppLanguage.EN -> Locale.ENGLISH
        }
        val config = Configuration(baseContext.resources.configuration)
        config.setLocale(targetLocale)
        config.setLayoutDirection(targetLocale)
        return baseContext.createConfigurationContext(config)
    }
}
