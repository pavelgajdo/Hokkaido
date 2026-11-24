// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.utils

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import org.yuzu.yuzu_emu.R
import java.util.Locale

/** One app-wide locale for the Hokkaido UI and Banana's other activities/processes. */
object AppLanguage {
    // Keep these indices in step with appLanguageValues. Existing config files use the indices.
    private val codes = arrayOf(
        "system", "en", "es", "fr", "de", "it", "pt-PT", "pt-BR", "ru", "ja", "ko",
        "zh-CN", "zh-TW", "pl", "cs", "nb", "hu", "uk", "vi", "id", "ar", "ckb", "fa",
        "he", "sr", "th"
    )

    val usesPlatformLocales: Boolean get() = Build.VERSION.SDK_INT >= 33

    fun selectedIndex(context: Context): Int {
        if (!usesPlatformLocales) return NativeConfig.getInt("app_language", true)
        val locales = context.getSystemService(LocaleManager::class.java)?.applicationLocales
        if (locales == null || locales.isEmpty) return 0
        val selected = locales[0] ?: return 0
        return codes.indexOfFirst { it != "system" && matches(selected, Locale.forLanguageTag(it)) }
    }

    fun selectedLabel(context: Context): String {
        if (!usesPlatformLocales) {
            val index = selectedIndex(context)
            return context.resources.getStringArray(R.array.appLanguageNames)
                .getOrElse(index) { context.getString(R.string.app_language_system) }
        }
        val locales = context.getSystemService(LocaleManager::class.java)?.applicationLocales
        val selected = if (locales == null || locales.isEmpty) null else locales[0]
        return selected?.getDisplayName(selected) ?: context.getString(R.string.app_language_system)
    }

    /** An empty application locale list means follow the device, including later system changes. */
    fun select(context: Context, index: Int) {
        if (!usesPlatformLocales) return
        val code = codes.getOrElse(index) { "system" }
        val locales = if (code == "system") LocaleList.getEmptyLocaleList()
            else LocaleList.forLanguageTags(code)
        context.getSystemService(LocaleManager::class.java)?.applicationLocales = locales
    }

    /** Preserve an explicitly selected legacy language once; never overwrite an Android selection. */
    fun migrateLegacySelection(context: Context) {
        if (!usesPlatformLocales) return
        val prefs = context.getSharedPreferences("fusion_language", Context.MODE_PRIVATE)
        if (prefs.getBoolean("platform_locale_migrated", false)) return
        val manager = context.getSystemService(LocaleManager::class.java) ?: return
        val legacy = NativeConfig.getInt("app_language", true)
        // Record before requesting the configuration change, which can recreate activities.
        prefs.edit().putBoolean("platform_locale_migrated", true).apply()
        if (manager.applicationLocales.isEmpty && legacy in 1 until codes.size) {
            select(context, legacy)
        }
    }

    fun wrapLegacyContext(context: Context): Context {
        // Android 13 applies the same locale to all contexts and processes. A second wrapper here
        // used to leave the library and the host displaying different languages.
        if (usesPlatformLocales) return context
        val code = codes.getOrElse(NativeConfig.getInt("app_language", true)) { "system" }
        val locales = if (code == "system") Resources.getSystem().configuration.locales
            else LocaleList.forLanguageTags(code)
        if (!locales.isEmpty) Locale.setDefault(locales[0]!!)
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocales(locales)
        return context.createConfigurationContext(configuration)
    }

    private fun matches(selected: Locale, candidate: Locale): Boolean {
        if (selected.language != candidate.language) return false
        if (candidate.language == "zh") return chineseScript(selected) == chineseScript(candidate)
        if (candidate.language == "pt") {
            return (selected.country == "BR") == (candidate.country == "BR")
        }
        return true
    }

    private fun chineseScript(locale: Locale): String = locale.script.ifEmpty {
        if (locale.country in listOf("TW", "HK", "MO")) "Hant" else "Hans"
    }
}
