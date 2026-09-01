package com.uong.phormi

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/** Local visit history stored only on this phone. Ghost tabs are not recorded. */
class HistoryActivity : AppCompatActivity() {

    data class Entry(val title: String, val url: String, val visitedAt: Long)

    private val items = mutableListOf<Entry>()
    private lateinit var adapter: BaseAdapter
    private lateinit var empty: TextView
    private lateinit var search: EditText

    companion object {
        private const val PREFS = "phormi_history"
        private const val KEY = "items"
        private const val MAX = 300

        fun record(context: android.content.Context, title: String, url: String) {
            if (url.isBlank() || url == "about:blank") return
            val prefs = context.getSharedPreferences(PREFS, MODE_PRIVATE)
            val arr = runCatching {
                JSONArray(prefs.getString(KEY, "[]"))
            }.getOrElse { JSONArray() }

            if (
                arr.length() > 0 &&
                arr.optJSONObject(arr.length() - 1)?.optString("url") == url
            ) {
                return
            }

            arr.put(
                JSONObject()
                    .put("title", title.ifBlank { url })
                    .put("url", url)
                    .put("visitedAt", System.currentTimeMillis())
            )

            while (arr.length() > MAX) arr.remove(0)
            prefs.edit().putString(KEY, arr.toString()).apply()
        }

        fun clearAll(context: android.content.Context) {
            context.getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .remove(KEY)
                .apply()
        }

        fun removeUrl(context: android.content.Context, url: String) {
            val prefs = context.getSharedPreferences(PREFS, MODE_PRIVATE)
            val arr = runCatching { JSONArray(prefs.getString(KEY, "[]")) }.getOrElse { JSONArray() }
            val kept = JSONArray()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                if (item.optString("url", "") != url) kept.put(item)
            }
            prefs.edit().putString(KEY, kept.toString()).apply()
        }

        /**
         * Returns the most-used recent sites for the browser home page.
         * Frequency is the primary signal; most-recent visit breaks ties.
         */
        fun getTopSites(
            context: android.content.Context,
            limit: Int = 4
        ): List<Entry> {
            if (limit <= 0) return emptyList()

            val prefs = context.getSharedPreferences(PREFS, MODE_PRIVATE)
            val arr = runCatching {
                JSONArray(prefs.getString(KEY, "[]"))
            }.getOrElse { JSONArray() }

            val entries = mutableListOf<Entry>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val url = o.optString("url", "").trim()
                if (url.isBlank()) continue
                entries += Entry(
                    title = o.optString("title", url).ifBlank { url },
                    url = url,
                    visitedAt = o.optLong("visitedAt", 0L)
                )
            }

            val grouped = linkedMapOf<String, MutableList<Entry>>()
            entries.forEach { entry ->
                val key = canonical(entry.url)
                grouped.getOrPut(key) { mutableListOf() }.add(entry)
            }

            return grouped.values
                .map { group ->
                    group.maxByOrNull { it.visitedAt } ?: group.first()
                }
                .sortedWith(
                    compareByDescending<Entry> { entry ->
                        grouped[canonical(entry.url)]?.size ?: 0
                    }.thenByDescending { it.visitedAt }
                )
                .take(limit)
        }

        private fun canonical(url: String): String {
            return try {
                java.net.URL(url).run {
                    "${host.lowercase().removePrefix("www.")}${path.ifBlank { "/" }}"
                        .lowercase()
                }
            } catch (_: Exception) {
                url.lowercase().substringBefore("#")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        empty = findViewById(R.id.history_empty)
        search = findViewById(R.id.history_search)
        findViewById<TextView>(R.id.history_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.history_clear).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear history?")
                .setMessage("This removes all saved visits on this phone.")
                .setPositiveButton("Clear") { _, _ ->
                    clearAll(this)
                    items.clear()
                    adapter.notifyDataSetChanged()
                    empty.visibility = View.VISIBLE
                    Toast.makeText(this, "History cleared", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        adapter = object : BaseAdapter() {
            override fun getCount() = filtered().size
            override fun getItem(position: Int) = filtered()[position]
            override fun getItemId(position: Int) = position.toLong()

            override fun getView(
                position: Int,
                convertView: View?,
                parent: ViewGroup?
            ): View {
                val v = convertView ?: layoutInflater.inflate(
                    R.layout.item_history,
                    parent,
                    false
                )
                val e = filtered()[position]
                v.findViewById<TextView>(R.id.history_title).text = e.title
                v.findViewById<TextView>(R.id.history_url).text = e.url
                v.findViewById<TextView>(R.id.history_time).text =
                    DateFormat.getDateTimeInstance(
                        DateFormat.SHORT,
                        DateFormat.SHORT
                    ).format(Date(e.visitedAt))

                v.setOnClickListener {
                    setResult(RESULT_OK, Intent().putExtra("open_url", e.url))
                    finish()
                }
                return v
            }
        }

        findViewById<ListView>(R.id.history_list).adapter = adapter

        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(
                s: CharSequence?,
                start: Int,
                count: Int,
                after: Int
            ) = Unit

            override fun onTextChanged(
                s: CharSequence?,
                start: Int,
                before: Int,
                count: Int
            ) {
                adapter.notifyDataSetChanged()
                empty.visibility = if (filtered().isEmpty()) View.VISIBLE else View.GONE
            }

            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })

        load()
    }

    private fun filtered(): List<Entry> {
        val q = search.text?.toString()?.trim()?.lowercase().orEmpty()
        if (q.isEmpty()) return items
        return items.filter {
            it.title.lowercase().contains(q) || it.url.lowercase().contains(q)
        }
    }

    private fun load() {
        items.clear()

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val arr = runCatching {
            JSONArray(prefs.getString(KEY, "[]"))
        }.getOrElse { JSONArray() }

        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            items.add(
                Entry(
                    title = o.optString("title", "Page"),
                    url = o.optString("url", ""),
                    visitedAt = o.optLong("visitedAt", 0L)
                )
            )
        }

        items.reverse()
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }
}
