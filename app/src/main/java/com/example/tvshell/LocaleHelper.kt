package com.example.tvshell

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object LocaleHelper {
    const val SYSTEM = "system"
    const val ZH = "zh"
    const val EN = "en"

    val OPTIONS = arrayOf(SYSTEM, ZH, EN)

    fun wrap(context: Context, language: String?): Context {
        val locale = localeFor(language) ?: return context
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }

    fun localeFor(language: String?): Locale? {
        return when (language) {
            ZH -> Locale.SIMPLIFIED_CHINESE
            EN -> Locale.ENGLISH
            else -> null
        }
    }

    fun labelRes(tag: String): Int {
        return when (tag) {
            ZH -> R.string.lang_zh
            EN -> R.string.lang_en
            else -> R.string.lang_system
        }
    }

    fun resolved(context: Context, preference: String?): String {
        return when (preference) {
            ZH -> ZH
            EN -> EN
            else -> {
                val sys = context.resources.configuration.locales[0].language
                if (sys.startsWith("zh")) ZH else if (sys.startsWith("en")) EN else ZH
            }
        }
    }
}
