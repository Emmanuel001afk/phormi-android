package com.uong.phormi

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray

/** Renders the browser New Tab Quick Access layer without mixing in Favorites. */
object PhormiQuickAccessRenderer {
    private data class Item(val title: String, val url: String, val kind: String, val visits: Int = 0)

    fun render(context: Context, rows: LinearLayout, onOpen: (url: String, newTab: Boolean) -> Unit) {
        rows.removeAllViews()
        val accent = if (context.getSharedPreferences("phormi_tabs", Context.MODE_PRIVATE).getBoolean("daily_accent", true)) Color.rgb(56, 189, 248) else Color.rgb(56, 189, 248)
        val fixed = listOf(
            Item("Google", "https://www.google.com", "Pinned"),
            Item("Bing", "https://www.bing.com", "Pinned"),
            Item("YouTube", "https://www.youtube.com", "Pinned"),
            Item("GitHub", "https://github.com", "Pinned")
        )
        val custom = readCustom(context).filterNot { PhormiFavorites.contains(context, it.url) }
        val pinned = (fixed + custom).distinctBy { it.url }.take(10)
        val mostVisited = PhormiVisitTracker.top(context, 10).map { Item(it.title, it.url, "Most visited", it.visits) }
        val combined = (pinned + mostVisited).distinctBy { it.url }.take(10)

        if (combined.isEmpty()) {
            rows.addView(TextView(context).apply {
                text = "Quick Access will fill with pinned sites and frequently visited sites."
                setTextColor(Color.rgb(148, 163, 184)); textSize = 12f; setPadding(8, 10, 8, 10)
            })
            return
        }
        combined.chunked(5).forEach { chunk ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            chunk.forEach { item ->
                val chip = TextView(context).apply {
                    text = item.title.take(16)
                    contentDescription = buildString {
                        append(item.title)
                        if (item.kind == "Most visited") append(" · ${item.visits} visits")
                    }
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    textSize = 11f
                    setPadding(7, 8, 7, 8)
                    maxLines = 2
                    background = GradientDrawable().apply { setColor(Color.rgb(30, 41, 59)); setStroke(1, accent); cornerRadius = 18f }
                    setOnClickListener { onOpen(item.url, item.kind == "Pinned") }
                    setOnLongClickListener {
                        if (item.kind == "Most visited") {
                            PhormiVisitTracker.clear(context)
                            render(context, rows, onOpen)
                            true
                        } else false
                    }
                }
                row.addView(chip, LinearLayout.LayoutParams(0, 48, 1f).apply { leftMargin = 3; rightMargin = 3; bottomMargin = 5 })
            }
            rows.addView(row, LinearLayout.LayoutParams(-1, 53))
        }
    }

    private fun readCustom(context: Context): List<Item> {
        val prefs = context.getSharedPreferences("phormi_tabs", Context.MODE_PRIVATE)
        val arr = runCatching { JSONArray(prefs.getString("custom_shortcuts", "[]") ?: "[]") }.getOrElse { JSONArray() }
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val url = o.optString("url").trim()
                if (url.startsWith("http")) add(Item(o.optString("name", url).ifBlank { url }, url, "Pinned"))
            }
        }
    }
}
