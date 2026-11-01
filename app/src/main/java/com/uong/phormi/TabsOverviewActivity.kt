package com.uong.phormi

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** Tab overview with persistent commands routed back to the live browser. */
class TabsOverviewActivity : AppCompatActivity() {
    data class TabInfo(val index: Int, val id: Int, val title: String, val url: String, val profile: String, val groupName: String = "")
    private val allTabs = mutableListOf<TabInfo>()
    private lateinit var tabsVerticalScroll: View
    private lateinit var tabsHorizontalScroll: View
    private lateinit var tabsGridScroll: View
    private lateinit var tabsVertical: LinearLayout
    private lateinit var tabsHorizontal: LinearLayout
    private lateinit var tabsGrid: LinearLayout
    private lateinit var tabCount: TextView
    private lateinit var modeButton: TextView
    private lateinit var selectButton: TextView
    private lateinit var selectAllButton: TextView
    private lateinit var closeSelectedButton: TextView
    private var mode = "vertical"
    private var query = ""
    // Bulk selection is explicit: entering select mode changes what a tap means,
    // so no tab is ever closed by an ordinary tap on a card.
    private var selectionMode = false
    private val selectedIds = mutableSetOf<Int>()

    companion object { private const val PREFS = "phormi_tabs"; private const val KEY_MODE = "tab_view_mode" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tabs_overview)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        mode = prefs.getString(KEY_MODE, "vertical") ?: "vertical"
        findViewById<TextView>(R.id.btn_close_overview).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btn_new_tab_overview).setOnClickListener { setResult(RESULT_OK, Intent().putExtra("action", "new_tab")); finish() }
        findViewById<TextView>(R.id.btn_split_overview).setOnClickListener { setResult(RESULT_OK, Intent().putExtra("action", "toggle_split")); finish() }
        findViewById<TextView>(R.id.btn_groups_overview).setOnClickListener { startActivity(Intent(this, TabGroupsActivity::class.java)) }
        modeButton = findViewById(R.id.btn_view_mode)
        modeButton.setOnClickListener {
            mode = when (mode) { "vertical" -> "horizontal"; "horizontal" -> "grid"; else -> "vertical" }
            prefs.edit().putString(KEY_MODE, mode).apply(); applyFilter()
        }
        tabsVerticalScroll = findViewById(R.id.tabs_vertical_scroll)
        tabsHorizontalScroll = findViewById(R.id.tabs_horizontal_scroll)
        tabsGridScroll = findViewById(R.id.tabs_grid_scroll)
        tabsVertical = findViewById(R.id.tabs_vertical)
        tabsHorizontal = findViewById(R.id.tabs_horizontal)
        tabsGrid = findViewById(R.id.tabs_grid)
        tabCount = findViewById(R.id.tab_count)
        selectButton = findViewById(R.id.btn_select_tabs)
        selectAllButton = findViewById(R.id.btn_select_all_tabs)
        closeSelectedButton = findViewById(R.id.btn_close_selected)

        selectButton.setOnClickListener {
            selectionMode = !selectionMode
            if (!selectionMode) selectedIds.clear()
            updateSelectionUi()
            applyFilter()
        }
        selectAllButton.setOnClickListener {
            if (selectedIds.size == allTabs.size) selectedIds.clear()
            else allTabs.forEach { selectedIds.add(it.id) }
            updateSelectionUi()
            applyFilter()
        }
        closeSelectedButton.setOnClickListener { confirmCloseSelected() }

        loadTabs()
        updateSelectionUi()
        applyFilter()
        findViewById<EditText>(R.id.search_tabs).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                query = s?.toString()?.trim()?.lowercase().orEmpty()
                applyFilter()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    private fun applyFilter() {
        val items = if (query.isBlank()) allTabs
        else allTabs.filter { it.title.lowercase().contains(query) || it.url.lowercase().contains(query) }
        renderTabs(items)
    }

    private fun updateSelectionUi() {
        selectButton.text = if (selectionMode) "Cancel" else "Select"
        val visible = if (selectionMode) View.VISIBLE else View.GONE
        selectAllButton.visibility = visible
        closeSelectedButton.visibility = visible
        selectAllButton.text = if (selectedIds.size == allTabs.size && allTabs.isNotEmpty()) "None" else "All"
        closeSelectedButton.text = "Close (${selectedIds.size})"
        closeSelectedButton.isEnabled = selectedIds.isNotEmpty()
        closeSelectedButton.alpha = if (selectedIds.isEmpty()) 0.5f else 1f
    }

    private fun confirmCloseSelected() {
        if (selectedIds.isEmpty()) return
        val count = selectedIds.size
        AlertDialog.Builder(this)
            .setTitle("Close $count tab${if (count == 1) "" else "s"}?")
            .setMessage("The selected tabs will be removed from this browser session.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Close $count") { _, _ ->
                setResult(RESULT_OK, Intent().apply {
                    putExtra("action", "close_many")
                    putExtra("tab_ids", selectedIds.toIntArray())
                })
                finish()
            }.show()
    }

    private fun renderTabs(items: List<TabInfo>) {
        listOf(tabsVertical, tabsHorizontal, tabsGrid).forEach { it.removeAllViews() }
        tabCount.text = allTabs.size.toString()
        modeButton.text = when (mode) { "vertical" -> "▥ Vertical"; "horizontal" -> "⇆ Horizontal"; else -> "▦ Grid" }
        tabsVerticalScroll.visibility = if (mode == "vertical") View.VISIBLE else View.GONE
        tabsHorizontalScroll.visibility = if (mode == "horizontal") View.VISIBLE else View.GONE
        tabsGridScroll.visibility = if (mode == "grid") View.VISIBLE else View.GONE
        if (items.isEmpty()) {
            val empty = TextView(this).apply { text = "No matching tabs"; setTextColor(0xFF94A3B8.toInt()); textSize = 14f; gravity = Gravity.CENTER; setPadding(dp(24), dp(24), dp(24), dp(24)) }
            when (mode) { "vertical" -> tabsVertical; "horizontal" -> tabsHorizontal; else -> tabsGrid }.addView(empty, LinearLayout.LayoutParams(-1, -1)); return
        }
        if (mode == "grid") {
            val sections = items.groupBy { it.groupName.ifBlank { "Ungrouped" } }
            sections.forEach { (group, groupItems) ->
                tabsGrid.addView(groupHeader(group), LinearLayout.LayoutParams(-1, -2))
                groupItems.chunked(2).forEach { pair ->
                    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(-1, dp(160)) }
                    pair.forEach { item -> row.addView(makeTab(item, "grid"), LinearLayout.LayoutParams(0, -1, 1f)) }
                    if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, -1, 1f))
                    tabsGrid.addView(row)
                }
            }
        } else if (mode == "vertical") {
            var lastGroup = ""
            items.forEach { item ->
                val group = item.groupName.ifBlank { "Ungrouped" }
                if (group != lastGroup) {
                    tabsVertical.addView(TextView(this).apply {
                        text = if (group == "Ungrouped") "UNGROUPED TABS" else "GROUP · $group"
                        textSize = 12f; setTextColor(0xFF38BDF8.toInt()); setPadding(dp(8), dp(12), dp(8), dp(4))
                    }, LinearLayout.LayoutParams(-1, -2))
                    lastGroup = group
                }
                tabsVertical.addView(makeTab(item, "vertical"), LinearLayout.LayoutParams(-1, dp(154)).apply { topMargin = dp(6) })
            }
        } else {
            tabsHorizontal.orientation = LinearLayout.VERTICAL
            items.groupBy { it.groupName.ifBlank { "Ungrouped" } }.forEach { (group, groupItems) ->
                tabsHorizontal.addView(groupHeader(group), LinearLayout.LayoutParams(-1, -2))
                groupItems.chunked(2).forEach { pair ->
                    val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                    val bottomRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                    pair.forEachIndexed { index, item ->
                        val view = makeTab(item, "horizontal")
                        val lp = LinearLayout.LayoutParams(dp(174), dp(174)).apply { leftMargin = dp(6); rightMargin = dp(6) }
                        if (index == 0) topRow.addView(view, lp) else bottomRow.addView(view, lp)
                    }
                    tabsHorizontal.addView(topRow, LinearLayout.LayoutParams(-2, dp(182)))
                    if (bottomRow.childCount > 0) tabsHorizontal.addView(bottomRow, LinearLayout.LayoutParams(-2, dp(182)))
                }
            }
        }
    }

    private fun groupHeader(group: String): TextView = TextView(this).apply {
        text = if (group == "Ungrouped") "UNGROUPED TABS" else "GROUP · $group"
        textSize = 12f
        setTextColor(0xFF38BDF8.toInt())
        setPadding(dp(8), dp(12), dp(8), dp(4))
    }

    private fun makeTab(item: TabInfo, style: String): View {
        val v = LayoutInflater.from(this).inflate(R.layout.item_tab_circle, null, false)
        val body = v.findViewById<View>(R.id.tab_circle_body)
        val title = v.findViewById<TextView>(R.id.tab_circle_title)
        val url = v.findViewById<TextView>(R.id.tab_circle_url)
        val selected = selectionMode && item.id in selectedIds
        title.text = item.title
        url.text = buildString {
            append(if (item.url == "about:blank") "New tab" else item.url)
            if (item.groupName.isNotBlank()) append("\nGroup: ${item.groupName}")
            if (item.profile.isNotBlank() && !item.profile.equals("Default", true)) append("\nEnvironment: ${item.profile}")
        }
        if (style != "horizontal") {
            val lp = body.layoutParams as FrameLayout.LayoutParams
            lp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = if (style == "vertical") dp(142) else dp(148)
            lp.gravity = Gravity.CENTER
            body.layoutParams = lp
            body.background = GradientDrawable().apply {
                setColor(if (selected) 0xFFDBEAFE.toInt() else 0xFFFFE9E2.toInt())
                setStroke(if (selected) dp(3) else dp(2), if (selected) 0xFF2563EB.toInt() else 0xFFF6CFC5.toInt())
                cornerRadius = dp(18).toFloat()
            }
            body.setPadding(if (style == "vertical") dp(18) else dp(12), dp(14), dp(92), dp(14))
            title.gravity = Gravity.CENTER_VERTICAL
            title.setTextColor(0xFF111827.toInt())
            url.gravity = Gravity.CENTER_VERTICAL
            url.setTextColor(0xFF334155.toInt())
            title.textSize = if (style == "vertical") 15f else 13f
            url.textSize = if (style == "vertical") 10f else 9f
        } else if (selected) {
            body.background = GradientDrawable().apply {
                setColor(0xFFDBEAFE.toInt())
                setStroke(dp(3), 0xFF2563EB.toInt())
                cornerRadius = dp(18).toFloat()
            }
        }

        if (selectionMode && v is FrameLayout) {
            val check = TextView(this).apply {
                text = if (selected) "✓" else ""
                contentDescription = if (selected) "Deselect ${item.title}" else "Select ${item.title}"
                gravity = Gravity.CENTER
                setTextColor(0xFF0F172A.toInt())
                textSize = 16f
                background = GradientDrawable().apply {
                    setColor(if (selected) 0xFF38BDF8.toInt() else 0xFFF8FAFC.toInt())
                    setStroke(dp(2), 0xFF2563EB.toInt())
                    cornerRadius = dp(17).toFloat()
                }
            }
            v.addView(check, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.TOP or Gravity.START).apply {
                leftMargin = dp(8); topMargin = dp(8)
            })
        }

        body.setOnClickListener {
            if (selectionMode) {
                if (item.id in selectedIds) selectedIds.remove(item.id) else selectedIds.add(item.id)
                updateSelectionUi()
                applyFilter()
            } else {
                setResult(RESULT_OK, Intent().putExtra("action", "select").putExtra("tab_id", item.id))
                finish()
            }
        }
        body.setOnLongClickListener {
            val actions = mutableListOf("Environment", "Assign to group")
            if (!selectionMode) actions += "Select multiple tabs"
            AlertDialog.Builder(this).setTitle(item.title).setItems(actions.toTypedArray()) { _, which ->
                when {
                    which == 0 -> showEnvironmentChooser(item)
                    which == 1 -> showGroupChooser(item)
                    else -> {
                        selectionMode = true
                        selectedIds.add(item.id)
                        updateSelectionUi()
                        applyFilter()
                    }
                }
            }.show()
            true
        }
        val groupButton = TextView(this).apply {
            text = "Group"; contentDescription = "Assign ${item.title} to a tab group"
            gravity = Gravity.CENTER; setTextColor(0xFF0F172A.toInt()); textSize = 11f
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = GradientDrawable().apply { setColor(0xFFBAE6FD.toInt()); cornerRadius = dp(14).toFloat() }
            setOnClickListener { showGroupChooser(item) }
        }
        // The card body is a LinearLayout, while the outer root is a FrameLayout.
        // The old code checked `body is FrameLayout`, which was never true, so
        // the "Group" action was silently missing from every card.
        if (v is FrameLayout) v.addView(groupButton, FrameLayout.LayoutParams(-2, dp(34), Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(44); bottomMargin = dp(8)
        })
        v.findViewById<TextView>(R.id.tab_circle_close).setOnClickListener {
            setResult(RESULT_OK, Intent().putExtra("action", "close").putExtra("tab_id", item.id))
            finish()
        }
        return v
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun loadTabs() {
        allTabs.clear(); val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        runCatching {
            val urls = org.json.JSONArray(prefs.getString("tab_urls", "[]") ?: "[]")
            val titles = org.json.JSONArray(prefs.getString("tab_titles", "[]") ?: "[]")
            val profiles = org.json.JSONArray(prefs.getString("tab_profiles", "[]") ?: "[]")
            val ids = org.json.JSONArray(prefs.getString("tab_ids", "[]") ?: "[]")
            val groups = TabGroupManager(this).list()
            val groupByTab = mutableMapOf<Int, String>()
            groups.forEach { group -> group.tabIds.forEach { id -> groupByTab[id] = group.name } }
            for (i in 0 until urls.length()) {
                val id = ids.optInt(i, i + 1)
                allTabs.add(TabInfo(i, id, titles.optString(i, "Tab ${i + 1}").ifBlank { "Tab ${i + 1}" }, urls.optString(i, "about:blank"), profiles.optString(i, "Default").ifBlank { "Default" }, groupByTab[id].orEmpty()))
            }
        }
    }

    private fun showEnvironmentChooser(item: TabInfo) {
        val envs = (listOf(PhormiEnvironmentManager.DEFAULT_ENVIRONMENT) +
            PhormiEnvironmentManager.list().filter { it != "Ghost" && it != PhormiEnvironmentManager.DEFAULT_ENVIRONMENT })
            .distinct()
            .toTypedArray()
        if (envs.isEmpty()) return
        AlertDialog.Builder(this).setTitle("Environment for ${item.title}").setItems(envs) { _, which ->
            setResult(RESULT_OK, Intent().apply {
                putExtra("action", "reassign_env")
                putExtra("tab_id", item.id)
                putExtra("profile", envs[which])
            })
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
            setResult(RESULT_OK, Intent().apply {
                putExtra("action", "assign_group")
                putExtra("group_id", groups[which].id)
                putExtra("tab_id", item.id)
                putExtra("url", item.url)
            }); finish()
        }.show()
    }
}
