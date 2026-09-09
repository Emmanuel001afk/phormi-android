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

/** Local Favorites/Bookmarks screen backed by the canonical PhormiFavorites store. */
class BookmarksActivity : AppCompatActivity() {

    data class Bookmark(val title: String, val url: String, val addedAt: Long)

    private val items = mutableListOf<Bookmark>()
    private lateinit var adapter: BaseAdapter
    private lateinit var empty: TextView
    private lateinit var search: EditText

    companion object {
        /**
         * Returns the canonical favorites. Quick Access gets a filtered view so a
         * Quick Access hide never makes the Favorite disappear from the Favorites UI.
         */
        fun getAll(context: android.content.Context): List<Bookmark> {
            val all = PhormiFavorites.getAll(context).map { Bookmark(it.title, it.url, it.addedAt) }
            return if (calledFromQuickAccessRenderer()) {
                all.filterNot { PhormiQuickAccessState.isFavoriteHidden(context, it.url) }
            } else all
        }

        fun add(context: android.content.Context, title: String, url: String) {
            PhormiFavorites.add(context, title, url)
        }

        /**
         * Existing MainActivity uses this for both real unfavorite and Quick Access
         * removal. Distinguish the Quick Access action at the boundary so the latter
         * only hides the tile; the actual Favorite remains intact.
         */
        fun remove(context: android.content.Context, url: String) {
            if (calledFromQuickAccessRenderer()) {
                PhormiQuickAccessState.hideFavorite(context, url)
            } else {
                PhormiFavorites.remove(context, url)
            }
        }

        private fun calledFromQuickAccessRenderer(): Boolean =
            Throwable().stackTrace.any { it.className == MainActivity::class.java.name && it.methodName == "confirmQuickSiteRemoval" }
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

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val v = convertView ?: layoutInflater.inflate(R.layout.item_bookmark, parent, false)
                val b = filtered()[position]
                v.findViewById<TextView>(R.id.bookmark_title).text = b.title
                v.findViewById<TextView>(R.id.bookmark_url).text = b.url
                v.setOnClickListener {
                    setResult(RESULT_OK, Intent().putExtra("open_url", b.url))
                    finish()
                }
                v.setOnLongClickListener { confirmDelete(b); true }
                return v
            }
        }

        findViewById<ListView>(R.id.bookmarks_list).adapter = adapter
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.notifyDataSetChanged()
                empty.visibility = if (filtered().isEmpty()) View.VISIBLE else View.GONE
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        load()
    }

    override fun onResume() { super.onResume(); if (::empty.isInitialized) load() }

    private fun filtered(): List<Bookmark> {
        val q = search.text?.toString()?.trim()?.lowercase().orEmpty()
        if (q.isEmpty()) return items
        return items.filter { it.title.lowercase().contains(q) || it.url.lowercase().contains(q) }
    }

    private fun load() {
        items.clear()
        items.addAll(getAll(this))
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    private fun promptAdd() {
        val input = EditText(this).apply {
            hint = "https://example.com"
            setTextColor(0xFFF1F5F9.toInt())
            setHintTextColor(0xFF64748B.toInt())
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("Add favorite")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val raw = input.text.toString().trim()
                if (raw.isBlank()) return@setPositiveButton
                val normalized = if (raw.startsWith("http")) raw else "https://$raw"
                PhormiFavorites.add(this, normalized, normalized)
                load()
                Toast.makeText(this, "Favorite saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(b: Bookmark) {
        AlertDialog.Builder(this)
            .setTitle("Remove favorite?")
            .setMessage(b.url)
            .setPositiveButton("Remove") { _, _ ->
                PhormiFavorites.remove(this, b.url)
                load()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
