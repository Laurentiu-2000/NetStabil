package ro.netstabil.app.i18n

import androidx.compose.runtime.compositionLocalOf
import ro.netstabil.app.data.AppLanguage
import java.util.Locale

object I18n {
    @Volatile
    var currentLanguage: AppLanguage = AppLanguage.AUTO
        private set

    @Volatile
    var currentIsRo: Boolean = isSystemLocaleRomanian()
        private set

    fun updateLanguage(lang: AppLanguage) {
        currentLanguage = lang
        currentIsRo = resolveIsRomanian(lang)
    }

    fun resolveIsRomanian(
        lang: AppLanguage,
        defaultLocale: Locale = Locale.getDefault()
    ): Boolean = when (lang) {
        AppLanguage.AUTO -> defaultLocale.language.startsWith("ro", ignoreCase = true)
        AppLanguage.RO -> true
        AppLanguage.EN -> false
    }

    fun isSystemLocaleRomanian(): Boolean {
        val code = runCatching { Locale.getDefault().language }.getOrDefault("en")
        return code.startsWith("ro", ignoreCase = true)
    }
}

val LocalIsRo = compositionLocalOf { true }

fun tr(isRo: Boolean, ro: String, en: String): String = if (isRo) ro else en

fun trNow(ro: String, en: String): String = if (I18n.currentIsRo) ro else en
