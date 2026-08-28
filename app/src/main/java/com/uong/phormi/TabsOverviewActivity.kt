package com.uong.phormi

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Circular tab overview — unique Phormi look (circles, not Chrome squares).
 * Tab data is passed via SharedPreferences snapshot from MainActivity.
 */
class TabsOverviewActivity : AppCompatActivity() {

    data class TabInfo(val index: Int, val title: String, val url: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tabs_overview)

        findViewById<TextView>(R.id.btn_close_overview).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btn_new_tab_overview).setOnClickListener {
            setResult(RESULT_OK, Intent().putExtra("action", "new_tab"))
            finish()
        }

        val prefs = getSharedPreferences("phormi_tabs", MODE_PRIVATE)
        val json = prefs.getString("tab_urls", "[]") ?: "[]"
        val titlesJson = prefs.getString("tab_titles", "[]") ?: "[]"
        val tabs = mutableListOf<TabInfo>()
        try {
            val urls = org.json.JSONArray(json)
            val titles = org.json.JSONArray(titlesJson)
            for (i in 0 until urls.length()) {
                val url = urls.optString(i, "")
                val title = if (i < titles.length()) titles.optString(i, "Tab") else "Tab"
                tabs.add(TabInfo(i, title.ifBlank { "Tab ${i + 1}" }, url))
            }
        } catch (_: Exception) { }

        val grid = findViewById<GridView>(R.id.tabs_grid)
        grid.adapter = object : BaseAdapter() {
            override fun getCount() = tabs.size
            override fun getItem(position: Int) = tabs[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val v = convertView ?: LayoutInflater.from(this@TabsOverviewActivity)
                    .inflate(R.layout.item_tab_circle, parent, false)
                val item = tabs[position]
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
    }
}
