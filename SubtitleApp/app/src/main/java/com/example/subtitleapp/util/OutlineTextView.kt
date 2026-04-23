package com.example.subtitleapp.util

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.appcompat.widget.AppCompatTextView

/**
 * 带黑色描边的白色文字 TextView，支持拖拽和缩放。
 * 描边宽度与字号成比例，匹配 ASS Outline=3 的效果。
 */
class OutlineTextView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : AppCompatTextView(context, attrs, defStyle) {

    /** 拖动回调：返回字幕中心点在父容器中的 Y 比例 (0~1) */
    var onPositionChanged: ((yRatio: Float) -> Unit)? = null
    /** 缩放回调 */
    var onScaleChanged: ((scale: Float) -> Unit)? = null

    // 视频在 PlayerView 中的实际显示区域
    private var videoTop = 0
    private var videoBottom = 0
    private var videoLeft = 0
    private var videoRight = 0

    private var currentScale = 1.0f
    private var baseFontSizePx = 0f  // 由外部根据视频显示高度计算后设置

    private var lastTouchY = 0f
    private var isDragging = false

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                currentScale = (currentScale * detector.scaleFactor).coerceIn(0.5f, 3.0f)
                applyFontSize()
                onScaleChanged?.invoke(currentScale)
                return true
            }
        })

    /** 设置视频在父容器中的实际显示区域 */
    fun setVideoDisplayRect(left: Int, top: Int, right: Int, bottom: Int) {
        videoLeft = left
        videoTop = top
        videoRight = right
        videoBottom = bottom
    }

    /** 设置基准字号（像素），由外部根据视频显示高度计算 */
    fun setBaseFontSize(sizePx: Float) {
        baseFontSizePx = sizePx
        applyFontSize()
    }

    fun setScale(scale: Float) {
        currentScale = scale.coerceIn(0.5f, 3.0f)
        applyFontSize()
    }

    private fun applyFontSize() {
        if (baseFontSizePx > 0) {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, baseFontSizePx * currentScale)
        }
    }

    /** 设置字幕中心 Y 在视频区域中的比例位置 */
    fun setPositionInVideo(yRatio: Float) {
        post {
            if (videoBottom <= videoTop) return@post
            val videoH = videoBottom - videoTop
            val targetCenterY = videoTop + yRatio * videoH
            y = (targetCenterY - height / 2f).coerceIn(
                videoTop.toFloat(), (videoBottom - height).toFloat()
            )
        }
    }

    // ---- 描边绘制 ----

    override fun onDraw(canvas: Canvas) {
        val textColor = currentTextColor
        // 描边宽度与字号成比例：ASS 中 Outline=3 对应 fontSize 的约 6%
        val strokeW = textSize * 0.06f

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokeW
        paint.strokeJoin = Paint.Join.ROUND
        setTextColor(Color.BLACK)
        super.onDraw(canvas)

        paint.style = Paint.Style.FILL
        paint.strokeWidth = 0f
        setTextColor(textColor)
        super.onDraw(canvas)
    }

    // ---- 触摸：拖动 + 缩放 ----

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        if (event.pointerCount == 1 && !scaleDetector.isInProgress) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchY = event.rawY
                    isDragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isDragging) {
                        val dy = event.rawY - lastTouchY
                        lastTouchY = event.rawY
                        val newY = (y + dy).coerceIn(
                            videoTop.toFloat(), (videoBottom - height).toFloat()
                        )
                        y = newY
                        // 计算中心点在视频区域中的 Y 比例
                        val videoH = (videoBottom - videoTop).toFloat()
                        if (videoH > 0) {
                            val centerY = newY + height / 2f - videoTop
                            onPositionChanged?.invoke((centerY / videoH).coerceIn(0f, 1f))
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDragging = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
            }
        }
        return true
    }
}
