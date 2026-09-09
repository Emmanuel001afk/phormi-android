package com.uong.phormi

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray

/** Canonical Quick Access renderer: pinned, manual shortcuts, Favorites, and Most Visited. */
object PhormiQuickAccessRenderer {
    private data class Item(val title: String, val url: String, val kind: String, val visits: Int = 0)
    private const val PREFS = "phormi_quick_access"
    private const val HIDDEN_FAVORITES = "hidden_favorites"
    private const val HIDDEN_VISITED = "hidden_visited"
    private const val HIDDEN_CUSTOM = "hidden_custom"

    fun render(context: Context, rows: LinearLayout, onOpen: (url: String, newTab: Boolean) -> Unit) {
        rows.removeAllViews()
        val accent = Color.rgb(56, 189, 248)
        val fixed = listOf(
            Item("Google", "https://www.google.com", "Pinned"),
            Item("Bing", "https://www.bing.com", "Pinned"),
            Item("YouTube", "https://www.youtube.com", "Pinned"),
            Item("GitHub", "https://github.com", "Pinned")
        )
        val hiddenFavorites = hidden(context, HIDDEN_FAVORITES)
        val hiddenVisited = hidden(context, HIDDEN_VISITED)
        val hiddenCustom = hidden(context, HIDDEN_CUSTOM)
        val favorites = PhormiFavorites.getAll(context)
            .filterNot { hiddenFavorites.contains(it.url) }
            .map { Item(it.title, it.url, "Favorite") }
        val custom = readCustom(context)
            .filterNot { hiddenCustom.contains(it.url) }
        val mostVisited = PhormiVisitTracker.top(context, 10)
            .filterNot { hiddenVisited.contains(it.url) || hiddenVisited.contains(it.host) }
            .map { Item(it.title, it.url, "Most visited", it.visits) }
        val combined = (fixed + custom + favorites + mostVisited).distinctBy { it.url }.take(12)

        if (combined.isEmpty()) {
            rows.addView(TextView(context).apply {
                text = "Quick Access will fill with pinned sites, Favorites, shortcuts, and frequently visited sites."
                setTextColor(Color.rgb(148, 163, 184)); textSize = 12f; setPadding(8, 10, 8, 10)
            })
            return
        }
        combined.chunked(4).forEach { chunk ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            chunk.forEach { item ->
                val chip = TextView(context).apply {
                    text = item.title.take(16)
                    contentDescription = buildString {
                        append(item.title)
                        append(" · ${item.kind}")
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
                        when (item.kind) {
                            "Favorite" -> hide(context, HIDDEN_FAVORITES, item.url)
                            "Most visited" -> hide(context, HIDDEN_VISITED, item.url)
                            "Pinned" -> return@setOnLongClickListener false
                            else -> hide(context, HIDDEN_CUSTOM, item.url)
                        }
                        render(context, rows, onOpen)
                        true
                    }
                }
                row.addView(chip, LinearLayout.LayoutParams(0, 52, 1f).apply { leftMargin = 3; rightMargin = 3; bottomMargin = 5 })
            }
            rows.addView(row, LinearLayout.LayoutParams(-1, 57))
        }
    }

    private fun readCustom(context: Context): List<Item> {
        val prefs = context.getSharedPreferences("phormi_tabs", Context.MODE_PRIVATE)
        val arr = runCatching { JSONArray(prefs.getString("custom_shortcuts", "[]") ?: "[]") }.getOrElse { JSONArray() }
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val url = o.optString("url").trim()
                if (url.startsWith("http")) add(Item(o.optString("name", url).ifBlank { url }, url, "Shortcut"))
            }
        }
    }

    private fun hidden(context: Context, key: String): MutableSet<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(key, emptySet()).orEmpty().toMutableSet()

    private fun hide(context: Context, key: String, url: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(key, emptySet()).orEmpty().toMutableSet().apply { add(url) }
        prefs.edit().putStringSet(key, set).apply()
    }
}
