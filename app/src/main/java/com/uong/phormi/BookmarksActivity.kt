package com.uong.phormi

import android.content.Intent
import android.net.Uri
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

/** Local bookmarks stored only on this phone. */
class BookmarksActivity : AppCompatActivity() {

    data class Bookmark(val title: String, val url: String, val addedAt: Long)

    private val items = mutableListOf<Bookmark>()
    private lateinit var adapter: BaseAdapter
    private lateinit var empty: TextView
    private lateinit var search: EditText

    companion object {
        private const val PREFS = "phormi_bookmarks"
        private const val KEY = "items"

        fun add(context: android.content.Context, title: String, url: String) {
            if (url.isBlank() || url == "about:blank") return
            val prefs = context.getSharedPreferences(PREFS, MODE_PRIVATE)
            val arr = runCatching {
                JSONArray(prefs.getString(KEY, "[]"))
            }.getOrElse { JSONArray() }

            for (i in 0 until arr.length()) {
                if (arr.optJSONObject(i)?.optString("url") == url) return
            }

            arr.put(
                JSONObject()
                    .put("title", title.ifBlank { url })
                    .put("url", url)
                    .put("addedAt", System.currentTimeMillis())
            )
            prefs.edit().putString(KEY, arr.toString()).apply()
        }

        /**
         * Returns bookmarks newest-first for quick-access surfaces.
         * This is intentionally read-only and does not alter the stored list.
         */
        fun getAll(context: android.content.Context): List<Bookmark> {
            val prefs = context.getSharedPreferences(PREFS, MODE_PRIVATE)
            val arr = runCatching {
                JSONArray(prefs.getString(KEY, "[]"))
            }.getOrElse { JSONArray() }

            val out = mutableListOf<Bookmark>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val url = o.optString("url", "").trim()
                if (url.isBlank()) continue
                out += Bookmark(
                    title = o.optString("title", url).ifBlank { url },
                    url = url,
                    addedAt = o.optLong("addedAt", 0L)
                )
            }
            return out.sortedByDescending { it.addedAt }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bookmarks)

        empty = findViewById(R.id.bookmarks_empty)
        search = findViewById(R.id.bookmarks_search)
        findViewById<TextView>(R.id.bookmarks_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.bookmarks_add).setOnClickListener { promptAdd() }

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
                    R.layout.item_bookmark,
                    parent,
                    false
                )
                val b = filtered()[position]
                v.findViewById<TextView>(R.id.bookmark_title).text = b.title
                v.findViewById<TextView>(R.id.bookmark_url).text = b.url
                v.setOnClickListener {
                    setResult(RESULT_OK, Intent().putExtra("open_url", b.url))
                    finish()
                }
                v.setOnLongClickListener {
                    confirmDelete(b)
                    true
                }
                return v
            }
        }

        findViewById<ListView>(R.id.bookmarks_list).adapter = adapter

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

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun filtered(): List<Bookmark> {
        val q = search.text?.toString()?.trim()?.lowercase().orEmpty()
        if (q.isEmpty()) return items
        return items.filter {
            it.title.lowercase().contains(q) || it.url.lowercase().contains(q)
        }
    }

    private fun load() {
        items.clear()
        items.addAll(getAll(this))
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    private fun persist() {
        val arr = JSONArray()
        items.asReversed().forEach { b ->
            arr.put(
                JSONObject()
                    .put("title", b.title)
                    .put("url", b.url)
                    .put("addedAt", b.addedAt)
            )
        }
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putString(KEY, arr.toString())
            .apply()
    }

    private fun promptAdd() {
        val input = EditText(this).apply {
            hint = "https://example.com"
            setTextColor(0xFFF1F5F9.toInt())
            setHintTextColor(0xFF64748B.toInt())
            setSingleLine()
        }

        AlertDialog.Builder(this)
            .setTitle("Add bookmark")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isBlank()) return@setPositiveButton
                val normalized =
                    if (url.startsWith("http")) url else "https://$url"
                items.add(
                    0,
                    Bookmark(
                        title = normalized,
                        url = normalized,
                        addedAt = System.currentTimeMillis()
                    )
                )
                persist()
                adapter.notifyDataSetChanged()
                empty.visibility = View.GONE
                Toast.makeText(this, "Bookmark saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(b: Bookmark) {
        AlertDialog.Builder(this)
            .setTitle("Remove bookmark?")
            .setMessage(b.url)
            .setPositiveButton("Remove") { _, _ ->
                items.removeAll {
                    it.url == b.url && it.addedAt == b.addedAt
                }
                persist()
                adapter.notifyDataSetChanged()
                empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
