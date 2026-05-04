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
 * 带黑色描边的白色文字 TextView，支持 X/Y 自由拖拽和缩放。
 * 描边宽度与字号成比例，匹配 ASS Outline=3 的效果。
 */
class OutlineTextView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : AppCompatTextView(context, attrs, defStyle) {

    /** 拖动回调：返回字幕中心点在视频区域中的 X/Y 比例 (0~1) */
    var onPositionChanged: ((xRatio: Float, yRatio: Float) -> Unit)? = null
    /** 缩放回调 */
    var onScaleChanged: ((scale: Float) -> Unit)? = null

    // 视频在 PlayerView 中的实际显示区域
    private var videoTop = 0
    private var videoBottom = 0
    private var videoLeft = 0
    private var videoRight = 0

    private var currentScale = 1.0f
    private var baseFontSizePx = 0f

    private var lastTouchX = 0f
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

    /** 设置字幕中心在视频区域中的 X/Y 比例位置 */
    fun setPositionInVideo(xRatio: Float, yRatio: Float) {
        post {
            if (videoBottom <= videoTop || videoRight <= videoLeft) return@post
            val videoW = videoRight - videoLeft
            val videoH = videoBottom - videoTop

            val targetCenterX = videoLeft + xRatio * videoW
            x = (targetCenterX - width / 2f).coerceIn(
                videoLeft.toFloat(), (videoRight - width).toFloat()
            )

            val targetCenterY = videoTop + yRatio * videoH
            y = (targetCenterY - height / 2f).coerceIn(
                videoTop.toFloat(), (videoBottom - height).toFloat()
            )
        }
    }

    // ---- 描边绘制 ----

    override fun onDraw(canvas: Canvas) {
        val textColor = currentTextColor
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

    // ---- 触摸：自由拖动 + 缩放 ----

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        if (event.pointerCount == 1 && !scaleDetector.isInProgress) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.rawX
                    lastTouchY = event.rawY
                    isDragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isDragging) {
                        val dx = event.rawX - lastTouchX
                        val dy = event.rawY - lastTouchY
                        lastTouchX = event.rawX
                        lastTouchY = event.rawY

                        val newX = (x + dx).coerceIn(
                            (videoLeft - width / 2f), (videoRight - width / 2f)
                        )
                        val newY = (y + dy).coerceIn(
                            videoTop.toFloat(), (videoBottom - height).toFloat()
                        )
                        x = newX
                        y = newY

                        val videoW = (videoRight - videoLeft).toFloat()
                        val videoH = (videoBottom - videoTop).toFloat()
                        if (videoW > 0 && videoH > 0) {
                            val centerX = newX + width / 2f - videoLeft
                            val centerY = newY + height / 2f - videoTop
                            onPositionChanged?.invoke(
                                (centerX / videoW).coerceIn(0f, 1f),
                                (centerY / videoH).coerceIn(0f, 1f)
                            )
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
