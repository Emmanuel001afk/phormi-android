package com.uong.phormi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.widget.LinearLayout
import kotlin.math.hypot

/** Intercepts only a real drag beginning on a letter. Taps/long-press accents remain native. */
class PhormiGlideLayout(context: Context) : LinearLayout(context) {
    data class Key(val letter: Char, val x: Float, val y: Float, val width: Float, val height: Float)
    var enabledForEditor: () -> Boolean = { false }
    var keyGeometry: () -> List<Key> = { emptyList() }
    var onGlide: (List<PhormiGlideDecoder.Point>, Map<Char, PhormiGlideDecoder.Point>) -> Unit = { _, _ -> }
    private val points = mutableListOf<PhormiGlideDecoder.Point>()
    private var keys = emptyList<Key>()
    private var tracking = false
    private var gliding = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PhormiUiColors.accent(context); strokeWidth = 4 * resources.displayMetrics.density; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    init { orientation = VERTICAL }
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            reset()
            if (!enabledForEditor()) return false
            keys = keyGeometry()
            tracking = keys.any { kotlin.math.abs(event.x - it.x) < it.width / 2 && kotlin.math.abs(event.y - it.y) < it.height / 2 }
            if (tracking) add(event)
        } else if (tracking && event.actionMasked == MotionEvent.ACTION_MOVE) {
            if (event.pointerCount != 1 || (!gliding && event.eventTime - event.downTime > 350)) { reset(); return false }
            add(event)
            val start = points.first()
            if (hypot(event.x - start.x, event.y - start.y) > 22 * resources.displayMetrics.density) {
                gliding = true
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
        } else if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) reset()
        return false
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!gliding) return super.onTouchEvent(event)
        if (event.pointerCount != 1 || event.actionMasked == MotionEvent.ACTION_CANCEL) { reset(); return true }
        add(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val scale = keys.map { it.width }.average().toFloat().coerceAtLeast(1f)
            val path = points.map { PhormiGlideDecoder.Point(it.x / scale, it.y / scale) }
            val centers = keys.associate { it.letter to PhormiGlideDecoder.Point(it.x / scale, it.y / scale) }
            reset()
            if (enabledForEditor()) onGlide(path, centers)
        }
        return true
    }
    private fun add(event: MotionEvent) {
        // Bound a gesture's memory even if a finger remains down indefinitely.
        if (points.size >= 256) { val reduced = points.filterIndexed { i, _ -> i % 2 == 0 }; points.clear(); points.addAll(reduced) }
        points.add(PhormiGlideDecoder.Point(event.x, event.y)); invalidate()
    }
    private fun reset() { points.clear(); tracking = false; gliding = false; parent?.requestDisallowInterceptTouchEvent(false); invalidate() }
    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (gliding && points.size > 1) {
            val path = Path(); path.moveTo(points[0].x, points[0].y)
            points.drop(1).forEach { path.lineTo(it.x, it.y) }
            canvas.drawPath(path, paint)
        }
    }
}
