package com.example.sscan.core.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

object ImageWatermarkHelper {

    /**
     * 给图片加时间戳水印。
     *
     * 若 source 已经是 mutable，会直接在原图上绘制并返回 source 本身；否则会先复制一份可写位图。
     * 调用方不能假设返回值一定是新对象，回收时要按实例是否相同分别处理。
     */
    fun addTimestampWatermark(
        source: Bitmap,
        timestampMillis: Long = System.currentTimeMillis()
    ): Bitmap {
        val mutableBitmap = if (source.isMutable) {
            source
        } else {
            source.copy(Bitmap.Config.ARGB_8888, true)
        }
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val canvas = Canvas(mutableBitmap)
        val watermarkText = formatter.format(Date(timestampMillis))
        val density = mutableBitmap.density.takeIf { it > 0 } ?: 320
        val scale = density / 160f
        val textSize = max(24f, 14f * scale)
        val padding = 12f * scale
        val bottomInset = 18f * scale
        val radius = 16f * scale

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            setShadowLayer(10f, 0f, 2f, Color.parseColor("#66000000"))
        }
        val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#8A111111")
        }

        val textWidth = textPaint.measureText(watermarkText)
        val textHeight = textPaint.fontMetrics.run { bottom - top }
        val boxWidth = textWidth + padding * 2
        val boxHeight = textHeight + padding * 1.4f
        val left = mutableBitmap.width - boxWidth - padding * 1.3f
        val top = mutableBitmap.height - boxHeight - bottomInset
        val right = mutableBitmap.width - padding * 1.3f
        val bottom = mutableBitmap.height - bottomInset

        canvas.drawRoundRect(RectF(left, top, right, bottom), radius, radius, backgroundPaint)
        val baseline = bottom - padding * 0.7f - textPaint.fontMetrics.bottom
        canvas.drawText(watermarkText, left + padding, baseline, textPaint)
        return mutableBitmap
    }
}
