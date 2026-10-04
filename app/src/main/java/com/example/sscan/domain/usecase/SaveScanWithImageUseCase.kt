package com.example.sscan.domain.usecase

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import com.example.sscan.core.settings.AppSettings
import com.example.sscan.core.storage.MediaStoreImageSaver
import com.example.sscan.core.utils.ImageSharpnessDetector
import com.example.sscan.core.utils.ImageWatermarkHelper
import com.example.sscan.data.repository.ScanRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class SaveScanWithImageResult(
    val savedCount: Int,
    val imageUri: String? = null,
    val blurRejected: Boolean = false
)

// 统一封装“扫码结果 + 原图文件”的保存流程，保证 UI 层只关心结果反馈。
class SaveScanWithImageUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scanRepository: ScanRepository,
    private val mediaStoreImageSaver: MediaStoreImageSaver,
    private val imageSharpnessDetector: ImageSharpnessDetector
) {

    // 先尝试保存图片，再把二维码结果写入 Room，最后清理临时文件。
    suspend operator fun invoke(
        results: List<String>,
        capturedImageFile: File?,
        groupName: String = "",
        forceSaveImage: Boolean = false
    ): SaveScanWithImageResult = withContext(Dispatchers.Default) {
        var imageUri: String? = null
        var blurRejected = false

        try {
            // forceSaveImage 只覆盖“是否要保留扫码图”；相册开关关闭时仍然不解码，也不写 MediaStore。
            val wantsImage = AppSettings.isSaveToAlbumEnabled(context) &&
                    (forceSaveImage || AppSettings.isSaveScanImageEnabled(context))
            val canProcessImage = wantsImage && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

            if (canProcessImage && capturedImageFile != null && capturedImageFile.exists()) {
                val sharpnessResult = imageSharpnessDetector.analyzeFile(capturedImageFile)
                if (sharpnessResult.isSharp) {
                    val bitmap = BitmapFactory.decodeFile(
                        capturedImageFile.absolutePath,
                        BitmapFactory.Options().apply {
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                            inMutable = true
                        }
                    )
                    if (bitmap != null) {
                        try {
                            val watermarkedBitmap =
                                ImageWatermarkHelper.addTimestampWatermark(bitmap)
                            imageUri = try {
                                mediaStoreImageSaver.saveBitmap(
                                    watermarkedBitmap,
                                    relativePath = buildRelativePath(groupName)
                                ).toString()
                            } finally {
                                if (watermarkedBitmap !== bitmap && !watermarkedBitmap.isRecycled) {
                                    watermarkedBitmap.recycle()
                                }
                            }
                        } finally {
                            if (!bitmap.isRecycled) {
                                bitmap.recycle()
                            }
                        }
                    }
                } else {
                    blurRejected = true
                }
            }

            results.forEach { result ->
                scanRepository.save(
                    result = result,
                    imageUri = imageUri.orEmpty(),
                    groupName = groupName
                )
            }

            SaveScanWithImageResult(
                savedCount = results.size,
                imageUri = imageUri,
                blurRejected = blurRejected
            )
        } finally {
            if (capturedImageFile != null && capturedImageFile.exists()) {
                capturedImageFile.delete()
            }
        }
    }

    @androidx.annotation.VisibleForTesting
    internal fun buildRelativePath(groupName: String): String {
        if (!AppSettings.isSaveSeparateFolder(context)) {
            return MediaStoreImageSaver.DEFAULT_RELATIVE_PATH
        }

        val safeGroupName = sanitizeGroupFolderName(groupName)
        return if (safeGroupName.isBlank()) {
            MediaStoreImageSaver.DEFAULT_RELATIVE_PATH
        } else {
            "${MediaStoreImageSaver.DEFAULT_RELATIVE_PATH}/$safeGroupName"
        }
    }

    @androidx.annotation.VisibleForTesting
    internal fun sanitizeGroupFolderName(groupName: String): String {
        return groupName
            .trim()
            .replace(Regex("""[/\\:*?"<>|]"""), "")
            .trim()
    }
}
