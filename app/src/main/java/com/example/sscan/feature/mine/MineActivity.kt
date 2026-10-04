package com.example.sscan.feature.mine

import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsetsController
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.sscan.R
import com.example.sscan.core.settings.AppLanguage
import com.example.sscan.core.settings.AppSettings
import com.example.sscan.core.settings.AppThemeMode
import com.example.sscan.data.repository.ScanRepository
import com.example.sscan.databinding.ActivityMineBinding
import com.example.sscan.feature.auth.presentation.LoginActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class MineActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMineBinding
    @Inject
    lateinit var scanRepository: ScanRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 启用边缘到边缘
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMineBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 设置沉浸式状态栏
        setupImmersiveMode()

        // 用户信息（从 SharedPreferences 读取；未登录时显示默认）
        val phone = AppSettings.getLoggedInPhone(this)
        val userName = AppSettings.getUserName(this)
        binding.userNameText.text = userName ?: getString(R.string.settings_user_name_value)
        binding.userPhoneText.text =
            phone?.let { maskPhone(it) } ?: getString(R.string.login_not_logged_in_title)

        // 语言与主题状态展示
        refreshLanguageAndTheme()

        // 扫码间隔时间展示
        refreshIntervalSummary()

        // 缓存大小展示（简单估算，此处不做实际计算，展示默认）
        binding.rowCacheSize.text = getString(R.string.settings_cache_size_value)

        // 版本号
        try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            binding.rowVersionText.text = packageInfo.versionName
        } catch (t: Throwable) {
            binding.rowVersionText.text = getString(R.string.settings_version_value)
        }

        // 所有开关的初始状态绑定
        bindSwitch(R.id.switchVibration) { AppSettings.setScanVibrationEnabled(this, it) }
            ?.isChecked = AppSettings.isScanVibrationEnabled(this)
        bindSwitch(R.id.switchTone) { AppSettings.setScanToneEnabled(this, it) }
            ?.isChecked = AppSettings.isScanToneEnabled(this)
        bindSwitch(R.id.switchDuplicateSpeech) { AppSettings.setDuplicateSpeechEnabled(this, it) }
            ?.isChecked = AppSettings.isDuplicateSpeechEnabled(this)
        bindSwitch(R.id.switchSaveImage) { AppSettings.setSaveScanImageEnabled(this, it) }
            ?.isChecked = AppSettings.isSaveScanImageEnabled(this)
        bindSwitch(R.id.switchScanSpeech) { AppSettings.setScanSpeechEnabled(this, it) }
        findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchScanSpeech)
            ?.isChecked = AppSettings.isScanSpeechEnabled(this)
        bindSwitch(R.id.switchAllowDuplicate) { AppSettings.setAllowDuplicateScan(this, it) }
            ?.isChecked = AppSettings.isAllowDuplicateScan(this)
        bindSwitch(R.id.switchClearConfirm) { AppSettings.setClearConfirmEnabled(this, it) }
            ?.isChecked = AppSettings.isClearConfirmEnabled(this)
        bindSwitch(R.id.switchAutoCopy) { AppSettings.setAutoCopyEnabled(this, it) }
            ?.isChecked = AppSettings.isAutoCopyEnabled(this)
        bindSwitch(R.id.switchSaveToAlbum) { AppSettings.setSaveToAlbumEnabled(this, it) }
            ?.isChecked = AppSettings.isSaveToAlbumEnabled(this)
        bindSwitch(R.id.switchSaveSeparateFolder) { AppSettings.setSaveSeparateFolder(this, it) }
            ?.isChecked = AppSettings.isSaveSeparateFolder(this)

        // 点击行为
        binding.rowLanguage.setOnClickListener { pickLanguage() }
        binding.rowTheme.setOnClickListener { pickTheme() }
        binding.rowInterval.setOnClickListener { pickInterval() }
        binding.rowClearHistory.setOnClickListener { confirmClearHistory() }
        binding.rowClearCache.setOnClickListener { clearCache() }
        binding.rowCheckUpdate.setOnClickListener {
            val versionName = runCatching {
                packageManager.getPackageInfo(packageName, 0).versionName
            }.getOrNull() ?: getString(R.string.settings_version_value)
            Toast.makeText(
                this,
                getString(R.string.settings_latest_version, versionName),
                Toast.LENGTH_SHORT
            ).show()
        }
        binding.rowAbout.setOnClickListener {
            val versionName = try {
                packageManager.getPackageInfo(packageName, 0).versionName
            } catch (_: Throwable) {
                getString(R.string.settings_version_value)
            }
            AlertDialog.Builder(this)
                .setTitle(R.string.settings_about_title)
                .setMessage(getString(R.string.settings_about_message, versionName, packageName))
                .setPositiveButton(R.string.settings_dialog_confirm, null)
                .show()
        }
        binding.rowLogout.setOnClickListener { confirmLogout() }
    }

    private fun bindSwitch(id: Int, onChange: (Boolean) -> Unit): SwitchCompat? {
        val sw = findViewById<SwitchCompat>(id) ?: return null
        sw.setOnCheckedChangeListener { _, isChecked -> onChange(isChecked) }
        return sw
    }

    private fun refreshLanguageAndTheme() {
        binding.rowLanguageValue.text = when (AppSettings.getLanguage(this)) {
            AppLanguage.FOLLOW_SYSTEM -> getString(R.string.settings_language_follow_system)
            AppLanguage.CHINESE -> getString(R.string.settings_language_chinese)
            AppLanguage.ENGLISH -> getString(R.string.settings_language_english)
        }
        binding.rowThemeValue.text = when (AppSettings.getTheme(this)) {
            AppThemeMode.FOLLOW_SYSTEM -> getString(R.string.settings_theme_follow_system)
            AppThemeMode.LIGHT -> getString(R.string.settings_theme_light)
            AppThemeMode.DARK -> getString(R.string.settings_theme_dark)
        }
    }

    private fun refreshIntervalSummary() {
        val ms = AppSettings.getContinuousIntervalMs(this)
        binding.rowIntervalSummary.text = getString(R.string.settings_continuous_interval_option, ms)
    }

    private fun pickLanguage() {
        val items = arrayOf<CharSequence>(
            getString(R.string.settings_language_follow_system),
            getString(R.string.settings_language_chinese),
            getString(R.string.settings_language_english)
        )
        val current = AppSettings.getLanguage(this)
        val selected = when (current) {
            AppLanguage.FOLLOW_SYSTEM -> 0
            AppLanguage.CHINESE -> 1
            AppLanguage.ENGLISH -> 2
        }
        val listener = android.content.DialogInterface.OnClickListener { dialog, which ->
            when (which) {
                0 -> AppSettings.setLanguage(this, AppLanguage.FOLLOW_SYSTEM)
                1 -> AppSettings.setLanguage(this, AppLanguage.CHINESE)
                2 -> AppSettings.setLanguage(this, AppLanguage.ENGLISH)
            }
            dialog.dismiss()
            refreshLanguageAndTheme()
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_language_title)
            .setSingleChoiceItems(items, selected, listener)
            .show()
    }

    private fun pickTheme() {
        val items = arrayOf<CharSequence>(
            getString(R.string.settings_theme_follow_system),
            getString(R.string.settings_theme_light),
            getString(R.string.settings_theme_dark)
        )
        val current = AppSettings.getTheme(this)
        val selected = when (current) {
            AppThemeMode.FOLLOW_SYSTEM -> 0
            AppThemeMode.LIGHT -> 1
            AppThemeMode.DARK -> 2
        }
        val listener = android.content.DialogInterface.OnClickListener { dialog, which ->
            when (which) {
                0 -> AppSettings.setTheme(this, AppThemeMode.FOLLOW_SYSTEM)
                1 -> AppSettings.setTheme(this, AppThemeMode.LIGHT)
                2 -> AppSettings.setTheme(this, AppThemeMode.DARK)
            }
            dialog.dismiss()
            refreshLanguageAndTheme()
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_theme_title)
            .setSingleChoiceItems(items, selected, listener)
            .show()
    }

    private fun pickInterval() {
        val options = longArrayOf(300, 500, 800, 1000, 1500)
        val items = options.map { getString(R.string.settings_continuous_interval_option, it) }
            .toTypedArray()
        val current = AppSettings.getContinuousIntervalMs(this)
        var selected = options.indexOfFirst { it == current }
        if (selected < 0) selected = 2
        val listener = android.content.DialogInterface.OnClickListener { dialog, which ->
            AppSettings.setContinuousIntervalMs(this, options[which])
            dialog.dismiss()
            refreshIntervalSummary()
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_continuous_interval_title)
            .setSingleChoiceItems(items, selected, listener)
            .show()
    }

    private fun confirmClearHistory() {
        if (AppSettings.isClearConfirmEnabled(this)) {
            AlertDialog.Builder(this)
                .setMessage(R.string.settings_clear_history_confirm)
                .setPositiveButton(R.string.settings_dialog_confirm) { _, _ ->
                    clearHistoryRecords()
                }
                .setNegativeButton(R.string.settings_dialog_cancel, null)
                .show()
        } else {
            clearHistoryRecords()
        }
    }

    private fun clearHistoryRecords() {
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    scanRepository.clearAll()
                }
            }.onSuccess {
                Toast.makeText(
                    this@MineActivity,
                    R.string.settings_clear_history_success,
                    Toast.LENGTH_SHORT
                ).show()
            }.onFailure { throwable ->
                Toast.makeText(
                    this@MineActivity,
                    getString(R.string.settings_clear_history_failed, throwable.message.orEmpty()),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun clearCache() {
        AlertDialog.Builder(this)
            .setMessage(R.string.settings_clear_cache_confirm)
            .setPositiveButton(R.string.settings_dialog_confirm) { _, _ ->
                AppSettings.clearCaches(this)
                binding.rowCacheSize.text = getString(R.string.settings_cache_size_value)
                Toast.makeText(
                    this,
                    getString(
                        R.string.settings_cache_cleared,
                        getString(R.string.settings_cache_size_value)
                    ),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(R.string.settings_dialog_cancel, null)
            .show()
    }

    private fun confirmLogout() {
        val phone = AppSettings.getLoggedInPhone(this).orEmpty()
        val message = if (phone.isNotBlank()) {
            getString(R.string.login_logout_dialog_message, maskPhone(phone))
        } else {
            getString(R.string.login_logout_confirm)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.login_logout_dialog_title)
            .setMessage(message)
            .setPositiveButton(R.string.login_logout_confirm) { _, _ -> logout() }
            .setNegativeButton(R.string.settings_dialog_cancel, null)
            .show()
    }

    private fun logout() {
        AppSettings.clearLoginInfo(this)
        Toast.makeText(this, R.string.login_logged_out, Toast.LENGTH_SHORT).show()
        startActivity(
            Intent(this, LoginActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
        )
        finish()
    }

    private fun maskPhone(phone: String): String {
        return if (phone.length == 11) {
            phone.substring(0, 3) + "****" + phone.substring(7)
        } else {
            phone
        }
    }

    private fun setupImmersiveMode() {
        // 设置状态栏和导航栏颜色为透明
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ 使用 WindowInsetsController
            window.insetsController?.let { controller ->
                // 设置系统栏为浅色内容（状态栏图标为深色）
                controller.setSystemBarsAppearance(
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                )
            }
        } else {
            // Android 10 及以下使用 View 系统
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    )
        }

        // 处理WindowInsets，让内容不被系统栏遮挡
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, insets.top, 0, insets.bottom)
            windowInsets
        }
    }
}
