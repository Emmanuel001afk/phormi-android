package com.uong.phormi

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Pure keyboard geometry/lettering rules. Keeps keys readable when width/height scale changes. */
object PhormiKeyboardMetrics {
    const val MIN_HEIGHT_SCALE = 0.60f
    const val MAX_HEIGHT_SCALE = 1.40f
    const val MIN_WIDTH_SCALE = 0.55f
    const val MAX_WIDTH_SCALE = 1.00f

    fun baseHeightDp(screenWidthDp: Int, screenHeightDp: Int): Int = when {
        screenHeightDp < 500 -> 224
        screenWidthDp >= 600 -> 320
        else -> 296
    }

    fun windowHeightDp(screenWidthDp: Int, screenHeightDp: Int, heightScale: Float, floating: Boolean): Int {
        val scale = heightScale.coerceIn(MIN_HEIGHT_SCALE, MAX_HEIGHT_SCALE)
        val base = baseHeightDp(screenWidthDp, screenHeightDp)
        val raw = (base * scale).roundToInt() + if (floating) 30 else 0
        val cap = max(240, (screenHeightDp * 0.82f).roundToInt())
        // Keep enough room for three readable key rows after the suggestion/toolbar strips.
        return raw.coerceIn(240, cap)
    }

    fun keyMarginHDp(rowKeys: Int): Int = if (rowKeys >= 11) 1 else 2
    fun homeInsetDp(rowKeys: Int, keyWidthDp: Float): Float =
        if (rowKeys <= 1) 0f else (keyWidthDp * 0.5f).coerceIn(6f, 22f)

    fun keyWidthDp(contentWidthDp: Float, rowKeys: Int): Float {
        val keys = rowKeys.coerceAtLeast(1)
        val margin = keyMarginHDp(keys) * 2 * keys
        return ((contentWidthDp - margin) / keys).coerceAtLeast(12f)
    }

    fun keyHeightDp(windowHeightDp: Int, heightScale: Float, rowCount: Int = 3): Float {
        val scale = heightScale.coerceIn(MIN_HEIGHT_SCALE, MAX_HEIGHT_SCALE)
        val strips = (34 + 38 + 48) * scale
        val padding = 8 * scale
        return ((windowHeightDp - strips - padding) / rowCount.coerceAtLeast(1)).coerceAtLeast(32f)
    }

    /** Lettering follows the smaller of key width/height, then clamps to a readable range. */
    fun keyTextSp(label: String, keyWidthDp: Float, keyHeightDp: Float, fontScale: Float): Float {
        val codePoints = label.codePointCount(0, label.length)
        val special = label == "Space" || label == "⌫" || label in setOf("?123", "ABC", "Symbols", "Enter", "Go", "Search", "Send", "Next", "Done")
        val base = when {
            codePoints == 1 && !special -> min(keyHeightDp * 0.42f, keyWidthDp * 0.58f).coerceIn(15f, 22f)
            label == "Space" -> min(keyHeightDp * 0.30f, 13f).coerceIn(11f, 13f)
            label.length > 5 -> min(keyHeightDp * 0.26f, 11f).coerceIn(9f, 11f)
            label.length > 3 -> min(keyHeightDp * 0.28f, 12f).coerceIn(10f, 12f)
            else -> min(keyHeightDp * 0.32f, 14f).coerceIn(11f, 14f)
        }
        val accessibility = fontScale.coerceIn(0.9f, 1.25f)
        return (base * accessibility).coerceIn(9f, 24f)
    }
}
