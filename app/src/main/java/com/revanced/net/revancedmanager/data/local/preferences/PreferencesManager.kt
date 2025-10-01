package com.revanced.net.revancedmanager.data.local.preferences

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale
import com.revanced.net.revancedmanager.domain.model.AppConfig
import com.revanced.net.revancedmanager.domain.model.Language
import com.revanced.net.revancedmanager.domain.model.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manager for handling shared preferences operations
 * Replaces the old SharedPreferencesUtil with proper dependency injection
 */
@Singleton
class PreferencesManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    
    private val sharedPreferences: SharedPreferences by lazy {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }
    
    companion object {
        private const val PREF_NAME = "ReVancedManagerPreferences"
        private const val CACHED_APP_LIST_KEY = "cached_app_list"
        private const val THEME_MODE_KEY = "theme_mode"
        private const val LANGUAGE_KEY = "language"
        private const val COMPACT_MODE_KEY = "compact_mode"
        private const val FIRST_RUN_KEY = "first_run"
        private const val PENDING_INSTALL_PREFIX = "pending_install_"
        private const val AUTO_INSTALL_KEY = "auto_install_enabled"
    }
    
    fun saveString(key: String, value: String) {
        sharedPreferences.edit().putString(key, value).apply()
    }
    
    fun getString(key: String, defaultValue: String = ""): String {
        return sharedPreferences.getString(key, defaultValue) ?: defaultValue
    }
    
    fun saveInt(key: String, value: Int) {
        sharedPreferences.edit().putInt(key, value).apply()
    }
    
    fun getInt(key: String, defaultValue: Int = 0): Int {
        return sharedPreferences.getInt(key, defaultValue)
    }
    
    fun saveBoolean(key: String, value: Boolean) {
        sharedPreferences.edit().putBoolean(key, value).apply()
    }
    
    fun getBoolean(key: String, defaultValue: Boolean = false): Boolean {
        return sharedPreferences.getBoolean(key, defaultValue)
    }
    
    fun saveLong(key: String, value: Long) {
        sharedPreferences.edit().putLong(key, value).apply()
    }
    
    fun getLong(key: String, defaultValue: Long = 0L): Long {
        return sharedPreferences.getLong(key, defaultValue)
    }
    
    fun saveFloat(key: String, value: Float) {
        sharedPreferences.edit().putFloat(key, value).apply()
    }
    
    fun getFloat(key: String, defaultValue: Float = 0f): Float {
        return sharedPreferences.getFloat(key, defaultValue)
    }
    
    fun removeKey(key: String) {
        sharedPreferences.edit().remove(key).apply()
    }
    
    fun clearAll() {
        sharedPreferences.edit().clear().apply()
    }
    
    /**
     * Save cached app list as JSON string
     */
    fun saveCachedAppList(json: String) {
        saveString(CACHED_APP_LIST_KEY, json)
    }
    
    /**
     * Get cached app list JSON string
     */
    fun getCachedAppList(): String {
        return getString(CACHED_APP_LIST_KEY)
    }
    
    /**
     * Save app configuration
     */
    fun saveAppConfig(config: AppConfig) {
        android.util.Log.d("PreferencesManager", "💾 === SAVE APP CONFIG START ===")
        android.util.Log.d("PreferencesManager", "💾 Saving theme: ${config.themeMode}")
        android.util.Log.d("PreferencesManager", "💾 Saving language: ${config.language.displayName} (${config.language.code})")
        android.util.Log.d("PreferencesManager", "💾 Saving compact mode: ${config.compactMode}")
        
        saveString(THEME_MODE_KEY, config.themeMode.name)
        saveString(LANGUAGE_KEY, config.language.code)
        saveBoolean(COMPACT_MODE_KEY, config.compactMode)
        
        // Verify what was actually saved
        val savedTheme = getString(THEME_MODE_KEY, "NOT_FOUND")
        val savedLanguage = getString(LANGUAGE_KEY, "NOT_FOUND")
        val savedCompactMode = getBoolean(COMPACT_MODE_KEY, true)
        android.util.Log.d("PreferencesManager", "💾 Verified saved theme: '$savedTheme'")
        android.util.Log.d("PreferencesManager", "💾 Verified saved language: '$savedLanguage'")
        android.util.Log.d("PreferencesManager", "💾 Verified saved compact mode: '$savedCompactMode'")
        android.util.Log.d("PreferencesManager", "💾 === SAVE APP CONFIG END ===")
    }
    
    /**
     * Check if this is the first run of the app
     */
    private fun isFirstRun(): Boolean {
        val firstRun = getBoolean(FIRST_RUN_KEY, true)
        android.util.Log.d("PreferencesManager", "🚀 Is first run: $firstRun")
        return firstRun
    }
    
    /**
     * Mark that the app has been run before
     */
    private fun setFirstRunCompleted() {
        android.util.Log.d("PreferencesManager", "🚀 Setting first run completed")
        saveBoolean(FIRST_RUN_KEY, false)
    }
    
    /**
     * Get app configuration with robust fallback and simplified logic
     */
    fun getAppConfig(): AppConfig {
        android.util.Log.d("PreferencesManager", "🔧 === GET APP CONFIG START ===")
        
        val themeModeString = getString(THEME_MODE_KEY, ThemeMode.DARK.name) // Default to DARK as requested
        android.util.Log.d("PreferencesManager", "🔧 Theme mode string from prefs: '$themeModeString'")

        // Simplified language handling - NO system language detection on first run
        // This avoids conflicts and ensures user's choice is always respected
        val languageCode = getString(LANGUAGE_KEY, Language.ENGLISH.code)
        android.util.Log.d("PreferencesManager", "🔧 Loaded language code from preferences: '$languageCode'")
        
        // Mark first run as completed if not already done (but don't change language)
        if (isFirstRun()) {
            android.util.Log.d("PreferencesManager", "🔧 First run detected, marking as completed without changing language")
            setFirstRunCompleted()
        }

        // Parse theme mode with fallback to DARK
        val themeMode = try {
            val parsed = ThemeMode.valueOf(themeModeString)
            android.util.Log.d("PreferencesManager", "🔧 Parsed theme mode: $parsed")
            // Validate that the parsed value is actually a valid enum value
            if (ThemeMode.values().contains(parsed)) {
                parsed
            } else {
                android.util.Log.w("PreferencesManager", "🔧 Invalid theme mode, using DARK fallback")
                ThemeMode.DARK // Fallback to DARK as requested
            }
        } catch (e: IllegalArgumentException) {
            android.util.Log.w("PreferencesManager", "🔧 Failed to parse theme mode, using DARK fallback", e)
            ThemeMode.DARK // Fallback to DARK as requested
        } catch (e: Exception) {
            android.util.Log.w("PreferencesManager", "🔧 Exception parsing theme mode, using DARK fallback", e)
            ThemeMode.DARK // Fallback to DARK for any other errors
        }

        // Parse language with fallback to ENGLISH - BUT ONLY AS LAST RESORT
        val language = try {
            val foundLanguage = Language.values().find { it.code == languageCode }
            if (foundLanguage != null) {
                android.util.Log.d("PreferencesManager", "🔧 Found language: ${foundLanguage.displayName} (${foundLanguage.code})")
                foundLanguage
            } else {
                android.util.Log.w("PreferencesManager", "🔧 ⚠️ Language code '$languageCode' not found in enum, using ENGLISH fallback")
                android.util.Log.w("PreferencesManager", "🔧 Available language codes: ${Language.values().map { it.code }.joinToString(", ")}")
                Language.ENGLISH // Fallback to ENGLISH as requested
            }
        } catch (e: Exception) {
            android.util.Log.e("PreferencesManager", "🔧 💥 Exception parsing language, using ENGLISH fallback", e)
            Language.ENGLISH // Fallback to ENGLISH for any errors
        }
        
        // Load compact mode with default value of true (matching AppConfig default)
        val compactMode = getBoolean(COMPACT_MODE_KEY, true)
        android.util.Log.d("PreferencesManager", "🔧 Loaded compact mode: $compactMode")
        
        val config = AppConfig(themeMode, language, compactMode)
        android.util.Log.d("PreferencesManager", "🔧 Final config: theme=${config.themeMode}, language=${config.language.displayName} (${config.language.code}), compactMode=${config.compactMode}")
        android.util.Log.d("PreferencesManager", "🔧 === GET APP CONFIG END ===")

        return config
    }

    /**
     * Save pending installation state
     */
    fun savePendingInstall(packageName: String, filePath: String) {
        saveString("${PENDING_INSTALL_PREFIX}${packageName}", filePath)
    }

    /**
     * Get pending installation file path
     */
    fun getPendingInstallPath(packageName: String): String? {
        val path = getString("${PENDING_INSTALL_PREFIX}${packageName}")
        return if (path.isEmpty()) null else path
    }

    /**
     * Check if package has pending installation
     */
    fun hasPendingInstall(packageName: String): Boolean {
        return getPendingInstallPath(packageName) != null
    }
    
    /**
     * Enable/disable auto-install of downloaded apps
     */
    fun setAutoInstallEnabled(enabled: Boolean) {
        saveBoolean(AUTO_INSTALL_KEY, enabled)
    }
    
    /**
     * Check if auto-install is enabled
     */
    fun isAutoInstallEnabled(): Boolean {
        return getBoolean(AUTO_INSTALL_KEY, true) // Default to true for convenience
    }
}