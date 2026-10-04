package com.example.sscan.feature.history.presentation

import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

/**
 * 支持双指缩放、双击放大还原、单指拖动的图片预览控件。
 * 图片加载后自动居中适应屏幕。
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val matrix = Matrix()
    private val scaleDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetector

    private var baseScale = 1f
    private var scaleFactor = 1f
    private val minScale = 1f
    private val maxScale = 5f

    private var lastPoint = PointF()
    private var isDragging = false
    private var activePointerId = MotionEvent.INVALID_POINTER_ID

    init {
        scaleType = ScaleType.MATRIX
        scaleDetector = ScaleGestureDetector(context, ScaleListener())
        gestureDetector = GestureDetector(context, GestureListener())
        setOnTouchListener { _, event -> handleTouch(event) }
    }

    override fun setImageURI(uri: android.net.Uri?) {
        super.setImageURI(uri)
        post { centerImage() }
    }

    override fun setImageResource(resId: Int) {
        super.setImageResource(resId)
        post { centerImage() }
    }

    /**
     * 将图片居中并适应屏幕，作为缩放的基准状态。
     */
    private fun centerImage() {
        val drawable = drawable ?: return
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        val drawWidth = drawable.intrinsicWidth.toFloat()
        val drawHeight = drawable.intrinsicHeight.toFloat()

        if (viewWidth == 0f || viewHeight == 0f || drawWidth == 0f || drawHeight == 0f) return

        // 计算适应屏幕的缩放比例
        val scale = minOf(viewWidth / drawWidth, viewHeight / drawHeight)
        baseScale = scale
        scaleFactor = 1f

        matrix.reset()
        // 先缩放到适应屏幕
        matrix.setScale(scale, scale)
        // 再平移居中
        val scaledWidth = drawWidth * scale
        val scaledHeight = drawHeight * scale
        val dx = (viewWidth - scaledWidth) / 2f
        val dy = (viewHeight - scaledHeight) / 2f
        matrix.postTranslate(dx, dy)

        imageMatrix = matrix
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                lastPoint.set(event.x, event.y)
                isDragging = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging && !scaleDetector.isInProgress) {
                    val pointerIndex = event.findPointerIndex(activePointerId)
                    if (pointerIndex != -1) {
                        val dx = event.getX(pointerIndex) - lastPoint.x
                        val dy = event.getY(pointerIndex) - lastPoint.y
                        matrix.postTranslate(dx, dy)
                        imageMatrix = matrix
                        lastPoint.set(event.getX(pointerIndex), event.getY(pointerIndex))
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                activePointerId = MotionEvent.INVALID_POINTER_ID
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val newPointerIndex = if (event.actionIndex == 0) 1 else 0
                activePointerId = event.getPointerId(newPointerIndex)
                lastPoint.set(event.getX(newPointerIndex), event.getY(newPointerIndex))
            }
        }
        return true
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scale = detector.scaleFactor
            val newScale = (scaleFactor * scale).coerceIn(minScale, maxScale)
            val realScale = newScale / scaleFactor
            matrix.postScale(realScale, realScale, detector.focusX, detector.focusY)
            imageMatrix = matrix
            scaleFactor = newScale
            return true
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            val targetScale = if (scaleFactor > 1.5f) minScale else 2.5f
            val realScale = targetScale / scaleFactor
            matrix.postScale(realScale, realScale, e.x, e.y)
            imageMatrix = matrix
            scaleFactor = targetScale
            return true
        }
    }

    /** 重置到初始居中状态。 */
    fun reset() {
        centerImage()
    }
}
