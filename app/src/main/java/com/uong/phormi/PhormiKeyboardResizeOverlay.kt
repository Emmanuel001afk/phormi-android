package com.uong.phormi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/** Gboard-style keyboard resize/move frame. */
class PhormiKeyboardResizeOverlay(
    context: Context,
    private val allowMove: Boolean,
    private val allowResize: Boolean,
    private val onChange: (widthScale: Float, heightScale: Float, offsetX: Float, offsetY: Float) -> Unit,
    private val onDone: () -> Unit
) : View(context) {
    private enum class Handle { NONE, LEFT, RIGHT, TOP, BOTTOM, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, MOVE, DONE }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = 0xFF42A5F5.toInt()
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF42A5F5.toInt()
    }
    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF42A5F5.toInt()
    }
    private val checkText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        textSize = dp(16f)
    }
    private var handle = Handle.NONE
    private var startRawX = 0f
    private var startRawY = 0f
    private var startWidth = 1f
    private var startHeight = 1f
    private var startOffsetX = 0f
    private var startOffsetY = 0f

    init {
        isClickable = true
        setWillNotDraw(false)
        contentDescription = if (allowResize) "Resize and move keyboard" else "Move floating keyboard"
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = dp(8f)
        val rect = RectF(inset, inset, width - inset, height - inset)
        borderPaint.alpha = if (allowResize) 255 else 150
        canvas.drawRoundRect(rect, dp(8f), dp(8f), borderPaint)

        if (allowResize) {
            drawHandle(canvas, rect.left, rect.top)
            drawHandle(canvas, rect.centerX(), rect.top)
            drawHandle(canvas, rect.right, rect.top)
            drawHandle(canvas, rect.left, rect.centerY())
            drawHandle(canvas, rect.right, rect.centerY())
            drawHandle(canvas, rect.left, rect.bottom)
            drawHandle(canvas, rect.centerX(), rect.bottom)
            drawHandle(canvas, rect.right, rect.bottom)
            canvas.drawCircle(rect.right - dp(22f), rect.top + dp(22f), dp(15f), checkPaint)
            canvas.drawText("✓", rect.right - dp(22f), rect.top + dp(28f), checkText)
        }

        if (allowMove) {
            val cx = rect.centerX()
            val cy = rect.bottom
            canvas.drawCircle(cx, cy, dp(18f), checkPaint)
            canvas.drawText("⠿", cx, cy + dp(6f), checkText)
        }
    }

    private fun drawHandle(canvas: Canvas, x: Float, y: Float) {
        canvas.drawCircle(x, y, dp(6f), handlePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val inset = dp(8f)
        val left = inset
        val right = width - inset
        val top = inset
        val bottom = height - inset
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        val radius = dp(28f)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                handle = pickHandle(event.x, event.y, left, right, top, bottom, cx, cy, radius)
                if (handle == Handle.NONE) return false
                startRawX = event.rawX
                startRawY = event.rawY
                val p = context.getSharedPreferences("phormi_keyboard", Context.MODE_PRIVATE)
                startWidth = p.getFloat("keyboard_width_scale", 1f).coerceIn(0.55f, 1f)
                startHeight = p.getFloat("keyboard_height_scale", 1f).coerceIn(0.60f, 1.40f)
                startOffsetX = p.getFloat("keyboard_offset_x", 0f).coerceIn(-0.9f, 0.9f)
                startOffsetY = p.getFloat("keyboard_offset_y", 0f).coerceIn(-0.9f, 0.9f)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (handle == Handle.NONE || handle == Handle.DONE) return true
                val screenW = resources.displayMetrics.widthPixels.coerceAtLeast(1).toFloat()
                val screenH = resources.displayMetrics.heightPixels.coerceAtLeast(1).toFloat()
                val dx = event.rawX - startRawX
                val dy = event.rawY - startRawY
                var widthScale = startWidth
                var heightScale = startHeight
                var offsetX = startOffsetX
                var offsetY = startOffsetY

                when (handle) {
                    Handle.MOVE -> {
                        offsetX = (startOffsetX + dx / screenW * 1.8f).coerceIn(-0.9f, 0.9f)
                        offsetY = (startOffsetY + dy / screenH * 1.8f).coerceIn(-0.9f, 0.9f)
                    }
                    Handle.LEFT -> {
                        widthScale = (startWidth - dx / screenW).coerceIn(0.55f, 1f)
                        // Keep the right edge fixed while the left edge moves.
                        val widthDeltaPx = (widthScale - startWidth) * screenW
                        offsetX = (startOffsetX - widthDeltaPx / screenW * 1.8f).coerceIn(-0.9f, 0.9f)
                    }
                    Handle.RIGHT -> widthScale = (startWidth + dx / screenW).coerceIn(0.55f, 1f)
                    Handle.TOP -> {
                        heightScale = (startHeight - dy / screenH).coerceIn(0.60f, 1.40f)
                        if (allowMove) {
                            val heightDeltaPx = (heightScale - startHeight) * screenH
                            offsetY = (startOffsetY - heightDeltaPx / screenH * 1.8f).coerceIn(-0.9f, 0.9f)
                        }
                    }
                    Handle.BOTTOM -> heightScale = (startHeight + dy / screenH).coerceIn(0.60f, 1.40f)
                    Handle.TOP_LEFT -> {
                        widthScale = (startWidth - dx / screenW).coerceIn(0.55f, 1f)
                        heightScale = (startHeight - dy / screenH).coerceIn(0.60f, 1.40f)
                        val widthDeltaPx = (widthScale - startWidth) * screenW
                        val heightDeltaPx = (heightScale - startHeight) * screenH
                        offsetX = (startOffsetX - widthDeltaPx / screenW * 1.8f).coerceIn(-0.9f, 0.9f)
                        if (allowMove) offsetY = (startOffsetY - heightDeltaPx / screenH * 1.8f).coerceIn(-0.9f, 0.9f)
                    }
                    Handle.TOP_RIGHT -> {
                        widthScale = (startWidth + dx / screenW).coerceIn(0.55f, 1f)
                        heightScale = (startHeight - dy / screenH).coerceIn(0.60f, 1.40f)
                        val heightDeltaPx = (heightScale - startHeight) * screenH
                        if (allowMove) offsetY = (startOffsetY - heightDeltaPx / screenH * 1.8f).coerceIn(-0.9f, 0.9f)
                    }
                    Handle.BOTTOM_LEFT -> {
                        widthScale = (startWidth - dx / screenW).coerceIn(0.55f, 1f)
                        heightScale = (startHeight + dy / screenH).coerceIn(0.60f, 1.40f)
                        val widthDeltaPx = (widthScale - startWidth) * screenW
                        offsetX = (startOffsetX - widthDeltaPx / screenW * 1.8f).coerceIn(-0.9f, 0.9f)
                    }
                    Handle.BOTTOM_RIGHT -> {
                        widthScale = (startWidth + dx / screenW).coerceIn(0.55f, 1f)
                        heightScale = (startHeight + dy / screenH).coerceIn(0.60f, 1.40f)
                    }
                    else -> Unit
                }
                onChange(widthScale, heightScale, offsetX, offsetY)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (handle == Handle.DONE) onDone()
                handle = Handle.NONE
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                handle = Handle.NONE
                return true
            }
        }
        return true
    }

    private fun pickHandle(x: Float, y: Float, left: Float, right: Float, top: Float, bottom: Float, cx: Float, cy: Float, radius: Float): Handle {
        if (allowResize && distance(x, y, right - dp(22f), top + dp(22f)) <= radius) return Handle.DONE
        if (allowMove && distance(x, y, cx, bottom) <= radius) return Handle.MOVE
        if (!allowResize) return Handle.NONE
        return when {
            distance(x, y, left, top) <= radius -> Handle.TOP_LEFT
            distance(x, y, cx, top) <= radius -> Handle.TOP
            distance(x, y, right, top) <= radius -> Handle.TOP_RIGHT
            distance(x, y, left, cy) <= radius -> Handle.LEFT
            distance(x, y, right, cy) <= radius -> Handle.RIGHT
            distance(x, y, left, bottom) <= radius -> Handle.BOTTOM_LEFT
            distance(x, y, cx, bottom) <= radius -> Handle.BOTTOM
            distance(x, y, right, bottom) <= radius -> Handle.BOTTOM_RIGHT
            else -> Handle.NONE
        }
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float =
        kotlin.math.sqrt((x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2))

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
