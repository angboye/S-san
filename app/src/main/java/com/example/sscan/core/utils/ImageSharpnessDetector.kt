package com.example.sscan.core.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import javax.inject.Inject
import kotlin.math.min

data class SharpnessResult(
    val variance: Double,
    val isSharp: Boolean
)

class ImageSharpnessDetector @Inject constructor() {

    companion object {
        // 刻意放宽的下限，只拦截完全不可用的图；未做真实设备样本校准。
        // 要恢复严格判定，必须先采集真实扫码图并按“可用图误拒率 ≤1%”定阈值，别凭理论拍数字。
        // 用户已扫码成功，误拒的代价高于多存一张一般质量图。
        private const val SHARPNESS_THRESHOLD = 15.0
        private const val ANALYSIS_LONG_EDGE = 512
    }

    fun isSharp(bitmap: Bitmap, thresholdOverride: Double? = null): Boolean {
        return analyze(bitmap, thresholdOverride).isSharp
    }

    fun analyze(bitmap: Bitmap, thresholdOverride: Double? = null): SharpnessResult {
        require(bitmap.width >= 3 && bitmap.height >= 3) {
            "Bitmap must be at least 3x3 for sharpness analysis."
        }

        val workingBitmap = downSampleIfNeeded(bitmap)
        val thresholdValue = thresholdOverride ?: SHARPNESS_THRESHOLD

        return try {
            analyzeBitmap(workingBitmap, thresholdValue)
        } finally {
            if (workingBitmap !== bitmap && !workingBitmap.isRecycled) {
                workingBitmap.recycle()
            }
        }
    }

    fun analyzeFile(file: File): SharpnessResult {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(file.absolutePath, bounds)

        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width < 3 || height < 3) {
            return SharpnessResult(0.0, false)
        }

        val sampleSize = chooseInSampleSize(width, height, ANALYSIS_LONG_EDGE)
        var decodedBitmap: Bitmap? = null
        var workingBitmap: Bitmap? = null

        return try {
            decodedBitmap = BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
            ) ?: return SharpnessResult(0.0, false)

            workingBitmap = downSampleIfNeeded(decodedBitmap)
            analyzeBitmap(workingBitmap, SHARPNESS_THRESHOLD)
        } finally {
            if (workingBitmap != null && workingBitmap !== decodedBitmap && !workingBitmap.isRecycled) {
                workingBitmap.recycle()
            }
            if (decodedBitmap != null && !decodedBitmap.isRecycled) {
                decodedBitmap.recycle()
            }
        }
    }

    private fun analyzeBitmap(bitmap: Bitmap, thresholdValue: Double): SharpnessResult {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val grayscale = DoubleArray(width * height)
        for (index in pixels.indices) {
            val color = pixels[index]
            val red = color shr 16 and 0xFF
            val green = color shr 8 and 0xFF
            val blue = color and 0xFF
            grayscale[index] = red * 0.299 + green * 0.587 + blue * 0.114
        }

        var sum = 0.0
        var sumSquares = 0.0
        var count = 0

        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val center = y * width + x
                val laplacian = (
                        grayscale[center - width] +
                                grayscale[center - 1] -
                                (4 * grayscale[center]) +
                                grayscale[center + 1] +
                                grayscale[center + width]
                        )

                sum += laplacian
                sumSquares += laplacian * laplacian
                count++
            }
        }

        val mean = if (count == 0) 0.0 else sum / count
        val variance = if (count == 0) {
            0.0
        } else {
            (sumSquares / count) - (mean * mean)
        }

        return SharpnessResult(
            variance = variance,
            isSharp = variance >= thresholdValue
        )
    }

    private fun downSampleIfNeeded(bitmap: Bitmap): Bitmap {
        val longestEdge = maxOf(bitmap.width, bitmap.height)
        if (longestEdge <= ANALYSIS_LONG_EDGE) {
            return bitmap
        }

        val scale = ANALYSIS_LONG_EDGE.toFloat() / longestEdge.toFloat()
        val targetWidth = maxOf(3, min(ANALYSIS_LONG_EDGE, (bitmap.width * scale).toInt()))
        val targetHeight = maxOf(3, min(ANALYSIS_LONG_EDGE, (bitmap.height * scale).toInt()))

        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    private fun chooseInSampleSize(width: Int, height: Int, targetLongEdge: Int): Int {
        val longest = maxOf(width, height)
        var sample = 1
        while (longest / (sample * 2) >= targetLongEdge) {
            sample *= 2
        }
        return sample
    }
}
