package io.github.poweran2020.rclone.manager.util

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import io.github.poweran2020.rclone.manager.data.AppLanguage
import java.util.Locale

object LocaleUtil {
    fun getTargetLocale(language: AppLanguage): Locale {
        return when (language) {
            AppLanguage.SYSTEM -> {
                val systemLocales = Resources.getSystem().configuration.locales
                if (!systemLocales.isEmpty) systemLocales.get(0) else Locale.getDefault()
            }
            AppLanguage.ZH -> Locale.SIMPLIFIED_CHINESE
            AppLanguage.EN -> Locale.ENGLISH
        }
    }

    fun getLocalizedConfiguration(baseConfig: Configuration, language: AppLanguage): Configuration {
        val targetLocale = getTargetLocale(language)
        val config = Configuration(baseConfig)
        config.setLocale(targetLocale)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(LocaleList(targetLocale))
        }
        config.setLayoutDirection(targetLocale)
        return config
    }

    fun getLocalizedContext(baseContext: Context, language: AppLanguage): Context {
        val targetLocale = getTargetLocale(language)
        Locale.setDefault(targetLocale)
        val config = getLocalizedConfiguration(baseContext.resources.configuration, language)
        return baseContext.createConfigurationContext(config)
    }

    fun applyLocale(activity: Activity, language: AppLanguage) {
        val targetLocale = getTargetLocale(language)
        Locale.setDefault(targetLocale)

        val config = getLocalizedConfiguration(activity.resources.configuration, language)

        val res = activity.resources
        @Suppress("DEPRECATION")
        res.updateConfiguration(config, res.displayMetrics)

        val appRes = activity.applicationContext.resources
        @Suppress("DEPRECATION")
        appRes.updateConfiguration(config, appRes.displayMetrics)
    }
}
