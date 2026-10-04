package com.example.sscan.core.camera

import android.content.Context
import android.graphics.Rect
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.MeteringPoint
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.sscan.core.scanner.MlKitProvider
import com.example.sscan.core.scanner.ScanCodeMode
import com.example.sscan.feature.scan.presentation.BarcodeAnnotation
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.io.File
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val onScanResults: (List<Barcode>) -> Unit,
    private val onAnnotationsDetected: (
        annotations: List<BarcodeAnnotation>,
        sourceWidth: Int,
        sourceHeight: Int,
        viewWidth: Int,
        viewHeight: Int
    ) -> Unit = { _, _, _, _, _ -> },
    private val onError: (Throwable) -> Unit = {}
) {

    private enum class CameraSessionState {
        IDLE,
        STARTING,
        BOUND,
        RELEASED
    }

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val dispatchingResult = AtomicBoolean(false)

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var imageCapture: ImageCapture? = null

    @Volatile
    private var camera: Camera? = null
    private var lastScanTime: Long = 0L
    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK
    private var scanMode: ScanCodeMode = ScanCodeMode.ALL
    private var analyzerThrottleMs: Long = 0L
    private var focusAutoCancelSeconds: Long = 1L
    private var sessionState: CameraSessionState = CameraSessionState.IDLE

    private val zoomCallback: (Float) -> Boolean = { ratio ->
        val currentCamera = camera
        currentCamera?.cameraControl?.setZoomRatio(ratio)
        currentCamera != null
    }

    @Volatile
    private var scanner: BarcodeScanner = MlKitProvider.createScanner(mode = scanMode)
    private var currentPreviewView: PreviewView? = null

    fun startCamera(
        previewView: PreviewView,
        lensFacing: Int = CameraSelector.LENS_FACING_BACK
    ) {
        sessionState = CameraSessionState.STARTING
        this.lensFacing = lensFacing
        this.currentPreviewView = previewView
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener(
            {
                try {
                    cameraProvider = providerFuture.get()
                    bindCamera(previewView, lensFacing)
                } catch (throwable: Throwable) {
                    onError(throwable)
                }
            },
            ContextCompat.getMainExecutor(context)
        )
    }

    fun stopCamera() {
        imageAnalysis?.clearAnalyzer()
        cameraProvider?.unbindAll()
        sessionState = CameraSessionState.IDLE
    }

    /**
     * 恢复扫码。注意这里**不再**触发一次性对焦。
     *
     * startFocusAndMetering 会把相机 AF 从 CONTINUOUS_PICTURE 切成一次性 AUTO
     * 并锁定焦点。此前每扫成功一个就调一次，导致连续扫码时焦点被锁死在上一个码
     * 的位置，移到下一个码上也不会重新合焦。
     * 现在一律依赖 CameraX 默认的连续自动对焦，只有用户点「重置对焦」或点触
     * 预览时才手动介入。
     */
    fun resumeScanning() {
        dispatchingResult.set(false)
    }

    fun setScanMode(mode: ScanCodeMode, previewView: PreviewView? = null) {
        if (scanMode == mode) return
        scanMode = mode
        if (previewView == null || cameraProvider == null) {
            rebuildScanner()
        }
        resumeScanning()
        previewView?.let { bindCamera(it, lensFacing) }
    }

    fun setTorchEnabled(enabled: Boolean) {
        camera?.cameraControl?.enableTorch(enabled)
    }

    fun updatePerformanceConfig(
        analyzerThrottleMs: Long,
        focusAutoCancelSeconds: Long
    ) {
        this.analyzerThrottleMs = analyzerThrottleMs
        this.focusAutoCancelSeconds = focusAutoCancelSeconds
    }

    fun resetFocus() {
        resumeScanning()
        startAutoFocus()
    }

    fun focusAt(point: MeteringPoint) {
        val currentCamera = camera ?: return
        currentCamera.cameraControl.startFocusAndMetering(createFocusMeteringAction(point))
    }

    fun release() {
        stopCamera()
        execOnCamera {
            scanner.close()
        }
        cameraExecutor.shutdown()
        sessionState = CameraSessionState.RELEASED
    }

    fun capturePhoto(
        outputDirectory: File,
        onCaptured: (File) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val captureUseCase = imageCapture
        if (captureUseCase == null) {
            onError(IllegalStateException("ImageCapture is not initialized"))
            return
        }

        val photoFile = File(outputDirectory, "CAPTURE_${UUID.randomUUID()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        captureUseCase.takePicture(
            options,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    onCaptured(photoFile)
                }

                override fun onError(exception: ImageCaptureException) {
                    if (photoFile.exists()) {
                        photoFile.delete()
                    }
                    onError(exception)
                }
            }
        )
    }

    private fun bindCamera(previewView: PreviewView, lensFacing: Int) {
        this.lensFacing = lensFacing
        val provider = cameraProvider ?: return
        val preview = createPreviewUseCase(previewView)
        imageAnalysis = createAnalysisUseCase()
        imageCapture = createImageCaptureUseCase()
        val selector = createCameraSelector(lensFacing)

        provider.unbindAll()

        // 使用 ViewPort + UseCaseGroup 确保 Preview、ImageAnalysis、ImageCapture
        // 共享完全相同的传感器裁剪区域和坐标系统，这是解决条码框与二维码不对齐的关键。
        // 注意：previewView.display 在视图尚未 attach to window 时可能为 null，需回退处理。
        val previewViewWidth = previewView.width.coerceAtLeast(1)
        val previewViewHeight = previewView.height.coerceAtLeast(1)

        val rotation = previewView.display?.rotation
            ?: (context.getSystemService(android.content.Context.WINDOW_SERVICE) as? android.view.WindowManager)
                ?.defaultDisplay?.rotation
            ?: android.view.Surface.ROTATION_0

        val viewPort = ViewPort.Builder(
            android.util.Rational(previewViewWidth, previewViewHeight),
            rotation
        ).build()

        val useCaseGroup = UseCaseGroup.Builder()
            .setViewPort(viewPort)
            .addUseCase(preview)
            .addUseCase(imageAnalysis!!)
            .addUseCase(imageCapture!!)
            .build()

        camera = provider.bindToLifecycle(
            lifecycleOwner,
            selector,
            useCaseGroup
        )
        rebuildScanner()
        sessionState = CameraSessionState.BOUND
    }

    private fun createPreviewUseCase(previewView: PreviewView): Preview {
        return Preview.Builder()
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }
    }

    @ExperimentalGetImage
    private fun createAnalysisUseCase(): ImageAnalysis {
        val resolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(1280, 720),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            .build()

        return ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(resolutionSelector)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(cameraExecutor, BarcodeAnalyzer())
            }
    }

    private fun createImageCaptureUseCase(): ImageCapture {
        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(1920, 1080),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                )
            )
            .build()

        return ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setJpegQuality(95)
            .setResolutionSelector(resolutionSelector)
            .build()
    }

    private fun createCameraSelector(lensFacing: Int): CameraSelector {
        return CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()
    }

    /**
     * 「重置对焦」按钮：先取消既有的对焦锁定，让相机回到连续自动对焦，
     * 再对取景框中心做一次合焦。
     *
     * 定点必须用 previewView.meteringPointFactory —— 它带预览的裁剪与旋转变换。
     * 原来用的 SurfaceOrientedMeteringPointFactory 是 surface 归一化坐标，
     * fillCenter 模式下算出的点未必落在用户看到的中心。
     */
    private fun startAutoFocus() {
        val currentCamera = camera ?: return
        val previewView = currentPreviewView ?: return
        currentCamera.cameraControl.cancelFocusAndMetering()
        if (previewView.width <= 0 || previewView.height <= 0) return
        val point = previewView.meteringPointFactory
            .createPoint(previewView.width / 2f, previewView.height / 2f)
        currentCamera.cameraControl.startFocusAndMetering(createFocusMeteringAction(point))
    }

    private fun createFocusMeteringAction(point: MeteringPoint): FocusMeteringAction {
        val builder = FocusMeteringAction.Builder(point)
        // auto-cancel 到期后相机会自己回到连续自动对焦，因此**必须**设置。
        // 设为 0 等于永久锁定焦点，正是「移动后不重新对焦」的成因，故给下限。
        val seconds = if (focusAutoCancelSeconds > 0) focusAutoCancelSeconds else DEFAULT_FOCUS_AUTO_CANCEL_SECONDS
        builder.setAutoCancelDuration(seconds, TimeUnit.SECONDS)
        return builder.build()
    }

    private fun rebuildScanner() {
        val maxZoomRatio = camera?.cameraInfo?.zoomState?.value?.maxZoomRatio
        val mode = scanMode
        execOnCamera {
            val previous = scanner
            scanner = MlKitProvider.createScanner(
                mode = mode,
                zoomCallback = zoomCallback,
                maxSupportedZoomRatio = maxZoomRatio
            )
            previous.close()
        }
    }

    private fun execOnCamera(block: () -> Unit) {
        if (cameraExecutor.isShutdown) return
        runCatching { cameraExecutor.execute(block) }
    }

    @ExperimentalGetImage
    private inner class BarcodeAnalyzer : ImageAnalysis.Analyzer {
        override fun analyze(imageProxy: ImageProxy) {
            val mediaImage = imageProxy.image
            if (mediaImage == null) {
                imageProxy.close()
                return
            }

            val now = System.currentTimeMillis()
            if (shouldSkipFrame(now)) {
                imageProxy.close()
                return
            }

            val inputImage = InputImage.fromMediaImage(
                mediaImage,
                imageProxy.imageInfo.rotationDegrees
            )

            scanner.process(inputImage)
                .addOnSuccessListener { barcodes ->
                    // ML Kit 已考虑 rotationDegrees，boundingBox 位于旋转后图像坐标系中
                    val (sourceWidth, sourceHeight) = resolveSourceDimensions(imageProxy)
                    val viewWidth = currentPreviewView?.width ?: sourceWidth
                    val viewHeight = currentPreviewView?.height ?: sourceHeight
                    onAnnotationsDetected(
                        barcodes.mapNotNull { barcode ->
                            val box = barcode.boundingBox ?: return@mapNotNull null
                            barcode.toAnnotation(box)
                        },
                        sourceWidth,
                        sourceHeight,
                        viewWidth,
                        viewHeight
                    )
                    dispatchDetectedResults(barcodes, now)
                }
                .addOnFailureListener(onError)
                .addOnCompleteListener {
                    imageProxy.close()
                }
        }
    }

    private fun shouldSkipFrame(now: Long): Boolean {
        return now - lastScanTime < analyzerThrottleMs
    }

    private fun resolveSourceDimensions(imageProxy: ImageProxy): Pair<Int, Int> {
        return if (imageProxy.imageInfo.rotationDegrees % 180 == 0) {
            imageProxy.width to imageProxy.height
        } else {
            imageProxy.height to imageProxy.width
        }
    }

    private fun dispatchDetectedResults(barcodes: List<Barcode>, now: Long) {
        val filtered = barcodes
            .filter { !it.rawValue.isNullOrBlank() }
            .take(6)
        if (filtered.isNotEmpty() && dispatchingResult.compareAndSet(false, true)) {
            lastScanTime = now
            onScanResults(filtered)
        }
    }

    private fun Barcode.toAnnotation(box: Rect): BarcodeAnnotation {
        return BarcodeAnnotation(
            left = box.left.toFloat(),
            top = box.top.toFloat(),
            right = box.right.toFloat(),
            bottom = box.bottom.toFloat(),
            label = rawValue.orEmpty()
        )
    }

    private companion object {
        /** 对焦锁定的最长时长，到期自动回到连续对焦。 */
        const val DEFAULT_FOCUS_AUTO_CANCEL_SECONDS = 2L
    }
}
