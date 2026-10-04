package com.example.sscan.core.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.example.sscan.core.scanner.ScanCodeMode

enum class AppLanguage(val value: String) {
    FOLLOW_SYSTEM("follow_system"),
    CHINESE("zh"),
    ENGLISH("en");

    companion object {
        fun fromValue(value: String?): AppLanguage {
            return entries.firstOrNull { it.value == value } ?: FOLLOW_SYSTEM
        }
    }
}

enum class AppThemeMode(val value: String) {
    FOLLOW_SYSTEM("follow_system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromValue(value: String?): AppThemeMode {
            return entries.firstOrNull { it.value == value } ?: FOLLOW_SYSTEM
        }
    }
}

// 统一管理应用设置项，避免页面直接读写 SharedPreferences。
object AppSettings {

    private const val PREFS_NAME = "s_scan_settings"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_THEME = "theme"
    private const val KEY_SCAN_VIBRATION = "scan_vibration"
    private const val KEY_SCAN_TONE = "scan_tone"
    private const val KEY_SCAN_SPEECH = "scan_speech"
    private const val KEY_DUPLICATE_SPEECH = "duplicate_speech"
    private const val KEY_CONTINUOUS_SCAN = "continuous_scan"
    private const val KEY_SCAN_CODE_MODE = "scan_code_mode"
    private const val KEY_SCAN_THROTTLE_MS = "scan_throttle_ms"
    private const val KEY_FOCUS_AUTO_CANCEL_SECONDS = "focus_auto_cancel_seconds"
    private const val KEY_LOGIN_PHONE = "login_phone"
    private const val KEY_LOGIN_AT = "login_at"
    private const val KEY_SAVE_SCAN_IMAGE = "save_scan_image"
    private const val KEY_ALLOW_DUPLICATE_SCAN = "allow_duplicate_scan"
    private const val KEY_CLEAR_CONFIRM = "clear_confirm"
    private const val KEY_AUTO_COPY = "auto_copy"
    private const val KEY_SAVE_TO_ALBUM = "save_to_album"
    private const val KEY_SAVE_SEPARATE_FOLDER = "save_separate_folder"
    private const val KEY_CONTINUOUS_INTERVAL_MS = "continuous_interval_ms"
    private const val KEY_USER_NAME = "user_name"
    private const val KEY_HISTORY_FOLDERS = "history_folders"
    private const val KEY_REMEMBER_FOLDER = "remember_folder"
    private const val KEY_LAST_FOLDER = "last_folder"

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun applyAppPreferences(context: Context) {
        applyLanguage(context, getLanguage(context))
        applyTheme(getTheme(context))
    }

    /** 客户在中国，默认中文；用户可在设置里改成跟随系统或英文。 */
    fun getLanguage(context: Context): AppLanguage {
        return AppLanguage.fromValue(
            prefs(context).getString(
                KEY_LANGUAGE,
                AppLanguage.CHINESE.value
            )
        )
    }

    fun setLanguage(context: Context, language: AppLanguage) {
        prefs(context).edit().putString(KEY_LANGUAGE, language.value).apply()
        applyLanguage(context, language)
    }

    fun getTheme(context: Context): AppThemeMode {
        return AppThemeMode.fromValue(
            prefs(context).getString(
                KEY_THEME,
                AppThemeMode.FOLLOW_SYSTEM.value
            )
        )
    }

    fun setTheme(context: Context, themeMode: AppThemeMode) {
        prefs(context).edit().putString(KEY_THEME, themeMode.value).apply()
        applyTheme(themeMode)
    }

