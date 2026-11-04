package com.uong.phormi

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** Sectioned tab workspace. Commands are returned to the live browser, never dispatched twice. */
class TabsOverviewActivity : AppCompatActivity() {
    private val allTabs = mutableListOf<PhormiTabOverview.TabEntry>()
    private var visibleTabs = emptyList<PhormiTabOverview.TabEntry>()
    private var groups = emptyList<PhormiTabOverview.GroupInfo>()
    private var activeId = -1
    private lateinit var tabsVerticalScroll: View
    private lateinit var tabsHorizontalScroll: View
    private lateinit var tabsGridScroll: View
    private lateinit var tabsVertical: LinearLayout
    private lateinit var tabsHorizontal: LinearLayout
    private lateinit var tabsGrid: LinearLayout
    private lateinit var tabCount: TextView
    private lateinit var summary: TextView
    private lateinit var selectButton: TextView
    private lateinit var selectAllButton: TextView
    private lateinit var closeSelectedButton: TextView
    private lateinit var searchInput: EditText
    private var mode = "vertical"
    private var query = ""
    private var selectionMode = false
    private val selectedIds = linkedSetOf<Int>()

    companion object { private const val PREFS = "phormi_tabs"; private const val KEY_MODE = "tab_view_mode" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tabs_overview)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        mode = PhormiTabOverview.normalizeMode(prefs.getString(KEY_MODE, "vertical"))
        findViewById<View>(R.id.btn_close_overview).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_new_tab_overview).setOnClickListener { returnAction(Intent().putExtra("action", "new_tab")) }
        findViewById<View>(R.id.btn_split_overview).setOnClickListener { returnAction(Intent().putExtra("action", "toggle_split")) }
        findViewById<View>(R.id.btn_groups_overview).setOnClickListener { startActivity(Intent(this, TabGroupsActivity::class.java)) }
        mapOf("vertical" to R.id.btn_mode_list, "grid" to R.id.btn_mode_grid, "horizontal" to R.id.btn_mode_carousel).forEach { (value, id) ->
            findViewById<View>(id).setOnClickListener {
                mode = value
                prefs.edit().putString(KEY_MODE, mode).apply()
                applyFilter()
            }
        }
        tabsVerticalScroll = findViewById(R.id.tabs_vertical_scroll)
        tabsHorizontalScroll = findViewById(R.id.tabs_horizontal_scroll)
        tabsGridScroll = findViewById(R.id.tabs_grid_scroll)
        tabsVertical = findViewById(R.id.tabs_vertical)
        tabsHorizontal = findViewById(R.id.tabs_horizontal)
        tabsGrid = findViewById(R.id.tabs_grid)
        tabCount = findViewById(R.id.tab_count)
        summary = findViewById(R.id.overview_summary)
        selectButton = findViewById(R.id.btn_select_tabs)
        selectAllButton = findViewById(R.id.btn_select_all_tabs)
        closeSelectedButton = findViewById(R.id.btn_close_selected)
        searchInput = findViewById(R.id.search_tabs)

        selectButton.setOnClickListener {
            selectionMode = !selectionMode
            if (!selectionMode) selectedIds.clear()
            updateSelectionUi(); applyFilter()
        }
        selectAllButton.setOnClickListener {
            val visibleIds = visibleTabs.map { it.id }.toSet()
            if (visibleIds.isNotEmpty() && selectedIds.containsAll(visibleIds)) selectedIds.removeAll(visibleIds)
            else selectedIds.addAll(visibleIds)
            updateSelectionUi(); applyFilter()
        }
        closeSelectedButton.setOnClickListener { confirmCloseSelected() }
        findViewById<View>(R.id.btn_clear_tab_search).setOnClickListener { searchInput.setText("") }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                query = s?.toString().orEmpty()
                applyFilter()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        loadTabs(); updateSelectionUi(); applyFilter()
    }

    override fun onResume() {
        super.onResume()
        if (::tabsVertical.isInitialized) {
            loadTabs()
            selectedIds.retainAll(allTabs.map { it.id }.toSet())
            updateSelectionUi(); applyFilter()
        }
    }

    private fun returnAction(intent: Intent) { setResult(RESULT_OK, intent); finish() }

    private fun applyFilter() {
        visibleTabs = PhormiTabOverview.filter(allTabs, query)
        findViewById<View>(R.id.btn_clear_tab_search).visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
        renderTabs(visibleTabs)
        updateSelectionUi()
    }

    private fun updateSelectionUi() {
        selectButton.text = if (selectionMode) "Done" else "Select"
        selectButton.contentDescription = if (selectionMode) "Finish selecting tabs" else "Select multiple tabs"
        selectButton.isEnabled = allTabs.isNotEmpty()
        selectButton.alpha = if (allTabs.isEmpty()) 0.45f else 1f
        findViewById<View>(R.id.overview_layout_controls).visibility = if (selectionMode) View.GONE else View.VISIBLE
        findViewById<View>(R.id.overview_selection_bar).visibility = if (selectionMode) View.VISIBLE else View.GONE
        val visibleIds = visibleTabs.map { it.id }.toSet()
        val allVisibleSelected = visibleIds.isNotEmpty() && selectedIds.containsAll(visibleIds)
        selectAllButton.text = if (allVisibleSelected) "Clear all" else "Select all"
        selectAllButton.isEnabled = visibleTabs.isNotEmpty()
        findViewById<TextView>(R.id.selection_summary).text = "${selectedIds.size} selected"
        closeSelectedButton.text = "Close (${selectedIds.size})"
        closeSelectedButton.isEnabled = selectedIds.isNotEmpty()
        closeSelectedButton.alpha = if (selectedIds.isEmpty()) 0.45f else 1f
        val count = PhormiTabOverview.countLabel(allTabs.size)
        summary.text = when {
            query.isNotBlank() -> "${visibleTabs.size} of $count match"
            groups.isNotEmpty() -> "$count · ${groups.size} ${if (groups.size == 1) "group" else "groups"}"
            else -> "$count · organized by section"
        }
    }

    private fun confirmCloseSelected() {
        if (selectedIds.isEmpty()) return
        val count = selectedIds.size
        AlertDialog.Builder(this)
            .setTitle("Close $count ${if (count == 1) "tab" else "tabs"}?")
            .setMessage("The selected tabs will be removed from this browser session.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Close $count") { _, _ ->
                returnAction(Intent().putExtra("action", "close_many").putExtra("tab_ids", selectedIds.toIntArray()))
            }.show()
    }

    private fun renderTabs(items: List<PhormiTabOverview.TabEntry>) {
        listOf(tabsVertical, tabsHorizontal, tabsGrid).forEach { it.removeAllViews() }
        tabCount.text = allTabs.size.toString()
        tabCount.contentDescription = PhormiTabOverview.countLabel(allTabs.size)
        mapOf("vertical" to R.id.btn_mode_list, "grid" to R.id.btn_mode_grid, "horizontal" to R.id.btn_mode_carousel).forEach { (value, id) ->
            findViewById<TextView>(id).apply {
                val selected = mode == value
                isSelected = selected
                setTextColor(if (selected) PhormiUiColors.accent(this@TabsOverviewActivity) else PhormiUiColors.secondary(this@TabsOverviewActivity))
                background = GradientDrawable().apply {
                    setColor(if (selected) PhormiUiColors.accentSoft(this@TabsOverviewActivity) else android.graphics.Color.TRANSPARENT)
                    cornerRadius = dp(12).toFloat()
                }
            }
        }
        tabsVerticalScroll.visibility = if (mode == "vertical") View.VISIBLE else View.GONE
        tabsHorizontalScroll.visibility = if (mode == "horizontal") View.VISIBLE else View.GONE
        tabsGridScroll.visibility = if (mode == "grid") View.VISIBLE else View.GONE
        val target = when (mode) { "horizontal" -> tabsHorizontal; "grid" -> tabsGrid; else -> tabsVertical }
        if (items.isEmpty()) {
            target.addView(emptyState(), LinearLayout.LayoutParams(-1, -2))
            return
        }
        val sections = PhormiTabOverview.sections(items, activeId, groups)
        if (mode == "horizontal") {
            val width = dp(PhormiTabOverview.carouselWidth(resources.configuration.screenWidthDp))
            sections.forEach { section ->
                val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                column.addView(makeSectionHeader(section))
                section.tabs.forEach { column.addView(makeTab("horizontal", it), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }) }
                target.addView(column, LinearLayout.LayoutParams(width, -2).apply { marginEnd = dp(14) })
            }
            return
        }
        val columns = PhormiTabOverview.columns(mode, resources.configuration.screenWidthDp, resources.configuration.fontScale)
        sections.forEach { section ->
            target.addView(makeSectionHeader(section))
            section.tabs.chunked(columns).forEach { members ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                members.forEach { item ->
                    row.addView(makeTab(mode, item), LinearLayout.LayoutParams(0, -2, 1f).apply {
                        setMargins(0, 0, if (columns == 2) dp(8) else 0, dp(10))
                    })
                }
                if (columns == 2 && members.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
                target.addView(row, LinearLayout.LayoutParams(-1, -2))
            }
        }
    }

    private fun emptyState(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(24), dp(48), dp(24), dp(48))
        addView(TextView(this@TabsOverviewActivity).apply {
            text = if (query.isBlank()) "A fresh start" else "No matching tabs"
            textSize = 19f; setTextColor(PhormiUiColors.text(this@TabsOverviewActivity)); gravity = Gravity.CENTER
        })
        addView(TextView(this@TabsOverviewActivity).apply {
            text = if (query.isBlank()) "Open a new tab to start browsing." else "Try a site name, address, group, or environment."
            textSize = 14f; setTextColor(PhormiUiColors.secondary(this@TabsOverviewActivity)); gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        })
    }

    private fun makeSectionHeader(section: PhormiTabOverview.Section): View {
        val header = LayoutInflater.from(this).inflate(R.layout.item_overview_section, tabsVertical, false)
        header.tag = section.key
        header.findViewById<TextView>(R.id.section_title).text = section.title
        header.findViewById<TextView>(R.id.section_count).text = section.tabs.size.toString()
        header.findViewById<View>(R.id.section_dot).background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(when {
                section.key == "current" -> PhormiUiColors.accent(this@TabsOverviewActivity)
                section.key == "ghost" -> PhormiUiColors.coralText(this@TabsOverviewActivity)
                section.key == "new" -> PhormiUiColors.border(this@TabsOverviewActivity)
                else -> PhormiUiColors.secondary(this@TabsOverviewActivity)
            })
        }
        header.findViewById<TextView>(R.id.btn_manage_section).apply {
            visibility = if (section.groupId != null) View.VISIBLE else View.GONE
            contentDescription = "Edit group ${section.title}"
            setOnClickListener { startActivity(Intent(this@TabsOverviewActivity, TabGroupsActivity::class.java)) }
        }
        return header
    }

    private fun makeTab(layoutMode: String, item: PhormiTabOverview.TabEntry): View {
        val card = LayoutInflater.from(this).inflate(R.layout.item_overview_tab, tabsVertical, false)
        card.tag = "tab-card"
        val selected = item.id in selectedIds
        val active = item.id == activeId
        val title = PhormiTabOverview.displayTitle(item.title, item.url)
        card.background = GradientDrawable().apply {
            setColor(if (selected) PhormiUiColors.accentSoft(this@TabsOverviewActivity) else PhormiUiColors.surface(this@TabsOverviewActivity))
            setStroke(dp(if (active || selected) 2 else 1), if (active || selected) PhormiUiColors.accent(this@TabsOverviewActivity) else PhormiUiColors.border(this@TabsOverviewActivity))
            cornerRadius = dp(16).toFloat()
        }
        card.findViewById<TextView>(R.id.tab_card_monogram).text = PhormiTabOverview.monogram(item.url)
        card.findViewById<TextView>(R.id.tab_card_title).apply {
            text = title
            maxLines = if (layoutMode == "grid") 2 else 1
        }
        card.findViewById<TextView>(R.id.tab_card_address).text = PhormiTabOverview.address(item.url)
        val status = buildList {
            if (active) add("Current")
            if (item.ghost) add("Ghost")
            if (item.profile != PhormiEnvironmentManager.DEFAULT_ENVIRONMENT) add(item.profile)
            if (isEmpty()) add(if (PhormiTabOverview.isNewTab(item.url)) "Not started" else "Open website")
        }.joinToString(" · ")
        card.findViewById<TextView>(R.id.tab_card_status).text = status
        card.findViewById<TextView>(R.id.tab_card_check).apply {
            visibility = if (selectionMode) View.VISIBLE else View.GONE
            text = if (selected) "✓" else "○"
        }
        val open = card.findViewById<View>(R.id.tab_card_open)
        open.contentDescription = if (selectionMode) "${if (selected) "Deselect" else "Select"} $title" else "Open $title"
        open.setOnClickListener {
            if (selectionMode) {
                if (!selectedIds.add(item.id)) selectedIds.remove(item.id)
                updateSelectionUi(); applyFilter()
            } else returnAction(Intent().putExtra("action", "select").putExtra("tab_id", item.id))
        }
        open.setOnLongClickListener { showTabActions(item); true }
        card.findViewById<TextView>(R.id.btn_group_tab_card).apply {
            text = if (item.groupId.isBlank()) "Group" else "Move"
            contentDescription = "Group $title"
            setOnClickListener { showGroupChooser(item) }
        }
        card.findViewById<TextView>(R.id.btn_close_tab_card).apply {
            contentDescription = "Close $title"
            setOnClickListener { returnAction(Intent().putExtra("action", "close").putExtra("tab_id", item.id)) }
        }
        card.findViewById<TextView>(R.id.btn_more_tab_card).apply {
            contentDescription = "More actions for $title"
            setOnClickListener { showTabActions(item) }
        }
        return card
    }

    private fun showTabActions(item: PhormiTabOverview.TabEntry) {
        val title = PhormiTabOverview.displayTitle(item.title, item.url)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(content).setNegativeButton("Cancel", null).create()
        fun action(label: String, click: () -> Unit) {
            content.addView(android.widget.Button(this).apply {
                text = label; isAllCaps = false; gravity = Gravity.CENTER_VERTICAL
                setOnClickListener { dialog.dismiss(); click() }
            }, LinearLayout.LayoutParams(-1, dp(48)))
        }
        action(if (item.groupId.isBlank()) "Assign to a group" else "Move to another group") { showGroupChooser(item) }
        action("Change browsing environment") { showEnvironmentChooser(item) }
        action("Select multiple tabs") {
            selectionMode = true; selectedIds.add(item.id); updateSelectionUi(); applyFilter()
        }
        dialog.show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun loadTabs() {
        allTabs.clear()
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        runCatching {
            val urls = org.json.JSONArray(prefs.getString("tab_urls", "[]") ?: "[]")
            val titles = org.json.JSONArray(prefs.getString("tab_titles", "[]") ?: "[]")
            val profiles = org.json.JSONArray(prefs.getString("tab_profiles", "[]") ?: "[]")
            val ids = org.json.JSONArray(prefs.getString("tab_ids", "[]") ?: "[]")
            activeId = ids.optInt(prefs.getInt("active_index", 0).coerceAtLeast(0), -1)
            val managedGroups = TabGroupManager(this).list()
            groups = managedGroups.map { PhormiTabOverview.GroupInfo(it.id, it.name) }
            val groupByTab = managedGroups.flatMap { group -> group.tabIds.map { it to group } }.toMap()
            for (i in 0 until minOf(urls.length(), ids.length())) {
                val id = ids.optInt(i, i + 1)
                val group = groupByTab[id]
                allTabs.add(PhormiTabOverview.TabEntry(
                    id = id,
                    title = titles.optString(i, "New tab"),
                    url = urls.optString(i, PhormiTabOverview.NEW_TAB_URL),
                    profile = profiles.optString(i, PhormiEnvironmentManager.DEFAULT_ENVIRONMENT).ifBlank { PhormiEnvironmentManager.DEFAULT_ENVIRONMENT },
                    groupId = group?.id.orEmpty(), groupName = group?.name.orEmpty()
                ))
            }
        }
    }

    private fun showEnvironmentChooser(item: PhormiTabOverview.TabEntry) {
        val envs = (listOf(PhormiEnvironmentManager.DEFAULT_ENVIRONMENT) +
            PhormiEnvironmentManager.list().filter { it != "Ghost" && it != PhormiEnvironmentManager.DEFAULT_ENVIRONMENT }).distinct()
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        val dialog = AlertDialog.Builder(this).setTitle("Browsing environment").setView(content).setNegativeButton("Cancel", null).create()
        envs.forEach { env ->
            content.addView(android.widget.Button(this).apply {
                text = if (env == item.profile) "✓  $env" else env; isAllCaps = false
                setOnClickListener {
                    dialog.dismiss()
                    returnAction(Intent().putExtra("action", "reassign_env").putExtra("tab_id", item.id).putExtra("profile", env))
                }
            }, LinearLayout.LayoutParams(-1, dp(48)))
        }
        dialog.show()
    }

    private fun showGroupChooser(item: PhormiTabOverview.TabEntry) {
        val managed = TabGroupManager(this).list()
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        val dialog = AlertDialog.Builder(this).setTitle("Organize this tab").setView(content).setNegativeButton("Cancel", null).create()
        fun row(label: String, click: () -> Unit) {
            content.addView(android.widget.Button(this).apply {
                text = label; isAllCaps = false; setOnClickListener { dialog.dismiss(); click() }
            }, LinearLayout.LayoutParams(-1, dp(48)))
        }
        managed.forEach { group ->
            row(if (group.id == item.groupId) "✓  ${group.name}" else group.name) {
                returnAction(Intent().putExtra("action", "assign_group").putExtra("group_id", group.id).putExtra("tab_id", item.id).putExtra("url", item.url))
            }
        }
        if (item.groupId.isNotBlank()) row("Remove from group") {
            returnAction(Intent().putExtra("action", "unassign_group").putExtra("tab_id", item.id))
        }
        row("+  Create a group") {
            val input = EditText(this).apply { hint = "Group name"; setSingleLine(); setPadding(dp(4), dp(12), dp(4), dp(12)) }
            val box = LinearLayout(this).apply { setPadding(dp(20), 0, dp(20), 0); addView(input, LinearLayout.LayoutParams(-1, -2)) }
            AlertDialog.Builder(this).setTitle("New group").setView(box).setNegativeButton("Cancel", null)
                .setPositiveButton("Create and add") { _, _ ->
                    val group = TabGroupManager(this).create(input.text.toString())
                    returnAction(Intent().putExtra("action", "assign_group").putExtra("group_id", group.id).putExtra("tab_id", item.id).putExtra("url", item.url))
                }.show()
        }
        dialog.show()
    }
}
