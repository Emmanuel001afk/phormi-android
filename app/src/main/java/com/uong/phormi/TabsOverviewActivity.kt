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
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * Tab overview. The Split action is a real two-tab picker: first choose the
 * upper pane, then choose the lower pane. Blank tabs are valid selections.
 */
class TabsOverviewActivity : AppCompatActivity() {
    data class TabInfo(val index: Int, val title: String, val url: String)
    private val allTabs = mutableListOf<TabInfo>()
    private lateinit var groupManager: TabGroupManager
    private lateinit var tabsVerticalScroll: View
    private lateinit var tabsHorizontalScroll: View
    private lateinit var tabsGridScroll: View
    private lateinit var tabsVertical: LinearLayout
    private lateinit var tabsHorizontal: LinearLayout
    private lateinit var tabsGrid: LinearLayout
    private lateinit var tabCount: TextView
    private lateinit var modeButton: TextView
    private var mode = "vertical"

    companion object {
        private const val PREFS = "phormi_tabs"
        private const val KEY_MODE = "tab_view_mode"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tabs_overview)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        groupManager = TabGroupManager(this)
        mode = prefs.getString(KEY_MODE, "vertical") ?: "vertical"
        findViewById<TextView>(R.id.btn_close_overview).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btn_new_tab_overview).setOnClickListener {
            setResult(RESULT_OK, Intent().putExtra("action", "new_tab")); finish()
        }
        findViewById<TextView>(R.id.btn_split_overview).setOnClickListener { chooseSplitTabs() }
        findViewById<TextView>(R.id.btn_groups_overview).setOnClickListener { showGroups() }
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
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString()?.trim()?.lowercase().orEmpty()
                renderTabs(allTabs.filter { it.title.lowercase().contains(q) || it.url.lowercase().contains(q) })
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    private fun chooseSplitTabs() {
        if (allTabs.size < 2) {
            AlertDialog.Builder(this)
                .setTitle("Split screen")
                .setMessage("Open at least two tabs first. Blank tabs can be used too.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val choices = mutableListOf<Pair<String, Int>>()
        groupManager.list().forEach { g ->
            val first = allTabs.firstOrNull { g.urls.contains(it.url) }
            if (first != null) choices.add("▣ ${g.name} (${g.urls.size} tabs)" to first.index)
        }
        allTabs.forEach { item -> if (groupManager.groupFor(item.url) == null) choices.add("${item.index + 1}. ${item.title}" to item.index) }
        val labels = choices.map { it.first }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Choose TOP pane")
            .setItems(labels) { _, top ->
                AlertDialog.Builder(this)
                    .setTitle("Choose BOTTOM pane")
                    .setItems(labels) { _, bottom ->
                        val topIndex = choices[top].second; val bottomIndex = choices[bottom].second
                        if (topIndex == bottomIndex) {
                            AlertDialog.Builder(this).setTitle("Choose two different panes").setMessage("The top and bottom panes must use different tabs or groups.").setPositiveButton("OK", null).show()
                        } else {
                            setResult(RESULT_OK, Intent().putExtra("action", "split_pair").putExtra("top_index", topIndex).putExtra("bottom_index", bottomIndex)); finish()
                        }
                    }.setNegativeButton("Cancel", null).show()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun renderTabs(items: List<TabInfo>) {
        listOf(tabsVertical, tabsHorizontal, tabsGrid).forEach { it.removeAllViews() }
        tabCount.text = allTabs.size.toString()
        modeButton.text = when (mode) { "vertical" -> "▥ Vertical"; "horizontal" -> "⇆ Horizontal"; else -> "▦ Grid" }
        tabsVerticalScroll.visibility = if (mode == "vertical") View.VISIBLE else View.GONE
        tabsHorizontalScroll.visibility = if (mode == "horizontal") View.VISIBLE else View.GONE
        tabsGridScroll.visibility = if (mode == "grid") View.VISIBLE else View.GONE
        if (items.isEmpty()) {
            val empty = TextView(this).apply { text = "No matching tabs"; setTextColor(0xFF94A3B8.toInt()); textSize = 14f; gravity = Gravity.CENTER; setPadding(24,24,24,24) }
            when(mode) { "vertical" -> tabsVertical; "horizontal" -> tabsHorizontal; else -> tabsGrid }.addView(empty, LinearLayout.LayoutParams(-1,-1)); return
        }
        if (mode == "grid") {
            items.chunked(2).forEach { pair ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(-1,160) }
                pair.forEach { item -> row.addView(makeTab(item, "grid"), LinearLayout.LayoutParams(0,-1,1f)) }
                if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0,-1,1f))
                tabsGrid.addView(row)
            }
        } else if (mode == "vertical") {
            val groupedUrls = mutableSetOf<String>()
            groupManager.list().forEach { group ->
                val members = items.filter { group.urls.contains(it.url) }
                if (members.isNotEmpty()) {
                    groupedUrls.addAll(members.map { it.url })
                    tabsVertical.addView(makeGroupCard(group, members), LinearLayout.LayoutParams(-1, -2).apply { topMargin = 6 })
                }
            }
            items.filterNot { groupedUrls.contains(it.url) }.forEach { item -> tabsVertical.addView(makeTab(item, "vertical"), LinearLayout.LayoutParams(-1,154).apply { topMargin=6 }) }
        } else {
            tabsHorizontal.orientation = LinearLayout.VERTICAL
            val topRow = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
            val bottomRow = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
            items.forEachIndexed { index, item ->
                val view = makeTab(item, "horizontal")
                val lp = LinearLayout.LayoutParams(174.dp(),174.dp()).apply { leftMargin=6; rightMargin=6 }
                if (index % 2 == 0) topRow.addView(view,lp) else bottomRow.addView(view,lp)
            }
            tabsHorizontal.addView(topRow,LinearLayout.LayoutParams(-2,182.dp()))
            tabsHorizontal.addView(bottomRow,LinearLayout.LayoutParams(-2,182.dp()))
        }
    }

    private fun makeGroupCard(group: TabGroupManager.Group, members: List<TabInfo>): View {
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(14.dp(),12.dp(),14.dp(),12.dp()); setBackgroundColor(0xFF162033.toInt()) }
        val header = TextView(this).apply { text = "▣ ${group.name}  ·  ${members.size} tabs"; setTextColor(0xFF38BDF8.toInt()); textSize = 15f }
        card.addView(header)
        if (group.taskNote.isNotBlank()) card.addView(TextView(this).apply { text = "Task: ${group.taskNote}"; setTextColor(0xFF94A3B8.toInt()); textSize = 11f; setPadding(0,6,0,8) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        members.forEach { item ->
            row.addView(TextView(this).apply {
                text = item.title.take(14); setTextColor(0xFFE2E8F0.toInt()); textSize = 11f; gravity = Gravity.CENTER; setPadding(10,8,10,8); setBackgroundColor(0xFF243247.toInt())
                setOnClickListener { setResult(RESULT_OK, Intent().putExtra("action","select").putExtra("index",item.index)); finish() }
            }, LinearLayout.LayoutParams(0,48.dp(),1f).apply { rightMargin=5 })
        }
        card.addView(row)
        card.setOnLongClickListener {
            val input = EditText(this).apply { setText(group.taskNote); hint = "What are you trying to finish?" }
            AlertDialog.Builder(this).setTitle("${group.name} task thread").setView(input).setPositiveButton("Save") { _, _ -> groupManager.setTaskNote(group.id, input.text.toString()); renderTabs(allTabs) }.setNegativeButton("Cancel", null).show(); true
        }
        return card
    }

    private fun makeTab(item: TabInfo, style: String): View {
        val v = LayoutInflater.from(this).inflate(R.layout.item_tab_circle, null, false)
        val body = v.findViewById<View>(R.id.tab_circle_body)
        val title = v.findViewById<TextView>(R.id.tab_circle_title)
        val url = v.findViewById<TextView>(R.id.tab_circle_url)
        title.text = item.title
        val group = groupManager.groupFor(item.url)
        title.text = if (group != null) "${item.title}  ·  ${group.name}" else item.title
        url.text = if (item.url == "about:blank") "New tab" else item.url
        if (style != "horizontal") {
            val lp = body.layoutParams as android.widget.FrameLayout.LayoutParams
            lp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = if(style=="vertical") 142.dp() else 148.dp()
            lp.gravity = Gravity.CENTER
            body.layoutParams = lp
            body.background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFFFFE9E2.toInt()); setStroke(2.dp(),0xFFF6CFC5.toInt()); cornerRadius=18.dp().toFloat() }
            body.setPadding(if(style=="vertical") 18.dp() else 12.dp(),14.dp(),42.dp(),14.dp())
            title.gravity=Gravity.CENTER_VERTICAL; url.gravity=Gravity.CENTER_VERTICAL
            title.textSize=if(style=="vertical") 15f else 13f; url.textSize=if(style=="vertical") 10f else 9f
        }
        body.setOnClickListener { setResult(RESULT_OK,Intent().putExtra("action","select").putExtra("index",item.index)); finish() }
        body.setOnLongClickListener { showGroupEditor(item); true }
        v.findViewById<TextView>(R.id.tab_circle_close).setOnClickListener { setResult(RESULT_OK,Intent().putExtra("action","close").putExtra("index",item.index)); finish() }
        return v
    }


    private fun showGroupEditor(item: TabInfo) {
        if (item.url == "about:blank") {
            Toast.makeText(this, "Open a page before grouping it", Toast.LENGTH_SHORT).show()
            return
        }
        val groups = groupManager.list()
        val labels = mutableListOf("No group", "＋ Create new group")
        labels.addAll(groups.map { "${it.name}  ·  ${it.urls.size} tab(s)" })
        val current = groupManager.groupFor(item.url)
        AlertDialog.Builder(this).setTitle("Group tab")
            .setItems(labels.toTypedArray()) { _, which ->
                when (which) {
                    0 -> groupManager.unassign(item.url)
                    1 -> {
                        val input = EditText(this).apply { hint = "Group name" }
                        AlertDialog.Builder(this).setTitle("New tab group").setView(input)
                            .setPositiveButton("Create") { _, _ ->
                                val g = groupManager.create(input.text.toString())
                                groupManager.assign(g.id, item.url)
                                renderTabs(allTabs)
                            }.setNegativeButton("Cancel", null).show()
                    }
                    else -> groupManager.assign(groups[which - 2].id, item.url)
                }
                if (which != 1) renderTabs(allTabs)
            }.setNegativeButton("Cancel", null).show()
    }


    private fun showGroups() {
        val groups = groupManager.list()
        if (groups.isEmpty()) {
            val input = EditText(this).apply { hint = "Group name" }
            AlertDialog.Builder(this).setTitle("Create tab group").setView(input)
                .setPositiveButton("Create") { _, _ -> groupManager.create(input.text.toString()); renderTabs(allTabs) }
                .setNegativeButton("Cancel", null).show()
            return
        }
        val labels = groups.map { "${it.name} · ${it.urls.size} tab(s)${if (it.taskNote.isBlank()) "" else " · note"}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Tab groups")
            .setItems(labels) { _, which ->
                val g = groups[which]
                val input = EditText(this).apply { setText(g.taskNote); hint = "What are you trying to finish?" }
                AlertDialog.Builder(this).setTitle("${g.name} · task thread").setView(input)
                    .setPositiveButton("Save note") { _, _ -> groupManager.setTaskNote(g.id, input.text.toString()); renderTabs(allTabs) }
                    .setNeutralButton("Rename") { _, _ ->
                        val name = EditText(this).apply { setText(g.name) }
                        AlertDialog.Builder(this).setTitle("Rename group").setView(name).setPositiveButton("Save") { _, _ -> groupManager.rename(g.id, name.text.toString()); renderTabs(allTabs) }.setNegativeButton("Cancel", null).show()
                    }.setNegativeButton("Close", null).show()
            }.setPositiveButton("Create new") { _, _ ->
                val input = EditText(this).apply { hint = "Group name" }
                AlertDialog.Builder(this).setTitle("Create tab group").setView(input).setPositiveButton("Create") { _, _ -> groupManager.create(input.text.toString()); renderTabs(allTabs) }.setNegativeButton("Cancel", null).show()
            }.show()
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    private fun loadTabs() {
        allTabs.clear()
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        try {
            val urls = org.json.JSONArray(prefs.getString("tab_urls", "[]") ?: "[]")
            val titles = org.json.JSONArray(prefs.getString("tab_titles", "[]") ?: "[]")
            for (i in 0 until urls.length()) {
                allTabs.add(TabInfo(i, titles.optString(i,"Tab ${i+1}").ifBlank { "Tab ${i+1}" }, urls.optString(i,"about:blank")))
            }
        } catch (_:Exception) { }
    }
}
