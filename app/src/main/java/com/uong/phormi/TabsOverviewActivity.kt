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
            prefs.edit().putString(KEY_MODE, mode).apply(); filterTabs()
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

    override fun onResume() {
        super.onResume()
        if (::tabsVertical.isInitialized) {
            loadTabs(); selectedIds.retainAll(allTabs.map { it.id }.toSet()); updateSelectionUi(); applyFilter()
        }
    }
    private fun filterTabs() = applyFilter()
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
        modeButton.text = when (mode) { "vertical" -> "List"; "horizontal" -> "Carousel"; else -> "Grid" }
        tabsVerticalScroll.visibility = if (mode == "vertical") View.VISIBLE else View.GONE
        tabsHorizontalScroll.visibility = if (mode == "horizontal") View.VISIBLE else View.GONE
        tabsGridScroll.visibility = if (mode == "grid") View.VISIBLE else View.GONE
        val target = when(mode) { "horizontal" -> tabsHorizontal; "grid" -> tabsGrid; else -> tabsVertical }
        if (items.isEmpty()) {
            target.addView(TextView(this).apply { text = "No matching tabs"; setTextColor(PhormiUiColors.secondary(this@TabsOverviewActivity)); textSize = 16f; setPadding(16.dp(), 24.dp(), 16.dp(), 24.dp()) })
            return
        }
        if (mode == "horizontal") {
            tabsHorizontal.orientation = LinearLayout.HORIZONTAL
            items.forEach { target.addView(makeTab(it), LinearLayout.LayoutParams(260.dp(), -2).apply { setMargins(6.dp(), 8.dp(), 6.dp(), 8.dp()) }) }
        } else {
            val columns = if (mode == "grid" && resources.configuration.screenWidthDp >= 380 && resources.configuration.fontScale <= 1.3f) 2 else 1
            items.groupBy { it.groupName.ifBlank { "Ungrouped" } }.forEach { (group, members) ->
                target.addView(TextView(this).apply { text = group; textSize = 14f; setTextColor(PhormiUiColors.secondary(this@TabsOverviewActivity)); setPadding(8.dp(), 16.dp(), 8.dp(), 8.dp()) })
                members.chunked(columns).forEach { pair ->
                    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    pair.forEach { row.addView(makeTab(it), LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(4.dp(), 4.dp(), 4.dp(), 4.dp()) }) }
                    if (columns == 2 && pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
                    target.addView(row, LinearLayout.LayoutParams(-1, -2))
                }
            }
        }
    }

    private fun makeTab(item: TabInfo): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(12.dp(), 12.dp(), 12.dp(), 8.dp())
            background = android.graphics.drawable.GradientDrawable().apply { setColor(PhormiUiColors.surface(this@TabsOverviewActivity)); cornerRadius = 16.dp().toFloat() }
        }
        val open = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; minimumHeight = 88.dp(); isClickable = true; isFocusable = true
            contentDescription = if (selectionMode) "${if (item.id in selectedIds) "Deselect" else "Select"} ${item.title}" else "Open ${item.title}"
            setOnClickListener {
                if (selectionMode) {
                    if (item.id in selectedIds) selectedIds.remove(item.id) else selectedIds.add(item.id)
                    updateSelectionUi(); applyFilter()
                } else {
                    setResult(RESULT_OK, Intent().putExtra("action", "select").putExtra("tab_id", item.id)); finish()
                }
            }
            setOnLongClickListener {
                AlertDialog.Builder(this@TabsOverviewActivity).setTitle(item.title)
                    .setItems(arrayOf("Environment", "Assign to group", "Select multiple tabs")) { _, which ->
                        when (which) {
                            0 -> showEnvironmentChooser(item)
                            1 -> showGroupChooser(item)
                            else -> { selectionMode = true; selectedIds.add(item.id); updateSelectionUi(); applyFilter() }
                        }
                    }.show()
                true
            }
        }
        if (selectionMode) open.addView(TextView(this).apply {
            text = if (item.id in selectedIds) "☑ Selected" else "☐ Select"
            setTextColor(PhormiUiColors.accent(this@TabsOverviewActivity)); textSize = 16f
        })
        open.addView(TextView(this).apply { text = item.title; textSize = 16f; setTextColor(PhormiUiColors.text(this@TabsOverviewActivity)); maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END })
        open.addView(TextView(this).apply {
            text = (if (item.url == "about:blank") "New tab" else item.url) +
                (if (item.profile != "Default") "\nEnvironment: ${item.profile}" else "")
            textSize = 12f; setTextColor(PhormiUiColors.secondary(this@TabsOverviewActivity)); maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, 6.dp(), 0, 6.dp())
        })
        card.addView(open, LinearLayout.LayoutParams(-1, -2))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun action(label: String, description: String, click: () -> Unit) {
            actions.addView(TextView(this).apply { text = label; contentDescription = description; gravity = Gravity.CENTER; textSize = 14f
                setTextColor(PhormiUiColors.accent(this@TabsOverviewActivity)); isClickable = true; isFocusable = true; setOnClickListener { click() }
            }, LinearLayout.LayoutParams(0, 48.dp(), 1f))
        }
        action("Group", "Group ${item.title}") { showGroupChooser(item) }
        action("Close", "Close ${item.title}") { setResult(RESULT_OK, Intent().putExtra("action", "close").putExtra("tab_id", item.id)); finish() }
        card.addView(actions)
        return card
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
