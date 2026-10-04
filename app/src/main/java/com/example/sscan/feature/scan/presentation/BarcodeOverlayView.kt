package com.example.sscan.feature.scan.presentation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

data class BarcodeAnnotation(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val label: String
)

// 在相机预览上绘制检测到的条码边框和文字标注。
class BarcodeOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2CE6B4")
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    private val labelBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC0D1B1E")
        style = Paint.Style.FILL
    }

    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 32f
    }

    private var annotations: List<BarcodeAnnotation> = emptyList()
    private var sourceWidth: Int = 1
    private var sourceHeight: Int = 1
    private var viewWidth: Int = 1
    private var viewHeight: Int = 1

    fun updateAnnotations(
        items: List<BarcodeAnnotation>,
        sourceWidth: Int,
        sourceHeight: Int,
        viewWidth: Int,
        viewHeight: Int
    ) {
        annotations = items
        this.sourceWidth = sourceWidth.coerceAtLeast(1)
        this.sourceHeight = sourceHeight.coerceAtLeast(1)
        this.viewWidth = if (viewWidth > 0) viewWidth else this.sourceWidth
        this.viewHeight = if (viewHeight > 0) viewHeight else this.sourceHeight
        invalidate()
    }

    fun clearAnnotations() {
        annotations = emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (annotations.isEmpty()) return

        // 使用 PreviewView 的实际尺寸来计算变换（而非 BarcodeOverlayView 的全屏尺寸），
        // 这样可以保证条码框与相机预览中二维码的实际位置精确对齐。
        // PreviewView 使用 FILL_CENTER（CENTER_CROP 风格），所以使用 maxOf 来选择较大的缩放比。
        val scale = maxOf(viewWidth / sourceWidth.toFloat(), viewHeight / sourceHeight.toFloat())
        val offsetX = (viewWidth - sourceWidth * scale) / 2f
        val offsetY = (viewHeight - sourceHeight * scale) / 2f
        // BarcodeOverlayView 和 PreviewView 都是铺满父容器的，
        // 但如果它们的起点不同，需要加上 BarcodeOverlayView 与 PreviewView 的左/上边距偏移。
        // 这里两者都是 match_parent，所以起点一致（都是 0,0），可以直接使用 offsetX/offsetY。

        annotations.forEach { item ->
            val rect = RectF(
                item.left * scale + offsetX,
                item.top * scale + offsetY,
                item.right * scale + offsetX,
                item.bottom * scale + offsetY
            )
            canvas.drawRoundRect(rect, 18f, 18f, framePaint)

            val label = item.label.take(24)
            val textWidth = labelTextPaint.measureText(label)
            val textHeight = labelTextPaint.textSize
            val labelRect = RectF(
                rect.left,
                (rect.top - textHeight - 24f).coerceAtLeast(0f),
                rect.left + textWidth + 28f,
                rect.top - 8f
            )
            canvas.drawRoundRect(labelRect, 16f, 16f, labelBackgroundPaint)
            canvas.drawText(
                label,
                labelRect.left + 14f,
                labelRect.bottom - 12f,
                labelTextPaint
            )
        }
    }
}
