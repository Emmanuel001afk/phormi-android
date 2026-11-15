package com.uong.phormi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Gboard-style frame for the floating keyboard.
 *
 * A slim grab bar centred on the top edge moves the panel; the four corner handles
 * resize it. Edges of the keyboard remain live typing surface — only the handle zones
 * are intercepted, so keys keep working exactly as in the docked layout.
 *
 * Every change is applied straight to the IME window via [onLiveRect] (no view
 * rebuilds mid-gesture) and committed once when the finger lifts via [onCommit],
 * which persists the geometry and re-renders the keys once. This keeps dragging
 * and resizing fluid instead of stiff.
 */
class PhormiKeyboardResizeOverlay(
    context: Context,
    /** Current window rect as [x, y, width, height] in pixels. */
    private val startRect: () -> IntArray,
    private val onLiveRect: (x: Int, y: Int, width: Int, height: Int) -> Unit,
    private val onCommit: () -> Unit
) : View(context) {

    private enum class Handle { NONE, MOVE, LEFT, RIGHT, TOP, BOTTOM, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = PhormiUiColors.accent(context)
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = PhormiUiColors.accent(context)
        alpha = 200
    }
    private var handle = Handle.NONE
    private var startRawX = 0f
    private var startRawY = 0f
    private var rectX = 0
    private var rectY = 0
    private var rectW = 0
    private var rectH = 0

    init {
        isClickable = true
        setWillNotDraw(false)
        contentDescription = "Floating keyboard. Drag the bar on the top edge to move; drag a corner to resize."
    }

    private val grabHalfWidth get() = dp(52f)
    private val grabHalfHeight get() = dp(26f)
    private val cornerRadius get() = dp(28f)
    private val edgeZone get() = dp(20f)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = dp(4f)
        canvas.drawRoundRect(RectF(inset, inset, width - inset, height - inset), dp(16f), dp(16f), borderPaint)
        // Grab bar, top centre — mirrors the handle of a Gboard floating keyboard.
        val barWidth = dp(56f)
        val barHeight = dp(6f)
        val barLeft = (width - barWidth) / 2f
        val barTop = inset + dp(7f)
        val bar = RectF(barLeft, barTop, barLeft + barWidth, barTop + barHeight)
        val fill = Paint(accentPaint).apply { alpha = 235 }
        canvas.drawRoundRect(bar, barHeight / 2f, barHeight / 2f, fill)
        // Corner resize dots.
        val dot = Paint(accentPaint).apply { alpha = 235 }
        val dotInset = inset + dp(2f)
        val r = dp(5.5f)
        canvas.drawCircle(dotInset, dotInset, r, dot)
        canvas.drawCircle(width - dotInset, dotInset, r, dot)
        canvas.drawCircle(dotInset, height - dotInset, r, dot)
        canvas.drawCircle(width - dotInset, height - dotInset, r, dot)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                handle = pickHandle(event.x, event.y)
                if (handle == Handle.NONE) return false
                startRawX = event.rawX
                startRawY = event.rawY
                val start = startRect()
                rectX = start.getOrElse(0) { 0 }
                rectY = start.getOrElse(1) { 0 }
                rectW = start.getOrElse(2) { width }
                rectH = start.getOrElse(3) { height }
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (handle == Handle.NONE) return true
                applyDrag(event.rawX - startRawX, event.rawY - startRawY)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (handle != Handle.NONE) onCommit()
                handle = Handle.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return true
    }

    private fun applyDrag(dx: Float, dy: Float) {
        val screenW = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val screenH = resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val screenHeightDp = (screenH / resources.displayMetrics.density).toInt().coerceAtLeast(240)
        val screenWidthDp = (screenW / resources.displayMetrics.density).toInt().coerceAtLeast(240)
        // Floating floor: 60% of the shared base height, same rule the docked minimum uses.
        val minHeight = (PhormiKeyboardMetrics.baseHeightDp(screenWidthDp, screenHeightDp) * PhormiKeyboardMetrics.MIN_HEIGHT_SCALE * resources.displayMetrics.density).toInt().coerceAtLeast(dp(180))
        val minWidth = dp(220)
        var x = rectX
        var y = rectY
        var w = rectW
        var h = rectH
        when (handle) {
            Handle.MOVE -> { x += dx.toInt(); y += dy.toInt() }
            Handle.LEFT -> { x += dx.toInt(); w -= dx.toInt() }
            Handle.RIGHT -> w += dx.toInt()
            Handle.TOP -> { y += dy.toInt(); h -= dy.toInt() }
            Handle.BOTTOM -> h += dy.toInt()
            Handle.TOP_LEFT -> { x += dx.toInt(); w -= dx.toInt(); y += dy.toInt(); h -= dy.toInt() }
            Handle.TOP_RIGHT -> { w += dx.toInt(); y += dy.toInt(); h -= dy.toInt() }
            Handle.BOTTOM_LEFT -> { x += dx.toInt(); w -= dx.toInt(); h += dy.toInt() }
            Handle.BOTTOM_RIGHT -> { w += dx.toInt(); h += dy.toInt() }
            Handle.NONE -> return
        }
        w = w.coerceIn(minWidth, screenW)
        h = h.coerceIn(minHeight, screenH)
        x = x.coerceIn(0, (screenW - w).coerceAtLeast(0))
        y = y.coerceIn(0, (screenH - h).coerceAtLeast(0))
        onLiveRect(x, y, w, h)
        invalidate()
    }

    private fun pickHandle(x: Float, y: Float): Handle {
        val inset = dp(4f)
        val left = inset
        val top = inset
        val right = width - inset
        val bottom = height - inset
        val cx = width / 2f
        val corner = cornerRadius
        if (distance(x, y, left, top) <= corner) return Handle.TOP_LEFT
        if (distance(x, y, right, top) <= corner) return Handle.TOP_RIGHT
        if (distance(x, y, left, bottom) <= corner) return Handle.BOTTOM_LEFT
        if (distance(x, y, right, bottom) <= corner) return Handle.BOTTOM_RIGHT
        // Grab bar on the top edge moves the panel.
        if (abs(x - cx) <= grabHalfWidth && y <= grabHalfHeight) return Handle.MOVE
        if (abs(x - cx) <= grabHalfWidth * 0.75f && abs(y - top) <= edgeZone) return Handle.TOP
        if (abs(y - bottom) <= edgeZone && abs(x - cx) <= grabHalfWidth * 1.6f) return Handle.BOTTOM
        if (abs(x - left) <= edgeZone) return Handle.LEFT
        if (abs(x - right) <= edgeZone) return Handle.RIGHT
        if (abs(y - top) <= edgeZone) return Handle.TOP
        if (abs(y - bottom) <= edgeZone) return Handle.BOTTOM
        return Handle.NONE
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float =
        kotlin.math.sqrt((x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2))

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)
}