    fun isScanVibrationEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_SCAN_VIBRATION, true)
    }

    fun setScanVibrationEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SCAN_VIBRATION, enabled).apply()
    }

    fun isScanToneEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_SCAN_TONE, true)
    }

    fun setScanToneEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SCAN_TONE, enabled).apply()
    }

    /** 扫码语音播报总开关：关掉后成功/重复/失败一律不播报。默认开。 */
    fun isScanSpeechEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_SCAN_SPEECH, true)
    }

    fun setScanSpeechEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SCAN_SPEECH, enabled).apply()
    }

    fun isDuplicateSpeechEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_DUPLICATE_SPEECH, true)
    }

    fun setDuplicateSpeechEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DUPLICATE_SPEECH, enabled).apply()
    }

    fun isContinuousScanEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_CONTINUOUS_SCAN, true)
    }

    fun setContinuousScanEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CONTINUOUS_SCAN, enabled).apply()
    }

    fun getScanCodeMode(context: Context): ScanCodeMode {
        return ScanCodeMode.fromName(
            prefs(context).getString(
                KEY_SCAN_CODE_MODE,
                ScanCodeMode.ALL.name
            )
        )
    }

    fun setScanCodeMode(context: Context, mode: ScanCodeMode) {
        prefs(context).edit().putString(KEY_SCAN_CODE_MODE, mode.name).apply()
    }

    fun getScanThrottleMs(context: Context): Long {
        return prefs(context).getLong(KEY_SCAN_THROTTLE_MS, 0L)
    }

    fun setScanThrottleMs(context: Context, value: Long) {
        prefs(context).edit()
            .putLong(KEY_SCAN_THROTTLE_MS, value)
            .apply()
    }

    fun sanitizeLegacyThrottle(context: Context) {
        val throttleMs = prefs(context).getLong(KEY_SCAN_THROTTLE_MS, 0L)
        if (throttleMs > 200L) {
            prefs(context).edit().putLong(KEY_SCAN_THROTTLE_MS, 0L).apply()
        }
    }

    fun getFocusAutoCancelSeconds(context: Context): Int {
        return prefs(context).getInt(KEY_FOCUS_AUTO_CANCEL_SECONDS, 0)
    }

    fun getLoggedInPhone(context: Context): String? {
        val phone = prefs(context).getString(KEY_LOGIN_PHONE, null) ?: return null
        val loginAt = prefs(context).getLong(KEY_LOGIN_AT, 0L)
        return if (loginAt > 0) phone else null
    }

    fun saveLoginInfo(context: Context, phone: String, loginAt: Long) {
        prefs(context).edit()
            .putString(KEY_LOGIN_PHONE, phone)
            .putLong(KEY_LOGIN_AT, loginAt)
            .apply()
    }

    fun clearLoginInfo(context: Context) {
        prefs(context).edit()
            .remove(KEY_LOGIN_PHONE)
            .remove(KEY_LOGIN_AT)
            .apply()
    }

    fun getUserName(context: Context): String? {
        return prefs(context).getString(KEY_USER_NAME, null)
    }

    // ——— 扫码偏好 ———
    fun isSaveScanImageEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_SAVE_SCAN_IMAGE, true)
    }

    fun setSaveScanImageEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SAVE_SCAN_IMAGE, enabled).apply()
    }

    fun isAllowDuplicateScan(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_ALLOW_DUPLICATE_SCAN, false)
    }

    fun setAllowDuplicateScan(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ALLOW_DUPLICATE_SCAN, enabled).apply()
    }

    fun isClearConfirmEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_CLEAR_CONFIRM, true)
    }

    fun setClearConfirmEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CLEAR_CONFIRM, enabled).apply()
    }

    fun isAutoCopyEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_AUTO_COPY, false)
    }

    fun setAutoCopyEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_COPY, enabled).apply()
    }

    fun isSaveToAlbumEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_SAVE_TO_ALBUM, true)
    }

    fun setSaveToAlbumEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SAVE_TO_ALBUM, enabled).apply()
    }

    fun isSaveSeparateFolder(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_SAVE_SEPARATE_FOLDER, false)
    }

    fun setSaveSeparateFolder(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SAVE_SEPARATE_FOLDER, enabled).apply()
    }

    fun getContinuousIntervalMs(context: Context): Long {
        return prefs(context).getLong(KEY_CONTINUOUS_INTERVAL_MS, 800L)
    }

    fun setContinuousIntervalMs(context: Context, value: Long) {
        prefs(context).edit()
            .putLong(KEY_CONTINUOUS_INTERVAL_MS, value)
            .apply()
    }

    fun clearCaches(context: Context): Long {
        var deletedBytes = 0L
        deletedBytes += context.cacheDir?.let(::clearDirectory) ?: 0L
        deletedBytes += context.externalCacheDir?.let(::clearDirectory) ?: 0L
        return deletedBytes
    }

    fun getHistoryFolders(context: Context): Set<String> {
        return prefs(context).getStringSet(KEY_HISTORY_FOLDERS, emptySet()).orEmpty()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
    }

    fun addHistoryFolder(context: Context, folderName: String) {
        val name = folderName.trim()
        if (name.isBlank()) return
        val folders = getHistoryFolders(context).toMutableSet()
        folders += name
        prefs(context).edit().putStringSet(KEY_HISTORY_FOLDERS, folders).apply()
    }

    fun renameHistoryFolder(context: Context, oldName: String, newName: String) {
        val oldFolder = oldName.trim()
        val newFolder = newName.trim()
        if (oldFolder.isBlank() || newFolder.isBlank()) return
        val folders = getHistoryFolders(context).toMutableSet()
        if (folders.remove(oldFolder)) {
            folders += newFolder
            prefs(context).edit().putStringSet(KEY_HISTORY_FOLDERS, folders).apply()
        }
    }

    fun removeHistoryFolder(context: Context, folderName: String) {
        val name = folderName.trim()
        if (name.isBlank()) return
        val folders = getHistoryFolders(context).toMutableSet()
        if (folders.remove(name)) {
            prefs(context).edit().putStringSet(KEY_HISTORY_FOLDERS, folders).apply()
        }
    }

    /** 是否记住上次选择的文件夹（勾选后不再自动弹窗）。 */
    fun isFolderRememberEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_REMEMBER_FOLDER, false)
    }

    fun setFolderRememberEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_REMEMBER_FOLDER, enabled).apply()
    }

    /** 上次选择的文件夹名，空串表示「未分组」。 */
    fun getLastFolder(context: Context): String {
        return prefs(context).getString(KEY_LAST_FOLDER, "").orEmpty()
    }

    fun setLastFolder(context: Context, folder: String) {
        prefs(context).edit().putString(KEY_LAST_FOLDER, folder).apply()
    }

    private fun applyLanguage(context: Context, language: AppLanguage) {
        val locales = when (language) {
            AppLanguage.FOLLOW_SYSTEM -> LocaleListCompat.getEmptyLocaleList()
            AppLanguage.CHINESE -> LocaleListCompat.forLanguageTags("zh-CN")
            AppLanguage.ENGLISH -> LocaleListCompat.forLanguageTags("en")
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }

    private fun applyTheme(themeMode: AppThemeMode) {
        val mode = when (themeMode) {
            AppThemeMode.FOLLOW_SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            AppThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            AppThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    private fun clearDirectory(directory: java.io.File): Long {
        if (!directory.exists()) return 0L
        var deletedBytes = 0L
        directory.listFiles()?.forEach { file ->
            deletedBytes += if (file.isDirectory) {
                clearDirectory(file)
            } else {
                file.length()
            }
            file.deleteRecursively()
        }
        return deletedBytes
    }
}
