package com.uong.phormi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/** API-free local emoji composer. It renders one fused reaction from the strongest context concepts. */
object PhormiAiEmojiEngine {
    data class Style(
        val mood: String,
        val accessory: Int,
        val skin: Int,
        val background: Int,
        val fire: Boolean,
        val money: Boolean,
        val sun: Boolean,
        val celebration: Boolean,
        val love: Boolean
    )

    fun generate(context: Context, prompt: String, variant: Int): File {
        val style = styleFor(prompt, variant)
        val size = 512
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val cx = size / 2f
        val cy = size / 2f
        val r = 205f

        paint.color = style.background
        canvas.drawCircle(cx, cy, 238f, paint)

        // Secondary concepts are embedded into the same face/head. Nothing is rendered
        // as a second emoji, sticker, coin, or separate object.
        paint.color = style.skin
        canvas.drawCircle(cx, cy, r, paint)

        paint.color = 0xFF111827.toInt()
        when (style.mood) {
            "sad" -> {
                canvas.drawCircle(cx - 72, cy - 55, 22f, paint)
                canvas.drawCircle(cx + 72, cy - 55, 22f, paint)
                canvas.drawArc(RectF(cx - 75, cy + 55, cx + 75, cy + 145), 200f, 140f, false, paint)
                paint.color = 0xFF38BDF8.toInt()
                canvas.drawCircle(cx - 88, cy + 10, 10f, paint)
                canvas.drawCircle(cx + 88, cy + 10, 10f, paint)
            }
            "angry" -> {
                canvas.drawLine(cx - 98, cy - 80, cx - 40, cy - 50, paint)
                canvas.drawLine(cx + 98, cy - 80, cx + 40, cy - 50, paint)
                canvas.drawCircle(cx - 70, cy - 35, 18f, paint)
                canvas.drawCircle(cx + 70, cy - 35, 18f, paint)
                canvas.drawLine(cx - 72, cy + 92, cx + 72, cy + 92, paint)
            }
            "love" -> {
                heart(canvas, cx - 72, cy - 45, 26f, paint)
                heart(canvas, cx + 72, cy - 45, 26f, paint)
                heart(canvas, cx, cy + 82, 45f, paint)
            }
            "laugh" -> {
                canvas.drawArc(RectF(cx - 90, cy + 5, cx + 90, cy + 145), 0f, 180f, true, paint)
                canvas.drawCircle(cx - 70, cy - 45, 20f, paint)
                canvas.drawCircle(cx + 70, cy - 45, 20f, paint)
            }
            "cool" -> {
                canvas.drawRoundRect(RectF(cx - 120, cy - 80, cx - 8, cy - 18), 18f, 18f, paint)
                canvas.drawRoundRect(RectF(cx + 8, cy - 80, cx + 120, cy - 18), 18f, 18f, paint)
                canvas.drawRect(cx - 8, cy - 60, cx + 8, cy - 45, paint)
                canvas.drawArc(RectF(cx - 70, cy + 20, cx + 70, cy + 125), 0f, 180f, false, paint)
            }
            "wow" -> {
                canvas.drawCircle(cx - 72, cy - 55, 28f, paint)
                canvas.drawCircle(cx + 72, cy - 55, 28f, paint)
                canvas.drawCircle(cx, cy + 72, 38f, paint)
            }
            "tired" -> {
                canvas.drawLine(cx - 105, cy - 45, cx - 35, cy - 45, paint)
                canvas.drawLine(cx + 35, cy - 45, cx + 105, cy - 45, paint)
                canvas.drawArc(RectF(cx - 55, cy + 45, cx + 55, cy + 100), 0f, 180f, false, paint)
            }
            else -> {
                canvas.drawCircle(cx - 72, cy - 55, 20f, paint)
                canvas.drawCircle(cx + 72, cy - 55, 20f, paint)
                canvas.drawArc(RectF(cx - 72, cy + 15, cx + 72, cy + 120), 10f, 160f, false, paint)
            }
        }

        // Integrate secondary concepts into the same facial silhouette.
        if (style.love && style.mood != "love") {
            heart(canvas, cx + 128, cy + 82, 20f, paint)
        }
        if (style.money) drawCoin(canvas, paint, cx - 128, cy + 82)
        if (style.celebration) drawSpark(canvas, paint, cx + 118, cy - 108, 15f)
        if (style.fire) drawSmallFlame(canvas, paint, cx, cy - 208, 30f)

        drawAccessory(canvas, paint, style.accessory, cx, cy)
        val dir = File(context.filesDir, "phormi_stickers").apply { mkdirs() }
        val file = File(dir, "aiemoji_${System.currentTimeMillis()}_${variant}.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }

    private fun styleFor(prompt: String, variant: Int): Style {
        val p = prompt.lowercase(Locale.US)
        val mood = when {
            listOf("super sad", "heartbroken", "devastated", "crying").any(p::contains) -> "sad"
            listOf("sad", "sorry", "cry", "hurt").any(p::contains) -> "sad"
            listOf("furious", "angry", "mad", "hate").any(p::contains) -> "angry"
            listOf("love", "heart", "romance", "kiss", "crush").any(p::contains) -> "love"
            listOf("laugh", "funny", "lol", "joke", "hilarious").any(p::contains) -> "laugh"
            listOf("cool", "style", "stylish").any(p::contains) -> "cool"
            listOf("wow", "shock", "surprise", "amazing", "excited", "exciting").any(p::contains) -> "wow"
            listOf("tired", "sleep", "sleepy", "exhausted").any(p::contains) -> "tired"
            else -> "happy"
        }
        val skins = intArrayOf(0xFFFFD7B5.toInt(), 0xFFF2C29B.toInt(), 0xFFD99A6C.toInt(), 0xFF8D5524.toInt())
        val backgrounds = intArrayOf(0xFFFFF7ED.toInt(), 0xFFE0F2FE.toInt(), 0xFFFCE7F3.toInt(), 0xFFDCFCE7.toInt())
        val fire = listOf("fire", "flame", "lit", "hot", "energy", "super excited", "on fire").any(p::contains)
        val money = listOf("money", "cash", "rich", "paid", "salary", "profit", "naira", "dollar", "wealth").any(p::contains)
        val sun = listOf("sun", "sunny", "bright", "summer", "sunshine").any(p::contains)
        val celebration = listOf("celebrate", "celebration", "party", "birthday", "congrat", "win", "winning", "fireworks").any(p::contains)
        val love = listOf("love", "heart", "romance", "kiss", "crush").any(p::contains)
        return Style(mood, variant % 4, skins[variant % skins.size], backgrounds[variant % backgrounds.size], fire, money, sun, celebration, love)
    }

    private fun drawFlame(canvas: Canvas, paint: Paint, x: Float, y: Float, s: Float) {
        val path = Path()
        path.moveTo(x, y + s)
        path.cubicTo(x - s * .9f, y + s * .45f, x - s * .45f, y - s * .25f, x, y - s)
        path.cubicTo(x + s * .1f, y - s * .3f, x + s * .7f, y + s * .05f, x + s * .45f, y + s)
        path.close()
        paint.color = 0xFFF97316.toInt()
        canvas.drawPath(path, paint)
        paint.color = 0xFFFDE68A.toInt()
        canvas.drawCircle(x, y + s * .35f, s * .22f, paint)
    }

    private fun drawSmallFlame(canvas: Canvas, paint: Paint, x: Float, y: Float, s: Float) = drawFlame(canvas, paint, x, y, s)

    private fun drawCoin(canvas: Canvas, paint: Paint, x: Float, y: Float) {
        paint.color = 0xFFF59E0B.toInt(); paint.style = Paint.Style.FILL; canvas.drawCircle(x, y, 28f, paint)
        paint.color = 0xFF78350F.toInt(); paint.style = Paint.Style.STROKE; paint.strokeWidth = 6f; canvas.drawCircle(x, y, 21f, paint)
        paint.style = Paint.Style.FILL; paint.strokeWidth = 1f
        canvas.drawRect(x - 3f, y - 13f, x + 3f, y + 13f, paint)
        canvas.drawArc(RectF(x - 11, y - 10, x + 11, y + 10), 90f, 180f, false, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawSpark(canvas: Canvas, paint: Paint, x: Float, y: Float, s: Float) {
        paint.color = 0xFFFDE047.toInt(); paint.style = Paint.Style.FILL
        val path = Path(); path.moveTo(x, y - s); path.lineTo(x + s * .24f, y - s * .24f); path.lineTo(x + s, y); path.lineTo(x + s * .24f, y + s * .24f); path.lineTo(x, y + s); path.lineTo(x - s * .24f, y + s * .24f); path.lineTo(x - s, y); path.lineTo(x - s * .24f, y - s * .24f); path.close(); canvas.drawPath(path, paint)
    }

    private fun heart(canvas: Canvas, x: Float, y: Float, s: Float, paint: Paint) {
        val path = Path()
        path.moveTo(x, y + s)
        path.cubicTo(x - s * 1.5f, y - s * .2f, x - s, y - s, x, y - s * .2f)
        path.cubicTo(x + s, y - s, x + s * 1.5f, y - s * .2f, x, y + s)
        paint.color = 0xFFEF4444.toInt()
        canvas.drawPath(path, paint)
    }

    private fun drawAccessory(canvas: Canvas, paint: Paint, accessory: Int, cx: Float, cy: Float) {
        paint.strokeWidth = 14f
        paint.style = Paint.Style.STROKE
        paint.color = 0xFF111827.toInt()
        when (accessory) {
            0 -> canvas.drawArc(RectF(cx - 150, cy - 155, cx + 150, cy + 20), 200f, 140f, false, paint)
            1 -> canvas.drawLine(cx - 165, cy + 135, cx - 205, cy + 180, paint)
            2 -> {
                canvas.drawLine(cx - 155, cy - 155, cx - 190, cy - 195, paint)
                canvas.drawLine(cx + 155, cy - 155, cx + 190, cy - 195, paint)
            }
            3 -> {
                paint.style = Paint.Style.FILL
                paint.color = 0xFFF59E0B.toInt()
                for (i in 0 until 5) {
                    val a = i * 72f
                    val x = cx + kotlin.math.cos(Math.toRadians(a.toDouble())).toFloat() * 230f
                    val y = cy + kotlin.math.sin(Math.toRadians(a.toDouble())).toFloat() * 230f
                    canvas.drawCircle(x, y, 13f, paint)
                }
            }
        }
        paint.style = Paint.Style.FILL
    }
}