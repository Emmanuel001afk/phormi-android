from pathlib import Path
R = Path(__file__).resolve().parents[1]

def patch(rel, old, new, count=1):
    p = R / rel
    t = p.read_text()
    if old not in t:
        print('MISS', rel, old[:100].replace('\n', ' '))
        return
    p.write_text(t.replace(old, new, count))
    print('PATCH', rel)

# Refresh the real renderer after favorite changes; MainActivity's old legacy renderer is not used.
patch(
    'app/src/main/java/com/uong/phormi/MainActivity.kt',
    '        loadQuickAccessRows()',
    '        renderQuickAccessRows()',
)

# Keep the split WebView in the same named browsing environment as the active tab.
patch(
    'app/src/main/java/com/uong/phormi/MainActivity.kt',
    '            CookieManager.getInstance().setAcceptThirdPartyCookies(secondary, true)\n            configureWebView(secondary)',
    '            val profile = tabs.find { it.id == activeTabId }?.profileName ?: DEFAULT_PROFILE_NAME\n            PhormiEnvironmentManager.apply(secondary, profile)\n            CookieManager.getInstance().setAcceptThirdPartyCookies(secondary, true)\n            configureWebView(secondary)',
)

# Use the system input-method picker rather than the newer direct-switch API.
patch(
    'app/src/main/java/com/uong/phormi/PhormiKeyboardService.kt',
    '        key(utility, "⌨", 0.9f) { runCatching { switchToNextInputMethod(false) } }',
    '        key(utility, "⌨", 0.9f) { (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showInputMethodPicker() }',
)

# Quick Access is a speed-dial surface: permanent pins + Add + favorites + personal shortcuts +
# sites whose recorded visit count is greater than ten. It must not remove favorites from the feed.
renderer = '''package com.uong.phormi

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray

/** Renders the browser New Tab Quick Access layer. */
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
        val custom = readCustom(context).map { it.copy(kind = "Personal") }
        val favorites = PhormiFavorites.getAll(context).map { Item(it.title, it.url, "Favorite") }
        val frequent = PhormiVisitTracker.top(context, 20).map { Item(it.title, it.url, "Frequent", it.visits) }
        val add = Item("Add", "", "Add")
        val combined = (fixed + add + custom + favorites + frequent)
            .distinctBy { it.url.ifBlank { "add" } }
            .take(25)

        combined.chunked(5).forEach { chunk ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            chunk.forEach { item ->
                val chip = TextView(context).apply {
                    text = if (item.kind == "Add") "+\nAdd" else item.title.take(14)
                    contentDescription = when (item.kind) {
                        "Add" -> "Add a website to Quick Access"
                        "Frequent" -> "${item.title}, ${item.visits} visits"
                        else -> "${item.title}, ${item.kind}"
                    }
                    gravity = Gravity.CENTER
                    setTextColor(if (item.kind == "Add") accent else Color.WHITE)
                    textSize = 11f
                    setPadding(7, 8, 7, 8)
                    maxLines = 2
                    background = GradientDrawable().apply { setColor(Color.rgb(30, 41, 59)); setStroke(1, accent); cornerRadius = 18f }
                    setOnClickListener { if (item.kind == "Add") onAdd(context) else onOpen(item.url, item.kind == "Pinned") }
                }
                row.addView(chip, LinearLayout.LayoutParams(0, 48, 1f).apply { leftMargin = 3; rightMargin = 3; bottomMargin = 5 })
            }
            repeat(5 - chunk.size) { row.addView(android.view.View(context), LinearLayout.LayoutParams(0, 48, 1f)) }
            rows.addView(row, LinearLayout.LayoutParams(-1, 53))
        }
    }

    private fun onAdd(context: Context) {
        val input = android.widget.EditText(context).apply { hint = "Website name and URL"; singleLine = true; setPadding(16, 8, 16, 8) }
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle("Add to Quick Access")
            .setMessage("Enter a URL such as https://example.com")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Add") { _, _ ->
                val raw = input.text.toString().trim()
                val url = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
                val prefs = context.getSharedPreferences("phormi_tabs", Context.MODE_PRIVATE)
                val arr = runCatching { JSONArray(prefs.getString("custom_shortcuts", "[]") ?: "[]") }.getOrElse { JSONArray() }
                val name = runCatching { android.net.Uri.parse(url).host.orEmpty().removePrefix("www.") }.getOrDefault(url)
                arr.put(org.json.JSONObject().put("name", name.ifBlank { url }).put("url", url))
                prefs.edit().putString("custom_shortcuts", arr.toString()).apply()
            }
            .show()
    }

    private fun readCustom(context: Context): List<Item> {
        val prefs = context.getSharedPreferences("phormi_tabs", Context.MODE_PRIVATE)
        val arr = runCatching { JSONArray(prefs.getString("custom_shortcuts", "[]") ?: "[]") }.getOrElse { JSONArray() }
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val url = o.optString("url").trim()
                if (url.startsWith("http")) add(Item(o.optString("name", url).ifBlank { url }, url, "Personal"))
            }
        }
    }
}
'''
(R / 'app/src/main/java/com/uong/phormi/PhormiQuickAccessRenderer.kt').write_text(renderer)
print('PATCH renderer')
