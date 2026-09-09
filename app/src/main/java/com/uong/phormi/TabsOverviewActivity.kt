package com.uong.phormi

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** Tab overview with persistent commands routed back to the live browser. */
class TabsOverviewActivity : AppCompatActivity() {
    data class TabInfo(val index: Int, val id: Int, val title: String, val url: String, val profile: String)
    private val allTabs = mutableListOf<TabInfo>()
    private lateinit var tabsVerticalScroll: View
    private lateinit var tabsHorizontalScroll: View
    private lateinit var tabsGridScroll: View
    private lateinit var tabsVertical: LinearLayout
    private lateinit var tabsHorizontal: LinearLayout
    private lateinit var tabsGrid: LinearLayout
    private lateinit var tabCount: TextView
    private lateinit var modeButton: TextView
    private var mode = "vertical"

    companion object { private const val PREFS = "phormi_tabs"; private const val KEY_MODE = "tab_view_mode" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tabs_overview)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        mode = prefs.getString(KEY_MODE, "vertical") ?: "vertical"
        findViewById<TextView>(R.id.btn_close_overview).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btn_new_tab_overview).setOnClickListener { PhormiCommandBus.enqueue(this, "new_tab"); finish() }
        findViewById<TextView>(R.id.btn_split_overview).setOnClickListener { PhormiCommandBus.enqueue(this, "toggle_split"); finish() }
        findViewById<TextView>(R.id.btn_groups_overview).setOnClickListener { startActivity(Intent(this, TabGroupsActivity::class.java)) }
        modeButton = findViewById(R.id.btn_view_mode)
        modeButton.setOnClickListener {
            mode = when (mode) { "vertical" -> "horizontal"; "horizontal" -> "grid"; else -> "vertical" }
            prefs.edit().putString(KEY_MODE, mode).apply(); renderTabs(allTabs)
        }
        tabsVerticalScroll = findViewById(R.id.tabs_vertical_scroll)
        tabsHorizontalScroll = findViewById(R.id.tabs_horizontal_scroll)
        tabsGridScroll = findViewById(R.id.tabs_grid_scroll)
        tabsVertical = findViewById(R.id.tabs_vertical)
        tabsHorizontal = findViewById(R.id.tabs_horizontal)
        tabsGrid = findViewById(R.id.tabs_grid)
        tabCount = findViewById(R.id.tab_count)
        loadTabs(); renderTabs(allTabs)
        findViewById<EditText>(R.id.search_tabs).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { val q = s?.toString()?.trim()?.lowercase().orEmpty(); renderTabs(allTabs.filter { it.title.lowercase().contains(q) || it.url.lowercase().contains(q) }) }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    private fun renderTabs(items: List<TabInfo>) {
        listOf(tabsVertical, tabsHorizontal, tabsGrid).forEach { it.removeAllViews() }
        tabCount.text = allTabs.size.toString()
        modeButton.text = when (mode) { "vertical" -> "▥ Vertical"; "horizontal" -> "⇆ Horizontal"; else -> "▦ Grid" }
        tabsVerticalScroll.visibility = if (mode == "vertical") View.VISIBLE else View.GONE
        tabsHorizontalScroll.visibility = if (mode == "horizontal") View.VISIBLE else View.GONE
        tabsGridScroll.visibility = if (mode == "grid") View.VISIBLE else View.GONE
        if (items.isEmpty()) {
            val empty = TextView(this).apply { text = "No matching tabs"; setTextColor(0xFF94A3B8.toInt()); textSize = 14f; gravity = Gravity.CENTER; setPadding(24, 24, 24, 24) }
            when (mode) { "vertical" -> tabsVertical; "horizontal" -> tabsHorizontal; else -> tabsGrid }.addView(empty, LinearLayout.LayoutParams(-1, -1)); return
        }
        if (mode == "grid") {
            items.chunked(2).forEach { pair ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(-1, 160) }
                pair.forEach { item -> row.addView(makeTab(item, "grid"), LinearLayout.LayoutParams(0, -1, 1f)) }
                if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, -1, 1f))
                tabsGrid.addView(row)
            }
        } else if (mode == "vertical") {
            items.forEach { item -> tabsVertical.addView(makeTab(item, "vertical"), LinearLayout.LayoutParams(-1, 154).apply { topMargin = 6 }) }
        } else {
            tabsHorizontal.orientation = LinearLayout.VERTICAL
            val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val bottomRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            items.forEachIndexed { index, item ->
                val view = makeTab(item, "horizontal")
                val lp = LinearLayout.LayoutParams(174.dp(), 174.dp()).apply { leftMargin = 6; rightMargin = 6 }
                if (index % 2 == 0) topRow.addView(view, lp) else bottomRow.addView(view, lp)
            }
            tabsHorizontal.addView(topRow, LinearLayout.LayoutParams(-2, 182.dp()))
            tabsHorizontal.addView(bottomRow, LinearLayout.LayoutParams(-2, 182.dp()))
        }
    }

    private fun makeTab(item: TabInfo, style: String): View {
        val v = LayoutInflater.from(this).inflate(R.layout.item_tab_circle, null, false)
        val body = v.findViewById<View>(R.id.tab_circle_body)
        val title = v.findViewById<TextView>(R.id.tab_circle_title)
        val url = v.findViewById<TextView>(R.id.tab_circle_url)
        title.text = item.title; url.text = if (item.url == "about:blank") "New tab" else item.url
        if (style != "horizontal") {
            val lp = body.layoutParams as android.widget.FrameLayout.LayoutParams
            lp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT; lp.height = if (style == "vertical") 142.dp() else 148.dp(); lp.gravity = Gravity.CENTER; body.layoutParams = lp
            body.background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFFFFE9E2.toInt()); setStroke(2.dp(), 0xFFF6CFC5.toInt()); cornerRadius = 18.dp().toFloat() }
            body.setPadding(if (style == "vertical") 18.dp() else 12.dp(), 14.dp(), 92.dp(), 14.dp())
            title.gravity = Gravity.CENTER_VERTICAL; title.setTextColor(0xFF111827.toInt()); url.gravity = Gravity.CENTER_VERTICAL; url.setTextColor(0xFF334155.toInt()); title.textSize = if (style == "vertical") 15f else 13f; url.textSize = if (style == "vertical") 10f else 9f
        }
        body.setOnClickListener { PhormiCommandBus.enqueue(this, "select", mapOf("tab_id" to item.id.toString())); finish() }
        body.setOnLongClickListener { AlertDialog.Builder(this).setTitle(item.title).setItems(arrayOf("Environment", "Assign to group")) { _, which -> if (which == 0) showEnvironmentChooser(item) else showGroupChooser(item) }.show(); true }
        val groupButton = TextView(this).apply {
            text = "Group"; contentDescription = "Assign ${item.title} to a tab group"; gravity = Gravity.CENTER; setTextColor(0xFF0F172A.toInt()); textSize = 11f; setPadding(10.dp(), 5.dp(), 10.dp(), 5.dp())
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFFBAE6FD.toInt()); cornerRadius = 14.dp().toFloat() }
            setOnClickListener { showGroupChooser(item) }
        }
        if (body is android.widget.FrameLayout) body.addView(groupButton, android.widget.FrameLayout.LayoutParams(-2, 34.dp(), Gravity.BOTTOM or Gravity.END).apply { rightMargin = 44.dp(); bottomMargin = 8.dp() })
        v.findViewById<TextView>(R.id.tab_circle_close).setOnClickListener { PhormiCommandBus.enqueue(this, "close", mapOf("tab_id" to item.id.toString())); finish() }
        return v
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    private fun loadTabs() {
        allTabs.clear(); val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        runCatching {
            val urls = org.json.JSONArray(prefs.getString("tab_urls", "[]") ?: "[]")
            val titles = org.json.JSONArray(prefs.getString("tab_titles", "[]") ?: "[]")
            val profiles = org.json.JSONArray(prefs.getString("tab_profiles", "[]") ?: "[]")
            val ids = org.json.JSONArray(prefs.getString("tab_ids", "[]") ?: "[]")
            for (i in 0 until urls.length()) allTabs.add(TabInfo(i, ids.optInt(i, i + 1), titles.optString(i, "Tab ${i + 1}").ifBlank { "Tab ${i + 1}" }, urls.optString(i, "about:blank"), profiles.optString(i, "Default").ifBlank { "Default" }))
        }
    }

    private fun showEnvironmentChooser(item: TabInfo) {
        val envs = PhormiEnvironmentManager.list().filter { it != "Ghost" }.toTypedArray()
        if (envs.isEmpty()) return
        AlertDialog.Builder(this).setTitle("Environment for ${item.title}").setItems(envs) { _, which ->
            PhormiCommandBus.enqueue(this, "reassign_env", mapOf("index" to item.index.toString(), "profile" to envs[which]))
            finish()
        }.show()
    }

    private fun showGroupChooser(item: TabInfo) {
        val groups = TabGroupManager(this).list()
        if (groups.isEmpty()) {
            AlertDialog.Builder(this).setTitle("No tab groups").setMessage("Create a group first, then assign this tab. You can assign as many tabs as you want to the same group.")
                .setNegativeButton("Cancel", null).setPositiveButton("Create group") { _, _ -> startActivity(Intent(this, TabGroupsActivity::class.java)) }.show(); return
        }
        AlertDialog.Builder(this).setTitle("Add tab to group").setItems(groups.map { it.name }.toTypedArray()) { _, which ->
            PhormiCommandBus.enqueue(this, "assign_group", mapOf("group_id" to groups[which].id, "url" to item.url, "tab_id" to item.id.toString())); finish()
        }.show()
    }
}
