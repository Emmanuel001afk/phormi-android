package com.uong.phormi

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.GridView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Phormi tab overview: searchable circular tab cards. */
class TabsOverviewActivity : AppCompatActivity() {

    data class TabInfo(val index: Int, val title: String, val url: String)

    private val allTabs = mutableListOf<TabInfo>()
    private val visibleTabs = mutableListOf<TabInfo>()
    private lateinit var adapter: BaseAdapter
    private lateinit var tabCount: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tabs_overview)

        findViewById<TextView>(R.id.btn_close_overview).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btn_new_tab_overview).setOnClickListener {
            setResult(RESULT_OK, Intent().putExtra("action", "new_tab"))
            finish()
        }
        tabCount = findViewById(R.id.tab_count)

        loadTabs()
        visibleTabs.addAll(allTabs)

        val grid = findViewById<GridView>(R.id.tabs_grid)
        adapter = object : BaseAdapter() {
            override fun getCount() = visibleTabs.size
            override fun getItem(position: Int) = visibleTabs[position]
            override fun getItemId(position: Int) = visibleTabs[position].index.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val v = convertView ?: LayoutInflater.from(this@TabsOverviewActivity)
                    .inflate(R.layout.item_tab_circle, parent, false)
                val item = visibleTabs[position]
                v.findViewById<TextView>(R.id.tab_circle_title).text = item.title
                v.findViewById<TextView>(R.id.tab_circle_url).text =
                    if (item.url == "about:blank") "New tab" else item.url
                v.findViewById<View>(R.id.tab_circle_body).setOnClickListener {
                    setResult(RESULT_OK, Intent().putExtra("action", "select").putExtra("index", item.index))
                    finish()
                }
                v.findViewById<TextView>(R.id.tab_circle_close).setOnClickListener {
                    setResult(RESULT_OK, Intent().putExtra("action", "close").putExtra("index", item.index))
                    finish()
                }
                return v
            }
        }
        grid.adapter = adapter
        updateCount()

        findViewById<EditText>(R.id.search_tabs).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.trim()?.lowercase().orEmpty()
                visibleTabs.clear()
                visibleTabs.addAll(allTabs.filter {
                    it.title.lowercase().contains(query) || it.url.lowercase().contains(query)
                })
                adapter.notifyDataSetChanged()
                updateCount()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    private fun loadTabs() {
        val prefs = getSharedPreferences("phormi_tabs", MODE_PRIVATE)
        val json = prefs.getString("tab_urls", "[]") ?: "[]"
        val titlesJson = prefs.getString("tab_titles", "[]") ?: "[]"
        try {
            val urls = org.json.JSONArray(json)
            val titles = org.json.JSONArray(titlesJson)
            for (i in 0 until urls.length()) {
                val url = urls.optString(i, "")
                val title = if (i < titles.length()) titles.optString(i, "Tab") else "Tab"
                allTabs.add(TabInfo(i, title.ifBlank { "Tab ${i + 1}" }, url))
            }
        } catch (_: Exception) {
            allTabs.clear()
        }
    }

    private fun updateCount() {
        // Keep the badge as the real number of open tabs, even while searching.
        tabCount.text = allTabs.size.toString()
    }
}
