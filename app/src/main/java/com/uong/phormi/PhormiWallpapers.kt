package com.uong.phormi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils

/** Resolution-independent, bundled artwork: no network, downloads or storage permission. */
internal object PhormiWallpapers {
    data class Style(val id: String, val label: String)
    val styles = listOf(Style("none", "Plain"), Style("aurora", "Aurora"), Style("tidal", "Tidal"),
        Style("dunes", "Dunes"), Style("bloom", "Bloom"), Style("orbit", "Orbit"))
    fun selected(context: Context, keyboard: Boolean = false): String =
        context.getSharedPreferences("phormi_appearance", Context.MODE_PRIVATE)
            .getString(if (keyboard) "keyboard_wallpaper" else "browser_wallpaper", "aurora") ?: "aurora"
    fun select(context: Context, id: String, keyboard: Boolean = false) {
        if (styles.none { it.id == id }) return
        context.getSharedPreferences("phormi_appearance", Context.MODE_PRIVATE).edit()
            .putString(if (keyboard) "keyboard_wallpaper" else "browser_wallpaper", id).apply()
    }
    fun drawable(id: String, dark: Boolean): Drawable? = if (id == "none") null else Artwork(id, dark)

    private class Artwork(private val style: String, private val dark: Boolean) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var opacity = 255
        override fun draw(canvas: Canvas) {
            val w = bounds.width().toFloat(); val h = bounds.height().toFloat()
            if (w <= 0 || h <= 0) return
            val colors = when (style) {
                "tidal" -> listOf("#071F31", "#126B77", "#75D8C3")
                "dunes" -> listOf("#392B46", "#B77869", "#F5C997")
                "bloom" -> listOf("#221B41", "#86518D", "#EEA7BF")
                "orbit" -> listOf("#101A35", "#504A94", "#82B8E5")
                else -> listOf("#121B38", "#574C91", "#56C7CA")
            }.map { color -> Color.parseColor(color).let { if (dark) it else ColorUtils.blendARGB(it, Color.WHITE, 0.68f) } }
            val checkpoint = canvas.save()
            canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
            paint.style = Paint.Style.FILL; paint.alpha = opacity
            paint.shader = LinearGradient(0f, 0f, w, h, intArrayOf(colors[0], colors[1]), null, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, paint)
            if (style == "orbit") {
                paint.shader = LinearGradient(w * .2f, h * .3f, w * .8f, h * .8f, colors[2], colors[1], Shader.TileMode.CLAMP)
                canvas.drawCircle(w * .72f, h * .58f, minOf(w, h) * .28f, paint)
                paint.shader = null; paint.color = colors[2]; paint.alpha = opacity * 2 / 5
                paint.style = Paint.Style.STROKE; paint.strokeWidth = maxOf(1f, w / 450)
                canvas.save(); canvas.rotate(-28f, w * .72f, h * .58f)
                repeat(3) { i ->
                    val radius = minOf(w, h) * (.34f + i * .065f)
                    canvas.drawOval(RectF(w * .72f - radius, h * .58f - radius * .38f, w * .72f + radius, h * .58f + radius * .38f), paint)
                }
                canvas.restore()
            } else if (style == "bloom") {
                paint.shader = null
                repeat(7) { i ->
                    canvas.save(); canvas.rotate(i * 51.4f, w * .68f, h * .67f)
                    paint.color = ColorUtils.blendARGB(colors[1], colors[2], i / 7f); paint.alpha = opacity * 3 / 5
                    canvas.drawOval(RectF(w * .40f, h * .33f, w * .84f, h * .78f), paint); canvas.restore()
                }
            } else {
                repeat(5) { i ->
                    val offset = i * .105f
                    val path = Path().apply {
                        moveTo(-w * .15f, h * (.45f + offset))
                        cubicTo(w * .30f, h * (.10f + offset), w * .45f, h * (1.10f - offset), w * 1.16f, h * (.25f + offset))
                        lineTo(w * 1.16f, h * 1.2f); lineTo(-w * .15f, h * 1.2f); close()
                    }
                    paint.shader = LinearGradient(0f, h * .2f, w, h,
                        ColorUtils.blendARGB(colors[1], colors[2], i / 5f), colors[0], Shader.TileMode.CLAMP)
                    paint.alpha = opacity * (35 + i * 8) / 100
                    canvas.drawPath(path, paint)
                    paint.shader = null; paint.color = colors[2]; paint.style = Paint.Style.STROKE
                    paint.strokeWidth = maxOf(1f, w / 550); paint.alpha = opacity * 15 / 100
                    canvas.drawPath(path, paint); paint.style = Paint.Style.FILL
                }
            }
            paint.shader = null; paint.alpha = 255; paint.style = Paint.Style.FILL
            canvas.restoreToCount(checkpoint)
        }
        override fun setAlpha(alpha: Int) { opacity = alpha; invalidateSelf() }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
        @Deprecated("Deprecated in Android") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
