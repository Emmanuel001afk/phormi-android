from pathlib import Path

R = Path(__file__).resolve().parents[1]
p = R / 'app/src/main/java/com/uong/phormi/PhormiQuickAccessRenderer.kt'
p.write_text(r'''package com.uong.phormi

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/** Browser-style Quick Access: fixed services, Add, personal shortcuts, favorites and >10 visits. */
object PhormiQuickAccessRenderer {
    private data class Item(val title: String, val url: String, val kind: String, val visits: Int = 0)

    fun render(context: Context, rows: LinearLayout, onOpen: (url: String, newTab: Boolean) -> Unit) {
        rows.removeAllViews()
        val accent = Color.rgb(56, 189, 248)
        val fixed = listOf(
            Item("Google", "https://www.google.com", "Pinned"),
            Item("Bing", "https://www.bing.com", "Pinned"),
            Item("YouTube", "https://www.youtube.com", "Pinned"),
            Item("GitHub", "https://github.com", "Pinned")
        )
        val add = Item("+ Add", "", "Add")
        val custom = readCustom(context)
        val favorites = PhormiFavorites.getAll(context)
            .filter { PhormiFavorites.contains(context, it.url) }
            .map { Item(it.title, it.url, "Favorite") }
        val frequent = PhormiVisitTracker.top(context, 20)
            .filter { it.visits > 10 }
            .take(10)
            .map { Item(it.title, it.url, "Frequent", it.visits) }
        val combined = (fixed + add + custom + favorites + frequent)
            .distinctBy { if (it.kind == "Add") "__add__" else it.url }
            .take(20)

        val chunks = combined.chunked(5)
        if (chunks.isEmpty()) {
            rows.addView(TextView(context).apply {
                text = "Quick Access"
                setTextColor(Color.rgb(148, 163, 184))
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(8, 12, 8, 12)
            })
            return
        }
        chunks.forEach { chunk ->
            val line = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            chunk.forEach { item ->
                val chip = TextView(context).apply {
                    text = item.title.take(16)
                    contentDescription = buildString {
                        append(item.title)
                        if (item.kind == "Frequent") append(" · ${item.visits} visits")
                    }
                    gravity = Gravity.CENTER
                    setTextColor(if (item.kind == "Add") accent else Color.WHITE)
                    textSize = 11f
                    setPadding(7, 8, 7, 8)
                    maxLines = 2
                    background = GradientDrawable().apply {
                        setColor(Color.rgb(30, 41, 59))
                        setStroke(1, accent)
                        cornerRadius = 18f
                    }
                    setOnClickListener {
                        if (item.kind == "Add") {
                            showAddShortcutDialog(context, rows, onOpen)
                        } else {
                            onOpen(item.url, item.kind == "Pinned")
                        }
                    }
                    setOnLongClickListener {
                        if (item.kind == "Frequent") {
                            PhormiVisitTracker.clear(context)
                            render(context, rows, onOpen)
                            true
                        } else {
                            false
                        }
                    }
                }
                line.addView(chip, LinearLayout.LayoutParams(0, 48, 1f).apply {
                    leftMargin = 3
                    rightMargin = 3
                    bottomMargin = 5
                })
            }
            rows.addView(line, LinearLayout.LayoutParams(-1, 53))
        }
    }

    private fun showAddShortcutDialog(context: Context, rows: LinearLayout, onOpen: (String, Boolean) -> Unit) {
        val activity = context as? Activity ?: return
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 0)
        }
        val name = EditText(activity).apply {
            hint = "Name"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val url = EditText(activity).apply {
            hint = "https://example.com"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        box.addView(name)
        box.addView(url)
        android.app.AlertDialog.Builder(activity)
            .setTitle("Add Quick Access")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Add") { _, _ ->
                val u = url.text.toString().trim()
                val n = name.text.toString().trim().ifBlank { u }
                if (n.isBlank() || !(u.startsWith("https://") || u.startsWith("http://"))) return@setPositiveButton
                val prefs = activity.getSharedPreferences("phormi_tabs", Context.MODE_PRIVATE)
                val arr = runCatching { JSONArray(prefs.getString("custom_shortcuts", "[]") ?: "[]") }
                    .getOrElse { JSONArray() }
                arr.put(JSONObject().put("name", n).put("url", u))
                prefs.edit().putString("custom_shortcuts", arr.toString()).apply()
                render(activity, rows, onOpen)
            }
            .show()
    }

    private fun readCustom(context: Context): List<Item> {
        val prefs = context.getSharedPreferences("phormi_tabs", Context.MODE_PRIVATE)
        val arr = runCatching { JSONArray(prefs.getString("custom_shortcuts", "[]") ?: "[]") }
            .getOrElse { JSONArray() }
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val url = o.optString("url").trim()
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    add(Item(o.optString("name", url).ifBlank { url }, url, "Personal"))
                }
            }
        }
    }
}
''')
print('Round 10 Quick Access renderer finalized')
