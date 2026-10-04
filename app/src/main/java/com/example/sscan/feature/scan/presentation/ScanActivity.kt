package com.example.sscan.feature.scan.presentation

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.sscan.R
import com.example.sscan.core.camera.CameraManager
import com.example.sscan.core.scanner.MlKitProvider
import com.example.sscan.core.scanner.ScanCodeMode
import com.example.sscan.core.settings.AppSettings
import com.example.sscan.databinding.ActivityScanBinding
import com.example.sscan.databinding.DialogMoveToFolderBinding
import com.example.sscan.databinding.DialogPermissionExplainBinding
import com.example.sscan.feature.history.presentation.HistoryActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.mlkit.vision.common.InputImage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@AndroidEntryPoint
// 商业扫码页入口：已重构分为 权限处理、扫码反馈、模式切换、页面渲染 四块。
class ScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScanBinding
    private lateinit var cameraManager: CameraManager
    private val scanViewModel: ScanViewModel by viewModels()
    private val feedbackManager = ScanFeedbackManager(this)

    // === 状态与配置 ===
    private var continuousScanEnabled = true
    private var flashEnabled = false
    private var currentMode = ScanCodeMode.ALL
    private var updatingModeSwitches = false
    private var syncingPreferenceViews = false

    companion object {
        const val EXTRA_SCAN_MODE = "extra_scan_mode"
        const val EXTRA_FORCE_CONTINUOUS = "extra_force_continuous"
        const val EXTRA_RETURN_RESULT_ONLY = "extra_return_result_only"
        const val EXTRA_SCAN_RESULT = "scan_result"

        /** 早于这个时长的抓拍缓存视为无人认领（进程被杀留下的），启动时清掉。 */
        private const val STALE_CAPTURE_TIMEOUT_MS = 10 * 60 * 1000L

        /** 瞬时状态（成功/重复/失败/对焦/手电筒…）停留多久后回落到常驻文案。 */
        private const val STATUS_RESTORE_DELAY_MS = 900L

        private const val STATE_TARGET_FOLDER = "state_target_folder"
        private const val STATE_FOLDER_PICKER_SHOWN = "state_folder_picker_shown"
    }

    private var scanVibrationEnabled = true
    private var scanToneEnabled = true
    private var scanSpeechEnabled = true
    private var duplicateSpeechEnabled = true
    private var autoCopyEnabled = false
    /** 本次扫码作业的目标文件夹。空串 = 未分组。每次进页面重置，不跨会话保留。 */
    private var targetFolder = ""
    private var pendingFolderDialog: BottomSheetDialog? = null
    private var pendingPermissionDialog: BottomSheetDialog? = null
    private var folderPickerAutoOpened = false
    private var cameraPermissionDenied = false
    private var scanThrottleMs = 100L
    private var focusAutoCancelSeconds = 0
    private val scanTimeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val returnResultOnly: Boolean
        get() = intent?.getBooleanExtra(EXTRA_RETURN_RESULT_ONLY, false) ?: false

    // ==========================================
    // 1. 权限处理与系统入口 (Permission & Lifecycle)
    // ==========================================

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            cameraPermissionDenied = true
            updateStatusText(getString(R.string.scan_status_denied))
            // 系统权限弹窗刚关闭，Activity 窗口尚未完全稳定，直接 show 容易被吞或抛
            // WindowManager$BadTokenException。用主线程 Handler 延迟到窗口稳定后再展示。
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                showPermissionExplainSheet()
            }, 300L)
        }
    }

    private val albumPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) processAlbumImage(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityScanBinding.inflate(layoutInflater)
        setContentView(binding.root)
        volumeControlStream = AudioManager.STREAM_MUSIC

        savedInstanceState?.let {
            targetFolder = it.getString(STATE_TARGET_FOLDER).orEmpty()
            folderPickerAutoOpened = it.getBoolean(STATE_FOLDER_PICKER_SHOWN, false)
        }

        feedbackManager.initialize()
        applyWindowInsets()
        applyStoredScanPreferences()

        setupCameraManager()
        setupUiListeners()
        setupFolderChip()

        updateFlashUi()
        updateModeSwitches()
        applyCameraPerformancePreferences()
        startScanLineAnimation()

        ensureCameraPermission()
        observeScanState()
        sweepStaleCaptures()
    }

    /**
     * 目标文件夹只在「重新进页面」时重置，**配置变更不算**。
     *
     * 转屏 / 切深色 / 改字号都会重建 Activity；不存的话 targetFolder 会悄悄回到「未分组」，
     * 而用户以为还在往原文件夹里扫——这批货会被静默错分且事后无从察觉。
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_TARGET_FOLDER, targetFolder)
        outState.putBoolean(STATE_FOLDER_PICKER_SHOWN, folderPickerAutoOpened)
    }

    override fun onResume() {
        super.onResume()
        applyStoredScanPreferences()
        recheckCameraPermission()
        // 兜底解卡：dispatchingResult 只由 resumeScanning() 清除，而分发链路上任何一条
        // 早退/抛异常的分支都会把它永久留在 true——症状是扫码彻底停住，不崩溃也不报错。
        // 注意 applyStoredScanPreferences() 里的 setScanMode 解不了卡：模式没变时它直接 return。
        // 代价是后台切回时若恰有抓拍在途，同一个码可能重复分发一次，由节流 + 去重兜住。
        // 权限被拒时不调用 resumeScanning，避免在无相机权限状态下操作相机导致异常。
        if (::cameraManager.isInitialized && !cameraPermissionDenied) {
            cameraManager.resumeScanning()
        }
        startScanLineAnimation()
    }

    /**
     * 用户拒权限后去系统设置里授权再回来时，进程通常不会重启（撤销权限才会）。
     * 不复检的话相机永远起不来、胶囊也永久钉死在「未授权」上，用户没有任何重试入口。
     */
    private fun recheckCameraPermission() {
        if (!cameraPermissionDenied) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return
        cameraPermissionDenied = false
        startCamera()
    }

    override fun onPause() {
        binding.scanLine.clearAnimation()
        binding.barcodeOverlayView.clearAnnotations()
        super.onPause()
    }

    override fun onDestroy() {
        binding.root.removeCallbacks(restoreActiveStatusRunnable)
        pendingFolderDialog?.setOnDismissListener(null)
        pendingFolderDialog?.dismiss()
        pendingFolderDialog = null
        pendingPermissionDialog?.setOnDismissListener(null)
        pendingPermissionDialog?.dismiss()
        pendingPermissionDialog = null
        if (::cameraManager.isInitialized) {
            cameraManager.release()
        }
        feedbackManager.release()
        super.onDestroy()
    }

    private fun ensureCameraPermission() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) startCamera() else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        updateStatusText(idleStatusText())
        cameraManager.startCamera(binding.previewView)
        cameraManager.setScanMode(currentMode, binding.previewView)
        cameraManager.setTorchEnabled(flashEnabled)
        maybeAutoOpenFolderPicker()
    }

    /**
     * 每次进扫码页先确认这批货扫到哪个文件夹；之后扫多少个都不再打断。
     *
     * 挂在 startCamera() 而不是 onCreate()：onCreate 里 ensureCameraPermission() 会拉起系统
     * 权限框，紧接着弹自己的框会叠在上面。startCamera() 是「权限已落定」的唯一汇合点——
     * 已授权分支和 permissionLauncher 的授权回调都会走到它。
     * 守卫的语义是「用户已经有过一次选择机会」，所以**只在弹窗真正关闭后才置位**——
     * 若在这里提前置位，转屏时 onDestroy 会 dismiss 掉弹窗，而恢复出来的守卫已是 true，
     * 提示永远不再出现，整批货静默落进「未分组」。
     * showFolderPicker 开头已有 pendingFolderDialog 判空，重入是安全的。
     *
     * 若用户勾选了「记住此选择」，直接应用上次的文件夹，不再弹窗打断。
     */
    private fun maybeAutoOpenFolderPicker() {
        if (folderPickerAutoOpened) return
        if (AppSettings.isFolderRememberEnabled(this)) {
            val lastFolder = AppSettings.getLastFolder(this)
            applyTargetFolder(lastFolder)
            folderPickerAutoOpened = true
            return
        }
        binding.root.post { showFolderPicker(autoOpened = true) }
    }

    /**
     * 相机权限被拒时的解释弹窗。
     *
     * 不直接 Toast "相机未授权" 就了事——用户可能没理解为什么需要相机，
     * 也可能是误触拒绝。这里用底部 Sheet 说明用途，给「再次请求」和「去设置」两个出口，
     * 比直接跳系统设置更柔和，也给了第二次机会。
     */
    private fun showPermissionExplainSheet() {
        if (isDestroyed) return
        if (pendingPermissionDialog != null) return

        try {
            val dialogBinding = DialogPermissionExplainBinding.inflate(layoutInflater)
            val dialog = BottomSheetDialog(this).apply {
                setContentView(dialogBinding.root)
                window?.setBackgroundDrawableResource(android.R.color.transparent)
                setCancelable(true)
            }

            dialogBinding.retryButton.setOnClickListener {
                dialog.dismiss()
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
            dialogBinding.settingsButton.setOnClickListener {
                dialog.dismiss()
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", packageName, null)
                )
                startActivity(intent)
            }
            dialog.setOnDismissListener {
                pendingPermissionDialog = null
            }

            pendingPermissionDialog = dialog
            dialog.show()
        } catch (e: Exception) {
            pendingPermissionDialog = null
        }
    }

    private fun setupCameraManager() {
        cameraManager = CameraManager(
            context = applicationContext,
            lifecycleOwner = this,
            onScanResults = { barcodes -> captureAndDispatch(barcodes) },
            onAnnotationsDetected = { annotations, sourceWidth, sourceHeight, viewWidth, viewHeight ->
                runOnUiThread {
                    if (continuousScanEnabled && annotations.isNotEmpty()) {
                        binding.barcodeOverlayView.updateAnnotations(
                            annotations,
                            sourceWidth,
                            sourceHeight,
                            viewWidth,
                            viewHeight
                        )
                    } else {
                        binding.barcodeOverlayView.clearAnnotations()
                    }
                }
            },
            onError = { scanViewModel.onScanError(it) }
        )
    }

    // ==========================================
    // 2. 扫码反馈与结果分发 (Feedback & Dispatch Handlers)
    // ==========================================

    private fun observeScanState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                scanViewModel.uiState.collect { state ->
                    renderScanStatus(state)
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                scanViewModel.events.collect { event ->
                    when (event) {
                        is ScanUiEvent.Error -> feedbackManager.showToast(event.message)
                        is ScanUiEvent.Feedback -> renderFeedback(event.event)
                        is ScanUiEvent.ImageSaved -> updateLiveResultThumbnail(event.imageUri)
                    }
                }
            }
        }
    }

    private fun renderFeedback(event: ScanFeedbackEvent) {
        val message = feedbackText(event)
        if (message.isBlank()) return
        updateStatusText(message)
        feedbackManager.performFeedback(
            event = event,
            message = message,
            toneEnabled = scanToneEnabled,
            vibrationEnabled = scanVibrationEnabled,
            speechEnabled = scanSpeechEnabled,
            duplicateSpeechEnabled = duplicateSpeechEnabled
        )
    }

    private fun handleDispatchResult(result: ScanDispatchResult) {
        when (result) {
            ScanDispatchResult.ACCEPTED -> handleScanSuccess()
            ScanDispatchResult.DUPLICATE -> handleScanDuplicate()
            ScanDispatchResult.THROTTLED,
            ScanDispatchResult.EMPTY -> handleScanFailedOrIgnored()
        }
    }

    private fun handleScanSuccess() {
        cameraManager.resumeScanning()
        scanViewModel.uiState.value.latestResults.firstOrNull()?.let { result ->
            copyScanResultIfNeeded(result)
            returnScanResult(result)
        }
    }

    private fun handleScanDuplicate() {
        cameraManager.resumeScanning()
    }

    private fun handleScanFailedOrIgnored() {
        cameraManager.resumeScanning()
    }

    private fun copyScanResultIfNeeded(result: String) {
        if (!autoCopyEnabled || result.isBlank()) return
        val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.setPrimaryClip(ClipData.newPlainText("scan_result", result))
    }

    private fun captureAndDispatch(barcodes: List<com.google.mlkit.vision.barcode.common.Barcode>) {
        val firstResult = barcodes.firstOrNull()?.rawValue?.trim().orEmpty()
        if (firstResult.isBlank()) return
        if (returnResultOnly) {
            runOnUiThread {
                returnScanResult(firstResult)
                finish()
            }
            return
        }

        // 抓拍条件严格跟随用户设置，与 SaveScanWithImageUseCase 的 wantsImage 对齐；
        // 不再因为"选了文件夹"就强行抓拍，否则每次扫码都要白付一次快门延迟。
        val shouldCaptureImage = AppSettings.isSaveToAlbumEnabled(this) &&
                AppSettings.isSaveScanImageEnabled(this)
        if (!shouldCaptureImage) {
            runOnUiThread {
                dispatchScanResults(barcodes, null)
            }
            return
        }

        runCatching { provideCaptureDirectory() }
            .onSuccess { outputDirectory ->
                captureBestScanPhoto(
                    outputDirectory = outputDirectory,
                    onCaptured = { capturedFile ->
                        runOnUiThread {
                            // 页面已经在关了：ViewModel 的协程作用域也已取消，
                            // SaveScanWithImageUseCase 的 finally 不会跑，这张图没人删。
                            if (isFinishing || isDestroyed) {
                                capturedFile.takeIf { it.exists() }?.delete()
                            } else {
                                dispatchScanResults(barcodes, capturedFile)
                            }
                        }
                    },
                    onError = { throwable ->
                        runOnUiThread {
                            handleCaptureError(throwable)
                        }
                    }
                )
            }.onFailure { throwable ->
                runOnUiThread {
                    handleCaptureError(throwable)
                }
            }
    }

    private fun captureBestScanPhoto(
        outputDirectory: File,
        onCaptured: (File) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        cameraManager.capturePhoto(
            outputDirectory = outputDirectory,
            onCaptured = onCaptured,
            onError = onError
        )
    }

    private fun handleCaptureError(throwable: Throwable) {
        scanViewModel.onScanError(throwable)
        handleScanFailedOrIgnored()
    }

    private fun returnScanResult(result: String) {
        val returnIntent = android.content.Intent().apply {
            putExtra(EXTRA_SCAN_RESULT, result)
        }
        setResult(RESULT_OK, returnIntent)
    }

    private fun processAlbumImage(uri: android.net.Uri) {
        updateStatusText(getString(R.string.scan_status_album_loading), sticky = true)
        runCatching {
            val tempFile = copyUriToTempFile(uri)
            val image = InputImage.fromFilePath(this, uri)
            tempFile to image
        }.onSuccess { (tempFile, inputImage) ->
            val scanner = MlKitProvider.createScanner(currentMode)
            scanner.process(inputImage)
                .addOnSuccessListener { barcodes ->
                    scanner.close()
                    if (barcodes.isEmpty()) {
                        val dispatchResult = scanViewModel.onScanResults(barcodes, tempFile)
                        if (dispatchResult != ScanDispatchResult.ACCEPTED && tempFile.exists()) {
                            tempFile.delete()
                        }
                        if (dispatchResult == ScanDispatchResult.EMPTY) {
                            updateStatusText(getString(R.string.scan_status_album_empty))
                        }
                        handleDispatchResult(dispatchResult)
                    } else {
                        dispatchScanResults(barcodes, tempFile)
                    }
                }
                .addOnFailureListener {
                    if (tempFile.exists()) tempFile.delete()
                    scanViewModel.onScanError(it)
                    scanner.close()
                }
        }.onFailure { scanViewModel.onScanError(it) }
    }

    private fun provideCaptureDirectory(): File {
        val outputDirectory = File(cacheDir, "capture")
        if (!outputDirectory.exists() && !outputDirectory.mkdirs()) {
            throw IllegalStateException("Failed to create capture directory")
        }
        return outputDirectory
    }

    private fun copyUriToTempFile(uri: android.net.Uri): File {
        val tempFile = File(cacheDir, "album_${System.currentTimeMillis()}.jpg")
        contentResolver.openInputStream(uri)?.use { input ->
            tempFile.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("Failed to open selected image")
        return tempFile
    }

    private fun feedbackText(event: ScanFeedbackEvent): String {
        return when (event) {
            ScanFeedbackEvent.SUCCESS -> getString(R.string.scan_feedback_success)
            ScanFeedbackEvent.FAILED -> getString(R.string.scan_feedback_failed)
            ScanFeedbackEvent.DUPLICATE -> getString(R.string.scan_feedback_repeat)
            ScanFeedbackEvent.TOO_FAST -> getString(R.string.scan_feedback_fast)
            ScanFeedbackEvent.BLUR_REJECTED -> getString(R.string.scan_feedback_blur)
        }
    }

    // ==========================================
    // 3. 模式切换 (Mode Switching)
    // ==========================================

    private fun handleModeSwitch(
        isChecked: Boolean,
        targetMode: ScanCodeMode,
        otherChecked: Boolean
    ) {
        if (updatingModeSwitches) return
        when {
            isChecked -> updateModeSelection(targetMode)
            !otherChecked -> updateModeSelection(ScanCodeMode.ALL)
        }
    }

    private fun updateModeSelection(mode: ScanCodeMode) {
        currentMode = mode
        AppSettings.setScanCodeMode(this, mode)
        updateModeSwitches()
        if (::cameraManager.isInitialized) {
            cameraManager.setScanMode(mode, binding.previewView)
            cameraManager.resumeScanning()
        }
        val messageRes = when (mode) {
            ScanCodeMode.ALL -> R.string.scan_status_mode_all
            ScanCodeMode.QR_ONLY -> R.string.scan_status_mode_qr
            ScanCodeMode.BARCODE_ONLY -> R.string.scan_status_mode_barcode
        }
        updateStatusText(getString(messageRes))
    }

    private fun updateModeSwitches() {
        updatingModeSwitches = true
        binding.qrOnlySwitch.isChecked = currentMode == ScanCodeMode.QR_ONLY
        binding.barcodeOnlySwitch.isChecked = currentMode == ScanCodeMode.BARCODE_ONLY
        updatingModeSwitches = false
        updateControlVisualState()
    }

    private fun applyStoredScanPreferences() {
        scanVibrationEnabled = AppSettings.isScanVibrationEnabled(this)
        scanToneEnabled = AppSettings.isScanToneEnabled(this)
        scanSpeechEnabled = AppSettings.isScanSpeechEnabled(this)
        duplicateSpeechEnabled = AppSettings.isDuplicateSpeechEnabled(this)
        autoCopyEnabled = AppSettings.isAutoCopyEnabled(this)

        // 优先使用 Intent 传入的扫码模式 / 连续扫码标志
        val intentModeName = intent?.getStringExtra(EXTRA_SCAN_MODE)
        val intentForceContinuous = intent?.getBooleanExtra(EXTRA_FORCE_CONTINUOUS, false) ?: false
        if (!intentModeName.isNullOrBlank()) {
            currentMode =
                runCatching { ScanCodeMode.valueOf(intentModeName) }.getOrElse { ScanCodeMode.ALL }
            continuousScanEnabled = true
        } else {
            continuousScanEnabled = AppSettings.isContinuousScanEnabled(this)
            currentMode = AppSettings.getScanCodeMode(this)
        }
        if (intentForceContinuous) continuousScanEnabled = true
        scanViewModel.setTargetGroupName(targetFolder)

        scanThrottleMs = AppSettings.getScanThrottleMs(this)
        focusAutoCancelSeconds = AppSettings.getFocusAutoCancelSeconds(this)

        syncingPreferenceViews = true
        binding.continuousScanSwitch.isChecked = continuousScanEnabled
        syncingPreferenceViews = false

        updateControlVisualState()
        if (!continuousScanEnabled) {
            binding.barcodeOverlayView.clearAnnotations()
        }
        updateModeSwitches()
        if (::cameraManager.isInitialized) {
            cameraManager.setScanMode(currentMode, binding.previewView)
        }
        applyCameraPerformancePreferences()
    }

    private fun applyCameraPerformancePreferences() {
        if (::cameraManager.isInitialized) {
            cameraManager.updatePerformanceConfig(
                analyzerThrottleMs = scanThrottleMs,
                focusAutoCancelSeconds = focusAutoCancelSeconds.toLong()
            )
        }
    }

    // ==========================================
    // 4. 页面渲染与 UI (UI Rendering)
    // ==========================================

    private fun setupUiListeners() {
        binding.previewView.apply {
            contentDescription = getString(R.string.scan_panel_title)
            isClickable = true
            isFocusable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_UP -> {
                        val factory = binding.previewView.meteringPointFactory
                        val point = factory.createPoint(event.x, event.y)
                        cameraManager.focusAt(point)
                        updateStatusText(getString(R.string.scan_status_focusing))
                        view.performClick()
                    }
                }
                true
            }
        }
        binding.backButton.setOnClickListener { finish() }
        binding.resetFocusButton.setOnClickListener {
            cameraManager.resetFocus()
            updateStatusText(getString(R.string.scan_status_focus_reset))
        }
        binding.flashToggleButton.setOnClickListener {
            flashEnabled = !flashEnabled
            cameraManager.setTorchEnabled(flashEnabled)
            updateFlashUi()
            updateStatusText(getString(if (flashEnabled) R.string.scan_status_flash_on else R.string.scan_status_flash_off))
        }
        binding.albumReadButton.setOnClickListener {
            albumPickerLauncher.launch("image/*")
        }
        binding.liveResultAction.setOnClickListener {
            startActivity(android.content.Intent(this, HistoryActivity::class.java))
        }
        binding.continuousScanSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (syncingPreferenceViews) return@setOnCheckedChangeListener
            continuousScanEnabled = isChecked
            AppSettings.setContinuousScanEnabled(this, isChecked)
            updateControlVisualState()
            if (!isChecked) binding.barcodeOverlayView.clearAnnotations()
            updateStatusText(getString(if (isChecked) R.string.scan_status_continuous_on else R.string.scan_status_continuous_off))
            if (isChecked) {
                cameraManager.resumeScanning()
            }
        }
        binding.qrOnlySwitch.setOnCheckedChangeListener { _, isChecked ->
            handleModeSwitch(isChecked, ScanCodeMode.QR_ONLY, binding.barcodeOnlySwitch.isChecked)
        }
        binding.barcodeOnlySwitch.setOnCheckedChangeListener { _, isChecked ->
            handleModeSwitch(isChecked, ScanCodeMode.BARCODE_ONLY, binding.qrOnlySwitch.isChecked)
        }
    }

    /**
     * 选择目标文件夹。进扫码页时自动弹一次，之后点顶部芯片可随时改。
     *
     * 上一版是「每扫一次弹一次」，客户反馈很不顺手——参考 App 的做法是扫之前选一次、
     * 扫的过程完全不打断，扫完再去历史页批量归类。这里改成同一套。
     *
     * 目标文件夹**不跨会话保留**：每次进扫码页都回到「未分组」，逼使用者主动确认这批
     * 货扫到哪儿，避免隔几天回来把新一批误扫进上一批。
     */
    private fun showFolderPicker(autoOpened: Boolean = false) {
        if (isFinishing || isDestroyed || returnResultOnly) return
        if (pendingFolderDialog != null) return

        val dialogBinding = DialogMoveToFolderBinding.inflate(layoutInflater)
        dialogBinding.titleText.setText(
            if (autoOpened) R.string.scan_folder_pick_title else R.string.scan_save_to_folder_title
        )
        // 仅首次自动弹出时才显示「记住此选择」，手动点芯片改文件夹时不显示
        dialogBinding.rememberFolderCheck.visibility = if (autoOpened) View.VISIBLE else View.GONE

        val dialog = BottomSheetDialog(this).apply {
            setContentView(dialogBinding.root)
            // BottomSheet 容器本身透明，圆角由布局的 bg_bottom_sheet 提供
            window?.setBackgroundDrawableResource(android.R.color.transparent)
        }

        val folders = AppSettings.getHistoryFolders(this).toList().sorted()
        // 「未分组」用空串表示，与 ScanHistoryEntity.groupName 的默认值一致。
        val options = listOf("") + folders
        var selected = targetFolder.takeIf { it in folders }.orEmpty()

        val rows = options.map { folder ->
            val row = TextView(this).apply {
                text = folder.ifBlank { getString(R.string.scan_folder_unsorted) }
                setTextColor(getColor(R.color.text_primary))
                textSize = 14f
                background = getDrawable(R.drawable.bg_dialog_input)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = dp(2); it.bottomMargin = dp(2) }
            }
            dialogBinding.folderListContainer.addView(row)
            folder to row
        }

        fun renderSelection() {
            rows.forEach { (folder, row) ->
                val active = folder == selected
                row.setTextColor(getColor(if (active) R.color.brand_primary else R.color.text_primary))
                row.paint.isFakeBoldText = active
            }
        }
        rows.forEach { (folder, row) ->
            row.setOnClickListener {
                selected = folder
                dialogBinding.newFolderInput.text?.clear()
                renderSelection()
            }
        }
        renderSelection()

        dialogBinding.confirmButton.setOnClickListener {
            val newFolder = dialogBinding.newFolderInput.text?.toString()?.trim().orEmpty()
            val target = if (newFolder.isNotEmpty()) {
                AppSettings.addHistoryFolder(this, newFolder)
                newFolder
            } else {
                selected
            }
            applyTargetFolder(target)
            // 勾选「记住此选择」时持久化文件夹，下次进页面直接应用、不再弹窗
            if (autoOpened && dialogBinding.rememberFolderCheck.isChecked) {
                AppSettings.setFolderRememberEnabled(this, true)
                AppSettings.setLastFolder(this, target)
            }
            dialog.dismiss()
        }
        dialogBinding.cancelButton.setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener {
            pendingFolderDialog = null
            // 用户已经有过一次选择机会，本 Activity 实例不再自动弹。
            // 转屏时 onDestroy 先摘掉本监听器再 dismiss，所以这里不会被误触发。
            if (autoOpened) folderPickerAutoOpened = true
            // 取消 / 点外部关闭时 applyTargetFolder 不会被调用，这里补一次，
            // 否则芯片会一直停在 startCamera 设的文案上，用户看不到目标文件夹。
            updateStatusText(idleStatusText())
            // 这里**不能**调 resumeScanning()：选择器已不在扫码分发链路上，它没暂停过任何东西；
            // 而 dispatchingResult 是在分发之前就置位的，抓拍可能正在进行中——
            // 此时清掉标志会让分析器提前重新武装，两条抓拍/分发流水线并发跑同一个码。
            // 恢复扫码由 handleDispatchResult 的四条分支各自负责。
        }

        pendingFolderDialog = dialog
        dialog.show()
    }

    /** 应用目标文件夹并刷新顶部芯片。 */
    private fun applyTargetFolder(folder: String) {
        targetFolder = folder
        scanViewModel.setTargetGroupName(folder)
        // 必须写 idleStatusText() 而不是 folderChipText()：被拒态下两者不同，
        // 写后者会被 dismiss 回调里的 idleStatusText() 立刻盖掉，芯片存活 0 毫秒。
        updateStatusText(idleStatusText())
    }

    private fun folderChipText(): String = getString(
        R.string.scan_folder_chip,
        targetFolder.ifBlank { getString(R.string.scan_folder_unsorted) }
    )

    /**
     * 空闲态的顶部胶囊文案。扫码状态（扫描中/重复/成功）只是瞬时覆盖，之后一律回落到这里。
     * 「扫码搜索」没有分组概念，仍显示「扫描中」；其余情况常驻文件夹芯片——
     * 芯片必须一直可见，否则用户不知道当前这批扫到哪儿去了。
     */
    private fun idleStatusText(): String = when {
        // 权限被拒时相机是黑的，但**相册读取照样产生记录**，所以目标文件夹一样要看得见。
        // 只显示「未授权」会让用户不知道相册导入的这批存到哪儿去了。
        cameraPermissionDenied -> getString(
            R.string.scan_status_denied_with_folder,
            targetFolder.ifBlank { getString(R.string.scan_folder_unsorted) }
        )
        returnResultOnly -> getString(R.string.scan_status_active)
        else -> folderChipText()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** 进程被杀会留下没人认领的抓拍图；cacheDir 本身会被系统回收，这里只做软兜底。 */
    private fun sweepStaleCaptures() {
        runCatching {
            val dir = File(cacheDir, "capture")
            if (!dir.isDirectory) return
            val deadline = System.currentTimeMillis() - STALE_CAPTURE_TIMEOUT_MS
            dir.listFiles()?.forEach { file ->
                if (file.isFile && file.lastModified() < deadline) file.delete()
            }
        }
    }

    /**
     * 写顶部胶囊，并**自动**安排回落到常驻文案。
     *
     * 胶囊兼任扫码状态与文件夹芯片，一旦某处只写不回落，目标文件夹就被永久盖住。
     * 之前靠「每个调用点都记得调 restoreActiveStatusLater()」维持，手电筒 / 对焦 /
     * 重置焦距 / 模式切换 / renderScanStatus 这五处全漏了。改成在这里统一处理——
     * 写的是常驻文案就取消回落，写的是瞬时提示就排一次，调用点无需再记任何事。
     */
    private fun updateStatusText(message: String, sticky: Boolean = false) {
        binding.scanStatusValue.text = message
        // 容器带 contentDescription 会被当成单个无障碍节点，子 View 的文字不再朗读，
        // 所以描述必须在这里随文本一起更新——写死在 XML 上等于让读屏用户听不到状态。
        binding.scanStatusChip.contentDescription =
            if (returnResultOnly) message
            else getString(R.string.scan_folder_chip_desc, message)
        binding.root.removeCallbacks(restoreActiveStatusRunnable)
        // sticky 用于「相册识别中」「保存中」这类真·长任务：它们要一直显示到任务结束，
        // 不能被 900ms 的回落打断。
        if (!sticky && message != idleStatusText()) {
            binding.root.postDelayed(restoreActiveStatusRunnable, STATUS_RESTORE_DELAY_MS)
        }
    }

    /** 顶部胶囊平时显示目标文件夹，点它可随时改；扫码状态只是瞬时覆盖，之后回落到这里。 */
    private fun setupFolderChip() {
        if (returnResultOnly) return
        // 点击目标放在整条胶囊上而不是里面那个 TextView——文字越短触摸区越小，
        // 只有一两个字时几乎点不中。
        binding.scanStatusChip.isClickable = true
        binding.scanStatusChip.isFocusable = true
        binding.scanStatusChip.setOnClickListener { showFolderPicker() }
        updateStatusText(folderChipText())
    }

    private fun renderScanStatus(state: ScanUiState) {
        val latestResult = state.latestResults.firstOrNull()
        val statusText = when {
            state.isProcessing -> getString(R.string.scan_status_processing)
            latestResult != null && state.blurImageRejected -> getString(
                R.string.scan_status_result_blur_skipped,
                latestResult
            )

            latestResult != null && !state.lastSavedImageUri.isNullOrBlank() -> getString(
                R.string.scan_status_result_image_saved,
                latestResult
            )

            latestResult != null -> getString(R.string.scan_status_result, latestResult)
            else -> null
        }
        if (statusText != null) {
            // 「保存中」要一直显示到状态流下一次发射，别被 900ms 回落打断。
            updateStatusText(statusText, sticky = state.isProcessing)
        }
        renderLiveResultCard(state, latestResult)
    }

    private fun renderLiveResultCard(state: ScanUiState, latestResult: String?) {
        // 不弹出扫码结果卡片，但仍更新已扫数量
        binding.liveScanCountText.text = state.savedCount.toString()
        binding.liveResultCard.visibility = View.GONE
    }

    private fun updateLiveResultThumbnail(imageUri: String) {
        if (imageUri.isBlank()) return
        binding.liveLatestImage.visibility = View.VISIBLE
        binding.liveLatestImage.setImageURI(imageUri.toUri())
    }

    private fun updateFlashUi() {
        binding.flashIcon.alpha = if (flashEnabled) 1f else 0.75f
        binding.flashText.alpha = if (flashEnabled) 1f else 0.85f
        updateControlVisualState()
    }

    private fun updateControlVisualState() {
        binding.flashToggleButton.setBackgroundResource(if (flashEnabled) R.drawable.bg_scan_control_active else R.drawable.bg_scan_control_idle)
        binding.continuousRow.setBackgroundResource(if (continuousScanEnabled) R.drawable.bg_scan_control_active else R.drawable.bg_scan_control_idle)
        binding.qrModeContainer.setBackgroundResource(if (currentMode == ScanCodeMode.QR_ONLY) R.drawable.bg_scan_control_active else R.drawable.bg_scan_control_idle)
        binding.barcodeModeContainer.setBackgroundResource(if (currentMode == ScanCodeMode.BARCODE_ONLY) R.drawable.bg_scan_control_active else R.drawable.bg_scan_control_idle)
    }

    private fun startScanLineAnimation() {
        val animation = AnimationUtils.loadAnimation(this, R.anim.scan_line_move)
        binding.scanLine.startAnimation(animation)
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // 只补状态栏高度，保留布局里定义的左右内边距
            binding.topOverlay.setPadding(
                binding.topOverlay.paddingLeft,
                systemBars.top + 12.dp,
                binding.topOverlay.paddingRight,
                binding.topOverlay.paddingBottom
            )
            // 重置对焦按钮现已在 bottomPanel 内部流式布局，
            // 底部安全区由面板自身的 padding 承担，无需再手工定位
            binding.bottomPanel.setPadding(
                binding.bottomPanel.paddingLeft,
                binding.bottomPanel.paddingTop,
                binding.bottomPanel.paddingRight,
                16.dp + systemBars.bottom
            )
            insets
        }
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    private fun dispatchScanResults(
        barcodes: List<com.google.mlkit.vision.barcode.common.Barcode>,
        capturedFile: File?
    ) {
        val dispatchResult = scanViewModel.onScanResults(barcodes, capturedFile)
        if (capturedFile != null && dispatchResult != ScanDispatchResult.ACCEPTED && capturedFile.exists()) {
            capturedFile.delete()
        }
        handleDispatchResult(dispatchResult)
    }

    private val restoreActiveStatusRunnable = Runnable {
        if (!isFinishing && !isDestroyed) {
            updateStatusText(idleStatusText())
        }
    }

}
