package com.example.sscan

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsetsController
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.example.sscan.core.scanner.ScanCodeMode
import com.example.sscan.core.settings.AppSettings
import com.example.sscan.databinding.ActivityMainBinding
import com.example.sscan.feature.auth.presentation.LoginActivity
import com.example.sscan.feature.history.presentation.HistoryActivity
import com.example.sscan.feature.scan.presentation.ScanActivity
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val mainViewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!ensureLoggedIn()) return

        // 启用边缘到边缘
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 设置沉浸式状态栏
        setupImmersiveMode()

        setupDashboardActions()
        observeStats()
    }

    private fun observeStats() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                mainViewModel.uiState.collect { state ->
                    binding.statTodayValue.text = state.todayCount.toString()
                    binding.statTotalValue.text = state.totalCount.toString()
                    binding.statImageValue.text = state.imageCount.toString()
                }
            }
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

    override fun onResume() {
        super.onResume()
        ensureLoggedIn()
    }

    private fun ensureLoggedIn(): Boolean {
        if (AppSettings.getLoggedInPhone(this) != null) {
            return true
        }
        startActivity(
            Intent(this, LoginActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
        )
        finish()
        return false
    }

    private fun startScan(mode: ScanCodeMode) {
        val intent = Intent(this, ScanActivity::class.java).apply {
            putExtra(ScanActivity.EXTRA_SCAN_MODE, mode.name)
        }
        startActivity(intent)
    }

    private fun setupDashboardActions() {
        // 扫码 - 默认模式
        binding.cardScan.setOnClickListener {
            startScan(ScanCodeMode.ALL)
        }

        // 只扫条形码
        binding.cardBarcodeOnly.setOnClickListener {
            startScan(ScanCodeMode.BARCODE_ONLY)
        }

        // 只扫二维码
        binding.cardQrOnly.setOnClickListener {
            startScan(ScanCodeMode.QR_ONLY)
        }

        // 连续扫码
        binding.cardContinuous.setOnClickListener {
            val intent = Intent(this, ScanActivity::class.java).apply {
                putExtra(ScanActivity.EXTRA_SCAN_MODE, ScanCodeMode.ALL.name)
                putExtra(ScanActivity.EXTRA_FORCE_CONTINUOUS, true)
            }
            startActivity(intent)
        }

        binding.homeTab.setOnClickListener { }

        binding.historyTab.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        binding.mineTab.setOnClickListener {
            startActivity(Intent(this, com.example.sscan.feature.mine.MineActivity::class.java))
        }
    }
}
